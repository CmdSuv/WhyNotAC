package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import org.junit.jupiter.api.Test;

/**
 * A riptide trident let go in water ({@code TridentItem.releaseUsing}, decompiled 1.21.11): a push along the look of its
 * spin attack strength before the player's tick, a lift of 1.2 on the ground, the spin attack's count of 20 run down by
 * the ticks (a horizontal collision ends it), its pose once the server's flag says so.
 */
class RiptideTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final OnFoot.Conditions DEFAULTS = OnFoot.Conditions.VANILLA_DEFAULTS;

    /** Stone up to y 63; water from y 64 to 69 over x 0 to 15, a stone wall at x 16; air elsewhere. */
    private final Colliders.World world = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            if (y < 64 || x == 16 && y < 72) {
                return STATES.firstStateOf("minecraft:stone");
            }
            if (x >= 0 && x < 16 && y < 70) {
                return STATES.firstStateOf("minecraft:water");
            }
            return STATES.firstStateOf("minecraft:air");
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public Boolean fastLava() {
            return Boolean.FALSE;
        }
    };

    private static PlayerState swimmer(double x, double y, double z, float yRot, float xRot, int spin) {
        return new PlayerState(x, y, z, new Vec3d(0.0, -0.005, 0.0), yRot, xRot, false, false, false, false, false, 0.0, false, false, false,
                PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, true, false, false, true, spin);
    }

    @Test
    void theReleasePushesAlongTheLookByItsStrength() throws Colliders.Unknown {
        PlayerState s = swimmer(8.5, 66.0, 8.5, 30.0F, -40.0F, 0);
        PlayerState released = OnFoot.tick(s, PlayerState.Keys.NONE, world, DEFAULTS.withRiptide(3.0F));
        // the same tick from the motion the push leaves (Entity.push: the look's float vector, scaled to the strength)
        float yaw = 30.0F * VanillaMath.DEG_TO_RAD;
        float pitch = -40.0F * VanillaMath.DEG_TO_RAD;
        float px = -VanillaMath.sin(yaw) * VanillaMath.cos(pitch);
        float py = -VanillaMath.sin(pitch);
        float pz = VanillaMath.cos(yaw) * VanillaMath.cos(pitch);
        float length = VanillaMath.sqrt(px * px + py * py + pz * pz);
        px *= 3.0F / length;
        py *= 3.0F / length;
        pz *= 3.0F / length;
        PlayerState pushed = new PlayerState(s.x(), s.y(), s.z(), s.deltaMovement().add(px, py, pz), s.yRot(), s.xRot(), false, false, false, false,
                false, 0.0, false, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, true, false,
                false, true, 20);
        assertEquals(OnFoot.tick(pushed, PlayerState.Keys.NONE, world, DEFAULTS), released);
        assertEquals(19, released.spinAttackTicks());
        assertTrue(released.y() > 67.0, "up out of the water: " + released);
        assertEquals(Boolean.TRUE, released.inWater(), "the tick's baseTick found water");
    }

    @Test
    void onTheGroundItLiftsTheBody() throws Colliders.Unknown {
        PlayerState ground = new PlayerState(8.5, 64.0, 8.5, new Vec3d(0.0, -0.0784000015258789, 0.0), 0.0F, 0.0F, true, false, false, true, true, 0.0,
                false, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, new BlockPos(8, 63, 8), false, Vec3d.ZERO, false, true,
                false, false, true, 0);
        PlayerState lifted = OnFoot.tick(ground, PlayerState.Keys.NONE, world, DEFAULTS.withRiptide(3.0F));
        // move(SELF, 0, 1.2, 0) before the tick: off the ground, its support gone, the push (ahead at yaw 0) in its motion
        PlayerState up = new PlayerState(8.5, 64.0 + (double) 1.1999999F, 8.5, new Vec3d(0.0, -0.0784000015258789, 0.0).add(-0.0F, -0.0F, 3.0F), 0.0F,
                0.0F, false, false, false, false, false, 0.0, false, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false,
                Vec3d.ZERO, false, true, false, false, true, 20);
        assertEquals(OnFoot.tick(up, PlayerState.Keys.NONE, world, DEFAULTS), lifted);
    }

    @Test
    void theSpinAttackRunsDownAndAHorizontalCollisionEndsIt() throws Colliders.Unknown {
        PlayerState spinning = swimmer(8.5, 66.0, 8.5, 0.0F, 0.0F, 12);
        assertEquals(11, OnFoot.tick(spinning, PlayerState.Keys.NONE, world, DEFAULTS).spinAttackTicks());
        assertEquals(-11, OnFoot.tick(spinning.withSpinAttackTicks(-12), PlayerState.Keys.NONE, world, DEFAULTS).spinAttackTicks(),
                "not known: at most one less");
        assertEquals(0, OnFoot.tick(spinning.withSpinAttackTicks(0), PlayerState.Keys.NONE, world, DEFAULTS).spinAttackTicks());
        PlayerState atTheWall = new PlayerState(15.6, 66.0, 8.5, new Vec3d(0.5, 0.0, 0.0), -90.0F, 0.0F, false, false, false, false, false, 0.0, false,
                false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, true, false, false, true, 12);
        PlayerState hit = OnFoot.tick(atTheWall, PlayerState.Keys.NONE, world, DEFAULTS);
        assertTrue(hit.horizontalCollision());
        assertEquals(0, hit.spinAttackTicks(), "checkAutoSpinAttack: no entity, a horizontal collision");
    }

    @Test
    void itsPoseFollowsTheServersFlag() throws Colliders.Unknown {
        PlayerState air = new PlayerState(30.5, 70.0, 8.5, new Vec3d(0.0, 0.5, 0.0), 0.0F, 0.0F, false, false, false, false, false, 0.0, false, false,
                false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, false, false, false, false, 15);
        assertEquals(PlayerState.Pose.STANDING, OnFoot.tick(air, PlayerState.Keys.NONE, world, DEFAULTS).pose(), "its count alone: no pose");
        PlayerState flagged = OnFoot.tick(air, PlayerState.Keys.NONE, world, DEFAULTS.withSpinAttack(true));
        assertEquals(PlayerState.Pose.SPIN_ATTACK, flagged.pose());
        assertEquals(0.6, flagged.box().maxY() - flagged.box().minY(), 1.0E-6, "0.6 tall");
    }
}
