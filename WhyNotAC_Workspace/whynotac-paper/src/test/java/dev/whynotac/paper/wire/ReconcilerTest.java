package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static final long GRACE = 1_000;
    /** The production grace period: tests that depend on stale entries must use it, not 0. */
    private static final long PRODUCTION_GRACE = WireObserver.DEFAULT_GRACE_NANOS;

    /** Strings starting with '?' stand for packets one side could not translate: no content key. */
    private static final Reconciler.Key<String> KEY = (type, p) -> p.startsWith("?") ? null : p;

    private final List<String> missing = new ArrayList<>();
    private final Reconciler<String> reconciler = new Reconciler<>(KEY, (p, t) -> missing.add(p), GRACE);

    @Test
    void inOrderStreamsMatchCompletely() {
        for (String p : List.of("a", "b", "c")) {
            reconciler.expect("t", p, 0);
        }
        for (String p : List.of("a", "b", "c")) {
            assertTrue(reconciler.match("t", p, 10));
        }
        assertEquals(0, reconciler.pending());
        assertTrue(missing.isEmpty());
    }

    @Test
    void aHeldBackPacketIsADelayWithinTheGracePeriod() {
        reconciler.expect("t", "held", 0);
        reconciler.expect("t", "next", 0);
        assertTrue(reconciler.match("t", "next", GRACE - 1), "a later packet overtook it");
        assertTrue(missing.isEmpty(), "still within the grace period");
        assertTrue(reconciler.match("t", "held", GRACE - 1), "released late, matched");
        assertTrue(missing.isEmpty());
    }

    @Test
    void aPacketTheStreamMovedPastIsMissingAfterTheGracePeriod() {
        reconciler.expect("t", "cancelled", 0);
        reconciler.expect("t", "next", 0);
        assertTrue(reconciler.match("t", "next", 5));
        reconciler.expire(GRACE - 1);
        assertTrue(missing.isEmpty());
        reconciler.expire(GRACE);
        assertEquals(List.of("cancelled"), missing);
    }

    @Test
    void nothingIsMissingWhileTheStreamHasNotMovedOn() {
        reconciler.expect("t", "queued", 0);
        reconciler.expire(GRACE * 100);
        assertTrue(missing.isEmpty(), "everything after it is still pending too (a plugin buffers the whole stream)");
        assertFalse(reconciler.match("t", "unannounced", GRACE * 100), "never announced: exists on one side only");
    }

    @Test
    void equalPacketsMatchOldestFirst() {
        reconciler.expect("t", "same", 0);
        reconciler.expect("t", "other", 1);
        reconciler.expect("t", "same", 2);
        assertTrue(reconciler.match("t", "same", 3));
        assertTrue(reconciler.match("t", "other", 3));
        assertTrue(reconciler.match("t", "same", 3));
        assertEquals(0, reconciler.pending());
        assertTrue(missing.isEmpty());
    }

    @Test
    void theTypeIsPartOfTheKey() {
        reconciler.expect("a", "x", 0);
        assertFalse(reconciler.match("b", "x", 0), "same content, different packet type");
        assertTrue(reconciler.match("a", "x", 0));
    }

    @Test
    void staleEntriesNeverHideALaterMatchEvenOfTheSameType() {
        // Regression (review of the per-type reconciler): a plugin below WhyNotAC cancels hundreds of
        // motion packets for other entities within the 2 s grace period. They are all pending, and with a
        // scan window the player's own, unchanged knockback was not found among them: a false
        // "injected below the game" divergence. With the content index it matches at once.
        List<String> reported = new ArrayList<>();
        Reconciler<String> r = new Reconciler<>(KEY, (p, t) -> reported.add(p), PRODUCTION_GRACE);
        for (int i = 0; i < 1_000; i++) {
            r.expect("set_entity_motion", "other-entity-" + i, i);
        }
        r.expect("set_entity_motion", "self-knockback", 1_000);
        assertTrue(r.match("set_entity_motion", "self-knockback", 1_001));
        assertTrue(reported.isEmpty(), "within the grace period nothing is reported yet");
        r.expire(PRODUCTION_GRACE + 1_001);
        assertEquals(1_000, reported.size(), "the cancelled packets are reported once the grace period is over");
        assertTrue(reported.stream().allMatch(p -> p.startsWith("other-entity-")), "the real packet never is");
    }

    @Test
    void staleEntriesOfOneTypeNeverBlockMatchesOfAnother() {
        List<String> stale = new ArrayList<>();
        Reconciler<String> r = new Reconciler<>(KEY, (p, t) -> stale.add(p), GRACE);
        for (int i = 0; i < 300; i++) {
            r.expect("skipped", "s" + i, 0);
        }
        for (int i = 0; i < 1_000; i++) {
            r.expect("move", "m" + i, i);
            assertTrue(r.match("move", "m" + i, i), "move " + i);
        }
        r.expire(GRACE + 1_000);
        assertEquals(300, stale.size(), "the dropped flood is reported once the stream has moved past it");
        assertTrue(stale.stream().allMatch(p -> p.startsWith("s")), "no real packet was ever reported");
        assertEquals(0, r.pending());
    }

    @Test
    void untranslatablePacketsMatchByTypeAlone() {
        reconciler.expect("t", "?reference-failed", 0);
        assertTrue(reconciler.match("t", "anything", 1), "the reference side could not translate it: the type decides");
        reconciler.expect("t", "a", 2);
        reconciler.expect("t", "b", 3);
        assertTrue(reconciler.match("t", "?matching-side-failed", 4), "the matching side could not translate it");
        assertTrue(reconciler.match("t", "b", 5), "it took the oldest of its type (a), b is still there");
        assertFalse(reconciler.match("t", "a", 5));
        assertTrue(missing.isEmpty());
    }

    @Test
    void anUntranslatableEntryIsOnlyTakenWhenItIsOlderThanTheExactMatch() {
        reconciler.expect("t", "x", 0);
        reconciler.expect("t", "?failed", 1);
        assertTrue(reconciler.match("t", "x", 2), "the exact match is older");
        assertTrue(reconciler.match("t", "y", 2), "now the untranslatable entry takes y");
        assertEquals(0, reconciler.pending());
    }

    @Test
    void capacityIsBoundedAndTheNewestEntryIsStillFound() {
        for (int i = 0; i < Reconciler.CAPACITY + 10; i++) {
            reconciler.expect("t", "p" + i, 0);
        }
        assertEquals(Reconciler.CAPACITY, reconciler.pending());
        assertEquals(10, missing.size(), "oldest entries are reported, not kept forever");
        assertTrue(reconciler.match("t", "p" + (Reconciler.CAPACITY + 9), 1), "no scan window: found among 4096 pending");
    }

    @Test
    void matchedEntriesBehindAStaleOneDoNotAccumulate() {
        Reconciler<String> r = new Reconciler<>(KEY, (p, t) -> { }, PRODUCTION_GRACE);
        r.expect("t", "stale", 0); // never matched: keeps the head of the order for the whole grace period
        for (int i = 0; i < 100_000; i++) {
            r.expect("t", "m" + i, 1);
            assertTrue(r.match("t", "m" + i, 1));
        }
        assertEquals(1, r.pending());
        assertTrue(r.retained() <= 2 + 1_024 + 1, "matched entries are compacted away: " + r.retained());
    }

    @Test
    void inFlightEntriesSurviveTheLossOfAViewAndPassedOnesAreReported() {
        Reconciler<String> r = new Reconciler<>(KEY, (p, t) -> missing.add(p), PRODUCTION_GRACE);
        r.expect("swing", "swing", 0);      // cancelled before the game
        r.expect("move", "m1", 1);
        assertTrue(r.match("move", "m1", 2)); // the stream moved past the swing
        r.expect("swing", "swing", 3);      // in flight when the view is lost
        r.expect("tick_end", "tick", 4);    // in flight
        List<Reconciler.InFlight<String>> inFlight = r.drainInFlight(5);
        assertEquals(List.of("swing"), missing, "the cancelled swing is reported now, before it could absorb a new one");
        assertEquals(List.of("swing", "tick"), inFlight.stream().map(Reconciler.InFlight::packet).toList());
        assertEquals(0, r.pending());
    }
}
