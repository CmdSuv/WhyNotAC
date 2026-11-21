package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
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

final class PingSpoofCheckTest {

    private final List<Evidence> evidence = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        seq = 0;
        now = 1_000_000_000L;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new PingSpoofCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {}
        }));
        session = registry.openReplay("ping-spoof-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "773", Trust.FORMAT_BOUND, "ViaVersion"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "viaversion", Trust.FORMAT_BOUND, "ViaVersion"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.Bound(seq++, now, new UUID(5, 5), "spoofTester", 1));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(1, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
    }

    @Test
    void symmetricNetworkRttBetweenKeepAliveAndPingProducesZeroEvidence() {
        for (int i = 0; i < 15; i++) {
            long kaSent = now;
            session.deliver(new TimelineEvent.Outbound(seq++, kaSent, new ClientboundPacket.KeepAlive(i)));
            now += 35_000_000L; // 35 ms KA RTT
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.KeepAlive(i)));

            long pingSent = now;
            session.deliver(new TimelineEvent.Outbound(seq++, pingSent, new ClientboundPacket.Ping(i)));
            now += 50_000_000L; // 50 ms Ping RTT (15 ms frame delta)
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(i)));
            session.deliver(new TimelineEvent.Acknowledged(seq++, now, i, pingSent, 0));
        }
        assertTrue(evidence.isEmpty());
    }

    @Test
    void detectsSustainedBacktrackPingRttInflationEvenThroughTranslator() {
        long kaSent = now;
        session.deliver(new TimelineEvent.Outbound(seq++, kaSent, new ClientboundPacket.KeepAlive(1L)));
        now += 30_000_000L; // Real network RTT = 30 ms
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.KeepAlive(1L)));

        for (int i = 0; i < PingSpoofCheck.REQUIRED_INFLATED_SAMPLES; i++) {
            long pingSent = now;
            session.deliver(new TimelineEvent.Outbound(seq++, pingSent, new ClientboundPacket.Ping(100 + i)));
            for (int t = 0; t < 5; t++) {
                now += 50_000_000L;
                session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
            }
            // Ping answered at 250 ms while KA RTT is 30 ms (+220 ms inflation)
            session.deliver(new TimelineEvent.Inbound(seq++, pingSent + 250_000_000L, 0, new ServerboundPacket.Pong(100 + i)));
            session.deliver(new TimelineEvent.Acknowledged(seq++, pingSent + 250_000_000L, 100 + i, pingSent, 0));
        }
        assertEquals(1, evidence.size());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.get(0).evidenceClass());
        assertEquals("sustained-ping-rtt-inflation", evidence.get(0).details().get("reason"));
    }

    @Test
    void detectsCombatRttDropAfterRepeatedBacktrackHoldOnAttackAndIgnoresSingleStallRecovery() {
        long kaSent = now;
        session.deliver(new TimelineEvent.Outbound(seq++, kaSent, new ClientboundPacket.KeepAlive(1L)));
        now += 25_000_000L; // Real network RTT = 25 ms
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.KeepAlive(1L)));

        // Cycle 1: Hold 5 Ping samples inflated at 260 ms then flush to 30 ms on attack (single event must not fire!)
        for (int cycle = 0; cycle < 2; cycle++) {
            if (cycle == 1) {
                // Separate cycles by > 500 ms with smooth 50 ms ticks
                for (int t = 0; t < 12; t++) {
                    now += 50_000_000L;
                    session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
                }
            }
            for (int i = 0; i < 5; i++) {
                long pingSent = now;
                int pid = 200 + cycle * 20 + i;
                session.deliver(new TimelineEvent.Outbound(seq++, pingSent, new ClientboundPacket.Ping(pid)));
                now += 50_000_000L;
                session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
                long ackTime = pingSent + 260_000_000L;
                session.deliver(new TimelineEvent.Inbound(seq++, ackTime, 0, new ServerboundPacket.Pong(pid)));
                session.deliver(new TimelineEvent.Acknowledged(seq++, ackTime, pid, pingSent, 0));
            }
            long dropPingSent = now;
            int dropPid = 250 + cycle;
            session.deliver(new TimelineEvent.Outbound(seq++, dropPingSent, new ClientboundPacket.Ping(dropPid)));
            session.deliver(new TimelineEvent.Inbound(seq++, dropPingSent + 10_000_000L, 0,
                    new ServerboundPacket.InteractEntity(99, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
            now += 50_000_000L;
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
            long dropAckTime = dropPingSent + 30_000_000L;
            session.deliver(new TimelineEvent.Inbound(seq++, dropAckTime, 0, new ServerboundPacket.Pong(dropPid)));
            session.deliver(new TimelineEvent.Acknowledged(seq++, dropAckTime, dropPid, dropPingSent, 0));
            if (cycle == 0) {
                assertTrue(evidence.isEmpty(), "Single combat RTT recovery must not trigger false positive");
            }
        }

        assertEquals(1, evidence.size());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.get(0).evidenceClass());
        assertEquals("combat-rtt-drop", evidence.get(0).details().get("reason"));
    }

    @Test
    void ignoresClientFreezeStallAndStaircaseDrainWithoutFullFlush() {
        long kaSent = now;
        session.deliver(new TimelineEvent.Outbound(seq++, kaSent, new ClientboundPacket.KeepAlive(1L)));
        now += 5_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.KeepAlive(1L)));

        // Staircase drain (290 ms -> 150 ms) never flushes near KeepAlive (5 + 60 ms) -> 0 evidence
        for (int cycle = 0; cycle < 3; cycle++) {
            for (int t = 0; t < 12; t++) {
                now += 50_000_000L;
                session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
            }
            for (int i = 0; i < 5; i++) {
                long pingSent = now;
                int pid = 400 + cycle * 20 + i;
                session.deliver(new TimelineEvent.Outbound(seq++, pingSent, new ClientboundPacket.Ping(pid)));
                now += 50_000_000L;
                session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
                session.deliver(new TimelineEvent.Inbound(seq++, pingSent + 290_000_000L, 0, new ServerboundPacket.Pong(pid)));
                session.deliver(new TimelineEvent.Acknowledged(seq++, pingSent + 290_000_000L, pid, pingSent, 0));
            }
            long dropSent = now;
            int dropId = 500 + cycle;
            session.deliver(new TimelineEvent.Outbound(seq++, dropSent, new ClientboundPacket.Ping(dropId)));
            session.deliver(new TimelineEvent.Inbound(seq++, dropSent + 10_000_000L, 0,
                    new ServerboundPacket.InteractEntity(99, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
            now += 50_000_000L;
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
            session.deliver(new TimelineEvent.Inbound(seq++, dropSent + 150_000_000L, 0, new ServerboundPacket.Pong(dropId)));
            session.deliver(new TimelineEvent.Acknowledged(seq++, dropSent + 150_000_000L, dropId, dropSent, 0));
        }
        assertTrue(evidence.isEmpty(), "Partial queue staircase drain must not false-flag");
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(PingSpoofCheck.VERSION, Checks.version(PingSpoofCheck.ID));
        assertNotNull(Checks.create(PingSpoofCheck.ID, (s, e) -> {}));
        assertFalse(new PingSpoofCheck((s, e) -> {}).readsEntityMotion());
    }
}
