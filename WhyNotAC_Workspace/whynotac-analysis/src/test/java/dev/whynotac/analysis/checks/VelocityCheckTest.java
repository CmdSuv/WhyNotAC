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
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Replicas;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class VelocityCheckTest {

    private static final int SELF = 1;
    private static final ClientboundPacket.Dimension OVERWORLD =
            new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);
    private static final int STONE = BlockStates.vanilla().firstStateOf("minecraft:stone");
    private static final int AIR = BlockStates.vanilla().firstStateOf("minecraft:air");

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 2_000_000_000L;
    private int pingId = 10;

    @BeforeEach
    void setUp() {
        evidence.clear();
        rejected.clear();
        seq = 0;
        now = 2_000_000_000L;
        pingId = 10;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new VelocityCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        session = registry.openReplay("velocity-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        UUID playerId = new UUID(3, 3);
        session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "kbTarget", SELF));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Login(SELF, 4, OVERWORLD, 0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerInfoUpdate(true, true,
                        List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerId, 0)))));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.ChunkCacheCenter(0, 0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.LevelChunk(0, 0, flatChunk())));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerPosition(1, new Vec3d(2.5, 64.0, 2.5), Vec3d.ZERO, 0.0F, 0.0F, Set.of())));
        syncPing();
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.AcceptTeleportation(1)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0F, 0.0F, true, true, true, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
        // Two post-load ground position packets establish ground anchor
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0F, 0.0F, true, false, true, false)));
        tick();
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0F, 0.0F, true, false, true, false)));
        tick();
    }

    private void syncPing() {
        int id = ++pingId;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(id)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(id)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
    }

    private void tick() {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        now += 50_000_000L;
    }

    @Test
    void honestVerticalKnockbackResponseProducesZeroEvidence() {
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                ClientboundPacket.SetEntityMotion.sent(SELF, new Vec3d(0.0, 0.42, 0.0))));
        syncPing();
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(2.5, 64.42, 2.5, 0.0F, 0.0F, true, false, false, false)));
        tick();
        tick();
        assertTrue(evidence.isEmpty(), "Honest knockback rise must produce 0 evidence");
    }

    @Test
    void detectsZeroPercentAntiKbStayingOnGround() {
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                ClientboundPacket.SetEntityMotion.sent(SELF, new Vec3d(0.25, 0.40, 0.0))));
        syncPing();
        // Cheater cancels knockback and stays on ground at y=64.0
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0F, 0.0F, true, false, true, false)));
        tick();
        tick();
        assertEquals(1, evidence.size());
        assertEquals(EvidenceClass.ADVANTAGE_PROOF, evidence.get(0).evidenceClass());
        assertEquals("ignored-vertical-knockback", evidence.get(0).details().get("reason"));
    }

    @Test
    void detectsHorizontalAntiKbWhileJumpingOrIgnoringHorizontalPush() {
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                ClientboundPacket.SetEntityMotion.sent(SELF, new Vec3d(0.45, 0.36, 0.0))));
        syncPing();
        // Cheater accepts vertical jump (leaves ground) to bypass vertical-only check, but zero horizontal displacement
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(2.5, 64.36, 2.5, 0.0F, 0.0F, true, false, false, false)));
        tick();
        tick();
        assertEquals(1, evidence.size());
        assertEquals(EvidenceClass.ADVANTAGE_PROOF, evidence.get(0).evidenceClass());
        assertEquals("ignored-horizontal-knockback", evidence.get(0).details().get("reason"));
    }

    @Test
    void honestHorizontalKnockbackProducesZeroEvidence() {
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                ClientboundPacket.SetEntityMotion.sent(SELF, new Vec3d(0.45, 0.36, 0.0))));
        syncPing();
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(2.90, 64.36, 2.5, 0.0F, 0.0F, true, false, false, false)));
        tick();
        tick();
        assertTrue(evidence.isEmpty());
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(VelocityCheck.VERSION, Checks.version(VelocityCheck.ID));
        assertNotNull(Checks.create(VelocityCheck.ID, (s, e) -> { }));
        assertTrue(new VelocityCheck((s, e) -> { }, Replicas.vanilla()).readsEntityMotion());
    }

    private static byte[] flatChunk() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < OVERWORLD.sections(); i++) {
            boolean solid = i < 8;
            data.write(solid ? 0x10 : 0);
            data.write(0);
            data.write(0);
            varInt(data, solid ? STONE : AIR);
            data.write(0);
            varInt(data, 0);
        }
        varInt(body, data.size());
        body.writeBytes(data.toByteArray());
        varInt(body, 0);
        return body.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
