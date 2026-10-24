package dev.whynotac.math.precision;

/**
 * Error-free transformations: each one returns a floating-point result <em>and</em> the exact
 * rounding error of that result, so {@code hi + lo} equals the mathematically exact value with no
 * loss at all.
 *
 * <ul>
 *   <li>{@link #twoSum}: Knuth's branch-free TwoSum, exact for any finite inputs.
 *   <li>{@link #twoProduct}: uses {@link Math#fma}, whose single rounding makes
 *       {@code fma(a, b, -fl(a*b))} the exact product error (barring underflow).
 * </ul>
 *
 * <p>Typical use: proving that a replicated computation produced its result without hidden
 * rounding (error term {@code == 0}), and building compensated accumulators.
 */
public final class ErrorFree {

    private ErrorFree() {}

    /** An unevaluated sum {@code hi + lo} with {@code |lo| <= ulp(hi) / 2}. */
    public record Pair(double hi, double lo) {
        public boolean isExact() {
            return lo == 0.0;
        }
    }

    /** Knuth TwoSum: {@code hi = fl(a + b)}, {@code lo = (a + b) - hi} exactly. */
    public static Pair twoSum(double a, double b) {
        double hi = a + b;
        double bVirtual = hi - a;
        double aVirtual = hi - bVirtual;
        double lo = (a - aVirtual) + (b - bVirtual);
        return new Pair(hi, lo);
    }

    /** Error term of {@code a + b} only (allocation-free form of {@link #twoSum}). */
    public static double sumError(double a, double b) {
        double hi = a + b;
        double bVirtual = hi - a;
        double aVirtual = hi - bVirtual;
        return (a - aVirtual) + (b - bVirtual);
    }

    /** {@code hi = fl(a * b)}, {@code lo = a * b - hi} exactly (via a single-rounding FMA). */
    public static Pair twoProduct(double a, double b) {
        double hi = a * b;
        double lo = Math.fma(a, b, -hi);
        return new Pair(hi, lo);
    }

    /** Error term of {@code a * b} only. */
    public static double productError(double a, double b) {
        return Math.fma(a, b, -(a * b));
    }
}
