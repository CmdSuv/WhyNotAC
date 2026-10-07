package dev.whynotac.paper.diagnostics;

import dev.whynotac.math.precision.ExactAngles;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Phase 1 observability: a listener that keeps the latest raw facts per session so operators can
 * verify the interceptor in game ({@code /whynotac inspect}). It analyses nothing and judges nobody.
 *
 * <p>Writes happen on the analysis thread; reads on the main thread. Every field is volatile and
 * individually consistent; a reader may see fields from adjacent events, which is fine for counters.
 * Values that must agree with each other (a yaw and its exact delta) are published together as one
 * immutable {@link Rotation}, never as separate fields.
 */
public final class Diagnostics implements TimelineListener {

    /**
     * A rotation packet and the exact (zero rounding error) change from the rotation packet before
     * it; the deltas are NaN for the first one. {@code yawInformationLimit} is half the float spacing
     * of the client's yaw: finer structure than this does not exist in the data.
     */
    public record Rotation(ServerboundPacket.MovePlayer move, double yawDelta, double pitchDelta, double yawInformationLimit) {}

    /** Latest facts for one connection. */
    public static final class View {
        volatile ServerboundPacket.MovePlayer lastMove;
        volatile Rotation lastRotation;
        volatile ServerboundPacket.PlayerInput lastInput = ServerboundPacket.PlayerInput.NONE;
        volatile long inbound;
        volatile long movements;
        volatile long anomalies;
        volatile long gaps;
        volatile TimelineEvent.Anomaly lastAnomaly;

        public ServerboundPacket.MovePlayer lastMove() {
            return lastMove;
        }

        public ServerboundPacket.PlayerInput lastInput() {
            return lastInput;
        }

        /** The last rotation packet with its exact deltas, or null before the first one. */
        public Rotation lastRotation() {
            return lastRotation;
        }

        public long inbound() {
            return inbound;
        }

        public long movements() {
            return movements;
        }

        public long anomalies() {
            return anomalies;
        }

        public long gaps() {
            return gaps;
        }

        public TimelineEvent.Anomaly lastAnomaly() {
            return lastAnomaly;
        }
    }

    /**
     * Totals over every session since the plugin started, including sessions that have ended: what a
     * load test or an operator checks first (an honest population must show no anomalies, no
     * divergences and no lost events).
     */
    public record Totals(long sessions, long ended, long inbound, long outbound, long lostEvents,
                         long inboundDivergences, long outboundDivergences, Map<TimelineEvent.Anomaly.Kind, Long> anomalies) {}

    private final Map<Long, View> views = new ConcurrentHashMap<>();
    private final LongAdder sessions = new LongAdder();
    private final LongAdder ended = new LongAdder();
    private final LongAdder inbound = new LongAdder();
    private final LongAdder outbound = new LongAdder();
    private final LongAdder lostEvents = new LongAdder();
    private final LongAdder inboundDivergences = new LongAdder();
    private final LongAdder outboundDivergences = new LongAdder();
    private final Map<TimelineEvent.Anomaly.Kind, LongAdder> anomalies = new EnumMap<>(TimelineEvent.Anomaly.Kind.class);

    public Diagnostics() {
        for (TimelineEvent.Anomaly.Kind kind : TimelineEvent.Anomaly.Kind.values()) {
            anomalies.put(kind, new LongAdder()); // filled once, never modified: safe to read concurrently
        }
    }

    public Totals totals() {
        Map<TimelineEvent.Anomaly.Kind, Long> byKind = new EnumMap<>(TimelineEvent.Anomaly.Kind.class);
        anomalies.forEach((kind, count) -> byKind.put(kind, count.sum()));
        return new Totals(sessions.sum(), ended.sum(), inbound.sum(), outbound.sum(), lostEvents.sum(),
                inboundDivergences.sum(), outboundDivergences.sum(), byKind);
    }

    public View view(PlayerSession session) {
        return views.get(session.connectionId());
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        View view = views.get(session.connectionId());
        if (view == null) {
            view = new View();
            views.put(session.connectionId(), view);
            sessions.increment();
        }
        switch (event) {
            case TimelineEvent.Inbound inbound -> {
                this.inbound.increment();
                view.inbound++;
                switch (inbound.packet()) {
                    case ServerboundPacket.MovePlayer move -> onMove(view, move);
                    case ServerboundPacket.PlayerInput input -> view.lastInput = input;
                    default -> { }
                }
            }
            case TimelineEvent.Outbound ignored -> outbound.increment();
            case TimelineEvent.Anomaly anomaly -> {
                anomalies.get(anomaly.kind()).increment();
                view.anomalies++;
                view.lastAnomaly = anomaly;
            }
            case TimelineEvent.Gap gap -> {
                lostEvents.add(gap.droppedEvents());
                view.gaps++;
            }
            case TimelineEvent.InboundDivergence ignored -> inboundDivergences.increment();
            case TimelineEvent.OutboundDivergence ignored -> outboundDivergences.increment();
            case TimelineEvent.Disconnected ignored -> {
                views.remove(session.connectionId());
                ended.increment();
            }
            default -> { }
        }
    }

    private static void onMove(View view, ServerboundPacket.MovePlayer move) {
        view.movements++;
        view.lastMove = move;
        if (!move.hasRotation()) {
            return;
        }
        Rotation previous = view.lastRotation;
        view.lastRotation = previous == null
                ? new Rotation(move, Double.NaN, Double.NaN, Double.NaN)
                : new Rotation(move,
                        ExactAngles.delta(previous.move().yaw(), move.yaw()),
                        ExactAngles.delta(previous.move().pitch(), move.pitch()),
                        ExactAngles.informationLimit(previous.move().yaw(), move.yaw()));
    }
}
