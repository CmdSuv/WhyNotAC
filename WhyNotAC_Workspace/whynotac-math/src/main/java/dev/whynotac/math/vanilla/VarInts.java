package dev.whynotac.math.vanilla;

/** Minecraft's VarInt: 7 data bits per byte, least significant group first, high bit = "more follows". */
final class VarInts {

    private static final int MAX_BYTES = 5;

    private VarInts() {}

    static byte[] encode(int value) {
        byte[] buffer = new byte[MAX_BYTES];
        int length = 0;
        while ((value & ~0x7F) != 0) {
            buffer[length++] = (byte) (value & 0x7F | 0x80);
            value >>>= 7;
        }
        buffer[length++] = (byte) value;
        byte[] out = new byte[length];
        System.arraycopy(buffer, 0, out, 0, length);
        return out;
    }

    static int decode(byte[] in, int offset) {
        int value = 0;
        for (int i = 0; i < MAX_BYTES; i++) {
            byte b = in[offset + i];
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("VarInt too big");
    }
}
