package dev.whynotac.recording.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.MemorySink;
import dev.whynotac.recording.write.RecordingWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingToolTest {

    @TempDir
    Path dir;

    private Path sample(boolean complete) throws Exception {
        MemorySink sink = new MemorySink();
        RecordingWriter writer = new RecordingWriter(sink, 1 << 16, Long.MAX_VALUE);
        writer.append(new TimelineEvent.Outbound(0, 0, new ClientboundPacket.Ping(0x574E0000)));
        writer.append(new TimelineEvent.Inbound(1, 1_000_000, 0, new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 12.5F, 3F, false, true, true, false)));
        writer.append(new TimelineEvent.Inbound(2, 2_000_000, 0, new ServerboundPacket.ClientTickEnd()));
        writer.append(new TimelineEvent.Inbound(3, 4_000_000, 1, new ServerboundPacket.Pong(0x574E0000)));
        writer.append(new TimelineEvent.Acknowledged(4, 4_000_000, 0x574E0000, 0, 0));
        writer.append(new TimelineEvent.Anomaly(5, 4_000_000, TimelineEvent.Anomaly.Kind.UNKNOWN_TRANSACTION, "pong \"x\""));
        if (complete) {
            writer.append(new TimelineEvent.Disconnected(6, 5_000_000));
            writer.close();
        }
        Path file = dir.resolve(complete ? "complete.wnrec" : "partial.wnrec.part");
        Files.write(file, sink.bytes());
        return file;
    }

    private static String run(int expectedExit, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = RecordingTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals(expectedExit, exit);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void inspectSummarisesAndReportsCompleteness() throws Exception {
        String json = run(0, "inspect", sample(true).toString());
        assertTrue(json.contains("\"complete\":true"), json);
        assertTrue(json.contains("\"client_ticks\":1"), json);
        assertTrue(json.contains("\"rtt_ms_max\":{\"f64\":\"4.0\""), json);
        assertTrue(json.contains("\"kind\":\"UNKNOWN_TRANSACTION\",\"detail\":\"pong \\\"x\\\"\""), json);
        run(2, "inspect", sample(false).toString());
        run(1, "inspect", dir.resolve("missing.wnrec").toString());
        run(1, "bogus");
    }

    @Test
    void dumpPrintsEveryEventWithRawFloatBits() throws Exception {
        String[] lines = run(0, "dump", sample(true).toString()).strip().split("\n");
        assertEquals(7, lines.length);
        assertTrue(lines[1].contains("\"yaw\":{\"f32\":\"12.5\",\"bits\":\"0x41480000\"}"), lines[1]);
        assertTrue(lines[1].contains("\"x\":{\"f64\":\"NaN\",\"bits\":\"0x7ff8000000000000\"}"), lines[1]);
    }
}
