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
 * ({@code ping-spoof}, S, SHADOW).
 *
 * <p>Decompiled 1.21.11 client processes {@code KeepAlive} immediately on the Netty I/O thread while
 * {@code Ping} runs on the main game thread via {@code PacketProcessor}. When a {@code Backtrack} or
 * {@code PingSpoof} cheat delays inbound {@code Ping} / game packets by {@code 180..550 ms} (below
 * {@link TransactionOrderCheck}'s deterministic 12-tick cap) while {@code KeepAlive} responds at
 * real network RTT, {@code rtt_ping - rtt_keepalive} remains persistently inflated across >= 10
 * consecutive active transactions.
 *
 * <p>Uses {@link AdmissibilityTracker#evaluateTranslatorSafe(long)}. Class {@code S}: non-punitive
 * alert and AI triage signal only.
 */
public final class PingSpoofCheck implements TimelineListener {

    public static final String ID = "ping-spoof";
    public static final int VERSION = 1;
    static final int REQUIRED_INFLATED_SAMPLES = 10;
    static final double MIN_INFLATION_MS = 180.0;
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
        double latestKeepAliveRttMs = Double.NaN;
        int inflatedStreak;
        long streakStartSeq = -1L;
        long streakStartNano;
        double inflatedSumMs;
        long lastReportNanos = Long.MIN_VALUE;

        void reset() {
            loaded = false;
            keepAliveSentNanos.clear();
            ticksSinceLastAck = 0;
            latestKeepAliveRttMs = Double.NaN;
            resetStreak();
        }

        void resetStreak() {
            inflatedStreak = 0;
            streakStartSeq = -1L;
            inflatedSumMs = 0.0;
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
                    case ServerboundPacket.ClientTickEnd ignored -> state.ticksSinceLastAck++;
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
