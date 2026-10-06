package dev.whynotac.protocol.packet;

/** {@code net.minecraft.world.entity.Relative}: which teleport components are relative to the current state. */
public enum RelativeFlag {
    X,
    Y,
    Z,
    Y_ROT,
    X_ROT,
    DELTA_X,
    DELTA_Y,
    DELTA_Z,
    ROTATE_DELTA
}
