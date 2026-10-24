package dev.whynotac.protocol.latency;

/**
 * Round-trip-time estimator over transaction samples.
 *
 * <ul>
 *   <li><b>Smoothed RTT</b>: TCP's estimator (RFC 6298), {@code srtt += (sample - srtt) / 8}.
 *   <li><b>Jitter</b>: RTP's interarrival jitter (RFC 3550 §6.4.1) applied to consecutive RTT
 *       samples, {@code J += (|D| - J) / 16} with {@code D} the change between samples. It reacts to
 *       spikes within a few samples yet stays stable on a steady link, which is what lag
 *       compensation needs to size its uncertainty window.
 * </ul>
 * Exact per-sample values stay available to the analysis layer through the timeline
 * ({@code Acknowledged} events); this class only feeds live summaries.
 */
final class LatencyEstimator {

    private static final double NANOS_PER_MILLI = 1.0e6;

    private boolean initialised;
    private double lastMillis = Double.NaN;
    private double smoothedMillis = Double.NaN;
    private double jitterMillis;
    private double minMillis = Double.POSITIVE_INFINITY;
    private double maxMillis = Double.NEGATIVE_INFINITY;

    void addSample(long rttNanos) {
        double millis = rttNanos / NANOS_PER_MILLI;
        if (!initialised) {
            smoothedMillis = millis;
            initialised = true;
        } else {
            smoothedMillis += (millis - smoothedMillis) / 8.0;
            jitterMillis += (Math.abs(millis - lastMillis) - jitterMillis) / 16.0;
        }
        lastMillis = millis;
        minMillis = Math.min(minMillis, millis);
        maxMillis = Math.max(maxMillis, millis);
    }

    double lastMillis() {
        return lastMillis;
    }

    double smoothedMillis() {
        return smoothedMillis;
    }

    double jitterMillis() {
        return jitterMillis;
    }

    double minMillis() {
        return initialised ? minMillis : Double.NaN;
    }

    double maxMillis() {
        return initialised ? maxMillis : Double.NaN;
    }
}
