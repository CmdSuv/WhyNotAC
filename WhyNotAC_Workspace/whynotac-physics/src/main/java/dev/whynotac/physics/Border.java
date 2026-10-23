package dev.whynotac.physics;

import dev.whynotac.world.Shape;

/**
 * The client's world border at the player's move, as its collision reads it: the extent's bounds at partial tick 0
 * ({@code WorldBorder.getMinX()} and the rest, clamped to the absolute maximum; a moving extent's previous size).
 */
public record Border(double minX, double minZ, double maxX, double maxZ) {

    /**
     * {@code WorldBorder.isInsideCloseToBorder(entity, box)} for an entity at {@code (x, z)}: nearer the border than twice
     * the box's larger horizontal size (at least 1), and inside it by that much.
     */
    public boolean closeTo(double x, double z, Box box) {
        double margin = Math.max(Math.max(Math.abs(box.maxX() - box.minX()), Math.abs(box.maxZ() - box.minZ())), 1.0);
        return distance(x, z) < margin * 2.0 && within(x, z, margin);
    }

    /** {@code WorldBorder.getDistanceToBorder(x, z)}. */
    public double distance(double x, double z) {
        double toMinZ = z - minZ;
        double toMaxZ = maxZ - z;
        double toMinX = x - minX;
        double toMaxX = maxX - x;
        double distance = Math.min(toMinX, toMaxX);
        distance = Math.min(distance, toMinZ);
        return Math.min(distance, toMaxZ);
    }

    /** {@code WorldBorder.isWithinBounds(x, z, margin)}. */
    public boolean within(double x, double z, double margin) {
        return x >= minX - margin && x < maxX + margin && z >= minZ - margin && z < maxZ + margin;
    }

    /**
     * {@code getCollisionShape()}: {@code Shapes.join(INFINITY, box(floor(minX), -inf, floor(minZ), ceil(maxX), inf,
     * ceil(maxZ)), ONLY_FIRST)}, optimized as the game does. Its coordinates are the infinities and the box's rounded
     * bounds; every cell but the box's is full, the boxes in {@code forAllBoxes} order. A box thinner than
     * {@link VoxelCollisions#EPSILON} on an axis is empty ({@code Shapes.box}): the shape is then {@code INFINITY}'s
     * single cell.
     */
    public Voxels shape() {
        double inf = Double.POSITIVE_INFINITY;
        double x0 = Math.floor(minX);
        double x1 = Math.ceil(maxX);
        double z0 = Math.floor(minZ);
        double z1 = Math.ceil(maxZ);
        if (x1 - x0 < VoxelCollisions.EPSILON || z1 - z0 < VoxelCollisions.EPSILON) {
            return Voxels.of(new Shape(Shape.Kind.ARRAY, new double[] {-inf, inf}, new double[] {-inf, inf}, new double[] {-inf, inf},
                    new long[] {1L}, new double[] {-inf, -inf, -inf, inf, inf, inf}));
        }
        // cells x * 3 + z (one y cell): all but the middle (1, 1), bit 4
        long full = 0b1_1110_1111L;
        return Voxels.of(new Shape(Shape.Kind.ARRAY, new double[] {-inf, x0, x1, inf}, new double[] {-inf, inf}, new double[] {-inf, z0, z1, inf},
                new long[] {full}, new double[] {
                    -inf, -inf, -inf, x0, inf, inf, // x 0, z 0..3
                    x0, -inf, -inf, inf, inf, z0,   // x 1..3, z 0
                    x0, -inf, z1, inf, inf, inf,    // x 1..3, z 2
                    x1, -inf, z0, inf, inf, z1}));  // x 2, z 1
    }
}
