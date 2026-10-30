package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.PositionCodec;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ClientEntities#pushers}: which living entities' ticks meet the player's box, ticking before it or after it,
 * and where that is not known.
 */
class ClientPushersTest {

    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final int SELF = 1;
    private static final UUID ALICE = new UUID(1, 2);
    /** The player's box standing at (0.5, 64, 0.5). */
    private static final double[] BOX = {0.2, 64.0, 0.2, 0.8, 65.8, 0.8};

    private ClientEntities entities;
    private long sequence;
    private int pingId;

    @BeforeEach
    void setUp() {
        entities = new ClientEntities(EntityTypes.vanilla());
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD));
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                out(new ClientboundPacket.LevelChunk(x, z, null));
            }
        }
        sync();
    }

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

    private void sync() {
        int id = ping();
        entities.onEvent(new TimelineEvent.Acknowledged(sequence++, sequence, id, 0, 0));
    }

    private static ClientboundPacket.AddEntity add(int id, String type, double x, double y, double z) {
        return ClientboundPacket.AddEntity.received(id, new UUID(0, id), type, x, y, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0);
    }

    private List<ClientEntities.Pusher> pushers(boolean beforeSelf) {
        return entities.pushers(SELF, BOX[0], BOX[1], BOX[2], BOX[3], BOX[4], BOX[5], beforeSelf, 64.0);
    }

    private void alice() {
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ALICE, 0))));
        out(ClientboundPacket.AddEntity.received(7, ALICE, EntityTypes.PLAYER, 0.9, 64.0, 0.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
    }

    @Test
    void aMobAddedAfterThePlayerPushesAfterItsTickOnceItTicked() {
        out(add(5, "minecraft:zombie", 0.9, 64.0, 0.6));
        sync();
        assertEquals(List.of(), pushers(false), "not ticked yet");
        tick();
        assertEquals(List.of(new ClientEntities.Pusher(5, 0.9, 0.6, new UUID(0, 5).toString())), pushers(false));
        assertEquals(List.of(), pushers(true), "it ticks after the player");
    }

    @Test
    void entitiesApartOrThatPushNoPlayerDoNot() {
        out(add(5, "minecraft:zombie", 1.11, 64.0, 0.5)); // its box (0.81..1.41) does not meet the player's (..0.8)
        out(add(6, "minecraft:bat", 0.6, 64.5, 0.5));
        out(add(7, "minecraft:armor_stand", 0.5, 64.0, 0.6));
        out(add(8, "minecraft:parrot", 0.4, 64.0, 0.5));
        out(add(9, "minecraft:item", 0.5, 64.0, 0.5));
        sync();
        tick();
        assertEquals(List.of(), pushers(false));
        assertEquals(List.of(), pushers(true));
    }

    @Test
    void aTamableAnimalsTeamIsNotKnownByItsName() {
        out(add(5, "minecraft:wolf", 0.9, 64.0, 0.6));
        sync();
        tick();
        assertEquals(List.of(new ClientEntities.Pusher(5, 0.9, 0.6, null)), pushers(false));
    }

    @Test
    void aPlayerAddedBeforeARespawnTicksBeforeThePlayerAndAMobIsNotKnown() {
        alice();
        tick();
        assertEquals(List.of(new ClientEntities.Pusher(7, 0.9, 0.5, null)), pushers(false), "added after the login: after the player");
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0)); // a new LocalPlayer, last in the tick list
        sync();
        tick();
        assertEquals(List.of(new ClientEntities.Pusher(7, 0.9, 0.5, null)), pushers(true), "players are always ticking: before the new player");
        assertEquals(List.of(), pushers(false));
        out(add(5, "minecraft:zombie", 0.5, 64.0, 0.1));
        sync();
        tick();
        assertEquals(List.of(new ClientEntities.Pusher(5, 0.5, 0.1, new UUID(0, 5).toString())), pushers(false), "added after the respawn");
    }

    @Test
    void aMobFromBeforeARespawnHasNoKnownPlaceInTheTickList() {
        out(add(5, "minecraft:zombie", 0.9, 64.0, 0.6));
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0));
        sync();
        tick();
        assertNull(pushers(true));
        assertNull(pushers(false));
    }

    @Test
    void aMoveTheClientMayNotHaveHandledLeavesThePushUnknown() {
        out(add(5, "minecraft:zombie", 1.2, 64.0, 0.5)); // its box from 0.9: apart
        sync();
        tick();
        assertEquals(List.of(), pushers(false));
        out(new ClientboundPacket.MoveEntity(5, (short) PositionCodec.delta(1.2, 0.7), (short) 0, (short) 0, (byte) 0, (byte) 0, true, true, false));
        ping(); // not answered: the client may have moved it before the tick (a third of the way: its box from 0.73) or not
        tick();
        assertNull(pushers(false));
    }

    @Test
    void aBoatThatMayMeetThePlayerIsACollider() {
        out(add(5, "minecraft:oak_boat", 0.9, 64.0, 0.5));
        sync();
        tick();
        assertNull(pushers(false));
        assertTrue(entities.mayHaveColliderNear(SELF, BOX[0] - 1.5, BOX[1] - 1.5, BOX[2] - 1.5, BOX[3] + 1.5, BOX[4] + 1.5, BOX[5] + 1.5, 64.0));
    }

    @Test
    void livingEntitiesAreNoColliders() {
        out(add(5, "minecraft:villager", 0.9, 64.0, 0.6));
        sync();
        tick();
        assertFalse(entities.mayHaveColliderNear(SELF, BOX[0] - 1.5, BOX[1] - 1.5, BOX[2] - 1.5, BOX[3] + 1.5, BOX[4] + 1.5, BOX[5] + 1.5, 64.0));
        assertTrue(entities.mayHavePusherNear(SELF, BOX[0] - 1.5, BOX[1] - 1.5, BOX[2] - 1.5, BOX[3] + 1.5, BOX[4] + 1.5, BOX[5] + 1.5, 64.0));
    }

    /** A spin attack's sweep counts every entity: a dropped item neither pushes nor collides, but stops a collision ending it. */
    @Test
    void everyEntityCountsForASpinAttack() {
        sync();
        tick();
        assertFalse(entities.mayHaveEntityNear(SELF, BOX[0] - 1.5, BOX[1] - 1.5, BOX[2] - 1.5, BOX[3] + 1.5, BOX[4] + 1.5, BOX[5] + 1.5, 64.0),
                "the player alone");
        out(add(5, "minecraft:item", 0.9, 64.0, 0.5));
        sync();
        tick();
        assertFalse(entities.mayHavePusherNear(SELF, BOX[0] - 1.5, BOX[1] - 1.5, BOX[2] - 1.5, BOX[3] + 1.5, BOX[4] + 1.5, BOX[5] + 1.5, 64.0));
        assertTrue(entities.mayHaveEntityNear(SELF, BOX[0] - 1.5, BOX[1] - 1.5, BOX[2] - 1.5, BOX[3] + 1.5, BOX[4] + 1.5, BOX[5] + 1.5, 64.0));
        // the client simulates a dropped item itself: where the server last put it, widened by the radius
        assertFalse(entities.mayHaveEntityNear(SELF, BOX[0] + 100.0, BOX[1], BOX[2], BOX[3] + 100.0, BOX[4], BOX[5], 64.0), "far from it");
    }
}
