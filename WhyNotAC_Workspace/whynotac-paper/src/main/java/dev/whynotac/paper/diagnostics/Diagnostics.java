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
 * individually consistent; a reader may see fields from adjacent events, which is fine for display.
 */
public final class Diagnostics implements TimelineListener {

    /** Latest facts for one connection. */
    public static final class View {
        volatile ServerboundPacket.MovePlayer lastMove;
        volatile ServerboundPacket.MovePlayer lastRotation;
        volatile ServerboundPacket.PlayerInput lastInput = ServerboundPacket.PlayerInput.NONE;
        volatile double lastYawDelta = Double.NaN;
        volatile double lastPitchDelta = Double.NaN;
        volatile double yawInformationLimit = Double.NaN;
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

        /** Exact (zero rounding error) yaw change between the last two rotation packets. */
        public double lastYawDelta() {
            return lastYawDelta;
        }

        public double lastPitchDelta() {
            return lastPitchDelta;
        }

        /** Half the float spacing of the client's yaw: finer structure than this does not exist in the data. */
        public double yawInformationLimit() {
            return yawInformationLimit;
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
        ServerboundPacket.MovePlayer previous = view.lastRotation;
        if (previous != null) {
            view.lastYawDelta = ExactAngles.delta(previous.yaw(), move.yaw());
            view.lastPitchDelta = ExactAngles.delta(previous.pitch(), move.pitch());
            view.yawInformationLimit = ExactAngles.informationLimit(previous.yaw(), move.yaw());
        }
        view.lastRotation = move;
    }
}
