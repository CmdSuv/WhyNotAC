package dev.whynotac.recording.format;

import java.nio.charset.StandardCharsets;

/** Bounds-checked reader matching {@link ByteSink}. Every overrun is a {@link RecordingFormatException}. */
public final class ByteSource {

    /** A string can never exceed the block it lives in, so the block bound is the only consistent limit. */
    private static final int MAX_STRING_BYTES = RecordingFormat.MAX_BLOCK_BYTES;

    private final byte[] data;
    private int position;
    private final int limit;

    public ByteSource(byte[] data) {
        this(data, 0, data.length);
    }

    public ByteSource(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new RecordingFormatException("source window out of range");
        }
        this.data = data;
        this.position = offset;
        this.limit = offset + length;
    }

    private void need(int bytes) {
        if (bytes < 0 || limit - position < bytes) {
            throw new RecordingFormatException("unexpected end of data (need " + bytes + ", have " + (limit - position) + ")");
        }
    }

    public int readUnsignedByte() {
        need(1);
        return data[position++] & 0xFF;
    }

    public void readBytes(byte[] out) {
        need(out.length);
        System.arraycopy(data, position, out, 0, out.length);
        position += out.length;
    }

    public int readUnsignedShort() {
        need(2);
        int value = (data[position] & 0xFF) << 8 | (data[position + 1] & 0xFF);
        position += 2;
        return value;
    }

    public int readInt() {
        need(4);
        int value = (data[position] & 0xFF) << 24 | (data[position + 1] & 0xFF) << 16 | (data[position + 2] & 0xFF) << 8 | (data[position + 3] & 0xFF);
        position += 4;
        return value;
    }

    public long readLong() {
        return (long) readInt() << 32 | (readInt() & 0xFFFF_FFFFL);
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public long readVarLong() {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new RecordingFormatException("varint longer than 10 bytes");
    }

    public int readVarInt() {
        long value = readVarLong();
        if ((value >>> 32) != 0) {
            throw new RecordingFormatException("varint exceeds 32 bits");
        }
        return (int) value;
    }

    public long readZigZag() {
        long raw = readVarLong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    public String readUtf8() {
        int length = readVarInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new RecordingFormatException("string length " + length);
        }
        need(length);
        String value = new String(data, position, length, StandardCharsets.UTF_8);
        position += length;
        return value;
    }

    public void skip(int bytes) {
        need(bytes);
        position += bytes;
    }

    public int position() {
        return position;
    }

    public int remaining() {
        return limit - position;
    }

    /** A sub-source over the next {@code length} bytes; this source advances past them. */
    public ByteSource slice(int length) {
        need(length);
        ByteSource slice = new ByteSource(data, position, length);
        position += length;
        return slice;
    }
}
