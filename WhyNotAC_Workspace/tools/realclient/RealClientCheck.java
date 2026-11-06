import dev.whynotac.analysis.movement.RotationReplies;
import dev.whynotac.math.vanilla.MouseTurnModel;
import dev.whynotac.protocol.packet.ClientboundPacket;
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
 *       frames of integer cursor counts (at most {@link #MAX_COUNTS} per frame, what the driver can
 *       produce) through MouseTurnModel's exact float arithmetic. The same data must also REJECT wrong
 *       models (negative controls: wrong multipliers, yaw off by one ulp); a session that cannot tell
 *       them apart proves nothing and fails.
 *   <li>Wire order: a vanilla client answers pings at frame start, before that frame's ticks, so in the
 *       play phase no Pong may appear between a tick's first packet and its ClientTickEnd (a teleport
 *       reply is frame-start work too: AcceptTeleportation + MovePlayer from handleMovePlayer, and so is the reply to a
 *       rotation the server set, Rot from handleRotatePlayer, found as the movement check finds it: RotationReplies).
 *       Both carry the rotation the server set: the mouse's steps start again from it.
 * </ul>
 *
 * usage: java -cp WhyNotAC.jar RealClientCheck.java <recording.wnrec> [sensitivity]
 */
public class RealClientCheck {

    /** Cursor counts per frame: drive.py moves at most 7 per step, two steps may land in one frame. */
    static final int MAX_COUNTS = 16;
    static final int MAX_FRAMES = 3;
    /** The tick clock is measured after the join (world loading drops ticks: at most 10 per frame). */
    static final long STEADY_AFTER_NANOS = 3_000_000_000L;

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
        long steadyTicks = 0;
        long steadyFirst = Long.MIN_VALUE;
        java.util.Set<Integer> teleports = new java.util.HashSet<>();
        RotationReplies rotationReplies = new RotationReplies();
        List<Long> replies = new ArrayList<>();
        boolean riding = false;
        boolean afterRide = false;
        RecordingReader reader = RecordingReader.open(Path.of(args[0]));
        {
            for (TimelineEvent event : reader) {
                if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.SetPassengers set) {
                    riding = set.passengerIds().length > 0; // every boat of the session carries the player
                }
                if (event instanceof TimelineEvent.Outbound out) {
                    rotationReplies.onOutbound(out.sequence(), out.packet());
                }
                if (event instanceof TimelineEvent.Acknowledged ack) {
                    rotationReplies.onAcknowledged(ack.transactionId());
                }
                if (!(event instanceof TimelineEvent.Inbound in)) {
                    continue;
                }
                boolean rotationReply = false;
                if (in.packet() instanceof ServerboundPacket.ClientTickEnd) {
                    rotationReplies.atTickEnd(); // a reply that may be the tick's own stays out of it, as for the movement check
                } else {
                    rotationReply = rotationReplies.onInbound(in.packet());
                }
                if (rotationReply) {
                    replies.add(in.sequence());
                }
                boolean teleportReply = (afterTeleportAck || rotationReply) && in.packet() instanceof ServerboundPacket.MovePlayer;
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
                        if (in.nanoTime() - firstNanos >= STEADY_AFTER_NANOS) {
                            if (steadyFirst == Long.MIN_VALUE) {
                                steadyFirst = in.nanoTime();
                            }
                            steadyTicks++;
                        }
                    }
                    case ServerboundPacket.Pong ignored -> {
                        pongs++;
                        if (inTick) {
                            pongsInsideTicks++;
                        }
                    }
                    case ServerboundPacket.MovePlayer move -> {
                        inTick |= inPlay && !teleportReply; // a teleport reply is frame-start work, not a tick
                        // A passenger's rotation is its boat's heading, not a mouse step; the first one after it is a new start.
                        if (move.hasRotation() && riding) {
                            afterRide = true;
                        } else if (move.hasRotation()) {
                            // A teleport reply carries the rotation the server set: the mouse's steps start again from it.
                            if (teleportReply || afterRide) {
                                teleports.add(rotations.size());
                            }
                            afterRide = false;
                            rotations.add(move);
                        }
                    }
                    case ServerboundPacket.PlayerInput ignored -> inTick |= inPlay;
                    default -> { }
                }
            }
        }
        Lattice real = lattice(rotations, teleports, multiplier, 0);
        Map<String, Lattice> controls = new LinkedHashMap<>();
        controls.put("multiplier/2", lattice(rotations, teleports, multiplier / 2, 0));
        controls.put("multiplier/3", lattice(rotations, teleports, multiplier / 3, 0));
        controls.put("multiplier*2", lattice(rotations, teleports, multiplier * 2, 0));
        controls.put("multiplier=1", lattice(rotations, teleports, 1.0, 0));
        controls.put("yaw+1ulp", lattice(rotations, teleports, multiplier, 1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client_ticks", ticks);
        out.put("ticks_per_second", ticks > 1 ? (ticks - 1) / ((lastNanos - firstNanos) / 1e9) : 0.0);
        out.put("steady_ticks_per_second", steadyTicks > 1 ? (steadyTicks - 1) / ((lastNanos - steadyFirst) / 1e9) : 0.0);
        out.put("pongs", pongs);
        out.put("pongs_inside_a_tick", pongsInsideTicks);
        out.put("rotation_packets", rotations.size());
        out.put("rotation_replies", replies);
        out.put("sensitivity", sensitivity);
        out.put("multiplier", multiplier);
        out.put("yaw_steps", real.yawSteps);
        out.put("yaw_one_frame", real.yawOne);
        out.put("yaw_two_or_three_frames", real.yawMulti);
        out.put("yaw_unexplained", real.yawUnexplained);
        out.put("yaw_unexplained_examples", real.unexplained);
        out.put("single_frame_counts", real.countHistogram);
        out.put("pitch_steps", real.pitchSteps);
        out.put("pitch_explained", real.pitchExplained);
        out.put("yaw_counts", real.yawCounts);
        out.put("pitch_counts", real.pitchCounts);
        Map<String, Object> controlCounts = new LinkedHashMap<>();
        controls.forEach((name, c) -> controlCounts.put(name, Map.of("yaw_counts", c.yawCounts, "pitch_counts", c.pitchCounts)));
        out.put("negative_controls", controlCounts);
        System.out.println(toJson(out));
    }

    static final class Lattice {
        long yawSteps, yawOne, yawMulti, yawUnexplained, pitchSteps, pitchExplained;
        /**
         * Per yaw step in order: the unique one-frame count; for a step only 2-3 frames explain (the mouse
         * moved in more than one frame of the tick), the total count if every such explanation has the same
         * total; else null.
         */
        final List<Long> yawCounts = new ArrayList<>();
        /** Per pitch step in order: the one-frame count, or null. */
        final List<Long> pitchCounts = new ArrayList<>();
        final Map<Long, Long> countHistogram = new java.util.TreeMap<>();
        final List<String> unexplained = new ArrayList<>();
    }

    /** Explains every rotation step with the model; {@code yawUlpShift} perturbs the observed yaws (a control). */
    static Lattice lattice(List<ServerboundPacket.MovePlayer> rotations, java.util.Set<Integer> teleports, double multiplier, int yawUlpShift) {
        Lattice l = new Lattice();
        for (int k = 1; k < rotations.size(); k++) {
            if (teleports.contains(k)) {
                continue; // the server's rotation, not a mouse step
            }
            float from = shift(rotations.get(k - 1).yaw(), yawUlpShift);
            float to = shift(rotations.get(k).yaw(), yawUlpShift * 2);
            if (from != to) {
                l.yawSteps++;
                MouseTurnModel.CountRange range = MouseTurnModel.yawCountsFor(from, to, multiplier);
                Long count = !range.isEmpty() && range.isUnique() ? range.min() : null;
                if (!range.isEmpty() && Math.abs(range.min()) <= MAX_COUNTS) {
                    l.yawOne++;
                    if (range.isUnique()) {
                        l.countHistogram.merge(range.min(), 1L, Long::sum);
                    }
                } else if (explainedByFrames(from, to, multiplier, MAX_FRAMES)) {
                    l.yawMulti++;
                    count = framesTotal(from, to, multiplier, MAX_FRAMES);
                } else {
                    l.yawUnexplained++;
                    if (l.unexplained.size() < 10) {
                        l.unexplained.add(from + " -> " + to);
                    }
                }
                l.yawCounts.add(count);
            }
            float pFrom = rotations.get(k - 1).pitch();
            float pTo = rotations.get(k).pitch();
            if (pFrom != pTo) {
                l.pitchSteps++;
                l.pitchCounts.add(pitchCount(pFrom, pTo, multiplier));
                if (pitchExplained(pFrom, pTo, multiplier, MAX_FRAMES)) {
                    l.pitchExplained++;
                }
            }
        }
        return l;
    }

    /** The control shifts consecutive yaws by different ulp counts, so the shift does not cancel out. */
    private static float shift(float yaw, int ulps) {
        float y = yaw;
        for (int i = 0; i < ulps; i++) {
            y = Math.nextUp(y);
        }
        return y;
    }

    /** True if 2..frames frames of integer counts in [-MAX_COUNTS, MAX_COUNTS] turn {@code from} into {@code to} exactly. */
    static boolean explainedByFrames(float from, float to, double multiplier, int frames) {
        if (frames <= 1) {
            MouseTurnModel.CountRange range = MouseTurnModel.yawCountsFor(from, to, multiplier);
            return !range.isEmpty() && Math.abs(range.min()) <= MAX_COUNTS;
        }
        for (long c = -MAX_COUNTS; c <= MAX_COUNTS; c++) {
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

    /**
     * The total count of every way 2..frames frames of integer counts in [-MAX_COUNTS, MAX_COUNTS] turn
     * {@code from} into {@code to} exactly, if they all have the same total; null otherwise.
     */
    static Long framesTotal(float from, float to, double multiplier, int frames) {
        java.util.Set<Long> totals = new java.util.TreeSet<>();
        for (long c = -MAX_COUNTS; c <= MAX_COUNTS; c++) {
            if (c != 0) {
                totals(MouseTurnModel.turnYaw(from, c * multiplier), to, multiplier, frames - 1, c, totals);
            }
        }
        return totals.size() == 1 ? totals.iterator().next() : null;
    }

    /** Adds {@code sofar} plus the counts of 1..frames further frames that end at {@code to}. */
    private static void totals(float from, float to, double multiplier, int frames, long sofar, java.util.Set<Long> totals) {
        MouseTurnModel.CountRange last = MouseTurnModel.yawCountsFor(from, to, multiplier);
        for (long c = Math.max(last.min(), -MAX_COUNTS); c <= Math.min(last.max(), MAX_COUNTS); c++) {
            if (c != 0) {
                totals.add(sofar + c);
            }
        }
        if (frames > 1) {
            for (long c = -MAX_COUNTS; c <= MAX_COUNTS; c++) {
                if (c != 0) {
                    totals(MouseTurnModel.turnYaw(from, c * multiplier), to, multiplier, frames - 1, sofar + c, totals);
                }
            }
        }
    }

    /** The unique one-frame count turning pitch {@code from} into {@code to}, or null. */
    static Long pitchCount(float from, float to, double multiplier) {
        Long found = null;
        for (long c = -4 * MAX_COUNTS; c <= 4 * MAX_COUNTS; c++) {
            if (c != 0 && MouseTurnModel.turnPitch(from, c * multiplier) == to) {
                if (found != null) {
                    return null;
                }
                found = c;
            }
        }
        return found;
    }

    static boolean pitchExplained(float from, float to, double multiplier, int frames) {
        if (frames == 0) {
            return from == to;
        }
        for (long c = -MAX_COUNTS; c <= MAX_COUNTS; c++) {
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
