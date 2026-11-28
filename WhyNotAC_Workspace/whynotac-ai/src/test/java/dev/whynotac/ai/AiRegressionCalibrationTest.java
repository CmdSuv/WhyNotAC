package dev.whynotac.ai;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.verdict.DependencyGroup;
import dev.whynotac.verdict.EvidenceCertificate;
import dev.whynotac.verdict.MitigationKind;
import dev.whynotac.verdict.VerdictCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calibrates {@link BehavioralFeatureExtractor} and {@link AccountBaselineModel} against the real honest human
 * {@code regression/*.wnrec} recordings (AI-03): verifies finite statistics and zero runaway anomaly scores on
 * steady clean continuation.
 */
final class AiRegressionCalibrationTest {

    private static final Path CORPUS = Path.of(System.getProperty("whynotac.regressionCorpus", "../regression"));

    @Test
    void realHonestSessionsYieldBoundedFeaturesAndCalmBaselineContinuation() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(CORPUS)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".wnrec")).sorted().toList();
        }
        assertTrue(files.size() >= 3, "Expected >= 3 regression recordings");

        AccountBaselineModel model = new AccountBaselineModel();
        UUID player = UUID.randomUUID();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 4096, (sess, ev, err) -> {
            throw new AssertionError("Listener failed on " + ev, err);
        });
        BehavioralFeatureExtractor extractor = new BehavioralFeatureExtractor();
        registry.addListener(extractor);

        List<BehavioralFeatureExtractor.Snapshot> snapshots = new ArrayList<>();
        for (Path rec : files) {
            RecordingReader reader = RecordingReader.open(rec);
            PlayerSession session = registry.openReplay("ai-cal:" + rec.getFileName());
            TimelineEvent last = null;
            for (TimelineEvent ev : reader) {
                session.deliver(ev);
                last = ev;
            }
            if (!session.isClosed()) {
                session.deliver(new TimelineEvent.Disconnected(
                        last == null ? 0L : last.sequence() + 1L,
                        last == null ? 0L : last.nanoTime()));
            }
            assertTrue(reader.report().complete());
            BehavioralFeatureExtractor.Snapshot snap = extractor.snapshot(session);
            snap.features().forEach((name, moments) -> {
                if (moments.count() > 0) {
                    assertTrue(Double.isFinite(moments.mean()), rec + " non-finite mean for " + name);
                }
                if (moments.count() >= 2) {
                    assertTrue(Double.isFinite(moments.sampleVariance()),
                            rec + " non-finite variance for " + name);
                }
            });
            assertTrue(snap.features().get(BehavioralFeatureExtractor.TICK_DELTA_MS).count() > 500);
            assertFalse(snap.features().get(BehavioralFeatureExtractor.AIM_ANGULAR_STEP_DEG).count() == 0);
            snapshots.add(snap);
        }

        // Train baseline on first two real clean sessions, evaluate held-out third clean session
        model.mergeSession(player, snapshots.get(0));
        model.mergeSession(player, snapshots.get(1));
        AccountBaselineModel.ShiftAssessment continuation = model.assess(player, snapshots.get(2));
        if (!continuation.coldStart()) {
            assertTrue(Double.isFinite(continuation.rmsZScore()));
            Evidence context = new Evidence("aim-pattern", 1, EvidenceClass.NOMINAL_STATISTIC,
                    1L, 100L, 1L, 100L, "calibration check", Map.of(),
                    new Admissibility(true, List.of(), Admissibility.IdentityTier.AUTHENTICATED));
            VerdictCase vc = new VerdictCase("cal-case", 100L, null, context, ActionPolicy.Lifecycle.SHADOW,
                    DependencyGroup.STATISTICAL_SIGNALS, EnumSet.of(ActionPolicy.Action.ALERT, ActionPolicy.Action.REVIEW),
                    ActionPolicy.Action.REVIEW, MitigationKind.NONE, 0.0, 0.15, 0, false,
                    EvidenceCertificate.issue("cert-cal", 100L, 1L, "aim-pattern", 1,
                            EvidenceClass.NOMINAL_STATISTIC, ActionPolicy.Lifecycle.SHADOW,
                            Admissibility.IdentityTier.AUTHENTICATED, context.fingerprint(),
                            List.of(), List.of(DependencyGroup.STATISTICAL_SIGNALS.id()),
                            EvidenceCertificate.VerificationStatus.NOT_NEEDED, "", ActionPolicy.Action.REVIEW, MitigationKind.NONE));
            double priority = AiAdvisor.computePriorityScore(vc, continuation, 1.0);
            assertTrue(priority >= 0.0 && priority <= 25.0,
                    "Honest clean session priority score stayed low: " + priority);
        }
    }
}
