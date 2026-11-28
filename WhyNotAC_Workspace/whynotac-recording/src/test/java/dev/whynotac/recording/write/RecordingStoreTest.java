package dev.whynotac.recording.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.Generators;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import dev.whynotac.recording.read.RecordingReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingStoreTest {

    @TempDir
    Path dir;

    private static final Logger LOG = Logger.getLogger("test");

    private static List<Path> files(Path dir, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
        }
    }

    private static List<TimelineEvent> read(Path file) throws IOException {
        List<TimelineEvent> events = new ArrayList<>();
        RecordingReader.open(file).forEach(events::add);
        return events;
    }

    private static RecordingHeader header() {
        return new RecordingHeader(RecordingFormat.VERSION, 774, "1.21.11", "test", UUID.randomUUID(), UUID.randomUUID(), System.currentTimeMillis());
    }

    @Test
    void cleanCloseProducesACompleteFinalFile() throws IOException {
        RecordingStore store = new RecordingStore(dir, RecordingStore.Settings.defaults(), LOG);
        RecordingWriter writer = store.open(header());
        List<TimelineEvent> events = new Generators(5).stream(30_000);
        events.forEach(writer::append);
        writer.close();
        store.close();
        assertTrue(files(dir, RecordingFormat.PARTIAL_SUFFIX).isEmpty());
        Path file = files(dir, RecordingFormat.FILE_SUFFIX).getFirst();
        RecordingReader reader = RecordingReader.open(file);
        List<TimelineEvent> read = new ArrayList<>();
        reader.forEach(read::add);
        assertEquals(events, read);
        assertTrue(reader.report().complete(), reader.report().toString());
    }

    @Test
    void crashLeavesAReadablePartialFile() throws IOException {
        RecordingStore store = new RecordingStore(dir, new RecordingStore.Settings(64L << 20, Duration.ofDays(1), 1L << 30, 2048, Long.MAX_VALUE), LOG);
        RecordingWriter writer = store.open(header());
        List<TimelineEvent> events = new Generators(6).stream(5_000);
        events.forEach(writer::append);
        // No writer.close(): the process "dies" with the last block still in memory.
        store.close();
        Path partial = files(dir, RecordingFormat.PARTIAL_SUFFIX).getFirst();
        RecordingReader reader = RecordingReader.open(partial);
        List<TimelineEvent> read = new ArrayList<>();
        reader.forEach(read::add);
        assertFalse(reader.report().endRecordFound());
        assertTrue(read.size() > 4_000, "only the unsealed tail may be lost, read " + read.size());
        assertEquals(events.subList(0, read.size()), read);
    }

    @Test
    void backPressureRejectsBlocksInsteadOfBlocking() throws IOException {
        // A budget smaller than any block (the last one may hold a single small event): every block is
        // rejected, the writer is never blocked.
        RecordingStore store = new RecordingStore(dir, new RecordingStore.Settings(1, Duration.ofDays(1), 1L << 30, 1024, Long.MAX_VALUE), LOG);
        RecordingWriter writer = store.open(header());
        new Generators(7).stream(2_000).forEach(writer::append);
        writer.close();
        store.close();
        assertTrue(store.rejectedBlocks() > 0);
        assertEquals(2_000, writer.lostEvents());
        RecordingReader reader = RecordingReader.open(files(dir, RecordingFormat.FILE_SUFFIX).getFirst());
        reader.forEach(e -> { });
        assertEquals(2_000, reader.report().lostEvents(), "the end record carries the loss even when no block survived");
        assertFalse(reader.report().complete());
    }

    @Test
    void retentionRemovesOldAndExcessRecordingsOldestFirst() throws IOException {
        Path day = Files.createDirectories(dir.resolve("2026-01-01"));
        Path ancient = Files.write(day.resolve("a" + RecordingFormat.FILE_SUFFIX), new byte[100]);
        Path older = Files.write(day.resolve("b" + RecordingFormat.FILE_SUFFIX), new byte[600]);
        Path newer = Files.write(day.resolve("c" + RecordingFormat.FILE_SUFFIX), new byte[600]);
        Path unrelated = Files.write(day.resolve("notes.txt"), "keep".getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        Files.setLastModifiedTime(ancient, FileTime.from(now.minus(Duration.ofDays(30))));
        Files.setLastModifiedTime(older, FileTime.from(now.minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(newer, FileTime.from(now.minus(Duration.ofHours(1))));
        RecordingStore store = new RecordingStore(dir, new RecordingStore.Settings(1 << 20, Duration.ofDays(14), 1000, 1024, Long.MAX_VALUE), LOG);
        store.enforceRetention();
        store.close();
        assertFalse(Files.exists(ancient), "older than max age");
        assertFalse(Files.exists(older), "deleted to get under the size cap");
        assertTrue(Files.exists(newer));
        assertTrue(Files.exists(unrelated), "only recordings are ever deleted");
    }

    @Test
    void listenerRecordsOnlyLoggedInSessionsAndPseudonymisesThem() throws IOException {
        byte[] secret = Pseudonymizer.newSecret();
        RecordingStore store = new RecordingStore(dir, RecordingStore.Settings.defaults(), LOG);
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1 << 16, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new RecordingListener(store, new Pseudonymizer(secret), 774, "1.21.11", "test"));

        PlayerSession ping = registry.open("1.2.3.4:5555"); // server-list ping: never logs in
        ping.onInbound(new ServerboundPacket.Unmodeled("minecraft:status_request"), 1);
        ping.disconnect(2);

        UUID realId = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        String realName = "VerySecretPlayerName";
        PlayerSession player = registry.open("10.0.0.7:41234");
        player.onInbound(new ServerboundPacket.Unmodeled("minecraft:hello"), 10);
        player.bind(realId, realName, 99, 20);
        player.onInbound(new ServerboundPacket.MovePlayer(1, 2, 3, 4, 5, true, true, true, false), 30);
        // The player list (its own entry and another player's) and the other player's entity carry UUIDs too.
        UUID otherId = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
        player.onOutbound(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(realId, 0),
                new ClientboundPacket.PlayerInfoUpdate.Entry(otherId, 0))), 31);
        player.onOutbound(ClientboundPacket.AddEntity.received(7, otherId, "minecraft:player", 0, 64, 0, dev.whynotac.math.vector.Vec3d.ZERO,
                (byte) 0, (byte) 0, (byte) 0, 0), 32);
        player.onOutbound(new ClientboundPacket.PlayerInfoRemove(List.of(otherId)), 33);
        // A team names its entries: the player's own name, another player's, an entity's UUID.
        UUID mobId = UUID.fromString("2b0f4c3d-5e6a-4b7c-8d9e-0f1a2b3c4d5e");
        player.onOutbound(new ClientboundPacket.SetPlayerTeam("VerySecretTeam", ClientboundPacket.SetPlayerTeam.ADD, "NEVER",
                List.of(realName, "OtherSecretPlayer", mobId.toString())), 34);
        player.disconnect(40);
        store.close();

        List<Path> recordings = files(dir, RecordingFormat.FILE_SUFFIX);
        assertEquals(1, recordings.size(), "the ping session must not create a file");
        List<TimelineEvent> events = read(recordings.getFirst());
        assertEquals(8, events.size(), "pre-login packet, Bound, move, player list, entity, list removal, team, disconnect");
        UUID other = new Pseudonymizer(secret).pseudonym(otherId);
        ClientboundPacket.PlayerInfoUpdate list = (ClientboundPacket.PlayerInfoUpdate) ((TimelineEvent.Outbound) events.get(3)).packet();
        assertEquals(List.of(new Pseudonymizer(secret).pseudonym(realId), other), list.entries().stream().map(e -> e.profileId()).toList(),
                "the same pseudonyms as Bound: a replay links the entries as live");
        assertEquals(other, ((ClientboundPacket.AddEntity) ((TimelineEvent.Outbound) events.get(4)).packet()).uuid());
        assertEquals(List.of(other), ((ClientboundPacket.PlayerInfoRemove) ((TimelineEvent.Outbound) events.get(5)).packet()).profileIds());
        TimelineEvent.Bound bound = (TimelineEvent.Bound) events.get(1);
        UUID pseudonym = new Pseudonymizer(secret).pseudonym(realId);
        assertEquals(pseudonym, bound.playerId());
        assertEquals(Pseudonymizer.displayName(pseudonym), bound.playerName());
        assertEquals(pseudonym, RecordingReader.open(recordings.getFirst()).header().subject());
        ClientboundPacket.SetPlayerTeam team = (ClientboundPacket.SetPlayerTeam) ((TimelineEvent.Outbound) events.get(6)).packet();
        assertEquals("team-1", team.team());
        assertEquals("NEVER", team.collisionRule());
        assertEquals(List.of(bound.playerName(), "entry-1", new Pseudonymizer(secret).pseudonym(mobId).toString()), team.players(),
                "the player's own entry as its Bound name, an entity's as its UUID's pseudonym: a replay matches them as live");

        // Privacy: neither the name, the UUID nor the address appears anywhere in the decoded timeline or the raw file.
        String decoded = events.toString();
        byte[] raw = Files.readAllBytes(recordings.getFirst());
        for (String secretText : List.of(realName, realId.toString(), otherId.toString(), "10.0.0.7", "VerySecretTeam", "OtherSecretPlayer",
                mobId.toString())) {
            assertFalse(decoded.contains(secretText), secretText);
            assertFalse(new String(raw, StandardCharsets.ISO_8859_1).contains(secretText), secretText);
        }
        assertFalse(containsUuidBytes(raw, realId));
        assertFalse(containsUuidBytes(raw, otherId));
    }

    @Test
    void pseudonymsAreStablePerSecretAndUnlinkableAcrossSecrets() {
        UUID id = UUID.randomUUID();
        byte[] a = Pseudonymizer.newSecret();
        byte[] b = Pseudonymizer.newSecret();
        assertEquals(new Pseudonymizer(a).pseudonym(id), new Pseudonymizer(a).pseudonym(id));
        assertNotEquals(new Pseudonymizer(a).pseudonym(id), new Pseudonymizer(b).pseudonym(id));
        assertEquals(8, new Pseudonymizer(a).pseudonym(id).version());
        assertNotEquals(id, new Pseudonymizer(a).pseudonym(id));
    }

    private static boolean containsUuidBytes(byte[] haystack, UUID id) {
        byte[] needle = java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
