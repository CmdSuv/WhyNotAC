package dev.whynotac.physics;

import dev.whynotac.world.Shape;
import java.util.List;

/** The game's voxel collision operations ({@code VoxelShape.collideX}, {@code Shapes.collide}, {@code Shapes.joinIsNotEmpty}, {@code Shapes.create}). */
public final class VoxelCollisions {

    /** The collision code's tolerance. */
    public static final double EPSILON = 1.0E-7;

    private static final Shape BLOCK = new Shape(Shape.Kind.CUBE, new double[] {0.0, 1.0}, new double[] {0.0, 1.0}, new double[] {0.0, 1.0},
            new long[] {1L}, new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0});

    private VoxelCollisions() {}

    /**
     * {@code VoxelShape.collide(axis, box, distance)} ({@code collideX}): how far {@code box} may move along
     * {@code axis} towards {@code distance} before it enters the shape.
     */
    public static double collide(Voxels shape, Axis axis, Box box, double distance) {
        if (shape.isEmpty()) {
            return distance;
        }
        if (Math.abs(distance) < EPSILON) {
            return 0.0;
        }
        Axis second = next(axis);
        Axis third = next(second);
        double max = box.max(axis);
        double min = box.min(axis);
        int lower = shape.findIndex(axis, min + EPSILON);
        int upper = shape.findIndex(axis, max - EPSILON);
        int secondFrom = Math.max(0, shape.findIndex(second, box.min(second) + EPSILON));
        int secondTo = Math.min(shape.size(second), shape.findIndex(second, box.max(second) - EPSILON) + 1);
        int thirdFrom = Math.max(0, shape.findIndex(third, box.min(third) + EPSILON));
        int thirdTo = Math.min(shape.size(third), shape.findIndex(third, box.max(third) - EPSILON) + 1);
        int size = shape.size(axis);
        if (distance > 0.0) {
            for (int i = upper + 1; i < size; i++) {
                for (int j = secondFrom; j < secondTo; j++) {
                    for (int k = thirdFrom; k < thirdTo; k++) {
                        if (shape.isFullWide(axis, i, j, k)) {
                            double gap = shape.coord(axis, i) - max;
                            if (gap >= -EPSILON) {
                                distance = Math.min(distance, gap);
                            }
                            return distance;
                        }
                    }
                }
            }
        } else if (distance < 0.0) {
            for (int i = lower - 1; i >= 0; i--) {
                for (int j = secondFrom; j < secondTo; j++) {
                    for (int k = thirdFrom; k < thirdTo; k++) {
                        if (shape.isFullWide(axis, i, j, k)) {
                            double gap = shape.coord(axis, i + 1) - min;
                            if (gap <= EPSILON) {
                                distance = Math.max(distance, gap);
                            }
                            return distance;
                        }
                    }
                }
            }
        }
        return distance;
    }

    /** {@code Shapes.collide(axis, box, shapes, distance)}: the shapes in order, stopping once the distance is (almost) zero. */
    public static double collide(Axis axis, Box box, List<Voxels> shapes, double distance) {
        for (Voxels shape : shapes) {
            if (Math.abs(distance) < EPSILON) {
                return 0.0;
            }
            distance = collide(shape, axis, box, distance);
        }
        return distance;
    }

    /** {@code AxisCycle.FORWARD.cycle}: X to Y, Y to Z, Z to X. */
    static Axis next(Axis axis) {
        return switch (axis) {
            case X -> Axis.Y;
            case Y -> Axis.Z;
            case Z -> Axis.X;
        };
    }

    /** {@code Shapes.create(box)}: empty when thin, the full block, a power-of-two grid when the box lies on one, else one cell. */
    public static Voxels create(Box box) {
        double x0 = box.minX();
        double y0 = box.minY();
        double z0 = box.minZ();
        double x1 = box.maxX();
        double y1 = box.maxY();
        double z1 = box.maxZ();
        if (x1 - x0 < EPSILON || y1 - y0 < EPSILON || z1 - z0 < EPSILON) {
            return Voxels.of(Shape.EMPTY);
        }
        int bx = findBits(x0, x1);
        int by = findBits(y0, y1);
        int bz = findBits(z0, z1);
        if (bx < 0 || by < 0 || bz < 0) {
            return Voxels.of(new Shape(Shape.Kind.ARRAY, new double[] {x0, x1}, new double[] {y0, y1}, new double[] {z0, z1}, new long[] {1L},
                    new double[] {x0, y0, z0, x1, y1, z1}));
        }
        if (bx == 0 && by == 0 && bz == 0) {
            return Voxels.of(BLOCK);
        }
        int nx = 1 << bx;
        int ny = 1 << by;
        int nz = 1 << bz;
        int fx0 = (int) Math.round(x0 * nx);
        int fy0 = (int) Math.round(y0 * ny);
        int fz0 = (int) Math.round(z0 * nz);
        int fx1 = (int) Math.round(x1 * nx);
        int fy1 = (int) Math.round(y1 * ny);
        int fz1 = (int) Math.round(z1 * nz);
        long[] full = new long[(nx * ny * nz + 63) >>> 6];
        for (int x = fx0; x < fx1; x++) {
            for (int y = fy0; y < fy1; y++) {
                for (int z = fz0; z < fz1; z++) {
                    int bit = (x * ny + y) * nz + z;
                    full[bit >>> 6] |= 1L << bit;
                }
            }
        }
        return Voxels.of(new Shape(Shape.Kind.CUBE, cubePoints(nx), cubePoints(ny), cubePoints(nz), full,
                new double[] {(double) fx0 / nx, (double) fy0 / ny, (double) fz0 / nz, (double) fx1 / nx, (double) fy1 / ny, (double) fz1 / nz}));
    }

    /** {@code CubePointRange}: {@code i / parts}. */
    private static double[] cubePoints(int parts) {
        double[] points = new double[parts + 1];
        for (int i = 0; i <= parts; i++) {
            points[i] = (double) i / parts;
        }
        return points;
    }

    /** {@code Shapes.findBits}: the smallest power-of-two grid (up to 8) both bounds lie on, or -1. */
    static int findBits(double min, double max) {
        if (min < -EPSILON || max > 1.0000001) {
            return -1;
        }
        for (int bits = 0; bits <= 3; bits++) {
            int intervals = 1 << bits;
            double a = min * intervals;
            double b = max * intervals;
            boolean onMin = Math.abs(a - Math.round(a)) < EPSILON * intervals;
            boolean onMax = Math.abs(b - Math.round(b)) < EPSILON * intervals;
            if (onMin && onMax) {
                return bits;
            }
        }
        return -1;
    }

    /**
     * {@code Shapes.joinIsNotEmpty(first, second, BooleanOp.AND)}: whether the shapes share a cell once their
     * coordinates are merged (coordinates closer than {@link #EPSILON} merge). Neither shape may be a
     * cube grid of its own: the block shapes the collision code tests are moved, so their lists are never
     * {@code CubePointRange}s, and the merger for two of those is not needed.
     */
    public static boolean overlaps(Voxels first, Voxels second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        if (first == second) {
            return true;
        }
        for (Axis axis : Axis.values()) {
            if (first.max(axis) < second.min(axis) - EPSILON || second.max(axis) < first.min(axis) - EPSILON) {
                return false;
            }
        }
        int[][] x = merge(Axis.X, first, second);
        if (x == null) {
            return false;
        }
        int[][] y = merge(Axis.Y, first, second);
        if (y == null) {
            return false;
        }
        int[][] z = merge(Axis.Z, first, second);
        if (z == null) {
            return false;
        }
        for (int i = 0; i < x[0].length; i++) {
            for (int j = 0; j < y[0].length; j++) {
                for (int k = 0; k < z[0].length; k++) {
                    if (first.isFullWide(x[0][i], y[0][j], z[0][k]) && second.isFullWide(x[1][i], y[1][j], z[1][k])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code Shapes.createIndexMerger} for an AND: the (first, second) cell index pairs of the merged cells,
     * or null when the lists do not overlap ({@code NonOverlappingMerger}: every pair holds an index outside
     * one of the shapes, so no merged cell is in both).
     */
    private static int[][] merge(Axis axis, Voxels first, Voxels second) {
        int firstSize = first.size(axis);
        int secondSize = second.size(axis);
        if (!first.moved() && first.shape().kind() == Shape.Kind.CUBE && !second.moved() && second.shape().kind() == Shape.Kind.CUBE) {
            throw new UnsupportedOperationException("two cube grids (DiscreteCubeMerger) never meet in block collisions");
        }
        if (first.coord(axis, firstSize) < second.coord(axis, 0) - EPSILON || second.coord(axis, secondSize) < first.coord(axis, 0) - EPSILON) {
            return null;
        }
        if (firstSize == secondSize && first.sameCoords(axis, second)) {
            int[][] identical = new int[2][firstSize];
            for (int i = 0; i < firstSize; i++) {
                identical[0][i] = i;
                identical[1][i] = i;
            }
            return identical;
        }
        return indirect(axis, first, second);
    }

    /** {@code IndirectMerger} with {@code firstOnlyMatters = secondOnlyMatters = false} (an AND). */
    private static int[][] indirect(Axis axis, Voxels lower, Voxels upper) {
        int lowerSize = lower.size(axis) + 1;
        int upperSize = upper.size(axis) + 1;
        int[] firstIndices = new int[lowerSize + upperSize];
        int[] secondIndices = new int[lowerSize + upperSize];
        double last = Double.NaN;
        int count = 0;
        int li = 0;
        int ui = 0;
        while (true) {
            boolean lowerDone = li >= lowerSize;
            boolean upperDone = ui >= upperSize;
            if (lowerDone && upperDone) {
                break;
            }
            boolean takeLower = !lowerDone && (upperDone || lower.coord(axis, li) < upper.coord(axis, ui) + EPSILON);
            if (takeLower) {
                li++;
                if (ui == 0 || upperDone) {
                    continue;
                }
            } else {
                ui++;
                if (li == 0 || lowerDone) {
                    continue;
                }
            }
            int currentLower = li - 1;
            int currentUpper = ui - 1;
            double next = takeLower ? lower.coord(axis, currentLower) : upper.coord(axis, currentUpper);
            if (!(last >= next - EPSILON)) {
                firstIndices[count] = currentLower;
                secondIndices[count] = currentUpper;
                count++;
                last = next;
            } else {
                firstIndices[count - 1] = currentLower;
                secondIndices[count - 1] = currentUpper;
            }
        }
        int cells = Math.max(1, count) - 1; // forMergedIndexes visits resultLength - 1 entries
        int[][] pairs = new int[2][cells];
        System.arraycopy(firstIndices, 0, pairs[0], 0, cells);
        System.arraycopy(secondIndices, 0, pairs[1], 0, cells);
        return pairs;
    }
}
