package dev.whynotac.physics;

/** {@code BlockPos}: a block's integer position, ordered as {@code Vec3i.compareTo} orders them (y, then z, then x). */
public record BlockPos(int x, int y, int z) implements Comparable<BlockPos> {

    /** {@code BlockPos.containing}: the block holding the point. */
    public static BlockPos containing(double x, double y, double z) {
        return new BlockPos(Colliders.floor(x), Colliders.floor(y), Colliders.floor(z));
    }

    public BlockPos atY(int newY) {
        return new BlockPos(x, newY, z);
    }

    @Override
    public int compareTo(BlockPos other) {
        if (y == other.y) {
            return z == other.z ? x - other.x : z - other.z;
        }
        return y - other.y;
    }
}
