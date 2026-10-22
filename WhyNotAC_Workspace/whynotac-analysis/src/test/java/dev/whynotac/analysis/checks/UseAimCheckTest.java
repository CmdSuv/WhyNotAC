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
class UseAimCheckTest {

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
        registry.addListener(new UseAimCheck(new EvidenceSink() {
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

    private void use(float yaw, float pitch) {
        in(new ServerboundPacket.UseItem(dev.whynotac.protocol.packet.Hand.MAIN_HAND, ++useSequence, yaw, pitch));
    }

    private int useSequence;

    @Test
    void aUseWithTheLastSentLookIsNoFinding() {
        look(EAST, 10F);
        tick();
        use(EAST, 10F);
        tick(); // no rotation packet: unchanged
        use(EAST, 10F);
        move(0.5, 64.0, 0.5); // a position only: unchanged
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void aUseWithTheTicksOwnRotationIsNoFinding() {
        look(EAST, 10F);
        tick();
        use(SOUTH, -5F);
        look(SOUTH, -5F); // the tick's own packet after the use: the mouse turned before the tick
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void aUseWithAnotherRotationIsAFinding() {
        look(EAST, 10F);
        tick();
        use(SOUTH, 10F);
        tick();
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(UseAimCheck.ID, e.check());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("rotation", e.details().get("reason"));
        assertEquals("0.0/10.0", e.details().get("used"));
        assertEquals("-90.0/10.0", e.details().get("looks"));
        assertTrue(e.admissibility().admissible());
    }

    @Test
    void theUseMustCarryTheRotationTheTickSendsAfterIt() {
        look(EAST, 10F);
        tick();
        use(EAST, 10F); // the last sent look, but the tick turned: its own packet says where the player looked
        look(SOUTH, 10F);
        tick();
        assertEquals(1, evidence.size());
        assertEquals("0.0/10.0", evidence.getFirst().details().get("looks"));
    }

    @Test
    void theRotationIsComparedBitForBit() {
        look(EAST, 10F);
        tick();
        use(Math.nextUp(EAST), 10F);
        tick();
        assertEquals(1, evidence.size());
    }

    @Test
    void aPacketThatMayBeAReplyKeepsBothLooks() {
        look(EAST, 0F);
        tick();
        out(new ClientboundPacket.PlayerRotation(SOUTH, false, 0F, false)); // not yet handled: the next rotation packet may be its reply
        look(SOUTH, 0F);
        tick();
        use(EAST, 0F);
        tick();
        use(SOUTH, 0F);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClaimBeforeTheLookIsKnownOrWhileRiding() {
        use(SOUTH, 33F); // no rotation sent since the client loaded
        tick();
        assertEquals(List.of(), evidence);
        look(EAST, 0F);
        tick();
        out(ClientboundPacket.AddEntity.received(40, new UUID(0, 40), "minecraft:pig", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.SetPassengers(40, new int[] {SELF}));
        sync();
        use(SOUTH, 33F); // a vehicle may turn its passenger within the tick
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClaimIfThePlayerMayRideWhenItUsesOrWhenItsLookIsKnown() {
        look(EAST, 0F);
        tick();
        out(ClientboundPacket.AddEntity.received(40, new UUID(0, 40), "minecraft:pig", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.SetPassengers(40, new int[] {SELF}));
        sync();
        use(SOUTH, 33F); // riding when it used
        out(new ClientboundPacket.SetPassengers(40, new int[0]));
        sync();
        tick();
        assertEquals(List.of(), evidence, "riding at the use");
        look(EAST, 0F);
        tick();
        use(SOUTH, 33F);
        out(new ClientboundPacket.SetPassengers(40, new int[] {SELF}));
        sync();
        tick();
        assertEquals(List.of(), evidence, "riding when the look of the use is known");
    }

    @Test
    void findingsAreAtMostOneASecondAndCountWhatCameBetween() {
        look(EAST, 0F);
        tick();
        for (int i = 0; i < 3; i++) {
            use(SOUTH, i);
            tick();
        }
        assertEquals(1, evidence.size());
        for (int i = 0; i < 20; i++) {
            tick();
        }
        use(SOUTH, 0F);
        tick();
        assertEquals(2, evidence.size());
        assertEquals("3", evidence.get(1).details().get("rotationUses"));
    }

    @Test
    void aPacketNotUnderstoodBeforeTheUseRejects() {
        look(EAST, 0F);
        tick();
        in(new ServerboundPacket.Untranslatable("lost"));
        use(SOUTH, 0F);
        tick();
        assertEquals(List.of(), evidence);
        assertEquals(1, rejected.size());
    }

    // --- clicks on blocks ---

    private static final double EYE = 64.0 + 1.62F;
    private int clickSequence = 100;

    private void put(int x, int y, int z, String block) {
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(x, y, z), STATES.firstStateOf(block)));
    }

    private void click(int x, int y, int z, dev.whynotac.protocol.packet.BlockFace face, double cx, double cy, double cz, boolean inside) {
        in(new ServerboundPacket.UseItemOn(dev.whynotac.protocol.packet.Hand.MAIN_HAND, new BlockPosition(x, y, z), face, new Vec3d(cx, cy, cz),
                inside, false, ++clickSequence));
    }

    /** Stone at (2, 65, 0): the standing eye's level look east meets its west face at (2, 65.62, 0.5). */
    private void stoneAhead() {
        put(2, 65, 0, "minecraft:stone");
        sync();
        look(EAST, 0F);
        tick();
    }

    @Test
    void aClickWhereTheLookEntersTheBlockIsNoFinding() {
        stoneAhead();
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.0, EYE, 0.5, false);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void aClickOffTheLookIsAFinding() {
        stoneAhead();
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.0, EYE, 0.9, false); // 0.4 beside the look
        tick();
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("look", e.details().get("reason"));
        assertEquals("2,65,0", e.details().get("block"));
        assertEquals("1", e.details().get("offLookClicks"));
    }

    @Test
    void aFaceTheLookCannotEnterIsAFinding() {
        stoneAhead();
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.EAST, 2.0, EYE, 0.5, false);
        tick();
        assertEquals(1, evidence.size());
        assertEquals("face", evidence.getFirst().details().get("reason"));
    }

    @Test
    void aClickOnAirOrInsideTheBlockIsAFinding() {
        stoneAhead();
        click(3, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 3.0, EYE, 0.5, false); // the air behind the stone
        tick();
        assertEquals(1, evidence.size());
        assertEquals("cursor", evidence.getFirst().details().get("reason"));
        for (int i = 0; i < 20; i++) {
            tick();
        }
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.3, EYE, 0.5, false);
        tick();
        assertEquals(1, evidence.size(), "the client's own prediction of the first click holds the stone until the server acknowledges it");
        out(new ClientboundPacket.BlockChangedAck(clickSequence));
        sync();
        move(0.5, 64.0, 0.5); // the ack may have rolled the player back: its eye is known again from its next position
        for (int i = 0; i < 20; i++) {
            tick();
        }
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.3, EYE, 0.5, false); // on the look, inside the stone
        tick();
        assertEquals(2, evidence.size());
        assertEquals("cursor", evidence.get(1).details().get("reason"));
    }

    @Test
    void aClickOutOfReachIsAFinding() {
        put(6, 65, 0, "minecraft:stone");
        sync();
        look(EAST, 0F);
        tick();
        click(6, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 6.0, EYE, 0.5, false); // 5.5 from the eye, 4.5 the range
        tick();
        assertEquals(1, evidence.size());
        assertEquals("reach", evidence.getFirst().details().get("reason"));
        assertEquals(5.5, Double.parseDouble(evidence.getFirst().details().get("distance")), 1e-3);
        evidence.clear();
        for (int i = 0; i < 20; i++) {
            tick();
        }
        out(new ClientboundPacket.UpdateAttributes(SELF, List.of(new ClientboundPacket.UpdateAttributes.Attribute("minecraft:block_interaction_range", 6.0,
                List.of()))));
        sync();
        click(6, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 6.0, EYE, 0.5, false);
        tick();
        assertEquals(List.of(), evidence, "in the range the server gave");
    }

    @Test
    void aClickThroughABlockIsAFinding() {
        put(2, 65, 0, "minecraft:stone");
        put(4, 65, 0, "minecraft:stone");
        sync();
        look(EAST, 0F);
        tick();
        click(4, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 4.0, EYE, 0.5, false);
        tick();
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals("blocks", e.details().get("reason"));
        assertEquals("2,65,0", e.details().get("wall"));
        assertEquals(2.0, Double.parseDouble(e.details().get("stoppedBefore")), 1e-3);
    }

    @Test
    void aClickFromInsideABlockIsAtAThousandthOfTheLook() {
        put(0, 65, 0, "minecraft:stone"); // the eye is in it
        sync();
        look(EAST, 0F);
        tick();
        click(0, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 0.5 + 0.001 * 4.5, EYE, 0.5, true);
        tick();
        assertEquals(List.of(), evidence);
        click(0, 65, 0, dev.whynotac.protocol.packet.BlockFace.UP, 0.5 + 0.001 * 4.5, EYE, 0.5, true);
        tick();
        assertEquals(1, evidence.size(), "the face is the opposite of the look's nearest axis");
        assertEquals("face", evidence.getFirst().details().get("reason"));
    }

    @Test
    void noClaimFromTheWorldWhenTheClientConfirmedSomethingAfterTheClick() {
        put(2, 65, 0, "minecraft:stone");
        put(4, 65, 0, "minecraft:stone");
        sync();
        look(EAST, 0F);
        tick();
        click(4, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 4.0, EYE, 0.5, false);
        sync();
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void aBlockTheClientMayStillHoldIsEnough() {
        look(EAST, 0F);
        tick();
        put(2, 65, 0, "minecraft:stone"); // not yet acknowledged: the client may have the stone, or still the air
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.0, EYE, 0.5, false);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClickClaimWithAnAttackRangeItem() {
        Stack stick = new Stack("minecraft:stick", 1, List.of(Stack.ATTACK_RANGE), List.of(), LONG, false, 1234);
        out(new ClientboundPacket.ContainerSetContent(0, 2, inventory(stick), Stack.EMPTY));
        stoneAhead();
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.0, EYE, 0.9, false);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void theFacesALookCanEnterAndTheNearestOpposite() {
        double[] east = {1, 0, 0};
        assertTrue(BlockClick.entersThrough(east, dev.whynotac.protocol.packet.BlockFace.WEST, 4.5));
        assertTrue(!BlockClick.entersThrough(east, dev.whynotac.protocol.packet.BlockFace.EAST, 4.5));
        assertTrue(!BlockClick.entersThrough(east, dev.whynotac.protocol.packet.BlockFace.UP, 4.5), "no motion along y");
        double[] down = AttackAim.view(60F, EAST);
        assertTrue(BlockClick.entersThrough(down, dev.whynotac.protocol.packet.BlockFace.UP, 4.5));
        assertTrue(BlockClick.nearestOpposite(down, dev.whynotac.protocol.packet.BlockFace.UP));
        assertTrue(!BlockClick.nearestOpposite(down, dev.whynotac.protocol.packet.BlockFace.WEST));
        double[] diagonal = {Math.sqrt(0.5), 0, Math.sqrt(0.5)};
        assertTrue(BlockClick.nearestOpposite(diagonal, dev.whynotac.protocol.packet.BlockFace.WEST), "a tie: either");
        assertTrue(BlockClick.nearestOpposite(diagonal, dev.whynotac.protocol.packet.BlockFace.NORTH));
    }

    @Test
    void noCursorClaimOnAnOutlineThatDependsOnTheHeldItem() {
        put(2, 65, 0, "minecraft:light"); // a full block while the player holds light, empty otherwise
        sync();
        look(EAST, 0F);
        tick();
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.0, EYE, 0.5, false);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void noCursorClaimWhileAnItemUseMayHavePlacedABlockAnywhere() {
        look(EAST, 0F);
        tick();
        in(new ServerboundPacket.UseItem(dev.whynotac.protocol.packet.Hand.MAIN_HAND, 50, EAST, 0F)); // a lily pad: where its own ray hit
        tick();
        click(2, 65, 0, dev.whynotac.protocol.packet.BlockFace.WEST, 2.0, EYE, 0.5, false); // air here, unless that placed something
        tick();
        assertEquals(List.of(), evidence);
    }
}
