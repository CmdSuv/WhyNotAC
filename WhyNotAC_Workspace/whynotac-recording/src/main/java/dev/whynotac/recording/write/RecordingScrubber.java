package dev.whynotac.recording.write;

import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.read.RecordingReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * A player's pseudonym in other players' recordings: their player list and the entities near them
 * ({@link RecordingUuids}). {@link #scrub} rewrites a finished recording with that pseudonym replaced by
 * a fresh random UUID, the same one throughout the file, so the recording replays exactly as before
 * (the replicas link a player-list entry to its entity by equality only) but no longer links to the
 * player.
 */
public final class RecordingScrubber {

    private RecordingScrubber() {}

    /** How many times {@code pseudonym} occurs in the recording's events. */
    public static long mentions(Path file, UUID pseudonym) throws IOException {
        long n = 0;
        for (TimelineEvent event : RecordingReader.open(file)) {
            n += RecordingUuids.count(event, pseudonym);
        }
        return n;
    }

    /**
     * Rewrites the finished recording {@code file} with every occurrence of {@code pseudonym} replaced, and
     * returns how many there were. The new file is written beside it, read back and checked (the same
     * events, no occurrence left), then moved over it atomically; on any failure the original stays.
     */
    public static long scrub(Path file, UUID pseudonym) throws IOException {
        RecordingReader reader = RecordingReader.open(file);
        UUID replacement = UUID.randomUUID();
        Path temp = file.resolveSibling(file.getFileName() + ".scrub");
        long replaced = 0;
        long events = 0;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                Deflater deflater = new Deflater(Deflater.BEST_SPEED);
                IOException[] failure = {null};
                write(channel, reader.header().encode());
                BlockSink sink = new BlockSink() {
                    @Override
                    public boolean accept(Block block) {
                        try {
                            write(channel, block.encode(deflater));
                        } catch (IOException e) {
                            failure[0] = e;
                        }
                        return true;
                    }

                    @Override
                    public void close(long totalEvents, long lostEvents) {
                        ByteSink end = new ByteSink(32);
                        // the original's loss (read once the events were), now explicit Gap events
                        EventCodec.Encoder.encodeEnd(totalEvents, reader.report().lostEvents(), end);
                        accept(new Block(0, 0, 0, 0, 0, end.toByteArray()));
                    }
                };
                RecordingWriter writer = new RecordingWriter(sink, RecordingWriter.DEFAULT_TARGET_BLOCK_BYTES, Long.MAX_VALUE);
                for (TimelineEvent event : reader) {
                    replaced += RecordingUuids.count(event, pseudonym);
                    writer.append(RecordingUuids.map(event, id -> id.equals(pseudonym) ? replacement : id));
                    events++;
                }
                writer.close();
                deflater.end();
                if (failure[0] != null) {
                    throw failure[0];
                }
                channel.force(true);
            }
            long readBack = 0;
            for (TimelineEvent event : RecordingReader.open(temp)) {
                if (RecordingUuids.count(event, pseudonym) != 0) {
                    throw new IOException("the rewritten recording still holds the pseudonym");
                }
                readBack++;
            }
            if (readBack != events) {
                throw new IOException("the rewritten recording has " + readBack + " events, the original " + events);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return replaced;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
