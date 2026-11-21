package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class TimerBalanceCheckTest {

    private final List<Evidence> evidence = new ArrayList<>();
    private final ServerClock clock = new ServerClock();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        seq = 0;
        now = 1_000_000_000L;
        clock.sample(1_000_000L, 1_000L);
        clock.sample(2_000_000_000L, 3_000L);
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new TimerBalanceCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {}
        }, clock));
        session = registry.openReplay("timer-balance-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.Bound(seq++, now, new UUID(4, 4), "timerTester", 1));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(1, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
    }

    @Test
    void steady20TpsAndSinglePauseRecoveryProduceZeroEvidence() {
        // Steady 20 TPS for 6 seconds
        for (int i = 0; i < 120; i++) {
            now += 50_000_000L;
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        }
        // Single 800 ms lag freeze followed by catch-up
        now += 800_000_000L;
        for (int i = 0; i < 10; i++) {
            now += 5_000_000L;
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        }
        assertTrue(evidence.isEmpty());
    }

    @Test
    void detectsRepeatedOscillatingSlowFastBalanceTimer() {
        for (int cycle = 0; cycle < TimerBalanceCheck.REQUIRED_OSCILLATION_CYCLES; cycle++) {
            // Slow bucket (~11 ticks across 1.05 s)
            for (int t = 0; t < 11; t++) {
                now += 96_000_000L;
                session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
            }
            // Fast burst bucket (~28 ticks across 1.01 s)
            for (int t = 0; t < 28; t++) {
                now += 36_000_000L;
                session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
            }
        }
        assertEquals(1, evidence.size());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.get(0).evidenceClass());
        assertEquals("oscillating-timer-balance", evidence.get(0).details().get("reason"));
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(TimerBalanceCheck.VERSION, Checks.version(TimerBalanceCheck.ID));
        assertNotNull(Checks.create(TimerBalanceCheck.ID, (s, e) -> {}));
        assertFalse(new TimerBalanceCheck((s, e) -> {}).readsEntityMotion());
    }
}
