package dev.whynotac.recording.read;

import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.format.ByteSource;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingFormatException;
import dev.whynotac.recording.format.RecordingHeader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Reads a recording, tolerating damage without ever inventing data.
 *
 * <ul>
 *   <li>A block whose checksum, length or content is wrong is skipped as a whole; the reader
 *       resynchronises on the next block marker and emits a {@link TimelineEvent.Gap} covering the
 *       sequence numbers that went missing.
 *   <li>Losses the writer itself recorded ({@code lostBefore}) become gaps at the right position.
 *   <li>A file that ends mid-block (crash, still being written) ends the iteration cleanly with
 *       {@link Report#truncatedTail()} set.
 * </ul>
 * {@link #report()} is final once iteration has finished.
 */
public final class RecordingReader implements Iterable<TimelineEvent> {

    /** Outcome of a read. {@code complete} means the end record was found and nothing was lost. */
    public record Report(long events, long blocks, long corruptBlocks, long gapsEmitted, long lostEvents,
            long unknownRecords, boolean endRecordFound, boolean truncatedTail) {
        public boolean complete() {
            return endRecordFound && corruptBlocks == 0 && lostEvents == 0 && !truncatedTail && unknownRecords == 0;
        }
    }

    private final byte[] data;
    private final RecordingHeader header;
    private final int bodyStart;

    private RecordingReader(byte[] data) {
        this.data = data;
        RecordingHeader.Parsed parsed = RecordingHeader.decode(data);
        this.header = parsed.header();
        this.bodyStart = parsed.length();
    }

    /** Opens a recording file; throws if the header itself is unreadable (then nothing can be trusted). */
    public static RecordingReader open(Path file) throws IOException {
        return new RecordingReader(Files.readAllBytes(file));
    }

    public static RecordingReader of(byte[] bytes) {
        return new RecordingReader(bytes.clone());
    }

    public RecordingHeader header() {
        return header;
    }

    private Report report;

    /** Available after a full iteration. */
    public Report report() {
        if (report == null) {
            throw new IllegalStateException("iterate the recording first");
        }
        return report;
    }

    @Override
    public Iterator<TimelineEvent> iterator() {
        return new Cursor();
    }

    private final class Cursor implements Iterator<TimelineEvent> {

        private int position = bodyStart;
        private final ArrayDeque<TimelineEvent> ready = new ArrayDeque<>();
        private final EventCodec.Decoder decoder = new EventCodec.Decoder();
        private long lastSequence = -1;
        private long writerLost;
        private boolean skippedCorruption;
        private boolean done;
        private long events;
        private long blocks;
        private long corruptBlocks;
        private long gaps;
        private long lost;
        private long unknown;
        private boolean end;
        private boolean truncated;

        @Override
        public boolean hasNext() {
            while (ready.isEmpty() && !done) {
                readBlock();
            }
            if (ready.isEmpty() && report == null) {
                report = new Report(events, blocks, corruptBlocks, gaps, Math.max(lost, writerLost), unknown, end, truncated);
            }
            return !ready.isEmpty();
        }

        @Override
        public TimelineEvent next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return ready.poll();
        }

        private void readBlock() {
            if (position >= data.length) {
                done = true;
                return;
            }
            int markerAt = position;
            if (data.length - position < RecordingFormat.BLOCK_HEADER_BYTES) {
                truncated = true;
                done = true;
                return;
            }
            if (!markerAt(position)) {
                corrupt(markerAt);
                return;
            }
            byte[] headerBytes = Arrays.copyOfRange(data, position + 4, position + RecordingFormat.BLOCK_HEADER_BYTES);
            Block.Header blockHeader;
            try {
                blockHeader = Block.decodeHeader(headerBytes);
            } catch (RecordingFormatException e) {
                corrupt(markerAt);
                return;
            }
            int payloadStart = position + RecordingFormat.BLOCK_HEADER_BYTES;
            if ((long) payloadStart + blockHeader.compressedLength() > data.length) {
                // Either the file ends inside this block (crash) or the length field is damaged.
                if (nextMarker(markerAt + 1) < 0) {
                    truncated = true;
                    done = true;
                } else {
                    corrupt(markerAt);
                }
                return;
            }
            byte[] compressed = Arrays.copyOfRange(data, payloadStart, payloadStart + blockHeader.compressedLength());
            ArrayDeque<TimelineEvent> decoded = new ArrayDeque<>();
            long decodedUnknown = 0;
            long blockEvents = 0;
            boolean decodedEnd = false;
            long declaredLost = -1;
            try {
                byte[] raw = Block.verifyAndDecompress(headerBytes, blockHeader, compressed);
                decoder.reset(blockHeader.baseSequence(), blockHeader.baseNanoTime(), blockHeader.baseClientTick());
                ByteSource in = new ByteSource(raw);
                while (in.remaining() > 0) {
                    switch (decoder.decode(in)) {
                        case EventCodec.Decoded.Event event -> {
                            decoded.add(event.event());
                            blockEvents++;
                        }
                        case EventCodec.Decoded.UnknownEvent unknownEvent -> {
                            // A newer writer's event kind: its slot becomes an explicit one-event gap.
                            decodedUnknown++;
                            blockEvents++;
                            decoded.add(new TimelineEvent.Gap(unknownEvent.sequence(), unknownEvent.nanoTime(), 1));
                        }
                        case EventCodec.Decoded.End endRecord -> {
                            decodedEnd = true;
                            declaredLost = endRecord.lostEvents();
                        }
                        case EventCodec.Decoded.UnknownMetadata ignored -> decodedUnknown++;
                    }
                }
                if (blockHeader.eventCount() != blockEvents) {
                    throw new RecordingFormatException("block declares " + blockHeader.eventCount() + " events, contains " + blockEvents);
                }
            } catch (RecordingFormatException e) {
                corrupt(markerAt);
                return;
            }
            blocks++;
            position = payloadStart + blockHeader.compressedLength();
            unknown += decodedUnknown;
            if (decodedEnd) {
                end = true;
                writerLost = declaredLost;
            }
            if (decoded.isEmpty()) {
                return;
            }
            // Sequence jumps alone are not loss: the live session numbers its own dropped events and
            // records an explicit Gap for them. Only a skipped corrupt block or a loss the writer
            // declared means events of this recording are missing here.
            long firstSequence = decoded.peekFirst().sequence();
            boolean evidenceOfLoss = skippedCorruption || blockHeader.lostBefore() > 0;
            if (lastSequence >= 0 && evidenceOfLoss) {
                long missing = firstSequence - lastSequence - 1;
                if (missing > 0) {
                    ready.add(new TimelineEvent.Gap(firstSequence - 1, decoded.peekFirst().nanoTime(), missing));
                    gaps++;
                }
                lost += Math.max(missing, blockHeader.lostBefore());
            } else if (lastSequence < 0) {
                lost += blockHeader.lostBefore();
            }
            skippedCorruption = false;
            for (TimelineEvent event : decoded) {
                lastSequence = event.sequence();
                ready.add(event);
            }
            events += blockEvents - decodedUnknown;
            gaps += decodedUnknown;
        }

        private void corrupt(int from) {
            corruptBlocks++;
            skippedCorruption = true;
            int next = nextMarker(from + 1);
            if (next < 0) {
                done = true;
            } else {
                position = next;
            }
        }

        private boolean markerAt(int at) {
            for (int i = 0; i < RecordingFormat.BLOCK_MARKER.length; i++) {
                if (at + i >= data.length || data[at + i] != RecordingFormat.BLOCK_MARKER[i]) {
                    return false;
                }
            }
            return true;
        }

        private int nextMarker(int from) {
            for (int i = Math.max(from, bodyStart); i <= data.length - RecordingFormat.BLOCK_MARKER.length; i++) {
                if (markerAt(i)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
