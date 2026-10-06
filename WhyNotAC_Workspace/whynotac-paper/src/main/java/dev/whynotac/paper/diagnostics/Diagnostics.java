package dev.whynotac.paper.diagnostics;

import dev.whynotac.math.precision.ExactAngles;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<Long, View> views = new ConcurrentHashMap<>();

    public View view(PlayerSession session) {
        return views.get(session.connectionId());
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        View view = views.computeIfAbsent(session.connectionId(), id -> new View());
        switch (event) {
            case TimelineEvent.Inbound inbound -> {
                view.inbound++;
                switch (inbound.packet()) {
                    case ServerboundPacket.MovePlayer move -> onMove(view, move);
                    case ServerboundPacket.PlayerInput input -> view.lastInput = input;
                    default -> { }
                }
            }
            case TimelineEvent.Anomaly anomaly -> {
                view.anomalies++;
                view.lastAnomaly = anomaly;
            }
            case TimelineEvent.Gap ignored -> view.gaps++;
            case TimelineEvent.Disconnected ignored -> views.remove(session.connectionId());
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
