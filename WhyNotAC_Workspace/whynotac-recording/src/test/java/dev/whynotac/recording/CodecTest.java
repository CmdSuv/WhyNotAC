package dev.whynotac.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.ByteSource;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.format.RecordingHeader;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.write.RecordingWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodecTest {

    private static List<TimelineEvent> roundTrip(List<TimelineEvent> events) {
        EventCodec.Encoder encoder = new EventCodec.Encoder();
        ByteSink sink = new ByteSink(1024);
        TimelineEvent first = events.getFirst();
        long baseTick = 0;
        encoder.reset(first.sequence(), first.nanoTime(), baseTick);
        for (TimelineEvent event : events) {
            encoder.encode(event, sink);
        }
        EventCodec.Decoder decoder = new EventCodec.Decoder();
        decoder.reset(first.sequence(), first.nanoTime(), baseTick);
        ByteSource in = new ByteSource(sink.toByteArray());
        List<TimelineEvent> out = new ArrayList<>();
        while (in.remaining() > 0) {
            out.add(((EventCodec.Decoded.Event) decoder.decode(in)).event());
        }
        return out;
    }

    @Test
    void everyPacketAndEventTypeRoundTripsBitExactly() {
        Generators gen = new Generators(774);
        Set<Class<?>> seenServerbound = new HashSet<>();
        Set<Class<?>> seenClientbound = new HashSet<>();
        Set<Class<?>> seenEvents = new HashSet<>();
        for (int round = 0; round < 200; round++) {
            List<TimelineEvent> events = gen.stream(500);
            for (TimelineEvent e : events) {
                seenEvents.add(e.getClass());
                if (e instanceof TimelineEvent.Inbound in) {
                    seenServerbound.add(in.packet().getClass());
                } else if (e instanceof TimelineEvent.Outbound out) {
                    seenClientbound.add(out.packet().getClass());
                }
            }
            List<TimelineEvent> decoded = roundTrip(events);
            assertEquals(events.size(), decoded.size());
            for (int i = 0; i < events.size(); i++) {
                BitExact.assertSame(events.get(i), decoded.get(i));
            }
        }
        // Exhaustiveness: the generators (and therefore the codec) covered every sealed subtype.
        assertEquals(Set.copyOf(Generators.SERVERBOUND), seenServerbound);
        assertEquals(Set.copyOf(Generators.CLIENTBOUND), seenClientbound);
        Set<Class<?>> expectedEvents = new HashSet<>(Generators.EVENTS);
        expectedEvents.remove(TimelineEvent.Disconnected.class);
        assertEquals(expectedEvents, seenEvents);
        TimelineEvent disconnect = new TimelineEvent.Disconnected(9, -5);
        BitExact.assertSame(disconnect, roundTrip(List.of(disconnect)).getFirst());
    }

    @Test
    void nonIncreasingSequenceIsRejectedAtWriteTime() {
        EventCodec.Encoder encoder = new EventCodec.Encoder();
        encoder.reset(10, 0, 0);
        ByteSink sink = new ByteSink(64);
        encoder.encode(new TimelineEvent.Disconnected(10, 0), sink);
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(new TimelineEvent.Disconnected(10, 1), sink));
    }

    @Test
    void headerRoundTripsAndDetectsDamage() {
        byte[] bytes = MemorySink.HEADER.encode();
        assertEquals(MemorySink.HEADER, RecordingHeader.decode(bytes).header());
        for (int i = 0; i < bytes.length; i++) {
            byte[] damaged = bytes.clone();
            damaged[i] ^= 0x40;
            assertThrows(RuntimeException.class, () -> RecordingHeader.decode(damaged), "flip at " + i + " went unnoticed");
        }
    }

    @Test
    void typicalMovementCostsFewBytesPerEvent() {
        // Realistic stream: Rot packets + tick ends + transactions, 20 ticks per second.
        MemorySink sink = new MemorySink();
        RecordingWriter writer = new RecordingWriter(sink, RecordingWriter.DEFAULT_TARGET_BLOCK_BYTES, RecordingWriter.DEFAULT_MAX_BLOCK_AGE_NANOS);
        long seq = 0;
        long t = 0;
        float yaw = 0;
        for (int tick = 0; tick < 72_000; tick++) { // one hour
            t += 50_000_000L;
            yaw += 0.15F * ((tick % 7) - 3);
            writer.append(new TimelineEvent.Inbound(seq++, t, tick, new dev.whynotac.protocol.packet.ServerboundPacket.MovePlayer(
                    100.5 + tick * 1e-3, 64, -20.25, yaw, 10F, true, true, true, false)));
            writer.append(new TimelineEvent.Inbound(seq++, t + 10_000, tick, new dev.whynotac.protocol.packet.ServerboundPacket.ClientTickEnd()));
            writer.append(new TimelineEvent.Outbound(seq++, t + 20_000, new dev.whynotac.protocol.packet.ClientboundPacket.Ping(0x574E0000 | tick & 0xFFFF)));
            writer.append(new TimelineEvent.Inbound(seq++, t + 2_020_000, tick, new dev.whynotac.protocol.packet.ServerboundPacket.Pong(0x574E0000 | tick & 0xFFFF)));
            writer.append(new TimelineEvent.Acknowledged(seq++, t + 2_020_000, 0x574E0000 | tick & 0xFFFF, t + 20_000, 0));
        }
        writer.close();
        byte[] file = sink.bytes();
        double perEvent = file.length / (double) writer.totalEvents();
        double megabytesPerHour = file.length / 1048576.0;
        assertTrue(perEvent < 12, "bytes per event " + perEvent);
        RecordingReader reader = RecordingReader.of(file);
        long count = 0;
        for (TimelineEvent ignored : reader) {
            count++;
        }
        assertEquals(writer.totalEvents(), count);
        assertTrue(reader.report().complete());
        System.out.printf("one hour of play: %.2f MB, %.2f bytes/event%n", megabytesPerHour, perEvent);
    }
}
