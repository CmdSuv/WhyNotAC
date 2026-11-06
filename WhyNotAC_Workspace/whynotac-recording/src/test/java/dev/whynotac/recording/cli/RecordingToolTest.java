package dev.whynotac.recording.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.MemorySink;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.write.Pseudonymizer;
import dev.whynotac.recording.write.RecordingListener;
import dev.whynotac.recording.write.RecordingStore;
import dev.whynotac.recording.write.RecordingWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
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
        byte[] secret = Pseudonymizer.newSecret();
        Path secretFile = Files.write(dir.resolve("recording-secret.key"), secret);
        Path recordings = dir.resolve("recordings");
        RecordingStore store = new RecordingStore(recordings,
                RecordingStore.Settings.defaults(), Logger.getLogger("test"));
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1024,
                (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new RecordingListener(store, new Pseudonymizer(secret),
                774, "1.21.11", "test"));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        for (UUID player : List.of(alice, bob, alice)) {
            PlayerSession session = registry.open("x");
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
        Path otherSecret = Files.write(dir.resolve("other.key"), Pseudonymizer.newSecret());
        assertEquals(0, count(run(0, "find", recordings.toString(), otherSecret.toString(), bob.toString()), "\"file\":"));
        run(1, "forget", recordings.toString(), secretFile.toString(), "not-a-uuid");
    }

    @Test
    void findListsAndForgetScrubsAPlayerInOthersRecordings() throws Exception {
        byte[] secret = Pseudonymizer.newSecret();
        Path secretFile = Files.write(dir.resolve("recording-secret.key"), secret);
        Path recordings = dir.resolve("recordings");
        RecordingStore store = new RecordingStore(recordings,
                RecordingStore.Settings.defaults(), Logger.getLogger("test"));
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1024,
                (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new RecordingListener(store, new Pseudonymizer(secret),
                774, "1.21.11", "test"));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        // Bob's session sees Alice: in its player list and as an entity.
        PlayerSession bobs = registry.open("x");
        bobs.bind(bob, "bob", 1, 0);
        bobs.onOutbound(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(bob, 0),
                new ClientboundPacket.PlayerInfoUpdate.Entry(alice, 0))), 1);
        bobs.onOutbound(ClientboundPacket.AddEntity.received(2, alice, "minecraft:player", 0, 64, 0, Vec3d.ZERO,
                (byte) 0, (byte) 0, (byte) 0, 0), 2);
        bobs.onOutbound(new ClientboundPacket.PlayerInfoRemove(List.of(alice)), 3);
        bobs.disconnect(4);
        PlayerSession alices = registry.open("y");
        alices.bind(alice, "alice", 2, 0);
        alices.disconnect(1);
        store.close();
        Pseudonymizer pseudonyms = new Pseudonymizer(secret);

        String found = run(0, "find", recordings.toString(), secretFile.toString(), alice.toString());
        assertTrue(found.contains("\"mentioned_in\":[{") && found.contains("\"occurrences\":3"), found);
        String forgotten = run(0, "forget", recordings.toString(), secretFile.toString(), alice.toString(), "--yes");
        assertTrue(forgotten.contains("\"deleted\":1") && forgotten.contains("\"scrubbed\":1"), forgotten);
        assertTrue(run(0, "find", recordings.toString(), secretFile.toString(), alice.toString()).contains("\"mentioned_in\":[]"));

        // Bob's recording replays as before, with Alice under one fresh UUID that links to nothing.
        Path bobsFile;
        try (Stream<Path> walk = Files.walk(recordings)) {
            bobsFile = walk.filter(f -> f.toString().endsWith(".wnrec")).findFirst().orElseThrow();
        }
        List<TimelineEvent> events = new ArrayList<>();
        RecordingReader.open(bobsFile).forEach(events::add);
        assertEquals(5, events.size(), "Bound, the list, the entity, the removal, the disconnect");
        ClientboundPacket.PlayerInfoUpdate list = (ClientboundPacket.PlayerInfoUpdate) ((TimelineEvent.Outbound) events.get(1)).packet();
        UUID replaced = list.entries().get(1).profileId();
        assertEquals(pseudonyms.pseudonym(bob), list.entries().get(0).profileId(), "Bob's own entry stays");
        assertTrue(!replaced.equals(pseudonyms.pseudonym(alice)) && !replaced.equals(alice));
        assertEquals(replaced, ((ClientboundPacket.AddEntity) ((TimelineEvent.Outbound) events.get(2)).packet()).uuid());
        assertEquals(List.of(replaced), ((ClientboundPacket.PlayerInfoRemove) ((TimelineEvent.Outbound) events.get(3)).packet()).profileIds());
        assertEquals(1, count(run(0, "find", recordings.toString(), secretFile.toString(), bob.toString()), "\"file\":"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static long countRecordings(Path dir) throws java.io.IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
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
