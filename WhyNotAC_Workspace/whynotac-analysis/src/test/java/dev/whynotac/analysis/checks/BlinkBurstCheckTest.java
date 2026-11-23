package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlinkBurstCheckTest {

    private static final long TICK_NS = 50_000_000L;
    private static final long MS = 1_000_000L;
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);

    private final List<Evidence> evidence = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new BlinkBurstCheck(new EvidenceSink() {
            @Override public void accept(PlayerSession s, Evidence e) { evidence.add(e); }
            @Override public void rejected(PlayerSession s, String check, Admissibility a) {}
        }));
        session = registry.openReplay("blink-test");
        session.deliver(new TimelineEvent.Observation(seq++, now,
                TimelineEvent.Observation.Source.WIRE, TimelineEvent.Observation.Source.WIRE, 774, "wire"));
        session.deliver(new TimelineEvent.ClientFact(seq++, now, TimelineEvent.ClientFact.Fact.NATIVE_PROTOCOL, "774",
                TimelineEvent.ClientFact.Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.ClientFact(seq++, now, TimelineEvent.ClientFact.Fact.TRANSLATOR, "none",
                TimelineEvent.ClientFact.Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.ClientFact(seq++, now, TimelineEvent.ClientFact.Fact.TRANSACTION_ORIGIN, "CLIENT",
                TimelineEvent.ClientFact.Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.Bound(seq++, now, new UUID(1, 2), "blink", 1));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Login(1, 8, OVERWORLD, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
    }

    private void normalTick(double x, boolean attack) {
        now += TICK_NS;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(x, 64.0, 0.0, 0.0F, 0.0F, true, false, true, false)));
        if (attack) {
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                    new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        }
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
    }

    private void blinkCycle(double baseX, long silenceMs, int flushTicks, boolean includeAttack) {
        now += silenceMs * MS;
        for (int i = 0; i < flushTicks; i++) {
            now += MS; // 1 ms spacing -> flush within < 20 ms
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                    new ServerboundPacket.MovePlayer(baseX + i * 0.2, 64.0, 0.0, 0.0F, 0.0F, true, false, true, false)));
            if (includeAttack && i == flushTicks - 1) {
                session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                        new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
            }
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        }
        normalTick(baseX + flushTicks * 0.2, false);
    }

    @Test
    void isolatedLagSpikeAndIdleLagSpikesProduceZeroFalsePositives() {
        for (int i = 0; i < 10; i++) normalTick(i * 0.1, false);
        // Single ISP lag spike with an attack -> below MIN_CYCLES (3)
        blinkCycle(2.0, 450, 8, true);
        // 4 repeated lag spikes with NO combat or block placement -> 0 FP
        for (int c = 0; c < 4; c++) {
            blinkCycle(5.0 + c * 2.0, 420, 8, false);
            for (int i = 0; i < 10; i++) normalTick(10.0 + c + i * 0.1, false);
        }
        assertTrue(evidence.isEmpty(), "Honest lag spikes must produce 0 FP: " + evidence);
    }

    @Test
    void repeatedCombatPulseBlinkIsFlaggedEvenBehindTranslator() {
        // Mark session as translated (ViaVersion) to verify translator-safe admissibility!
        session.deliver(new TimelineEvent.ClientFact(seq++, now, TimelineEvent.ClientFact.Fact.TRANSLATOR, "ViaVersion",
                TimelineEvent.ClientFact.Trust.FORMAT_BOUND, "test"));
        for (int i = 0; i < 5; i++) normalTick(i * 0.1, false);
        for (int c = 0; c < 3; c++) {
            blinkCycle(2.0 + c * 2.0, 400, 7, true);
            for (int i = 0; i < 10; i++) normalTick(4.0 + c * 2.0 + i * 0.1, false);
        }
        assertEquals(1, evidence.size());
        assertEquals(BlinkBurstCheck.ID, evidence.get(0).check());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.get(0).evidenceClass());
        assertEquals("combat-blink-burst", evidence.get(0).details().get("reason"));
    }
}
