package dev.whynotac.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.verdict.EvidenceCertificate.VerificationStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceCertificateTest {

    @Test
    void certificateDetectsFieldTamperingViaSha256IntegrityCheck() {
        EvidenceCertificate cert = EvidenceCertificate.issue(
                "cert-1",
                123456789L,
                42L,
                "attack-reach",
                1,
                EvidenceClass.ADVANTAGE_PROOF,
                Lifecycle.ARMED,
                IdentityTier.AUTHENTICATED,
                "attack-reach@1 P-A [10..20] {distance=6.5}",
                List.of("attack-reach@1 P-A [10..20] {distance=6.5}"),
                List.of("attack-reach"),
                VerificationStatus.VERIFIED,
                "reproduced fingerprint via isolated replay",
                Action.TEMP_BAN,
                MitigationKind.CANCEL_IMPOSSIBLE_HIT);

        assertTrue(cert.verifyIntegrity());
        assertEquals(64, cert.sha256Digest().length());
        assertTrue(cert.toJson().contains("\"sha256Digest\": \"" + cert.sha256Digest() + "\""));

        EvidenceCertificate tampered = new EvidenceCertificate(
                cert.certificateId(),
                cert.issuedAtEpochMillis(),
                cert.sessionConnectionId(),
                cert.check(),
                cert.checkVersion(),
                cert.evidenceClass(),
                cert.lifecycle(),
                cert.identityTier(),
                "attack-reach@1 P-A [10..20] {distance=2.9}", // altered detail
                cert.corroboratingFingerprints(),
                cert.independentGroups(),
                cert.verificationStatus(),
                cert.verificationDetails(),
                cert.decisionAction(),
                cert.mitigationKind(),
                cert.sha256Digest());
        assertFalse(tampered.verifyIntegrity());
    }
}
