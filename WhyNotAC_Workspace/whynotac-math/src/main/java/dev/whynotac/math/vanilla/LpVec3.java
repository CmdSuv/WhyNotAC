package dev.whynotac.math.vanilla;

import dev.whynotac.math.vector.Vec3d;

/**
 * Bit-exact model of {@code net.minecraft.network.LpVec3} (1.21.11), the lossy "low precision"
 * velocity encoding used by {@code ClientboundSetEntityMotionPacket} and {@code ClientboundAddEntityPacket}.
 *
 * <p><b>Why this matters:</b> knockback reaches the client through this codec. The client applies the
 * <em>decoded</em> vector, not the one the server computed. A simulator that uses the server-side
 * velocity is wrong by up to {@link #maxAxisError(Vec3d)} per axis (about 3.05e-5 for normal
 * knockback), which is 30x the 1e-6 analysis grid. WhyNotAC therefore feeds the physics layer
 * {@link #quantize(Vec3d)}, the exact value the client will see.
 *
 * <h2>Wire format</h2>
 * The scale {@code s = ceil(max |component|)} is stored in 2 low bits (plus a VarInt continuation for
 * {@code s > 3}). Each component {@code c} is stored in 15 bits as
 * {@code round((c / s * 0.5 + 0.5) * 32766)}. Layout of the 48-bit little-endian word:
 * bits 0-1 scale, bit 2 continuation, bits 3-17 x, 18-32 y, 33-47 z. A first byte of 0 means
 * {@link Vec3d#ZERO}, which is what every vector with max component below
 * {@link #ABS_MIN_VALUE} becomes.
 */
public final class LpVec3 {

    private static final int DATA_BITS_MASK = 32767;
    private static final double MAX_QUANTIZED_VALUE = 32766.0;
    private static final int SCALE_BITS_MASK = 3;
    private static final int CONTINUATION_FLAG = 4;
    private static final int X_OFFSET = 3;
    private static final int Y_OFFSET = 18;
    private static final int Z_OFFSET = 33;

    /** Components are clamped to +/- this before encoding. */
    public static final double ABS_MAX_VALUE = 1.7179869183E10;
    /** Vectors whose largest component is below this encode as zero. */
    public static final double ABS_MIN_VALUE = 3.051944088384301E-5;

    private LpVec3() {}

    /** The vector the client decodes after the server sends {@code v}. */
    public static Vec3d quantize(Vec3d v) {
        return decode(encode(v));
    }

    /**
     * Encodes exactly as {@code LpVec3.write} would into a Netty buffer: 1 byte for zero, otherwise
     * 6 bytes ({@code byte, byte, big-endian int}) plus an optional VarInt.
     */
    public static byte[] encode(Vec3d v) {
        double x = sanitize(v.x());
        double y = sanitize(v.y());
        double z = sanitize(v.z());
        double max = VanillaMath.absMax(x, VanillaMath.absMax(y, z));
        if (max < ABS_MIN_VALUE) {
            return new byte[] {0};
        }
        long scale = VanillaMath.ceilLong(max);
        boolean continuation = (scale & SCALE_BITS_MASK) != scale;
        long header = continuation ? scale & SCALE_BITS_MASK | CONTINUATION_FLAG : scale;
        long packed = header | pack(x / scale) << X_OFFSET | pack(y / scale) << Y_OFFSET | pack(z / scale) << Z_OFFSET;

        byte[] varInt = continuation ? VarInts.encode((int) (scale >> 2)) : new byte[0];
        byte[] out = new byte[6 + varInt.length];
        out[0] = (byte) packed;
        out[1] = (byte) (packed >> 8);
        int high = (int) (packed >> 16);
        out[2] = (byte) (high >>> 24);
        out[3] = (byte) (high >>> 16);
        out[4] = (byte) (high >>> 8);
        out[5] = (byte) high;
        System.arraycopy(varInt, 0, out, 6, varInt.length);
        return out;
    }

    /** Decodes exactly as {@code LpVec3.read} does. */
    public static Vec3d decode(byte[] in) {
        int b0 = in[0] & 0xFF;
        if (b0 == 0) {
            return Vec3d.ZERO;
        }
        int b1 = in[1] & 0xFF;
        long high = ((long) (in[2] & 0xFF) << 24 | (in[3] & 0xFF) << 16 | (in[4] & 0xFF) << 8 | (in[5] & 0xFF)) & 0xFFFFFFFFL;
        long packed = high << 16 | b1 << 8 | b0;
        long scale = b0 & SCALE_BITS_MASK;
        if ((b0 & CONTINUATION_FLAG) == CONTINUATION_FLAG) {
            scale |= (VarInts.decode(in, 6) & 0xFFFFFFFFL) << 2;
        }
        return new Vec3d(unpack(packed >> X_OFFSET) * scale, unpack(packed >> Y_OFFSET) * scale, unpack(packed >> Z_OFFSET) * scale);
    }

    /**
     * Upper bound on the per-axis quantization error for {@code v}: half a quantization step,
     * {@code s / 32766}, plus slack for the final double roundings. Zero-encoded vectors are bounded
     * by {@link #ABS_MIN_VALUE}.
     */
    public static double maxAxisError(Vec3d v) {
        double max = VanillaMath.absMax(sanitize(v.x()), VanillaMath.absMax(sanitize(v.y()), sanitize(v.z())));
        if (max < ABS_MIN_VALUE) {
            return ABS_MIN_VALUE;
        }
        long scale = VanillaMath.ceilLong(max);
        return scale / MAX_QUANTIZED_VALUE + Math.ulp((double) scale) * 4;
    }

    private static double sanitize(double value) {
        return Double.isNaN(value) ? 0.0 : Math.clamp(value, -ABS_MAX_VALUE, ABS_MAX_VALUE);
    }

    private static long pack(double value) {
        return Math.round((value * 0.5 + 0.5) * MAX_QUANTIZED_VALUE);
    }

    private static double unpack(long value) {
        return Math.min(value & DATA_BITS_MASK, MAX_QUANTIZED_VALUE) * 2.0 / MAX_QUANTIZED_VALUE - 1.0;
    }
}
