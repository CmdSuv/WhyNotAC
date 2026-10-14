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
        assertEquals(adult.scale(1.5F), onlySize(5));
        // One modifier per operation: AttributeInstance.calculateValue in its fixed operation order.
        out(scale(5, 1.0, modifier(0.5, ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL),
                modifier(0.5, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE),
                modifier(0.5, ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_BASE)));
        sync();
        assertEquals(adult.scale((float) ((1.5 + 1.5 * 0.5) * 1.5)), onlySize(5));
        // Two of one operation: the client sums them in its hash map's order, which is not followed.
        out(scale(5, 1.0, modifier(0.1, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE),
                modifier(0.2, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE)));
        sync();
        assertNull(onlySize(5));
        // The attribute's range: at most 16.
        out(scale(5, 40.0));
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
    void aSizeTheTableDoesNotFollowIsNotClaimed() {
        out(add(5, "minecraft:slime", 0.5, 64.0, 0.5));
        sync();
        assertNull(onlySize(5));
        assertEquals(ClientEntities.Existence.PRESENT, entities.existence(5));
    }
}
