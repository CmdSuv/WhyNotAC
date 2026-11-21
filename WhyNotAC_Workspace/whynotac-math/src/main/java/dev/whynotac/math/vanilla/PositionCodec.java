package dev.whynotac.math.vanilla;

import dev.whynotac.math.vector.Vec3d;

/**
 * Bit-exact model of {@code net.minecraft.network.protocol.game.VecDeltaCodec} (1.21.11): how the client
 * turns an entity's relative move ({@code ClientboundMoveEntityPacket}, deltas in 1/4096 block) into a
 * position, against the base it keeps per entity. Every absolute position packet sets the base; every
 * relative one decodes against it and then becomes the base.
 *
 * <p>The decoded coordinate is {@code (round(base * 4096) + delta) / 4096}: a position on the 1/4096
 * grid, not {@code base + delta / 4096}. A zero delta keeps the base's own coordinate unchanged (also
 * off the grid), and an all-zero delta returns the base itself.
 */
public final class PositionCodec {

    private PositionCodec() {}

    /** {@code VecDeltaCodec.encode(double)}: {@code Math.round(value * 4096.0)}. */
    public static long encode(double value) {
        return Math.round(value * 4096.0);
    }

    /** {@code VecDeltaCodec.decode(long)}: {@code value / 4096.0}. */
    public static double decode(long value) {
        return value / 4096.0;
    }

    /** {@code VecDeltaCodec.decode(long, long, long)} against {@code base}. */
    public static Vec3d decode(Vec3d base, long xa, long ya, long za) {
        if (xa == 0L && ya == 0L && za == 0L) {
            return base;
        }
        double x = xa == 0L ? base.x() : decode(encode(base.x()) + xa);
        double y = ya == 0L ? base.y() : decode(encode(base.y()) + ya);
        double z = za == 0L ? base.z() : decode(encode(base.z()) + za);
        return new Vec3d(x, y, z);
    }

    /** {@code VecDeltaCodec.encodeX(Vec3)} and its siblings: the delta the server writes for {@code target}. */
    public static long delta(double base, double target) {
        return encode(target) - encode(base);
    }
}
