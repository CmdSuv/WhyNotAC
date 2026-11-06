package dev.whynotac.math.vanilla;

/**
 * Exact model of how the 1.21.11 client turns mouse motion into rotation
 * ({@code MouseHandler.turnPlayer} followed by {@code Entity.turn}), evaluated once per rendered
 * <em>frame</em>, not per tick:
 *
 * <pre>
 *   d          = sensitivity * 0.6F + 0.2F        double; the float literals are widened first
 *   e          = d * d * d
 *   multiplier = e * 8.0                          (e alone while scoping through a spyglass in 1st person)
 *   turn       = accumulatedCursorDelta * multiplier          double; negated if invertMouseX/Y
 *   increment  = (float) turn * 0.15F                          float multiply
 *   yaw'       = yaw + increment                               float add, never wrapped
 *   pitch'     = Math.clamp((pitch + increment) % 360F, -90F, 90F)
 * </pre>
 *
 * <p>Consequences the analysis layer must respect (each one is a classic false-positive source):
 * <ul>
 *   <li>A tick's rotation is the float sum of several frame increments, each rounded to the
 *       resolution of the current yaw. Rotation deltas are only an <em>approximate</em> lattice, and
 *       the approximation degrades as |yaw| grows (ulp(10 000 deg) = 9.8e-4 while the smallest
 *       sensitivity step is 9.6e-3).
 *   <li>{@code (float)(c * m)} is not {@code c * (float) m}: step sizes for different cursor counts
 *       are not exact multiples of one step.
 *   <li>Cinematic camera ({@code smoothCamera}) feeds deltas through {@code SmoothDouble}; the
 *       output is continuous, not a lattice. Spyglass scoping uses a different multiplier.
 *   <li>The cursor delta is a GLFW double. It is integral on most platforms but may be fractional
 *       (e.g. scaled HiDPI cursors), which also breaks the lattice for an innocent player.
 * </ul>
 */
public final class MouseTurnModel {

    /** {@code 0.15F} factor in {@code Entity.turn}. */
    public static final float TURN_SCALE = 0.15F;
    /** Multiplier applied to {@code e} outside spyglass scoping. */
    public static final double NORMAL_FACTOR = 8.0;

    private MouseTurnModel() {}

    /** {@code e = d^3} with {@code d = sensitivity * 0.6F + 0.2F}; sensitivity is the option value in [0, 1] (0.5 = "100%"). */
    public static double sensitivityCube(double sensitivity) {
        double d = sensitivity * 0.6F + 0.2F;
        return d * d * d;
    }

    /** Turn multiplier applied to raw cursor deltas. */
    public static double multiplier(double sensitivity, boolean scopingInFirstPerson) {
        double e = sensitivityCube(sensitivity);
        return scopingInFirstPerson ? e : e * NORMAL_FACTOR;
    }

    /** Float rotation increment for a double turn amount ({@code Entity.turn}'s per-axis term). */
    public static float increment(double turnAmount) {
        return (float) turnAmount * TURN_SCALE;
    }

    /** Increment produced by {@code counts} cursor units at the given multiplier. */
    public static float countIncrement(long counts, double multiplier) {
        return increment(counts * multiplier);
    }

    /** One frame of yaw: {@code setYRot(getYRot() + increment)}; a non-finite result is discarded by the game. */
    public static float turnYaw(float yaw, double turnAmount) {
        float next = yaw + increment(turnAmount);
        return Float.isFinite(next) ? next : yaw;
    }

    /**
     * One frame of pitch. {@code Entity.turn} calls {@code setXRot} twice (add, then clamp); both
     * reduce to {@code Math.clamp(v % 360F, -90F, 90F)}, which is idempotent.
     */
    public static float turnPitch(float pitch, double turnAmount) {
        float next = pitch + increment(turnAmount);
        if (!Float.isFinite(next)) {
            return pitch;
        }
        return Math.clamp(next % 360.0F, -90.0F, 90.0F);
    }

    /** Inclusive range of integer cursor counts; empty when {@code min > max}. */
    public record CountRange(long min, long max) {
        public static final CountRange EMPTY = new CountRange(1, 0);

        public boolean isEmpty() {
            return min > max;
        }

        /** Exactly one count explains the observation. */
        public boolean isUnique() {
            return min == max;
        }

        public long size() {
            return isEmpty() ? 0 : max - min + 1;
        }
    }

    private static final long SEARCH_LIMIT = 1L << 31;

    /**
     * Inverse of one yaw frame: every integer cursor count {@code c} for which
     * {@code turnYaw(fromYaw, c * multiplier)} lands exactly on {@code toYaw}.
     *
     * <p>{@code c -> turnYaw(from, c * m)} is non-decreasing for {@code m > 0} (it is a chain of
     * monotone roundings), so the preimage of a value is a contiguous range, found by two binary
     * searches. The range is often wider than one count when |yaw| is large: the game itself cannot
     * tell those inputs apart, so neither may we. An empty range means no integer count explains the
     * frame (fractional cursor input, smoothing, or a synthetic rotation; deciding which is the
     * analysis layer's job, not this method's).
     */
    public static CountRange yawCountsFor(float fromYaw, float toYaw, double multiplier) {
        if (!(multiplier > 0.0) || !Float.isFinite(fromYaw) || !Float.isFinite(toYaw)) {
            return CountRange.EMPTY;
        }
        long lo = -SEARCH_LIMIT;
        long hi = SEARCH_LIMIT;
        // first c with turnYaw(c) >= toYaw
        while (lo < hi) {
            long mid = Math.floorDiv(lo + hi, 2);
            if (turnYaw(fromYaw, mid * multiplier) >= toYaw) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        long first = lo;
        if (turnYaw(fromYaw, first * multiplier) != toYaw) {
            return CountRange.EMPTY;
        }
        lo = first;
        hi = SEARCH_LIMIT;
        // last c with turnYaw(c) <= toYaw
        while (lo < hi) {
            long mid = Math.floorDiv(lo + hi + 1, 2);
            if (turnYaw(fromYaw, mid * multiplier) <= toYaw) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return new CountRange(first, lo);
    }
}
