package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Paired {@code Ping} vs {@code KeepAlive} latency divergence &amp; combat RTT-drop check
 * ({@code ping-spoof}, S, SHADOW, v2).
 *
 * <p>Decompiled 1.21.11 client processes {@code KeepAlive} immediately on the Netty I/O thread while
 * {@code Ping} runs on the main game thread via {@code PacketProcessor}. When a {@code Backtrack} or
 * {@code PingSpoof} cheat delays inbound {@code Ping} / game packets by {@code 200..550 ms} (below
 * {@link TransactionOrderCheck}'s deterministic 12-tick cap) while {@code KeepAlive} responds at
 * real network RTT, {@code rtt_ping - rtt_keepalive} remains persistently inflated across {@code >= 15}
 * consecutive stall-free active transactions, or repeatedly drops back near {@code KeepAlive} RTT
 * ({@code <= ka + 60 ms}) upon attacking ({@code >= 2} distinct cycles within 60 s).
 *
 * <p>Legitimate client GC pauses, alt-tabs, and CPU-starved server bursts are excluded by resetting
 * streaks whenever consecutive {@code ClientTickEnd} wall-clock gaps exceed {@code 150 ms}, requiring
 * combat drops to flush down near real {@code KeepAlive} RTT (filtering staircase queue drains), and
 * requiring repeated combat-drop correlation.
 *
 * <p>Uses {@link AdmissibilityTracker#evaluateTranslatorSafe(long)}. Class {@code S}: non-punitive
 * alert and AI triage signal only.
 */
public final class PingSpoofCheck implements TimelineListener {

    public static final String ID = "ping-spoof";
    public static final int VERSION = 3;
    static final int REQUIRED_INFLATED_SAMPLES = 15;
    static final double MIN_INFLATION_MS = 200.0;
    static final int REQUIRED_BASELINE_SAMPLES = 5;
    static final int REQUIRED_PRE_ATTACK_INFLATED = 4;
    static final double PRE_ATTACK_INFLATION_MS = 160.0;
    static final double MIN_COMBAT_DROP_MS = 120.0;
    static final double MAX_KEEPALIVE_FOR_DROP_MS = 100.0;
    static final double MAX_POST_DROP_ABOVE_KA_MS = 60.0;
    static final int ATTACK_DROP_WINDOW_TICKS = 3;
    static final int REQUIRED_COMBAT_DROPS = 2;
    static final long COMBAT_DROP_WINDOW_NANOS = 60_000_000_000L;
    static final long MIN_COMBAT_DROP_SEPARATION_NANOS = 500_000_000L;
    static final long MAX_TICK_WALL_GAP_NANOS = 150_000_000L;
    static final long REPORT_INTERVAL_NANOS = 2_000_000_000L;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;

    public PingSpoofCheck(EvidenceSink sink) {
        this.sink = sink;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final Map<Long, Long> keepAliveSentNanos = new LinkedHashMap<>(16, 0.75F, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
                return size() > 128;
            }
        };
        boolean loaded;
        long ticksSinceLastAck;
        long lastTickEndNano = Long.MIN_VALUE;
        double latestKeepAliveRttMs = Double.NaN;
        int inflatedStreak;
        long streakStartSeq = -1L;
        long streakStartNano;
        double inflatedSumMs;
        int preDropHighSamples;
        double preDropBaselinePingMs = Double.NaN;
        long preDropStartSeq = -1L;
        long preDropStartNano;
        long ticksSinceAttack = Long.MAX_VALUE / 4;
        int combatDropCount;
        long firstCombatDropSeq = -1L;
        long firstCombatDropNano = Long.MIN_VALUE;
        long lastCombatDropNano = Long.MIN_VALUE;
        int cleanBaselineSamples;
        double minBaselinePingRttMs = Double.POSITIVE_INFINITY;
        int symPreDropHighSamples;
        double symPreDropBaselinePingMs = Double.NaN;
        long symPreDropStartSeq = -1L;
        long symPreDropStartNano;
        int symCombatDropCount;
        long firstSymCombatDropSeq = -1L;
        long firstSymCombatDropNano = Long.MIN_VALUE;
        long lastSymCombatDropNano = Long.MIN_VALUE;
        long lastReportNanos = Long.MIN_VALUE;

        void reset() {
            loaded = false;
            keepAliveSentNanos.clear();
            ticksSinceLastAck = 0;
            lastTickEndNano = Long.MIN_VALUE;
            latestKeepAliveRttMs = Double.NaN;
            ticksSinceAttack = Long.MAX_VALUE / 4;
            cleanBaselineSamples = 0;
            minBaselinePingRttMs = Double.POSITIVE_INFINITY;
            resetCombatDrops();
            resetSymCombatDrops();
            resetPreDrop();
            resetSymPreDrop();
            resetStreak();
        }

        void resetStreak() {
            inflatedStreak = 0;
            streakStartSeq = -1L;
            inflatedSumMs = 0.0;
        }

        void resetPreDrop() {
            preDropHighSamples = 0;
            preDropBaselinePingMs = Double.NaN;
            preDropStartSeq = -1L;
        }

        void resetSymPreDrop() {
            symPreDropHighSamples = 0;
            symPreDropBaselinePingMs = Double.NaN;
            symPreDropStartSeq = -1L;
        }

        void resetCombatDrops() {
            combatDropCount = 0;
            firstCombatDropSeq = -1L;
            firstCombatDropNano = Long.MIN_VALUE;
            lastCombatDropNano = Long.MIN_VALUE;
        }

        void resetSymCombatDrops() {
            symCombatDropCount = 0;
            firstSymCombatDropSeq = -1L;
            firstSymCombatDropNano = Long.MIN_VALUE;
            lastSymCombatDropNano = Long.MIN_VALUE;
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
                switch (out.packet()) {
                    case ClientboundPacket.Login ignored -> state.reset();
                    case ClientboundPacket.Respawn ignored -> state.reset();
                    case ClientboundPacket.KeepAlive ka -> state.keepAliveSentNanos.put(ka.id(), out.nanoTime());
                    default -> { }
                }
            }
            case TimelineEvent.Inbound in -> {
                switch (in.packet()) {
                    case ServerboundPacket.PlayerLoaded ignored -> state.loaded = true;
                    case ServerboundPacket.ClientTickEnd ignored -> {
                        if (state.lastTickEndNano != Long.MIN_VALUE
                                && in.nanoTime() - state.lastTickEndNano > MAX_TICK_WALL_GAP_NANOS) {
                            // Wall-clock stall (GC pause, alt-tab, or CPU/network burst): clear transient backlog streaks
                            state.resetPreDrop();
                            state.resetSymPreDrop();
                            state.resetStreak();
                        }
                        state.lastTickEndNano = in.nanoTime();
                        state.ticksSinceLastAck++;
                        if (state.ticksSinceAttack < Long.MAX_VALUE / 4) {
                            state.ticksSinceAttack++;
                        }
                    }
                    case ServerboundPacket.InteractEntity interact when interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK ->
                            state.ticksSinceAttack = 0;
                    case ServerboundPacket.KeepAlive ka -> {
                        Long sentNano = state.keepAliveSentNanos.remove(ka.id());
                        if (sentNano != null && in.nanoTime() >= sentNano) {
                            state.latestKeepAliveRttMs = (in.nanoTime() - sentNano) / 1_000_000.0;
                        }
                    }
                    default -> { }
                }
            }
            case TimelineEvent.Acknowledged ack -> onAck(session, state, ack);
            default -> { }
        }
    }

    private void onAck(PlayerSession session, State state, TimelineEvent.Acknowledged ack) {
        if (!state.loaded || Double.isNaN(state.latestKeepAliveRttMs)) {
            state.ticksSinceLastAck = 0;
            return;
        }
        double pingRttMs = (ack.nanoTime() - ack.sentNanoTime()) / 1_000_000.0;
        double diffMs = pingRttMs - state.latestKeepAliveRttMs;
        // Require the client to actually be ticking (not frozen/loading a huge chunk batch)
        boolean activeTicking = state.ticksSinceLastAck >= 1;
        state.ticksSinceLastAck = 0;
        if (activeTicking && pingRttMs > 0.0 && pingRttMs < MAX_KEEPALIVE_FOR_DROP_MS
                && Math.abs(diffMs) <= MAX_POST_DROP_ABOVE_KA_MS) {
            state.cleanBaselineSamples++;
            state.minBaselinePingRttMs = Math.min(state.minBaselinePingRttMs, pingRttMs);
        }
        if (state.preDropHighSamples >= REQUIRED_PRE_ATTACK_INFLATED
                && state.ticksSinceAttack <= ATTACK_DROP_WINDOW_TICKS
                && state.latestKeepAliveRttMs < MAX_KEEPALIVE_FOR_DROP_MS
                && pingRttMs <= state.latestKeepAliveRttMs + MAX_POST_DROP_ABOVE_KA_MS
                && state.preDropBaselinePingMs - pingRttMs >= MIN_COMBAT_DROP_MS) {
            long fromSeq = state.preDropStartSeq;
            long fromNano = state.preDropStartNano;
            double baselinePingMs = state.preDropBaselinePingMs;
            state.resetPreDrop();
            state.resetSymPreDrop();
            state.resetStreak();
            if (state.firstCombatDropNano != Long.MIN_VALUE
                    && ack.nanoTime() - state.firstCombatDropNano > COMBAT_DROP_WINDOW_NANOS) {
                state.resetCombatDrops();
            }
            if (state.lastCombatDropNano == Long.MIN_VALUE
                    || ack.nanoTime() - state.lastCombatDropNano >= MIN_COMBAT_DROP_SEPARATION_NANOS) {
                if (state.combatDropCount == 0) {
                    state.firstCombatDropSeq = fromSeq;
                    state.firstCombatDropNano = fromNano;
                }
                state.combatDropCount++;
                state.lastCombatDropNano = ack.nanoTime();
            }
            if (state.combatDropCount >= REQUIRED_COMBAT_DROPS) {
                long reportFromSeq = state.firstCombatDropSeq;
                long reportFromNano = state.firstCombatDropNano;
                int dropCount = state.combatDropCount;
                state.resetCombatDrops();
                if (state.lastReportNanos == Long.MIN_VALUE
                        || ack.nanoTime() - state.lastReportNanos >= REPORT_INTERVAL_NANOS) {
                    emitCombatDrop(session, state, reportFromSeq, reportFromNano, ack, baselinePingMs, pingRttMs,
                            state.latestKeepAliveRttMs, dropCount, "combat-rtt-drop");
                }
            }
            return;
        }
        if (state.cleanBaselineSamples >= REQUIRED_BASELINE_SAMPLES
                && state.symPreDropHighSamples >= REQUIRED_PRE_ATTACK_INFLATED
                && state.ticksSinceAttack <= ATTACK_DROP_WINDOW_TICKS
                && pingRttMs <= state.minBaselinePingRttMs + MAX_POST_DROP_ABOVE_KA_MS
                && state.symPreDropBaselinePingMs - pingRttMs >= MIN_COMBAT_DROP_MS) {
            long fromSeq = state.symPreDropStartSeq;
            long fromNano = state.symPreDropStartNano;
            double baselinePingMs = state.symPreDropBaselinePingMs;
            state.resetSymPreDrop();
            state.resetPreDrop();
            state.resetStreak();
            if (state.firstSymCombatDropNano != Long.MIN_VALUE
                    && ack.nanoTime() - state.firstSymCombatDropNano > COMBAT_DROP_WINDOW_NANOS) {
                state.resetSymCombatDrops();
            }
            if (state.lastSymCombatDropNano == Long.MIN_VALUE
                    || ack.nanoTime() - state.lastSymCombatDropNano >= MIN_COMBAT_DROP_SEPARATION_NANOS) {
                if (state.symCombatDropCount == 0) {
                    state.firstSymCombatDropSeq = fromSeq;
                    state.firstSymCombatDropNano = fromNano;
                }
                state.symCombatDropCount++;
                state.lastSymCombatDropNano = ack.nanoTime();
            }
            if (state.symCombatDropCount >= REQUIRED_COMBAT_DROPS) {
                long reportFromSeq = state.firstSymCombatDropSeq;
                long reportFromNano = state.firstSymCombatDropNano;
                int dropCount = state.symCombatDropCount;
                state.resetSymCombatDrops();
                if (state.lastReportNanos == Long.MIN_VALUE
                        || ack.nanoTime() - state.lastReportNanos >= REPORT_INTERVAL_NANOS) {
                    emitCombatDrop(session, state, reportFromSeq, reportFromNano, ack, baselinePingMs, pingRttMs,
                            state.latestKeepAliveRttMs, dropCount, "symmetric-combat-rtt-drop");
                }
            }
            return;
        }
        if (activeTicking && state.cleanBaselineSamples >= REQUIRED_BASELINE_SAMPLES
                && pingRttMs >= state.minBaselinePingRttMs + PRE_ATTACK_INFLATION_MS
                && pingRttMs <= 2_500.0) {
            if (state.symPreDropHighSamples == 0) {
                state.symPreDropStartSeq = ack.sequence();
                state.symPreDropStartNano = ack.nanoTime();
                state.symPreDropBaselinePingMs = pingRttMs;
            } else {
                state.symPreDropBaselinePingMs = Math.min(state.symPreDropBaselinePingMs, pingRttMs);
            }
            state.symPreDropHighSamples++;
        } else if (state.cleanBaselineSamples >= REQUIRED_BASELINE_SAMPLES
                && pingRttMs < state.minBaselinePingRttMs + PRE_ATTACK_INFLATION_MS * 0.5) {
            state.resetSymPreDrop();
        }
        if (activeTicking && diffMs >= PRE_ATTACK_INFLATION_MS && pingRttMs <= 2_500.0
                && state.latestKeepAliveRttMs < MAX_KEEPALIVE_FOR_DROP_MS) {
            if (state.preDropHighSamples == 0) {
                state.preDropStartSeq = ack.sequence();
                state.preDropStartNano = ack.nanoTime();
                state.preDropBaselinePingMs = pingRttMs;
            } else {
                state.preDropBaselinePingMs = Math.min(state.preDropBaselinePingMs, pingRttMs);
            }
            state.preDropHighSamples++;
        } else if (diffMs < PRE_ATTACK_INFLATION_MS * 0.5) {
            state.resetPreDrop();
        }
        if (activeTicking && diffMs >= MIN_INFLATION_MS && pingRttMs <= 2_500.0) {
            if (state.inflatedStreak == 0) {
                state.streakStartSeq = ack.sequence();
                state.streakStartNano = ack.nanoTime();
            }
            state.inflatedStreak++;
            state.inflatedSumMs += diffMs;
            if (state.inflatedStreak >= REQUIRED_INFLATED_SAMPLES) {
                double meanDiff = state.inflatedSumMs / state.inflatedStreak;
                long fromSeq = state.streakStartSeq;
                long fromNano = state.streakStartNano;
                state.resetStreak();
                if (state.lastReportNanos == Long.MIN_VALUE
                        || ack.nanoTime() - state.lastReportNanos >= REPORT_INTERVAL_NANOS) {
                    emit(session, state, fromSeq, fromNano, ack, meanDiff, pingRttMs, state.latestKeepAliveRttMs);
                }
            }
        } else {
            state.resetStreak();
        }
    }

    private void emitCombatDrop(PlayerSession session, State state, long fromSeq, long fromNano,
            TimelineEvent.Acknowledged at, double baselinePingMs, double dropPingMs, double kaMs, int dropCount,
            String reason) {
        Admissibility admissibility = state.admissibility.evaluateTranslatorSafe(fromSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("reason", reason);
        details.put("combatDrops", Integer.toString(dropCount));
        details.put("baselinePingMs", String.format(Locale.ROOT, "%.1f", baselinePingMs));
        details.put("droppedPingMs", String.format(Locale.ROOT, "%.1f", dropPingMs));
        details.put("dropDeltaMs", String.format(Locale.ROOT, "%.1f", baselinePingMs - dropPingMs));
        details.put("keepAliveRttMs", String.format(Locale.ROOT, "%.1f", kaMs));
        if (Double.isFinite(state.minBaselinePingRttMs)) {
            details.put("sessionMinPingMs", String.format(Locale.ROOT, "%.1f", state.minBaselinePingRttMs));
        }
        sink.accept(session,
                new Evidence(ID, VERSION, EvidenceClass.NOMINAL_STATISTIC, fromSeq, at.sequence(), fromNano, at.nanoTime(),
                        String.format(Locale.ROOT,
                                "Ping RTT dropped %.1f ms (from %.1f ms to %.1f ms, %dx) at attack while KeepAlive was %.1f ms (%s)",
                                baselinePingMs - dropPingMs, baselinePingMs, dropPingMs, dropCount, kaMs, reason),
                        details, admissibility));
        state.lastReportNanos = at.nanoTime();
    }

    private void emit(
            PlayerSession session,
            State state,
            long fromSeq,
            long fromNano,
            TimelineEvent.Acknowledged at,
            double meanDiffMs,
            double latestPingMs,
            double latestKaMs) {
        Admissibility admissibility = state.admissibility.evaluateTranslatorSafe(fromSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("reason", "sustained-ping-rtt-inflation");
        details.put("meanPingOverKeepAliveMs", String.format(Locale.ROOT, "%.2f", meanDiffMs));
        details.put("latestPingRttMs", String.format(Locale.ROOT, "%.2f", latestPingMs));
        details.put("latestKeepAliveRttMs", String.format(Locale.ROOT, "%.2f", latestKaMs));
        sink.accept(
                session,
                new Evidence(
                        ID,
                        VERSION,
                        EvidenceClass.NOMINAL_STATISTIC,
                        fromSeq,
                        at.sequence(),
                        fromNano,
                        at.nanoTime(),
                        String.format(
                                Locale.ROOT,
                                "Ping RTT persistently %.1f ms slower than KeepAlive RTT (ping=%.1f ms, ka=%.1f ms)",
                                meanDiffMs,
                                latestPingMs,
                                latestKaMs),
                        details,
                        admissibility));
        state.lastReportNanos = at.nanoTime();
    }
}
