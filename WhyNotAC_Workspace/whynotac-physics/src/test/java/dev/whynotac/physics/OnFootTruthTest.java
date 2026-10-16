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
 * jumping, strafing, sneaking on a superflat world; tools/realclient). Each tick, run from its start in the
 * same flat world, must end exactly as the client's did. The real-client CI job runs the same comparison
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

    @Test
    void everyRecordedTickEndsAsTheClientsDid() throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(OnFootTruthTest.class.getResourceAsStream("truth-flat.jsonl.gz")), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                lines.add(line);
            }
        }
        int ticks = 0;
        int jumps = 0;
        int sprinting = 0;
        int crouching = 0;
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            String pre = lines.get(i);
            String post = lines.get(i + 1);
            PlayerState before = state(pre);
            PlayerState expected = state(post);
            PlayerState predicted = OnFoot.tick(before, keys(text(post, "keys")), FLAT, OnFoot.Conditions.VANILLA_DEFAULTS);
            String where = "tick " + number(pre, "tickCount") + " keys " + text(post, "keys");
            assertEquals(expected, predicted, where);
            ticks++;
            jumps += expected.noJumpDelay() == 10 ? 1 : 0;
            sprinting += expected.sprinting() ? 1 : 0;
            crouching += expected.crouching() ? 1 : 0;
        }
        // the fixture exercises what it claims to
        assertTrue(ticks > 150 && jumps >= 3 && sprinting > 10 && crouching > 10, ticks + " ticks, " + jumps + " jumps, " + sprinting + " sprinting, "
                + crouching + " crouching");
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
                bool(line, "crouching"), PlayerState.Pose.valueOf(text(line, "pose")), keys(text(line, "keys")), number(line, "noJumpDelay"),
                number(line, "sprintTriggerTime"), number(line, "autoJumpTime"), supportPos, bool(line, "onGroundNoBlocks"),
                vec(list(line, "stuckSpeedMultiplier")));
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
