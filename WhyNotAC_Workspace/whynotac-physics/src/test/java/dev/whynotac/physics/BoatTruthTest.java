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
 * {@link Boat} against ticks of the official client: {@code truth-boat.jsonl.gz} holds an oak boat's state before and after
 * every {@code AbstractBoat.tick()} it ran while the client's own player steered it (the truth agent's {@code boatPre} and
 * {@code boatPost}, tools/realclient): paddled across a pool two deep, turned, backed and driven into its bank; sunk to
 * the floor of a pit three deep in it; hung in the air without gravity and let fall onto the water; driven over ice and
 * blue ice into a stone wall, head on and at an angle; dropped onto the ice and onto slime; dragged over grass. Each tick,
 * run from its start in the same world (rebuilt here from the commands that built the course), must end exactly as the
 * client's did. The real-client CI job runs the same comparison on every new session (MovementTruth), in the world
 * WhyNotAC's replica holds.
 */
class BoatTruthTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final int AIR = STATES.firstStateOf("minecraft:air");

    /** run_realclient.sh's boat course on the default superflat world: {x0, y0, z0, x1, y1, z1, state}, the later over the earlier. */
    private static final int[][] COURSE = {
        {40, -62, -40, 70, -61, -28, state("minecraft:water", "level=0")},
        {60, -63, -37, 66, -63, -31, state("minecraft:water", "level=0")},
        {76, -61, -40, 87, -61, -28, state("minecraft:ice", "")},
        {88, -61, -40, 100, -61, -28, state("minecraft:blue_ice", "")},
        {101, -60, -40, 101, -59, -28, state("minecraft:stone", "")},
        {76, -61, -26, 84, -61, -24, state("minecraft:slime_block", "")},
    };

    private static int state(String block, String properties) {
        for (int s = STATES.firstStateOf(block); s >= 0 && s < STATES.size() && STATES.block(s).equals(block); s++) {
            if (STATES.properties(s).equals(properties)) {
                return s;
            }
        }
        throw new AssertionError(block + "[" + properties + "]");
    }

    private static final Colliders.World WORLD = new Colliders.World() {
        private final int bedrock = STATES.firstStateOf("minecraft:bedrock");
        private final int dirt = STATES.firstStateOf("minecraft:dirt");
        private final int grass = STATES.firstStateOf("minecraft:grass_block");

        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            for (int i = COURSE.length - 1; i >= 0; i--) {
                int[] c = COURSE[i];
                if (x >= c[0] && x <= c[3] && y >= c[1] && y <= c[4] && z >= c[2] && z <= c[5]) {
                    return c[6];
                }
            }
            return switch (y) {
                case -64 -> bedrock;
                case -63, -62 -> dirt;
                case -61 -> grass;
                default -> AIR;
            };
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public Boolean fastLava() {
            return false;
        }
    };

    @Test
    void everyRecordedBoatTickEndsAsTheClientsDid() throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(BoatTruthTest.class.getResourceAsStream("truth-boat.jsonl.gz")), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                lines.add(line);
            }
        }
        int[] counts = new int[12];
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            String pre = lines.get(i);
            String post = lines.get(i + 1);
            if (bool(pre, "interpolating") || bool(pre, "firstTick")) {
                continue; // the client is easing the boat toward a position the server sent, or it is the boat's first tick: not modelled
            }
            BoatState before = state(pre);
            BoatState expected = state(post);
            String input = text(pre, "input");
            Boat.Input keys = new Boat.Input(input.charAt(0) == 'L', input.charAt(1) == 'R', input.charAt(2) == 'U', input.charAt(3) == 'D');
            BoatState predicted = Boat.tick(before, keys, WORLD, new Boat.Conditions(bool(pre, "noGravity"), null, List.of()));
            assertEquals(expected, predicted, "boat tick " + number(pre, "tickCount") + " input " + input);
            counts[0]++;
            counts[expected.status().ordinal() + 1]++; // IN_WATER, UNDER_WATER, UNDER_FLOWING_WATER, ON_LAND, IN_AIR
            counts[6] += before.status() == BoatState.Status.IN_AIR && expected.status() == BoatState.Status.IN_WATER ? 1 : 0;
            counts[7] += expected.horizontalCollision() ? 1 : 0;
            counts[8] += expected.deltaRotation() != 0.0F && (keys.left() || keys.right()) ? 1 : 0;
            counts[9] += bool(pre, "noGravity") ? 1 : 0;
            counts[10] += expected.verticalCollisionBelow() && before.deltaMovement().y() < -0.2 ? 1 : 0; // the status is the tick's start: the landing tick began in the air
            counts[11] += keys.down() ? 1 : 0;
        }
        // every status but flowing water, landings on water from the air, walls, turns, no gravity, landings on land, backing
        assertTrue(counts[0] > 400 && counts[1] > 100 && counts[2] > 10 && counts[4] > 100 && counts[5] > 20 && counts[6] >= 1 && counts[7] > 10
                && counts[8] > 30 && counts[9] > 5 && counts[10] >= 2 && counts[11] > 10, java.util.Arrays.toString(counts));
    }

    private static BoatState state(String line) {
        List<String> pos = list(line, "pos");
        String support = raw(line, "support");
        int status = (int) number(line, "status");
        BlockPos on = null;
        if (!support.equals("null")) {
            String[] p = support.substring(1, support.length() - 1).split(",");
            on = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        return new BoatState(f64(pos.get(0)), f64(pos.get(1)), f64(pos.get(2)), vec(line, "deltaMovement"), f32(text(line, "yRot")),
                f32(text(line, "xRot")), f32(text(line, "deltaRotation")), status < 0 ? null : BoatState.Status.values()[status],
                f64(text(line, "lastYd")), f64(text(line, "fallDistance")), bool(line, "onGround"), bool(line, "horizontalCollision"),
                bool(line, "verticalCollision"), bool(line, "verticalCollisionBelow"), on, bool(line, "onGroundNoBlocks"),
                vec(line, "stuckSpeedMultiplier"));
    }

    private static String raw(String line, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(\\[[^\\]]*\\]|\"[^\"]*\"|[^,}]*)").matcher(line);
        if (!m.find()) {
            throw new AssertionError("no " + key + " in " + line);
        }
        return m.group(1);
    }

    private static String text(String line, String key) {
        String v = raw(line, key);
        return v.substring(1, v.length() - 1);
    }

    private static long number(String line, String key) {
        return Long.parseLong(raw(line, key));
    }

    private static boolean bool(String line, String key) {
        return Boolean.parseBoolean(raw(line, key));
    }

    private static List<String> list(String line, String key) {
        String v = raw(line, key);
        List<String> out = new ArrayList<>();
        for (String s : v.substring(1, v.length() - 1).split(",")) {
            out.add(s.substring(1, s.length() - 1));
        }
        return out;
    }

    private static Vec3d vec(String line, String key) {
        List<String> v = list(line, key);
        return new Vec3d(f64(v.get(0)), f64(v.get(1)), f64(v.get(2)));
    }

    private static double f64(String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }

    private static float f32(String hex) {
        return Float.intBitsToFloat(Integer.parseUnsignedInt(hex, 16));
    }
}
