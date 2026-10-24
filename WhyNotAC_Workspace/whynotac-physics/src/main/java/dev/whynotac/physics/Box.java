package dev.whynotac.physics;

/**
 * {@code AABB}: every operation builds the result through {@link #of}, which orders each pair of bounds as
 * the game's constructor does ({@code Math.min}, {@code Math.max}).
 */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    /** {@code new AABB(x1, y1, z1, x2, y2, z2)}. */
    public static Box of(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public double min(Axis axis) {
        return axis.choose(minX, minY, minZ);
    }

    public double max(Axis axis) {
        return axis.choose(maxX, maxY, maxZ);
    }

    /** {@code AABB.move(double, double, double)}. */
    public Box move(double x, double y, double z) {
        return of(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
    }

    /** {@code AABB.expandTowards(double, double, double)}: a negative component moves the minimum, a positive one the maximum. */
    public Box expandTowards(double x, double y, double z) {
        double x0 = minX;
        double y0 = minY;
        double z0 = minZ;
        double x1 = maxX;
        double y1 = maxY;
        double z1 = maxZ;
        if (x < 0.0) {
            x0 += x;
        } else if (x > 0.0) {
            x1 += x;
        }
        if (y < 0.0) {
            y0 += y;
        } else if (y > 0.0) {
            y1 += y;
        }
        if (z < 0.0) {
            z0 += z;
        } else if (z > 0.0) {
            z1 += z;
        }
        return of(x0, y0, z0, x1, y1, z1);
    }

    /** {@code AABB.inflate(x, y, z)}; a negative amount is {@code deflate}. */
    public Box inflate(double x, double y, double z) {
        return of(minX - x, minY - y, minZ - z, maxX + x, maxY + y, maxZ + z);
    }

    /** {@code AABB.intersects(double...)}: a common interior, so touching faces do not count. */
    public boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1) {
        return minX < x1 && maxX > x0 && minY < y1 && maxY > y0 && minZ < z1 && maxZ > z0;
    }

    /** {@code AABB.intersects(Vec3, Vec3)}: with the box the two points span. */
    public boolean intersects(dev.whynotac.math.vector.Vec3d a, dev.whynotac.math.vector.Vec3d b) {
        return intersects(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()), Math.max(a.x(), b.x()), Math.max(a.y(), b.y()),
                Math.max(a.z(), b.z()));
    }
}
