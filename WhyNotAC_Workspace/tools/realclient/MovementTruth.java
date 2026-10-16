import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.BlockPos;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.OnFoot;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.replay.Replayer;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Replicas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The physics engine against the real client: for every tick the truth agent recorded (the client's own
 * state before and after {@code LocalPlayer.tick()}), {@code OnFoot.tick} is run from the state before,
 * with the keys the client polled, in the world WhyNotAC's replica holds from the server's recording.
 * Every tick the engine models must come out equal to the state after, bit for bit: position, velocity,
 * collision flags, fall distance, sprint, crouch, pose, the jump and sprint counters and the supporting
 * block. Ticks outside its scope are counted by reason.
 *
 * <p>Each tick is evaluated in the world the replica holds when the client's movement packet of that tick (or of
 * the next tick that sent one) arrives in the replay: the k-th packet the client sent, rebuilt from its own
 * tick states as verify_truth.py does, is the k-th one the server recorded (teleport replies aside).
 *
 * <p>usage: java -cp WhyNotAC.jar:whynotac-physics.jar MovementTruth.java RECORDING TRUTH.jsonl; one JSON line, exit code 1 on
 * any difference
 */
public class MovementTruth {

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> truth = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            if (!line.isBlank()) {
                truth.add(Json.object(line));
            }
        }
        Replicas replicas = Replicas.vanilla();
        Comparison comparison = new Comparison(truth);
        long[] blockChanges = {0};
        boolean[] played = {false};
        boolean[] afterAccept = {false};
        List<TimelineListener> chain = new ArrayList<>();
        // First in the chain: the replicas hold the world as it was before this event.
        chain.add((session, event) -> {
            if (event instanceof TimelineEvent.Inbound in) {
                boolean reply = afterAccept[0] && in.packet() instanceof ServerboundPacket.MovePlayer;
                afterAccept[0] = in.packet() instanceof ServerboundPacket.AcceptTeleportation;
                if (in.packet() instanceof ServerboundPacket.MovePlayer && !reply) {
                    played[0] = true;
                    comparison.packetArrived(view(replicas.world().world(session)));
                }
            }
            if (played[0] && event instanceof TimelineEvent.Outbound out
                    && (out.packet() instanceof ClientboundPacket.BlockUpdate || out.packet() instanceof ClientboundPacket.SectionBlocksUpdate)) {
                blockChanges[0]++;
            }
            if (event instanceof TimelineEvent.Disconnected) {
                comparison.packetArrived(view(replicas.world().world(session))); // the ticks after the last packet
            }
        });
        chain.addAll(replicas.listeners());
        Replayer.replay(RecordingReader.open(Path.of(args[0])), chain);
        Map<String, Object> result = comparison.result();
        result.put("blockChangesDuringPlay", blockChanges[0]);
        System.out.println(Json.write(result));
        boolean clean = Long.valueOf(0).equals(result.get("mismatched")) && ((Number) result.get("matched")).longValue() > 0;
        System.exit(clean ? 0 : 1);
    }

    private static Colliders.World view(ClientWorld world) {
        return new Colliders.World() {
            @Override
            public BlockStates states() {
                return world.states();
            }

            @Override
            public int blockState(int x, int y, int z) {
                return world.blockState(x, y, z);
            }

            @Override
            public boolean isLoaded(int chunkX, int chunkZ) {
                return world.isLoaded(chunkX, chunkZ);
            }
        };
    }

    /** The recorded ticks in order, each compared when its movement packet (or the next one) arrives. */
    private static final class Comparison {
        private final List<Map<String, Object>[]> ticks = new ArrayList<>();
        private final List<Boolean> sent = new ArrayList<>();
        private int next;
        private boolean firstLoaded = true;
        long matched;
        long mismatched;
        final Map<String, Long> unknown = new TreeMap<>();
        final Map<String, Long> differingFields = new TreeMap<>();
        final List<String> examples = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Comparison(List<Map<String, Object>> truth) {
            for (int i = 0; i + 1 < truth.size(); i++) {
                Map<String, Object> pre = truth.get(i);
                Map<String, Object> post = truth.get(i + 1);
                if ("pre".equals(pre.get("hook")) && "post".equals(post.get("hook")) && pre.get("tickCount").equals(post.get("tickCount"))
                        && (Boolean) pre.get("loaded")) {
                    if ((Boolean) pre.get("passenger")) {
                        throw new IllegalStateException("the session rode a vehicle");
                    }
                    ticks.add(new Map[] {pre, post});
                    // sendPosition: a position (reminder reset), a rotation (last sent changed) or the flags changed
                    boolean position = ((Number) post.get("positionReminder")).longValue() == 0;
                    boolean rotation = !post.get("yRotLast").equals(pre.get("yRotLast")) || !post.get("xRotLast").equals(pre.get("xRotLast"));
                    boolean status = !pre.get("lastOnGround").equals(post.get("onGround"))
                            || !pre.get("lastHorizontalCollision").equals(post.get("horizontalCollision"));
                    sent.add(position || rotation || status);
                }
            }
        }

        /** A movement packet arrived: the ticks up to the one that sent it are compared in {@code world}. */
        void packetArrived(Colliders.World world) {
            while (next < ticks.size()) {
                Map<String, Object>[] tick = ticks.get(next);
                boolean sentHere = sent.get(next);
                next++;
                compare(tick[0], tick[1], world);
                if (sentHere) {
                    return;
                }
            }
        }

        private void compare(Map<String, Object> pre, Map<String, Object> post, Colliders.World world) {
            boolean autoJump = firstLoaded; // LocalPlayer.autoJumpEnabled starts true until the first sendPosition
            firstLoaded = false;
            PlayerState before = state(pre);
            PlayerState expected = state(post);
            try {
                PlayerState predicted = OnFoot.tick(before, keys((String) post.get("keys")), world,
                        new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, autoJump));
                List<String> differences = differences(predicted, expected);
                if (differences.isEmpty()) {
                    matched++;
                } else {
                    mismatched++;
                    for (String d : differences) {
                        differingFields.merge(d.substring(0, d.indexOf(':')), 1L, Long::sum);
                    }
                    if (examples.size() < 12) {
                        examples.add("tick " + pre.get("tickCount") + " keys " + post.get("keys") + ": " + String.join("; ", differences));
                    }
                }
            } catch (Colliders.Unknown e) {
                unknown.merge(e.getMessage().replaceAll(" at -?\\d+ -?\\d+ -?\\d+| at BlockPos\\[.*\\]", ""), 1L, Long::sum);
            }
        }

        Map<String, Object> result() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ticks", (long) ticks.size());
            out.put("compared", (long) next);
            out.put("matched", matched);
            out.put("mismatched", mismatched);
            out.put("unknown", unknown);
            out.put("differingFields", differingFields);
            out.put("examples", examples);
            return out;
        }
    }

    private static List<String> differences(PlayerState p, PlayerState e) {
        List<String> d = new ArrayList<>();
        bits(d, "x", p.x(), e.x());
        bits(d, "y", p.y(), e.y());
        bits(d, "z", p.z(), e.z());
        bits(d, "dx", p.deltaMovement().x(), e.deltaMovement().x());
        bits(d, "dy", p.deltaMovement().y(), e.deltaMovement().y());
        bits(d, "dz", p.deltaMovement().z(), e.deltaMovement().z());
        bits(d, "fallDistance", p.fallDistance(), e.fallDistance());
        same(d, "onGround", p.onGround(), e.onGround());
        same(d, "horizontalCollision", p.horizontalCollision(), e.horizontalCollision());
        same(d, "minorHorizontalCollision", p.minorHorizontalCollision(), e.minorHorizontalCollision());
        same(d, "verticalCollision", p.verticalCollision(), e.verticalCollision());
        same(d, "verticalCollisionBelow", p.verticalCollisionBelow(), e.verticalCollisionBelow());
        same(d, "sprinting", p.sprinting(), e.sprinting());
        same(d, "crouching", p.crouching(), e.crouching());
        same(d, "pose", p.pose(), e.pose());
        same(d, "noJumpDelay", p.noJumpDelay(), e.noJumpDelay());
        same(d, "sprintTriggerTime", p.sprintTriggerTime(), e.sprintTriggerTime());
        same(d, "autoJumpTime", p.autoJumpTime(), e.autoJumpTime());
        same(d, "support", p.support(), e.support());
        same(d, "onGroundNoBlocks", p.onGroundNoBlocks(), e.onGroundNoBlocks());
        return d;
    }

    private static void bits(List<String> d, String name, double predicted, double expected) {
        if (Double.doubleToRawLongBits(predicted) != Double.doubleToRawLongBits(expected)) {
            d.add(name + ": " + predicted + " vs client " + expected);
        }
    }

    private static void same(List<String> d, String name, Object predicted, Object expected) {
        if (!java.util.Objects.equals(predicted, expected)) {
            d.add(name + ": " + predicted + " vs client " + expected);
        }
    }

    @SuppressWarnings("unchecked")
    private static PlayerState state(Map<String, Object> r) {
        List<Object> pos = (List<Object>) r.get("pos");
        List<Object> support = (List<Object>) r.get("support");
        return new PlayerState(f64(pos.get(0)), f64(pos.get(1)), f64(pos.get(2)), vec(r.get("deltaMovement")), f32(r.get("yRot")), f32(r.get("xRot")),
                (Boolean) r.get("onGround"), (Boolean) r.get("horizontalCollision"), (Boolean) r.get("minorHorizontalCollision"),
                (Boolean) r.get("verticalCollision"), (Boolean) r.get("verticalCollisionBelow"), f64(r.get("fallDistance")), (Boolean) r.get("sprinting"),
                (Boolean) r.get("crouching"), PlayerState.Pose.valueOf((String) r.get("pose")), keys((String) r.get("keys")),
                ((Number) r.get("noJumpDelay")).intValue(), ((Number) r.get("sprintTriggerTime")).intValue(), ((Number) r.get("autoJumpTime")).intValue(),
                support == null ? null : new BlockPos(((Number) support.get(0)).intValue(), ((Number) support.get(1)).intValue(),
                        ((Number) support.get(2)).intValue()),
                (Boolean) r.get("onGroundNoBlocks"), vec(r.get("stuckSpeedMultiplier")));
    }

    /** The recorder's key string: W S A D J H R or '-' per key, in {@code Input}'s order. */
    private static PlayerState.Keys keys(String k) {
        return new PlayerState.Keys(k.charAt(0) == 'W', k.charAt(1) == 'S', k.charAt(2) == 'A', k.charAt(3) == 'D', k.charAt(4) == 'J', k.charAt(5) == 'H',
                k.charAt(6) == 'R');
    }

    @SuppressWarnings("unchecked")
    private static Vec3d vec(Object o) {
        List<Object> v = (List<Object>) o;
        return new Vec3d(f64(v.get(0)), f64(v.get(1)), f64(v.get(2)));
    }

    private static double f64(Object hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong((String) hex, 16));
    }

    private static float f32(Object hex) {
        return Float.intBitsToFloat(Integer.parseUnsignedInt((String) hex, 16));
    }

    /** Just enough JSON for the recorder's lines and this tool's output. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> object(String text) {
            return (Map<String, Object>) new Json(text).value();
        }

        private Object value() {
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                while (s.charAt(i) != '}') {
                    String key = (String) value();
                    i++; // ':'
                    m.put(key, value());
                    if (s.charAt(i) == ',') {
                        i++;
                    }
                }
                i++;
                return m;
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                while (s.charAt(i) != ']') {
                    l.add(value());
                    if (s.charAt(i) == ',') {
                        i++;
                    }
                }
                i++;
                return l;
            }
            if (c == '"') {
                int end = s.indexOf('"', i + 1);
                String v = s.substring(i + 1, end);
                i = end + 1;
                return v;
            }
            int start = i;
            while (i < s.length() && ",}]".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String token = s.substring(start, i);
            return switch (token) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> Long.parseLong(token);
            };
        }

        static String write(Object o) {
            if (o instanceof Map<?, ?> m) {
                StringBuilder sb = new StringBuilder("{");
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    sb.append(sb.length() > 1 ? "," : "").append(write(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
                }
                return sb.append('}').toString();
            }
            if (o instanceof List<?> l) {
                StringBuilder sb = new StringBuilder("[");
                for (Object v : l) {
                    sb.append(sb.length() > 1 ? "," : "").append(write(v));
                }
                return sb.append(']').toString();
            }
            if (o instanceof String str) {
                return '"' + str.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            }
            return String.valueOf(o);
        }
    }
}
