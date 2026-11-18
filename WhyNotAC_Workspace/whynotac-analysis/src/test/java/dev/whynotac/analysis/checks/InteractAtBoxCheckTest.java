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

final class InteractAtBoxCheckTest {

    private static final int SELF = 1;
    private static final int TARGET = 50;

    private final List<Evidence> evidence = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 3_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        seq = 0;
        now = 3_000_000_000L;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new InteractAtBoxCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {}
        }, replicas));
        session = registry.openReplay("interact-at-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        UUID playerId = new UUID(8, 8);
        session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "interactor", SELF));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(SELF, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerInfoUpdate(true, true,
                        List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerId, 0)))));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                ClientboundPacket.AddEntity.sent(TARGET, new UUID(9, 9), "minecraft:armor_stand",
                        2.5, 64.0, 2.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(1)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(1)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 1, now, 0));
    }

    @Test
    void validArmorStandInteractAtOffsetProducesZeroEvidence() {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.INTERACT_AT,
                        Hand.MAIN_HAND, new Vec3d(0.15, 1.20, -0.10), false)));
        assertTrue(evidence.isEmpty());
    }

    @Test
    void detectsOutOfBoundsAndNonFiniteInteractAt() {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.INTERACT_AT,
                        Hand.MAIN_HAND, new Vec3d(2.40, 1.20, 0.0), false)));
        assertEquals(1, evidence.size());
        assertEquals("out-of-bounds-interact-at", evidence.get(0).details().get("reason"));
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.get(0).evidenceClass());

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.INTERACT_AT,
                        Hand.MAIN_HAND, new Vec3d(Double.NaN, 1.0, 0.0), false)));
        assertEquals(2, evidence.size());
        assertEquals("non-finite-interact-at", evidence.get(1).details().get("reason"));
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(InteractAtBoxCheck.VERSION, Checks.version(InteractAtBoxCheck.ID));
        assertNotNull(Checks.create(InteractAtBoxCheck.ID, (s, e) -> { }));
        assertFalse(new InteractAtBoxCheck((s, e) -> { }, Replicas.vanilla()).readsEntityMotion());
    }
}
