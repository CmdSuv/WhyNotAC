package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.PositionCodec;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Resting tracks ({@code ClientEntities.rest}) change no answer: a replica that steps every track at every tick sees the
 * same events, and after each one both give the same pushers, possible states and tick flags. The script moves the
 * answer a resting entity's tick depends on (the level frozen, its chunk forgotten and sent again) while the pings
 * answered prune the history that answer came from.
 */
class ClientEntitiesRestingTest {

    private static final int SELF = 1;
    private static final int COW = 7;
    private static final int OTHER_COW = 8;
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);

    private final ClientEntities resting = new ClientEntities(EntityTypes.vanilla());
    private final ClientEntities stepping = ClientEntities.steppingEveryTrack(EntityTypes.vanilla());
    private long sequence;
    private int pingId;
    private int compared;
    private int comparedWhileResting;

    private void deliver(TimelineEvent event) {
        resting.onEvent(event);
        stepping.onEvent(event);
        compare();
    }

    private void out(ClientboundPacket packet) {
        deliver(new TimelineEvent.Outbound(sequence++, sequence, packet));
    }

    private void tick() {
        deliver(new TimelineEvent.Inbound(sequence++, sequence, 0, new ServerboundPacket.ClientTickEnd()));
    }

    private int ping() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        return id;
    }

    private void ack(int id) {
        deliver(new TimelineEvent.Acknowledged(sequence++, sequence, id, 0, 0));
    }

    private void sync() {
        ack(ping());
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    /** Pushers first: they must bring a resting track's flags up to date themselves. */
    private void compare() {
        compared++;
        if (resting.restingTracks() > 0) {
            comparedWhileResting++;
        }
        for (boolean beforeSelf : new boolean[] {false, true}) {
            List<ClientEntities.Pusher> expected = stepping.pushers(SELF, 7.5, 64, 7.5, 9.5, 66, 9.5, beforeSelf, 1.0);
            String why = expected == null ? stepping.whyNotKnown() : null;
            List<ClientEntities.Pusher> actual = resting.pushers(SELF, 7.5, 64, 7.5, 9.5, 66, 9.5, beforeSelf, 1.0);
            assertEquals(expected, actual, "pushers at " + sequence);
            if (actual == null) {
                assertEquals(why, resting.whyNotKnown(), "why at " + sequence);
            }
        }
        for (int id : new int[] {COW, OTHER_COW}) {
            List<ClientEntities.PossibleState> expected = new ArrayList<>();
            List<ClientEntities.PossibleState> actual = new ArrayList<>();
            stepping.forEachPossibleState(id, expected::add);
            resting.forEachPossibleState(id, actual::add);
            assertEquals(expected, actual, "entity " + id + " at " + sequence);
            List<boolean[]> expectedFlags = stepping.tickFlags(id);
            List<boolean[]> actualFlags = resting.tickFlags(id);
            assertEquals(expectedFlags.size(), actualFlags.size(), "entity " + id + " at " + sequence);
            for (int i = 0; i < expectedFlags.size(); i++) {
                assertTrue(Arrays.equals(expectedFlags.get(i), actualFlags.get(i)), "tick flags of " + id + " at " + sequence + ": expected "
                        + Arrays.toString(expectedFlags.get(i)) + ", was " + Arrays.toString(actualFlags.get(i)));
            }
        }
    }

    private static ClientboundPacket.AddEntity cow(int id, double x, double y, double z) {
        return ClientboundPacket.AddEntity.received(id, new UUID(0, id), "minecraft:cow", x, y, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0);
    }

    @Test
    void restingTracksGiveTheAnswersOfSteppedOnes() {
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD));
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                out(new ClientboundPacket.LevelChunk(x, z, null));
            }
        }
        sync();
        out(cow(COW, 8.5, 64, 8.5)); // chunk 0,0, inside the pushers' box
        out(cow(OTHER_COW, 20.5, 64, 4.5)); // chunk 1,0
        sync();
        ticks(6);
        assertTrue(resting.restingTracks() >= 2, "both cows rest: " + resting.restingTracks());

        // the level freezes: a tick may or may not step a cow; the pings answered meanwhile prune the history
        int freeze = ping();
        out(new ClientboundPacket.TickingState(20.0F, true));
        int frozen = ping();
        tick();
        ack(freeze);
        tick();
        ack(frozen);
        ticks(3);
        sync();
        tick();
        out(new ClientboundPacket.TickingState(20.0F, false));
        tick();
        sync();
        ticks(3);

        // the cow's chunk goes and comes back: its tick is certainly not run while the chunk is gone
        int forget = ping();
        out(new ClientboundPacket.ForgetLevelChunk(0, 0));
        tick();
        ack(forget);
        ticks(2);
        sync();
        ticks(3);
        out(new ClientboundPacket.LevelChunk(0, 0, null));
        tick();
        sync();
        ticks(3);

        // the cow moves (an op wakes it), interpolates, rests again
        out(new ClientboundPacket.MoveEntity(COW, (short) PositionCodec.delta(8.5, 8.75), (short) 0, (short) 0, (byte) 0, (byte) 0, true, true,
                false));
        ticks(2);
        sync();
        ticks(6);
        assertTrue(resting.restingTracks() >= 2, "both cows rest again: " + resting.restingTracks());
        assertTrue(comparedWhileResting > 30, comparedWhileResting + " of " + compared + " comparisons while tracks rested");
    }
}
