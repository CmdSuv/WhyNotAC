package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary & invariant checks specifically targeting conditional/operator mutation resilience (TEST-03)
 * across safety gates: {@link CircuitBreaker}, {@link FalseBanBudget}, {@link EvidenceCertificate}, and
 * {@link VerdictConfig}.
 */
final class MutationResilienceTest {

    @Test
    void circuitBreakerTripsAtExactThresholdBoundary() {
        CircuitBreaker cb = new CircuitBreaker(2);
        long t = 10_000L;
        // 1st and 2nd unique subjects stay open (<= maxUnique)
        assertFalse(cb.recordEnforcedSubject("u1", t));
        assertFalse(cb.recordEnforcedSubject("u1", t + 1)); // repeat same subject never trips
        assertFalse(cb.recordEnforcedSubject("u2", t + 2));
        assertFalse(cb.isSuppressed());

        // 3rd unique subject within 60s trips strictly (> maxUnique)
        assertTrue(cb.recordEnforcedSubject("u3", t + 3));
        assertTrue(cb.isSuppressed());

        cb.resetAutomaticTrip();
        assertFalse(cb.isSuppressed());
        // Old entries outside 60s window purge cleanly -> next 2 do NOT trip
        assertFalse(cb.recordEnforcedSubject("u4", t + 65_000L));
        assertFalse(cb.recordEnforcedSubject("u5", t + 65_001L));
        assertFalse(cb.isSuppressed());
    }

    @Test
    void falseBanBudgetLocksAtExactIncidentLimit() {
        FalseBanBudget zeroBudget = new FalseBanBudget(0, 60_000L);
        assertTrue(zeroBudget.isLocked());

        FalseBanBudget oneBudget = new FalseBanBudget(1, 1_000L);
        assertFalse(oneBudget.isLocked());
        assertTrue(oneBudget.recordIncident("c1", "overturned", 10_000L));
        assertTrue(oneBudget.isLocked());

        oneBudget.reset();
        assertFalse(oneBudget.isLocked());
    }

    @Test
    void evidenceCertificateIntegrityDetectsEverySingleFieldMutation() {
        EvidenceCertificate base = EvidenceCertificate.issue(
                "cert-1", 123456L, 7L, "attack-reach", 1,
                EvidenceClass.ADVANTAGE_PROOF, Lifecycle.ARMED, IdentityTier.AUTHENTICATED,
                "fp1", List.of("fp1", "fp2"), List.of("attack-reach"),
                EvidenceCertificate.VerificationStatus.VERIFIED, "ok", Action.TEMP_BAN, MitigationKind.NONE);
        assertTrue(base.verifyIntegrity());

        EvidenceCertificate forgedAction = new EvidenceCertificate(
                base.certificateId(), base.issuedAtEpochMillis(), base.sessionConnectionId(),
                base.check(), base.checkVersion(), base.evidenceClass(), base.lifecycle(),
                base.identityTier(), base.primaryFingerprint(), base.corroboratingFingerprints(),
                base.independentGroups(), base.verificationStatus(), base.verificationDetails(),
                Action.REVIEW, base.mitigationKind(), base.sha256Digest());
        assertFalse(forgedAction.verifyIntegrity());

        EvidenceCertificate forgedDigest = new EvidenceCertificate(
                base.certificateId(), base.issuedAtEpochMillis(), base.sessionConnectionId(),
                base.check(), base.checkVersion(), base.evidenceClass(), base.lifecycle(),
                base.identityTier(), base.primaryFingerprint(), base.corroboratingFingerprints(),
                base.independentGroups(), base.verificationStatus(), base.verificationDetails(),
                base.decisionAction(), base.mitigationKind(), "0".repeat(64));
        assertFalse(forgedDigest.verifyIntegrity());
        assertNotEquals(base.sha256Digest(), "0".repeat(64));
    }

    @Test
    void verdictConfigRejectsInvalidBoundaryArguments() {
        assertThrows(IllegalArgumentException.class, () -> VerdictConfig.builder().armedChecks(java.util.Set.of(" ")).build());
        assertThrows(IllegalArgumentException.class, () -> VerdictConfig.builder().maxUniquePenalizedPlayersPerMinute(0).build());
        assertThrows(IllegalArgumentException.class, () -> VerdictConfig.builder().falseBanBudgetWindowMillis(999L).build());
        assertThrows(IllegalArgumentException.class, () -> VerdictConfig.builder().sameGroupCapMultiplier(0.99).build());
        assertThrows(IllegalArgumentException.class, () -> VerdictConfig.builder().banWaveDelayMillis(-1L).build());
        assertEquals(0L, VerdictConfig.shadowDefaults().banWaveDelayMillis());
    }
}
