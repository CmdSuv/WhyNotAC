package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A ridden horse's rearing ({@code AbstractHorse.tick}, decompiled 1.21.11): the rider's jump rears it for 20 ticks the
 * client counts itself ({@code setStanding(20)}); the tick the count ends clears the flag and with it
 * {@code allowStandSliding}, and a rearing horse on the ground the rider's jump does not let slide takes no input.
 */
class HorseStandTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final Horse.Rider FORWARD = new Horse.Rider(0.0F, 1.0F, 0.0F, 0.0F);
    private static final Horse.Conditions HORSE = new Horse.Conditions(0.225F, 0.7, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, true, 1.3964844F, 1.6F,
            null, List.of());

    private final Colliders.World world = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            return STATES.firstStateOf(y < 0 ? "minecraft:stone" : "minecraft:air");
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }
    };

    private static HorseState onTheGround(boolean allowStandSliding, boolean standing, int standCounter) {
        return new HorseState(0.5, 0.0, 0.5, new Vec3d(0.0, -0.0784000015258789, 0.0), 0.0F, 0.0F, true, false, true, true, 0.0, new BlockPos(0, -1, 0),
                false, Vec3d.ZERO, false, 0, 0.0F, allowStandSliding, standing, standCounter, null, 0.0, false);
    }

    @Test
    void theTickTheRearingEndsClearsItAndTheSliding() throws Colliders.Unknown {
        HorseState after = Horse.tick(onTheGround(true, true, 1), FORWARD, world, HORSE);
        assertFalse(after.standing(), "clearStanding");
        assertEquals(0, after.standCounter());
        assertFalse(after.allowStandSliding(), "a tick that ends not rearing");
        assertNotEquals(0.5, after.z(), "the sliding the jump allowed still moved it this tick");
    }

    @Test
    void aRearingStillCountingKeepsItsSliding() throws Colliders.Unknown {
        HorseState after = Horse.tick(onTheGround(true, true, 5), FORWARD, world, HORSE);
        assertTrue(after.standing());
        assertEquals(4, after.standCounter());
        assertTrue(after.allowStandSliding());
    }

    @Test
    void aRearingTheServerSetTakesNoInputOnTheGround() throws Colliders.Unknown {
        HorseState after = Horse.tick(onTheGround(false, true, 0), FORWARD, world, HORSE);
        assertTrue(after.standing(), "the client counts no rearing it did not start");
        assertEquals(0.5, after.x());
        assertEquals(0.5, after.z(), "getRiddenInput: nothing while rearing");
    }
}
