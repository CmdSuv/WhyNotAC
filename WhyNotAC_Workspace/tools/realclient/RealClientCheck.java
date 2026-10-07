import dev.whynotac.math.vanilla.MouseTurnModel;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.read.RecordingReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a recording of the real vanilla client against WhyNotAC's vanilla models. Prints JSON.
 *
 * <ul>
 *   <li>Rotation lattice: every yaw/pitch change between rotation packets must be produced by 1-3
 *       frames of integer cursor counts through MouseTurnModel's exact float arithmetic.
 *   <li>Wire order: a vanilla client answers pings at frame start, before that frame's ticks, so in the
 *       play phase no Pong may appear between a tick's first packet and its ClientTickEnd (a teleport
 *       reply is frame-start work too: AcceptTeleportation + MovePlayer from handleMovePlayer).
 * </ul>
 *
 * usage: java -cp <math,protocol,recording jars> RealClientCheck.java <recording.wnrec> [sensitivity]
 */
public class RealClientCheck {

    public static void main(String[] args) throws Exception {
        double sensitivity = args.length > 1 ? Double.parseDouble(args[1]) : 0.5;
        double multiplier = MouseTurnModel.multiplier(sensitivity, false);
        List<ServerboundPacket.MovePlayer> rotations = new ArrayList<>();
        long ticks = 0;
        long pongsInsideTicks = 0;
        long pongs = 0;
        boolean inTick = false;
        boolean inPlay = false;        // the rule applies from the first client tick on (not during login/configuration)
        boolean afterTeleportAck = false; // handleMovePlayer answers a teleport with AcceptTeleportation + MovePlayer at frame start
        long firstNanos = Long.MIN_VALUE;
        long lastNanos = 0;
        RecordingReader reader = RecordingReader.open(Path.of(args[0]));
        {
            for (TimelineEvent event : reader) {
                if (!(event instanceof TimelineEvent.Inbound in)) {
                    continue;
                }
                boolean teleportReply = afterTeleportAck && in.packet() instanceof ServerboundPacket.MovePlayer;
                afterTeleportAck = in.packet() instanceof ServerboundPacket.AcceptTeleportation;
                switch (in.packet()) {
                    case ServerboundPacket.ClientTickEnd ignored -> {
                        ticks++;
                        inTick = false;
                        inPlay = true;
                        if (firstNanos == Long.MIN_VALUE) {
                            firstNanos = in.nanoTime();
                        }
                        lastNanos = in.nanoTime();
                    }
                    case ServerboundPacket.Pong ignored -> {
                        pongs++;
                        if (inTick) {
                            pongsInsideTicks++;
                        }
                    }
                    case ServerboundPacket.MovePlayer move -> {
                        inTick |= inPlay && !teleportReply; // a teleport reply is frame-start work, not a tick
                        if (move.hasRotation() && !teleportReply) {
                            rotations.add(move);
                        }
                    }
                    case ServerboundPacket.PlayerInput ignored -> inTick |= inPlay;
                    default -> { }
                }
            }
        }
        long yawSteps = 0, yawOne = 0, yawMulti = 0, yawUnexplained = 0;
        long pitchSteps = 0, pitchExplained = 0;
        Map<Long, Long> countHistogram = new java.util.TreeMap<>();
        List<String> unexplained = new ArrayList<>();
        for (int k = 1; k < rotations.size(); k++) {
            float from = rotations.get(k - 1).yaw();
            float to = rotations.get(k).yaw();
            if (from != to) {
                yawSteps++;
                MouseTurnModel.CountRange range = MouseTurnModel.yawCountsFor(from, to, multiplier);
                if (!range.isEmpty()) {
                    yawOne++;
                    if (range.isUnique()) {
                        countHistogram.merge(range.min(), 1L, Long::sum);
                    }
                } else if (explainedByFrames(from, to, multiplier, 3)) {
                    yawMulti++;
                } else {
                    yawUnexplained++;
                    if (unexplained.size() < 10) {
                        unexplained.add(from + " -> " + to);
                    }
                }
            }
            float pFrom = rotations.get(k - 1).pitch();
            float pTo = rotations.get(k).pitch();
            if (pFrom != pTo) {
                pitchSteps++;
                if (pitchExplained(pFrom, pTo, multiplier, 3)) {
                    pitchExplained++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_ticks", ticks);
        out.put("ticks_per_second", ticks > 1 ? (ticks - 1) / ((lastNanos - firstNanos) / 1e9) : 0.0);
        out.put("pongs", pongs);
        out.put("pongs_inside_a_tick", pongsInsideTicks);
        out.put("rotation_packets", rotations.size());
        out.put("yaw_steps", yawSteps);
        out.put("yaw_one_frame", yawOne);
        out.put("yaw_two_or_three_frames", yawMulti);
        out.put("yaw_unexplained", yawUnexplained);
        out.put("yaw_unexplained_examples", unexplained);
        out.put("single_frame_counts", countHistogram);
        out.put("pitch_steps", pitchSteps);
        out.put("pitch_explained", pitchExplained);
        System.out.println(toJson(out));
    }

    /** True if 2..frames frames of integer counts in [-64, 64] turn {@code from} into {@code to} exactly. */
    static boolean explainedByFrames(float from, float to, double multiplier, int frames) {
        if (frames <= 1) {
            return !MouseTurnModel.yawCountsFor(from, to, multiplier).isEmpty();
        }
        for (long c = -64; c <= 64; c++) {
            if (c == 0) {
                continue;
            }
            float mid = MouseTurnModel.turnYaw(from, c * multiplier);
            if (!MouseTurnModel.yawCountsFor(mid, to, multiplier).isEmpty() || explainedByFrames(mid, to, multiplier, frames - 1)) {
                return true;
            }
        }
        return false;
    }

    static boolean pitchExplained(float from, float to, double multiplier, int frames) {
        if (frames == 0) {
            return from == to;
        }
        for (long c = -64; c <= 64; c++) {
            float next = MouseTurnModel.turnPitch(from, c * multiplier);
            if (next == to || (c != 0 && pitchExplained(next, to, multiplier, frames - 1))) {
                return true;
            }
        }
        return false;
    }

    static String toJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            map.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(toJson(v)));
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            list.forEach(v -> sb.append(sb.length() > 1 ? "," : "").append(toJson(v)));
            return sb.append(']').toString();
        }
        if (value instanceof String s) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return String.valueOf(value);
    }
}
