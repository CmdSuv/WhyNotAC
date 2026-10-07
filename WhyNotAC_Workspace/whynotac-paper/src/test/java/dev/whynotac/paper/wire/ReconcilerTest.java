package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private static final long GRACE = 1_000;

    private final List<String> missing = new ArrayList<>();
    private final Reconciler<String> reconciler = new Reconciler<>((ta, a, tb, b) -> a.equals(b), (p, t) -> missing.add(p), GRACE);

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
    void capacityAndScanAreBounded() {
        for (int i = 0; i < Reconciler.CAPACITY + 10; i++) {
            reconciler.expect("t", "p" + i, 0);
        }
        assertEquals(Reconciler.CAPACITY, reconciler.pending());
        assertEquals(10, missing.size(), "oldest entries are reported, not kept forever");
        assertFalse(reconciler.match("t", "p" + (Reconciler.CAPACITY + 9), 1), "beyond the scan window");
    }
}
