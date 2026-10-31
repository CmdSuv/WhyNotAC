package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.evidence.Margins;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.world.Replicas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The replay gate ({@code VAL-10}): every shipped check, behind fresh replicas, replayed over each honest session of the
 * regression corpus ({@code regression/}, see its README), finds nothing, not even a candidate the admissibility gate
 * would reject. The same session with a cheat put in must be found: the gate can fail.
 */
class RegressionReplayTest {

    private static final Path CORPUS = Path.of(System.getProperty("whynotac.regressionCorpus", "../regression"));

    /** What one replay came to. */
    private record Outcome(List<Evidence> evidence, List<String> rejected, List<String> errors, long clientTicks, long moves,
            Map<String, Margins.Margin> margins) {}

    static Stream<Path> corpus() throws IOException {
        try (Stream<Path> files = Files.list(CORPUS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".wnrec")).sorted().toList().stream();
        }
    }

    @Test
    void theCorpusHoldsSessions() throws IOException {
        assertTrue(corpus().count() >= 3, "regression corpus at " + CORPUS.toAbsolutePath());
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void everyCheckFindsNothingInAnHonestSession(Path recording) throws IOException {
        Outcome o = replay(recording, UnaryOperator.identity());
        assertEquals(List.of(), o.errors(), "listener errors");
        assertTrue(o.clientTicks() > 1000 && o.moves() > 1000, "a whole session: " + o.clientTicks() + " ticks, " + o.moves() + " moves");
        assertEquals(List.of(), o.evidence().stream().map(e -> e.check() + ": " + e.summary()).toList());
        assertEquals(List.of(), o.rejected());
        // VAL-4: what was no finding stayed within each limit, and the clock check judged every answered ping
        o.margins().forEach((check, m) -> assertTrue(m.smallest() >= 0, check + ": " + m));
        assertTrue(o.margins().containsKey(ClientClockCheck.ID) && o.margins().get(ClientClockCheck.ID).observed() > 100,
                "client-clock margins: " + o.margins());
        // every corpus session is a real-client run with its attack and dig legs
        assertTrue(o.margins().containsKey(AttackReachCheck.ID) && o.margins().containsKey(BreakTimeCheck.ID), "margins: " + o.margins());
        System.out.println(recording.getFileName() + " margins " + o.margins());
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void halfABlockUpInTheMiddleOfTheSessionIsFound(Path recording) throws IOException {
        long[] index = {0};
        long total = replay(recording, UnaryOperator.identity()).moves();
        Outcome o = replay(recording, event -> {
            if (event instanceof TimelineEvent.Inbound in && in.packet() instanceof ServerboundPacket.MovePlayer m && m.hasPosition()) {
                long i = index[0]++;
                if (i > total / 3 && i < 2 * total / 3) {
                    return new TimelineEvent.Inbound(in.sequence(), in.nanoTime(), in.clientTick(), new ServerboundPacket.MovePlayer(m.x(), m.y() + 0.5,
                            m.z(), m.yaw(), m.pitch(), true, m.hasRotation(), m.onGround(), m.horizontalCollision()));
                }
            }
            return event;
        });
        assertFalse(o.evidence().stream().noneMatch(e -> e.check().equals(MovementCheck.ID)),
                "the movement check finds the step: " + o.evidence().stream().map(Evidence::summary).toList());
    }

    @ParameterizedTest
    @MethodSource("corpus")
    void listenersThatReadNoEntityMotionFindTheSameWithoutIt(Path recording) throws IOException {
        long[] index = {0};
        long total = replay(recording, UnaryOperator.identity()).moves();
        UnaryOperator<TimelineEvent> cheat = event -> {
            if (event instanceof TimelineEvent.Inbound in && in.packet() instanceof ServerboundPacket.MovePlayer m && m.hasPosition()) {
                long i = index[0]++;
                if (i > total / 3 && i < 2 * total / 3 && i % 50 == 0) {
                    return new TimelineEvent.Inbound(in.sequence(), in.nanoTime(), in.clientTick(), new ServerboundPacket.MovePlayer(m.x(), m.y() + 0.5,
                            m.z(), m.yaw(), m.pitch(), true, m.hasRotation(), m.onGround(), m.horizontalCollision()));
                }
            }
            return event;
        };
        Outcome filtered = replay(recording, cheat, false);
        index[0] = 0;
        Outcome everything = replay(recording, cheat, true);
        assertFalse(everything.evidence().isEmpty());
        assertEquals(summaries(everything), summaries(filtered));
        assertEquals(everything.rejected(), filtered.rejected());
    }

    private static List<String> summaries(Outcome o) {
        return o.evidence().stream().map(e -> e.check() + " " + e.fromSequence() + ": " + e.summary()).toList();
    }

    /** Replays {@code recording}, each event through {@code change}, into the replicas and every check (the plugin's order). */
    private static Outcome replay(Path recording, UnaryOperator<TimelineEvent> change) throws IOException {
        return replay(recording, change, false);
    }

    /** {@code everything}: every listener receives every event, entity motion included, whether it reads it or not. */
    private static Outcome replay(Path recording, UnaryOperator<TimelineEvent> change, boolean everything) throws IOException {
        List<Evidence> evidence = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Margins margins = new Margins();
        EvidenceSink sink = new EvidenceSink() {
            @Override
            public void accept(PlayerSession session, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession session, String check, Admissibility admissibility) {
                rejected.add(check + ": " + admissibility);
            }

            @Override
            public void margin(PlayerSession session, String check, double slack) {
                margins.add(check, slack);
            }
        };
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (session, event, error) -> errors.add(event + ": " + error));
        Replicas replicas = Replicas.vanilla();
        List<TimelineListener> listeners = new ArrayList<>(replicas.listeners());
        listeners.addAll(Checks.all(sink, replicas));
        for (TimelineListener listener : listeners) {
            registry.addListener(everything ? listener::onEvent : listener);
        }
        long ticks = 0;
        long moves = 0;
        RecordingReader reader = RecordingReader.open(recording);
        PlayerSession session = registry.openReplay("regression:" + recording.getFileName());
        TimelineEvent last = null;
        for (TimelineEvent event : reader) {
            TimelineEvent e = change.apply(event);
            if (e instanceof TimelineEvent.Inbound in) {
                ticks += in.packet() instanceof ServerboundPacket.ClientTickEnd ? 1 : 0;
                moves += in.packet() instanceof ServerboundPacket.MovePlayer m && m.hasPosition() ? 1 : 0;
            }
            session.deliver(e);
            last = e;
        }
        if (!session.isClosed()) { // a recording that ends without one (Replayer does the same)
            session.deliver(new TimelineEvent.Disconnected(last == null ? 0 : last.sequence() + 1, last == null ? 0 : last.nanoTime()));
        }
        assertTrue(reader.report().complete(), "the recording reads to its end: " + reader.report());
        return new Outcome(evidence, rejected, errors, ticks, moves, margins.snapshot());
    }
}
