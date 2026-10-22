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
 * the client's auto-jump option on. {@code truth-effects.jsonl.gz} holds the ticks under effects the step reads: jump
 * boost II (sprint jumps, auto-jump at a two-block wall), slow falling (jumps, a six-block drop), levitation II and
 * blindness (sprint key and a double tap). {@code truth-use.jsonl.gz} holds the ticks with an item in use: a golden apple
 * eaten while sprinting and walking, a shield raised behind a sword (the sprint key, a double tap and sneaking while
 * blocking), a spear charged while a sprint starts, a bow drawn. {@code truth-climb.jsonl.gz} holds a ladder, vines and an
 * open trapdoor over a ladder, climbed by walking into them, held with shift, jumped on and slid down, and the client
 * pushing itself out of a wall it was placed 0.15 into. {@code truth-bounce.jsonl.gz} holds a slime pad walked across
 * (sneaking too), jumped across and dropped onto (with shift held too), and a bed jumped and dropped onto.
 * {@code truth-fence.jsonl.gz} holds a fence line, a lone fence post, a low wall line and closed fence gates walked along
 * on top (sneaking to their edges too) and jumped along until the player drops off, and the fence walked and jumped
 * into from the ground. {@code truth-water.jsonl.gz} holds a pool walked into, crossed jumping, sunk into with shift, swum
 * across under water (level, down, up, with dolphin's grace) and left at its wall, a channel's current walked against,
 * stood in and sprinted through, a waterfall against a wall stood and jumped in, and a tunnel crawled out of. Each tick, run from its start in the same world (rebuilt here from the
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
        {44, -60, -1, 44, -60, 1, blockState("minecraft:smooth_stone_slab", "type=bottom", "waterlogged=false")},
        {45, -60, -1, 46, -60, 1, blockState("minecraft:stone")},
        {46, -59, -1, 46, -59, 1, blockState("minecraft:smooth_stone_slab", "type=bottom", "waterlogged=false")},
        {52, -61, -1, 62, -61, 1, blockState("minecraft:ice")},
        {70, -61, -1, 74, -61, 1, blockState("minecraft:soul_sand")},
        {82, -60, -2, 82, -59, 2, blockState("minecraft:stone")},
        {88, -60, -1, 91, -60, 1, blockState("minecraft:stone")},
        {98, -58, -1, 101, -58, 1, blockState("minecraft:stone")},
        {124, -60, -1, 124, -60, 1, blockState("minecraft:oak_stairs", "facing=east", "half=bottom", "shape=straight", "waterlogged=false")},
        {125, -60, -1, 127, -60, 1, blockState("minecraft:stone")},
        {136, -60, -1, 139, -60, 1, blockState("minecraft:stone")},
        {143, -60, -3, 146, -60, 3, blockState("minecraft:stone")},
        {170, -60, -1, 170, -59, 1, blockState("minecraft:stone")},
        {62, -60, -11, 62, -55, -9, blockState("minecraft:stone")},
        {61, -60, -10, 61, -57, -10, blockState("minecraft:ladder", "facing=west", "waterlogged=false")},
        {67, -60, -11, 67, -55, -9, blockState("minecraft:stone")},
        {66, -60, -10, 66, -57, -10, blockState("minecraft:vine", "east=true", "north=false", "south=false", "up=false", "west=false")},
        {72, -60, -11, 72, -58, -9, blockState("minecraft:stone")},
        {71, -60, -10, 71, -60, -10, blockState("minecraft:ladder", "facing=west", "waterlogged=false")},
        {71, -59, -10, 71, -59, -10, blockState("minecraft:oak_trapdoor", "facing=west", "half=bottom", "open=true", "powered=false", "waterlogged=false")},
        {80, -61, -16, 85, -61, -13, blockState("minecraft:slime_block")},
        {90, -60, -15, 90, -60, -15, blockState("minecraft:red_bed", "facing=east", "occupied=false", "part=foot")},
        {91, -60, -15, 91, -60, -15, blockState("minecraft:red_bed", "facing=east", "occupied=false", "part=head")},
        // the fence, wall and gate lines as the server connected them
        {100, -60, 5, 100, -60, 5, fence("east=true", "west=false")},
        {101, -60, 5, 105, -60, 5, fence("east=true", "west=true")},
        {106, -60, 5, 106, -60, 5, fence("east=false", "west=true")},
        {109, -60, 5, 109, -60, 5, fence("east=false", "west=false")},
        {100, -60, 8, 100, -60, 8, wall("east=low", "up=true", "west=none")},
        {101, -60, 8, 105, -60, 8, wall("east=low", "up=false", "west=low")},
        {106, -60, 8, 106, -60, 8, wall("east=none", "up=true", "west=low")},
        {100, -60, 11, 104, -60, 11, blockState("minecraft:oak_fence_gate", "facing=north", "in_wall=false", "open=false", "powered=false")},
    };

    private static int fence(String east, String west) {
        return blockState("minecraft:oak_fence", east, "north=false", "south=false", "waterlogged=false", west);
    }

    private static int wall(String east, String up, String west) {
        return blockState("minecraft:cobblestone_wall", east, "north=none", "south=none", up, "waterlogged=false", west);
    }

    /**
     * The water lanes as the client held them (course-water.txt.gz: {@code x y z block[properties]}, every block that is
     * not the flat world's), read from the session's own replica: the pool, the channel's flow and the waterfall's spread.
     */
    private static final java.util.Map<Long, Integer> WATER_LANES = readBlocks("course-water.txt.gz");

    private static java.util.Map<Long, Integer> readBlocks(String resource) {
        java.util.Map<Long, Integer> blocks = new java.util.HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(OnFootTruthTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                String[] f = line.split(" ", 4);
                int open = f[3].indexOf('[');
                String block = open < 0 ? f[3] : f[3].substring(0, open);
                String properties = open < 0 ? "" : f[3].substring(open + 1, f[3].length() - 1);
                int id = -1;
                for (int s = STATES.firstStateOf(block); s >= 0 && s < STATES.size() && STATES.block(s).equals(block); s++) {
                    if (STATES.properties(s).equals(properties)) {
                        id = s;
                        break;
                    }
                }
                if (id < 0) {
                    throw new AssertionError(line);
                }
                blocks.put(key(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])), id);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return blocks;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y & 0xFFF;
    }

    private static final Colliders.World COURSE_WORLD = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            Integer lane = WATER_LANES.get(key(x, y, z));
            if (lane != null) {
                return lane;
            }
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

    @Test
    void effectsAreTheClientsOwn() throws Exception {
        int[] counts = replay("truth-effects.jsonl.gz", COURSE_WORLD);
        assertTrue(counts[0] > 450 && counts[1] >= 3 && counts[7] > 150 && counts[8] > 150 && counts[9] >= 15 && counts[10] > 80,
                java.util.Arrays.toString(counts));
    }

    @Test
    void climbingIsTheClientsOwn() throws Exception {
        int[] counts = replay("truth-climb.jsonl.gz", COURSE_WORLD);
        assertTrue(counts[0] > 150 && counts[13] > 100 && counts[14] >= 1, java.util.Arrays.toString(counts));
    }

    @Test
    void slimeAndBedBouncesAreTheClientsOwn() throws Exception {
        int[] counts = replay("truth-bounce.jsonl.gz", COURSE_WORLD);
        // landings that bounce on slime and on the bed, walking on slime, and landings on it with shift held (no bounce)
        assertTrue(counts[0] > 600 && counts[15] >= 5 && counts[16] >= 2 && counts[17] > 20 && counts[18] >= 1, java.util.Arrays.toString(counts));
    }

    @Test
    void standingOnFencesWallsAndGatesIsTheClientsOwn() throws Exception {
        int[] counts = replay("truth-fence.jsonl.gz", COURSE_WORLD);
        // ticks ending on top of the fence line, the lone post, the wall line and the gates, sneaking at their edges among them
        assertTrue(counts[0] > 500 && counts[19] > 250 && counts[3] > 30, java.util.Arrays.toString(counts));
    }

    @Test
    void waterAndCrawlingAreTheClientsOwn() throws Exception {
        int[] counts = replay("truth-water.jsonl.gz", COURSE_WORLD);
        // ticks in water, swimming, with the eye under water, in the channel's current and the waterfall, and crawling
        assertTrue(counts[0] > 600 && counts[20] > 300 && counts[21] > 30 && counts[22] > 100 && counts[23] > 30 && counts[24] > 20
                && counts[25] > 10, java.util.Arrays.toString(counts));
    }

    @Test
    void itemUseIsTheClientsOwn() throws Exception {
        int[] counts = replay("truth-use.jsonl.gz", COURSE_WORLD);
        assertTrue(counts[0] > 180 && counts[11] > 180 && counts[12] > 20 && counts[3] > 10, java.util.Arrays.toString(counts));
    }

    /**
     * {ticks, jumps, sprinting, crouching, horizontal collisions, ceiling hits, auto-jumps triggered, then ticks under jump
     * boost, slow falling, levitation, blindness, then ticks with an item in use, and with one in use while sprinting, then
     * ticks ending in a climbable column, and ticks after a push out of a block, then landings that bounce on the slime
     * pad and on the bed, ticks walking on the slime pad, and landings on it with shift held, then ticks ending on top of a
     * fence, a wall or a fence gate, then ticks in water, swimming, with the eye under water, in the channel's current and in
     * the waterfall, and crawling}.
     */
    private static int[] replay(String resource, Colliders.World world) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(OnFootTruthTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                lines.add(line);
            }
        }
        int[] counts = new int[26];
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
                OnFoot.Effects effects = effects(pre);
                OnFoot.ItemUse use = use(pre);
                predicted = OnFoot.tick(before, keys(text(post, "keys")), world,
                        new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, autoJump, OnFoot.Speed.VANILLA, effects, use));
                counts[11] += use != null ? 1 : 0;
                counts[12] += use != null && predicted.sprinting() ? 1 : 0;
                // on a climbable block at the end (the ladder, vine and trapdoor columns), and pushed out of a block
                int fx = (int) Math.floor(expected.x());
                int fz = (int) Math.floor(expected.z());
                counts[13] += fz == -10 && (fx == 61 || fx == 66 || fx == 71) && expected.y() < -56.0 ? 1 : 0;
                counts[14] += before.x() + 0.3 > 72.0 && before.z() > -11.0 && before.z() < -9.0 ? 1 : 0; // the box inside the wall at x 72
                counts[7] += effects.jumpBoost() > 0 ? 1 : 0;
                counts[8] += effects.slowFalling() ? 1 : 0;
                counts[9] += effects.levitation() > 0 ? 1 : 0;
                counts[10] += effects.blindness() ? 1 : 0;
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
            boolean overSlime = expected.x() > 79.7 && expected.x() < 86.3 && expected.z() > -16.3 && expected.z() < -12.7;
            boolean overBed = expected.x() > 89.7 && expected.x() < 92.3 && expected.z() > -15.3 && expected.z() < -13.7;
            boolean landed = expected.verticalCollisionBelow() && before.deltaMovement().y() < -0.2;
            counts[15] += landed && overSlime && expected.deltaMovement().y() > 0.0 ? 1 : 0;
            counts[16] += landed && overBed && expected.deltaMovement().y() > 0.0 ? 1 : 0;
            counts[17] += expected.onGround() && overSlime && expected.deltaMovement().horizontalDistanceSqr() > 0.0 ? 1 : 0;
            counts[18] += landed && overSlime && expected.keys().shift() ? 1 : 0;
            BlockPos on = expected.support();
            counts[19] += expected.onGround() && on != null && on.y() == -60 && on.x() >= 100 && on.x() <= 109 && (on.z() == 5 || on.z() == 8 || on.z() == 11)
                    ? 1 : 0;
            boolean inWater = post.contains("\"wasTouchingWater\":true");
            counts[20] += inWater ? 1 : 0;
            counts[21] += expected.swimming() ? 1 : 0;
            counts[22] += expected.eyeInWater() ? 1 : 0;
            counts[23] += inWater && expected.z() > -10.0 && expected.z() < -9.0 && expected.x() > 134.0 && expected.x() < 142.0 ? 1 : 0;
            counts[24] += inWater && expected.x() > 159.0 && expected.x() < 161.0 && expected.z() > -10.5 && expected.z() < -8.5 ? 1 : 0;
            counts[25] += expected.pose() == PlayerState.Pose.SWIMMING && !inWater ? 1 : 0;
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
                vec(list(line, "stuckSpeedMultiplier")), line.contains("\"swimming\":true"), line.contains("\"eyeInWater\":true"));
    }

    /** The recorded active effects ({@code "effects":{"minecraft:jump_boost":1,...}}, newer files only), by amplifier. */
    private static OnFoot.Effects effects(String line) {
        Matcher m = Pattern.compile("\"effects\":\\{([^}]*)\\}").matcher(line);
        int jumpBoost = 0;
        int levitation = 0;
        boolean slowFalling = false;
        boolean blindness = false;
        boolean dolphinsGrace = false;
        if (m.find() && !m.group(1).isEmpty()) {
            for (String entry : m.group(1).split(",")) {
                String[] kv = entry.split("\":");
                String key = kv[0].substring(1);
                int level = Integer.parseInt(kv[1]) + 1;
                switch (key) {
                    case "minecraft:jump_boost" -> jumpBoost = level;
                    case "minecraft:levitation" -> levitation = level;
                    case "minecraft:slow_falling" -> slowFalling = true;
                    case "minecraft:blindness" -> blindness = true;
                    case "minecraft:dolphins_grace" -> dolphinsGrace = true;
                    default -> { } // not read by the step (a golden apple's absorption and regeneration): the ticks show it
                }
            }
        }
        return new OnFoot.Effects(jumpBoost, levitation, slowFalling, blindness, dolphinsGrace);
    }

    /** The item in use at the tick's start (newer files only): its prototype's {@code use_effects}; null when none. */
    private static OnFoot.ItemUse use(String line) {
        if (!line.contains("\"usingItem\":true")) {
            return null;
        }
        String stack = text(line, "useItem");
        dev.whynotac.world.ItemPrototypes.Prototype p = dev.whynotac.world.ItemPrototypes.vanilla().get(stack.substring(stack.lastIndexOf(' ') + 1));
        return new OnFoot.ItemUse(p.useCanSprint(), p.useSpeed());
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
