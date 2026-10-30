package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.evidence.ServerClock;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientClockCheckTest {

    private static final long MS = 1_000_000L;
    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private final ServerClock clock = new ServerClock();
    private PlayerSession session;
    private long seq;
    private int pingId = 1;

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new ClientClockCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, clock));
        session = registry.openReplay("test");
        session.deliver(new TimelineEvent.Observation(seq++, 0, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        session.deliver(new ClientFact(seq++, 0, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        session.deliver(new ClientFact(seq++, 0, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, 0, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, 0, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
    }

    private void tickEnd(long nanos) {
        session.deliver(new TimelineEvent.Inbound(seq++, nanos, 0, new ServerboundPacket.ClientTickEnd()));
    }

    /** A transaction sent at {@code sent} and answered at {@code answered} (server clock). */
    private void transaction(long sent, long answered) {
        int id = pingId++;
        session.deliver(new TimelineEvent.Outbound(seq++, sent, new ClientboundPacket.Ping(id)));
        session.deliver(new TimelineEvent.Inbound(seq++, answered, 0, new ServerboundPacket.Pong(id)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, answered, id, sent, 0));
    }

    /**
     * A client as in {@code Minecraft.runTick}: each frame advances the tick timer (whole ticks of
     * {@code tickMs} since the previous frame, the fraction kept), drains every packet that has arrived,
     * also those arriving while it drains (answering transactions), then runs at most 10 of the counted
     * ticks and drops the rest. With {@code stalls}, drains are sometimes long (a burst of chunks on a slow
     * machine) and frames sometimes freeze. The server sends a transaction every 50 ms; one-way delays are
     * jittered around {@code rttMs / 2} and each direction keeps its order (TCP).
     */
    private void play(double tickMs, int seconds, double rttMs, boolean stalls, long seed) {
        Random random = new Random(seed);
        double now = 0;
        double lastAdvance = 0;
        double residual = 0;
        long nextPing = 0;
        double lastArrival = 0; // at the client, server packets in order
        double lastReceive = 0; // at the server, client packets in order
        while (now < seconds * 1000.0) {
            residual += (now - lastAdvance) / tickMs;
            lastAdvance = now;
            int counted = (int) residual;
            residual -= counted;
            double drainEnd = now + (stalls && random.nextInt(60) == 0 ? 50 + random.nextInt(1200) : random.nextDouble());
            while (true) {
                double arrival = Math.max(lastArrival, nextPing + rttMs / 2 * (0.5 + random.nextDouble()));
                if (arrival > drainEnd) {
                    break;
                }
                lastArrival = arrival;
                double answered = Math.max(arrival, now);
                lastReceive = Math.max(lastReceive, answered + rttMs / 2 * (0.5 + random.nextDouble()));
                transaction(nextPing * MS, (long) Math.ceil(lastReceive * MS));
                nextPing += 50;
            }
            now = drainEnd;
            for (int i = 0; i < Math.min(10, counted); i++) {
                now += 0.2 + random.nextDouble() * 3;
                lastReceive = Math.max(lastReceive, now + rttMs / 2 * (0.5 + random.nextDouble()));
                tickEnd((long) Math.ceil(lastReceive * MS));
            }
            now += stalls && random.nextInt(200) == 0 ? 300 + random.nextInt(1500) : 2 + random.nextInt(15);
        }
    }

    @Test
    void aLongDrainBeforeTheAnswerIsWithinTheBound() {
        // Frame 1 counted 10 ticks after a stall, then drained a 500 ms burst of chunks and answered a
        // transaction sent near its end; frame 2 counted 10 more (the drain's time) and answered nothing;
        // frame 3 answered the next transaction. 20 ticks between two answers 50 ms apart, all vanilla.
        transaction(0, 1 * MS);
        transaction(980 * MS, 981 * MS);
        for (int i = 0; i < 20; i++) {
            tickEnd((982 + i) * MS);
        }
        transaction(1_030 * MS, 1_031 * MS);
        assertEquals(List.of(), evidence);
    }

    @Test
    void theWorstVanillaBurstIsWithinTheBound() {
        // A stall, then a frame that answers a transaction and runs its 10 computed ticks at once.
        transaction(0, 1_000 * MS);
        transaction(1_000 * MS, 1_000 * MS + 1);
        for (int i = 0; i < 10; i++) {
            tickEnd(1_000 * MS + 2);
        }
        transaction(1_000 * MS, 1_000 * MS + 3);
        assertEquals(List.of(), evidence);
    }

    @Test
    void aVanillaClientIsNeverAboveTheBound() {
        for (long seed = 0; seed < 20; seed++) {
            setUp();
            play(50.0, 120, 5 + seed * 15, seed % 2 == 0, seed);
            assertEquals(List.of(), evidence, "seed " + seed);
        }
    }

    @Test
    void aSlowedClientIsNeverAboveTheBound() {
        play(100.0, 60, 40, true, 7); // the server's tick rate below 20: the client ticks slower, never faster
        assertEquals(List.of(), evidence);
    }

    @Test
    void extraClientTickEndArePlainWithinTwoSeconds() {
        play(25.0, 2, 60, false, 3); // twice the ticks: hides a second movement packet per real tick
        assertTrue(evidence.size() >= 1);
        assertTrue(evidence.getFirst().toNanoTime() < 2_000 * MS, "found within the first 2 s: " + evidence.getFirst());
        Evidence e = evidence.getFirst();
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertTrue(Long.parseLong(e.details().get("ticks")) > Long.parseLong(e.details().get("allowedTicks")));
    }

    @Test
    void aSmallTimerIsCaughtGivenTime() {
        play(50.0 / 1.05, 60, 30, false, 5); // 5% faster
        assertTrue(evidence.size() >= 1);
    }

    @Test
    void nothingIsClaimedWhileTheServersClockRunsOffItsWallClock() {
        for (long s = 0; s <= 120; s++) {
            clock.sample(s * 1_000 * MS, s * 990); // VAL-3: the monotonic clock 1% faster than the wall clock
        }
        play(25.0, 2, 60, false, 3);
        assertEquals(List.of(), evidence);
        assertTrue(rejected.stream().anyMatch(a -> a.reasons().toString().contains("server's monotonic clock runs 1.0")), rejected.toString());
    }

    @Test
    void aTimerBelowTheDriftAllowanceIsNotClaimed() {
        play(50.0 / 1.005, 120, 30, false, 9); // 0.5%: within the 1% clock allowance
        assertEquals(List.of(), evidence);
    }
}
