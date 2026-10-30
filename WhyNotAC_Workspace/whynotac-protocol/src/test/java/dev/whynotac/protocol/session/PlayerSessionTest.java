package dev.whynotac.protocol.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlayerSessionTest {

    private static final ServerboundPacket MOVE = new ServerboundPacket.MovePlayer(1, 2, 3, 4, 5, true, true, true, false);

    /** Executor that only runs tasks when asked: lets a test hold analysis back to force overflow. */
    private static final class ManualExecutor implements java.util.concurrent.Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private static List<TimelineEvent> record(SessionRegistry registry) {
        List<TimelineEvent> events = Collections.synchronizedList(new ArrayList<>());
        registry.addListener((session, event) -> events.add(event));
        return events;
    }

    @Test
    void attachmentsAreMadeOncePerSessionAndKey() {
        PlayerSession.AttachmentKey first = new PlayerSession.AttachmentKey();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        PlayerSession one = registry.open("127.0.0.1");
        PlayerSession two = registry.open("127.0.0.1");
        PlayerSession.AttachmentKey later = new PlayerSession.AttachmentKey(); // made after the sessions: their slots widen
        AtomicInteger made = new AtomicInteger();
        assertEquals(1, (int) one.attachment(first, made::incrementAndGet));
        assertEquals(1, (int) one.attachment(first, made::incrementAndGet));
        assertEquals(2, (int) one.attachment(later, made::incrementAndGet));
        assertEquals(3, (int) two.attachment(later, made::incrementAndGet));
        assertEquals(4, (int) two.attachment(first, made::incrementAndGet));
        assertEquals(1, (int) one.attachment(first, made::incrementAndGet));
        assertEquals(2, (int) one.attachment(later, made::incrementAndGet));
    }

    @Test
    void entityMotionReachesOnlyTheListenersThatReadIt() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        List<String> seen = new ArrayList<>();
        registry.addListener((session, event) -> seen.add("reader " + event.getClass().getSimpleName()));
        registry.addListener(new TimelineListener() {
            @Override
            public void onEvent(PlayerSession session, TimelineEvent event) {
                seen.add("other " + event.getClass().getSimpleName());
            }

            @Override
            public boolean readsEntityMotion() {
                return false;
            }
        });
        PlayerSession session = registry.open("127.0.0.1");
        session.onOutbound(new ClientboundPacket.MoveEntity(7, (short) 1, (short) 0, (short) 0, (byte) 0, (byte) 0, true, true, false), 100);
        session.onOutbound(new ClientboundPacket.RotateHead(7, (byte) 3), 101);
        session.onOutbound(new ClientboundPacket.Ping(5), 102);
        session.onInbound(MOVE, 103);
        assertEquals(List.of("reader Outbound", "reader Outbound", "reader Outbound", "other Outbound", "reader Inbound", "other Inbound"),
                seen.subList(seen.size() - 6, seen.size()));
        assertTrue(TimelineListener.entityMotion(new TimelineEvent.Outbound(0, 0,
                new ClientboundPacket.SetEntityMotion(7, null, null))));
        assertFalse(TimelineListener.entityMotion(new TimelineEvent.Outbound(0, 0, new ClientboundPacket.Ping(1))));
    }

    @Test
    void eventsArriveInOrderWithClientTickIndex() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = record(registry);
        PlayerSession session = registry.open("127.0.0.1");
        session.onInbound(MOVE, 100);
        session.onInbound(new ServerboundPacket.ClientTickEnd(), 101);
        session.onInbound(MOVE, 150);
        session.onInbound(new ServerboundPacket.ClientTickEnd(), 151);
        assertEquals(4, events.size());
        assertEquals(0, ((TimelineEvent.Inbound) events.get(0)).clientTick());
        assertEquals(0, ((TimelineEvent.Inbound) events.get(1)).clientTick(), "the tick-end packet belongs to the tick it closes");
        assertEquals(1, ((TimelineEvent.Inbound) events.get(2)).clientTick());
        assertEquals(2, session.clientTicks());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i, events.get(i).sequence());
        }
    }

    @Test
    void unmodeledOutboundTrafficIsCountedButNotQueued() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = record(registry);
        PlayerSession session = registry.open("x");
        session.onOutbound(new ClientboundPacket.Unmodeled("ClientboundLevelChunkWithLightPacket"), 1);
        session.onOutbound(new ClientboundPacket.KeepAlive(7), 2);
        assertEquals(1, events.size());
        assertEquals(1, session.counters().outboundUnmodeled());
    }

    @Test
    void nonFiniteMovementIsReportedAsAnomaly() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = record(registry);
        registry.open("x").onInbound(new ServerboundPacket.MovePlayer(Double.NaN, 0, 0, 0, 0, true, false, false, false), 5);
        TimelineEvent.Anomaly anomaly = assertInstanceOf(TimelineEvent.Anomaly.class, events.get(1));
        assertEquals(TimelineEvent.Anomaly.Kind.NON_FINITE_MOVEMENT, anomaly.kind());
    }

    @Test
    void absentComponentsAreNotMistakenForNonFinite() {
        // A Rot packet carries NaN position placeholders; that is absence, not an anomaly.
        ServerboundPacket.MovePlayer rot = new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 10, 20, false, true, true, false);
        assertEquals(ServerboundPacket.MovePlayer.Shape.ROT, rot.shape());
        assertTrue(!rot.hasNonFiniteValue());
    }

    @Test
    void overflowBecomesAnExplicitGapAndNumberingStaysMonotonic() {
        ManualExecutor executor = new ManualExecutor();
        SessionRegistry registry = new SessionRegistry(executor, 8, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = record(registry);
        PlayerSession session = registry.open("x");
        for (int i = 0; i < 20; i++) {
            session.onInbound(MOVE, i);
        }
        executor.runAll();
        // Capacity 8 while analysis is stalled: 8 queued, 12 dropped.
        assertEquals(8, events.size());
        session.onInbound(MOVE, 100);
        executor.runAll();
        TimelineEvent.Gap gap = assertInstanceOf(TimelineEvent.Gap.class, events.get(8));
        assertEquals(12, gap.droppedEvents());
        assertEquals(12, session.counters().dropped());
        assertInstanceOf(TimelineEvent.Inbound.class, events.get(9));
        long previous = -1;
        for (TimelineEvent event : events) {
            assertTrue(event.sequence() > previous, "sequence went backwards at " + event);
            previous = event.sequence();
        }
        assertEquals(20, gap.sequence(), "dropped events 8..19 consumed their sequence numbers");
    }

    @Test
    void disconnectIsDeliveredEvenWhenTheMailboxIsFull() {
        ManualExecutor executor = new ManualExecutor();
        SessionRegistry registry = new SessionRegistry(executor, 4, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = record(registry);
        PlayerSession session = registry.open("x");
        session.bind(UUID.randomUUID(), "Steve", 42, 0);
        for (int i = 0; i < 10; i++) {
            session.onInbound(MOVE, i);
        }
        session.disconnect(99);
        session.onInbound(MOVE, 100); // ignored after close
        executor.runAll();
        assertInstanceOf(TimelineEvent.Gap.class, events.get(events.size() - 2));
        assertInstanceOf(TimelineEvent.Disconnected.class, events.getLast());
        assertTrue(registry.sessions().isEmpty());
        assertTrue(registry.byPlayer(session.identity().playerId()).isEmpty());
    }

    @Test
    void listenerFailureIsIsolated() {
        AtomicReference<Throwable> reported = new AtomicReference<>();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> reported.set(t));
        registry.addListener((s, e) -> { throw new IllegalStateException("broken check"); });
        List<TimelineEvent> events = record(registry);
        registry.open("x").onInbound(MOVE, 1);
        assertEquals(1, events.size());
        assertInstanceOf(IllegalStateException.class, reported.get());
    }

    @Test
    void replaySessionsReproduceRecordedEventsVerbatim() {
        SessionRegistry live = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> recorded = record(live);
        PlayerSession session = live.open("x");
        UUID id = UUID.randomUUID();
        session.bind(id, "Alex", 3, 10);
        session.onInbound(MOVE, 20);
        session.onInbound(new ServerboundPacket.ClientTickEnd(), 21);
        session.onInbound(MOVE, 70);
        session.disconnect(80);

        SessionRegistry replayRegistry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> replayed = record(replayRegistry);
        PlayerSession replay = replayRegistry.openReplay("recording.wnrec");
        recorded.forEach(replay::deliver);
        assertEquals(recorded, replayed);
        assertEquals(id, replay.identity().playerId());
        assertEquals(1, replay.clientTicks());
        assertTrue(replay.isReplay() && replay.isClosed());
    }

    @Test
    void replaySessionsRejectLiveCallsAndOutOfOrderEvents() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, t) -> { throw new AssertionError(t); });
        PlayerSession replay = registry.openReplay("r");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> replay.onInbound(MOVE, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, replay::allocateTransaction);
        replay.deliver(new TimelineEvent.Inbound(5, 1, 0, MOVE));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> replay.deliver(new TimelineEvent.Inbound(5, 2, 0, MOVE)));
        PlayerSession live = registry.open("l");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> live.deliver(new TimelineEvent.Inbound(0, 1, 0, MOVE)));
    }

    @Test
    void executorShutdownNeverStrandsQueuedEvents() throws InterruptedException {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        SessionRegistry registry = new SessionRegistry(pool, 1 << 20, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = record(registry);
        PlayerSession session = registry.open("x");
        for (int i = 0; i < 10_000; i++) { // many batches: the drain has to reschedule itself repeatedly
            session.onInbound(MOVE, i);
        }
        session.disconnect(10_001);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        session.onInbound(MOVE, 10_002); // ignored: closed
        assertEquals(10_001, events.size());
        assertInstanceOf(TimelineEvent.Disconnected.class, events.getLast());
        assertTrue(session.isFullyDelivered());
    }

    @Test
    void serverWideBudgetBoundsMemoryAcrossManyConnections() {
        ManualExecutor executor = new ManualExecutor(); // analysis stalled
        SessionRegistry registry = new SessionRegistry(executor, 1_000, 50, (s, e, t) -> { throw new AssertionError(t); });
        List<TimelineEvent> events = record(registry);
        List<PlayerSession> sessions = new ArrayList<>();
        for (int c = 0; c < 20; c++) {
            PlayerSession session = registry.open("bot-" + c);
            sessions.add(session);
            for (int i = 0; i < 10; i++) {
                session.onInbound(MOVE, i);
            }
        }
        assertTrue(registry.queuedEvents() <= 50, "queued " + registry.queuedEvents());
        long dropped = sessions.stream().mapToLong(s -> s.counters().dropped()).sum();
        assertEquals(200 - 50, dropped);
        executor.runAll();
        assertEquals(0, registry.queuedEvents());
        assertEquals(50, events.size());
    }

    @Test
    void concurrentProducersYieldSerialGaplessDelivery() throws InterruptedException {
        ExecutorService analysis = Executors.newFixedThreadPool(8);
        SessionRegistry registry = new SessionRegistry(analysis, 1 << 20, (s, e, t) -> { throw new AssertionError(t); });
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        int producers = 4;
        int perProducer = 50_000;
        registry.addListener((session, event) -> {
            if (inFlight.incrementAndGet() != 1) {
                overlaps.incrementAndGet();
            }
            sequences.add(event.sequence());
            inFlight.decrementAndGet();
            if (event instanceof TimelineEvent.Disconnected) {
                done.countDown();
            }
        });
        PlayerSession session = registry.open("x");
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            threads[p] = Thread.ofPlatform().start(() -> {
                for (int i = 0; i < perProducer; i++) {
                    session.onInbound(MOVE, System.nanoTime());
                }
            });
        }
        for (Thread thread : threads) {
            thread.join();
        }
        session.disconnect(System.nanoTime());
        assertTrue(done.await(30, TimeUnit.SECONDS));
        analysis.shutdown();
        assertEquals(0, overlaps.get(), "listener calls for one session overlapped");
        assertEquals(producers * perProducer + 1, sequences.size());
        for (int i = 0; i < sequences.size(); i++) {
            assertEquals(i, sequences.get(i), "out of order at " + i);
        }
    }
}
