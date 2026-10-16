package dev.whynotac.physics;

import dev.whynotac.world.Shape;

/**
 * A {@code VoxelShape} as the collision code meets it: a {@link Shape}'s grid and coordinates, moved by
 * an offset or not. A moved shape is always an {@code ArrayVoxelShape} whose coordinates are
 * {@code OffsetDoubleList}s ({@code VoxelShape.move}): coordinate {@code i} is the shape's plus the
 * offset, and a cell is found by binary search. An unmoved shape finds cells as its kind does.
 */
public final class Voxels {

    private final Shape shape;
    private final double ox;
    private final double oy;
    private final double oz;
    private final boolean moved;

    private Voxels(Shape shape, double ox, double oy, double oz, boolean moved) {
        this.shape = shape;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.moved = moved;
    }

    /** {@code shape.move(x, y, z)} (a block's shape at its position). The shape must not be empty. */
    public static Voxels moved(Shape shape, double x, double y, double z) {
        return new Voxels(shape, x, y, z, true);
    }

    /** The shape itself, not moved. */
    public static Voxels of(Shape shape) {
        return new Voxels(shape, 0.0, 0.0, 0.0, false);
    }

    public Shape shape() {
        return shape;
    }

    /** True for a moved shape: its cells are found by binary search, whatever its kind. */
    boolean moved() {
        return moved;
    }

    public boolean isEmpty() {
        return shape.isEmpty();
    }

    /** {@code DiscreteVoxelShape.getSize}: cells along the axis. */
    public int size(Axis axis) {
        return axis.choose(shape.sizeX(), shape.sizeY(), shape.sizeZ());
    }

    /** {@code VoxelShape.get(axis, index)}: {@code OffsetDoubleList.getDouble} adds the offset on every read. */
    public double coord(Axis axis, int index) {
        return switch (axis) {
            case X -> moved ? shape.x(index) + ox : shape.x(index);
            case Y -> moved ? shape.y(index) + oy : shape.y(index);
            case Z -> moved ? shape.z(index) + oz : shape.z(index);
        };
    }

    /** {@code DiscreteVoxelShape.isFullWide(int, int, int)}: false outside the grid. */
    public boolean isFullWide(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < shape.sizeX() && y < shape.sizeY() && z < shape.sizeZ() && shape.isFull(x, y, z);
    }

    /** Cell indices along {@code axis}, {@code along} and {@code other} given in the axis's own order. */
    boolean isFullWide(Axis axis, int along, int second, int third) {
        // collideX iterates (axis, then the two others); the result depends only on the layer along the axis.
        return switch (axis) {
            case X -> isFullWide(along, second, third);
            case Y -> isFullWide(third, along, second);
            case Z -> isFullWide(second, third, along);
        };
    }

    /**
     * {@code VoxelShape.findIndex}: the cell holding {@code value}, -1 below the first coordinate and
     * {@code size} above the last. {@code CubeVoxelShape}: {@code floor(clamp(value * size, -1, size))};
     * every other shape: {@code Mth.binarySearch(0, size + 1, i -> value < get(axis, i)) - 1}.
     */
    public int findIndex(Axis axis, double value) {
        int size = size(axis);
        if (!moved && shape.kind() == Shape.Kind.CUBE) {
            double scaled = value * size;
            return (int) Math.floor(scaled < -1.0 ? -1.0 : Math.min(scaled, size));
        }
        int min = 0;
        int length = size + 1;
        while (length > 0) {
            int half = length / 2;
            int middle = min + half;
            if (value < coord(axis, middle)) {
                length = half;
            } else {
                min = middle + 1;
                length -= half + 1;
            }
        }
        return min - 1;
    }

    /** {@code VoxelShape.min(axis)}: the coordinate where the first filled cell starts. */
    public double min(Axis axis) {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < shape.boxCount(); i++) {
            min = Math.min(min, shape.box(i, axis.ordinal()));
        }
        return moved ? min + axis.choose(ox, oy, oz) : min;
    }

    /** {@code VoxelShape.max(axis)}: the coordinate where the last filled cell ends. */
    public double max(Axis axis) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < shape.boxCount(); i++) {
            max = Math.max(max, shape.box(i, 3 + axis.ordinal()));
        }
        return moved ? max + axis.choose(ox, oy, oz) : max;
    }

    /** True if both are the same coordinate list ({@code Objects.equals} on two {@code DoubleList}s: element by element). */
    boolean sameCoords(Axis axis, Voxels other) {
        int n = size(axis);
        if (n != other.size(axis)) {
            return false;
        }
        for (int i = 0; i <= n; i++) {
            if (coord(axis, i) != other.coord(axis, i)) {
                return false;
            }
        }
        return true;
    }
}
