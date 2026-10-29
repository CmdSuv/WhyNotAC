package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.PositionCodec;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientEntitiesTest {

    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final ClientboundPacket.Dimension NETHER = new ClientboundPacket.Dimension("minecraft:the_nether", 0, 256);
    private static final int SELF = 1;
    private static final UUID ALICE = new UUID(1, 2);

    private ClientEntities entities;
    private long sequence;
    private int pingId;

    @BeforeEach
    void setUp() {
        entities = new ClientEntities(EntityTypes.vanilla());
        sequence = 0;
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD));
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                out(new ClientboundPacket.LevelChunk(x, z, null));
            }
        }
        sync();
    }

    // --- helpers ---

    private void out(ClientboundPacket packet) {
        entities.onEvent(new TimelineEvent.Outbound(sequence++, sequence, packet));
    }

    private void tick() {
        entities.onEvent(new TimelineEvent.Inbound(sequence++, sequence, 0, new ServerboundPacket.ClientTickEnd()));
    }

    private int ping() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        return id;
    }

    private void ack(int id) {
        entities.onEvent(new TimelineEvent.Acknowledged(sequence++, sequence, id, 0, 0));
    }

    /** The client processed everything sent so far. */
    private void sync() {
        ack(ping());
    }

    private static ClientboundPacket.AddEntity add(int id, String type, double x, double y, double z) {
        return ClientboundPacket.AddEntity.received(id, new UUID(0, id), type, x, y, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0);
    }

    private static ClientboundPacket.MoveEntity move(int id, double fromX, double toX) {
        return new ClientboundPacket.MoveEntity(id, (short) PositionCodec.delta(fromX, toX), (short) 0, (short) 0, (byte) 0, (byte) 0, true,
                true, false);
    }

    private List<ClientEntities.PossibleState> states(int id) {
        List<ClientEntities.PossibleState> states = new ArrayList<>();
        entities.forEachPossibleState(id, states::add);
        return states;
    }

    // --- tests ---

    @Test
    void anAddedEntityIsWhereThePacketPutIt() {
        out(add(5, "minecraft:zombie", 1.5, 64.0, -2.25));
        assertNull(entities.exactPosition(5), "not processed yet: absent or present");
        assertEquals(ClientEntities.Existence.MAYBE, entities.existence(5));
        sync();
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(5));
        assertEquals(new Vec3d(1.5, 64.0, -2.25), entities.exactPosition(5));
        assertEquals(ClientEntities.Existence.ABSENT, entities.existence(6), "never added");
    }

    @Test
    void livingEntitiesInterpolateOverThreeTicksExactlyAsTheClient() {
        out(add(5, "minecraft:zombie", 0.5, 64.0, 0.5));
        sync();
        double target = PositionCodec.decode(new Vec3d(0.5, 64.0, 0.5), PositionCodec.delta(0.5, 1.3), 0, 0).x();
        out(move(5, 0.5, 1.3));
        sync();
        assertEquals(0.5, entities.exactPosition(5).x(), "interpolateTo moves nothing by itself");
        double x = 0.5;
        for (int steps = 3; steps >= 1; steps--) {
            tick();
            x = VanillaMath.lerp(1.0 / steps, x, target);
            assertEquals(x, entities.exactPosition(5).x(), 0.0, "step " + (4 - steps));
        }
        assertEquals(target, x, 0.0);
        tick();
        assertEquals(target, entities.exactPosition(5).x(), 0.0, "done: stays");
    }

    @Test
    void boatsTheClientDoesNotControlInterpolateLikeLivingEntities() {
        out(add(5, "minecraft:oak_boat", 0.5, 64.0, 0.5));
        sync();
        double target = PositionCodec.decode(new Vec3d(0.5, 64.0, 0.5), PositionCodec.delta(0.5, 1.3), 0, 0).x();
        out(move(5, 0.5, 1.3));
        sync();
        double x = 0.5;
        for (int steps = 3; steps >= 1; steps--) {
            tick();
            x = VanillaMath.lerp(1.0 / steps, x, target);
            assertEquals(x, entities.exactPosition(5).x(), 0.0, "step " + (4 - steps));
        }
        assertNull(entities.mounted(5), "nobody rode it");
    }

    @Test
    void aBoatsClientStateIsKeptWhenItsPlayerMountsIt() {
        out(add(5, "minecraft:oak_boat", 0.5, 64.0, 0.5));
        sync();
        tick();
        out(new ClientboundPacket.EntityPositionSync(5, new Vec3d(0.5, 63.9, 0.5), Vec3d.ZERO, 90.0F, 0.0F, false));
        sync();
        tick(); // one of the three steps: a third of the way down
        double y = VanillaMath.lerp(1.0 / 3, 64.0, 63.9);
        out(ClientboundPacket.SetEntityMotion.received(5, new Vec3d(0.0, 0.25, 0.0)));
        out(data(5, 5, ClientboundPacket.SetEntityData.Kind.BOOLEAN, 1)); // DATA_NO_GRAVITY
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        List<ClientEntities.Mounted> mounted = entities.mounted(5);
        assertNotNull(mounted);
        assertEquals(1, mounted.size());
        ClientEntities.Mounted m = mounted.getFirst();
        assertTrue(m.exact());
        assertEquals(new Vec3d(0.5, y, 0.5), m.position());
        assertEquals(2, m.steps(), "two steps of the interpolation left");
        assertEquals(new Vec3d(0.5, 63.9, 0.5), m.target());
        assertEquals(new Vec3d(0.5, y, 0.5), m.previousPosition(), "the last step's position");
        assertEquals(new Vec3d(0.5, 64.0, 0.5), m.lastTickStart(), "its last tick started before that step");
        assertEquals(new Vec3d(0.0, 0.25, 0.0), m.motion(), "set after its last tick");
        assertTrue(m.noGravity());
        assertTrue(m.everTicked());
        assertEquals(0, m.onGround());
        assertEquals(new Vec3d(0.5, 64.0, 0.5), m.groundSetAt(), "the sync set the flag where the boat was then");
        assertNull(entities.exactPosition(5), "the client moves it itself now");
        tick();
        assertEquals(mounted, entities.mounted(5), "kept after its tick");
    }

    @Test
    void aBoatMountedAgainHasNoStateFromTheLastMount() {
        out(add(5, "minecraft:oak_boat", 0.5, 64.0, 0.5));
        sync();
        tick();
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        assertNotNull(entities.mounted(5));
        out(new ClientboundPacket.SetPassengers(5, new int[] {}));
        sync();
        tick();
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF})); // where the player left it: not followed
        sync();
        assertNull(entities.mounted(5), "the first mount's state is not this one's");
    }

    /** The client's own player sends where it is (a MovePlayer with a position, in a tick of its own). */
    private void selfAt(double x, double y, double z) {
        entities.onEvent(new TimelineEvent.Inbound(sequence++, sequence, 0, new ServerboundPacket.MovePlayer(x, y, z, 0.0F, 0.0F, true, false, true, false)));
    }

    private static ClientboundPacket.SetEquipment saddle(int id) {
        return new ClientboundPacket.SetEquipment(id, List.of(new ClientboundPacket.SetEquipment.Slot("saddle",
                new dev.whynotac.protocol.packet.Stack("minecraft:saddle", 1, List.of(), List.of(), null, false))));
    }

    /** One horse tick on a client that does not control it: the motion slowed by 0.98, each part below 0.003 zeroed. */
    private static Vec3d slowed(Vec3d m) {
        Vec3d s = m.scale(0.98);
        return new Vec3d(Math.abs(s.x()) < 0.003 ? 0.0 : s.x(), Math.abs(s.y()) < 0.003 ? 0.0 : s.y(), Math.abs(s.z()) < 0.003 ? 0.0 : s.z());
    }

    @Test
    void aHorsesClientStateIsKeptWhenItsPlayerMountsIt() {
        selfAt(10.5, 64.0, 10.5);
        tick();
        out(ClientboundPacket.AddEntity.received(5, new UUID(0, 5), "minecraft:horse", 0.5, 64.0, 0.5, new Vec3d(0.1, -0.0784, 0.0031), (byte) 0,
                (byte) 0, (byte) 0, 0));
        out(saddle(5));
        out(data(5, 17, ClientboundPacket.SetEntityData.Kind.BYTE, 2 | 32)); // tame, standing
        out(new ClientboundPacket.UpdateAttributes(5, List.of(new ClientboundPacket.UpdateAttributes.Attribute("minecraft:movement_speed", 0.3, List.of()),
                new ClientboundPacket.UpdateAttributes.Attribute("minecraft:jump_strength", 0.8, List.of()))));
        sync();
        selfAt(10.5, 64.0, 10.5);
        tick();
        selfAt(10.5, 64.0, 10.5);
        tick();
        Vec3d motion = slowed(slowed(new Vec3d(0.1, -0.0784, 0.0031)));
        out(new ClientboundPacket.MobEffect(5, "minecraft:jump_boost", 1, 200, 0)); // sendActiveEffects, before the passengers
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        List<ClientEntities.HorseMounted> mounted = entities.horseMounted(5);
        assertNotNull(mounted);
        assertEquals(1, mounted.size());
        ClientEntities.HorseMounted m = mounted.getFirst();
        assertTrue(m.exact());
        assertEquals(motion, m.motion(), "slowed twice, its z part zeroed in the second");
        assertEquals(0.0, m.motion().z());
        assertEquals(new Vec3d(0.5, 64.0, 0.5), m.position());
        assertTrue(m.everTicked());
        assertEquals(1, m.saddle());
        assertEquals(2 | 32, m.flags());
        assertEquals(java.util.Map.of("minecraft:movement_speed", 0.3, "minecraft:jump_strength", 0.8), m.attributes());
        assertEquals(java.util.Map.of("minecraft:jump_boost", 1), m.effects());
        assertEquals(new Vec3d(0.5, 64.0, 0.5), m.sweptMin(), "it stood still since it was added");
        assertNull(entities.horseMounted(6));
        assertNull(entities.mounted(5), "not a boat");
    }

    @Test
    void aPigsBoostStartsWhenItsBoostTimeReachesTheClient() {
        out(ClientboundPacket.AddEntity.received(5, new UUID(0, 5), "minecraft:pig", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(saddle(5));
        out(data(5, 17, ClientboundPacket.SetEntityData.Kind.INT, 300)); // ItemBasedSteering.onSynced
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        ClientEntities.HorseMounted m = entities.horseMounted(5).getFirst();
        assertEquals(1, m.saddle());
        assertEquals(1, m.boost(), "started, counted in no tick: nobody steered it");
        assertEquals(300, m.boostTotal());
        assertEquals(0, m.flags(), "a pig's index 17 is its boost time, not a horse's flags");
    }

    @Test
    void aPigsBoostAPlayerMayHaveCountedIsNotKnown() {
        out(ClientboundPacket.AddEntity.received(5, new UUID(0, 5), "minecraft:pig", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        out(ClientboundPacket.AddEntity.received(6, ALICE, EntityTypes.PLAYER, 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(data(5, 17, ClientboundPacket.SetEntityData.Kind.INT, 300));
        out(new ClientboundPacket.SetPassengers(5, new int[] {6})); // another player in it may steer it: its ticks count the boost
        out(new ClientboundPacket.SetPassengers(5, new int[] {}));
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        assertEquals(-1, entities.horseMounted(5).getFirst().boost());
    }

    @Test
    void aPigNeverBoostedOrBoostedAgainIsKnown() {
        out(ClientboundPacket.AddEntity.received(5, new UUID(0, 5), "minecraft:pig", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        out(ClientboundPacket.AddEntity.received(6, ALICE, EntityTypes.PLAYER, 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.SetPassengers(5, new int[] {6})); // not boosting: nothing to count
        out(new ClientboundPacket.SetPassengers(5, new int[] {}));
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        assertEquals(0, entities.horseMounted(5).getFirst().boost());
        out(new ClientboundPacket.SetPassengers(5, new int[] {}));
        out(data(5, 17, ClientboundPacket.SetEntityData.Kind.INT, 500)); // after the ride: from 0 again
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        assertNull(entities.horseMounted(5), "ridden by the player before: its position not followed since");
    }

    @Test
    void aHorsesAttributesAreClampedToTheirRangesAsTheClientDoes() {
        out(ClientboundPacket.AddEntity.received(5, new UUID(0, 5), "minecraft:horse", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.UpdateAttributes(5, List.of(new ClientboundPacket.UpdateAttributes.Attribute("minecraft:movement_speed", 2000.0, List.of()),
                new ClientboundPacket.UpdateAttributes.Attribute("minecraft:gravity", -3.0, List.of()))));
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        // RangedAttribute.sanitizeValue: movement speed at most 1024, gravity at least -1
        assertEquals(java.util.Map.of("minecraft:movement_speed", 1024.0, "minecraft:gravity", -1.0), entities.horseMounted(5).getFirst().attributes());
    }

    @Test
    void aHorseThePlayerStoodInMayHaveBeenPushed() {
        out(ClientboundPacket.AddEntity.received(5, new UUID(0, 5), "minecraft:horse", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        selfAt(1.0, 64.0, 0.5); // inside its box: its pushEntities pushes the player, and itself
        tick();
        selfAt(1.0, 64.0, 0.5);
        tick();
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        assertNull(entities.horseMounted(5).getFirst().motion(), "its motion is not known");
    }

    @Test
    void aHorsesMotionIsSetAgainByTheServerAndKeptWhileItInterpolates() {
        out(ClientboundPacket.AddEntity.received(5, new UUID(0, 5), "minecraft:horse", 0.5, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        tick(); // no position sent yet: the player may be anywhere
        selfAt(10.5, 64.0, 10.5);
        tick(); // one: where it was during the tick also needs the one before
        out(ClientboundPacket.SetEntityMotion.received(5, new Vec3d(0.2, 0.0, 0.0)));
        out(move(5, 0.5, 1.3)); // three steps toward it, which keep the motion as it is
        selfAt(10.5, 64.0, 10.5);
        sync();
        tick();
        selfAt(10.5, 64.0, 10.5);
        tick();
        out(new ClientboundPacket.SetPassengers(5, new int[] {SELF}));
        sync();
        ClientEntities.HorseMounted m = entities.horseMounted(5).getFirst();
        assertEquals(new Vec3d(0.2, 0.0, 0.0), m.motion(), "set again after the tick that did not know where the player was");
        assertEquals(1, m.steps(), "two of the three steps taken");
        assertTrue(m.sweptMax().x() > 0.5, "it moved toward the target since");
    }

    @Test
    void aBoatAnotherPassengerControlsStaysFollowed() {
        out(add(5, "minecraft:oak_boat", 0.5, 64.0, 0.5));
        out(add(6, "minecraft:zombie", 0.5, 64.0, 0.5));
        sync();
        tick();
        out(new ClientboundPacket.SetPassengers(5, new int[] {6, SELF}));
        sync();
        tick();
        assertNull(entities.mounted(5));
        assertEquals(new Vec3d(0.5, 64.0, 0.5), entities.exactPosition(5), "its first passenger, not the player, controls it");
    }

    @Test
    void entitiesWithoutInterpolationMoveAtOnce() {
        out(add(5, "minecraft:end_crystal", 0.5, 64.0, 0.5));
        sync();
        out(move(5, 0.5, 2.0));
        sync();
        assertEquals(2.0, entities.exactPosition(5).x(), 0.0);
    }

    @Test
    void aMoveBetweenTwoAnsweredPingsAroundNoTickIsExact() {
        out(add(5, "minecraft:zombie", 0.5, 64.0, 0.5));
        sync();
        int before = ping();
        out(move(5, 0.5, 1.5));
        int after = ping();
        ack(before);
        ack(after); // both answered before the next tick: processed together, before it
        tick();
        assertEquals(VanillaMath.lerp(1.0 / 3, 0.5, 1.5), entities.exactPosition(5).x(), 0.0);
    }

    @Test
    void aTickBetweenTheAnswersLeavesBothOrders() {
        out(add(5, "minecraft:zombie", 0.5, 64.0, 0.5));
        sync();
        int before = ping();
        out(move(5, 0.5, 1.5));
        int after = ping();
        ack(before);
        tick(); // the move may have been processed before this tick or after it
        ack(after);
        List<Double> xs = states(5).stream().map(ClientEntities.PossibleState::x).sorted().toList();
        assertEquals(List.of(0.5, VanillaMath.lerp(1.0 / 3, 0.5, 1.5)), xs);
        assertNull(entities.exactPosition(5));
        // Whichever it was, three more ticks without packets end at the target in both.
        tick();
        tick();
        tick();
        assertEquals(1, states(5).size(), "merged");
        ClientEntities.PossibleState merged = states(5).getFirst();
        assertTrue(Math.abs(merged.x() - 1.5) <= merged.positionRadius() + 1e-12);
        assertTrue(merged.positionRadius() < 1e-9, "the same target ends both: " + merged.positionRadius());
    }

    @Test
    void mergedCandidatesStayBoundedWhileTheEntityKeepsMoving() {
        out(add(5, "minecraft:zombie", 0.5, 64.0, 0.5));
        sync();
        int before = ping();
        out(move(5, 0.5, 1.0));
        int after = ping();
        ack(before);
        tick();
        ack(after);
        double x = 1.0;
        for (int i = 0; i < 20; i++) {
            out(move(5, x, x + 0.25));
            x = PositionCodec.decode(PositionCodec.encode(x) + PositionCodec.delta(x, x + 0.25));
            sync();
            tick();
        }
        List<ClientEntities.PossibleState> states = states(5);
        assertEquals(1, states.size());
        assertTrue(states.getFirst().positionRadius() < 1e-3, "the difference shrinks by a third each tick: " + states.getFirst().positionRadius());
    }

    @Test
    void aPlayerWithoutAProfileIsNotCreated() {
        out(add(5, EntityTypes.PLAYER, 0, 64, 0));
        sync();
        assertEquals(ClientEntities.Existence.ABSENT, entities.existence(5));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        out(ClientboundPacket.AddEntity.received(6, ALICE, EntityTypes.PLAYER, 0, 64, 0, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(6));
        out(new ClientboundPacket.PlayerInfoRemove(List.of(ALICE)));
        sync();
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(6), "the entity stays");
    }

    @Test
    void removedAndReplacedEntities() {
        out(add(5, "minecraft:zombie", 0, 64, 0));
        sync();
        out(new ClientboundPacket.RemoveEntities(new int[] {5}));
        sync();
        assertEquals(ClientEntities.Existence.ABSENT, entities.existence(5));
        out(add(7, "minecraft:zombie", 0, 64, 0));
        out(add(7, "minecraft:end_crystal", 3, 64, 0)); // the same id again: the client replaces it
        sync();
        assertEquals(new Vec3d(3, 64, 0), entities.exactPosition(7));
        assertEquals("minecraft:end_crystal", states(7).getFirst().type());
    }

    @Test
    void respawnKeepsEntitiesOnlyInTheSameDimension() {
        out(add(5, "minecraft:zombie", 0, 64, 0));
        sync();
        out(new ClientboundPacket.Respawn(OVERWORLD));
        sync();
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(5));
        out(new ClientboundPacket.Respawn(NETHER));
        sync();
        assertEquals(ClientEntities.Existence.ABSENT, entities.existence(5));
    }

    @Test
    void positionSyncInterpolatesNearbyAndSnapsFarAway() {
        out(add(5, "minecraft:zombie", 0.5, 64, 0.5));
        sync();
        out(new ClientboundPacket.EntityPositionSync(5, new Vec3d(10.5, 64, 0.5), Vec3d.ZERO, 0, 0, true));
        sync();
        assertEquals(0.5, entities.exactPosition(5).x(), 0.0, "10 blocks: interpolated");
        out(new ClientboundPacket.EntityPositionSync(5, new Vec3d(100.5, 64, 0.5), Vec3d.ZERO, 0, 0, true));
        sync();
        assertEquals(100.5, entities.exactPosition(5).x(), 0.0, "100 blocks: snapped");
        assertEquals(new Vec3d(100.5, 64, 0.5), entities.serverOrderBase(5));
    }

    @Test
    void aMobInAChunkTheClientDoesNotHoldDoesNotTick() {
        out(add(5, "minecraft:zombie", 100.5, 64, 0.5)); // chunk (6, 0): not loaded
        out(add(6, EntityTypes.PLAYER, 100.5, 64, 0.5));
        sync();
        out(move(5, 100.5, 101.5));
        sync();
        tick();
        assertEquals(100.5, entities.exactPosition(5).x(), 0.0, "not ticking: no interpolation");
        out(new ClientboundPacket.LevelChunk(6, 0, null));
        sync();
        tick();
        assertEquals(VanillaMath.lerp(1.0 / 3, 100.5, 101.5), entities.exactPosition(5).x(), 0.0);
    }

    @Test
    void passengersAreNotPlaced() {
        out(add(5, "minecraft:zombie", 0, 64, 0));
        out(add(6, "minecraft:horse", 0, 64, 0));
        out(new ClientboundPacket.SetPassengers(6, new int[] {5}));
        sync();
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(5));
        assertNull(entities.exactPosition(5));
        assertEquals(6, entities.serverOrderVehicle(5));
        assertNotNull(entities.exactPosition(6));
    }

    @Test
    void aVehicleThePlayerMayControlIsNotPlaced() {
        out(add(6, "minecraft:horse", 0, 64, 0));
        out(new ClientboundPacket.SetPassengers(6, new int[] {SELF}));
        sync();
        assertNull(entities.exactPosition(6));
    }

    @Test
    void lostEventsMakeEveryEntityUnknownUntilAddedAgain() {
        out(add(5, "minecraft:zombie", 0, 64, 0));
        sync();
        entities.onEvent(new TimelineEvent.Gap(sequence++, sequence, 3));
        assertEquals(ClientEntities.Existence.UNKNOWN, entities.existence(5));
        assertEquals(ClientEntities.Existence.UNKNOWN, entities.existence(9));
        out(add(5, "minecraft:zombie", 2, 64, 0));
        sync();
        assertEquals(new Vec3d(2, 64, 0), entities.exactPosition(5));
    }

    @Test
    void anUnreadableEntityPacketLosesTheEntities() {
        out(add(5, "minecraft:zombie", 0, 64, 0));
        sync();
        out(new ClientboundPacket.Untranslatable("minecraft:move_entity_pos"));
        sync();
        assertEquals(ClientEntities.Existence.UNKNOWN, entities.existence(5));
    }

    @Test
    void headRotationInterpolatesOverThreeTicks() {
        out(add(5, "minecraft:zombie", 0, 64, 0));
        sync();
        byte packed = VanillaMath.packDegrees(90.0F);
        out(new ClientboundPacket.RotateHead(5, packed));
        sync();
        float head = 0.0F;
        float target = VanillaMath.unpackDegrees(packed);
        for (int steps = 3; steps >= 1; steps--) {
            tick();
            head = (float) VanillaMath.rotLerp(1.0 / steps, (double) head, (double) target);
            assertEquals(head, states(5).getFirst().yHeadRot(), 0.0F);
        }
    }

    @Test
    void aFrozenGameStopsMobsButNotPlayers() {
        out(new ClientboundPacket.PlayerInfoUpdate(true, false, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, -1))));
        out(add(5, "minecraft:zombie", 0.5, 64, 0.5));
        out(ClientboundPacket.AddEntity.received(6, ALICE, EntityTypes.PLAYER, 0.5, 64, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.TickingState(20.0F, true));
        out(move(5, 0.5, 1.5));
        out(move(6, 0.5, 1.5));
        sync();
        tick();
        assertEquals(VanillaMath.lerp(1.0 / 3, 0.5, 1.5), entities.exactPosition(6).x(), 0.0, "players tick");
        assertNull(entities.exactPosition(5), "a frozen mob may tick (if it carries a player) or not");
        assertEquals(2, states(5).size());
    }

    @Test
    void anUnansweredPingKeepsLaterPacketsUnprocessed() {
        out(add(5, "minecraft:zombie", 0.5, 64, 0.5));
        sync();
        ping(); // never answered
        out(move(5, 0.5, 1.5));
        tick();
        tick();
        assertEquals(0.5, entities.exactPosition(5).x(), 0.0, "behind an unanswered ping: not processed");
    }

    @Test
    void teleportEntityInterpolatesNearbySetsFarAwayAndAddsRelativeParts() {
        out(add(5, "minecraft:zombie", 0.5, 64, 0.5));
        sync();
        out(new ClientboundPacket.TeleportEntity(5, new Vec3d(2.5, 64, 0.5), Vec3d.ZERO, 0, 0, java.util.Set.of(), true));
        sync();
        assertEquals(0.5, entities.exactPosition(5).x(), 0.0, "near: interpolated");
        tick();
        assertEquals(VanillaMath.lerp(1.0 / 3, 0.5, 2.5), entities.exactPosition(5).x(), 0.0);
        // Relative to the interpolation target (PositionMoveRotation.of while interpolating): 2.5 + 1.0.
        out(new ClientboundPacket.TeleportEntity(5, new Vec3d(1.0, 0, 0), Vec3d.ZERO, 0, 0,
                java.util.Set.of(dev.whynotac.protocol.packet.RelativeFlag.X, dev.whynotac.protocol.packet.RelativeFlag.Y,
                        dev.whynotac.protocol.packet.RelativeFlag.Z), true));
        sync();
        tick();
        tick();
        tick();
        assertEquals(3.5, entities.exactPosition(5).x(), 1e-12);
        out(new ClientboundPacket.TeleportEntity(5, new Vec3d(300.5, 70, 0.5), Vec3d.ZERO, 0, 0, java.util.Set.of(), true));
        sync();
        assertEquals(new Vec3d(300.5, 70, 0.5), entities.exactPosition(5), "far: set at once");
        assertEquals(new Vec3d(0.5, 64, 0.5), entities.serverOrderBase(5), "a teleport does not move the codec base");
    }

    @Test
    void aSleepingEntityIsPlacedByItsBed() {
        out(add(5, "minecraft:villager", 0.5, 64, 0.5));
        out(new ClientboundPacket.SetEntityData(5, List.of(new ClientboundPacket.SetEntityData.DataEntry(6,
                ClientboundPacket.SetEntityData.Kind.POSE, ClientboundPacket.EntityPose.SLEEPING.ordinal())), 1));
        sync();
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(5));
        assertNull(entities.exactPosition(5));
    }

    @Test
    void aForgottenChunkStopsItsMobsAndAShrunkViewDoesNot() {
        out(add(5, "minecraft:zombie", 0.5, 64, 0.5));
        sync();
        out(new ClientboundPacket.ForgetLevelChunk(0, 0));
        out(move(5, 0.5, 1.5));
        sync();
        tick();
        assertEquals(0.5, entities.exactPosition(5).x(), 0.0, "its chunk was unloaded: no tick");
        out(new ClientboundPacket.LevelChunk(0, 0, null));
        out(new ClientboundPacket.ChunkCacheRadius(2)); // the storage shrinks; the chunk stays loaded
        sync();
        tick();
        assertEquals(VanillaMath.lerp(1.0 / 3, 0.5, 1.5), entities.exactPosition(5).x(), 0.0);
    }

    @Test
    void aSwitchToConfigurationEmptiesTheLevel() {
        out(add(5, "minecraft:zombie", 0.5, 64, 0.5));
        sync();
        out(new ClientboundPacket.Unmodeled("minecraft:start_configuration"));
        sync();
        assertEquals(ClientEntities.Existence.ABSENT, entities.existence(5), "no level, no entities");
        out(add(6, "minecraft:zombie", 0.5, 64, 0.5)); // dropped: there is no level to add it to
        sync();
        assertEquals(ClientEntities.Existence.ABSENT, entities.existence(6));
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD));
        sync();
        assertEquals(ClientEntities.Existence.ABSENT, entities.existence(5));
    }

    // --- sizes ---

    private static final int STANDING = ClientboundPacket.EntityPose.STANDING.ordinal();
    private static final EntitySizes SIZES = EntitySizes.vanilla();

    private EntitySizes.Size onlySize(int id) {
        List<ClientEntities.PossibleState> states = states(id);
        assertEquals(1, states.size(), "one possible state");
        return states.getFirst().size();
    }

    private Set<EntitySizes.Size> sizes(int id) {
        Set<EntitySizes.Size> sizes = new HashSet<>();
        for (ClientEntities.PossibleState state : states(id)) {
            sizes.add(state.size());
        }
        return sizes;
    }

    private static ClientboundPacket.SetEntityData data(int id, int index, ClientboundPacket.SetEntityData.Kind kind, long value) {
        return new ClientboundPacket.SetEntityData(id, List.of(new ClientboundPacket.SetEntityData.DataEntry(index, kind, value)), 0);
    }

    private static ClientboundPacket.UpdateAttributes scale(int id, double base, ClientboundPacket.UpdateAttributes.Modifier... modifiers) {
        return new ClientboundPacket.UpdateAttributes(id,
                List.of(new ClientboundPacket.UpdateAttributes.Attribute(ClientEntities.SCALE_ATTRIBUTE, base, List.of(modifiers))));
    }

    private static ClientboundPacket.UpdateAttributes.Modifier modifier(double amount, ClientboundPacket.UpdateAttributes.Operation operation) {
        return new ClientboundPacket.UpdateAttributes.Modifier("test:" + operation + amount, amount, operation);
    }

    @Test
    void aBabyHasItsBabySizeOnceTheClientProcessedItsFlag() {
        int flag = SIZES.entry("minecraft:husk").babyIndex();
        EntitySizes.Size adult = SIZES.size("minecraft:husk", STANDING, false, 1.0);
        EntitySizes.Size baby = SIZES.size("minecraft:husk", STANDING, true, 1.0);
        out(add(5, "minecraft:husk", 0.5, 64.0, 0.5));
        sync();
        assertEquals(adult, onlySize(5), "a new entity has the default data: not a baby");
        out(data(5, flag, ClientboundPacket.SetEntityData.Kind.BOOLEAN, 1));
        assertEquals(Set.of(adult, baby), sizes(5), "not processed yet: either");
        sync();
        assertEquals(baby, onlySize(5));
        out(data(5, flag + 1, ClientboundPacket.SetEntityData.Kind.BOOLEAN, 0));
        sync();
        assertEquals(baby, onlySize(5), "another index is not the flag a husk reads");
    }

    @Test
    void theScaleAttributeScalesTheSizeWhenItsValueIsExact() {
        EntitySizes.Size adult = SIZES.size("minecraft:husk", STANDING, false, 1.0);
        out(add(5, "minecraft:husk", 0.5, 64.0, 0.5));
        out(scale(5, 1.5));
        sync();
        assertNull(onlySize(5), "the box follows the new scale only at the end of the entity's next tick");
        tick();
        assertEquals(adult.scale(1.5F), onlySize(5));
        // One modifier per operation: AttributeInstance.calculateValue in its fixed operation order.
        out(scale(5, 1.0, modifier(0.5, ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL),
                modifier(0.5, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE),
                modifier(0.5, ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_BASE)));
        sync();
        tick();
        assertEquals(adult.scale((float) ((1.5 + 1.5 * 0.5) * 1.5)), onlySize(5));
        // Two of one operation: the client sums them in its hash map's order, which is not followed.
        out(scale(5, 1.0, modifier(0.1, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE),
                modifier(0.2, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE)));
        sync();
        tick();
        assertNull(onlySize(5));
        // The attribute's range: at most 16.
        out(scale(5, 40.0));
        sync();
        tick();
        assertEquals(adult.scale(16.0F), onlySize(5));
        // The same value again changes nothing: no wait.
        out(scale(5, 16.0));
        sync();
        assertEquals(adult.scale(16.0F), onlySize(5));
    }

    @Test
    void aNonLivingEntityIgnoresAttributes() {
        out(add(5, "minecraft:end_crystal", 0.5, 64.0, 0.5));
        out(scale(5, 3.0));
        sync();
        assertEquals(SIZES.entry("minecraft:end_crystal").size(), onlySize(5));
    }

    @Test
    void aPlayersSizeFollowsItsPose() {
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        out(ClientboundPacket.AddEntity.received(6, ALICE, EntityTypes.PLAYER, 0, 64, 0, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        assertEquals(SIZES.size(EntityTypes.PLAYER, STANDING, false, 1.0), onlySize(6));
        int crouching = ClientboundPacket.EntityPose.CROUCHING.ordinal();
        out(data(6, 6, ClientboundPacket.SetEntityData.Kind.POSE, crouching));
        sync();
        assertEquals(new EntitySizes.Size(0.6F, 1.5F, 1.27F, false), onlySize(6));
    }

    @Test
    void sizesFollowTheTypesData() {
        // A slime starts at size 1 (defineSynchedData); its size data scales it once processed.
        EntitySizes.DataField slimeSize = SIZES.entry("minecraft:slime").data().getFirst();
        out(add(5, "minecraft:slime", 0.5, 64.0, 0.5));
        sync();
        assertEquals(SIZES.entry("minecraft:slime").size(), onlySize(5));
        out(data(5, slimeSize.index(), ClientboundPacket.SetEntityData.Kind.INT, 4));
        assertEquals(2, sizes(5).size(), "not processed yet: either");
        sync();
        assertEquals(SIZES.entry("minecraft:slime").size().scale(4.0F), onlySize(5));
        out(data(5, slimeSize.index(), ClientboundPacket.SetEntityData.Kind.FLOAT, 7));
        sync();
        assertEquals(SIZES.entry("minecraft:slime").size().scale(4.0F), onlySize(5), "another serializer at the index is not the size");

        // An armor stand's flags: small, then marker (fixed, no scale changes it).
        EntitySizes.DataField flags = SIZES.entry("minecraft:armor_stand").data().getFirst();
        out(add(6, "minecraft:armor_stand", 2.5, 64.0, 0.5));
        out(data(6, flags.index(), ClientboundPacket.SetEntityData.Kind.BYTE, (byte) EntitySizes.ARMOR_STAND_SMALL));
        sync();
        assertEquals(SIZES.entry("minecraft:armor_stand").alt(), onlySize(6));
        out(data(6, flags.index(), ClientboundPacket.SetEntityData.Kind.BYTE, (byte) (EntitySizes.ARMOR_STAND_MARKER | 0x80)));
        out(scale(6, 2.0));
        sync();
        assertEquals(new EntitySizes.Size(0.0F, 0.0F, 0.0F, true), onlySize(6));

        // An interaction: width and height from two floats, in either packet.
        List<EntitySizes.DataField> box = SIZES.entry("minecraft:interaction").data();
        out(add(7, "minecraft:interaction", 4.5, 64.0, 0.5));
        sync();
        assertEquals(new EntitySizes.Size(1.0F, 1.0F, 0.85F, false), onlySize(7), "1 x 1 until told otherwise");
        out(data(7, box.get(1).index(), ClientboundPacket.SetEntityData.Kind.FLOAT, Float.floatToRawIntBits(0.5F)));
        out(data(7, box.get(0).index(), ClientboundPacket.SetEntityData.Kind.FLOAT, Float.floatToRawIntBits(2.25F)));
        sync();
        assertEquals(new EntitySizes.Size(2.25F, 0.5F, 0.5F * 0.85F, false), onlySize(7));
    }

    @Test
    void spectatorsMarkersAndTaggedProjectilesAreNotPickedOrNotClaimed() {
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 3))));
        out(ClientboundPacket.AddEntity.received(6, ALICE, EntityTypes.PLAYER, 0, 64, 0, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        assertEquals(0, entities.pickable(6), "a spectator cannot be picked");
        out(new ClientboundPacket.PlayerInfoUpdate(false, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        assertEquals(-1, entities.pickable(6), "not processed yet: either");
        sync();
        assertEquals(1, entities.pickable(6));
        out(new ClientboundPacket.PlayerInfoUpdate(false, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 3))));
        out(new ClientboundPacket.PlayerInfoUpdate(true, false, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, -1))));
        sync();
        assertEquals(0, entities.pickable(6), "adding a listed profile again keeps its PlayerInfo (putIfAbsent): still a spectator");
        out(new ClientboundPacket.PlayerInfoUpdate(false, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        sync();
        out(new ClientboundPacket.PlayerInfoRemove(List.of(ALICE)));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        sync();
        assertEquals(-1, entities.pickable(6), "the entity keeps the PlayerInfo it looked up, which is no longer followed");

        EntitySizes.DataField flags = SIZES.entry("minecraft:armor_stand").data().getFirst();
        out(add(7, "minecraft:armor_stand", 2.5, 64.0, 0.5));
        sync();
        assertEquals(1, entities.pickable(7));
        out(data(7, flags.index(), ClientboundPacket.SetEntityData.Kind.BYTE, (byte) EntitySizes.ARMOR_STAND_MARKER));
        sync();
        assertEquals(0, entities.pickable(7), "a marker cannot be picked");

        out(add(8, "minecraft:arrow", 4.5, 64.0, 0.5));
        out(add(9, "minecraft:ender_dragon", 8.5, 64.0, 0.5));
        out(add(10, "minecraft:shulker_bullet", 10.5, 64.0, 0.5));
        sync();
        assertEquals(-1, entities.pickable(8), "projectiles: by a tag the server sends");
        assertEquals(0, entities.pickable(9), "the dragon itself: only its parts");
        assertEquals(1, entities.pickable(10));
        assertEquals(1.0F, states(10).getFirst().pickRadius(), "a projectile's box is inflated by 1 when picked");
        assertEquals(0, entities.pickable(11), "an id the client never got: absent, nothing to pick");
    }

    @Test
    void aSizeTheTableDoesNotFollowIsNotClaimed() {
        out(add(5, "minecraft:item_frame", 0.0, 64.0, 0.0));
        sync();
        assertNull(onlySize(5));
        assertNull(states(5).getFirst().bounds(), "a hanging entity's box comes from its block, facing and item");
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(5));
    }

    private EntitySizes.Box onlyBounds(int id) {
        List<ClientEntities.PossibleState> states = states(id);
        assertEquals(1, states.size(), "one possible state");
        return states.getFirst().bounds();
    }

    @Test
    void aPolarBearsSizeFollowsTheStandingAnimationTheClientRuns() {
        EntitySizes.Entry bear = SIZES.entry("minecraft:polar_bear");
        EntitySizes.DataField standing = bear.data().getFirst();
        EntitySizes.Size[] at = new EntitySizes.Size[7];
        for (int a = 0; a <= 6; a++) {
            at[a] = SIZES.size("minecraft:polar_bear", STANDING, false, 1.0, 0, 0, a);
        }
        assertEquals(at[0].height() * 2.0F, at[6].height(), 1.0E-6F, "standing: twice as high");
        out(add(5, "minecraft:polar_bear", 0.5, 64.0, 0.5));
        sync();
        assertEquals(at[0], onlySize(5));
        assertEquals(at[0].box(0.5, 64.0, 0.5), onlyBounds(5));
        out(data(5, standing.index(), ClientboundPacket.SetEntityData.Kind.BOOLEAN, 1));
        sync();
        assertEquals(at[0], onlySize(5), "the flag alone: the animation moves in the entity's tick");
        for (int a = 1; a <= 6; a++) {
            tick();
            assertNull(onlySize(5), "refreshed at the start of the tick or later: " + (a - 1) + " or " + a);
            assertEquals(at[a].box(0.5, 64.0, 0.5), onlyBounds(5), "bounded by the higher one");
        }
        tick();
        assertEquals(at[6], onlySize(5), "at the top the animation stops and the size is known again");
        out(data(5, standing.index(), ClientboundPacket.SetEntityData.Kind.BOOLEAN, 0));
        sync();
        tick();
        assertNull(onlySize(5));
        assertEquals(at[6].box(0.5, 64.0, 0.5), onlyBounds(5), "going down: bounded by the value before the tick");
        for (int i = 0; i < 6; i++) {
            tick();
        }
        assertEquals(at[0], onlySize(5));
        // a bear the client does not tick does not animate
        out(new ClientboundPacket.ForgetLevelChunk(0, 0));
        out(data(5, standing.index(), ClientboundPacket.SetEntityData.Kind.BOOLEAN, 1));
        sync();
        tick();
        assertEquals(at[0], onlySize(5), "its chunk is gone: it does not tick");
    }

    @Test
    void theScaleAnEntityHadSinceItsLastTickBoundsItsBox() {
        // 1, then 3, then 1 again before a tick: a pose change at 3 left the box at 3 until the entity ticks
        EntitySizes.Size adult = SIZES.size("minecraft:husk", STANDING, false, 1.0);
        out(add(5, "minecraft:husk", 0.5, 64.0, 0.5));
        sync();
        out(scale(5, 3.0));
        out(data(5, 6, ClientboundPacket.SetEntityData.Kind.POSE, STANDING));
        out(scale(5, 1.0));
        sync();
        assertNull(onlySize(5));
        assertEquals(adult.scale(3.0F).box(0.5, 64.0, 0.5), onlyBounds(5));
        tick();
        assertEquals(adult, onlySize(5));
        assertEquals(adult.box(0.5, 64.0, 0.5), onlyBounds(5));
    }

    @Test
    void anEntityLostInPlaceIsStillOfItsType() {
        out(add(8, "minecraft:arrow", 4.5, 64.0, 0.5));
        sync();
        out(new ClientboundPacket.TeleportEntity(8, null, Vec3d.ZERO, 0, 0, java.util.Set.of(), true)); // an old recording: the id only
        sync();
        tick(); // a projectile whose position is lost may have left the world: lost, but an arrow still
        out(new ClientboundPacket.RemoveEntities(new int[] {8})); // not processed yet
        assertTrue(entities.mayHaveNear("minecraft:arrow", 4.5, 64.0, 0.5, 1.0, Long.MIN_VALUE));
        assertTrue(!entities.mayHaveNear("minecraft:firework_rocket", 4.5, 64.0, 0.5, 1.0, Long.MIN_VALUE), "an arrow is not a rocket");
    }
}
