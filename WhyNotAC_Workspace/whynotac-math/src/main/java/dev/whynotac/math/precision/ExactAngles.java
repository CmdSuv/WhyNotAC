package dev.whynotac.math.precision;

/**
 * Rotation differences with zero arithmetic error.
 *
 * <p>The client sends yaw and pitch as float32, and yaw is unbounded (it keeps growing as the player
 * spins). Subtracting two such floats <em>in float</em> rounds the result. Subtracting them in double
 * does not: two floats whose exponents differ by at most 29 have an exact difference that fits in a
 * 53-bit significand (24 + 29 bits). Beyond that gap the smaller operand is below 2^-29 of the larger
 * one, which no real rotation pair reaches. {@code double % double} (IEEE fmod) is always exact, so
 * {@link #wrappedDelta} is exact too.
 *
 * <p>So WhyNotAC measures angular change with no added error. The only remaining uncertainty is the
 * client's own float resolution, {@link Ulp#floatResolution(float)} of the larger operand.
 */
public final class ExactAngles {

    private ExactAngles() {}

    /** {@code to - from}, computed exactly in double. */
    public static double delta(float from, float to) {
        return (double) to - (double) from;
    }

    /** {@link #delta} wrapped into [-180, 180). The remainder and the single +/-360 correction are exact. */
    public static double wrappedDelta(float from, float to) {
        double d = delta(from, to) % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /**
     * Whether {@link #delta(float, float)} is provably exact for these operands (its TwoSum error term
     * is zero). Always true for finite floats within 2^29 of each other in magnitude.
     */
    public static boolean isDeltaExact(float from, float to) {
        return ErrorFree.sumError(to, -(double) from) == 0.0;
    }

    /**
     * Information limit of a rotation measurement between {@code from} and {@code to}: half the
     * float spacing at the larger magnitude. Any feature smaller than this may be a representation
     * artefact and must never count as evidence.
     */
    public static double informationLimit(float from, float to) {
        return Math.max(Math.ulp(from), Math.ulp(to)) * 0.5;
    }
}
