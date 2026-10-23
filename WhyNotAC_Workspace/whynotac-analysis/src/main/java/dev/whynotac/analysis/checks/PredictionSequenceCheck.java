package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prediction sequence (W3, {@code prediction-sequence}, P-M): block actions numbered otherwise than the vanilla client
 * numbers them.
 *
 * <p>Decompiled 1.21.11: every packet the client predicts a block change for goes through
 * {@code MultiPlayerGameMode.startPrediction}, which takes the next number of the level's
 * {@code BlockStatePredictionHandler} ({@code currentSequenceNr++}) and sends it in the packet: {@code START_DESTROY_BLOCK}
 * (survival and creative), {@code STOP_DESTROY_BLOCK}, {@code UseItemOn} and {@code UseItem}. Nothing else touches the
 * number, and each {@code ClientLevel} starts it at 0 (a new one at every login and at a respawn into another dimension:
 * {@code ClientSelf.levels}). Every other player action ({@code ABORT_DESTROY_BLOCK}, dropping, swapping, releasing the
 * used item, the spear's stab) is built without one and carries 0. So a predicted packet carries the number after the
 * previous one's, or 1 if the client may have made a new level since; anything else, such as a packet a module built and
 * sent itself, is not the vanilla client's.
 *
 * <p>Which level the client was in is followed as a range of how many it may have made: the next number in the same
 * level is possible only while the range at the previous packet can still hold now, a 1 in a new level only if it may
 * have made one since. The first predicted packet of a session, and the first after lost events or a packet WhyNotAC
 * cannot read, starts the count over (only a number below 1 is claimed there: no level numbers so). At most one finding
 * per {@link #REPORT_INTERVAL_NANOS} per session; the next one counts what came between.
 */
public final class PredictionSequenceCheck implements TimelineListener {

    public static final String ID = "prediction-sequence";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public PredictionSequenceCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        /** False until the first predicted packet, and after anything lost: the next one starts the count over. */
        boolean followed;
        /** The last predicted packet's number, where it was, and how many levels the client may have made by it. */
        int last;
        long lastSequence = Long.MIN_VALUE;
        long lastNanos;
        long levelsLow;
        long levelsHigh;
        long lastReportNanos = Long.MIN_VALUE;
        long sinceReport;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> state.followed = false;
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.UseItemOn use -> predicted(session, state, in, use.sequence(), "UseItemOn");
            case ServerboundPacket.UseItem use -> predicted(session, state, in, use.sequence(), "UseItem");
            case ServerboundPacket.PlayerAction action -> {
                switch (action.action()) {
                    case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> predicted(session, state, in, action.sequence(), action.action().name());
                    case UNKNOWN -> state.followed = false; // an action this build does not know: it may have been predicted
                    default -> {
                        if (action.sequence() != 0) {
                            report(session, state, in, in.sequence(), in.nanoTime(), action.action().name(), action.sequence(), "0", "unpredicted");
                        }
                    }
                }
            }
            case ServerboundPacket.Untranslatable ignored -> state.followed = false; // it may have been a predicted packet
            default -> { }
        }
    }

    private void predicted(PlayerSession session, State state, TimelineEvent.Inbound in, int sequence, String packet) {
        long[] now = replicas.self().self(session).levels();
        if (!state.followed) {
            if (sequence < 1) {
                report(session, state, in, in.sequence(), in.nanoTime(), packet, sequence, "1 or more", "any level");
            }
            follow(state, in, sequence, now[0], now[1]); // the count starts over here
            return;
        }
        // The same level: the levels made by the previous packet may still be all now. A new one: one more may have been made.
        boolean same = now[0] <= state.levelsHigh;
        boolean fresh = now[1] > state.levelsLow;
        boolean next = same && sequence == state.last + 1;
        boolean restart = fresh && sequence == 1;
        if (!next && !restart) {
            String expected = same && fresh ? (state.last + 1) + " or 1" : same ? Integer.toString(state.last + 1) : "1";
            report(session, state, in, state.lastSequence, state.lastNanos, packet, sequence, expected, same ? "same level" : "new level");
            follow(state, in, sequence, now[0], now[1]); // go on from what it sent
            return;
        }
        // the range now: the same level's (both ranges hold), a new level's (above the previous one's low end), or either
        long low = restart && !next ? Math.max(now[0], state.levelsLow + 1) : Math.max(now[0], state.levelsLow);
        long high = next && !restart ? state.levelsHigh : now[1];
        follow(state, in, sequence, low, high);
    }

    private static void follow(State state, TimelineEvent.Inbound in, int sequence, long low, long high) {
        state.followed = true;
        state.last = sequence;
        state.lastSequence = in.sequence();
        state.lastNanos = in.nanoTime();
        state.levelsLow = low;
        state.levelsHigh = high;
    }

    /** A finding over {@code from} (the previous predicted packet, or the packet itself) to {@code at}. */
    private void report(PlayerSession session, State state, TimelineEvent.Inbound at, long from, long fromNanos, String packet, int sequence,
            String expected, String level) {
        state.sinceReport++;
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        state.lastReportNanos = at.nanoTime();
        Admissibility admissibility = state.admissibility.evaluate(from);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("packet", packet);
        details.put("sequence", Integer.toString(sequence));
        details.put("expected", expected);
        details.put("previous", state.followed ? Integer.toString(state.last) : "-");
        details.put("level", level);
        details.put("misnumbered", Long.toString(state.sinceReport));
        state.sinceReport = 0;
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, from, at.sequence(), fromNanos, at.nanoTime(), packet + " numbered " + sequence + " where the vanilla client numbers " + expected, details, admissibility));
    }
}
