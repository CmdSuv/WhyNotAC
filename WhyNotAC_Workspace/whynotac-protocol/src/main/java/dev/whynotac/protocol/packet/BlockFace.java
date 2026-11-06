package dev.whynotac.protocol.packet;

/** {@code net.minecraft.core.Direction}, in the protocol's 3D-data-value order. */
public enum BlockFace {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    private final int stepX;
    private final int stepY;
    private final int stepZ;

    BlockFace(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    /** {@code Direction.getStepX()}: the offset to the neighbouring block on this face. */
    public int stepX() {
        return stepX;
    }

    public int stepY() {
        return stepY;
    }

    public int stepZ() {
        return stepZ;
    }
}
