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

    @Test
    void idsCarryTheReservedPrefix() {
        TransactionTracker tracker = new TransactionTracker();
        int id = tracker.allocate().orElseThrow();
        assertTrue(TransactionTracker.isWhyNotAcId(id));
        assertFalse(TransactionTracker.isWhyNotAcId(-1));
        assertFalse(TransactionTracker.isWhyNotAcId(0));
    }

    @Test
    void inOrderPongsMatchAndMeasureRoundTrip() {
        TransactionTracker tracker = new TransactionTracker();
        int a = tracker.allocate().orElseThrow();
        int b = tracker.allocate().orElseThrow();
        tracker.onSent(a, 0);
        tracker.onSent(b, 50 * MS);
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
    void answeringANewerTransactionReportsSkippedOnes() {
        TransactionTracker tracker = new TransactionTracker();
        int[] ids = new int[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = tracker.allocate().orElseThrow();
            tracker.onSent(ids[i], i * MS);
        }
        var matched = assertInstanceOf(TransactionTracker.PongResult.Matched.class, tracker.onPong(ids[2], 10 * MS));
        assertEquals(2, matched.skipped());
        // The skipped ones are gone: answering them later is "unknown", not a late match.
        assertInstanceOf(TransactionTracker.PongResult.Unknown.class, tracker.onPong(ids[0], 11 * MS));
        assertInstanceOf(TransactionTracker.PongResult.Unknown.class, tracker.onPong(ids[2], 12 * MS));
    }

    @Test
    void stopsAllocatingWhenTheClientStopsAnswering() {
        TransactionTracker tracker = new TransactionTracker();
        for (int i = 0; i < TransactionTracker.MAX_PENDING; i++) {
            tracker.onSent(tracker.allocate().orElseThrow(), i);
        }
        assertTrue(tracker.allocate().isEmpty());
        assertEquals(TransactionTracker.MAX_PENDING, tracker.snapshot(0).pending());
    }

    @Test
    void counterWrapNeverCollidesWithAPendingId() {
        TransactionTracker tracker = new TransactionTracker();
        Set<Integer> pending = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            int id = tracker.allocate().orElseThrow();
            assertTrue(pending.add(id), "duplicate live id " + Integer.toHexString(id));
            order.add(id);
            tracker.onSent(id, i);
            if (order.size() > 1000) {
                int oldest = order.removeFirst();
                tracker.onPong(oldest, i + 1);
                pending.remove(oldest);
            }
        }
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
        session.onOutbound(new ClientboundPacket.SetEntityMotion(7, dev.whynotac.math.vector.Vec3d.Y_AXIS), 1);
        session.onOutbound(new ClientboundPacket.Ping(b), 2);
        session.onInbound(new ServerboundPacket.Pong(-42), 10); // another plugin's ping: ignored by the tracker
        session.onInbound(new ServerboundPacket.Pong(b), 60 * MS);

        assertEquals(7, events.size());
        TimelineEvent.Acknowledged ack = assertInstanceOf(TimelineEvent.Acknowledged.class, events.get(5));
        assertEquals(b, ack.transactionId());
        assertEquals(1, ack.skipped());
        assertEquals(60 * MS - 2, ack.roundTripNanos());
        TimelineEvent.Anomaly anomaly = assertInstanceOf(TimelineEvent.Anomaly.class, events.get(6));
        assertEquals(TimelineEvent.Anomaly.Kind.SKIPPED_TRANSACTION, anomaly.kind());
        assertEquals(1, session.latency(60 * MS).skipped());
    }
}
