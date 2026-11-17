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
    void validHotbarMovementInteractAndBlockCursorProduceZeroEvidence() {
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
        assertTrue(evidence.isEmpty());
        assertTrue(rejected.isEmpty());
    }

    @Test
    void detectsNonFiniteMoveInvalidHotbarSlotSelfInteractAndOutOfBoundsCursor() {
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
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(PacketBoundsCheck.VERSION, Checks.version(PacketBoundsCheck.ID));
        assertNotNull(Checks.create(PacketBoundsCheck.ID, (s, e) -> { }));
        assertFalse( new PacketBoundsCheck((s, e) -> { }, Replicas.vanilla()).readsEntityMotion());
    }
}
