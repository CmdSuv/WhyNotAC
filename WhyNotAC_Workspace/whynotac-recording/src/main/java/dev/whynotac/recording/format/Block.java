package dev.whynotac.recording.format;

import java.util.Arrays;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One self-contained block: raw (uncompressed) record bytes plus the base values its deltas start
 * from. {@link #encode} produces the on-disk form; {@link #decodeHeader}/{@link #decompress} undo it.
 *
 * @param lostBefore events that belonged between the previous block and this one but were never
 *                   written (recorder back-pressure or pre-login buffer overflow)
 */
public record Block(long baseSequence, long baseNanoTime, long baseClientTick, int eventCount, long lostBefore, byte[] raw) {

    /** Fixed fields after the marker, as read from disk. */
    public record Header(int compressedLength, int rawLength, long baseSequence, long baseNanoTime, long baseClientTick,
            int eventCount, long lostBefore, int crc) {}

    /** Deflate output, reused per thread: a block is compressed once and copied into its exact-size array. */
    private static final ThreadLocal<byte[][]> SCRATCH = ThreadLocal.withInitial(() -> new byte[1][]);

    /** On-disk bytes: marker, header fields, CRC32C over fields and payload, zlib payload. */
    public byte[] encode(Deflater deflater) {
        byte[][] scratch = SCRATCH.get();
        int compressedLength = deflate(raw, deflater, scratch);
        byte[] compressed = scratch[0];
        ByteSink sink = new ByteSink(RecordingFormat.BLOCK_HEADER_BYTES + compressedLength);
        sink.writeBytes(RecordingFormat.BLOCK_MARKER);
        int fieldsStart = sink.size();
        sink.writeInt(compressedLength);
        sink.writeInt(raw.length);
        sink.writeLong(baseSequence);
        sink.writeLong(baseNanoTime);
        sink.writeLong(baseClientTick);
        sink.writeInt(eventCount);
        sink.writeLong(lostBefore);
        CRC32C crc = new CRC32C();
        crc.update(sink.array(), fieldsStart, sink.size() - fieldsStart);
        crc.update(compressed, 0, compressedLength);
        sink.writeInt((int) crc.getValue());
        sink.writeBytes(compressed, 0, compressedLength);
        return sink.size() == sink.array().length ? sink.array() : sink.toByteArray();
    }

    /** Parses the fixed header that follows a marker ({@code headerBytes} excludes the marker). */
    public static Header decodeHeader(byte[] headerBytes) {
        ByteSource in = new ByteSource(headerBytes);
        Header header = new Header(in.readInt(), in.readInt(), in.readLong(), in.readLong(), in.readLong(), in.readInt(), in.readLong(), in.readInt());
        if (header.compressedLength() < 0 || header.compressedLength() > RecordingFormat.MAX_BLOCK_BYTES + 1024
                || header.rawLength() < 0 || header.rawLength() > RecordingFormat.MAX_BLOCK_BYTES
                || header.eventCount() < 0 || header.lostBefore() < 0) {
            throw new RecordingFormatException("implausible block header " + header);
        }
        return header;
    }

    /** Verifies the checksum and inflates the payload. */
    public static byte[] verifyAndDecompress(byte[] headerBytes, Header header, byte[] compressed) {
        CRC32C crc = new CRC32C();
        crc.update(headerBytes, 0, headerBytes.length - 4);
        crc.update(compressed);
        if ((int) crc.getValue() != header.crc()) {
            throw new RecordingFormatException("block checksum mismatch at sequence " + header.baseSequence());
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] raw = new byte[header.rawLength()];
            int produced = 0;
            while (produced < raw.length && !inflater.finished()) {
                int n = inflater.inflate(raw, produced, raw.length - produced);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                produced += n;
            }
            if (produced != raw.length || !inflater.finished()) {
                throw new RecordingFormatException("block payload length mismatch");
            }
            return raw;
        } catch (DataFormatException e) {
            throw new RecordingFormatException("corrupt block payload: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    /** Compresses {@code input} into {@code scratch[0]} (grown as needed) and returns the compressed length. */
    private static int deflate(byte[] input, Deflater deflater, byte[][] scratch) {
        deflater.reset();
        deflater.setInput(input);
        deflater.finish();
        // zlib's worst case for stored blocks, plus header and checksum: rarely exceeded, grown if it is.
        int bound = input.length + (input.length >> 12) + (input.length >> 14) + 64;
        if (scratch[0] == null || scratch[0].length < bound) {
            scratch[0] = new byte[bound];
        }
        int length = 0;
        while (!deflater.finished()) {
            if (length == scratch[0].length) {
                scratch[0] = Arrays.copyOf(scratch[0], scratch[0].length * 2);
            }
            length += deflater.deflate(scratch[0], length, scratch[0].length - length);
        }
        return length;
    }
}
