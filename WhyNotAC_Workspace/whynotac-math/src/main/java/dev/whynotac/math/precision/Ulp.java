package dev.whynotac.math.precision;

/**
 * Units-in-the-last-place arithmetic.
 *
 * <p>A fixed absolute epsilon like {@code 0.000001} means different things at different magnitudes:
 * a double coordinate at x = 30 000 000 (the world border) still resolves 3.7e-9, while a float yaw of
 * 10 000 degrees only resolves 9.8e-4. Comparing in ULPs measures "how many representable values
 * apart" two numbers are, which is the right question when checking replicated IEEE arithmetic.
 */
public final class Ulp {

    private Ulp() {}

    /**
     * Number of representable floats strictly between {@code a} and {@code b}, plus one (0 if they are
     * the same value, 1 if adjacent). {@code -0.0} and {@code 0.0} are treated as equal. Returns
     * {@link Long#MAX_VALUE} if either argument is NaN.
     */
    public static long distance(float a, float b) {
        if (Float.isNaN(a) || Float.isNaN(b)) {
            return Long.MAX_VALUE;
        }
        return Math.abs((long) ordered(a) - (long) ordered(b));
    }

    /**
     * Double version of {@link #distance(float, float)}. Saturates at {@link Long#MAX_VALUE} for
     * distances that do not fit in a long (only possible across sign with huge magnitudes).
     */
    public static long distance(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Long.MAX_VALUE;
        }
        long oa = ordered(a);
        long ob = ordered(b);
        long diff = oa - ob;
        // Overflow iff the operands have different signs and the result's sign differs from oa's.
        if (((oa ^ ob) & (oa ^ diff)) < 0) {
            return Long.MAX_VALUE;
        }
        return Math.abs(diff);
    }

    /** Maps float bit patterns onto a monotonically increasing integer line ({@code -0.0 -> 0}). */
    static int ordered(float f) {
        int bits = Float.floatToIntBits(f);
        return bits < 0 ? Integer.MIN_VALUE - bits : bits;
    }

    /** Maps double bit patterns onto a monotonically increasing integer line ({@code -0.0 -> 0}). */
    static long ordered(double d) {
        long bits = Double.doubleToLongBits(d);
        return bits < 0 ? Long.MIN_VALUE - bits : bits;
    }

    /**
     * Resolution of a float32 value at the given magnitude: the gap between {@code |value|} and the
     * next float above it. This is the information limit of anything the client sends as a float
     * (yaw, pitch): no analysis can see structure finer than this.
     */
    public static double floatResolution(float value) {
        return Math.ulp(value);
    }

    /** Resolution of a float64 value at the given magnitude (gap to the next double above {@code |value|}). */
    public static double doubleResolution(double value) {
        return Math.ulp(value);
    }
}
