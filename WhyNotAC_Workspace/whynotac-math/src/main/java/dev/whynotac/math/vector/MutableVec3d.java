package dev.whynotac.math.vector;

/**
 * Mutable counterpart of {@link Vec3d} for hot loops (the physics layer evaluates dozens of
 * movement hypotheses per player per tick). Every operation performs the same IEEE-754 operations,
 * in the same order, as the immutable vanilla equivalent, so switching between the two never changes
 * a result bit.
 *
 * <p>Not thread-safe: each instance belongs to one pipeline thread.
 */
public final class MutableVec3d {

    public double x;
    public double y;
    public double z;

    public MutableVec3d() {}

    public MutableVec3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public MutableVec3d set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public MutableVec3d set(Vec3d v) {
        return set(v.x(), v.y(), v.z());
    }

    public MutableVec3d set(MutableVec3d v) {
        return set(v.x, v.y, v.z);
    }

    /** Same as {@code Vec3.add(double, double, double)}. */
    public MutableVec3d add(double dx, double dy, double dz) {
        x = x + dx;
        y = y + dy;
        z = z + dz;
        return this;
    }

    public MutableVec3d add(Vec3d v) {
        return add(v.x(), v.y(), v.z());
    }

    /** Same as {@code Vec3.multiply(double, double, double)}. */
    public MutableVec3d multiply(double fx, double fy, double fz) {
        x = x * fx;
        y = y * fy;
        z = z * fz;
        return this;
    }

    /** Same as {@code Vec3.scale(double)}. */
    public MutableVec3d scale(double factor) {
        return multiply(factor, factor, factor);
    }

    /** Same as {@code Vec3.lengthSqr()}. */
    public double lengthSqr() {
        return x * x + y * y + z * z;
    }

    /** Same as {@code Vec3.horizontalDistanceSqr()}. */
    public double horizontalDistanceSqr() {
        return x * x + z * z;
    }

    public Vec3d toImmutable() {
        return new Vec3d(x, y, z);
    }

    @Override
    public String toString() {
        return "Mutable(" + x + ", " + y + ", " + z + ")";
    }
}
