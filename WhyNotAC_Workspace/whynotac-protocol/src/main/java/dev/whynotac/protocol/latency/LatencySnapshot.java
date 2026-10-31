package dev.whynotac.protocol.latency;

/**
 * Immutable latency summary of one connection (milliseconds; NaN until the first sample).
 *
 * @param pending             transactions sent but not yet answered
 * @param oldestPendingMillis age of the oldest unanswered transaction
 * @param skipped             transactions the client never answered although it answered a newer one
 * @param duplicates          transactions answered twice, or after a newer one
 * @param lostBeforeWire      allocated transactions whose ping never left the server (not the client's doing)
 * @param withheld            the client has stopped answering (pending limit reached)
 */
public record LatencySnapshot(
        double lastRttMillis, double smoothedRttMillis, double jitterMillis,
        double minRttMillis, double maxRttMillis,
        int pending, double oldestPendingMillis,
        long sent, long acknowledged, long skipped, long duplicates, long lostBeforeWire, boolean withheld) {

    public static final LatencySnapshot EMPTY =
            new LatencySnapshot(Double.NaN, Double.NaN, 0.0, Double.NaN, Double.NaN, 0, 0.0, 0, 0, 0, 0, 0, false);
}
