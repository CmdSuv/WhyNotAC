package dev.whynotac.math.vanilla;

/**
 * Bit-exact replica of the parts of {@code net.minecraft.util.Mth} (1.21.11) that movement,
 * rotation and combat code depends on.
 *
 * <p>Every method keeps the exact operation order, operand types and widening points of the
 * original, including the cases that look odd ({@code equal(double, double)} comparing against a
 * <em>float</em> epsilon, {@code clamp} propagating NaN through {@link Math#min}). Do not "clean up"
 * these expressions: they are only correct as long as they are identical to the game's.
 *
 * <h2>The sine table is the same on every client</h2>
 *
 * The game fills its 65536-entry table with {@link Math#sin}, which the JVM may compute with a
 * platform intrinsic that differs from {@link StrictMath#sin} in the last double bit. We build the
 * table with {@link StrictMath#sin} and prove equality anyway: every exact table value lies at least
 * 2502 double ulps from the nearest float rounding boundary (checked in {@code VanillaMathTest}),
 * while any conforming {@code Math.sin} is at most 1 ulp from the true value. So the {@code (float)}
 * cast lands on the same float for every JVM on every CPU.
 */
public final class VanillaMath {

    /** {@code Mth.EPSILON}. */
    public static final float EPSILON = 1.0E-5F;
    /** {@code Mth.PI}. */
    public static final float PI = (float) Math.PI;
    /** {@code Mth.DEG_TO_RAD}; also the literal {@code (float) (Math.PI / 180.0)} used inline across the game. */
    public static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
    /** {@code Mth.RAD_TO_DEG}. */
    public static final float RAD_TO_DEG = 180.0F / (float) Math.PI;

    /** Number of entries in the sine table. */
    public static final int SIN_TABLE_SIZE = 65536;
    /** {@code Mth.SIN_SCALE}: 65536 / (2 pi), the radians-to-table-index factor. */
    public static final double SIN_SCALE = 10430.378350470453;
    private static final int SIN_MASK = 65535;
    private static final double COS_OFFSET = 16384.0;

    private static final float[] SIN = new float[SIN_TABLE_SIZE];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) StrictMath.sin(i / SIN_SCALE);
        }
    }

    private VanillaMath() {}

    // --- trigonometry (lookup table, exactly as the client computes it) -----------------------

    /** {@code Mth.sin(double)}: table lookup, truncating (not rounding) the scaled angle. */
    public static float sin(double radians) {
        return SIN[sinIndex(radians)];
    }

    /** {@code Mth.cos(double)}: the sine table shifted by a quarter turn. */
    public static float cos(double radians) {
        return SIN[cosIndex(radians)];
    }

    /** Table slot {@link #sin(double)} reads. The physics layer uses it to see when two angles share a slot. */
    public static int sinIndex(double radians) {
        return (int) ((long) (radians * SIN_SCALE) & SIN_MASK);
    }

    /** Table slot {@link #cos(double)} reads. */
    public static int cosIndex(double radians) {
        return (int) ((long) (radians * SIN_SCALE + COS_OFFSET) & SIN_MASK);
    }

    /** Raw table entry; {@code index} is masked into range. */
    public static float sinTableEntry(int index) {
        return SIN[index & SIN_MASK];
    }

    // --- rounding --------------------------------------------------------------------------------

    /** {@code Mth.floor(double)}. Note: saturates through the {@code (int)} cast like the original. */
    public static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** {@code Mth.floor(float)}. */
    public static int floor(float value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** {@code Mth.lfloor(double)}. */
    public static long lfloor(double value) {
        long truncated = (long) value;
        return value < truncated ? truncated - 1L : truncated;
    }

    /** {@code Mth.ceil(double)}. */
    public static int ceil(double value) {
        int truncated = (int) value;
        return value > truncated ? truncated + 1 : truncated;
    }

    /** {@code Mth.ceil(float)}. */
    public static int ceil(float value) {
        int truncated = (int) value;
        return value > truncated ? truncated + 1 : truncated;
    }

    /** {@code Mth.ceilLong(double)}. */
    public static long ceilLong(double value) {
        long truncated = (long) value;
        return value > truncated ? truncated + 1L : truncated;
    }

    /** {@code Mth.frac(double)}: {@code value - lfloor(value)}. */
    public static double frac(double value) {
        return value - lfloor(value);
    }

    /** {@code Mth.frac(float)}. */
    public static float frac(float value) {
        return value - floor(value);
    }

    // --- clamping & comparison ------------------------------------------------------------------

    /** {@code Mth.clamp(double, double, double)}: {@code value < min ? min : Math.min(value, max)} (NaN passes through). */
    public static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** {@code Mth.clamp(float, float, float)}. */
    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    /** {@code Mth.clamp(int, int, int)}. */
    public static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    /** {@code Mth.absMax(double, double)}. */
    public static double absMax(double a, double b) {
        return Math.max(Math.abs(a), Math.abs(b));
    }

    /** {@code Mth.equal(float, float)}: {@code |b - a| < 1.0E-5F}. */
    public static boolean equal(float a, float b) {
        return Math.abs(b - a) < EPSILON;
    }

    /** {@code Mth.equal(double, double)}: compares against the <em>float</em> epsilon, widened. */
    public static boolean equal(double a, double b) {
        return Math.abs(b - a) < EPSILON;
    }

    // --- angles ------------------------------------------------------------------------------------

    /** {@code Mth.wrapDegrees(float)}: maps into [-180, 180) using float remainder. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    /** {@code Mth.wrapDegrees(double)}. */
    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    /** {@code Mth.degreesDifference(float, float)}: {@code wrapDegrees(to - from)} with a float subtraction. */
    public static float degreesDifference(float from, float to) {
        return wrapDegrees(to - from);
    }

    /** {@code Mth.packDegrees(float)}: an angle as the protocol's byte, {@code floor(deg * 256 / 360)} in float. */
    public static byte packDegrees(float degrees) {
        return (byte) floor(degrees * 256.0F / 360.0F);
    }

    /** {@code Mth.unpackDegrees(byte)}: an int product, then a float division. */
    public static float unpackDegrees(byte packed) {
        return packed * 360 / 256.0F;
    }

    /** {@code Mth.rotLerp(float, float, float)}: {@code start + delta * wrapDegrees(end - start)}. */
    public static float rotLerp(float delta, float start, float end) {
        return start + delta * wrapDegrees(end - start);
    }

    /** {@code Mth.rotLerp(double, double, double)} (entity interpolation calls it with widened float angles). */
    public static double rotLerp(double delta, double start, double end) {
        return start + delta * wrapDegrees(end - start);
    }

    /** {@code Mth.positiveModulo(double, double)}. */
    public static double positiveModulo(double value, double modulus) {
        return (value % modulus + modulus) % modulus;
    }

    // --- interpolation & lengths -----------------------------------------------------------------

    /** {@code Mth.lerp(double, double, double)}: {@code start + delta * (end - start)}. */
    public static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    /** {@code Mth.lerp(float, float, float)}. */
    public static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    /** {@code Mth.square(double)}. */
    public static double square(double value) {
        return value * value;
    }

    /** {@code Mth.square(float)}. */
    public static float square(float value) {
        return value * value;
    }

    /** {@code Mth.lengthSquared(double, double)}. */
    public static double lengthSquared(double x, double z) {
        return x * x + z * z;
    }

    /** {@code Mth.lengthSquared(double, double, double)}: evaluated left to right, {@code (x*x + y*y) + z*z}. */
    public static double lengthSquared(double x, double y, double z) {
        return x * x + y * y + z * z;
    }

    /** {@code Mth.length(double, double, double)}. */
    public static double length(double x, double y, double z) {
        return Math.sqrt(lengthSquared(x, y, z));
    }

    /** {@code Mth.sqrt(float)}: double square root rounded back to float. */
    public static float sqrt(float value) {
        return (float) Math.sqrt(value);
    }
}
