package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code LivingEntity.travelInAir} on a climbable block walked into: the motion turns 0.2 up after the move, but
 * {@code getEffectiveGravity} reads the motion the move left ({@code getDeltaMovement}), so a falling climber under slow
 * falling loses 0.01 of it, not gravity's 0.08 (decompiled 1.21.11).
 */
class ClimbGravityTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final PlayerState.Keys FORWARD = new PlayerState.Keys(true, false, false, false, false, false, false);

    private final Map<Long, Integer> blocks = new HashMap<>();
    private final Colliders.World world = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            Integer s = blocks.get(key(x, y, z));
            return s != null ? s : y < 0 ? STATES.firstStateOf("minecraft:stone") : AIR;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }
    };

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) y & 0xFFF;
    }

    private static int stateOf(String block, String properties) {
        for (int s = STATES.firstStateOf(block); STATES.block(s).equals(block); s++) {
            if (STATES.properties(s).equals(properties)) {
                return s;
            }
        }
        throw new AssertionError(block + "[" + properties + "]");
    }

    @Test
    void aFallingClimberUnderSlowFallingLosesTheSlowGravity() throws Colliders.Unknown {
        for (int y = 98; y <= 101; y++) { // a ladder column on a wall the climber walks into
            blocks.put(key(0, y, 0), stateOf("minecraft:ladder", "facing=north,waterlogged=false"));
            blocks.put(key(0, y, -1), STATES.firstStateOf("minecraft:stone"));
        }
        PlayerState climbing = new PlayerState(0.5, 100.0, 0.3, new Vec3d(0.0, -0.1, 0.0), 180.0F, 0.0F, false, false, false, false, false, 0.0, false,
                false, false, PlayerState.Pose.STANDING, FORWARD, 0, 0, 0, null, false, Vec3d.ZERO, false, false, false, false);
        OnFoot.Conditions slowFalling = new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, false, OnFoot.Speed.VANILLA,
                new OnFoot.Effects(0, 0, true, false, false, false), null, false);
        PlayerState after = OnFoot.tick(climbing, FORWARD, world, slowFalling);
        assertTrue(after.horizontalCollision(), "into the wall");
        assertTrue(after.y() < 100.0, "the move fell: the motion it left is downward");
        assertEquals((0.2 - 0.01) * 0.98F, after.deltaMovement().y(), "0.2 up, less slow falling's 0.01, then the drag");
    }
}
