package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.math.vanilla.MouseTurnModel;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.Replicas;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aim pattern ({@code aim-pattern}, S, SHADOW): statistical signals of automated or assisted combat aiming.
 *
 * <p>Uses {@link TickLook} to read only client-driven rotation ticks (excluding server teleport/rotation replies,
 * vehicle riding, and pre-load ticks) and applies {@code docs/reviews/2026-10-06-weakness-analysis.md} (#5)
 * legitimate exemptions <em>before</em> evaluating any rotation:
 * <ul>
 *   <li>spyglass in main or off hand ({@link ClientInventory#views()});</li>
 *   <li>large |yaw| where {@code Math.ulp(yaw)} degrades lattice resolution ({@code > 0.001});</li>
 *   <li>pitch near the ±90° clamp;</li>
 *   <li>cinematic camera / HiDPI fractional cursors / gamepads: {@code combat-lattice-break} activates only after a
 *       consistent discrete {@link MouseTurnModel} baseline has already been established outside combat.</li>
 * </ul>
 *
 * <p>Signals:
 * <ul>
 *   <li>{@code hit-and-snapback}: repeated &ge; 25° single-tick attack turns followed within 1–2 ticks by a return to
 *       within 5° of the pre-attack orientation;</li>
 *   <li>{@code robotic-tracking}: sustained moving combat turns with near-zero angular speed variance
 *       ({@link RunningMoments#sampleStdDev()});</li>
 *   <li>{@code combat-lattice-break}: an established discrete mouse lattice that suddenly breaks specifically during
 *       combat attack windows.</li>
 * </ul>
 *
 * <p>Class S (nominal statistic): never triggers automated bans or neutralization ({@code docs/POLICY.md}).
 */
public final class AimPatternCheck implements TimelineListener {

    public static final String ID = "aim-pattern";
    public static final int VERSION = 2;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    // Signal 1: hit-and-snapback
    static final double MIN_SNAP_DEGREES = 25.0;
    static final double MAX_RETURN_ERROR_DEGREES = 5.0;
    static final int SNAPBACK_WINDOW_TICKS = 120;
    static final int MIN_SNAPBACKS = 3;

    // Signal 2: robotic-tracking
    static final int ROBOTIC_WINDOW = 25;
    static final double MIN_ROBOTIC_MEAN_DYAW = 1.5;
    static final double MAX_ROBOTIC_STD_DEV = 0.015;
    static final int COMBAT_ACTIVE_TICKS = 4;

    // Signal 2b: low-entropy-aim (multi-tick discrete speed locking, e.g. alternating aimbot steps)
    static final int ENTROPY_WINDOW = 32;
    static final double MIN_ENTROPY_STEP = 0.5;
    static final double ENTROPY_BIN_WIDTH = 0.25;
    static final double MAX_ENTROPY_BITS = 1.05;
    static final int MIN_ENTROPY_ATTACKS = 3;

    // Signal 3: combat-lattice-break (MouseTurnModel GCD baseline vs combat deviation)
    static final int BASELINE_WINDOW = 20;
    static final int MIN_BASELINE_MATCHES = 18;
    static final int COMBAT_LATTICE_WINDOW = 15;
    static final int MIN_COMBAT_LATTICE_BREAKS = 12;
    static final float MAX_LATTICE_ULP = 0.001F;
    static final double MIN_STEP_DEG = 0.005;
    static final double MAX_STEP_DEG = 1.5;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public AimPatternCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private record SnapCandidate(long tick, float preYaw, float prePitch, long seq, long nano) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final TickLook look;

        State(PingLedger ledger) {
            this.look = new TickLook(ledger);
        }

        static State fromSession(PlayerSession session) {
            return new State(session.pingLedger());
        }

        long tickNumber;
        long lastReportNanos = Long.MIN_VALUE;

        boolean tickAttacked;
        long lastAttackTick = Long.MIN_VALUE;
        int lastAttackTarget = Integer.MIN_VALUE;
        int currentTarget = Integer.MIN_VALUE;

        // Previous client-driven rotation (null across teleports, rides, camera switches, reloads)
        boolean hasPrev;
        float prevYaw;
        float prevPitch;
        long prevSeq;
        long prevNano;

        // Hit-and-snapback
        SnapCandidate pendingSnap;
        final ArrayDeque<long[]> snapbacks = new ArrayDeque<>(); // [tick, seq, nano]

        // Robotic constant-speed & low-entropy combat tracking
        final ArrayDeque<double[]> roboticSamples = new ArrayDeque<>(); // [absDYaw, seq, nano]
        final ArrayDeque<double[]> entropySamples = new ArrayDeque<>(); // [absDYaw, attacked(0/1), seq, nano]

        // Lattice baseline + combat break
        double prevCleanPitchDelta = Double.NaN;
        double candidateStep = Double.NaN;
        double candidateMultiplier = Double.NaN;
        double candidateDeltaSum;
        long candidateCountSum;
        final ArrayDeque<Boolean> baselineMatches = new ArrayDeque<>();
        int baselineMatchCount;
        boolean latticeEstablished;
        double establishedStep;
        double establishedMultiplier;
        int nonCombatConsecutiveMisses;
        final ArrayDeque<long[]> combatLatticeSamples = new ArrayDeque<>(); // [breakFlag (0/1), seq, nano]
        int combatBreakCount;
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
            case TimelineEvent.Bound bound -> state.look.onBound(bound.entityId());
            case TimelineEvent.Outbound out -> {
                state.look.onOutbound(out);
                if (out.packet() instanceof ClientboundPacket.Login || out.packet() instanceof ClientboundPacket.Respawn
                        || out.packet() instanceof ClientboundPacket.PlayerPosition) {
                    resetTracking(state);
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
                resetTracking(state);
            }
            case ServerboundPacket.AcceptTeleportation accept -> {
                state.look.onAccept(accept);
                resetTracking(state);
            }
            case ServerboundPacket.InteractEntity interact when interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK -> {
                state.look.onAction();
                state.tickAttacked = true;
                state.lastAttackTick = state.tickNumber;
                state.lastAttackTarget = interact.entityId();
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

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        long tick = state.tickNumber++;
        ClientSelf self = replicas.self().self(session);
        boolean mayRide = self.mayRide();
        state.look.onTickEnd(mayRide);

        boolean attackedThisTick = state.tickAttacked;
        state.tickAttacked = false;

        List<TickLook.Rotation> candidates = state.look.candidates();
        if (!state.look.loaded() || mayRide || self.eye() == null || candidates == null || candidates.size() != 1) {
            resetTracking(state);
            return;
        }
        TickLook.Rotation curr = candidates.get(0);
        if (!Float.isFinite(curr.yRot()) || !Float.isFinite(curr.xRot())) {
            resetTracking(state);
            return;
        }
        if (!state.hasPrev) {
            state.hasPrev = true;
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

        if (Float.compare(prevYaw, currYaw) == 0 && Float.compare(prevPitch, currPitch) == 0) {
            if (state.pendingSnap != null && tick - state.pendingSnap.tick() > 2) {
                state.pendingSnap = null;
            }
            return;
        }

        double dYaw = VanillaMath.wrapDegrees((double) currYaw - prevYaw);
        double dPitch = currPitch - prevPitch;
        double stepDeg = Math.hypot(dYaw, dPitch);

        checkSnapback(session, state, tick, tickEnd, prevYaw, prevPitch, currYaw, currPitch, stepDeg, attackedThisTick, prevSeq, prevNano);
        boolean inCombat = attackedThisTick || (state.lastAttackTick != Long.MIN_VALUE && tick - state.lastAttackTick <= COMBAT_ACTIVE_TICKS);
        if (inCombat) {
            checkRoboticTracking(session, state, tickEnd, Math.abs(dYaw));
            checkLowEntropyTracking(session, state, tickEnd, Math.abs(dYaw), attackedThisTick);
        } else {
            state.roboticSamples.clear();
            state.entropySamples.clear();
            state.currentTarget = Integer.MIN_VALUE;
        }

        if (!mayHoldSpyglass(replicas.inventory().inventory(session)) && Math.ulp(Math.abs(prevYaw)) <= MAX_LATTICE_ULP) {
            boolean pitchValid = Math.abs(prevPitch) < 89.5F && Math.abs(currPitch) < 89.5F;
            checkLattice(session, state, tick, tickEnd, prevYaw, currYaw, prevPitch, currPitch, dYaw, dPitch, pitchValid);
        }
    }

    private void checkSnapback(PlayerSession session, State state, long tick, TimelineEvent.Inbound tickEnd,
                               float prevYaw, float prevPitch, float currYaw, float currPitch, double stepDeg,
                               boolean attackedThisTick, long prevSeq, long prevNano) {
        if (state.pendingSnap != null) {
            SnapCandidate snap = state.pendingSnap;
            if (tick - snap.tick() <= 2) {
                double returnErr = Math.hypot(VanillaMath.wrapDegrees((double) currYaw - snap.preYaw()), currPitch - snap.prePitch());
                if (stepDeg >= MIN_SNAP_DEGREES && returnErr <= MAX_RETURN_ERROR_DEGREES) {
                    state.snapbacks.addLast(new long[] {tick, snap.seq(), snap.nano()});
                    state.pendingSnap = null;
                    while (!state.snapbacks.isEmpty() && tick - state.snapbacks.peekFirst()[0] > SNAPBACK_WINDOW_TICKS) {
                        state.snapbacks.pollFirst();
                    }
                    if (state.snapbacks.size() >= MIN_SNAPBACKS) {
                        long[] oldest = state.snapbacks.peekFirst();
                        Map<String, String> details = new TreeMap<>();
                        details.put("reason", "hit-and-snapback");
                        details.put("snapbacks", Integer.toString(state.snapbacks.size()));
                        details.put("windowTicks", Integer.toString(SNAPBACK_WINDOW_TICKS));
                        details.put("lastReturnError", format(returnErr));
                        if (report(session, state, oldest[1], oldest[2], tickEnd,
                                state.snapbacks.size() + " hit-and-snapback rotations within " + SNAPBACK_WINDOW_TICKS + " ticks", details)) {
                            state.snapbacks.clear();
                        }
                    }
                    return;
                }
            } else {
                state.pendingSnap = null;
            }
        }
        if (attackedThisTick && stepDeg >= MIN_SNAP_DEGREES) {
            state.pendingSnap = new SnapCandidate(tick, prevYaw, prevPitch, prevSeq, prevNano);
        }
    }

    private void checkRoboticTracking(PlayerSession session, State state, TimelineEvent.Inbound tickEnd, double absDYaw) {
        if (state.lastAttackTarget != state.currentTarget) {
            state.currentTarget = state.lastAttackTarget;
            state.roboticSamples.clear();
        }
        if (absDYaw < 0.1) {
            return;
        }
        state.roboticSamples.addLast(new double[] {absDYaw, tickEnd.sequence(), tickEnd.nanoTime()});
        while (state.roboticSamples.size() > ROBOTIC_WINDOW) {
            state.roboticSamples.pollFirst();
        }
        if (state.roboticSamples.size() < ROBOTIC_WINDOW) {
            return;
        }
        RunningMoments moments = new RunningMoments();
        for (double[] s : state.roboticSamples) {
            moments.add(s[0]);
        }
        double mean = moments.mean();
        double sd = moments.sampleStdDev();
        if (mean >= MIN_ROBOTIC_MEAN_DYAW && sd < MAX_ROBOTIC_STD_DEV) {
            double[] first = state.roboticSamples.peekFirst();
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "robotic-tracking");
            details.put("samples", Integer.toString(ROBOTIC_WINDOW));
            details.put("meanAbsDYaw", format(mean));
            details.put("stdDevDYaw", format(sd));
            if (report(session, state, (long) first[1], (long) first[2], tickEnd,
                    String.format(Locale.ROOT, "constant combat angular speed (mean %.2f deg/tick, sd %.4f over %d ticks)",
                            mean, sd, ROBOTIC_WINDOW), details)) {
                state.roboticSamples.clear();
            }
        }
    }

    private void checkLowEntropyTracking(PlayerSession session, State state, TimelineEvent.Inbound tickEnd,
                                         double absDYaw, boolean attackedThisTick) {
        if (absDYaw < MIN_ENTROPY_STEP) {
            return;
        }
        state.entropySamples.addLast(new double[] {absDYaw, attackedThisTick ? 1.0 : 0.0, tickEnd.sequence(), tickEnd.nanoTime()});
        while (state.entropySamples.size() > ENTROPY_WINDOW) {
            state.entropySamples.pollFirst();
        }
        if (state.entropySamples.size() < ENTROPY_WINDOW) {
            return;
        }
        RunningMoments moments = new RunningMoments();
        Map<Long, Integer> bins = new java.util.HashMap<>();
        int attackTicks = 0;
        for (double[] s : state.entropySamples) {
            moments.add(s[0]);
            if (s[1] > 0.5) {
                attackTicks++;
            }
            long bin = Math.round(s[0] / ENTROPY_BIN_WIDTH);
            bins.merge(bin, 1, Integer::sum);
        }
        double mean = moments.mean();
        double sd = moments.sampleStdDev();
        if (attackTicks < MIN_ENTROPY_ATTACKS || mean < MIN_ROBOTIC_MEAN_DYAW || sd < MAX_ROBOTIC_STD_DEV) {
            return;
        }
        double entropy = 0.0;
        for (int count : bins.values()) {
            double p = (double) count / ENTROPY_WINDOW;
            entropy -= p * (Math.log(p) / Math.log(2.0));
        }
        if (bins.size() <= 2 && entropy <= MAX_ENTROPY_BITS) {
            double[] first = state.entropySamples.peekFirst();
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "low-entropy-aim");
            details.put("samples", Integer.toString(ENTROPY_WINDOW));
            details.put("uniqueBins", Integer.toString(bins.size()));
            details.put("entropyBits", format(entropy));
            details.put("meanAbsDYaw", format(mean));
            if (report(session, state, (long) first[2], (long) first[3], tickEnd,
                    String.format(Locale.ROOT, "low-entropy combat aiming (H=%.2f bits, %d bins over %d ticks)",
                            entropy, bins.size(), ENTROPY_WINDOW), details)) {
                state.entropySamples.clear();
            }
        }
    }

    private void checkLattice(PlayerSession session, State state, long tick, TimelineEvent.Inbound tickEnd,
                              float prevYaw, float currYaw, float prevPitch, float currPitch,
                              double dYaw, double dPitch, boolean pitchValid) {
        double absDPitch = Math.abs(dPitch);
        boolean recentAttack = state.lastAttackTick != Long.MIN_VALUE && tick - state.lastAttackTick <= 2;

        // Outside combat, learn or refine the discrete MouseTurnModel sensitivity multiplier.
        if (!recentAttack && pitchValid && absDPitch >= MIN_STEP_DEG && absDPitch <= 15.0) {
            if (!Double.isNaN(state.prevCleanPitchDelta)) {
                double g = approxGcd(absDPitch, state.prevCleanPitchDelta);
                if (!Double.isNaN(g) && g >= MIN_STEP_DEG && g <= MAX_STEP_DEG) {
                    if (Double.isNaN(state.candidateStep)) {
                        initCandidateStep(state, g);
                    } else {
                        double refined = approxGcd(state.candidateStep, g);
                        if (!Double.isNaN(refined) && refined >= MIN_STEP_DEG && refined < state.candidateStep * 0.97) {
                            initCandidateStep(state, refined);
                        }
                    }
                }
            }
            state.prevCleanPitchDelta = absDPitch;
        }

        double activeStep = state.latticeEstablished ? state.establishedStep : state.candidateStep;
        double activeMult = state.latticeEstablished ? state.establishedMultiplier : state.candidateMultiplier;
        if (Double.isNaN(activeStep)) {
            return;
        }

        boolean matches = stepMatchesLattice(prevYaw, currYaw, prevPitch, currPitch, dYaw, dPitch, pitchValid, activeStep, activeMult);

        if (!recentAttack) {
            if (state.latticeEstablished) {
                if (matches) {
                    state.nonCombatConsecutiveMisses = 0;
                } else if (++state.nonCombatConsecutiveMisses >= 3) {
                    // Sensitivity changed or cinematic/HiDPI mode enabled: invalidate baseline cleanly.
                    clearLatticeBaseline(state);
                }
            } else if (!Double.isNaN(state.candidateStep)) {
                if (matches && pitchValid && absDPitch >= MIN_STEP_DEG && absDPitch <= 15.0) {
                    long q = Math.round(absDPitch / state.candidateStep);
                    if (q >= 1 && q <= 64) {
                        state.candidateDeltaSum += absDPitch;
                        state.candidateCountSum += q;
                        state.candidateStep = state.candidateDeltaSum / state.candidateCountSum;
                        state.candidateMultiplier = state.candidateStep / MouseTurnModel.TURN_SCALE;
                    }
                }
                state.baselineMatches.addLast(matches);
                if (matches) {
                    state.baselineMatchCount++;
                }
                if (state.baselineMatches.size() > BASELINE_WINDOW) {
                    if (state.baselineMatches.pollFirst()) {
                        state.baselineMatchCount--;
                    }
                }
                if (state.baselineMatches.size() == BASELINE_WINDOW && state.baselineMatchCount >= MIN_BASELINE_MATCHES) {
                    state.latticeEstablished = true;
                    state.establishedStep = state.candidateStep;
                    state.establishedMultiplier = state.candidateMultiplier;
                    state.nonCombatConsecutiveMisses = 0;
                    state.combatLatticeSamples.clear();
                    state.combatBreakCount = 0;
                }
            }
            return;
        }

        if (!state.latticeEstablished) {
            return;
        }
        // Only judge smooth-range yaw/pitch steps during active attack ticks (fast flicks > 30 counts amplify any tiny step residual)
        long qYaw = Math.round(Math.abs(dYaw) / state.establishedStep);
        if (Math.abs(dYaw) < 0.02 || qYaw > 30) {
            return;
        }
        long broken = matches ? 0L : 1L;
        state.combatLatticeSamples.addLast(new long[] {broken, tickEnd.sequence(), tickEnd.nanoTime()});
        state.combatBreakCount += (int) broken;
        if (state.combatLatticeSamples.size() > COMBAT_LATTICE_WINDOW) {
            state.combatBreakCount -= (int) state.combatLatticeSamples.pollFirst()[0];
        }
        if (state.combatLatticeSamples.size() == COMBAT_LATTICE_WINDOW && state.combatBreakCount >= MIN_COMBAT_LATTICE_BREAKS) {
            long[] oldest = state.combatLatticeSamples.peekFirst();
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "combat-lattice-break");
            details.put("breaks", Integer.toString(state.combatBreakCount));
            details.put("window", Integer.toString(COMBAT_LATTICE_WINDOW));
            details.put("establishedStep", format(state.establishedStep));
            if (report(session, state, oldest[1], oldest[2], tickEnd,
                    state.combatBreakCount + " of " + COMBAT_LATTICE_WINDOW + " combat rotations broke the mouse lattice", details)) {
                state.combatLatticeSamples.clear();
                state.combatBreakCount = 0;
            }
        }
    }

    private static boolean stepMatchesLattice(float prevYaw, float currYaw, float prevPitch, float currPitch,
                                              double dYaw, double dPitch, boolean pitchValid,
                                              double step, double multiplier) {
        boolean yawOk = Math.abs(dYaw) < 1e-6
                || !MouseTurnModel.yawCountsFor(prevYaw, currYaw, multiplier).isEmpty()
                || residualToGrid(Math.abs(dYaw), step) <= Math.max(0.03 * step, 2.0E-4);
        boolean pitchOk = !pitchValid || Math.abs(dPitch) < 1e-6
                || !MouseTurnModel.yawCountsFor(prevPitch, currPitch, multiplier).isEmpty()
                || residualToGrid(Math.abs(dPitch), step) <= Math.max(0.03 * step, 2.0E-4);
        return yawOk && pitchOk;
    }

    private static double residualToGrid(double delta, double step) {
        double q = Math.round(delta / step);
        return Math.abs(delta - q * step);
    }

    private static double approxGcd(double a, double b) {
        double x = Math.max(a, b);
        double y = Math.min(a, b);
        for (int i = 0; i < 16 && y > 1e-4; i++) {
            double rem = x % y;
            if (rem < 1e-4 || Math.abs(y - rem) < 1e-4) {
                return y;
            }
            x = y;
            y = rem;
        }
        return y > MIN_STEP_DEG ? y : Double.NaN;
    }

    private static boolean mayHoldSpyglass(ClientInventory inventory) {
        List<ClientInventory.View> views = inventory.views();
        if (views.isEmpty()) {
            return true;
        }
        for (ClientInventory.View view : views) {
            if (view.selected() < 0 || view.selected() >= view.hotbar().size()) {
                return true;
            }
            Stack main = view.hotbar().get(view.selected());
            Stack off = view.offhand();
            if (main == null || off == null
                    || "minecraft:spyglass".equals(main.item())
                    || "minecraft:spyglass".equals(off.item())) {
                return true;
            }
        }
        return false;
    }

    private boolean report(PlayerSession session, State state, long fromSeq, long fromNano, TimelineEvent.Inbound at,
                           String summary, Map<String, String> details) {
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

    private static void resetTracking(State state) {
        state.hasPrev = false;
        state.pendingSnap = null;
        state.roboticSamples.clear();
        state.entropySamples.clear();
        state.currentTarget = Integer.MIN_VALUE;
        state.prevCleanPitchDelta = Double.NaN;
        state.combatLatticeSamples.clear();
        state.combatBreakCount = 0;
    }

    private static void initCandidateStep(State state, double step) {
        state.candidateStep = step;
        state.candidateMultiplier = step / MouseTurnModel.TURN_SCALE;
        state.candidateDeltaSum = 0.0;
        state.candidateCountSum = 0L;
        state.baselineMatches.clear();
        state.baselineMatchCount = 0;
    }

    private static void clearLatticeBaseline(State state) {
        state.latticeEstablished = false;
        state.candidateStep = Double.NaN;
        state.candidateMultiplier = Double.NaN;
        state.candidateDeltaSum = 0.0;
        state.candidateCountSum = 0L;
        state.baselineMatches.clear();
        state.baselineMatchCount = 0;
        state.nonCombatConsecutiveMisses = 0;
        state.combatLatticeSamples.clear();
        state.combatBreakCount = 0;
    }

    private static String format(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
