package dev.whynotac.math.precision;

/**
 * Neumaier's compensated summation (improved Kahan–Babuška).
 *
 * <p>Naive summation of {@code n} terms can accumulate a relative error of about {@code n * 2^-53}.
 * For a long-running behavioural profile (hundreds of thousands of rotation deltas around 1e-3) that
 * drift reaches the 1e-6 analysis grid. Neumaier's method keeps the running rounding error in a
 * separate term and adds it back at the end, giving an error bound independent of {@code n} (to first
 * order). It also handles the case Kahan's original misses, where a new term is larger than the sum.
 *
 * <p>{@link #addProduct(double, double)} adds the exact error of the product too, so sums of
 * products (dot products, sums of squares) are compensated end to end.
 *
 * <p>Mutable and not thread-safe.
 */
public final class CompensatedSum {

    private double sum;
    private double compensation;
    private long count;

    public CompensatedSum add(double value) {
        double t = sum + value;
        if (Math.abs(sum) >= Math.abs(value)) {
            compensation += (sum - t) + value;
        } else {
            compensation += (value - t) + sum;
        }
        sum = t;
        count++;
        return this;
    }

    /** Adds {@code a * b} including the product's own rounding error. */
    public CompensatedSum addProduct(double a, double b) {
        double product = a * b;
        add(product);
        compensation += ErrorFree.productError(a, b);
        return this;
    }

    /** The compensated total. */
    public double sum() {
        return sum + compensation;
    }

    /** Number of {@code add}/{@code addProduct} calls. */
    public long count() {
        return count;
    }

    public CompensatedSum reset() {
        sum = 0.0;
        compensation = 0.0;
        count = 0;
        return this;
    }
}
