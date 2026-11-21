package dev.whynotac.math.precision;

/**
 * A comparison budget: two values match when they are within {@code absolute} of each other
 * <em>or</em> within {@code ulps} representable steps of each other.
 *
 * <p>The two conditions cover different failure modes. The absolute bound handles values near zero,
 * where ULPs become meaninglessly tiny. The ULP bound handles large magnitudes, where a fixed
 * epsilon can be smaller than the spacing of representable numbers and would reject results that
 * are exactly as precise as the format allows.
 *
 * @param absolute non-negative absolute bound
 * @param ulps     non-negative ULP bound
 */
public record Tolerance(double absolute, long ulps) {

    /** Only bit-identical values match ({@code -0.0} and {@code 0.0} count as identical). */
    public static final Tolerance EXACT = new Tolerance(0.0, 0);

    /**
     * WhyNotAC's analysis grid: 0.000001 (1e-6) absolute. Doubles resolve far better than this
     * everywhere inside the world border (worst case 3.7e-9), so for coordinates this is a real 1e-6
     * guarantee. For float32 channels it is capped by {@link Ulp#floatResolution(float)}: from
     * |yaw| = 16 degrees upward adjacent floats are 1.9e-6 or more apart, so the client's own value
     * cannot carry 1e-6 detail there.
     */
    public static final Tolerance MICRO = new Tolerance(1.0E-6, 0);

    public Tolerance {
        if (!(absolute >= 0.0) || ulps < 0) {
            throw new IllegalArgumentException("tolerance must be non-negative: " + absolute + ", " + ulps);
        }
    }

    public static Tolerance absolute(double absolute) {
        return new Tolerance(absolute, 0);
    }

    public static Tolerance ulps(long ulps) {
        return new Tolerance(0.0, ulps);
    }

    public boolean matches(double expected, double actual) {
        if (expected == actual) {
            return true;
        }
        if (Double.isNaN(expected) || Double.isNaN(actual)) {
            return false;
        }
        return Math.abs(actual - expected) <= absolute || Ulp.distance(expected, actual) <= ulps;
    }

    public boolean matches(float expected, float actual) {
        if (expected == actual) {
            return true;
        }
        if (Float.isNaN(expected) || Float.isNaN(actual)) {
            return false;
        }
        // Difference taken in double: exact for any two floats within 2^29 of each other in scale.
        return Math.abs((double) actual - (double) expected) <= absolute || Ulp.distance(expected, actual) <= ulps;
    }
}
