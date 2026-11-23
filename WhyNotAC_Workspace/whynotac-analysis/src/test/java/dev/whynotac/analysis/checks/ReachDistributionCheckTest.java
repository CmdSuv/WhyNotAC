package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReachDistributionCheckTest {

    private static final int SELF = 1;
    private static final int TARGET = 2;
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);

    private final List<Evidence> evidence = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;
    private int pingId;

    @BeforeEach
    void setUp() {
        evidence.clear();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new ReachDistributionCheck(new EvidenceSink() {
            @Override public void accept(PlayerSession s, Evidence e) { evidence.add(e); }
            @Override public void rejected(PlayerSession s, String check, Admissibility a) {}
        }, replicas));
        session = registry.openReplay("reach-dist-test");
        session.deliver(new TimelineEvent.Observation(seq++, now,
                TimelineEvent.Observation.Source.WIRE, TimelineEvent.Observation.Source.WIRE, 774, "wire"));
        session.deliver(new TimelineEvent.ClientFact(seq++, now, TimelineEvent.ClientFact.Fact.NATIVE_PROTOCOL, "774",
                TimelineEvent.ClientFact.Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.ClientFact(seq++, now, TimelineEvent.ClientFact.Fact.TRANSLATOR, "none",
                TimelineEvent.ClientFact.Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.ClientFact(seq++, now, TimelineEvent.ClientFact.Fact.TRANSACTION_ORIGIN, "CLIENT",
                TimelineEvent.ClientFact.Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.Bound(seq++, now, new UUID(3, 4), "reach", SELF));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Login(SELF, 8, OVERWORLD, 0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.SetHeldSlot(0)));
        List<Stack> slots = new ArrayList<>();
        for (int i = 0; i < 46; i++) slots.add(Stack.EMPTY);
        slots.set(36, new Stack("minecraft:diamond_sword", 1, List.of(), List.of(), null, false));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.ContainerSetContent(0, 1, slots, Stack.EMPTY)));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                ClientboundPacket.AddEntity.received(TARGET, new UUID(0, TARGET), "minecraft:zombie", 0.0, 64.0, 0.0, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0)));
        syncPing();
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
    }

    private void syncPing() {
        int id = ++pingId;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(id)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, id, 0, 0));
    }

    private void hitAtDistance(double targetSurfaceDist) {
        // Target is zombie (width=0.6 -> half-width=0.3) centered at (0, 64, 0), so its +X face is at x=+0.3.
        // Player standing at x = 0.3 + targetSurfaceDist has eye-to-box distance == targetSurfaceDist!
        double px = 0.3 + targetSurfaceDist;
        now += 500_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(px, 64.0, 0.0, 90.0F, 0.0F, true, false, true, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
    }

    @Test
    void normalVariedSurvivalPvpProducesZeroFalsePositives() {
        SplittableRandom rng = new SplittableRandom(12345L);
        for (int i = 0; i < 60; i++) {
            double d = 1.6 + rng.nextDouble() * 1.38;
            hitAtDistance(d);
        }
        assertTrue(evidence.isEmpty(), "Normal varied PvP must produce 0 FP: " + evidence);
    }

    @Test
    void boundaryClampedGhostReachProducesNominalStatistic() {
        SplittableRandom rng = new SplittableRandom(777L);
        for (int i = 0; i < 40; i++) {
            double d = 2.95 + rng.nextDouble() * 0.04; // tight [2.95, 2.99] ghost-reach clamp
            hitAtDistance(d);
        }
        assertEquals(1, evidence.size());
        assertEquals(ReachDistributionCheck.ID, evidence.get(0).check());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.get(0).evidenceClass());
        assertEquals("clustered-boundary-reach", evidence.get(0).details().get("reason"));
    }
}
