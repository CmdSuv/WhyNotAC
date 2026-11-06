package dev.whynotac.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.write.RecordingWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reader may lose data it cannot verify, but it must never return an event that was not recorded. */
class ReaderRobustnessTest {

    private record Recorded(List<TimelineEvent> events, MemorySink sink) {}

    private static Recorded record(int events, int blockBytes, java.util.function.IntPredicate reject) {
        Generators gen = new Generators(events * 31L + blockBytes);
        List<TimelineEvent> stream = gen.stream(events);
        MemorySink sink = new MemorySink(reject);
        RecordingWriter writer = new RecordingWriter(sink, blockBytes, Long.MAX_VALUE);
        stream.forEach(writer::append);
        writer.close();
        return new Recorded(stream, sink);
    }

    private static List<TimelineEvent> readAll(RecordingReader reader) {
        List<TimelineEvent> out = new ArrayList<>();
        reader.forEach(out::add);
        return out;
    }

    @Test
    void completeRecordingReadsBackExactlyAcrossManyBlocks() {
        Recorded recorded = record(20_000, 4096, i -> false);
        assertTrue(recorded.sink().blockOffsets().size() > 20, "test needs many blocks");
        RecordingReader reader = RecordingReader.of(recorded.sink().bytes());
        List<TimelineEvent> read = readAll(reader);
        assertEquals(recorded.events().size(), read.size());
        for (int i = 0; i < read.size(); i++) {
            BitExact.assertSame(recorded.events().get(i), read.get(i));
        }
        assertTrue(reader.report().complete(), reader.report().toString());
        assertEquals(MemorySink.HEADER, reader.header());
    }

    @Test
    void truncationAtEveryByteYieldsAnExactPrefix() {
        Recorded recorded = record(600, 1024, i -> false);
        byte[] full = recorded.sink().bytes();
        int headerLength = MemorySink.HEADER.encode().length;
        for (int cut = headerLength; cut < full.length; cut++) {
            RecordingReader reader = RecordingReader.of(Arrays.copyOf(full, cut));
            List<TimelineEvent> read = readAll(reader);
            assertTrue(read.size() <= recorded.events().size());
            for (int i = 0; i < read.size(); i++) {
                assertEquals(recorded.events().get(i), read.get(i), "cut at " + cut + ", event " + i);
            }
            assertFalse(reader.report().complete(), "a cut file must never claim completeness (cut " + cut + ")");
        }
    }

    @Test
    void aDamagedBlockCostsExactlyItsOwnEventsAndBecomesAGap() {
        Recorded recorded = record(3000, 2048, i -> false);
        byte[] bytes = recorded.sink().bytes();
        List<Integer> offsets = recorded.sink().blockOffsets();
        int victim = offsets.size() / 2;
        Block lostBlock = recorded.sink().acceptedBlocks().get(victim);
        bytes[offsets.get(victim) + 60] ^= 0x01; // inside the compressed payload
        RecordingReader reader = RecordingReader.of(bytes);
        List<TimelineEvent> read = readAll(reader);

        assertEquals(1, reader.report().corruptBlocks());
        List<TimelineEvent> expected = new ArrayList<>(recorded.events());
        int firstLost = indexOfSequence(expected, lostBlock.baseSequence());
        List<TimelineEvent> lost = expected.subList(firstLost, firstLost + lostBlock.eventCount());
        long lastLostSequence = lost.getLast().sequence();
        long nextSequence = expected.get(firstLost + lostBlock.eventCount()).sequence();
        long previousSequence = expected.get(firstLost - 1).sequence();
        lost.clear();

        TimelineEvent.Gap gap = (TimelineEvent.Gap) read.get(firstLost);
        assertEquals(nextSequence - previousSequence - 1, gap.droppedEvents());
        assertTrue(gap.sequence() >= lastLostSequence && gap.sequence() < nextSequence);
        read.remove(firstLost);
        assertEquals(expected, read);
        assertFalse(reader.report().complete());
    }

    @Test
    void writerSideLossesBecomeGapsAtTheRightPlace() {
        Recorded recorded = record(5000, 2048, i -> i % 4 == 1); // every 4th block rejected by "back-pressure"
        RecordingReader reader = RecordingReader.of(recorded.sink().bytes());
        List<TimelineEvent> read = readAll(reader);
        List<TimelineEvent> withoutGaps = new ArrayList<>();
        long gapEvents = 0;
        for (TimelineEvent event : read) {
            if (event instanceof TimelineEvent.Gap gap && !recorded.events().contains(event)) {
                gapEvents += gap.droppedEvents();
            } else {
                withoutGaps.add(event);
            }
        }
        // Everything that was read is genuine and in order.
        int cursor = 0;
        for (TimelineEvent event : withoutGaps) {
            while (!recorded.events().get(cursor).equals(event)) {
                cursor++;
            }
        }
        assertTrue(reader.report().lostEvents() > 0);
        assertFalse(reader.report().complete());
        assertTrue(gapEvents >= recorded.events().size() - withoutGaps.size(), "every missing event is covered by a gap");
    }

    @Test
    void garbageAfterTheHeaderIsReportedNotDecoded() {
        byte[] header = MemorySink.HEADER.encode();
        byte[] junk = new byte[10_000];
        new java.util.Random(1).nextBytes(junk);
        byte[] file = new byte[header.length + junk.length];
        System.arraycopy(header, 0, file, 0, header.length);
        System.arraycopy(junk, 0, file, header.length, junk.length);
        RecordingReader reader = RecordingReader.of(file);
        assertTrue(readAll(reader).isEmpty());
        assertFalse(reader.report().complete());
    }

    @Test
    void unknownFutureEventKindBecomesAOneEventGap() {
        // Hand-craft a block with a record tag this version does not know (tag 42, valid event prefix).
        dev.whynotac.recording.format.ByteSink raw = new dev.whynotac.recording.format.ByteSink(64);
        dev.whynotac.recording.format.EventCodec.Encoder encoder = new dev.whynotac.recording.format.EventCodec.Encoder();
        encoder.reset(1, 100, 0);
        encoder.encode(new TimelineEvent.Gap(1, 100, 0), raw);
        raw.writeVarInt(42).writeVarInt(3).writeVarLong(1).writeZigZag(5).writeByte(0x7F);
        MemorySink sink = new MemorySink();
        sink.accept(new Block(1, 100, 0, 2, 0, raw.toByteArray()));
        sink.close(2, 0);
        RecordingReader reader = RecordingReader.of(sink.bytes());
        List<TimelineEvent> read = readAll(reader);
        assertEquals(2, read.size());
        TimelineEvent.Gap unknown = assertInstanceOf(TimelineEvent.Gap.class, read.get(1));
        assertEquals(2, unknown.sequence());
        assertEquals(105, unknown.nanoTime());
        assertEquals(1, reader.report().unknownRecords());
        assertFalse(reader.report().complete());
    }

    @Test
    void unknownPacketKindInsideAKnownEventKeepsTheEvent() {
        // A newer writer's packet tag (99) inside an Inbound record: the event, its time and tick survive.
        dev.whynotac.recording.format.ByteSink body = new dev.whynotac.recording.format.ByteSink(32);
        body.writeVarLong(1).writeZigZag(7).writeZigZag(2).writeVarInt(99).writeInt(0xCAFE);
        dev.whynotac.recording.format.ByteSink raw = new dev.whynotac.recording.format.ByteSink(32);
        raw.writeVarInt(dev.whynotac.recording.format.RecordingFormat.TAG_INBOUND).writeVarInt(body.size()).writeBytes(body.toByteArray());
        MemorySink sink = new MemorySink();
        sink.accept(new Block(10, 1000, 5, 1, 0, raw.toByteArray()));
        sink.close(1, 0);
        RecordingReader reader = RecordingReader.of(sink.bytes());
        List<TimelineEvent> read = readAll(reader);
        assertEquals(List.of(new TimelineEvent.Inbound(10, 1007, 7,
                new dev.whynotac.protocol.packet.ServerboundPacket.Unmodeled(dev.whynotac.recording.format.RecordingFormat.UNKNOWN_PACKET_PREFIX + 99))), read);
        assertEquals(0, reader.report().corruptBlocks());
    }

    private static int indexOfSequence(List<TimelineEvent> events, long sequence) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).sequence() == sequence) {
                return i;
            }
        }
        throw new AssertionError("sequence " + sequence);
    }
}
