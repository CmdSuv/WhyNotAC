package dev.whynotac.ai;

import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts per-session behavioral moments using {@link RunningMoments} (Welford/Pebay shifted online statistics)
 * for non-punitive baseline modeling and case prioritization (AI-01).
 */
public final class BehavioralFeatureExtractor implements TimelineListener {

    public static final String CLICK_INTERVAL_TICKS = "click_interval_ticks";
    public static final String AIM_ANGULAR_STEP_DEG = "aim_angular_step_deg";
    public static final String TICK_DELTA_MS = "tick_delta_ms";
    public static final String HORIZONTAL_STEP_BLOCKS = "horizontal_step_blocks";

    public record Snapshot(Map<String, RunningMoments> features) {
        public Snapshot {
            Map<String, RunningMoments> copy = new LinkedHashMap<>();
            features.forEach((k, v) -> copy.put(k, v.copy()));
            features = Collections.unmodifiableMap(copy);
        }
    }

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private static final class SessionStats {
        final RunningMoments clickIntervals = new RunningMoments();
        final RunningMoments aimSteps = new RunningMoments();
        final RunningMoments tickDeltasMs = new RunningMoments();
        final RunningMoments horizontalSteps = new RunningMoments();

        long tick;
        long lastAttackTick = Long.MIN_VALUE;
        long lastTickNano = Long.MIN_VALUE;
        boolean hasRot;
        float prevYaw;
        float prevPitch;
        boolean hasPos;
        double prevX;
        double prevZ;
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        if (!(event instanceof TimelineEvent.Inbound in)) {
            return;
        }
        SessionStats s = session.attachment(STATE, SessionStats::new);
        synchronized (s) {
            switch (in.packet()) {
                case ServerboundPacket.InteractEntity interact when interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK -> {
                    if (s.lastAttackTick != Long.MIN_VALUE) {
                        long dt = s.tick - s.lastAttackTick;
                        if (dt > 0 && dt <= 40) {
                            s.clickIntervals.add((double) dt);
                        }
                    }
                    s.lastAttackTick = s.tick;
                }
                case ServerboundPacket.MovePlayer move -> {
                    if (move.hasRotation() && !move.hasNonFiniteValue()) {
                        if (s.hasRot) {
                            double dy = Math.abs(wrapDeg(move.yaw() - s.prevYaw));
                            double dp = Math.abs(move.pitch() - s.prevPitch);
                            double step = Math.hypot(dy, dp);
                            if (step > 0.0 && step < 180.0) {
                                s.aimSteps.add(step);
                            }
                        }
                        s.hasRot = true;
                        s.prevYaw = move.yaw();
                        s.prevPitch = move.pitch();
                    }
                    if (move.hasPosition() && !move.hasNonFiniteValue()) {
                        if (s.hasPos) {
                            double d = Math.hypot(move.x() - s.prevX, move.z() - s.prevZ);
                            if (d < 10.0) {
                                s.horizontalSteps.add(d);
                            }
                        }
                        s.hasPos = true;
                        s.prevX = move.x();
                        s.prevZ = move.z();
                    }
                }
                case ServerboundPacket.ClientTickEnd ignored -> {
                    s.tick++;
                    if (s.lastTickNano != Long.MIN_VALUE) {
                        double ms = (in.nanoTime() - s.lastTickNano) / 1_000_000.0;
                        if (ms > 0.0 && ms < 500.0) {
                            s.tickDeltasMs.add(ms);
                        }
                    }
                    s.lastTickNano = in.nanoTime();
                }
                default -> { }
            }
        }
    }

    /** Captures a consistent snapshot copy of the session's running moments. */
    public Snapshot snapshot(PlayerSession session) {
        SessionStats s = session.attachment(STATE, SessionStats::new);
        synchronized (s) {
            Map<String, RunningMoments> out = new LinkedHashMap<>();
            out.put(CLICK_INTERVAL_TICKS, s.clickIntervals);
            out.put(AIM_ANGULAR_STEP_DEG, s.aimSteps);
            out.put(TICK_DELTA_MS, s.tickDeltasMs);
            out.put(HORIZONTAL_STEP_BLOCKS, s.horizontalSteps);
            return new Snapshot(out);
        }
    }

    private static double wrapDeg(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
