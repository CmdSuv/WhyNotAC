package dev.whynotac.protocol.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionTrackerTest {

    private static final long MS = 1_000_000L;

    private static int sendOne(TransactionTracker tracker, long nanoTime) {
        int id = tracker.allocate(nanoTime).orElseThrow();
        assertTrue(tracker.onSent(id, nanoTime));
        return id;
    }

    @Test
    void idsAreUnpredictableAndAvoidTheRangeOtherPluginsUse() {
        TransactionTracker tracker = new TransactionTracker();
        Set<Integer> ids = new HashSet<>();
        int consecutive = 0;
        int previous = 0;
        for (int i = 0; i < 1_000; i++) {
            int id = sendOne(tracker, i);
            tracker.onPong(id, i + 1);
            assertFalse(TransactionTracker.isReservedForOthers(id));
            assertTrue(ids.add(id), "repeated id");
            if (i > 0 && id == previous + 1) {
                consecutive++;
            }
            previous = id;
        }
        assertTrue(consecutive < 5, "ids look sequential (" + consecutive + " consecutive pairs)");
        // Upper 16 bits are spread out: no fixed prefix a client could key on.
        Set<Integer> prefixes = new HashSet<>();
        ids.forEach(id -> prefixes.add(id >>> 16));
        assertTrue(prefixes.size() > 900, "prefixes " + prefixes.size());
    }

    @Test
    void foreignPingsAreNotTracked() {
        TransactionTracker tracker = new TransactionTracker();
        assertFalse(tracker.onSent(-7, 0), "another plugin's ping");
        assertInstanceOf(TransactionTracker.PongResult.Foreign.class, tracker.onPong(-7, 1));
        assertEquals(0, tracker.snapshot(1).sent());
    }

    @Test
    void inOrderPongsMatchAndMeasureRoundTrip() {
        TransactionTracker tracker = new TransactionTracker();
        int a = sendOne(tracker, 0);
        int b = sendOne(tracker, 50 * MS);
        var first = assertInstanceOf(TransactionTracker.PongResult.Matched.class, tracker.onPong(a, 80 * MS));
        assertEquals(0, first.skipped());
        tracker.onPong(b, 130 * MS);
        LatencySnapshot snapshot = tracker.snapshot(130 * MS);
        assertEquals(80.0, snapshot.lastRttMillis());
        assertEquals(80.0, snapshot.minRttMillis());
        assertEquals(0, snapshot.pending());
        assertEquals(2, snapshot.acknowledged());
    }

    @Test
    void skippedAndDuplicateAnswersAreRecognised() {
        TransactionTracker tracker = new TransactionTracker();
        int[] ids = new int[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = sendOne(tracker, i * MS);
        }
        var matched = assertInstanceOf(TransactionTracker.PongResult.Matched.class, tracker.onPong(ids[2], 10 * MS));
        assertEquals(2, matched.skipped());
        // Answering a skipped one later, or the same one twice, is impossible for a vanilla client.
        assertInstanceOf(TransactionTracker.PongResult.Duplicate.class, tracker.onPong(ids[0], 11 * MS));
        assertInstanceOf(TransactionTracker.PongResult.Duplicate.class, tracker.onPong(ids[2], 12 * MS));
        assertEquals(2, tracker.snapshot(12 * MS).duplicates());
    }

    @Test
    void stopsAllocatingAndFlagsWithholdingWhenTheClientStopsAnswering() {
        TransactionTracker tracker = new TransactionTracker();
        int first = 0;
        for (int i = 0; i < TransactionTracker.MAX_PENDING; i++) {
            int id = sendOne(tracker, i);
            if (i == 0) {
                first = id;
            }
        }
        assertTrue(tracker.allocate(TransactionTracker.MAX_PENDING).isEmpty());
        assertTrue(tracker.isWithheld());
        assertTrue(tracker.snapshot(0).withheld());
        tracker.onPong(first, 5_000);
        assertFalse(tracker.isWithheld(), "answering again lifts the condition");
        assertTrue(tracker.allocate(5_001).isPresent());
    }

    @Test
    void pingsThatNeverLeftTheServerAreNeverHeldAgainstTheClient() {
        TransactionTracker tracker = new TransactionTracker();
        // A plugin cancels every WhyNotAC ping: ids are allocated but never seen leaving the server.
        for (int i = 0; i < TransactionTracker.MAX_PENDING + 100; i++) {
            tracker.allocate(i * MS);
        }
        assertFalse(tracker.isWithheld(), "a server-side backlog is not the client withholding");
        assertFalse(tracker.snapshot(0).withheld());
        // Five seconds later the stale allocations expire and are counted as lost before the wire.
        long later = TransactionTracker.UNSENT_EXPIRY_NANOS + TransactionTracker.MAX_PENDING * MS;
        assertTrue(tracker.allocate(later).isPresent());
        assertEquals(TransactionTracker.MAX_PENDING, tracker.snapshot(later).lostBeforeWire());
        assertEquals(0, tracker.snapshot(later).sent());
    }

    @Test
    void jitterTracksVariationNotLevel() {
        LatencyEstimator steady = new LatencyEstimator();
        LatencyEstimator spiky = new LatencyEstimator();
        for (int i = 0; i < 400; i++) {
            steady.addSample(150 * MS);
            spiky.addSample((i % 2 == 0 ? 40 : 90) * MS);
        }
        assertEquals(0.0, steady.jitterMillis());
        assertEquals(150.0, steady.smoothedMillis(), 1e-9);
        assertTrue(spiky.jitterMillis() > 45.0 && spiky.jitterMillis() <= 50.0, "jitter " + spiky.jitterMillis());
    }

    @Test
    void sessionTurnsTransactionsIntoTimelineEvents() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = new ArrayList<>();
        registry.addListener((s, e) -> events.add(e));
        PlayerSession session = registry.open("x");
        int a = session.allocateTransaction().orElseThrow();
        int b = session.allocateTransaction().orElseThrow();
        session.onOutbound(new ClientboundPacket.Ping(a), 0);
        session.onOutbound(ClientboundPacket.SetEntityMotion.received(7, dev.whynotac.math.vector.Vec3d.Y_AXIS), 1);
        session.onOutbound(new ClientboundPacket.Ping(b), 2);
        session.onInbound(new ServerboundPacket.Pong(-42), 10); // another plugin's ping: ignored by the tracker
        session.onInbound(new ServerboundPacket.Pong(b), 60 * MS);
        session.onInbound(new ServerboundPacket.Pong(b), 61 * MS); // answered twice

        assertEquals(9, events.size());
        TimelineEvent.Acknowledged ack = assertInstanceOf(TimelineEvent.Acknowledged.class, events.get(5));
        assertEquals(b, ack.transactionId());
        assertEquals(1, ack.skipped());
        assertEquals(60 * MS - 2, ack.roundTripNanos());
        assertEquals(TimelineEvent.Anomaly.Kind.SKIPPED_TRANSACTION, assertInstanceOf(TimelineEvent.Anomaly.class, events.get(6)).kind());
        assertEquals(TimelineEvent.Anomaly.Kind.DUPLICATE_TRANSACTION, assertInstanceOf(TimelineEvent.Anomaly.class, events.get(8)).kind());
        assertEquals(1, session.latency(60 * MS).skipped());
    }

    @Test
    void sessionReportsWithholdingOnce() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1 << 16, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = new ArrayList<>();
        registry.addListener((s, e) -> events.add(e));
        PlayerSession session = registry.open("x");
        for (int i = 0; i < TransactionTracker.MAX_PENDING + 50; i++) {
            session.allocateTransaction().ifPresent(id -> session.onOutbound(new ClientboundPacket.Ping(id), 0));
        }
        long withheld = events.stream().filter(e -> e instanceof TimelineEvent.Anomaly a && a.kind() == TimelineEvent.Anomaly.Kind.TRANSACTIONS_WITHHELD).count();
        assertEquals(1, withheld);
    }

    @Test
    void manyInFlightAnsweredInOrderAfterTheQueueWrappedAndGrew() {
        TransactionTracker tracker = new TransactionTracker();
        List<Integer> ids = new ArrayList<>();
        List<Long> sentAt = new ArrayList<>();
        long now = 0;
        int answered = 0;
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 7 + 9 * round; i++) { // more in flight every round: the queue grows while it has wrapped
                now += MS;
                ids.add(sendOne(tracker, now));
                sentAt.add(now);
            }
            for (int i = 0; i < 5 + round; i++, answered++) {
                now += MS;
                var matched = assertInstanceOf(TransactionTracker.PongResult.Matched.class, tracker.onPong(ids.get(answered), now));
                assertEquals(sentAt.get(answered), matched.sentNanoTime());
                assertEquals(0, matched.skipped());
            }
        }
        assertEquals(ids.size() - answered, tracker.snapshot(now).pending());
        assertEquals(now - sentAt.get(answered), tracker.oldestPendingAgeNanos(now));
        // the last one answers every older one: they count as skipped, then as duplicates
        var last = assertInstanceOf(TransactionTracker.PongResult.Matched.class, tracker.onPong(ids.getLast(), now + MS));
        assertEquals(ids.size() - answered - 1, last.skipped());
        assertEquals(sentAt.getLast(), last.sentNanoTime());
        assertInstanceOf(TransactionTracker.PongResult.Duplicate.class, tracker.onPong(ids.get(answered), now + 2 * MS));
        assertEquals(0, tracker.snapshot(now).pending());
    }
}
