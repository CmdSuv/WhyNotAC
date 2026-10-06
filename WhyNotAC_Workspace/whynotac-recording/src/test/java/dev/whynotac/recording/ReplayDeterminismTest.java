package dev.whynotac.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.replay.Replayer;
import dev.whynotac.recording.write.Pseudonymizer;
import dev.whynotac.recording.write.RecordingListener;
import dev.whynotac.recording.write.RecordingStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live session (real PlayerSession, multi-threaded analysis pool) -> recording on disk -> replay.
 * The replayed timeline must equal the live one event for event, with only the identity pseudonymised.
 */
class ReplayDeterminismTest {

    @TempDir
    Path dir;

    @Test
    void replayEqualsTheLiveTimeline() throws Exception {
        byte[] secret = Pseudonymizer.newSecret();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        RecordingStore store = new RecordingStore(dir, RecordingStore.Settings.defaults(), Logger.getLogger("test"));
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        SessionRegistry registry = new SessionRegistry(pool, 1 << 20, (s, e, t) -> errors.add(t));
        List<TimelineEvent> live = java.util.Collections.synchronizedList(new ArrayList<>());
        registry.addListener((session, event) -> live.add(event));
        registry.addListener(new RecordingListener(store, new Pseudonymizer(secret), 774, "1.21.11", "test"));

        UUID realId = UUID.randomUUID();
        PlayerSession session = registry.open("127.0.0.1:1");
        Generators gen = new Generators(11);
        SplittableRandom random = new SplittableRandom(12);
        long t = 1_000;
        session.onInbound(new ServerboundPacket.Unmodeled("minecraft:hello"), t);
        session.bind(realId, "Steve", 7, t += 10);
        List<Integer> unanswered = new ArrayList<>();
        for (int tick = 0; tick < 20_000; tick++) {
            t += 50_000_000L;
            session.onInbound(gen.serverbound(Generators.SERVERBOUND.get(random.nextInt(Generators.SERVERBOUND.size()))), t);
            session.onOutbound(gen.clientbound(Generators.CLIENTBOUND.get(random.nextInt(Generators.CLIENTBOUND.size()))), t + 1);
            OptionalInt tx = session.allocateTransaction();
            if (tx.isPresent()) {
                session.onOutbound(new ClientboundPacket.Ping(tx.getAsInt()), t + 2);
                unanswered.add(tx.getAsInt());
            }
            if (random.nextInt(3) == 0 && !unanswered.isEmpty()) {
                // usually in order, occasionally skipping one: produces Acknowledged and Anomaly events
                int index = random.nextInt(20) == 0 && unanswered.size() > 1 ? 1 : 0;
                int id = unanswered.get(index);
                unanswered.subList(0, index + 1).clear();
                session.onInbound(new ServerboundPacket.Pong(id), t + 30_000_000L);
            }
            session.onInbound(new ServerboundPacket.ClientTickEnd(), t + 40_000_000L);
        }
        session.disconnect(t + 1);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        store.close();
        if (!errors.isEmpty()) {
            throw new AssertionError("listener failed live: " + errors.getFirst(), errors.getFirst());
        }

        Path file;
        try (Stream<Path> walk = Files.walk(dir)) {
            file = walk.filter(p -> p.toString().endsWith(RecordingFormat.FILE_SUFFIX)).findFirst().orElseThrow();
        }
        List<TimelineEvent> replayed = new ArrayList<>();
        Replayer.Result result = Replayer.replay(RecordingReader.open(file), List.of((s, e) -> replayed.add(e)));

        assertTrue(result.report().complete(), result.report().toString());
        assertFalse(result.disconnectSynthesized());
        assertTrue(result.listenerErrors().isEmpty());
        assertTrue(live.stream().anyMatch(e -> e instanceof TimelineEvent.Acknowledged));
        assertTrue(live.stream().anyMatch(e -> e instanceof TimelineEvent.Anomaly));
        assertEquals(live.size(), replayed.size());
        UUID pseudonym = new Pseudonymizer(secret).pseudonym(realId);
        for (int i = 0; i < live.size(); i++) {
            TimelineEvent expected = live.get(i);
            if (expected instanceof TimelineEvent.Bound b) {
                expected = new TimelineEvent.Bound(b.sequence(), b.nanoTime(), pseudonym, Pseudonymizer.displayName(pseudonym), b.entityId());
            }
            BitExact.assertSame(expected, replayed.get(i));
        }
    }

    @Test
    void truncatedRecordingStillReplaysAndFinalisesListeners() throws IOException {
        MemorySink sink = new MemorySink();
        dev.whynotac.recording.write.RecordingWriter writer = new dev.whynotac.recording.write.RecordingWriter(sink, 1024, Long.MAX_VALUE);
        List<TimelineEvent> events = new Generators(13).stream(3000);
        events.forEach(writer::append);
        writer.close();
        byte[] bytes = sink.bytes();
        byte[] cut = java.util.Arrays.copyOf(bytes, bytes.length * 2 / 3);
        List<TimelineEvent> replayed = new ArrayList<>();
        Replayer.Result result = Replayer.replay(RecordingReader.of(cut), List.of((s, e) -> replayed.add(e)));
        assertTrue(result.disconnectSynthesized());
        assertTrue(replayed.getLast() instanceof TimelineEvent.Disconnected);
        assertEquals(events.subList(0, replayed.size() - 1), replayed.subList(0, replayed.size() - 1));
    }
}
