package dev.whynotac.ai;

import dev.whynotac.math.stats.RunningCovarianceMatrix;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts per-session behavioral moments using {@link RunningMoments} (Welford/Pebay shifted online statistics)
 * for non-punitive baseline modeling, cross-feature anomaly scoring, and case prioritization (AI-01).
 *
 * <p>Captures both global session kinematics and combat-conditioned / cross-feature moments so peaceful building
 * and walking do not dilute PvP combat tracking statistics.
 */
public final class BehavioralFeatureExtractor implements TimelineListener {

    public static final String CLICK_INTERVAL_TICKS = "click_interval_ticks";
    public static final String AIM_ANGULAR_STEP_DEG = "aim_angular_step_deg";
    public static final String TICK_DELTA_MS = "tick_delta_ms";
    public static final String HORIZONTAL_STEP_BLOCKS = "horizontal_step_blocks";
    public static final String COMBAT_AIM_STEP_DEG = "combat_aim_step_deg";
    public static final String COMBAT_AIM_JERK_DEG = "combat_aim_jerk_deg";
    public static final String ATTACK_SNAP_RATIO = "attack_snap_ratio";
    public static final String CONTAINER_CLICK_INTERVAL_TICKS = "container_click_interval_ticks";

    private static final long COMBAT_WINDOW_TICKS = 40L;
    private static final int RECENT_WINDOW = 30;
    public static final int COMBAT_COVARIANCE_DIM = 4;

    public record Snapshot(
            Map<String, RunningMoments> features,
            double intraSessionToggleZ,
            RunningCovarianceMatrix combatCovariance) {

        public Snapshot(Map<String, RunningMoments> features) {
            this(features, 0.0, new RunningCovarianceMatrix(COMBAT_COVARIANCE_DIM));
        }

        public Snapshot(Map<String, RunningMoments> features, double intraSessionToggleZ) {
            this(features, intraSessionToggleZ, new RunningCovarianceMatrix(COMBAT_COVARIANCE_DIM));
        }

        public Snapshot {
            Map<String, RunningMoments> copy = new LinkedHashMap<>();
            features.forEach((k, v) -> copy.put(k, v.copy()));
            features = Collections.unmodifiableMap(copy);
            combatCovariance = combatCovariance == null
                    ? new RunningCovarianceMatrix(COMBAT_COVARIANCE_DIM)
                    : combatCovariance.copy();
        }
    }

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private static final class SessionStats {
        final RunningMoments clickIntervals = new RunningMoments();
        final RunningMoments aimSteps = new RunningMoments();
        final RunningMoments tickDeltasMs = new RunningMoments();
        final RunningMoments horizontalSteps = new RunningMoments();
        final RunningMoments combatAimSteps = new RunningMoments();
        final RunningMoments combatAimJerks = new RunningMoments();
        final RunningMoments attackSnapRatios = new RunningMoments();
        final RunningMoments containerClickIntervals = new RunningMoments();
        final RunningCovarianceMatrix combatCovariance = new RunningCovarianceMatrix(COMBAT_COVARIANCE_DIM);
        double lastClickDt = Double.NaN;
        double lastCombatStep = Double.NaN;
        double lastCombatJerk = Double.NaN;

        // Disjoint early-session vs recent sliding window (30 samples) for mid-session toggle detection
        final RunningMoments earlyClickIntervals = new RunningMoments();
        final double[] recentClicks = new double[RECENT_WINDOW];
        int recentClickCount;
        int recentClickHead;

        final RunningMoments earlyCombatAimSteps = new RunningMoments();
        final double[] recentCombatAim = new double[RECENT_WINDOW];
        int recentCombatAimCount;
        int recentCombatAimHead;

        void recordClickInterval(double dt) {
            clickIntervals.add(dt);
            lastClickDt = dt;
            if (recentClickCount == RECENT_WINDOW) {
                earlyClickIntervals.add(recentClicks[recentClickHead]);
                recentClicks[recentClickHead] = dt;
                recentClickHead = (recentClickHead + 1) % RECENT_WINDOW;
            } else {
                recentClicks[recentClickCount++] = dt;
            }
        }

        void recordCombatAimStep(double step) {
            combatAimSteps.add(step);
            if (recentCombatAimCount == RECENT_WINDOW) {
                earlyCombatAimSteps.add(recentCombatAim[recentCombatAimHead]);
                recentCombatAim[recentCombatAimHead] = step;
                recentCombatAimHead = (recentCombatAimHead + 1) % RECENT_WINDOW;
            } else {
                recentCombatAim[recentCombatAimCount++] = step;
            }
        }

        double computeIntraSessionToggleZ() {
            double maxZ = 0.0;
            if (earlyClickIntervals.count() >= 20 && recentClickCount == RECENT_WINDOW) {
                RunningMoments rec = new RunningMoments();
                for (double v : recentClicks) {
                    rec.add(v);
                }
                double earlyMean = earlyClickIntervals.mean();
                double earlySdRaw = earlyClickIntervals.sampleStdDev();
                double recSdRaw = rec.sampleStdDev();
                double earlyStd = Double.isFinite(earlySdRaw) ? Math.max(0.35, earlySdRaw) : 0.35;
                double recStd = Double.isFinite(recSdRaw) ? recSdRaw : 0.0;
                double meanDropZ = Math.max(0.0, (earlyMean - rec.mean()) / (earlyStd / Math.sqrt(RECENT_WINDOW)));
                double spreadCollapseZ = Double.isFinite(earlySdRaw) && earlySdRaw >= 0.40 && recStd < earlySdRaw * 0.25
                        ? (earlySdRaw - recStd) / 0.15
                        : 0.0;
                maxZ = Math.max(maxZ, Math.min(25.0, Math.max(meanDropZ, spreadCollapseZ)));
            }
            if (earlyCombatAimSteps.count() >= 20 && recentCombatAimCount == RECENT_WINDOW) {
                RunningMoments rec = new RunningMoments();
                for (double v : recentCombatAim) {
                    rec.add(v);
                }
                double earlyMean = earlyCombatAimSteps.mean();
                double earlySdRaw = earlyCombatAimSteps.sampleStdDev();
                double earlyStd = Double.isFinite(earlySdRaw) ? Math.max(0.50, earlySdRaw) : 0.50;
                double stepJumpZ = Math.max(0.0, (rec.mean() - earlyMean) / (earlyStd / Math.sqrt(RECENT_WINDOW)));
                maxZ = Math.max(maxZ, Math.min(25.0, stepJumpZ));
            }
            return maxZ;
        }

        long tick;
        long lastAttackTick = Long.MIN_VALUE;
        long lastTickNano = Long.MIN_VALUE;
        boolean attackedThisTick;
        boolean hasRot;
        float prevYaw;
        float prevPitch;
        double prevCombatStep = Double.NaN;
        double lastNonAttackAimStep = 1.0;
        double currentTickAimStep = Double.NaN;
        boolean hasPos;
        double prevX;
        double prevZ;
        int lastContainerId = -1;
        int lastContainerSlot = Integer.MIN_VALUE;
        long lastContainerClickTick = Long.MIN_VALUE;
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        SessionStats s = session.attachment(STATE, SessionStats::new);
        synchronized (s) {
            if (event instanceof TimelineEvent.Outbound out) {
                if (out.packet() instanceof ClientboundPacket.OpenScreen
                        || out.packet() instanceof ClientboundPacket.ContainerClose
                        || out.packet() instanceof ClientboundPacket.Respawn
                        || out.packet() instanceof ClientboundPacket.Login) {
                    s.lastContainerId = -1;
                    s.lastContainerSlot = Integer.MIN_VALUE;
                    s.lastContainerClickTick = Long.MIN_VALUE;
                }
                return;
            }
            if (!(event instanceof TimelineEvent.Inbound in)) {
                return;
            }
            switch (in.packet()) {
                case ServerboundPacket.InteractEntity interact when interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK -> {
                    if (s.lastAttackTick != Long.MIN_VALUE) {
                        long dt = s.tick - s.lastAttackTick;
                        if (dt > 0 && dt <= 40) {
                            s.recordClickInterval((double) dt);
                        }
                    }
                    s.lastAttackTick = s.tick;
                    s.attackedThisTick = true;
                }
                case ServerboundPacket.ContainerClick click -> {
                    if (click.containerId() > 0 && click.slot() >= 0) {
                        if (click.containerId() != s.lastContainerId) {
                            s.lastContainerId = click.containerId();
                            s.lastContainerSlot = Integer.MIN_VALUE;
                            s.lastContainerClickTick = Long.MIN_VALUE;
                        }
                        if (click.slot() != s.lastContainerSlot) {
                            if (s.lastContainerClickTick != Long.MIN_VALUE) {
                                long dt = s.tick - s.lastContainerClickTick;
                                if (dt >= 0 && dt <= 40) {
                                    s.containerClickIntervals.add((double) dt);
                                }
                            }
                            s.lastContainerSlot = click.slot();
                            s.lastContainerClickTick = s.tick;
                        }
                    }
                }
                case ServerboundPacket.MovePlayer move -> {
                    if (move.hasRotation() && !move.hasNonFiniteValue()) {
                        if (s.hasRot) {
                            double dy = Math.abs(wrapDeg(move.yaw() - s.prevYaw));
                            double dp = Math.abs(move.pitch() - s.prevPitch);
                            double step = Math.hypot(dy, dp);
                            if (step > 0.0 && step < 180.0) {
                                s.aimSteps.add(step);
                                s.currentTickAimStep = step;
                                boolean inCombat = s.attackedThisTick
                                        || (s.lastAttackTick != Long.MIN_VALUE && (s.tick - s.lastAttackTick) <= COMBAT_WINDOW_TICKS);
                                if (inCombat) {
                                    s.recordCombatAimStep(step);
                                    s.lastCombatStep = step;
                                    if (!Double.isNaN(s.prevCombatStep)) {
                                        double jerk = Math.abs(step - s.prevCombatStep);
                                        s.combatAimJerks.add(jerk);
                                        s.lastCombatJerk = jerk;
                                    }
                                    s.prevCombatStep = step;
                                } else {
                                    s.prevCombatStep = Double.NaN;
                                }
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
                    if (!Double.isNaN(s.currentTickAimStep)) {
                        if (s.attackedThisTick) {
                            double denom = Math.max(0.25, s.lastNonAttackAimStep);
                            double ratio = Math.min(50.0, s.currentTickAimStep / denom);
                            s.attackSnapRatios.add(ratio);
                            if (Double.isFinite(s.lastClickDt)
                                    && Double.isFinite(s.lastCombatStep)
                                    && Double.isFinite(s.lastCombatJerk)) {
                                s.combatCovariance.add(s.lastClickDt, s.lastCombatStep, s.lastCombatJerk, ratio);
                            }
                        } else if (s.currentTickAimStep >= 0.05) {
                            s.lastNonAttackAimStep = 0.75 * s.lastNonAttackAimStep + 0.25 * s.currentTickAimStep;
                        }
                    }
                    s.attackedThisTick = false;
                    s.currentTickAimStep = Double.NaN;
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
            out.put(COMBAT_AIM_STEP_DEG, s.combatAimSteps);
            out.put(COMBAT_AIM_JERK_DEG, s.combatAimJerks);
            out.put(ATTACK_SNAP_RATIO, s.attackSnapRatios);
            out.put(CONTAINER_CLICK_INTERVAL_TICKS, s.containerClickIntervals);
            return new Snapshot(out, s.computeIntraSessionToggleZ(), s.combatCovariance);
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
