package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The check over the replicas it reads, fed a timeline as the plugin would. */
class AttackReachCheckTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final UUID ME = new UUID(7, 7);
    private static final int SELF = 1;
    private static final int ZOMBIE = 20;
    private static final long SECOND = 1_000_000_000L;
    private static final Stack.AttackRange LONG = new Stack.AttackRange(0F, 4.5F, 0F, 6.5F, 0.25F, 1F);

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now;
    private int pingId;

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new AttackReachCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        session = registry.openReplay("test");
        deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        deliver(new TimelineEvent.Bound(seq++, now, ME, "me", SELF));
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD, 0));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, 0))));
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        int air = STATES.firstStateOf("minecraft:air");
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                out(new ClientboundPacket.LevelChunk(x, z, chunk(OVERWORLD.sections(), air)));
            }
        }
        out(new ClientboundPacket.SetHeldSlot(0));
        out(new ClientboundPacket.ContainerSetContent(0, 1, inventory(Stack.EMPTY), Stack.EMPTY));
        sync();
        in(new ServerboundPacket.PlayerLoaded());
        move(0.5, 64.0, 0.5);
        tick();
    }

    // --- timeline helpers ---

    private void deliver(TimelineEvent event) {
        session.deliver(event);
    }

    private void out(ClientboundPacket packet) {
        deliver(new TimelineEvent.Outbound(seq++, now, packet));
    }

    private void in(ServerboundPacket packet) {
        deliver(new TimelineEvent.Inbound(seq++, now, 0, packet));
    }

    private void sync() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        in(new ServerboundPacket.Pong(id));
        deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
    }

    private void move(double x, double y, double z) {
        in(new ServerboundPacket.MovePlayer(x, y, z, 0F, 0F, true, false, true, false));
    }

    private void tick() {
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    private void zombie(double x, double z) {
        out(ClientboundPacket.AddEntity.received(ZOMBIE, new UUID(0, ZOMBIE), "minecraft:zombie", x, 64.0, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
    }

    private void attack(int target) {
        in(new ServerboundPacket.InteractEntity(target, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
    }

    private static List<Stack> inventory(Stack mainHand) {
        List<Stack> items = new ArrayList<>(Collections.nCopies(46, Stack.EMPTY));
        items.set(36, mainHand);
        return items;
    }

    /** A chunk of {@code sections} sections, each holding {@code state} only. */
    private static byte[] chunk(int sections, int state) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0); // no heightmaps
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < sections; i++) {
            data.write(0);
            data.write(0); // non-empty count (short)
            data.write(0); // single-valued palette
            varInt(data, state);
            data.write(0); // biomes: one value
            varInt(data, 0);
        }
        varInt(body, data.size());
        body.writeBytes(data.toByteArray());
        varInt(body, 0); // block entities
        return body.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    // --- tests ---

    @Test
    void anAttackWithinTheInteractionRangeIsNoFinding() {
        zombie(3.7, 0.5); // the box's near face at 3.4, the eye at 0.5: 2.9 away
        attack(ZOMBIE);
        assertEquals(List.of(), evidence);
    }

    @Test
    void anAttackBeyondEveryPossibleReachIsAFinding() {
        zombie(5.0, 0.5); // 4.2 away
        attack(ZOMBIE);
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(AttackReachCheck.ID, e.check());
        assertEquals(EvidenceClass.ADVANTAGE_PROOF, e.evidenceClass());
        assertEquals("minecraft:zombie", e.details().get("targetType"));
        assertEquals("4.1998", e.details().get("distance"), "4.2 less the send threshold");
        assertEquals("3.0000", e.details().get("allowed"));
        assertEquals("none", e.details().get("attackReach"));
        assertTrue(e.admissibility().admissible());
    }

    @Test
    void findingsAreAtMostOneASecondAndCountWhatCameBetween() {
        zombie(5.0, 0.5);
        attack(ZOMBIE);
        tick();
        attack(ZOMBIE);
        tick();
        attack(ZOMBIE);
        assertEquals(1, evidence.size());
        for (int i = 0; i < 20; i++) {
            move(0.5, 64.0, 0.5);
            tick();
        }
        attack(ZOMBIE);
        assertEquals(2, evidence.size());
        assertEquals("3", evidence.get(1).details().get("outOfReachAttacks"));
    }

    @Test
    void anItemsAttackRangeIsTheBound() {
        Stack stick = new Stack("minecraft:stick", 1, List.of(Stack.ATTACK_RANGE), List.of(), LONG, false, 1234);
        out(new ClientboundPacket.ContainerSetContent(0, 2, inventory(stick), Stack.EMPTY));
        sync();
        tick();
        zombie(5.0, 0.5); // 4.2 away: within 4.5 + 0.25
        attack(ZOMBIE);
        assertEquals(List.of(), evidence);
        out(new ClientboundPacket.AddEntity(ZOMBIE + 1, new UUID(0, ZOMBIE + 1), "minecraft:zombie", 6.0, 64.0, 0.5, Vec3d.ZERO, null,
                (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        attack(ZOMBIE + 1); // 5.2 away
        assertEquals(1, evidence.size());
        assertEquals("4.75", evidence.getFirst().details().get("allowed").substring(0, 4));
    }

    @Test
    void aProjectileTheClientMovesItselfIsNeverClaimed() {
        // Its position is the client's own simulation (ClientMotion), not followed: unknown, whatever its pick radius.
        out(ClientboundPacket.AddEntity.received(31, new UUID(0, 31), "minecraft:snowball", 9.0, 65.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        attack(31);
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClaimWhileRidingInCreativeOrWithoutAPosition() {
        zombie(5.0, 0.5);
        out(new ClientboundPacket.SetPassengers(40, new int[] {SELF}));
        sync();
        attack(ZOMBIE);
        out(new ClientboundPacket.SetPassengers(40, new int[0]));
        sync();
        attack(ZOMBIE); // dismounted, no position since
        out(new ClientboundPacket.PlayerInfoUpdate(false, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, 1))));
        sync();
        move(0.5, 64.0, 0.5);
        attack(ZOMBIE); // creative
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClaimNearAPistonOrAfterARollback() {
        zombie(5.0, 0.5);
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 64, 0), STATES.firstStateOf("minecraft:piston")));
        sync();
        move(0.5, 64.0, 0.5);
        attack(ZOMBIE);
        assertEquals(List.of(), evidence, "a piston near the player may push it after it sent its position");
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 64, 0), STATES.firstStateOf("minecraft:air")));
        sync();
        attack(ZOMBIE);
        assertEquals(List.of(), evidence, "removed after the position was sent: it may have pushed in that tick");
        move(0.5, 64.0, 0.5);
        attack(ZOMBIE);
        assertEquals(1, evidence.size(), "gone before the tick that sent this position");
        evidence.clear();
        for (int i = 0; i < 20; i++) {
            tick();
        }
        out(new ClientboundPacket.BlockChangedAck(3));
        sync();
        attack(ZOMBIE);
        assertEquals(List.of(), evidence, "a rollback may have moved the player");
    }

    @Test
    void aPolarBearIsAsHighAsTheClientsStandingAnimationMakesIt() {
        // the eye at least at 64.2 (any pose), the bear right below it: its top 3.5 under it on four legs, 2.1 standing
        int bear = 30;
        int standing = dev.whynotac.world.EntitySizes.vanilla().entry("minecraft:polar_bear").data().getFirst().index();
        out(ClientboundPacket.AddEntity.received(bear, new UUID(0, bear), "minecraft:polar_bear", 0.5, 59.3, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0,
                (byte) 0, 0));
        out(new ClientboundPacket.SetEntityData(bear, List.of(new ClientboundPacket.SetEntityData.DataEntry(standing,
                ClientboundPacket.SetEntityData.Kind.BOOLEAN, 1)), 0));
        sync();
        for (int i = 0; i < 3; i++) {
            move(0.5, 64.0, 0.5);
            tick();
        }
        attack(bear); // half way up: 2.1 high, 2.8 away
        assertEquals(List.of(), evidence);
        out(new ClientboundPacket.SetEntityData(bear, List.of(new ClientboundPacket.SetEntityData.DataEntry(standing,
                ClientboundPacket.SetEntityData.Kind.BOOLEAN, 0)), 0));
        sync();
        move(0.5, 64.0, 0.5);
        tick();
        attack(bear); // one step down: the box may still be at the value before the tick
        assertEquals(List.of(), evidence);
        for (int i = 0; i < 6; i++) {
            move(0.5, 64.0, 0.5);
            tick();
        }
        attack(bear); // down again
        assertEquals(1, evidence.size());
        assertEquals("minecraft:polar_bear", evidence.getFirst().details().get("targetType"));
        assertEquals("3.4998", evidence.getFirst().details().get("distance"));
    }

    @Test
    void noClaimNearAShulker() {
        zombie(5.0, 0.5);
        out(ClientboundPacket.AddEntity.received(50, new UUID(0, 50), "minecraft:shulker", -6.0, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        move(0.5, 64.0, 0.5);
        attack(ZOMBIE);
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClaimOnAnEntityTheClientMayNotHave() {
        attack(99);
        zombie(5.0, 0.5);
        int unanswered = ++pingId;
        out(new ClientboundPacket.Ping(unanswered));
        out(new ClientboundPacket.MoveEntity(ZOMBIE, (short) (-2 * 4096), (short) 0, (short) 0, (byte) 0, (byte) 0, true, false, false));
        attack(ZOMBIE); // the move is after the first unanswered ping: not processed yet
        assertEquals(1, evidence.size());
        assertEquals(List.of(), rejected);
    }
}
