package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.evidence.ServerClock;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client clock (P-M): the client cannot run more ticks than its own clock allows.
 *
 * <p>Soundness (decompiled 1.21.11 client). A frame of {@code Minecraft.runTick(true)}:
 * <ol>
 *   <li>{@code DeltaTracker.advanceTime} at time {@code A}: adds the elapsed {@code Util.getMillis()}
 *       ({@code System.nanoTime}) divided by {@code max(50 ms, server mspt)} to a fractional residual and
 *       takes the whole part {@code m}, so the server can slow the client's ticks but never speed them up;</li>
 *   <li>{@code PacketProcessor.processQueuedPackets}: handles queued packets until the queue is empty,
 *       including packets that arrive meanwhile. Pongs are sent only here ({@code handlePing} runs on the
 *       main thread through this queue);</li>
 *   <li>{@code min(10, m)} ticks, each ending with one {@code ServerboundClientTickEnd} (only place it
 *       is sent); the rest of {@code m} is dropped.</li>
 * </ol>
 * The drain can be long (a burst of chunks on a slow machine) and the ticks after it were counted before
 * it. So after the answer to transaction {@code i}, sent at client time {@code c_i} inside the drain of
 * frame {@code k}: frame {@code k}'s ticks (at most 10, counted before {@code c_i}), frame {@code k+1}'s
 * ticks (at most 10, they cover the drain time before {@code c_i}), then frames {@code k+2..} whose
 * whole ticks sum to less than {@code (A - A_{k+1}) / 50 ms + 1} with {@code A_{k+1} > c_i}. Before the
 * answer to {@code j}: fewer than {@code 21 + (c_j - c_i) / 50 ms} ClientTickEnd. One more tick covers
 * whole-millisecond timestamps and float rounding: {@link #SLACK_TICKS} = 22.
 *
 * <p>The client's span is bounded by causality alone, on the server's clock: it answered transaction
 * {@code i} no earlier than the server sent it ({@code s_i}) and answered {@code j} no later than the
 * server received the answer ({@code r_j}). So the ClientTickEnd between the two answers are at most
 * {@code (r_j - s_i) * (1 + drift) / 50 ms + 22}, with {@code drift} = 1% (the client's clock rate may
 * differ from the server's by that much: 100 times a normal quartz error). It holds whoever answers the
 * transactions, so it also holds through a translator.
 * The best pair is found in O(1) per answer: a violation exists iff
 * {@code K_j - f r_j - 22 > min_i (K_i - f s_i)}, {@code K} counting ClientTickEnd before an answer.
 *
 * <p>A client that sends extra ClientTickEnd (to hide extra movement packets from {@link TickFramingCheck},
 * or as a "timer") exceeds the bound once its extra ticks pass 22 (doubled ticks: about 1.1 s plus the
 * round trip; 5% faster: about half a minute). Assumption, stated in every finding: the client's
 * monotonic clock runs within 1% of real time (an OS-level speed hack breaks it on purpose).
 */
public final class ClientClockCheck implements TimelineListener {

    public static final String ID = "client-clock";
    public static final int VERSION = 1;
    /** Relative clock rate difference allowed between client and server. */
    static final double DRIFT = 0.01;
    /**
     * Whole ticks of slack: the up to 10 ticks of the answering frame and the up to 10 of the next one, both
     * counted before the answer; a partial tick; whole-millisecond timestamps and float rounding.
     */
    static final double SLACK_TICKS = 22.0;
    private static final double TICKS_PER_NANO = (1.0 + DRIFT) / 50_000_000.0;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final ServerClock clock;

    public ClientClockCheck(EvidenceSink sink) {
        this(sink, ServerClock.SERVER);
    }

    /** {@code clock}: whether the server's own clock may be relied on ({@code VAL-3}). */
    public ClientClockCheck(EvidenceSink sink, ServerClock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        long tickEnds;
        /** min over answered transactions i of (K_i - f s_i), and where it was reached. */
        double bestAnchor = Double.POSITIVE_INFINITY;
        long anchorSequence;
        long anchorTicks;
        long anchorSentNanos;
    }

    /** It reads no entity motion: the entity replica follows the entities. */
    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Inbound in when in.packet() instanceof ServerboundPacket.ClientTickEnd -> state.tickEnds++;
            case TimelineEvent.Acknowledged ack -> onAnswer(session, state, ack);
            default -> { }
        }
    }

    private void onAnswer(PlayerSession session, State state, TimelineEvent.Acknowledged ack) {
        long ticks = state.tickEnds;
        if (state.bestAnchor != Double.POSITIVE_INFINITY) {
            double allowed = (ack.nanoTime() - state.anchorSentNanos) * TICKS_PER_NANO + SLACK_TICKS;
            if (ticks - state.anchorTicks <= allowed) {
                sink.margin(session, ID, allowed - (ticks - state.anchorTicks)); // client ticks
            }
            if (ticks - state.anchorTicks > allowed) {
                Admissibility admissibility = state.admissibility.evaluate(state.anchorSequence);
                if (admissibility.admissible() && !clock.trusted()) {
                    admissibility = new Admissibility(false, List.of(String.format(Locale.ROOT,
                            "the server's monotonic clock runs %.3f%% off its wall clock", clock.divergence() * 100)), admissibility.identity());
                }
                if (admissibility.admissible()) {
                    long spanMillis = (ack.nanoTime() - state.anchorSentNanos) / 1_000_000;
                    sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, state.anchorSequence, ack.sequence(),
                            state.anchorSentNanos, ack.nanoTime(),
                            (ticks - state.anchorTicks) + " client ticks within " + spanMillis + " ms (at most " + (long) Math.floor(allowed) + " possible)",
                            Map.of("ticks", Long.toString(ticks - state.anchorTicks), "allowedTicks", Long.toString((long) Math.floor(allowed)),
                                    "spanMillis", Long.toString(spanMillis), "assumption", "the client's monotonic clock runs within 1% of real time"),
                            admissibility));
                } else {
                    sink.rejected(session, ID, admissibility);
                }
                state.bestAnchor = Double.POSITIVE_INFINITY; // a fresh window for the next finding
            }
        }
        // This answer is also an anchor for later ones: K_j - f s_j.
        double anchor = ticks - ack.sentNanoTime() * TICKS_PER_NANO;
        if (anchor < state.bestAnchor) {
            state.bestAnchor = anchor;
            state.anchorSequence = ack.sequence();
            state.anchorTicks = ticks;
            state.anchorSentNanos = ack.sentNanoTime();
        }
    }
}
