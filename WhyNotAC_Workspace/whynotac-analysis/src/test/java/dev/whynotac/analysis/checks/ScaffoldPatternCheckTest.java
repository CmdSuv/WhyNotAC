package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ScaffoldPatternCheckTest {

    private static final long TICK_NANOS = 50_000_000L;
    private static final int SELF = 1;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        rejected.clear();
        seq = 0;
        now = 1_000_000_000L;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new ScaffoldPatternCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        session = registry.openReplay("scaffold-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        UUID playerId = new UUID(11, 11);
        session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "bridger", SELF));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(SELF, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerInfoUpdate(true, true,
                        List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerId, 0)))));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerPosition(1, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 0.0F, 20.0F, Set.of())));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(1)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.AcceptTeleportation(1)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0.0F, 20.0F, true, true, true, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(1)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 1, now, 0));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
        rotateAndTick(0.0F, 20.0F);
    }

    private void rotateAndTick(float yaw, float pitch) {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, yaw, pitch, false, true, true, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        now += TICK_NANOS;
    }

    private void placeBlock(int x, int y, int z, BlockFace face) {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(x, y, z), face,
                        new Vec3d(x + 0.5, y + 0.5, z + 0.5), false, false, 1)));
    }

    @Test
    void honestSneakBridgeAndToweringProduceZeroEvidence() {
        // Tower up on BlockFace.UP + steady-angle sneak bridge across 6 blocks with natural timing
        rotateAndTick(180.0F, 78.0F);
        int[] gaps = {5, 4, 6, 3, 5, 4};
        for (int i = 0; i < gaps.length; i++) {
            placeBlock(0, 63, -i, BlockFace.NORTH);
            for (int t = 0; t < gaps[i]; t++) {
                rotateAndTick(180.0F, 78.0F);
            }
        }
        assertTrue(evidence.isEmpty(), "Honest sneak bridge must produce 0 evidence");
    }

    @Test
    void detectsPlaceAndSnapbackTellyScaffold() {
        rotateAndTick(0.0F, 15.0F);
        for (int i = 0; i < 3; i++) {
            // Snap 180 deg + pitch 78 deg to place on NORTH face, then snap right back next tick
            placeBlock(0, 63, i + 1, BlockFace.NORTH);
            rotateAndTick(180.0F, 78.0F);
            rotateAndTick(0.0F, 15.0F);
            rotateAndTick(0.0F, 15.0F);
        }
        assertEquals(1, evidence.size());
        assertEquals("place-and-snapback", evidence.get(0).details().get("reason"));
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.get(0).evidenceClass());
    }

    @Test
    void detectsConstantRoboticBridgeCadence() {
        rotateAndTick(180.0F, 78.0F);
        for (int i = 0; i <= ScaffoldPatternCheck.CADENCE_WINDOW; i++) {
            placeBlock(0, 63, i + 10, BlockFace.NORTH);
            rotateAndTick(180.0F, 78.0F);
            rotateAndTick(180.0F, 78.0F);
            rotateAndTick(180.0F, 78.0F);
        }
        assertEquals(1, evidence.size());
        assertEquals("constant-bridge-cadence", evidence.get(0).details().get("reason"));
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(ScaffoldPatternCheck.VERSION, Checks.version(ScaffoldPatternCheck.ID));
        assertNotNull(Checks.create(ScaffoldPatternCheck.ID, (s, e) -> { }));
        assertFalse(new ScaffoldPatternCheck((s, e) -> { }, Replicas.vanilla()).readsEntityMotion());
    }
}
