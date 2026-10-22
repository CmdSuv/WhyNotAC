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
class AttackAimCheckTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final UUID ME = new UUID(7, 7);
    private static final int SELF = 1;
    private static final int ZOMBIE = 20;
    private static final long SECOND = 1_000_000_000L;
    private static final Stack.AttackRange LONG = new Stack.AttackRange(0F, 4.5F, 0F, 6.5F, 0.25F, 1F);
    /** Looks along +x (the zombies' side) and +z. */
    private static final float EAST = -90F;
    private static final float SOUTH = 0F;

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
        registry.addListener(new AttackAimCheck(new EvidenceSink() {
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

    /** A rotation-only packet, as {@code sendPosition} sends when only the look changed. */
    private void look(float yaw, float pitch) {
        in(new ServerboundPacket.MovePlayer(0, 0, 0, yaw, pitch, false, true, true, false));
    }

    private void tick() {
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    private void zombie(int id, double x, double z) {
        out(ClientboundPacket.AddEntity.received(id, new UUID(0, id), "minecraft:zombie", x, 64.0, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
    }

    private void attack(int target) {
        in(new ServerboundPacket.InteractEntity(target, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        in(new ServerboundPacket.Swing(dev.whynotac.protocol.packet.Hand.MAIN_HAND));
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

    // --- geometry ---

    @Test
    void theLookIsTheGamesViewVector() {
        double[] east = AttackAim.view(0F, EAST);
        assertEquals(1.0, east[0], 1e-6);
        assertEquals(0.0, east[1], 1e-6);
        assertEquals(0.0, east[2], 1e-6);
        double[] down = AttackAim.view(90F, 0F);
        assertEquals(-1.0, down[1], 1e-6);
        double[] any = AttackAim.view(12.5F, 7212.25F); // whole turns: the sine table wraps them
        double[] same = AttackAim.view(12.5F, 7212.25F - 7200F);
        assertEquals(any[0], same[0], 1e-3);
        assertEquals(any[2], same[2], 1e-3);
    }

    @Test
    void aRayMeetsABoxOnlyAheadOfIt() {
        AttackReach.Box ahead = new AttackReach.Box(2, -1, -1, 3, 1, 1);
        assertTrue(AttackAim.meets(new double[] {1, 0, 0}, ahead));
        assertTrue(!AttackAim.meets(new double[] {-1, 0, 0}, ahead), "behind");
        assertTrue(!AttackAim.meets(new double[] {0, 0, 1}, ahead), "beside");
        assertTrue(AttackAim.meets(new double[] {0, 0, 1}, new AttackReach.Box(-1, -1, -1, 1, 1, 1)), "the eye inside");
        assertEquals(2.0, AttackAim.missBy(new double[] {0, 0, 1}, ahead, 64.0), 1e-6);
    }

    // --- the check ---

    @Test
    void anAttackAlongTheLookIsNoFinding() {
        zombie(ZOMBIE, 3.7, 0.5);
        attack(ZOMBIE);
        look(EAST, 0F);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void anAttackOffTheLookIsAFinding() {
        zombie(ZOMBIE, 3.7, 0.5);
        attack(ZOMBIE);
        look(SOUTH, 0F); // along +z: the zombie's box from x 3.4 to 4.0, the eye at x 0.5
        tick();
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(AttackAimCheck.ID, e.check());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("minecraft:zombie", e.details().get("targetType"));
        assertEquals("0.0000/0.0000", e.details().get("looks"));
        assertEquals(2.9, Double.parseDouble(e.details().get("missBy")), 1e-3);
        assertTrue(e.admissibility().admissible());
        assertEquals(List.of(), rejected);
    }

    @Test
    void theEyeIsAtOneOfThePosesHeightsSoThePitchCounts() {
        // a chicken (0.7 high) floating from 64.45 to 65.15: between the 0.6-tall poses' eye (64.4) and the crouching
        // eye (65.27), so no level look from any pose meets it, though one from somewhere between them would
        out(ClientboundPacket.AddEntity.received(30, new UUID(0, 30), "minecraft:chicken", 2.5, 64.45, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0,
                (byte) 0, 0));
        sync();
        attack(30);
        look(EAST, 0F);
        tick();
        assertEquals(1, evidence.size());
        evidence.clear();
        for (int i = 0; i < 20; i++) {
            tick();
        }
        attack(30);
        look(EAST, 15F); // from the standing eye, 2.2 ahead and 0.6 down: into the chicken
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void withoutARotationAfterTheAttackTheLookIsTheLastOneSent() {
        zombie(ZOMBIE, 3.7, 0.5);
        attack(ZOMBIE);
        tick(); // no rotation sent yet since the client loaded: the look is not known
        assertEquals(List.of(), evidence);
        look(EAST, 0F);
        tick(); // the tick's own: yRotLast
        attack(ZOMBIE);
        move(0.5, 64.0, 0.5); // the tick's own, without a rotation: unchanged
        tick();
        attack(ZOMBIE);
        tick(); // no movement packet at all: unchanged
        assertEquals(List.of(), evidence);
        look(SOUTH, 0F);
        tick();
        attack(ZOMBIE);
        tick();
        assertEquals(1, evidence.size(), "the last sent look is south");
    }

    @Test
    void aPacketThatMayBeAReplyKeepsBothLooks() {
        zombie(ZOMBIE, 3.7, 0.5);
        look(EAST, 0F);
        tick();
        out(new ClientboundPacket.PlayerRotation(SOUTH, false, 0F, false)); // not yet handled: the next rotation packet may be its reply
        look(SOUTH, 0F);
        tick();
        attack(ZOMBIE);
        tick(); // yRotLast is east if that was the reply, south if it was the tick's own
        assertEquals(List.of(), evidence);
        sync();
        tick();
        look(SOUTH, 0F); // nothing to reply to any more: the tick's own
        tick();
        attack(ZOMBIE);
        tick();
        assertEquals(1, evidence.size());
    }

    @Test
    void aTeleportsReplyIsNotTheLastSentLook() {
        zombie(ZOMBIE, 3.7, 0.5);
        look(SOUTH, 0F);
        tick();
        out(new ClientboundPacket.PlayerPosition(5, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, EAST, 0F, java.util.Set.of()));
        in(new ServerboundPacket.AcceptTeleportation(5));
        in(new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, EAST, 0F, true, true, false, false)); // the reply: yRotLast stays south
        attack(ZOMBIE);
        tick(); // looking east after the teleport, the tick would have sent east (yRotLast is south): no own rotation, no east
        assertEquals(1, evidence.size());
    }

    @Test
    void anAttackRangeItemAlsoPicksAlongTheHeadsYawOfTheTickBefore() {
        Stack stick = new Stack("minecraft:stick", 1, List.of(Stack.ATTACK_RANGE), List.of(), LONG, false, 1234);
        out(new ClientboundPacket.ContainerSetContent(0, 2, inventory(stick), Stack.EMPTY));
        sync();
        zombie(ZOMBIE, 3.7, 0.5);
        look(EAST, 0F);
        tick();
        attack(ZOMBIE);
        look(SOUTH, 0F); // the view turned away; the head's yaw is still east (Player.aiStep of the tick before)
        tick();
        assertEquals(List.of(), evidence);
        out(new ClientboundPacket.ContainerSetContent(0, 3, inventory(Stack.EMPTY), Stack.EMPTY));
        sync();
        tick();
        look(EAST, 0F);
        tick();
        attack(ZOMBIE);
        look(SOUTH, 0F);
        tick();
        assertEquals(1, evidence.size(), "without an attack range only the view picks");
    }

    @Test
    void oneLookCannotMeetEntitiesOnBothSides() {
        zombie(ZOMBIE, 3.7, 0.5);
        zombie(ZOMBIE + 1, -2.7, 0.5);
        attack(ZOMBIE);
        attack(ZOMBIE + 1);
        look(EAST, 0F);
        tick();
        assertEquals(1, evidence.size());
        assertEquals(Integer.toString(ZOMBIE + 1), evidence.getFirst().details().get("target"));
        assertEquals("1", evidence.getFirst().details().get("offAimAttacks"));
    }

    @Test
    void findingsAreAtMostOneASecondAndCountWhatCameBetween() {
        zombie(ZOMBIE, 3.7, 0.5);
        for (int i = 0; i < 3; i++) {
            attack(ZOMBIE);
            look(SOUTH + i, 0F);
            tick();
        }
        assertEquals(1, evidence.size());
        for (int i = 0; i < 20; i++) {
            tick();
        }
        attack(ZOMBIE);
        look(SOUTH, 0F);
        tick();
        assertEquals(2, evidence.size());
        assertEquals("3", evidence.get(1).details().get("offAimAttacks"));
    }

    @Test
    void noClaimNearAPistonForASpectatorOrBeforeTheClientLoaded() {
        zombie(ZOMBIE, 3.7, 0.5);
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(0, 66, 0), STATES.firstStateOf("minecraft:piston")));
        sync();
        move(0.5, 64.0, 0.5);
        tick();
        attack(ZOMBIE);
        look(SOUTH, 0F);
        tick();
        assertEquals(List.of(), evidence, "a piston near the player may have pushed it after it sent its position");
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(0, 66, 0), STATES.firstStateOf("minecraft:air")));
        out(new ClientboundPacket.PlayerInfoUpdate(false, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, 3))));
        sync();
        move(0.5, 64.0, 0.5);
        tick();
        attack(ZOMBIE);
        look(SOUTH, 0F);
        tick();
        assertEquals(List.of(), evidence, "a spectator");
        out(new ClientboundPacket.PlayerInfoUpdate(false, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, 0))));
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0));
        sync();
        move(0.5, 64.0, 0.5);
        tick();
        attack(ZOMBIE);
        look(SOUTH, 0F);
        tick();
        assertEquals(List.of(), evidence, "a new player that has not loaded");
        in(new ServerboundPacket.PlayerLoaded());
        zombie(ZOMBIE, 3.7, 0.5);
        move(0.5, 64.0, 0.5);
        tick();
        attack(ZOMBIE);
        tick();
        assertEquals(List.of(), evidence, "loaded, but no rotation sent since");
        attack(ZOMBIE);
        look(SOUTH, 0F);
        tick();
        assertEquals(1, evidence.size());
    }

    @Test
    void aPacketNotUnderstoodBetweenTheEyeAndTheAttackRejects() {
        zombie(ZOMBIE, 3.7, 0.5);
        in(new ServerboundPacket.Untranslatable("lost"));
        attack(ZOMBIE);
        look(SOUTH, 0F);
        tick();
        assertEquals(List.of(), evidence);
        assertEquals(1, rejected.size());
    }
}
