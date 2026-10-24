package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Gliding with an elytra ({@code LivingEntity.travelFallFlying}, decompiled 1.21.11): the movement as the client computes it,
 * the start on a new jump press in the air, a climbable block ending it, and what the flag changes elsewhere in the tick.
 */
class GlideTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final OnFoot.Conditions GLIDER = new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, false, OnFoot.Speed.VANILLA,
            OnFoot.Effects.NONE, null, true);
    private static final OnFoot.Conditions NO_GLIDER = new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, false, OnFoot.Speed.VANILLA,
            OnFoot.Effects.NONE, null, false);
    private static final PlayerState.Keys JUMP = new PlayerState.Keys(false, false, false, false, true, false, false);

    /** Air everywhere but the blocks put, stone below y 0. */
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

    private void put(int x, int y, int z, String block) {
        blocks.put(key(x, y, z), STATES.firstStateOf(block));
    }

    private static PlayerState state(double x, double y, double z, Vec3d delta, float yRot, float xRot, boolean onGround, boolean fallFlying,
            PlayerState.Pose pose, PlayerState.Keys keys) {
        return new PlayerState(x, y, z, delta, yRot, xRot, onGround, false, false, onGround, onGround, 0.0, false, false, false, pose, keys, 0, 0, 0,
                null, false, Vec3d.ZERO, false, false, fallFlying);
    }

    /** {@code updateFallFlyingMovement}, transcribed from the decompiled client as it reads. */
    private static Vec3d vanilla(Vec3d d, float xRot, float yRot) {
        return vanilla(d, xRot, yRot, PlatformCos.X86_64.cos(xRot * (float) (Math.PI / 180.0))); // the conditions' default
    }

    /** The same with {@code cos} as the platform's {@code Math.cos} of the pitch. */
    private static Vec3d vanilla(Vec3d d, float xRot, float yRot, double cos) {
        float f = xRot * (float) (Math.PI / 180.0);
        float g = -yRot * (float) (Math.PI / 180.0);
        float h = VanillaMath.cos(g);
        float i = VanillaMath.sin(g);
        float j = VanillaMath.cos(f);
        float k = VanillaMath.sin(f);
        double lx = i * j;
        double ly = -k;
        double lz = h * j;
        float pitch = xRot * (float) (Math.PI / 180.0);
        double horizontal = Math.sqrt(lx * lx + lz * lz);
        double speed = Math.sqrt(d.x() * d.x() + d.z() * d.z());
        double gravity = 0.08;
        double square = cos * cos;
        double x = d.x();
        double y = d.y() + gravity * (-1.0 + square * 0.75);
        double z = d.z();
        if (y < 0.0 && horizontal > 0.0) {
            double sink = y * -0.1 * square;
            x += lx * sink / horizontal;
            y += sink;
            z += lz * sink / horizontal;
        }
        if (pitch < 0.0F && horizontal > 0.0) {
            double climb = speed * -VanillaMath.sin(pitch) * 0.04;
            x += -lx * climb / horizontal;
            y += climb * 3.2;
            z += -lz * climb / horizontal;
        }
        if (horizontal > 0.0) {
            x += (lx / horizontal * speed - x) * 0.1;
            z += (lz / horizontal * speed - z) * 0.1;
        }
        assertEquals(ly, Vec3d.viewVector(xRot, yRot).y());
        return new Vec3d(x * 0.99F, y * 0.98F, z * 0.99F);
    }

    @Test
    void aGlideTickIsTheClientsMovementThenTheMove() throws Colliders.Unknown {
        for (float[] look : new float[][] {{45.0F, -20.0F}, {-120.5F, 35.0F}, {10.0F, 0.0F}, {170.0F, -89.5F}}) {
            Vec3d delta = new Vec3d(0.6, -0.3, 0.2);
            PlayerState pre = state(0.5, 100.0, 0.5, delta, look[0], look[1], false, true, PlayerState.Pose.FALL_FLYING, PlayerState.Keys.NONE);
            OnFoot.Step step = OnFoot.step(pre, PlayerState.Keys.NONE, world, NO_GLIDER);
            Vec3d expected = vanilla(delta, look[1], look[0]);
            PlayerState after = step.state();
            assertEquals(expected, after.deltaMovement(), "yaw " + look[0] + " pitch " + look[1]);
            assertEquals(0.5 + expected.x(), after.x());
            assertEquals(100.0 + expected.y(), after.y());
            assertEquals(0.5 + expected.z(), after.z());
            assertTrue(after.fallFlying());
            assertEquals(PlayerState.Pose.FALL_FLYING, after.pose());
            assertFalse(step.startedGliding());
        }
    }

    @Test
    void aNewJumpPressInTheAirWithAGliderStartsGlidingInThatTick() throws Colliders.Unknown {
        Vec3d delta = new Vec3d(0.1, -0.4, 0.0);
        PlayerState falling = state(0.5, 100.0, 0.5, delta, 0.0F, 10.0F, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE);
        OnFoot.Step step = OnFoot.step(falling, JUMP, world, GLIDER);
        assertTrue(step.startedGliding());
        assertTrue(step.state().fallFlying());
        assertEquals(PlayerState.Pose.FALL_FLYING, step.state().pose());
        assertEquals(vanilla(delta, 10.0F, 0.0F), step.state().deltaMovement(), "travelFallFlying already in the tick that started it");

        assertFalse(OnFoot.step(falling, JUMP, world, NO_GLIDER).startedGliding(), "nothing to glide with");
        PlayerState held = state(0.5, 100.0, 0.5, delta, 0.0F, 10.0F, false, false, PlayerState.Pose.STANDING, JUMP);
        assertFalse(OnFoot.step(held, JUMP, world, GLIDER).startedGliding(), "the jump key held since the tick before");
        PlayerState standing = state(0.5, 0.0, 0.5, new Vec3d(0.0, -0.0784000015258789, 0.0), 0.0F, 0.0F, true, false, PlayerState.Pose.STANDING,
                PlayerState.Keys.NONE);
        OnFoot.Step jump = OnFoot.step(standing, JUMP, world, GLIDER);
        assertFalse(jump.startedGliding(), "on the ground: canGlide is false (the jump comes after)");
        assertFalse(jump.state().fallFlying());
        OnFoot.Conditions levitating = new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, false, OnFoot.Speed.VANILLA,
                new OnFoot.Effects(0, 1, false, false), null, true);
        assertFalse(OnFoot.step(falling, JUMP, world, levitating).startedGliding(), "levitation");
    }

    @Test
    void aLadderEndsTheGlideAVineDoesNot() throws Colliders.Unknown {
        Vec3d delta = new Vec3d(0.0, -0.2, 0.0);
        put(0, 100, 0, "minecraft:vine");
        PlayerState inVine = state(0.5, 100.2, 0.5, delta, 0.0F, 0.0F, false, true, PlayerState.Pose.FALL_FLYING, PlayerState.Keys.NONE);
        PlayerState through = OnFoot.tick(inVine, PlayerState.Keys.NONE, world, NO_GLIDER);
        assertTrue(through.fallFlying(), "can_glide_through");
        assertEquals(vanilla(delta, 0.0F, 0.0F), through.deltaMovement());
        blocks.put(key(0, 100, 0), stateOf("minecraft:ladder", "facing=north,waterlogged=false"));
        PlayerState onLadder = OnFoot.tick(inVine, PlayerState.Keys.NONE, world, NO_GLIDER);
        assertFalse(onLadder.fallFlying(), "stopFallFlying");
        assertEquals(-0.15F, onLadder.y() - 100.2, 1.0E-6, "a step in the air, held to 0.15 a tick by the ladder");
    }

    @Test
    void theGroundBelowDoesNotSlowAGlide() throws Colliders.Unknown {
        put(0, -1, 0, "minecraft:soul_sand"); // its speed factor 0.4 slows a walk
        Vec3d delta = new Vec3d(0.5, -0.1, 0.0);
        PlayerState sliding = state(0.5, 0.0, 0.5, delta, -90.0F, 0.0F, true, true, PlayerState.Pose.FALL_FLYING, PlayerState.Keys.NONE);
        PlayerState after = OnFoot.tick(sliding, PlayerState.Keys.NONE, world, NO_GLIDER);
        Vec3d glide = vanilla(delta, 0.0F, -90.0F);
        assertEquals(glide.x(), after.deltaMovement().x(), "Player.getBlockSpeedFactor: 1 while gliding");
        assertEquals(0.5 + glide.x(), after.x());
        assertTrue(after.fallFlying(), "only the server ends a glide on the ground");
    }

    @Test
    void aGlideNeitherStartsSprintingNorCountsTheFallBeyondOneWhileSlow() throws Colliders.Unknown {
        PlayerState.Keys sprint = new PlayerState.Keys(true, false, false, false, false, false, true);
        PlayerState gliding = state(0.5, 100.0, 0.5, new Vec3d(0.3, -0.2, 0.0), -90.0F, 0.0F, false, true, PlayerState.Pose.FALL_FLYING, sprint);
        PlayerState after = OnFoot.tick(new PlayerState(gliding.x(), gliding.y(), gliding.z(), gliding.deltaMovement(), gliding.yRot(), gliding.xRot(),
                false, false, false, false, false, 5.0, false, false, false, PlayerState.Pose.FALL_FLYING, sprint, 0, 0, 0, null, false, Vec3d.ZERO,
                false, false, true), sprint, world, NO_GLIDER);
        assertFalse(after.sprinting(), "canStartSprinting: not while gliding (out of water)");
        // checkFallDistanceAccumulation: above -0.5, the fall counts from 1, then the move adds its drop (as a float)
        assertEquals(1.0 - (float) after.deltaMovement().y(), after.fallDistance());
    }

    @Test
    void aGlideIntoAFluidIsNotModelled() {
        put(0, 100, 0, "minecraft:water");
        PlayerState wet = state(0.5, 100.2, 0.5, new Vec3d(0.0, -0.2, 0.0), 0.0F, 0.0F, false, true, PlayerState.Pose.FALL_FLYING, PlayerState.Keys.NONE);
        assertThrows(Colliders.Unknown.class, () -> OnFoot.tick(wet, PlayerState.Keys.NONE, world, NO_GLIDER));
    }

    @Test
    void aFastFallGoesOnWhereItsRayMeetsNothingThatResetsIt() throws Colliders.Unknown {
        // Entity.move: a move of a block or more while falling casts a ray that a vine, cobweb or water on its way ends the fall at
        PlayerState fast = state(0.5, 50.0, 0.5, new Vec3d(0.0, -1.5, 0.0), 0.0F, 0.0F, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE);
        PlayerState falling = new PlayerState(fast.x(), fast.y(), fast.z(), fast.deltaMovement(), 0.0F, 0.0F, false, false, false, false, false, 20.0,
                false, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, false, false);
        PlayerState after = OnFoot.tick(falling, PlayerState.Keys.NONE, world, NO_GLIDER);
        double dy = after.y() - 50.0;
        assertTrue(dy < -1.0, String.valueOf(dy));
        assertEquals(20.0 - (float) dy, after.fallDistance(), 1.0E-6, "nothing on the way: the fall goes on");
        put(0, 48, 0, "minecraft:cobweb");
        assertThrows(Colliders.Unknown.class, () -> OnFoot.tick(falling, PlayerState.Keys.NONE, world, NO_GLIDER));
        blocks.clear();
        put(0, 49, 0, "minecraft:water");
        assertThrows(Colliders.Unknown.class, () -> OnFoot.tick(falling, PlayerState.Keys.NONE, world, NO_GLIDER));
        blocks.clear();
        put(0, 45, 0, "minecraft:cobweb"); // below where the move ends
        assertEquals(after, OnFoot.tick(falling, PlayerState.Keys.NONE, world, NO_GLIDER));
    }

    @Test
    void aGlideTakesTheCosineOfTheClientsPlatform() throws Colliders.Unknown {
        Vec3d delta = new Vec3d(0.6, -0.3, 0.2);
        for (float pitch : new float[] {34.2F, 62.7F}) { // where x86_64's, then aarch64's, Math.cos is not fdlibm's
            PlayerState pre = state(0.5, 100.0, 0.5, delta, 45.0F, pitch, false, true, PlayerState.Pose.FALL_FLYING, PlayerState.Keys.NONE);
            java.util.Set<Double> cosines = new java.util.HashSet<>();
            for (PlatformCos platform : PlatformCos.values()) {
                OnFoot.Step step = OnFoot.step(pre, PlayerState.Keys.NONE, world, new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, false,
                        OnFoot.Speed.VANILLA, OnFoot.Effects.NONE, null, false, platform));
                double cos = platform.cos(pitch * (float) (Math.PI / 180.0));
                assertEquals(cos, OnFoot.glideCosine(platform, pitch));
                assertEquals(vanilla(delta, pitch, 45.0F, cos), step.state().deltaMovement(), platform + " at " + pitch);
                assertTrue(step.readCosine());
                cosines.add(cos);
            }
            assertEquals(2, cosines.size(), "one platform's cosine an ulp from the others'");
        }
        assertFalse(OnFoot.step(state(0.5, 100.0, 0.5, delta, 45.0F, 17.3F, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE),
                PlayerState.Keys.NONE, world, NO_GLIDER).readCosine(), "a fall reads no cosine");
    }
}
