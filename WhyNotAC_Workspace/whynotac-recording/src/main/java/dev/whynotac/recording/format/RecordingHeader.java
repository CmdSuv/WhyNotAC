package dev.whynotac.recording.format;

import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * File header of a recording.
 *
 * @param subject          pseudonymous player id (never the real UUID)
 * @param startEpochMillis wall-clock time of the first recorded event, for filing and retention only;
 *                         all analysis uses the events' monotonic {@code nanoTime}
 */
public record RecordingHeader(int formatVersion, int protocolVersion, String gameVersion, String whynotacVersion,
        UUID recordingId, UUID subject, long startEpochMillis) {

    public byte[] encode() {
        ByteSink sink = new ByteSink(96);
        sink.writeBytes(RecordingFormat.MAGIC);
        sink.writeShort(formatVersion);
        sink.writeVarInt(protocolVersion);
        sink.writeUtf8(gameVersion);
        sink.writeUtf8(whynotacVersion);
        sink.writeLong(recordingId.getMostSignificantBits()).writeLong(recordingId.getLeastSignificantBits());
        sink.writeLong(subject.getMostSignificantBits()).writeLong(subject.getLeastSignificantBits());
        sink.writeLong(startEpochMillis);
        CRC32C crc = new CRC32C();
        crc.update(sink.array(), 0, sink.size());
        sink.writeInt((int) crc.getValue());
        return sink.toByteArray();
    }

    /** Decodes and verifies a header; returns the header and the number of bytes it occupied. */
    public static Parsed decode(byte[] bytes) {
        ByteSource in = new ByteSource(bytes);
        byte[] magic = new byte[RecordingFormat.MAGIC.length];
        in.readBytes(magic);
        if (!Arrays.equals(magic, RecordingFormat.MAGIC)) {
            throw new RecordingFormatException("not a WhyNotAC recording (bad magic)");
        }
        int version = in.readUnsignedShort();
        if (version < RecordingFormat.MIN_READABLE_VERSION || version > RecordingFormat.VERSION) {
            throw new RecordingFormatException("unsupported recording format version " + version);
        }
        int protocol = in.readVarInt();
        String game = in.readUtf8();
        String whynotac = in.readUtf8();
        UUID id = new UUID(in.readLong(), in.readLong());
        UUID subject = new UUID(in.readLong(), in.readLong());
        long start = in.readLong();
        int length = in.position();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, length);
        if ((int) crc.getValue() != in.readInt()) {
            throw new RecordingFormatException("header checksum mismatch");
        }
        return new Parsed(new RecordingHeader(version, protocol, game, whynotac, id, subject, start), in.position());
    }

    public record Parsed(RecordingHeader header, int length) {}
}
