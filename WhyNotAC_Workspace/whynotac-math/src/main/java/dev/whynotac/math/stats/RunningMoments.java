package dev.whynotac.math.stats;

/**
 * Single-pass, numerically stable mean / variance / skewness / kurtosis (Welford, extended to
 * the 3rd and 4th central moments by Terriberry and Pébay) with exact parallel merging (Chan et al.).
 *
 * <p>The textbook formula {@code E[x^2] - E[x]^2} subtracts two nearly equal large numbers and loses
 * everything when the spread is small compared with the mean: a click interval of 50 ms with 0.001 ms
 * jitter, or coordinates near 10^7 moving by 10^-6. Plain Welford fixes the subtraction but still
 * stores the running mean at the data's magnitude, where a double only resolves {@code ulp(mean)}
 * (1.9e-9 at 10^7), which costs about 1e-5 relative accuracy in that regime.
 *
 * <p>This implementation is <b>shifted</b>: the first sample becomes a reference point {@code K} and
 * all moments are accumulated over {@code x - K}. For data clustered around {@code K} that
 * subtraction is exact (Sterbenz's lemma: {@code y/2 <= x <= 2y} implies {@code x - y} is
 * representable), so the deviations entering the update carry no error at all.
 *
 * <p>{@link #merge} combines two accumulators as if all samples had been fed to one, which lets
 * per-session statistics roll up into a long-term player profile without storing raw samples.
 *
 * <p>Mutable and not thread-safe.
 */
public final class RunningMoments {

    private long count;
    private double shift;
    /** Mean of {@code x - shift}. */
    private double mean;
    private double m2;
    private double m3;
    private double m4;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    public RunningMoments add(double x) {
        if (count == 0) {
            shift = x;
        }
        long n1 = count;
        count++;
        double n = count;
        double delta = (x - shift) - mean;
        double deltaN = delta / n;
        double deltaN2 = deltaN * deltaN;
        double term1 = delta * deltaN * n1;
        mean += deltaN;
        m4 += term1 * deltaN2 * (n * n - 3 * n + 3) + 6 * deltaN2 * m2 - 4 * deltaN * m3;
        m3 += term1 * deltaN * (n - 2) - 3 * deltaN * m2;
        m2 += term1;
        if (x < min) {
            min = x;
        }
        if (x > max) {
            max = x;
        }
        return this;
    }

    /** Folds {@code other} into this accumulator (keeping this accumulator's shift). {@code other} is not modified. */
    public RunningMoments merge(RunningMoments other) {
        if (other.count == 0) {
            return this;
        }
        if (count == 0) {
            count = other.count;
            shift = other.shift;
            mean = other.mean;
            m2 = other.m2;
            m3 = other.m3;
            m4 = other.m4;
            min = other.min;
            max = other.max;
            return this;
        }
        double na = count;
        double nb = other.count;
        double n = na + nb;
        // Difference of the two true means, (Kb + mb) - (Ka + ma), grouped so close shifts cancel exactly.
        double delta = (other.shift - shift) + (other.mean - mean);
        double delta2 = delta * delta;
        double delta3 = delta2 * delta;
        double delta4 = delta2 * delta2;

        double newMean = mean + delta * nb / n;
        double newM2 = m2 + other.m2 + delta2 * na * nb / n;
        double newM3 = m3 + other.m3
                + delta3 * na * nb * (na - nb) / (n * n)
                + 3.0 * delta * (na * other.m2 - nb * m2) / n;
        double newM4 = m4 + other.m4
                + delta4 * na * nb * (na * na - na * nb + nb * nb) / (n * n * n)
                + 6.0 * delta2 * (na * na * other.m2 + nb * nb * m2) / (n * n)
                + 4.0 * delta * (na * other.m3 - nb * m3) / n;

        count += other.count;
        mean = newMean;
        m2 = newM2;
        m3 = newM3;
        m4 = newM4;
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
        return this;
    }

    public long count() {
        return count;
    }

    public double mean() {
        return count == 0 ? Double.NaN : shift + mean;
    }

    /** Population variance {@code M2 / n}. */
    public double populationVariance() {
        return count == 0 ? Double.NaN : m2 / count;
    }

    /** Unbiased sample variance {@code M2 / (n - 1)}. */
    public double sampleVariance() {
        return count < 2 ? Double.NaN : m2 / (count - 1);
    }

    public double sampleStdDev() {
        return Math.sqrt(sampleVariance());
    }

    /** Population skewness {@code sqrt(n) * M3 / M2^1.5}; NaN when undefined (fewer than 2 samples or zero spread). */
    public double skewness() {
        if (count < 2 || m2 == 0.0) {
            return Double.NaN;
        }
        return Math.sqrt((double) count) * m3 / Math.pow(m2, 1.5);
    }

    /** Population excess kurtosis {@code n * M4 / M2^2 - 3}; NaN when undefined. */
    public double excessKurtosis() {
        if (count < 2 || m2 == 0.0) {
            return Double.NaN;
        }
        return count * m4 / (m2 * m2) - 3.0;
    }

    public double min() {
        return count == 0 ? Double.NaN : min;
    }

    public double max() {
        return count == 0 ? Double.NaN : max;
    }

    public RunningMoments copy() {
        return new RunningMoments().merge(this);
    }
}
