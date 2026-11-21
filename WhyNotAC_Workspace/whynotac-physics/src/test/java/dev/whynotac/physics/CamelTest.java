package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A camel its rider steers ({@code Camel}, decompiled 1.21.11): ridden as a horse is, 0.1 faster while its rider sprints
 * and its dash has cooled down, its riding jump a dash along its own look on the level and up, which then cools down 55
 * ticks; its dashing flag, cleared on the ground once the cooldown is under 50, cools a cooled-down dash again.
 */
class CamelTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final Horse.Rider AHEAD = new Horse.Rider(0.0F, 1.0F, 0.0F, 0.0F);
    private static final Horse.Rider STILL = new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F);
    private static final float GROUND_SPEED = 0.21600002F / (0.6F * 0.6F * 0.6F); // stone's friction

    /** Stone up to y 63, air above. */
    private final Colliders.World world = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            return STATES.firstStateOf(y < 64 ? "minecraft:stone" : "minecraft:air");
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

    private static Horse.Conditions camel(boolean sprinting) {
        return new Horse.Conditions(0.0F, 0.42F, 1.5F, 0.08, Horse.Effects.NONE, 0.8F, true, 1.7F, 2.375F, null, List.of(), null, null, null,
                new Horse.Camel(0.09F, sprinting));
    }

    /** On the stone, as its last tick on the ground left it. */
    private static HorseState walking(int dashCooldown, boolean dashing) {
        return new HorseState(0.5, 64.0, 0.5, new Vec3d(0.0, -0.08 * 0.98F, 0.0), 0.0F, 0.0F, true, false, true, true, 0.0, new BlockPos(0, 63, 0),
                false, Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 0.0, false, dashCooldown, dashing);
    }

    @Test
    void itWalksAtItsSpeedAndFasterWhileItsRiderSprintsOnceItsDashHasCooledDown() throws Colliders.Unknown {
        HorseState walked = Horse.tick(walking(0, false), AHEAD, world, camel(false));
        assertEquals(0.5 + (double) (0.09F * GROUND_SPEED), walked.z());
        HorseState sprinted = Horse.tick(walking(0, false), AHEAD, world, camel(true));
        assertEquals(0.5 + (double) ((0.09F + 0.1F) * GROUND_SPEED), sprinted.z());
        HorseState cooling = Horse.tick(walking(5, false), AHEAD, world, camel(true));
        assertEquals(walked.z(), cooling.z(), "no sprint while its dash cools down");
        assertEquals(4, cooling.dashCooldown());
        HorseState unknown = Horse.tick(walking(-1, false), AHEAD, world, camel(true));
        assertEquals(walked.z(), unknown.z(), "a cooldown not known: not taken as cooled down");
        assertEquals(-1, unknown.dashCooldown());
    }

    @Test
    void aRidingJumpOnTheGroundDashesAlongItsLookOnTheLevelAndUp() throws Colliders.Unknown {
        HorseState jumped = Horse.onCamelJump(walking(0, false), 90);
        assertEquals(1.0F, jumped.jumpPendingScale());
        assertTrue(jumped.allowStandSliding());
        assertFalse(jumped.standing(), "a camel never rears");
        HorseState dashed = Horse.tick(jumped, new Horse.Rider(0.0F, 0.0F, 30.0F, 20.0F), world, camel(false));
        // its own look: the rider's yaw, half its pitch
        float pitch = 10.0F * VanillaMath.DEG_TO_RAD;
        float yaw = -30.0F * VanillaMath.DEG_TO_RAD;
        float cosPitch = VanillaMath.cos(pitch);
        Vec3d level = new Vec3d(VanillaMath.sin(yaw) * cosPitch, -VanillaMath.sin(pitch) * 0.0, VanillaMath.cos(yaw) * cosPitch).normalize();
        Vec3d dash = level.scale(22.2222F * 1.0F * (double) 0.09F * 1.0F);
        assertEquals(0.5 + dash.x(), dashed.x());
        assertEquals(0.5 + dash.z(), dashed.z());
        assertEquals(64.0 + (-0.08 * 0.98F + 1.4285F * 1.0F * (double) (0.42F * 1.0F * 1.0F)), dashed.y(), "up 1.4285 of its jump power");
        assertFalse(dashed.onGround());
        assertEquals(54, dashed.dashCooldown(), "55, less its own tick");
        assertTrue(dashed.dashing());
        assertEquals(0.0F, dashed.jumpPendingScale());
        HorseState half = Horse.tick(Horse.onCamelJump(walking(0, false), 45), STILL, world, camel(false));
        float scale = Horse.jumpPendingScale(45);
        assertEquals(0.5 + 22.2222F * scale * (double) 0.09F * 1.0F, half.z(), "its jump's scale, ahead");
        assertEquals(64.0 + (-0.08 * 0.98F + 1.4285F * scale * (double) 0.42F), half.y(), "and up");
    }

    @Test
    void aJumpWaitsForTheGroundAndNoneComesWhileItsDashCoolsDown() throws Colliders.Unknown {
        HorseState air = new HorseState(0.5, 70.0, 0.5, new Vec3d(0.0, -0.2, 0.0), 0.0F, 0.0F, false, false, false, false, 0.0, null, false, Vec3d.ZERO,
                false, 0, 0.0F, false, false, 0, null, 0.0, false, 0, false);
        assertSame(air, Horse.onCamelJump(air, 90), "Camel.onPlayerJump: on the ground only");
        HorseState pending = Horse.onCamelJump(walking(0, false), 90);
        HorseState inTheAir = new HorseState(0.5, 70.0, 0.5, new Vec3d(0.0, -0.2, 0.0), 0.0F, 0.0F, false, false, false, false, 0.0, null, false,
                Vec3d.ZERO, false, 0, pending.jumpPendingScale(), true, false, 0, null, 0.0, false, 0, false);
        HorseState falling = Horse.tick(inTheAir, STILL, world, camel(false));
        assertEquals(1.0F, falling.jumpPendingScale(), "tickRidden: it waits for the ground");
        assertEquals(0, falling.dashCooldown());
        HorseState cooling = walking(3, false);
        assertSame(cooling, Horse.onCamelJump(cooling, 90));
    }

    @Test
    void itsDashingFlagClearsOnTheGroundUnderFiftyAndCoolsACooledDownDashAgain() throws Colliders.Unknown {
        HorseState fifty = Horse.tick(walking(50, true), STILL, world, camel(false));
        assertTrue(fifty.dashing(), "not under 50 yet");
        assertEquals(49, fifty.dashCooldown());
        HorseState cleared = Horse.tick(fifty, STILL, world, camel(false));
        assertFalse(cleared.dashing());
        assertEquals(48, cleared.dashCooldown());
        // a long fall from its dash: still dashing when it has cooled down, until it lands
        HorseState air = new HorseState(0.5, 70.0, 0.5, new Vec3d(0.0, -0.2, 0.0), 0.0F, 0.0F, false, false, false, false, 0.0, null, false, Vec3d.ZERO,
                false, 0, 0.0F, false, false, 0, null, 0.0, false, 0, true);
        HorseState stillDashing = Horse.tick(air, STILL, world, camel(false));
        assertTrue(stillDashing.dashing());
        assertEquals(0, stillDashing.dashCooldown());
        HorseState landed = Horse.tick(walking(0, true), STILL, world, camel(false));
        assertFalse(landed.dashing());
        assertEquals(54, landed.dashCooldown(), "onSyncedDataUpdated: 55 again, less its own tick");
    }

    @Test
    void theDashingFlagsDataCoolsACooledDownDashAgain() {
        HorseState synced = Horse.onCamelDashSynced(walking(0, false), true);
        assertEquals(55, synced.dashCooldown());
        assertTrue(synced.dashing());
        assertEquals(12, Horse.onCamelDashSynced(walking(12, true), false).dashCooldown());
        assertFalse(Horse.onCamelDashSynced(walking(12, true), false).dashing());
        assertEquals(-1, Horse.onCamelDashSynced(walking(-1, false), true).dashCooldown());
    }
}
