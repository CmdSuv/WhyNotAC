package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;

/**
 * {@code BlockGetter.forEachBlockIntersectedBetween}: the cells a box meets on its way from one point to another, in
 * the client's order, each with the step it is met at. The cells of the box where it starts come first (step 0), then
 * those a ray from one corner crosses ({@code addCollisionsAlongTravel}: a grid walk, each crossed cell counting a
 * step, the cells between it and the box's opposite corner in that step), then the cells of the box where it ends,
 * after the last step. A move shorter than {@code 1.0E-5F} visits the box's cells alone ({@code betweenClosed}). The
 * walk reproduces the game's arithmetic, its odd corner choice ({@code getFurthestCorner}) included.
 */
final class InsideBlocks {

    /** {@code BlockGetter.BlockStepVisitor}: false stops the walk. */
    @FunctionalInterface
    interface Visitor {
        boolean visit(int x, int y, int z, int step) throws Colliders.Unknown;
    }

    private InsideBlocks() {}

    /** Walks the cells from {@code from} to {@code to} for the box at {@code to}; false when the visitor stopped it. */
    static boolean forEachBlockIntersectedBetween(Vec3d from, Vec3d to, Box box, Visitor visitor) throws Colliders.Unknown {
        Vec3d travel = to.subtract(from);
        if (travel.lengthSqr() < (double) (1.0E-5F * 1.0E-5F)) { // Mth.square(float)
            // betweenClosed(AABB): x fastest, then y, then z
            int x0 = Colliders.floor(box.minX());
            int y0 = Colliders.floor(box.minY());
            int z0 = Colliders.floor(box.minZ());
            int x1 = Colliders.floor(box.maxX());
            int y1 = Colliders.floor(box.maxY());
            int z1 = Colliders.floor(box.maxZ());
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                    for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
                        if (!visitor.visit(x, y, z, 0)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
        Seen seen = new Seen();
        Vec3d back = travel.scale(-1.0);
        Box start = box.move(back.x(), back.y(), back.z());
        int[] cells = cornersInDirection(Colliders.floor(start.minX()), Colliders.floor(start.minY()), Colliders.floor(start.minZ()),
                Colliders.floor(start.maxX()), Colliders.floor(start.maxY()), Colliders.floor(start.maxZ()), travel);
        for (int i = 0; i < cells.length; i += 3) {
            if (!visitor.visit(cells[i], cells[i + 1], cells[i + 2], 0)) {
                return false;
            }
            seen.add(cells[i], cells[i + 1], cells[i + 2]);
        }
        int steps = alongTravel(seen, travel, box, visitor);
        if (steps < 0) {
            return false;
        }
        cells = cornersInDirection(Colliders.floor(box.minX()), Colliders.floor(box.minY()), Colliders.floor(box.minZ()),
                Colliders.floor(box.maxX()), Colliders.floor(box.maxY()), Colliders.floor(box.maxZ()), travel);
        for (int i = 0; i < cells.length; i += 3) {
            if (seen.add(cells[i], cells[i + 1], cells[i + 2]) && !visitor.visit(cells[i], cells[i + 1], cells[i + 2], steps + 1)) {
                return false;
            }
        }
        return true;
    }

    /** {@code addCollisionsAlongTravel}: the number of steps taken, or -1 when the visitor stopped the walk. */
    private static int alongTravel(Seen seen, Vec3d travel, Box box, Visitor visitor) throws Colliders.Unknown {
        double sizeX = box.maxX() - box.minX();
        double sizeY = box.maxY() - box.minY();
        double sizeZ = box.maxZ() - box.minZ();
        int[] corner = furthestCorner(travel);
        // getCenter: Mth.lerp(0.5, min, max)
        double centerX = box.minX() + 0.5 * (box.maxX() - box.minX());
        double centerY = box.minY() + 0.5 * (box.maxY() - box.minY());
        double centerZ = box.minZ() + 0.5 * (box.maxZ() - box.minZ());
        Vec3d end = new Vec3d(centerX + sizeX * 0.5 * corner[0], centerY + sizeY * 0.5 * corner[1], centerZ + sizeZ * 0.5 * corner[2]);
        Vec3d begin = end.subtract(travel);
        int cx = Colliders.floor(begin.x());
        int cy = Colliders.floor(begin.y());
        int cz = Colliders.floor(begin.z());
        int stepX = sign(travel.x());
        int stepY = sign(travel.y());
        int stepZ = sign(travel.z());
        double deltaX = stepX == 0 ? Double.MAX_VALUE : stepX / travel.x();
        double deltaY = stepY == 0 ? Double.MAX_VALUE : stepY / travel.y();
        double deltaZ = stepZ == 0 ? Double.MAX_VALUE : stepZ / travel.z();
        double maxX = deltaX * (stepX > 0 ? 1.0 - frac(begin.x()) : frac(begin.x()));
        double maxY = deltaY * (stepY > 0 ? 1.0 - frac(begin.y()) : frac(begin.y()));
        double maxZ = deltaZ * (stepZ > 0 ? 1.0 - frac(begin.z()) : frac(begin.z()));
        int steps = 0;
        while (maxX <= 1.0 || maxY <= 1.0 || maxZ <= 1.0) {
            if (maxX < maxY) {
                if (maxX < maxZ) {
                    cx += stepX;
                    maxX += deltaX;
                } else {
                    cz += stepZ;
                    maxZ += deltaZ;
                }
            } else if (maxY < maxZ) {
                cy += stepY;
                maxY += deltaY;
            } else {
                cz += stepZ;
                maxZ += deltaZ;
            }
            double[] hit = clip(cx, cy, cz, cx + 1, cy + 1, cz + 1, begin, end);
            if (hit != null) {
                steps++;
                double hx = clamp(hit[0], (float) cx + 1.0E-5F, cx + 1.0 - 1.0E-5F);
                double hy = clamp(hit[1], (float) cy + 1.0E-5F, cy + 1.0 - 1.0E-5F);
                double hz = clamp(hit[2], (float) cz + 1.0E-5F, cz + 1.0 - 1.0E-5F);
                int ox = Colliders.floor(hx - sizeX * corner[0]);
                int oy = Colliders.floor(hy - sizeY * corner[1]);
                int oz = Colliders.floor(hz - sizeZ * corner[2]);
                int[] cells = cornersInDirection(cx, cy, cz, ox, oy, oz, travel);
                for (int i = 0; i < cells.length; i += 3) {
                    if (seen.add(cells[i], cells[i + 1], cells[i + 2]) && !visitor.visit(cells[i], cells[i + 1], cells[i + 2], steps)) {
                        return -1;
                    }
                }
            }
        }
        return steps;
    }

    /** {@code getFurthestCorner}, as the game writes it. */
    private static int[] furthestCorner(Vec3d v) {
        double ax = Math.abs(v.x());
        double ay = Math.abs(v.y());
        double az = Math.abs(v.z());
        int sx = v.x() >= 0.0 ? 1 : -1;
        int sy = v.y() >= 0.0 ? 1 : -1;
        int sz = v.z() >= 0.0 ? 1 : -1;
        if (ax <= ay && ax <= az) {
            return new int[] {-sx, -sz, sy};
        }
        return ay <= az ? new int[] {sz, -sy, -sx} : new int[] {-sy, sx, -sz};
    }

    /**
     * {@code BlockPos.betweenCornersInDirection}: the cells between two corners, from the corner the direction leaves,
     * along {@code Direction.axisStepOrder} (y, then x and z, the larger of the two last), the last axis fastest.
     */
    static int[] cornersInDirection(int x0, int y0, int z0, int x1, int y1, int z1, Vec3d direction) {
        int minX = Math.min(x0, x1);
        int minY = Math.min(y0, y1);
        int minZ = Math.min(z0, z1);
        int maxX = Math.max(x0, x1);
        int maxY = Math.max(y0, y1);
        int maxZ = Math.max(z0, z1);
        int startX = direction.x() >= 0.0 ? minX : maxX;
        int startY = direction.y() >= 0.0 ? minY : maxY;
        int startZ = direction.z() >= 0.0 ? minZ : maxZ;
        int dirY = direction.y() >= 0.0 ? 1 : -1;
        int dirX = direction.x() >= 0.0 ? 1 : -1;
        int dirZ = direction.z() >= 0.0 ? 1 : -1;
        int sizeX = maxX - minX;
        int sizeY = maxY - minY;
        int sizeZ = maxZ - minZ;
        boolean zSecond = Math.abs(direction.x()) < Math.abs(direction.z()); // YZX, else YXZ
        int[] out = new int[3 * (sizeX + 1) * (sizeY + 1) * (sizeZ + 1)];
        int n = 0;
        int second = zSecond ? sizeZ : sizeX;
        int third = zSecond ? sizeX : sizeZ;
        for (int a = 0; a <= sizeY; a++) {
            for (int b = 0; b <= second; b++) {
                for (int c = 0; c <= third; c++) {
                    out[n++] = startX + dirX * (zSecond ? c : b);
                    out[n++] = startY + dirY * a;
                    out[n++] = startZ + dirZ * (zSecond ? b : c);
                }
            }
        }
        return out;
    }

    /** {@code AABB.clip(minX, minY, minZ, maxX, maxY, maxZ, from, to)}: the point the segment enters the box at, or null. */
    static double[] clip(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Vec3d from, Vec3d to) {
        double[] t = {1.0};
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        boolean found = false;
        // getDirection: the face each moving axis enters through, the nearest kept (ties to the earlier)
        if (dx > 1.0E-7) {
            found |= clipPoint(t, dx, dy, dz, minX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
        } else if (dx < -1.0E-7) {
            found |= clipPoint(t, dx, dy, dz, maxX, minY, maxY, minZ, maxZ, from.x(), from.y(), from.z());
        }
        if (dy > 1.0E-7) {
            found |= clipPoint(t, dy, dz, dx, minY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
        } else if (dy < -1.0E-7) {
            found |= clipPoint(t, dy, dz, dx, maxY, minZ, maxZ, minX, maxX, from.y(), from.z(), from.x());
        }
        if (dz > 1.0E-7) {
            found |= clipPoint(t, dz, dx, dy, minZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
        } else if (dz < -1.0E-7) {
            found |= clipPoint(t, dz, dx, dy, maxZ, minX, maxX, minY, maxY, from.z(), from.x(), from.y());
        }
        if (!found) {
            return null;
        }
        double s = t[0];
        return new double[] {from.x() + s * dx, from.y() + s * dy, from.z() + s * dz};
    }

    /** {@code AABB.clipPoint}: the plane is crossed before the best so far, within the face (with a {@code 1.0E-7} margin). */
    private static boolean clipPoint(double[] t, double d0, double d1, double d2, double plane, double min1, double max1, double min2, double max2,
            double s0, double s1, double s2) {
        double at = (plane - s0) / d0;
        double p1 = s1 + at * d1;
        double p2 = s2 + at * d2;
        if (0.0 < at && at < t[0] && min1 - 1.0E-7 < p1 && p1 < max1 + 1.0E-7 && min2 - 1.0E-7 < p2 && p2 < max2 + 1.0E-7) {
            t[0] = at;
            return true;
        }
        return false;
    }

    /** {@code Mth.sign(double)}. */
    private static int sign(double v) {
        return v == 0.0 ? 0 : v > 0.0 ? 1 : -1;
    }

    /** {@code Mth.frac(double)}: {@code v - lfloor(v)}. */
    private static double frac(double v) {
        long l = (long) v;
        return v - (v < l ? l - 1L : l);
    }

    /** {@code Mth.clamp(double, double, double)}. */
    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    /** The cells a walk has met ({@code LongSet}); a walk meets a few dozen. */
    private static final class Seen {
        private int[] cells = new int[3 * 32];
        private int size;

        boolean add(int x, int y, int z) {
            for (int i = 0; i < size; i += 3) {
                if (cells[i] == x && cells[i + 1] == y && cells[i + 2] == z) {
                    return false;
                }
            }
            if (size == cells.length) {
                cells = java.util.Arrays.copyOf(cells, cells.length * 2);
            }
            cells[size++] = x;
            cells[size++] = y;
            cells[size++] = z;
            return true;
        }
    }
}
