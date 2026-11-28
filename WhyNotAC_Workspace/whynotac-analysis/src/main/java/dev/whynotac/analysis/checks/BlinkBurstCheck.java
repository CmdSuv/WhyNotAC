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
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Combat &amp; block-placement PulseBlink / FakeLag burst correlation ({@code blink-burst}, S,
 * {@code CLOCK_AND_FRAMING}, translator-safe): distinguishes deliberate outbound packet buffering
 * (PulseBlink / FakeLag) from benign ISP/GC freezes by requiring repeated silence-to-flush bursts
 * ({@code >= 350 ms} silence followed by {@code >= 6} ticks flushed in {@code <= 20 ms} wall clock)
 * specifically correlated within {@code <= 3} ticks of {@code InteractEntity(ATTACK)} or
 * {@code UseItemOn} across {@code >= 3} cycles within 60 seconds.
 *
 * <p>Class S (nominal statistic): never triggers automated bans, kicks, or mitigations ({@code docs/POLICY.md}).
 */
public final class BlinkBurstCheck implements TimelineListener {

    public static final String ID = "blink-burst";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final long MIN_SILENCE_NANOS = 350_000_000L;
    static final long MAX_BURST_FLUSH_NANOS = 20_000_000L;
    static final int MIN_BURST_TICKS = 6;
    static final int MIN_BURST_MOVE_TICKS = 4;
    static final int ACTION_CORRELATION_TICKS = 3;
    static final long CYCLE_WINDOW_NANOS = 60_000_000_000L;
    static final int MIN_CYCLES = 3;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;

    public BlinkBurstCheck(EvidenceSink sink) {
        this.sink = sink;
    }

    private record BurstCycle(long startSeq, long startNano, long endNano, double silenceMs, int burstTicks, String actionType) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        boolean loaded;
        long lastReportNanos = Long.MIN_VALUE;
        long tickNumber;
        long prevTickNano = Long.MIN_VALUE;

        // Current tick flags
        boolean sawMoveThisTick;
        String actionThisTick; // "attack", "block-place", or null

        // Active burst flush tracking
        boolean inBurst;
        long burstStartSeq;
        long burstStartNano;
        long burstSilenceNanos;
        int burstTicks;
        int burstMoveTicks;
        String burstActionType;

        // Post-flush action correlation window (<= 3 ticks after flush finishes)
        boolean awaitingPostAction;
        long pendingStartSeq;
        long pendingStartNano;
        long pendingEndNano;
        long pendingSilenceNanos;
        int pendingBurstTicks;
        int postActionTicksLeft;

        final ArrayDeque<BurstCycle> cycles = new ArrayDeque<>();

        void resetBurst() {
            inBurst = false;
            awaitingPostAction = false;
            sawMoveThisTick = false;
            actionThisTick = null;
            prevTickNano = Long.MIN_VALUE;
        }

        void resetAll() {
            loaded = false;
            resetBurst();
            cycles.clear();
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
            case TimelineEvent.Gap ignored -> state.resetBurst();
            case TimelineEvent.Outbound out -> {
                if (out.packet() instanceof ClientboundPacket.Login || out.packet() instanceof ClientboundPacket.Respawn) {
                    state.resetAll();
                } else if (out.packet() instanceof ClientboundPacket.PlayerPosition) {
                    state.resetBurst();
                }
            }
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerLoaded ignored -> {
                state.loaded = true;
                state.resetBurst();
            }
            case ServerboundPacket.AcceptTeleportation ignored -> state.resetBurst();
            case ServerboundPacket.MovePlayer move -> {
                if (move.hasPosition() && !move.hasNonFiniteValue()) {
                    state.sawMoveThisTick = true;
                }
            }
            case ServerboundPacket.InteractEntity interact
                    when interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK -> state.actionThisTick = "attack";
            case ServerboundPacket.UseItemOn ignored -> {
                if (state.actionThisTick == null) {
                    state.actionThisTick = "block-place";
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> onTickEnd(session, state, in);
            default -> { }
        }
    }

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        state.tickNumber++;
        boolean sawMove = state.sawMoveThisTick;
        String action = state.actionThisTick;
        state.sawMoveThisTick = false;
        state.actionThisTick = null;

        if (!state.loaded) {
            return;
        }
        long now = tickEnd.nanoTime();
        long prev = state.prevTickNano;
        state.prevTickNano = now;

        if (state.awaitingPostAction) {
            if (action != null) {
                state.awaitingPostAction = false;
                recordCorrelatedCycle(session, state, tickEnd,
                        new BurstCycle(state.pendingStartSeq, state.pendingStartNano, state.pendingEndNano,
                                state.pendingSilenceNanos / 1_000_000.0, state.pendingBurstTicks, action));
            } else if (--state.postActionTicksLeft <= 0) {
                state.awaitingPostAction = false;
            }
        }

        if (prev == Long.MIN_VALUE) {
            return;
        }
        long gap = now - prev;

        if (state.inBurst) {
            if (now - state.burstStartNano <= MAX_BURST_FLUSH_NANOS && gap >= 0L) {
                state.burstTicks++;
                if (sawMove) {
                    state.burstMoveTicks++;
                }
                if (action != null && state.burstActionType == null) {
                    state.burstActionType = action;
                }
                return;
            }
            // Burst flush window ended on the previous tick; evaluate it now
            finishBurst(session, state, tickEnd, action);
        }

        if (gap >= MIN_SILENCE_NANOS) {
            state.inBurst = true;
            state.burstStartSeq = tickEnd.sequence();
            state.burstStartNano = now;
            state.burstSilenceNanos = gap;
            state.burstTicks = 1;
            state.burstMoveTicks = sawMove ? 1 : 0;
            state.burstActionType = action;
        }
    }

    private void finishBurst(PlayerSession session, State state, TimelineEvent.Inbound currentTickEnd, String currentAction) {
        state.inBurst = false;
        if (state.burstTicks < MIN_BURST_TICKS || state.burstMoveTicks < MIN_BURST_MOVE_TICKS) {
            return;
        }
        String correlatedAction = state.burstActionType != null ? state.burstActionType : currentAction;
        if (correlatedAction != null) {
            recordCorrelatedCycle(session, state, currentTickEnd,
                    new BurstCycle(state.burstStartSeq, state.burstStartNano, currentTickEnd.nanoTime(),
                            state.burstSilenceNanos / 1_000_000.0, state.burstTicks, correlatedAction));
        } else {
            state.awaitingPostAction = true;
            state.pendingStartSeq = state.burstStartSeq;
            state.pendingStartNano = state.burstStartNano;
            state.pendingEndNano = currentTickEnd.nanoTime();
            state.pendingSilenceNanos = state.burstSilenceNanos;
            state.pendingBurstTicks = state.burstTicks;
            state.postActionTicksLeft = ACTION_CORRELATION_TICKS;
        }
    }

    private void recordCorrelatedCycle(PlayerSession session, State state, TimelineEvent.Inbound at, BurstCycle cycle) {
        state.cycles.addLast(cycle);
        while (!state.cycles.isEmpty() && at.nanoTime() - state.cycles.peekFirst().endNano() > CYCLE_WINDOW_NANOS) {
            state.cycles.pollFirst();
        }
        if (state.cycles.size() < MIN_CYCLES) {
            return;
        }
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        BurstCycle oldest = state.cycles.peekFirst();
        Admissibility admissibility = state.admissibility.evaluateTranslatorSafe(oldest.startSeq());
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            state.cycles.clear();
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("reason", "combat-blink-burst");
        details.put("cycles", Integer.toString(state.cycles.size()));
        details.put("lastSilenceMs", String.format(Locale.ROOT, "%.1f", cycle.silenceMs()));
        details.put("lastBurstTicks", Integer.toString(cycle.burstTicks()));
        details.put("actionType", cycle.actionType());
        sink.accept(session, new Evidence(
                ID, VERSION, EvidenceClass.NOMINAL_STATISTIC,
                oldest.startSeq(), at.sequence(), oldest.startNano(), at.nanoTime(),
                String.format(Locale.ROOT, "%d combat/block blink bursts within 60s (last: %.0f ms silence -> %d ticks, %s)",
                        state.cycles.size(), cycle.silenceMs(), cycle.burstTicks(), cycle.actionType()),
                details, admissibility));
        state.lastReportNanos = at.nanoTime();
        state.cycles.clear();
    }
}
