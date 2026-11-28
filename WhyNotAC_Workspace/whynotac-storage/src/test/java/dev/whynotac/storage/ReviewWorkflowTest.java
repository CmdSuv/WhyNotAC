package dev.whynotac.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.verdict.DependencyGroup;
import dev.whynotac.verdict.EvidenceCertificate;
import dev.whynotac.verdict.MitigationKind;
import dev.whynotac.verdict.VerdictCase;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReviewWorkflowTest {

    @Test
    void enforcesFourEyesConfirmationAndProducesPlainLanguageSummary() {
        ReviewWorkflow wf = new ReviewWorkflow();
        wf.open("case-12345678", 100L);
        wf.recordFirstReview("case-12345678", "Alice", "reviewed clip", 200L);

        assertThrows(IllegalArgumentException.class,
                () -> wf.confirmSecondReview("case-12345678", "alice", "self approve", 300L));

        ReviewWorkflow.ReviewRecord confirmed = wf.confirmSecondReview("case-12345678", "Bob", "verified reach", 400L);
        assertEquals(ReviewWorkflow.Status.CONFIRMED_FOUR_EYES, confirmed.status());
        assertEquals("Alice", confirmed.primaryReviewer());
        assertEquals("Bob", confirmed.secondaryReviewer());

        wf.submitAppeal("case-12345678", "lag spike?", 500L);
        assertThrows(IllegalArgumentException.class,
                () -> wf.resolveAppeal("case-12345678", "Alice", true, "deny own review", 600L));
        ReviewWorkflow.ReviewRecord upheld = wf.resolveAppeal("case-12345678", "Charlie", true, "reach > 6.5 verified", 600L);
        assertEquals(ReviewWorkflow.Status.UPHELD, upheld.status());

        Evidence ev = new Evidence("attack-reach", 1, EvidenceClass.ADVANTAGE_PROOF, 10, 20, 1L, 2L,
                "attacked from 6.5 blocks", Map.of("distance", "6.5"),
                new Admissibility(true, List.of(), IdentityTier.AUTHENTICATED));
        EvidenceCertificate cert = EvidenceCertificate.issue("cert-1", 1000L, 1L, "attack-reach", 1,
                EvidenceClass.ADVANTAGE_PROOF, Lifecycle.ARMED, IdentityTier.AUTHENTICATED,
                ev.fingerprint(), List.of(ev.fingerprint()), List.of("attack-reach"),
                EvidenceCertificate.VerificationStatus.VERIFIED, "ok", Action.TEMP_BAN, MitigationKind.CANCEL_IMPOSSIBLE_HIT);
        VerdictCase vc = new VerdictCase("case-12345678", 1000L, null, ev, Lifecycle.ARMED, DependencyGroup.ATTACK_REACH,
                Set.of(Action.ALERT, Action.REVIEW, Action.TEMP_BAN), Action.TEMP_BAN, MitigationKind.CANCEL_IMPOSSIBLE_HIT,
                1.0, 0.0, 1, false, cert);

        String explanation = ReviewWorkflow.plainLanguageExplanation(vc);
        assertTrue(explanation.contains("Mathematically impossible"));
        assertTrue(explanation.contains("attack-reach (P-A, group attack-reach)"));
        assertTrue(explanation.contains("VERIFIED"));
    }

    @Test
    void persistsAndReloadsFourEyesStateAcrossRestarts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path storeFile = dir.resolve("reviews.jsonl");
        ReviewWorkflow first = new ReviewWorkflow(storeFile);
        first.recordFirstReview("case-abc", "Alice", "clip \"1\"\nline2", 1000L);
        first.confirmSecondReview("case-abc", "Bob", "confirmed", 2000L);

        ReviewWorkflow reloaded = new ReviewWorkflow(storeFile);
        ReviewWorkflow.ReviewRecord rec = reloaded.status("case-abc").orElseThrow();
        assertEquals(ReviewWorkflow.Status.CONFIRMED_FOUR_EYES, rec.status());
        assertEquals("Alice", rec.primaryReviewer());
        assertEquals("Bob", rec.secondaryReviewer());
        assertTrue(rec.notes().contains("clip \"1\"\nline2"));
        assertTrue(rec.notes().contains("confirmed"));
        assertThrows(IllegalArgumentException.class,
                () -> reloaded.resolveAppeal("case-abc", "Alice", true, "self uphold", 3000L));
    }
}
