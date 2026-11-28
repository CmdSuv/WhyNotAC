package dev.whynotac.math.vector;

import dev.whynotac.math.vanilla.VanillaMath;

/**
 * Immutable 3D double vector with the exact semantics of {@code net.minecraft.world.phys.Vec3} (1.21.11).
 *
 * <p>Each vanilla method is reproduced with the same expression tree, so a prediction built from
 * {@code Vec3d} operations produces the same bits as the client. Methods that vanilla does not have
 * are marked <b>(WhyNotAC)</b>; they never feed the bit-exact simulation path.
 *
 * <p>Record equality uses {@link Double#compare}, the same as {@code Vec3.equals}: {@code -0.0 != 0.0}
 * and {@code NaN == NaN}.
 */
public record Vec3d(double x, double y, double z) {

    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);
    public static final Vec3d X_AXIS = new Vec3d(1.0, 0.0, 0.0);
    public static final Vec3d Y_AXIS = new Vec3d(0.0, 1.0, 0.0);
    public static final Vec3d Z_AXIS = new Vec3d(0.0, 0.0, 1.0);

    // --- vanilla Vec3 ------------------------------------------------------------------------------

    /** {@code Vec3.vectorTo}: {@code other - this}. */
    public Vec3d vectorTo(Vec3d other) {
        return new Vec3d(other.x - x, other.y - y, other.z - z);
    }

    /** {@code Vec3.normalize}: returns {@link #ZERO} if the length is below {@code 1.0E-5F} (a float literal, widened). */
    public Vec3d normalize() {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < VanillaMath.EPSILON ? ZERO : new Vec3d(x / length, y / length, z / length);
    }

    /** {@code Vec3.dot}. */
    public double dot(Vec3d other) {
        return x * other.x + y * other.y + z * other.z;
    }

    /** {@code Vec3.cross}. */
    public Vec3d cross(Vec3d other) {
        return new Vec3d(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
    }

    /** {@code Vec3.subtract(Vec3)}: implemented as {@code add(-x, -y, -z)}, like vanilla. */
    public Vec3d subtract(Vec3d other) {
        return subtract(other.x, other.y, other.z);
    }

    /** {@code Vec3.subtract(double, double, double)}. Adding the negation gives the same IEEE result as subtracting. */
    public Vec3d subtract(double dx, double dy, double dz) {
        return add(-dx, -dy, -dz);
    }

    /** {@code Vec3.add(Vec3)}. */
    public Vec3d add(Vec3d other) {
        return add(other.x, other.y, other.z);
    }

    /** {@code Vec3.add(double, double, double)}. */
    public Vec3d add(double dx, double dy, double dz) {
        return new Vec3d(x + dx, y + dy, z + dz);
    }

    /** {@code Vec3.distanceTo}. */
    public double distanceTo(Vec3d other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** {@code Vec3.distanceToSqr(Vec3)}. */
    public double distanceToSqr(Vec3d other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** {@code Vec3.scale}: {@code multiply(f, f, f)}. */
    public Vec3d scale(double factor) {
        return multiply(factor, factor, factor);
    }

    /** {@code Vec3.reverse}: {@code scale(-1.0)}. */
    public Vec3d reverse() {
        return scale(-1.0);
    }

    /** {@code Vec3.multiply(Vec3)}. */
    public Vec3d multiply(Vec3d other) {
        return multiply(other.x, other.y, other.z);
    }

    /** {@code Vec3.multiply(double, double, double)}. */
    public Vec3d multiply(double fx, double fy, double fz) {
        return new Vec3d(x * fx, y * fy, z * fz);
    }

    /** {@code Vec3.horizontal}. */
    public Vec3d horizontal() {
        return new Vec3d(x, 0.0, z);
    }

    /** {@code Vec3.length}. */
    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** {@code Vec3.lengthSqr}. */
    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    /** {@code Vec3.horizontalDistance}. */
    public double horizontalDistance() {
        return Math.sqrt(x * x + z * z);
    }

    /** {@code Vec3.horizontalDistanceSqr}. */
    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    /** {@code Vec3.lerp}: component-wise {@code Mth.lerp(delta, this, to)}. */
    public Vec3d lerp(Vec3d to, double delta) {
        return new Vec3d(VanillaMath.lerp(delta, x, to.x), VanillaMath.lerp(delta, y, to.y), VanillaMath.lerp(delta, z, to.z));
    }

    /** {@code Vec3.xRot}: rotation about X using the lookup-table sine (float factors, double products). */
    public Vec3d xRot(float radians) {
        float cos = VanillaMath.cos(radians);
        float sin = VanillaMath.sin(radians);
        return new Vec3d(x, y * cos + z * sin, z * cos - y * sin);
    }

    /** {@code Vec3.yRot}. */
    public Vec3d yRot(float radians) {
        float cos = VanillaMath.cos(radians);
        float sin = VanillaMath.sin(radians);
        return new Vec3d(x * cos + z * sin, y, z * cos - x * sin);
    }

    /** {@code Vec3.zRot}. */
    public Vec3d zRot(float radians) {
        float cos = VanillaMath.cos(radians);
        float sin = VanillaMath.sin(radians);
        return new Vec3d(x * cos + y * sin, y * cos - x * sin, z);
    }

    /** {@code Vec3.isFinite}. */
    public boolean isFinite() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /**
     * {@code Vec3.directionFromRotation(xRot, yRot)}. The angle arguments are computed in <em>float</em>
     * ({@code -yRot * DEG_TO_RAD - PI}) and widened only when passed to the table lookup; the three
     * products {@code sin * -cos} and so on are float multiplications.
     */
    public static Vec3d directionFromRotation(float xRot, float yRot) {
        float cosYaw = VanillaMath.cos(-yRot * VanillaMath.DEG_TO_RAD - VanillaMath.PI);
        float sinYaw = VanillaMath.sin(-yRot * VanillaMath.DEG_TO_RAD - VanillaMath.PI);
        float negCosPitch = -VanillaMath.cos(-xRot * VanillaMath.DEG_TO_RAD);
        float sinPitch = VanillaMath.sin(-xRot * VanillaMath.DEG_TO_RAD);
        return new Vec3d(sinYaw * negCosPitch, sinPitch, cosYaw * negCosPitch);
    }

    /**
     * {@code Entity.calculateViewVector(xRot, yRot)}: the look vector used for attack ray casts and
     * interaction reach. The component products are float multiplications widened to double.
     */
    public static Vec3d viewVector(float xRot, float yRot) {
        float pitchRad = xRot * VanillaMath.DEG_TO_RAD;
        float negYawRad = -yRot * VanillaMath.DEG_TO_RAD;
        float cosYaw = VanillaMath.cos(negYawRad);
        float sinYaw = VanillaMath.sin(negYawRad);
        float cosPitch = VanillaMath.cos(pitchRad);
        float sinPitch = VanillaMath.sin(pitchRad);
        return new Vec3d(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    /**
     * {@code Entity.getInputVector(input, speed, yRot)}: converts the local movement impulse
     * (strafe, up, forward) into a world-space velocity change. Returns {@link #ZERO} below a squared
     * length of {@code 1.0E-7}, normalises only when the squared length exceeds 1.
     */
    public static Vec3d inputVector(Vec3d input, float speed, float yRot) {
        double lengthSqr = input.lengthSqr();
        if (lengthSqr < 1.0E-7) {
            return ZERO;
        }
        Vec3d scaled = (lengthSqr > 1.0 ? input.normalize() : input).scale(speed);
        float sin = VanillaMath.sin(yRot * VanillaMath.DEG_TO_RAD);
        float cos = VanillaMath.cos(yRot * VanillaMath.DEG_TO_RAD);
        return new Vec3d(scaled.x * cos - scaled.z * sin, scaled.y, scaled.z * cos + scaled.x * sin);
    }

    // --- WhyNotAC extensions ---------------------------------------------------------------------

    /** <b>(WhyNotAC)</b> Largest absolute component (Chebyshev norm). */
    public double maxAbsComponent() {
        return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
    }

    /** <b>(WhyNotAC)</b> Component accessor by axis index 0/1/2. */
    public double get(int axis) {
        return switch (axis) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IndexOutOfBoundsException("axis " + axis);
        };
    }

    /** <b>(WhyNotAC)</b> Copies into a mutable vector for allocation-free hot loops. */
    public MutableVec3d toMutable() {
        return new MutableVec3d(x, y, z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
