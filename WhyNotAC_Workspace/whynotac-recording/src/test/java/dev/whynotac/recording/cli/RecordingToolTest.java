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
import java.util.List;
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
    void findAndForgetActOnExactlyOnePlayersRecordings() throws Exception {
        byte[] secret = dev.whynotac.recording.write.Pseudonymizer.newSecret();
        Path secretFile = Files.write(dir.resolve("recording-secret.key"), secret);
        Path recordings = dir.resolve("recordings");
        dev.whynotac.recording.write.RecordingStore store = new dev.whynotac.recording.write.RecordingStore(recordings,
                dev.whynotac.recording.write.RecordingStore.Settings.defaults(), java.util.logging.Logger.getLogger("test"));
        dev.whynotac.protocol.session.SessionRegistry registry = new dev.whynotac.protocol.session.SessionRegistry(Runnable::run, 1024,
                (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new dev.whynotac.recording.write.RecordingListener(store, new dev.whynotac.recording.write.Pseudonymizer(secret),
                774, "1.21.11", "test"));
        java.util.UUID alice = java.util.UUID.randomUUID();
        java.util.UUID bob = java.util.UUID.randomUUID();
        for (java.util.UUID player : List.of(alice, bob, alice)) {
            dev.whynotac.protocol.session.PlayerSession session = registry.open("x");
            session.bind(player, "p", 1, 0);
            session.onInbound(new ServerboundPacket.ClientTickEnd(), 1);
            session.disconnect(2);
        }
        store.close();

        String found = run(0, "find", recordings.toString(), secretFile.toString(), alice.toString());
        assertEquals(2, count(found, "\"file\":"), found);
        String dryRun = run(0, "forget", recordings.toString(), secretFile.toString(), alice.toString());
        assertTrue(dryRun.contains("\"dry_run\":true") && dryRun.contains("\"deleted\":0"), dryRun);
        assertEquals(3, countRecordings(recordings));
        String forgotten = run(0, "forget", recordings.toString(), secretFile.toString(), alice.toString(), "--yes");
        assertTrue(forgotten.contains("\"deleted\":2"), forgotten);
        assertEquals(1, countRecordings(recordings), "only Bob's recording remains");
        assertEquals(1, count(run(0, "find", recordings.toString(), secretFile.toString(), bob.toString()), "\"file\":"));

        // Another server's secret links nothing.
        Path otherSecret = Files.write(dir.resolve("other.key"), dev.whynotac.recording.write.Pseudonymizer.newSecret());
        assertEquals(0, count(run(0, "find", recordings.toString(), otherSecret.toString(), bob.toString()), "\"file\":"));
        run(1, "forget", recordings.toString(), secretFile.toString(), "not-a-uuid");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static long countRecordings(Path dir) throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".wnrec")).count();
        }
    }

    @Test
    void dumpPrintsEveryEventWithRawFloatBits() throws Exception {
        String[] lines = run(0, "dump", sample(true).toString()).strip().split("\n");
        assertEquals(7, lines.length);
        assertTrue(lines[1].contains("\"yaw\":{\"f32\":\"12.5\",\"bits\":\"0x41480000\"}"), lines[1]);
        assertTrue(lines[1].contains("\"x\":{\"f64\":\"NaN\",\"bits\":\"0x7ff8000000000000\"}"), lines[1]);
    }
}
