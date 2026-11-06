package dev.whynotac.recording;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.ByteSource;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.write.RecordingWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A chunk body resent byte for byte is written once and referred to after (CB_LEVEL_CHUNK_REPEAT). */
class ChunkRepeatTest {

    private static byte[] body(int seed, int length) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static TimelineEvent chunk(long sequence, int x, int z, byte[] body) {
        return new TimelineEvent.Outbound(sequence, 1_000 + sequence, new ClientboundPacket.LevelChunk(x, z, body));
    }

    private static List<TimelineEvent> read(byte[] file) {
        List<TimelineEvent> events = new ArrayList<>();
        RecordingReader.of(file).forEach(events::add);
        return events;
    }

    /** Encodes {@code events} as one block; {@code sizes} gets each record's size. */
    private static byte[] encode(EventCodec.Encoder encoder, List<TimelineEvent> events, int[] sizes) {
        TimelineEvent first = events.getFirst();
        encoder.reset(first.sequence(), first.nanoTime(), 0);
        ByteSink sink = new ByteSink(1024);
        for (int i = 0; i < events.size(); i++) {
            int before = sink.size();
            encoder.encode(events.get(i), sink);
            sizes[i] = sink.size() - before;
        }
        return sink.toByteArray();
    }

    private static List<EventCodec.Decoded> decode(EventCodec.Decoder decoder, byte[] block, TimelineEvent first) {
        decoder.reset(first.sequence(), first.nanoTime(), 0);
        ByteSource in = new ByteSource(block);
        List<EventCodec.Decoded> out = new ArrayList<>();
        while (in.remaining() > 0) {
            out.add(decoder.decode(in));
        }
        return out;
    }

    @Test
    void aResentBodyIsWrittenOnceAndReadBackBitExactlyAcrossBlocks() {
        byte[] a = body(1, 3_000);
        byte[] b = body(2, 3_000);
        byte[] c = body(3, 3_000);
        List<TimelineEvent> events = new ArrayList<>();
        long sequence = 0;
        events.add(chunk(sequence++, 0, 0, a));
        events.add(chunk(sequence++, 1, 0, b));
        events.add(chunk(sequence++, 5, 5, a.clone())); // the same bytes at other coordinates, another array
        for (int i = 0; i < 200; i++) {
            events.add(new TimelineEvent.Outbound(sequence, 1_000 + sequence++, new ClientboundPacket.Ping(i)));
        }
        events.add(chunk(sequence++, 0, 0, a.clone()));
        events.add(chunk(sequence++, 2, 0, c));
        events.add(chunk(sequence++, 1, 0, b.clone()));
        events.add(chunk(sequence++, 3, 0, null));

        MemorySink sink = new MemorySink();
        RecordingWriter writer = new RecordingWriter(sink, 4_096, Long.MAX_VALUE);
        events.forEach(writer::append);
        writer.close();

        assertTrue(sink.acceptedBlocks().size() >= 3, "the repeats fall in later blocks: " + sink.acceptedBlocks().size());
        List<TimelineEvent> decoded = read(sink.bytes());
        assertEquals(events.size(), decoded.size());
        for (int i = 0; i < events.size(); i++) {
            BitExact.assertSame(events.get(i), decoded.get(i));
        }
        // three distinct bodies of incompressible bytes on disk, not six
        assertTrue(sink.bytes().length < 4 * 3_000, "file of " + sink.bytes().length + " bytes");
    }

    @Test
    void aRepeatOfABodyThisReaderNeverSawIsAGapNeverAnotherChunksBytes() {
        EventCodec.Encoder encoder = new EventCodec.Encoder();
        byte[] a = body(1, 100);
        List<TimelineEvent> first = List.of(chunk(0, 0, 0, a));
        List<TimelineEvent> second = List.of(chunk(1, 4, 4, a.clone()));
        byte[] stored = encode(encoder, first, new int[1]);
        int[] sizes = new int[1];
        byte[] repeat = encode(encoder, second, sizes);
        assertTrue(sizes[0] < 16, "a repeat record of " + sizes[0] + " bytes");

        // the stored block was read: the repeat is the chunk
        EventCodec.Decoder reader = new EventCodec.Decoder();
        decode(reader, stored, first.getFirst());
        EventCodec.Decoded resolved = decode(reader, repeat, second.getFirst()).getFirst();
        BitExact.assertSame(second.getFirst(), ((EventCodec.Decoded.Event) resolved).event());

        // the stored block was skipped (corrupt): an explicit one-event gap at the chunk's place
        EventCodec.Decoded lost = decode(new EventCodec.Decoder(), repeat, second.getFirst()).getFirst();
        TimelineEvent.Gap gap = assertInstanceOf(TimelineEvent.Gap.class, ((EventCodec.Decoded.Event) lost).event());
        assertEquals(new TimelineEvent.Gap(1, 1_001, 1), gap);

        // the slot holds another ordinal's body (the window moved on in a skipped block): still a gap
        EventCodec.Decoder moved = new EventCodec.Decoder();
        EventCodec.Encoder other = new EventCodec.Encoder();
        List<TimelineEvent> filler = new ArrayList<>();
        for (int i = 0; i <= RecordingFormat.CHUNK_WINDOW; i++) {
            filler.add(chunk(i, i, 0, body(1_000 + i, 16)));
        }
        decode(moved, encode(other, filler, new int[filler.size()]), filler.getFirst()); // slot 0 holds ordinal 512
        assertInstanceOf(TimelineEvent.Gap.class, ((EventCodec.Decoded.Event) decode(moved, repeat, second.getFirst()).getFirst()).event());
    }

    @Test
    void aBodyOutsideTheWindowIsStoredAgain() {
        EventCodec.Encoder encoder = new EventCodec.Encoder();
        byte[] a = body(1, 200);
        List<TimelineEvent> events = new ArrayList<>();
        long sequence = 0;
        events.add(chunk(sequence++, 0, 0, a));
        for (int i = 1; i < RecordingFormat.CHUNK_WINDOW; i++) {
            events.add(chunk(sequence++, i, 0, body(10_000 + i, 200)));
        }
        events.add(chunk(sequence++, 0, 0, a)); // the oldest body still in the window: a repeat
        events.add(chunk(sequence++, -1, 0, body(99, 200))); // stores ordinal 512 over ordinal 0
        events.add(chunk(sequence++, 0, 0, a)); // out of the window: stored again
        events.add(chunk(sequence++, 0, 1, a)); // and repeated from its new ordinal
        int[] sizes = new int[events.size()];
        byte[] block = encode(encoder, events, sizes);
        int n = events.size();
        assertTrue(sizes[n - 4] < 16, "repeat inside the window: " + sizes[n - 4]);
        assertTrue(sizes[n - 2] > 200, "stored again: " + sizes[n - 2]);
        assertTrue(sizes[n - 1] < 16, "repeat of the new ordinal: " + sizes[n - 1]);

        List<EventCodec.Decoded> decoded = decode(new EventCodec.Decoder(), block, events.getFirst());
        for (int i = 0; i < n; i++) {
            BitExact.assertSame(events.get(i), ((EventCodec.Decoded.Event) decoded.get(i)).event());
        }
    }

    @Test
    void theBodiesOfABlockTheWriterDroppedAreStoredAgain() {
        byte[] a = body(1, 3_000);
        MemorySink sink = new MemorySink(index -> index == 0);
        RecordingWriter writer = new RecordingWriter(sink, 2_048, Long.MAX_VALUE);
        writer.append(chunk(0, 0, 0, a)); // seals block 0 at once: dropped
        writer.append(chunk(1, 0, 0, a.clone()));
        writer.close();

        List<TimelineEvent> decoded = read(sink.bytes());
        TimelineEvent.Outbound resent = (TimelineEvent.Outbound) decoded.stream()
                .filter(e -> e.sequence() == 1).findFirst().orElseThrow();
        assertArrayEquals(a, ((ClientboundPacket.LevelChunk) resent.packet()).body());
        assertEquals(1, writer.lostEvents());
    }
}
