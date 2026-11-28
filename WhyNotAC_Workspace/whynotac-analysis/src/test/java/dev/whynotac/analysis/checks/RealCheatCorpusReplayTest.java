package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Offline replay verification of the {@code DATA-02} real open-source cheat client corpus ({@code cheat-corpus/real/}).
 *
 * <p>Every recording was captured inside an isolated {@code --network=none} container against local {@code 127.0.0.1}
 * Paper 1.21.11 from unmodified GPLv3 Wurst {@code v7.57-MC1.21.11} and Meteor {@code 1.21.11-86} clients. This test
 * verifies zero listener errors, zero rejected evidence, and no regression across all 26 detected real cheat modules
 * while preserving the honest documented FN boundary ({@code meteor-killaura.wnrec}).
 */
final class RealCheatCorpusReplayTest {

    private static final Path REAL_CORPUS = Path.of(System.getProperty("whynotac.realCheatCorpus", "../cheat-corpus/real"));

    /** Honest documented FN on single stationary target within vanilla reach/rotation (5 s, 10 hits). */
    private static final Set<String> DOCUMENTED_HONEST_FNS = Set.of("meteor-killaura.wnrec");

    /** Expected deterministic proof check(s) that must fire on each detected real cheat recording. */
    private static final Map<String, Set<String>> EXPECTED_CHECKS = Map.ofEntries(
            Map.entry("wurst-flight.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("wurst-speed.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("wurst-timer.wnrec", Set.of(ClientClockCheck.ID)),
            Map.entry("wurst-killaura.wnrec", Set.of(AttackAimCheck.ID)),
            Map.entry("wurst-reach.wnrec", Set.of(AttackReachCheck.ID)),
            Map.entry("wurst-antikb.wnrec", Set.of(VelocityCheck.ID, MovementCheck.ID)),
            Map.entry("wurst-noslow.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("wurst-fastbreak.wnrec", Set.of(BreakTimeCheck.ID)),
            Map.entry("wurst-scaffold.wnrec", Set.of(ActionGrammarCheck.ID, TickFramingCheck.ID, UseAimCheck.ID)),
            Map.entry("wurst-blink.wnrec", Set.of(MovementCheck.ID, TickFramingCheck.ID)),
            Map.entry("wurst-criticals.wnrec", Set.of(TickFramingCheck.ID, ActionGrammarCheck.ID)),
            Map.entry("wurst-jesus.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("wurst-step.wnrec", Set.of(TickFramingCheck.ID)),
            Map.entry("meteor-flight.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("meteor-speed.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("meteor-timer.wnrec", Set.of(ClientClockCheck.ID)),
            Map.entry("meteor-reach.wnrec", Set.of(AttackReachCheck.ID)),
            Map.entry("meteor-velocity.wnrec", Set.of(VelocityCheck.ID, MovementCheck.ID)),
            Map.entry("meteor-noslow.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("meteor-speedmine.wnrec", Set.of(BreakTimeCheck.ID)),
            Map.entry("meteor-scaffold.wnrec", Set.of(ActionGrammarCheck.ID, UseAimCheck.ID, MovementCheck.ID)),
            Map.entry("meteor-blink.wnrec", Set.of(MovementCheck.ID, TickFramingCheck.ID)),
            Map.entry("meteor-criticals.wnrec", Set.of(TickFramingCheck.ID, ActionGrammarCheck.ID)),
            Map.entry("meteor-jesus.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("meteor-step.wnrec", Set.of(MovementCheck.ID)),
            Map.entry("meteor-guimove.wnrec", Set.of(ActionGrammarCheck.ID)));

    private record ReplayResult(List<Evidence> evidence, List<String> rejected, List<String> errors) {}

    static Stream<Path> recordings() throws IOException {
        try (Stream<Path> files = Files.list(REAL_CORPUS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".wnrec")).sorted().toList().stream();
        }
    }

    @Test
    void realCorpusContainsAll27Recordings() throws IOException {
        List<Path> files = recordings().toList();
        assertEquals(27, files.size(), "Expected 27 real DATA-02 recordings in " + REAL_CORPUS.toAbsolutePath());
        assertEquals(EXPECTED_CHECKS.size() + DOCUMENTED_HONEST_FNS.size(), files.size());
    }

    @ParameterizedTest
    @MethodSource("recordings")
    void replaysCleanlyAndPreservesMeasuredDetections(Path recording) throws IOException {
        String name = recording.getFileName().toString();
        ReplayResult result = replay(recording);
        assertEquals(List.of(), result.errors(), "Listener errors on " + name);
        assertEquals(List.of(), result.rejected(), "Rejected evidence on " + name);

        if (DOCUMENTED_HONEST_FNS.contains(name)) {
            return;
        }
        Set<String> expected = EXPECTED_CHECKS.get(name);
        assertFalse(result.evidence().isEmpty(), "Expected detection on real cheat recording " + name);
        Set<String> firedChecks = new LinkedHashSet<>();
        boolean hasProof = false;
        for (Evidence e : result.evidence()) {
            firedChecks.add(e.check());
            if (e.evidenceClass() == EvidenceClass.ADVANTAGE_PROOF
                    || e.evidenceClass() == EvidenceClass.MODIFICATION_PROOF) {
                hasProof = true;
            }
        }
        assertTrue(hasProof, "Expected P-A or P-M proof evidence on " + name + ", got " + result.evidence());
        assertTrue(firedChecks.containsAll(expected),
                "Expected checks " + expected + " on " + name + ", actually fired: " + firedChecks);
    }

    private static ReplayResult replay(Path recording) throws IOException {
        List<Evidence> evidence = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        EvidenceSink sink = new EvidenceSink() {
            @Override
            public void accept(PlayerSession session, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession session, String check, Admissibility admissibility) {
                rejected.add(check + ": " + admissibility);
            }
        };
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, ev, err) -> errors.add(ev + ": " + err));
        Replicas replicas = Replicas.vanilla();
        for (TimelineListener listener : replicas.listeners()) {
            registry.addListener(listener);
        }
        for (TimelineListener listener : Checks.all(sink, replicas)) {
            registry.addListener(listener);
        }
        RecordingReader reader = RecordingReader.open(recording);
        PlayerSession session = registry.openReplay("real-cheat:" + recording.getFileName());
        TimelineEvent last = null;
        for (TimelineEvent event : reader) {
            session.deliver(event);
            last = event;
        }
        if (!session.isClosed()) {
            session.deliver(new TimelineEvent.Disconnected(last == null ? 0 : last.sequence() + 1,
                    last == null ? 0 : last.nanoTime()));
        }
        assertTrue(reader.report().complete(), "Recording did not read cleanly to EOF: " + reader.report());
        return new ReplayResult(evidence, rejected, errors);
    }
}
