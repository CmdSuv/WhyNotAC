package dev.whynotac.world;

import java.util.Arrays;

/**
 * A block's collision shape in the game's own representation ({@code VoxelShape}): per axis a sorted
 * list of coordinates, a grid of filled cells between them, and the boxes {@code toAabbs()} yields.
 *
 * <p>The grid and the coordinates are what the game's collision code works on, so a physics engine can
 * reproduce it exactly. The kind matters too: a {@code CubeVoxelShape} finds the cell of a coordinate
 * by {@code floor(coordinate * size)}, every other shape by a binary search over its coordinates, and
 * the two can differ by one cell at a boundary.
 */
public final class Shape {

    /** {@code CubeVoxelShape} (cells of equal size) or any other {@code VoxelShape} (binary search). */
    public enum Kind { CUBE, ARRAY }

    public static final Shape EMPTY = new Shape(Kind.ARRAY, new double[] {0.0}, new double[] {0.0}, new double[] {0.0}, new long[0], new double[0]);

    private final Kind kind;
    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final long[] full;
    private final double[] boxes;

    /**
     * @param full  the filled cells, bit {@code (x * sizeY + y) * sizeZ + z} (sizes are the coordinate
     *              counts minus one), as in {@code BitSetDiscreteVoxelShape}
     * @param boxes {@code minX, minY, minZ, maxX, maxY, maxZ} per box
     */
    public Shape(Kind kind, double[] xs, double[] ys, double[] zs, long[] full, double[] boxes) {
        if (boxes.length % 6 != 0) {
            throw new IllegalArgumentException("boxes: " + boxes.length + " values");
        }
        this.kind = kind;
        this.xs = xs.clone();
        this.ys = ys.clone();
        this.zs = zs.clone();
        this.full = full.clone();
        this.boxes = boxes.clone();
    }

    public Kind kind() {
        return kind;
    }

    public boolean isEmpty() {
        return boxes.length == 0;
    }

    public int boxCount() {
        return boxes.length / 6;
    }

    /** Component {@code 0..5} ({@code minX, minY, minZ, maxX, maxY, maxZ}) of box {@code index}. */
    public double box(int index, int component) {
        return boxes[index * 6 + component];
    }

    public int sizeX() {
        return xs.length - 1;
    }

    public int sizeY() {
        return ys.length - 1;
    }

    public int sizeZ() {
        return zs.length - 1;
    }

    public double x(int index) {
        return xs[index];
    }

    public double y(int index) {
        return ys[index];
    }

    public double z(int index) {
        return zs[index];
    }

    public boolean isFull(int x, int y, int z) {
        int bit = (x * sizeY() + y) * sizeZ() + z;
        return (full[bit >>> 6] & 1L << bit) != 0;
    }

    double[] xs() {
        return xs;
    }

    double[] ys() {
        return ys;
    }

    double[] zs() {
        return zs;
    }

    long[] full() {
        return full;
    }

    double[] boxes() {
        return boxes;
    }

    /**
     * Equal except possibly in {@link #kind}: the same coordinates, cells and boxes. Paper's collision
     * patches build as {@code ArrayVoxelShape} what vanilla builds as {@code CubeVoxelShape}; for the
     * power-of-two grids cube shapes use, both find the same cell for every coordinate.
     */
    public boolean sameGeometry(Shape other) {
        return Arrays.equals(other.xs, xs) && Arrays.equals(other.ys, ys) && Arrays.equals(other.zs, zs) && Arrays.equals(other.full, full)
                && Arrays.equals(other.boxes, boxes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Shape s && s.kind == kind && Arrays.equals(s.xs, xs) && Arrays.equals(s.ys, ys) && Arrays.equals(s.zs, zs)
                && Arrays.equals(s.full, full) && Arrays.equals(s.boxes, boxes);
    }

    @Override
    public int hashCode() {
        return ((kind.hashCode() * 31 + Arrays.hashCode(xs)) * 31 + Arrays.hashCode(ys)) * 31 + Arrays.hashCode(zs) * 31 + Arrays.hashCode(boxes);
    }

    @Override
    public String toString() {
        return "Shape[" + kind + ", " + boxCount() + " boxes]";
    }
}
