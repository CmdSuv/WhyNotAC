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
import dev.whynotac.protocol.packet.Stack;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class PacketBoundsCheckTest {

    private static final int SELF = 7;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 5_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        rejected.clear();
        seq = 0;
        now = 5_000_000_000L;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new PacketBoundsCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        session = registry.openReplay("bounds-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        UUID playerId = new UUID(9, 9);
        session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "bounds", SELF));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(SELF, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
    }

    @Test
    void validHotbarMovementInteractContainerAndBlockCursorProduceZeroEvidence() {
        for (int slot = 0; slot <= 8; slot++) {
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.SetCarriedItem(slot)));
        }
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 90.0F, 45.0F, true, true, true, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(SELF + 1, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(1, 63, 1), BlockFace.UP,
                        new Vec3d(1.5, 64.0, 1.5), false, false, 1)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, -999, 0, "PICKUP", List.of(), Stack.EMPTY)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, 10, 40, "SWAP", List.of(), Stack.EMPTY)));
        assertTrue(evidence.isEmpty());
        assertTrue(rejected.isEmpty());
    }

    @Test
    void detectsNonFiniteMoveInvalidHotbarSlotSelfInteractInvalidContainerAndOutOfBoundsCursor() {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(Double.NaN, 64.0, 0.5, 0.0F, 0.0F, true, false, true, false)));
        assertEquals(1, evidence.size());
        assertEquals("non-finite-movement", evidence.get(0).details().get("reason"));
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.get(0).evidenceClass());

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.SetCarriedItem(9)));
        assertEquals(2, evidence.size());
        assertEquals("invalid-hotbar-slot", evidence.get(1).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(SELF, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        assertEquals(3, evidence.size());
        assertEquals("self-interaction", evidence.get(2).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(0, 64, 0), BlockFace.UP,
                        new Vec3d(12.5, 64.5, 0.5), false, false, 2)));
        assertEquals(4, evidence.size());
        assertEquals("invalid-block-cursor", evidence.get(3).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, -5, 0, "PICKUP", List.of(), Stack.EMPTY)));
        assertEquals(5, evidence.size());
        assertEquals("invalid-container-slot", evidence.get(4).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, 10, 99, "SWAP", List.of(), Stack.EMPTY)));
        assertEquals(6, evidence.size());
        assertEquals("invalid-container-swap", evidence.get(5).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.PlayerCommand(SELF + 99, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        assertEquals(7, evidence.size());
        assertEquals("command-entity-mismatch", evidence.get(6).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.PlayerCommand(SELF, ServerboundPacket.PlayerCommand.Action.START_RIDING_JUMP, 150)));
        assertEquals(8, evidence.size());
        assertEquals("invalid-command-data", evidence.get(7).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ChunkBatchReceived(-1.0F)));
        assertEquals(9, evidence.size());
        assertEquals("invalid-chunk-batch-rate", evidence.get(8).details().get("reason"));
    }

    @Test
    void detectsInvalidPitchDuplicateHotbarSlotAndInvalidContainerButtonsWithServerPitchExemption() {
        // 1. Pitch flip > 90 deg -> invalid-pitch
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0.0F, 95.5F, true, true, true, false)));
        assertEquals(1, evidence.size());
        assertEquals("invalid-pitch", evidence.get(0).details().get("reason"));

        // 2. Duplicate consecutive SetCarriedItem -> duplicate-hotbar-slot
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.SetCarriedItem(3)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.SetCarriedItem(3)));
        assertEquals(2, evidence.size());
        assertEquals("duplicate-hotbar-slot", evidence.get(1).details().get("reason"));

        // Respawn resets hotbar history so selecting slot 3 again once is permitted
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Respawn(new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.SetCarriedItem(3)));
        assertEquals(2, evidence.size());

        // 3. Invalid THROW / PICKUP_ALL / QUICK_CRAFT buttons
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, 10, 5, "THROW", List.of(), Stack.EMPTY)));
        assertEquals(3, evidence.size());
        assertEquals("invalid-container-button", evidence.get(2).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, 10, 1, "PICKUP_ALL", List.of(), Stack.EMPTY)));
        assertEquals(4, evidence.size());
        assertEquals("invalid-container-button", evidence.get(3).details().get("reason"));

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, 10, 3, "QUICK_CRAFT", List.of(), Stack.EMPTY)));
        assertEquals(5, evidence.size());
        assertEquals("invalid-container-button", evidence.get(4).details().get("reason"));

        // 4. Server sending out-of-range pitch exempts subsequent client pitch echo
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerRotation(0.0F, false, 120.0F, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 0.0F, 120.0F, false, true, true, false)));
        assertEquals(5, evidence.size());
    }

    @Test
    void translatorSafeRulesAcceptViaVersionWhileStrictRulesReject() {
        List<Evidence> tvEvidence = new ArrayList<>();
        List<Admissibility> tvRejected = new ArrayList<>();
        SessionRegistry tvRegistry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas tvReplicas = Replicas.vanilla();
        tvReplicas.listeners().forEach(tvRegistry::addListener);
        tvRegistry.addListener(new PacketBoundsCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                tvEvidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                tvRejected.add(a);
            }
        }, tvReplicas));
        PlayerSession tv = tvRegistry.openReplay("bounds-translator");
        long s = 0;
        long t = 1_000_000_000L;
        tv.deliver(new TimelineEvent.Observation(s++, t, Source.WIRE, Source.WIRE, 774, "test"));
        tv.deliver(new ClientFact(s++, t, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        tv.deliver(new ClientFact(s++, t, Fact.NATIVE_PROTOCOL, "773", Trust.FORMAT_BOUND, "ViaVersion"));
        tv.deliver(new ClientFact(s++, t, Fact.TRANSLATOR, "viaversion", Trust.FORMAT_BOUND, "ViaVersion"));
        tv.deliver(new ClientFact(s++, t, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        tv.deliver(new TimelineEvent.Bound(s++, t, new UUID(9, 10), "viaUser", SELF));
        tv.deliver(new TimelineEvent.Outbound(s++, t,
                new ClientboundPacket.Login(SELF, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));

        // Translator-safe: invalid-hotbar-slot accepted under ViaVersion
        tv.deliver(new TimelineEvent.Inbound(s++, t, 0, new ServerboundPacket.SetCarriedItem(9)));
        assertEquals(1, tvEvidence.size());
        assertEquals("invalid-hotbar-slot", tvEvidence.get(0).details().get("reason"));
        assertTrue(tvRejected.isEmpty());

        // Strict rule: invalid-block-cursor rejected under ViaVersion
        t += 2_000_000_000L;
        tv.deliver(new TimelineEvent.Inbound(s++, t, 0,
                new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(0, 64, 0), BlockFace.UP,
                        new Vec3d(12.5, 64.5, 0.5), false, false, 1)));
        assertEquals(1, tvEvidence.size());
        assertEquals(1, tvRejected.size());
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(PacketBoundsCheck.VERSION, Checks.version(PacketBoundsCheck.ID));
        assertNotNull(Checks.create(PacketBoundsCheck.ID, (s, e) -> { }));
        assertFalse(new PacketBoundsCheck((s, e) -> { }, Replicas.vanilla()).readsEntityMotion());
    }
}
