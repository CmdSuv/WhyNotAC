package dev.whynotac.ai;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DesignProbeEngineTest {

    @Test
    void emitsDesignStatisticOnlyWhenDecoysHitRepeatedlyAndIgnoresHonestTimeouts() {
        ProbeScheduler scheduler = new ProbeScheduler(10, 5, 0.001, 99L);
        List<Evidence> findings = new ArrayList<>();
        EvidenceSink sink = (s, e) -> findings.add(e);
        DesignProbeEngine engine = new DesignProbeEngine(scheduler, sink);

        SessionRegistry registry = new SessionRegistry(Runnable::run, 512, (s, e, err) -> {
            throw new AssertionError("Listener error", err);
        });
        registry.addListener(engine);

        UUID id = UUID.randomUUID();
        PlayerSession session = registry.open("127.0.0.1:25565");
        long nano = 1_000_000_000L;
        session.onObservation(Source.WIRE, Source.WIRE, 774, "wire", nano);
        session.onFact(Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "edge", nano + 1);
        session.onFact(Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "edge", nano + 2);
        session.onFact(Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "edge", nano + 3);
        session.onFact(Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "online", nano + 4);
        session.bind(id, "ProbeTarget", 1, nano + 5);

        // First: clean player ignores 6 decoys cleanly
        for (int p = 0; p < 6; p++) {
            assertTrue(engine.registerDecoy(session, 1_900_000_000 + p, 5L, 0.05));
            for (int t = 0; t < 6; t++) {
                nano += 50_000_000L;
                session.onInbound(new ServerboundPacket.ClientTickEnd(), nano);
            }
        }
        assertTrue(findings.isEmpty());
        assertTrue(scheduler.processFor(id).wealth() < 1.0);

        // Next: bot/killaura attacks phantom decoys behind FOV -> sequential e-value rejects H0 and emits D finding
        scheduler.processFor(id).reset();
        for (int p = 0; p < 5 && findings.isEmpty(); p++) {
            int decoyId = 1_900_001_000 + p;
            assertTrue(engine.registerDecoy(session, decoyId, 10L, 0.05));
            nano += 10_000_000L;
            session.onInbound(new ServerboundPacket.InteractEntity(
                    decoyId, ServerboundPacket.InteractEntity.Kind.ATTACK, Hand.MAIN_HAND, Vec3d.ZERO, false), nano);
            nano += 40_000_000L;
            session.onInbound(new ServerboundPacket.ClientTickEnd(), nano);
        }
        assertEquals(1, findings.size());
        assertEquals(EvidenceClass.DESIGN_STATISTIC, findings.getFirst().evidenceClass());
        assertEquals(DesignProbeEngine.CHECK_ID, findings.getFirst().check());
        assertFalse(scheduler.processFor(id).rejectedNull()); // reset for next window
        assertEquals(Admissibility.IdentityTier.AUTHENTICATED, findings.getFirst().admissibility().identity());
    }
}
