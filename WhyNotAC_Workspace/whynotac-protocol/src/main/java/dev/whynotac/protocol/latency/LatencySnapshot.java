package dev.whynotac.protocol.latency;

/**
 * Immutable latency summary of one connection (milliseconds; NaN until the first sample).
 *
 * @param pending           transactions sent but not yet answered
 * @param oldestPendingMillis age of the oldest unanswered transaction
 * @param skipped           transactions the client never answered although it answered a newer one
 * @param unknown           pongs with a WhyNotAC id that was never sent or already answered
 */
public record LatencySnapshot(
        double lastRttMillis, double smoothedRttMillis, double jitterMillis,
        double minRttMillis, double maxRttMillis,
        int pending, double oldestPendingMillis,
        long sent, long acknowledged, long skipped, long unknown) {

    public static final LatencySnapshot EMPTY =
            new LatencySnapshot(Double.NaN, Double.NaN, 0.0, Double.NaN, Double.NaN, 0, 0.0, 0, 0, 0, 0);
}
