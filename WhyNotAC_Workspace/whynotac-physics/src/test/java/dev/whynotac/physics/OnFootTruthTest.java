package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * {@link OnFoot} against ticks of the official client: {@code truth-flat.jsonl.gz} holds the client's own
 * state before and after every tick in which it moved during a real-client session (walking, sprinting,
 * jumping, strafing, sneaking on a superflat world; tools/realclient). {@code truth-course.jsonl.gz} holds the
 * movement course of the same session: step-ups onto a slab, a block and a slab on a block, ice, soul sand, a
 * wall met at an angle, sneaking to a platform's edge and off it, jumps under a low ceiling, a double-tapped
 * sprint and stairs. {@code truth-autojump.jsonl.gz} holds one-block steps walked into head on and at an angle with
 * the client's auto-jump option on. Each tick, run from its start in the same world (rebuilt here from the
 * commands that built the course), must end exactly as the client's did. The real-client CI job runs the same comparison
 * on every new session (MovementTruth), in the world WhyNotAC's replica holds.
 */
class OnFootTruthTest {

    private static final BlockStates STATES = BlockStates.vanilla();

    /** The default superflat world: bedrock, two dirt, grass, from the bottom of the world. */
    private static final Colliders.World FLAT = new Colliders.World() {
        private final int bedrock = STATES.firstStateOf("minecraft:bedrock");
        private final int dirt = STATES.firstStateOf("minecraft:dirt");
        private final int grass = STATES.firstStateOf("minecraft:grass_block");
        private final int air = STATES.firstStateOf("minecraft:air");

        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            return switch (y) {
                case -64 -> bedrock;
                case -63, -62 -> dirt;
                case -61 -> grass;
                default -> air;
            };
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }
    };

    /** The course of run_realclient.sh on the flat world: {x0, y0, z0, x1, y1, z1, state}. */
    private static final int[][] COURSE = {
        {44, -60, -1, 44, -60, 1, blockState("minecraft:smooth_stone_slab", "type=bottom")},
        {45, -60, -1, 46, -60, 1, blockState("minecraft:stone")},
        {46, -59, -1, 46, -59, 1, blockState("minecraft:smooth_stone_slab", "type=bottom")},
        {52, -61, -1, 62, -61, 1, blockState("minecraft:ice")},
        {70, -61, -1, 74, -61, 1, blockState("minecraft:soul_sand")},
        {82, -60, -2, 82, -59, 2, blockState("minecraft:stone")},
        {88, -60, -1, 91, -60, 1, blockState("minecraft:stone")},
        {98, -58, -1, 101, -58, 1, blockState("minecraft:stone")},
        {124, -60, -1, 124, -60, 1, blockState("minecraft:oak_stairs", "facing=east", "half=bottom", "shape=straight", "waterlogged=false")},
        {125, -60, -1, 127, -60, 1, blockState("minecraft:stone")},
        {136, -60, -1, 139, -60, 1, blockState("minecraft:stone")},
        {143, -60, -3, 146, -60, 3, blockState("minecraft:stone")},
    };

    private static final Colliders.World COURSE_WORLD = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            for (int[] c : COURSE) {
                if (x >= c[0] && x <= c[3] && y >= c[1] && y <= c[4] && z >= c[2] && z <= c[5]) {
                    return c[6];
                }
            }
            return FLAT.blockState(x, y, z);
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }
    };

    private static int blockState(String block, String... properties) {
        for (int id = 0; id < STATES.size(); id++) {
            if (STATES.block(id).equals(block) && java.util.Arrays.stream(properties).allMatch(STATES.properties(id)::contains)) {
                return id;
            }
        }
        throw new AssertionError(block);
    }

    @Test
    void everyRecordedTickOnTheFlatWorldEndsAsTheClientsDid() throws Exception {
        int[] counts = replay("truth-flat.jsonl.gz", FLAT);
        // the fixture exercises what it claims to
        assertTrue(counts[0] > 150 && counts[1] >= 3 && counts[2] > 10 && counts[3] > 10, java.util.Arrays.toString(counts));
    }

    @Test
    void everyRecordedTickOfTheCourseEndsAsTheClientsDid() throws Exception {
        int[] counts = replay("truth-course.jsonl.gz", COURSE_WORLD);
        assertTrue(counts[0] > 400 && counts[1] >= 5 && counts[3] > 20 && counts[4] > 20 && counts[5] >= 1, java.util.Arrays.toString(counts));
    }

    @Test
    void autoJumpIsTheClientsOwnAtOneBlockSteps() throws Exception {
        int[] counts = replay("truth-autojump.jsonl.gz", COURSE_WORLD);
        assertTrue(counts[0] > 150 && counts[6] >= 2 && counts[1] >= 2, java.util.Arrays.toString(counts));
    }

    /** {ticks, jumps, sprinting, crouching, horizontal collisions, ceiling hits, auto-jumps triggered}. */
    private static int[] replay(String resource, Colliders.World world) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(OnFootTruthTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                lines.add(line);
            }
        }
        int[] counts = new int[7];
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            String pre = lines.get(i);
            String post = lines.get(i + 1);
            PlayerState before = state(pre);
            PlayerState expected = state(post);
            String where = "tick " + number(pre, "tickCount") + " keys " + text(post, "keys");
            PlayerState predicted;
            try {
                // LocalPlayer.autoJumpEnabled (recorded in newer files; the older ones were recorded with the option off)
                boolean autoJump = pre.contains("\"autoJumpEnabled\":true");
                predicted = OnFoot.tick(before, keys(text(post, "keys")), world, new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, autoJump));
            } catch (Colliders.Unknown e) {
                continue; // outside the engine's scope (the session's suffocation check at the wall): no claim, nothing to compare
            }
            assertEquals(expected, predicted, where);
            counts[0]++;
            counts[1] += expected.noJumpDelay() == 10 ? 1 : 0;
            counts[2] += expected.sprinting() ? 1 : 0;
            counts[3] += expected.crouching() ? 1 : 0;
            counts[4] += expected.horizontalCollision() ? 1 : 0;
            counts[5] += expected.verticalCollision() && !expected.verticalCollisionBelow() ? 1 : 0;
            counts[6] += expected.autoJumpTime() == 1 ? 1 : 0;
        }
        return counts;
    }

    private static PlayerState state(String line) {
        List<String> pos = list(line, "pos");
        String support = raw(line, "support");
        BlockPos supportPos = null;
        if (!support.equals("null")) {
            String[] p = support.substring(1, support.length() - 1).split(",");
            supportPos = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        return new PlayerState(f64(pos.get(0)), f64(pos.get(1)), f64(pos.get(2)), vec(list(line, "deltaMovement")), f32(text(line, "yRot")),
                f32(text(line, "xRot")), bool(line, "onGround"), bool(line, "horizontalCollision"), bool(line, "minorHorizontalCollision"),
                bool(line, "verticalCollision"), bool(line, "verticalCollisionBelow"), f64(text(line, "fallDistance")), bool(line, "sprinting"),
                sprintModifier(line), bool(line, "crouching"), PlayerState.Pose.valueOf(text(line, "pose")), keys(text(line, "keys")), number(line, "noJumpDelay"),
                number(line, "sprintTriggerTime"), number(line, "autoJumpTime"), supportPos, bool(line, "onGroundNoBlocks"),
                vec(list(line, "stuckSpeedMultiplier")));
    }

    /** {@code LivingEntity.speed} (the attribute as a float, set in the last tick's aiStep) holds the sprinting modifier or not. */
    private static boolean sprintModifier(String line) {
        float speed = f32(text(line, "speed"));
        if (speed != (float) OnFoot.Speed.VANILLA.value(true) && speed != (float) OnFoot.Speed.VANILLA.value(false)) {
            throw new AssertionError("speed " + speed + " in " + line);
        }
        return speed == (float) OnFoot.Speed.VANILLA.value(true);
    }

    private static PlayerState.Keys keys(String k) {
        return new PlayerState.Keys(k.charAt(0) == 'W', k.charAt(1) == 'S', k.charAt(2) == 'A', k.charAt(3) == 'D', k.charAt(4) == 'J', k.charAt(5) == 'H',
                k.charAt(6) == 'R');
    }

    private static Vec3d vec(List<String> v) {
        return new Vec3d(f64(v.get(0)), f64(v.get(1)), f64(v.get(2)));
    }

    private static double f64(String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }

    private static float f32(String hex) {
        return Float.intBitsToFloat(Integer.parseUnsignedInt(hex, 16));
    }

    // The recorder's lines are flat: "name":value, values are strings, numbers, booleans, null, or arrays of those.
    private static String raw(String line, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(\\[[^\\]]*\\]|\"[^\"]*\"|[^,}]*)").matcher(line);
        if (!m.find()) {
            throw new AssertionError("no " + name + " in " + line);
        }
        return m.group(1);
    }

    private static String text(String line, String name) {
        String v = raw(line, name);
        return v.substring(1, v.length() - 1);
    }

    private static int number(String line, String name) {
        return Integer.parseInt(raw(line, name));
    }

    private static boolean bool(String line, String name) {
        return Boolean.parseBoolean(raw(line, name));
    }

    private static List<String> list(String line, String name) {
        String v = raw(line, name);
        List<String> out = new ArrayList<>();
        for (String part : v.substring(1, v.length() - 1).split(",")) {
            out.add(part.replace("\"", ""));
        }
        return out;
    }
}
