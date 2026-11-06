package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A nautilus its rider steers ({@code AbstractNautilus}, decompiled 1.21.11): it goes where its rider looks, turns half
 * the way to its rider's yaw, in water moves with no gravity and keeps 0.9 of its motion, is pushed by no current, and
 * dashes along its rider's look at a riding jump, which then cools down 40 ticks.
 */
class NautilusTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final Horse.Rider AHEAD = new Horse.Rider(0.0F, 1.0F, 0.0F, 0.0F);
    private static final Horse.Rider STILL = new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F);
    private static final Horse.Conditions NAUTILUS = conditions(new Horse.Nautilus(1.0));

    /**
     * Stone up to y 61; at x 0 and above water from y 62 to 65 (sources; at y 62 over x 10 to 17 flowing, its level the
     * distance from x 10), stone at y 62 below x 0; air above.
     */
    private final Colliders.World world = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            if (y < 62 || y == 62 && x < 0) {
                return STATES.firstStateOf("minecraft:stone");
            }
            if (x >= 10 && x < 18 && y == 62) {
                return water("level=" + (x - 10));
            }
            if (x >= 0 && y < 66 && (x < 10 || y < 62)) {
                return water("level=0");
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

    private static int water(String properties) {
        for (int s = STATES.firstStateOf("minecraft:water"); STATES.block(s).equals("minecraft:water"); s++) {
            if (STATES.properties(s).equals(properties)) {
                return s;
            }
        }
        throw new IllegalStateException(properties);
    }

    private static Horse.Conditions conditions(Horse.Nautilus nautilus) {
        return new Horse.Conditions(0.0F, 0.0, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, false, 0.875F, 0.95F, null, List.of(), null, null, nautilus);
    }

    private static HorseState at(double x, double y, double z, boolean onGround, BlockPos support, int dashCooldown) {
        return new HorseState(x, y, z, Vec3d.ZERO, 0.0F, 0.0F, onGround, false, false, false, 0.0, support, false, Vec3d.ZERO, false, 0, 0.0F,
                false, false, 0, null, 0.0, false, dashCooldown);
    }

    @Test
    void inWaterItGoesAheadAtItsSwimmingSpeedWithNoGravity() throws Colliders.Unknown {
        HorseState after = Horse.tick(at(4.5, 63.0, 2.5, false, null, 0), AHEAD, world, NAUTILUS);
        // AbstractNautilus.travelInWater: moveRelative(0.0325 times the attribute), the move, then 0.9 of the motion
        assertEquals(2.5 + (double) 0.0325F, after.z());
        assertEquals(63.0, after.y(), "no gravity");
        assertEquals(new Vec3d(0.0, 0.0, (double) 0.0325F * 0.9), after.deltaMovement());
        HorseState faster = Horse.tick(at(4.5, 63.0, 2.5, false, null, 0), AHEAD, world, conditions(new Horse.Nautilus(1.1F)));
        assertEquals(2.5 + (double) (0.0325F * (float) (double) 1.1F), faster.z(), "a zombie nautilus's attribute");
    }

    @Test
    void itGoesWhereItsRiderLooksAndTurnsHalfTheWay() throws Colliders.Unknown {
        HorseState up = Horse.tick(at(4.5, 63.0, 2.5, false, null, 0), new Horse.Rider(0.0F, 1.0F, 0.0F, -30.0F), world, NAUTILUS);
        assertTrue(up.y() > 63.0, "looking up: " + up.y());
        assertEquals(-15.0F, up.xRot(), "half its rider's pitch");
        HorseState back = Horse.tick(at(4.5, 63.0, 2.5, false, null, 0), new Horse.Rider(0.0F, -1.0F, 0.0F, 0.0F), world, NAUTILUS);
        assertEquals(2.5 - (double) (0.5F * 0.0325F), back.z(), "backward half as fast");
        HorseState turned = Horse.tick(at(4.5, 63.0, 2.5, false, null, 0), new Horse.Rider(0.0F, 0.0F, 90.0F, 0.0F), world, NAUTILUS);
        assertEquals(45.0F, turned.yRot());
    }

    @Test
    void noCurrentPushesIt() throws Colliders.Unknown {
        HorseState s = at(13.5, 62.0, 2.5, false, null, 0);
        HorseState after = Horse.tick(s, STILL, world, NAUTILUS);
        assertEquals(13.5, after.x(), "isPushedByFluid: false");
        HorseState horse = Horse.tick(s, STILL, world, new Horse.Conditions(0.225F, 0.7, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, true, 1.3964844F,
                1.6F, null, List.of()));
        assertTrue(horse.x() != 13.5, "a horse's current: " + horse.x());
    }

    @Test
    void onLandItWalksAtItsLandSpeedUnderGravity() throws Colliders.Unknown {
        HorseState walking = new HorseState(-2.5, 62.0, 2.5, new Vec3d(0.0, -0.08 * 0.98F, 0.0), 0.0F, 0.0F, true, false, true, true, 0.0,
                new BlockPos(-3, 61, 2), false, Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 0.0, false, 0); // as its last tick on the ground left it
        HorseState after = Horse.tick(walking, AHEAD, world, NAUTILUS);
        assertEquals(2.5 + (double) (0.02F * (0.21600002F / (0.6F * 0.6F * 0.6F))), after.z());
        assertEquals(62.0, after.y());
        assertTrue(after.onGround());
        assertTrue(after.deltaMovement().y() < 0.0, "gravity: " + after.deltaMovement());
    }

    @Test
    void aRidingJumpDashesAlongItsRidersLookThenCoolsDown() throws Colliders.Unknown {
        HorseState jumped = Horse.onNautilusJump(at(4.5, 63.0, 2.5, false, null, 0), 100);
        assertEquals(1.0F, jumped.jumpPendingScale());
        Horse.Rider looking = new Horse.Rider(0.0F, 0.0F, 30.0F, 20.0F);
        HorseState dashed = Horse.tick(jumped, looking, world, NAUTILUS);
        float pitch = 20.0F * VanillaMath.DEG_TO_RAD;
        float yaw = -30.0F * VanillaMath.DEG_TO_RAD;
        Vec3d look = new Vec3d(VanillaMath.sin(yaw) * VanillaMath.cos(pitch), -VanillaMath.sin(pitch), VanillaMath.cos(yaw) * VanillaMath.cos(pitch));
        Vec3d dash = look.scale(1.2F * 1.0F * 1.0 * 1.0F);
        assertEquals(new Vec3d(4.5, 63.0, 2.5).add(dash), new Vec3d(dashed.x(), dashed.y(), dashed.z()), "1.2 of its look in water");
        assertEquals(dash.scale(0.9), dashed.deltaMovement());
        assertEquals(0.0F, dashed.jumpPendingScale());
        assertEquals(39, dashed.dashCooldown(), "40, less its own tick");
        HorseState walking = new HorseState(-2.5, 62.0, 2.5, new Vec3d(0.0, -0.08 * 0.98F, 0.0), 0.0F, 0.0F, true, false, true, true, 0.0,
                new BlockPos(-3, 61, 2), false, Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 0.0, false, 0);
        HorseState land = Horse.tick(Horse.onNautilusJump(walking, 45), looking, world, NAUTILUS);
        Vec3d landDash = look.scale(0.5F * Horse.jumpPendingScale(45) * 1.0 * 1.0F);
        assertEquals(-2.5 + landDash.x(), land.x(), "0.5 of its look on land");
        assertEquals(2.5 + landDash.z(), land.z());
        HorseState cooling = Horse.tick(dashed, STILL, world, NAUTILUS);
        assertEquals(38, cooling.dashCooldown());
        assertSame(cooling, Horse.onNautilusJump(cooling, 100), "no dash before it cools down");
        for (int i = 0; i < 39; i++) {
            cooling = Horse.tick(cooling, STILL, world, NAUTILUS);
        }
        assertEquals(0, cooling.dashCooldown(), "cooled down 40 ticks after the dash");
        assertEquals(1.0F, Horse.onNautilusJump(cooling, 100).jumpPendingScale());
    }

    @Test
    void theDashingFlagsDataCoolsADashThatHasCooledDown() throws Colliders.Unknown {
        assertEquals(40, Horse.onDashSynced(at(4.5, 63.0, 2.5, false, null, 0)).dashCooldown());
        assertEquals(7, Horse.onDashSynced(at(4.5, 63.0, 2.5, false, null, 7)).dashCooldown());
        HorseState unknown = at(4.5, 63.0, 2.5, false, null, -1);
        assertEquals(-1, Horse.onDashSynced(unknown).dashCooldown());
        assertEquals(-1, Horse.tick(unknown, STILL, world, NAUTILUS).dashCooldown(), "not known until it dashes");
        assertEquals(39, Horse.tick(Horse.onNautilusJump(unknown, 10), STILL, world, NAUTILUS).dashCooldown(),
                "a riding jump the client sent: it had cooled down");
    }
}
