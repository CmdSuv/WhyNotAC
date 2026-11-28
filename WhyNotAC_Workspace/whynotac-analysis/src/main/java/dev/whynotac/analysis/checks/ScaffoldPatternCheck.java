package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.Replicas;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scaffold / automated bridging pattern ({@code scaffold-pattern}, S, SHADOW): statistical signals of automated
 * block-bridging modules (TellyBridge, GodBridge, snap-Scaffold) that satisfy single-tick raycast rules by snapping
 * into a block face and snapping back, or by placing across distinct horizontal blocks with robotic tick regularity.
 *
 * <ul>
 *   <li>{@code place-and-snapback}: &ge; {@link #MIN_SNAPBACKS} horizontal/underside {@code UseItemOn} placements
 *       within {@link #SNAPBACK_WINDOW_TICKS} ticks where the client snaps by &ge; {@link #MIN_SNAP_DEGREES} on the
 *       placement tick and returns within &le; {@link #MAX_RETURN_ERROR_DEGREES} of its pre-placement look within
 *       1–2 ticks;</li>
 *   <li>{@code constant-bridge-cadence}: &ge; {@link #CADENCE_WINDOW} consecutive distinct-block horizontal placements
 *       (1..{@link #MAX_CADENCE_GAP_TICKS} ticks, mean &le; {@link #MAX_MEAN_CADENCE_TICKS}) with near-zero tick
 *       variance ({@link RunningMoments#sampleStdDev()} &le; {@link #MAX_CADENCE_STD_DEV}).</li>
 * </ul>
 *
 * <p>Class S (nominal statistic): never triggers automated bans, kicks, or mitigations ({@code docs/POLICY.md}).
 */
public final class ScaffoldPatternCheck implements TimelineListener {

    public static final String ID = "scaffold-pattern";
    public static final int VERSION = 2;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    static final double MIN_SNAP_DEGREES = 22.0;
    static final double MAX_RETURN_ERROR_DEGREES = 6.0;
    static final int SNAPBACK_WINDOW_TICKS = 120;
    static final int MIN_SNAPBACKS = 3;

    static final int CADENCE_WINDOW = 12;
    static final int MIN_CADENCE_GAP_TICKS = 1;
    static final int MAX_CADENCE_GAP_TICKS = 6;
    static final double MAX_MEAN_CADENCE_TICKS = 4.5;
    static final double MAX_CADENCE_STD_DEV = 0.10;

    static final int EAGLE_WINDOW = 14;
    static final double MAX_EAGLE_LEAD_SD = 0.08;
    static final double MAX_EAGLE_DURATION_SD = 0.08;
    static final double MAX_EAGLE_MEAN_LEAD = 2.5;
    static final double MAX_EAGLE_MEAN_DURATION = 4.0;

    static final int PITCH_LOCK_WINDOW = 15;
    static final int PITCH_LOCK_MAX_GAP_TICKS = 12;
    static final int PITCH_LOCK_MIN_YAW_MOVES = 10;
    static final double PITCH_LOCK_MIN_TOTAL_YAW = 15.0;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public ScaffoldPatternCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private record SnapCandidate(long tick, float preYaw, float prePitch, long seq, long nano) {}
    private record PlaceSample(long tick, long seq, long nano) {}
    private record EagleCandidate(long placeTick, long sneakStartTick, int leadTicks, long seq, long nano) {}
    private record EagleSample(int leadTicks, int durationTicks, long seq, long nano) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final TickLook look;
        long tickNumber;
        long lastReportNanos = Long.MIN_VALUE;

        boolean hasPrevRot;
        float prevYaw;
        float prevPitch;
        long prevSeq;
        long prevNano;

        boolean placedBridgeThisTick;
        boolean distinctBridgePlacedThisTick;
        long lastBridgeSeq;
        long lastBridgeNano;
        SnapCandidate pendingSnap;
        final ArrayDeque<long[]> snapbacks = new ArrayDeque<>();

        BlockPosition lastBridgePos;
        long lastBridgeTick = -1L;
        final ArrayDeque<Integer> intervals = new ArrayDeque<>();
        final ArrayDeque<PlaceSample> intervalSamples = new ArrayDeque<>();

        // v2: Eagle / SafeWalk sneak-lead & sneak-duration timing
        boolean currentShift;
        long lastSneakStartTick = -1L;
        EagleCandidate pendingEagle;
        final ArrayDeque<EagleSample> eagleSamples = new ArrayDeque<>();

        // v2: Zero-dPitch bridge pitch-lock across active yaw turns
        boolean hasPitchLockRef;
        float pitchLockRefPitch;
        float pitchLockLastYaw;
        long pitchLockLastTick = -1L;
        long pitchLockStartSeq;
        long pitchLockStartNano;
        int pitchLockCount;
        int pitchLockYawMoves;
        double pitchLockTotalYaw;

        State(PingLedger ledger) {
            this.look = new TickLook(ledger);
        }

        static State fromSession(PlayerSession session) {
            return new State(session.pingLedger());
        }

        void resetPitchLock() {
            hasPitchLockRef = false;
            pitchLockLastTick = -1L;
            pitchLockCount = 0;
            pitchLockYawMoves = 0;
            pitchLockTotalYaw = 0.0;
        }

        void reset() {
            hasPrevRot = false;
            placedBridgeThisTick = false;
            distinctBridgePlacedThisTick = false;
            pendingSnap = null;
            snapbacks.clear();
            lastBridgePos = null;
            lastBridgeTick = -1L;
            intervals.clear();
            intervalSamples.clear();
            currentShift = false;
            lastSneakStartTick = -1L;
            pendingEagle = null;
            eagleSamples.clear();
            resetPitchLock();
        }
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::fromSession);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> state.reset();
            case TimelineEvent.Bound bound -> state.look.onBound(bound.entityId());
            case TimelineEvent.Outbound out -> {
                state.look.onOutbound(out);
                if (out.packet() instanceof ClientboundPacket.Login
                        || out.packet() instanceof ClientboundPacket.Respawn
                        || out.packet() instanceof ClientboundPacket.PlayerPosition) {
                    state.reset();
                }
            }
            case TimelineEvent.Acknowledged ack -> state.look.onAcknowledged(ack);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerLoaded ignored -> {
                state.look.onLoaded();
                state.reset();
            }
            case ServerboundPacket.AcceptTeleportation accept -> {
                state.look.onAccept(accept);
                state.reset();
            }
            case ServerboundPacket.PlayerInput input -> {
                boolean wasShift = state.currentShift;
                state.currentShift = input.shift();
                if (!wasShift && state.currentShift) {
                    state.lastSneakStartTick = state.tickNumber;
                } else if (wasShift && !state.currentShift && state.pendingEagle != null) {
                    EagleCandidate cand = state.pendingEagle;
                    state.pendingEagle = null;
                    long releaseAfterPlace = state.tickNumber - cand.placeTick();
                    long dur = state.tickNumber - cand.sneakStartTick();
                    if (releaseAfterPlace >= 0 && releaseAfterPlace <= 6 && dur >= 1 && dur <= 8) {
                        state.eagleSamples.addLast(new EagleSample(cand.leadTicks(), (int) dur, cand.seq(), cand.nano()));
                        while (state.eagleSamples.size() > EAGLE_WINDOW) {
                            state.eagleSamples.pollFirst();
                        }
                        if (state.eagleSamples.size() == EAGLE_WINDOW) {
                            RunningMoments leadM = new RunningMoments();
                            RunningMoments durM = new RunningMoments();
                            for (EagleSample es : state.eagleSamples) {
                                leadM.add(es.leadTicks());
                                durM.add(es.durationTicks());
                            }
                            if (leadM.mean() <= MAX_EAGLE_MEAN_LEAD && durM.mean() <= MAX_EAGLE_MEAN_DURATION
                                    && leadM.sampleStdDev() <= MAX_EAGLE_LEAD_SD
                                    && durM.sampleStdDev() <= MAX_EAGLE_DURATION_SD) {
                                EagleSample oldest = state.eagleSamples.peekFirst();
                                Map<String, String> details = new TreeMap<>();
                                details.put("reason", "eagle-sneak-macro");
                                details.put("samples", Integer.toString(EAGLE_WINDOW));
                                details.put("meanLeadTicks", String.format(Locale.ROOT, "%.3f", leadM.mean()));
                                details.put("stdDevLeadTicks", String.format(Locale.ROOT, "%.4f", leadM.sampleStdDev()));
                                details.put("meanDurationTicks", String.format(Locale.ROOT, "%.3f", durM.mean()));
                                details.put("stdDevDurationTicks", String.format(Locale.ROOT, "%.4f", durM.sampleStdDev()));
                                if (report(session, state, oldest.seq(), oldest.nano(), in,
                                        String.format(Locale.ROOT, "robotic Eagle/SafeWalk sneak macro (lead %.2f±%.3f, dur %.2f±%.3f over %d blocks)",
                                                leadM.mean(), leadM.sampleStdDev(), durM.mean(), durM.sampleStdDev(), EAGLE_WINDOW),
                                        details)) {
                                    state.eagleSamples.clear();
                                }
                            }
                        }
                    } else {
                        state.eagleSamples.clear();
                    }
                }
            }
            case ServerboundPacket.UseItemOn useOn -> {
                state.look.onAction();
                if (useOn.face() != BlockFace.UP) {
                    state.placedBridgeThisTick = true;
                    onBridgePlacement(session, state, in, useOn);
                }
            }
            case ServerboundPacket.MovePlayer move -> state.look.onMove(in, move);
            case ServerboundPacket.ClientTickEnd ignored -> onTickEnd(session, state, in);
            default -> {
                if (ActionGrammarCheck.action(in.packet()) != null || in.packet() instanceof ServerboundPacket.Swing) {
                    state.look.onAction();
                }
            }
        }
    }

    private void onBridgePlacement(PlayerSession session, State state, TimelineEvent.Inbound in, ServerboundPacket.UseItemOn useOn) {
        ClientSelf self = replicas.self().self(session);
        if (self.mayBeCreativeOrSpectator()) {
            state.reset();
            return;
        }
        BlockPosition pos = useOn.position();
        if (pos.equals(state.lastBridgePos)) {
            return;
        }
        state.lastBridgePos = pos;
        state.distinctBridgePlacedThisTick = true;
        state.lastBridgeSeq = in.sequence();
        state.lastBridgeNano = in.nanoTime();
        if (state.currentShift && state.lastSneakStartTick >= 0) {
            long lead = state.tickNumber - state.lastSneakStartTick;
            if (lead >= 1 && lead <= 4) {
                state.pendingEagle = new EagleCandidate(state.tickNumber, state.lastSneakStartTick, (int) lead, in.sequence(), in.nanoTime());
            } else {
                state.pendingEagle = null;
                state.eagleSamples.clear();
            }
        } else {
            state.pendingEagle = null;
            state.eagleSamples.clear();
        }

        if (state.lastBridgeTick >= 0) {
            long gap = state.tickNumber - state.lastBridgeTick;
            if (gap >= MIN_CADENCE_GAP_TICKS && gap <= MAX_CADENCE_GAP_TICKS) {
                state.intervals.addLast((int) gap);
                state.intervalSamples.addLast(new PlaceSample(state.tickNumber, in.sequence(), in.nanoTime()));
                while (state.intervals.size() > CADENCE_WINDOW) {
                    state.intervals.pollFirst();
                    state.intervalSamples.pollFirst();
                }
                if (state.intervals.size() == CADENCE_WINDOW) {
                    RunningMoments moments = new RunningMoments();
                    for (int dt : state.intervals) {
                        moments.add(dt);
                    }
                    double mean = moments.mean();
                    double sd = moments.sampleStdDev();
                    if (mean <= MAX_MEAN_CADENCE_TICKS && sd <= MAX_CADENCE_STD_DEV) {
                        PlaceSample oldest = state.intervalSamples.peekFirst();
                        Map<String, String> details = new TreeMap<>();
                        details.put("reason", "constant-bridge-cadence");
                        details.put("samples", Integer.toString(CADENCE_WINDOW));
                        details.put("meanTicks", String.format(Locale.ROOT, "%.3f", mean));
                        details.put("stdDevTicks", String.format(Locale.ROOT, "%.4f", sd));
                        if (report(session, state, oldest.seq(), oldest.nano(), in,
                                String.format(Locale.ROOT, "constant horizontal bridge cadence (mean %.2f ticks, sd %.4f over %d blocks)",
                                        mean, sd, CADENCE_WINDOW),
                                details)) {
                            state.intervals.clear();
                            state.intervalSamples.clear();
                        }
                    }
                }
            } else if (gap > MAX_CADENCE_GAP_TICKS) {
                state.intervals.clear();
                state.intervalSamples.clear();
            }
        }
        state.lastBridgeTick = state.tickNumber;
    }

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        long tick = state.tickNumber++;
        ClientSelf self = replicas.self().self(session);
        boolean mayRide = self.mayRide();
        state.look.onTickEnd(mayRide);

        boolean placedThisTick = state.placedBridgeThisTick;
        boolean distinctPlacedThisTick = state.distinctBridgePlacedThisTick;
        state.placedBridgeThisTick = false;
        state.distinctBridgePlacedThisTick = false;

        List<TickLook.Rotation> candidates = state.look.candidates();
        if (!state.look.loaded() || mayRide || self.mayBeCreativeOrSpectator() || candidates == null || candidates.size() != 1) {
            state.hasPrevRot = false;
            state.pendingSnap = null;
            return;
        }
        TickLook.Rotation curr = candidates.get(0);
        if (!Float.isFinite(curr.yRot()) || !Float.isFinite(curr.xRot())) {
            state.hasPrevRot = false;
            state.pendingSnap = null;
            return;
        }
        if (!state.hasPrevRot) {
            state.hasPrevRot = true;
            state.prevYaw = curr.yRot();
            state.prevPitch = curr.xRot();
            state.prevSeq = curr.sequence();
            state.prevNano = curr.nanoTime();
            return;
        }

        float prevYaw = state.prevYaw;
        float prevPitch = state.prevPitch;
        float currYaw = curr.yRot();
        float currPitch = curr.xRot();
        long prevSeq = state.prevSeq;
        long prevNano = state.prevNano;

        state.prevYaw = currYaw;
        state.prevPitch = currPitch;
        state.prevSeq = curr.sequence();
        state.prevNano = curr.nanoTime();

        double dYaw = VanillaMath.wrapDegrees((double) currYaw - prevYaw);
        double dPitch = (double) currPitch - prevPitch;
        double stepDeg = Math.hypot(dYaw, dPitch);

        if (state.pendingSnap != null) {
            SnapCandidate snap = state.pendingSnap;
            if (tick - snap.tick() <= 2) {
                double returnErr = Math.hypot(
                        VanillaMath.wrapDegrees((double) currYaw - snap.preYaw()),
                        currPitch - snap.prePitch());
                if (stepDeg >= MIN_SNAP_DEGREES && returnErr <= MAX_RETURN_ERROR_DEGREES) {
                    state.snapbacks.addLast(new long[] {tick, snap.seq(), snap.nano()});
                    state.pendingSnap = null;
                    while (!state.snapbacks.isEmpty() && tick - state.snapbacks.peekFirst()[0] > SNAPBACK_WINDOW_TICKS) {
                        state.snapbacks.pollFirst();
                    }
                    if (state.snapbacks.size() >= MIN_SNAPBACKS) {
                        long[] oldest = state.snapbacks.peekFirst();
                        Map<String, String> details = new TreeMap<>();
                        details.put("reason", "place-and-snapback");
                        details.put("snapbacks", Integer.toString(state.snapbacks.size()));
                        details.put("windowTicks", Integer.toString(SNAPBACK_WINDOW_TICKS));
                        details.put("lastReturnError", String.format(Locale.ROOT, "%.4f", returnErr));
                        if (report(session, state, oldest[1], oldest[2], tickEnd,
                                state.snapbacks.size() + " bridge place-and-snapback rotations within " + SNAPBACK_WINDOW_TICKS + " ticks",
                                details)) {
                            state.snapbacks.clear();
                        }
                    }
                    return;
                }
            } else {
                state.pendingSnap = null;
            }
        }
        if (placedThisTick && stepDeg >= MIN_SNAP_DEGREES) {
            state.pendingSnap = new SnapCandidate(tick, prevYaw, prevPitch, prevSeq, prevNano);
        }
        if (distinctPlacedThisTick) {
            if (!state.hasPitchLockRef
                    || tick - state.pitchLockLastTick > PITCH_LOCK_MAX_GAP_TICKS
                    || Float.compare(currPitch, state.pitchLockRefPitch) != 0) {
                state.hasPitchLockRef = true;
                state.pitchLockRefPitch = currPitch;
                state.pitchLockLastYaw = currYaw;
                state.pitchLockLastTick = tick;
                state.pitchLockStartSeq = state.lastBridgeSeq;
                state.pitchLockStartNano = state.lastBridgeNano;
                state.pitchLockCount = 1;
                state.pitchLockYawMoves = 0;
                state.pitchLockTotalYaw = 0.0;
            } else {
                double bridgeYawDelta = Math.abs(VanillaMath.wrapDegrees((double) currYaw - state.pitchLockLastYaw));
                state.pitchLockLastYaw = currYaw;
                state.pitchLockLastTick = tick;
                state.pitchLockCount++;
                if (bridgeYawDelta >= 1.0) {
                    state.pitchLockYawMoves++;
                    state.pitchLockTotalYaw += bridgeYawDelta;
                }
                if (state.pitchLockCount >= PITCH_LOCK_WINDOW
                        && state.pitchLockYawMoves >= PITCH_LOCK_MIN_YAW_MOVES
                        && state.pitchLockTotalYaw >= PITCH_LOCK_MIN_TOTAL_YAW) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "bridge-pitch-lock");
                    details.put("placements", Integer.toString(state.pitchLockCount));
                    details.put("lockedPitch", String.format(Locale.ROOT, "%.4f", state.pitchLockRefPitch));
                    details.put("yawMoves", Integer.toString(state.pitchLockYawMoves));
                    details.put("totalYawDegrees", String.format(Locale.ROOT, "%.2f", state.pitchLockTotalYaw));
                    if (report(session, state, state.pitchLockStartSeq, state.pitchLockStartNano, tickEnd,
                            String.format(Locale.ROOT, "zero-dPitch bridge lock at %.2f deg across %d placements (%.1f deg yaw turns)",
                                    state.pitchLockRefPitch, state.pitchLockCount, state.pitchLockTotalYaw),
                            details)) {
                        state.resetPitchLock();
                    }
                }
            }
        }
    }

    private boolean report(PlayerSession session, State state, long fromSeq, long fromNano,
                           TimelineEvent.Inbound at, String summary, Map<String, String> details) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return false;
        }
        Admissibility admissibility = state.admissibility.evaluate(fromSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return true;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.NOMINAL_STATISTIC, fromSeq, at.sequence(),
                fromNano, at.nanoTime(), summary, details, admissibility));
        state.lastReportNanos = at.nanoTime();
        return true;
    }
}
