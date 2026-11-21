package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.evidence.ServerClock;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bidirectional balance timer &amp; oscillating clock signal ({@code timer-balance}, S, SHADOW).
 *
 * <p>Complements {@link ClientClockCheck} ({@code P-M}, cumulative upper bound) by detecting
 * oscillating "balance timer" cheats that deliberately throttle below {@code 16} TPS to bank slack
 * and repeatedly burst above {@code 24} TPS within sub-22-tick bursts during combat/movement.
 *
 * <p>Uses {@link AdmissibilityTracker#evaluateTranslatorSafe(long)} since transaction-bounded tick
 * cadence holds through Java protocol translators. Class {@code S}: alerts and review priority only.
 */
public final class TimerBalanceCheck implements TimelineListener {

    public static final String ID = "timer-balance";
    public static final int VERSION = 1;
    static final long WINDOW_NANOS = 1_000_000_000L; // 1.0 s sampling buckets
    static final long MAX_FRAME_GAP_NANOS = 250_000_000L;
    static final double SLOW_TPS_THRESHOLD = 15.0;
    static final double FAST_TPS_THRESHOLD = 24.5;
    static final int REQUIRED_OSCILLATION_CYCLES = 4;
    static final long REPORT_INTERVAL_NANOS = 2_000_000_000L;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final ServerClock clock;

    public TimerBalanceCheck(EvidenceSink sink) {
        this(sink, ServerClock.SERVER);
    }

    public TimerBalanceCheck(EvidenceSink sink, ServerClock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        boolean loaded;
        long bucketStartNano = Long.MIN_VALUE;
        long bucketStartSeq;
        int bucketTicks;
        long prevTickNano = Long.MIN_VALUE;
        boolean sawLargePauseInBucket;

        boolean sawSlowPhase;
        int oscillationCycles;
        long windowStartSeq;
        long windowStartNano;
        long lastReportNanos = Long.MIN_VALUE;

        void reset() {
            loaded = false;
            bucketStartNano = Long.MIN_VALUE;
            bucketTicks = 0;
            prevTickNano = Long.MIN_VALUE;
            sawLargePauseInBucket = false;
            sawSlowPhase = false;
            oscillationCycles = 0;
        }
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
            case TimelineEvent.Gap ignored -> state.reset();
            case TimelineEvent.Outbound out -> {
                if (out.packet() instanceof ClientboundPacket.Login
                        || out.packet() instanceof ClientboundPacket.Respawn) {
                    state.reset();
                }
            }
            case TimelineEvent.Inbound in -> {
                if (in.packet() instanceof ServerboundPacket.PlayerLoaded) {
                    state.loaded = true;
                    state.bucketStartNano = in.nanoTime();
                    state.bucketStartSeq = in.sequence();
                    state.windowStartSeq = in.sequence();
                    state.windowStartNano = in.nanoTime();
                } else if (in.packet() instanceof ServerboundPacket.ClientTickEnd && state.loaded) {
                    onTick(session, state, in);
                }
            }
            default -> { }
        }
    }

    private void onTick(PlayerSession session, State state, TimelineEvent.Inbound in) {
        long now = in.nanoTime();
        if (state.prevTickNano != Long.MIN_VALUE && now - state.prevTickNano > MAX_FRAME_GAP_NANOS) {
            state.sawLargePauseInBucket = true;
        }
        state.prevTickNano = now;
        if (state.bucketStartNano == Long.MIN_VALUE) {
            state.bucketStartNano = now;
            state.bucketStartSeq = in.sequence();
            state.windowStartSeq = in.sequence();
            state.windowStartNano = now;
            return;
        }
        state.bucketTicks++;
        long dt = now - state.bucketStartNano;
        if (dt < WINDOW_NANOS) {
            return;
        }
        double tps = state.bucketTicks / (dt / 1_000_000_000.0);
        if (!state.sawLargePauseInBucket && dt <= 2_500_000_000L) {
            if (tps <= SLOW_TPS_THRESHOLD) {
                state.sawSlowPhase = true;
            } else if (tps >= FAST_TPS_THRESHOLD && state.sawSlowPhase) {
                state.sawSlowPhase = false;
                state.oscillationCycles++;
                if (state.oscillationCycles >= REQUIRED_OSCILLATION_CYCLES) {
                    int cycles = state.oscillationCycles;
                    state.oscillationCycles = 0;
                    if (state.lastReportNanos == Long.MIN_VALUE
                            || now - state.lastReportNanos >= REPORT_INTERVAL_NANOS) {
                        emit(session, state, in, cycles, tps);
                    }
                }
            }
        } else {
            state.sawSlowPhase = false;
            state.oscillationCycles = 0;
            state.windowStartSeq = in.sequence();
            state.windowStartNano = now;
        }
        state.bucketStartNano = now;
        state.bucketStartSeq = in.sequence();
        state.bucketTicks = 0;
        state.sawLargePauseInBucket = false;
    }

    private void emit(
            PlayerSession session,
            State state,
            TimelineEvent.Inbound at,
            int cycles,
            double latestTps) {
        Admissibility admissibility = state.admissibility.evaluateTranslatorSafe(state.windowStartSeq);
        if (admissibility.admissible() && !clock.trusted()) {
            admissibility = new Admissibility(
                    false,
                    java.util.List.of(String.format(
                            Locale.ROOT,
                            "server monotonic clock runs %.3f%% off wall clock",
                            clock.divergence() * 100.0)),
                    admissibility.identity());
        }
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("reason", "oscillating-timer-balance");
        details.put("cycles", Integer.toString(cycles));
        details.put("latestBurstTps", String.format(Locale.ROOT, "%.2f", latestTps));
        sink.accept(
                session,
                new Evidence(
                        ID,
                        VERSION,
                        EvidenceClass.NOMINAL_STATISTIC,
                        state.windowStartSeq,
                        at.sequence(),
                        state.windowStartNano,
                        at.nanoTime(),
                        String.format(
                                Locale.ROOT,
                                "oscillating timer balance detected across %d slow/fast cycles (burst %.1f TPS)",
                                cycles,
                                latestTps),
                        details,
                        admissibility));
        state.lastReportNanos = at.nanoTime();
    }
}
