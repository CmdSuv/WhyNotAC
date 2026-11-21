package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Click pattern ({@code click-pattern}, S, SHADOW): statistical signals of automated or macro clicking in combat.
 *
 * <p>Counts main-hand combat swings per client tick ({@code ClientTickEnd} framing makes the measurement immune to TCP
 * coalescing and network jitter) while excluding digging swings, item uses, block placements, and spear stabs:
 * <ul>
 *   <li>{@code sustained-cps}: sustained superhuman swing rate over {@link #CPS_WINDOW_TICKS} active ticks;</li>
 *   <li>{@code constant-interval}: {@link #INTERVAL_WINDOW} consecutive rapid combat intervals with near-zero tick
 *       variance ({@link RunningMoments#sampleStdDev()});</li>
 *   <li>{@code macro-periodicity}: {@link #INTERVAL_WINDOW} consecutive rapid combat intervals locked to a repeating
 *       period-2 or period-3 sub-tick cadence (characteristic of fixed-millisecond hardware macros).</li>
 * </ul>
 *
 * <p>Class S (nominal statistic): never triggers automated bans or neutralization ({@code docs/POLICY.md}).
 */
public final class ClickPatternCheck implements TimelineListener {

    public static final String ID = "click-pattern";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    static final int CPS_WINDOW_TICKS = 40;
    static final int SUSTAINED_SWING_THRESHOLD = 80;       // 40.0 CPS over 2.0 s
    static final int COMBAT_HIGH_SWING_THRESHOLD = 50;     // 25.0 CPS over 2.0 s with >= 3 attacks
    static final int COMBAT_MIN_ATTACKS_HIGH_CPS = 3;

    static final int INTERVAL_WINDOW = 60;
    static final int MAX_COMBAT_GAP_TICKS = 4;             // >= 5.0 CPS keeps the rapid burst active
    static final double MAX_MEAN_INTERVAL_TICKS = 3.2;     // >= 6.25 CPS
    static final double MAX_CONSTANT_STD_DEV = 0.14;
    static final double MAX_BIMODAL_KURTOSIS = -1.4;
    static final int MIN_PERIOD2_MATCHES = 56;             // out of 58
    static final int MIN_PERIOD3_MATCHES = 55;             // out of 57

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public ClickPatternCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();

        boolean seenTickEnd;
        long tickNumber;
        long lastReportNanos = Long.MIN_VALUE;

        // Per-tick accumulators (cleared on each ClientTickEnd)
        boolean digging;
        boolean tickExcluded;
        int tickMainSwings;
        int tickAttacks;

        // Rolling CPS window over CPS_WINDOW_TICKS client ticks
        final int[] cpsSwings = new int[CPS_WINDOW_TICKS];
        final int[] cpsAttacks = new int[CPS_WINDOW_TICKS];
        final long[] cpsSeqs = new long[CPS_WINDOW_TICKS];
        final long[] cpsNanos = new long[CPS_WINDOW_TICKS];
        int cpsHead;
        int cpsFilled;
        int windowSwings;
        int windowAttacks;

        // Consecutive rapid combat click intervals
        long lastCombatSwingTick = -1;
        final int[] intervals = new int[INTERVAL_WINDOW];
        final int[] intervalAttacks = new int[INTERVAL_WINDOW];
        final long[] intervalSeqs = new long[INTERVAL_WINDOW];
        final long[] intervalNanos = new long[INTERVAL_WINDOW];
        int intervalHead;
        int intervalCount;
        int burstAttacks;
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out -> {
                if (out.packet() instanceof ClientboundPacket.Login || out.packet() instanceof ClientboundPacket.Respawn) {
                    state.digging = false;
                    resetBurst(state);
                    resetCpsWindow(state);
                }
            }
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerAction action -> {
                switch (action.action()) {
                    case START_DESTROY_BLOCK -> {
                        state.digging = true;
                        state.tickExcluded = true;
                    }
                    case STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> {
                        state.digging = false;
                        state.tickExcluded = true;
                    }
                    case STAB -> state.tickExcluded = true;
                    default -> { }
                }
            }
            case ServerboundPacket.UseItemOn ignored -> state.tickExcluded = true;
            case ServerboundPacket.UseItem ignored -> state.tickExcluded = true;
            case ServerboundPacket.SetCarriedItem ignored -> state.digging = false;
            case ServerboundPacket.InteractEntity interact -> {
                if (interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK) {
                    state.digging = false;
                    state.tickAttacks++;
                }
            }
            case ServerboundPacket.Swing swing -> {
                if (swing.hand() == Hand.MAIN_HAND) {
                    state.tickMainSwings++;
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> onTickEnd(session, state, in);
            default -> { }
        }
    }

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        long tick = state.tickNumber++;
        if (!state.seenTickEnd) {
            state.seenTickEnd = true;
            clearTick(state);
            return;
        }
        boolean exclude = state.tickExcluded || state.digging;
        int swings = exclude ? 0 : state.tickMainSwings;
        int attacks = exclude ? 0 : state.tickAttacks;
        clearTick(state);

        recordCpsTick(session, state, tickEnd, swings, attacks);
        if (swings > 0) {
            recordRapidClicks(session, state, tick, tickEnd, swings, attacks);
        }
    }

    private void recordCpsTick(PlayerSession session, State state, TimelineEvent.Inbound tickEnd, int swings, int attacks) {
        int slot = state.cpsHead;
        if (state.cpsFilled == CPS_WINDOW_TICKS) {
            state.windowSwings -= state.cpsSwings[slot];
            state.windowAttacks -= state.cpsAttacks[slot];
        } else {
            state.cpsFilled++;
        }
        state.cpsSwings[slot] = swings;
        state.cpsAttacks[slot] = attacks;
        state.cpsSeqs[slot] = tickEnd.sequence();
        state.cpsNanos[slot] = tickEnd.nanoTime();
        state.windowSwings += swings;
        state.windowAttacks += attacks;
        state.cpsHead = (slot + 1) % CPS_WINDOW_TICKS;

        if (state.cpsFilled < CPS_WINDOW_TICKS) {
            return;
        }
        boolean overRaw = state.windowSwings >= SUSTAINED_SWING_THRESHOLD;
        boolean overCombat = state.windowSwings >= COMBAT_HIGH_SWING_THRESHOLD && state.windowAttacks >= COMBAT_MIN_ATTACKS_HIGH_CPS;
        if (!overRaw && !overCombat) {
            return;
        }
        long fromSeq = state.cpsSeqs[state.cpsHead];
        long fromNano = state.cpsNanos[state.cpsHead];
        double cps = state.windowSwings / (CPS_WINDOW_TICKS * 0.05);
        Map<String, String> details = new TreeMap<>();
        details.put("reason", "sustained-cps");
        details.put("swings", Integer.toString(state.windowSwings));
        details.put("attacks", Integer.toString(state.windowAttacks));
        details.put("ticks", Integer.toString(CPS_WINDOW_TICKS));
        details.put("cps", format(cps));
        if (report(session, state, fromSeq, fromNano, tickEnd,
                String.format(Locale.ROOT, "sustained %.1f combat CPS over %d ticks", cps, CPS_WINDOW_TICKS), details)) {
            resetCpsWindow(state);
        }
    }

    private void recordRapidClicks(PlayerSession session, State state, long tick, TimelineEvent.Inbound tickEnd, int swings, int attacks) {
        for (int i = 0; i < swings; i++) {
            int clickAttacks = i == 0 ? attacks : 0;
            if (state.lastCombatSwingTick < 0) {
                state.lastCombatSwingTick = tick;
                continue;
            }
            long gap = tick - state.lastCombatSwingTick;
            state.lastCombatSwingTick = tick;
            if (gap > MAX_COMBAT_GAP_TICKS) {
                resetBurst(state);
                state.lastCombatSwingTick = tick;
                continue;
            }
            pushInterval(state, (int) gap, clickAttacks, tickEnd.sequence(), tickEnd.nanoTime());
        }
        if (state.intervalCount < INTERVAL_WINDOW || state.burstAttacks < 1) {
            return;
        }
        evaluateBurst(session, state, tickEnd);
    }

    private void pushInterval(State state, int gap, int attacks, long seq, long nano) {
        int slot = state.intervalHead;
        if (state.intervalCount == INTERVAL_WINDOW) {
            state.burstAttacks -= state.intervalAttacks[slot];
        } else {
            state.intervalCount++;
        }
        state.intervals[slot] = gap;
        state.intervalAttacks[slot] = attacks;
        state.intervalSeqs[slot] = seq;
        state.intervalNanos[slot] = nano;
        state.burstAttacks += attacks;
        state.intervalHead = (slot + 1) % INTERVAL_WINDOW;
    }

    private void evaluateBurst(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        RunningMoments moments = new RunningMoments();
        int[] ordered = new int[INTERVAL_WINDOW];
        for (int i = 0; i < INTERVAL_WINDOW; i++) {
            int idx = (state.intervalHead + i) % INTERVAL_WINDOW;
            int dt = state.intervals[idx];
            ordered[i] = dt;
            moments.add(dt);
        }
        double mean = moments.mean();
        if (mean > MAX_MEAN_INTERVAL_TICKS) {
            return;
        }
        double stdDev = moments.sampleStdDev();
        double kurtosis = moments.excessKurtosis();
        int span = (int) Math.round(moments.max() - moments.min());

        String reason = null;
        String summary = null;
        int periodMatches = 0;
        if (stdDev < MAX_CONSTANT_STD_DEV) {
            reason = "constant-interval";
            summary = String.format(Locale.ROOT, "constant combat click interval (mean %.2f ticks, sd %.4f over %d clicks)",
                    mean, stdDev, INTERVAL_WINDOW);
        } else if (span == 1 && !Double.isNaN(kurtosis) && kurtosis <= MAX_BIMODAL_KURTOSIS) {
            int p2 = periodMatches(ordered, 2);
            int p3 = periodMatches(ordered, 3);
            if (p2 >= MIN_PERIOD2_MATCHES || p3 >= MIN_PERIOD3_MATCHES) {
                reason = "macro-periodicity";
                periodMatches = Math.max(p2, p3);
                summary = String.format(Locale.ROOT, "periodic combat macro cadence (period %d, %d matches over %d clicks)",
                        p2 >= MIN_PERIOD2_MATCHES ? 2 : 3, periodMatches, INTERVAL_WINDOW);
            }
        }
        if (reason == null) {
            return;
        }
        long fromSeq = state.intervalSeqs[state.intervalHead];
        long fromNano = state.intervalNanos[state.intervalHead];
        Map<String, String> details = new TreeMap<>();
        details.put("reason", reason);
        details.put("samples", Integer.toString(INTERVAL_WINDOW));
        details.put("attacks", Integer.toString(state.burstAttacks));
        details.put("meanTicks", format(mean));
        details.put("stdDevTicks", format(stdDev));
        details.put("excessKurtosis", Double.isNaN(kurtosis) ? "NaN" : format(kurtosis));
        if (periodMatches > 0) {
            details.put("periodMatches", Integer.toString(periodMatches));
        }
        if (report(session, state, fromSeq, fromNano, tickEnd, summary, details)) {
            resetBurst(state);
        }
    }

    private static int periodMatches(int[] values, int period) {
        int matches = 0;
        for (int i = period; i < values.length; i++) {
            if (values[i] == values[i - period]) {
                matches++;
            }
        }
        return matches;
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

    private static void clearTick(State state) {
        state.tickExcluded = false;
        state.tickMainSwings = 0;
        state.tickAttacks = 0;
    }

    private static void resetCpsWindow(State state) {
        state.cpsHead = 0;
        state.cpsFilled = 0;
        state.windowSwings = 0;
        state.windowAttacks = 0;
    }

    private static void resetBurst(State state) {
        state.lastCombatSwingTick = -1;
        state.intervalHead = 0;
        state.intervalCount = 0;
        state.burstAttacks = 0;
    }

    private static String format(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
