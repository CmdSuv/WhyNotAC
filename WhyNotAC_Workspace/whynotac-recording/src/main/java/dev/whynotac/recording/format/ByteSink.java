package dev.whynotac.recording.format;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Growable big-endian byte buffer with LEB128 varints. Not thread-safe. */
public final class ByteSink {

    private byte[] buffer;
    private int size;

    public ByteSink(int initialCapacity) {
        buffer = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        if (size + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + extra));
        }
    }

    public ByteSink writeByte(int value) {
        ensure(1);
        buffer[size++] = (byte) value;
        return this;
    }

    public ByteSink writeBytes(byte[] bytes, int offset, int length) {
        ensure(length);
        System.arraycopy(bytes, offset, buffer, size, length);
        size += length;
        return this;
    }

    public ByteSink writeBytes(byte[] bytes) {
        return writeBytes(bytes, 0, bytes.length);
    }

    public ByteSink writeShort(int value) {
        ensure(2);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
        return this;
    }

    public ByteSink writeInt(int value) {
        ensure(4);
        buffer[size++] = (byte) (value >>> 24);
        buffer[size++] = (byte) (value >>> 16);
        buffer[size++] = (byte) (value >>> 8);
        buffer[size++] = (byte) value;
        return this;
    }

    public ByteSink writeLong(long value) {
        writeInt((int) (value >>> 32));
        return writeInt((int) value);
    }

    /** Raw IEEE-754 bits: NaN payloads and signed zeros survive. */
    public ByteSink writeFloat(float value) {
        return writeInt(Float.floatToRawIntBits(value));
    }

    /** Raw IEEE-754 bits: NaN payloads and signed zeros survive. */
    public ByteSink writeDouble(double value) {
        return writeLong(Double.doubleToRawLongBits(value));
    }

    /** Unsigned LEB128 (1 byte below 128, at most 10 bytes). */
    public ByteSink writeVarLong(long value) {
        ensure(10);
        while ((value & ~0x7FL) != 0) {
            buffer[size++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer[size++] = (byte) value;
        return this;
    }

    public ByteSink writeVarInt(int value) {
        return writeVarLong(value & 0xFFFF_FFFFL);
    }

    /** Signed values via ZigZag ({@code 0, -1, 1, -2 ... -> 0, 1, 2, 3 ...}), so small magnitudes stay small. */
    public ByteSink writeZigZag(long value) {
        return writeVarLong((value << 1) ^ (value >> 63));
    }

    public ByteSink writeUtf8(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    public int size() {
        return size;
    }

    public byte[] array() {
        return buffer;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    public void reset() {
        size = 0;
    }
}
