package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Signed/digest-sealed certificate binding a case decision, contributing fingerprints, independent dependency
 * groups, and second-verifier status (VER-07).
 */
public record EvidenceCertificate(
        String certificateId,
        long issuedAtEpochMillis,
        long sessionConnectionId,
        String check,
        int checkVersion,
        EvidenceClass evidenceClass,
        Lifecycle lifecycle,
        IdentityTier identityTier,
        String primaryFingerprint,
        List<String> corroboratingFingerprints,
        List<String> independentGroups,
        VerificationStatus verificationStatus,
        String verificationDetails,
        Action decisionAction,
        MitigationKind mitigationKind,
        String sha256Digest) {

    public enum VerificationStatus {
        NOT_NEEDED,
        PENDING,
        VERIFIED,
        MISMATCH,
        NO_RECORDING,
        ERROR
    }

    public EvidenceCertificate {
        Objects.requireNonNull(certificateId, "certificateId");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(evidenceClass, "evidenceClass");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(identityTier, "identityTier");
        Objects.requireNonNull(primaryFingerprint, "primaryFingerprint");
        corroboratingFingerprints = List.copyOf(corroboratingFingerprints);
        independentGroups = List.copyOf(independentGroups);
        Objects.requireNonNull(verificationStatus, "verificationStatus");
        verificationDetails = verificationDetails == null ? "" : verificationDetails;
        Objects.requireNonNull(decisionAction, "decisionAction");
        Objects.requireNonNull(mitigationKind, "mitigationKind");
        Objects.requireNonNull(sha256Digest, "sha256Digest");
    }

    public static EvidenceCertificate issue(
            String certificateId,
            long issuedAtEpochMillis,
            long sessionConnectionId,
            String check,
            int checkVersion,
            EvidenceClass evidenceClass,
            Lifecycle lifecycle,
            IdentityTier identityTier,
            String primaryFingerprint,
            List<String> corroboratingFingerprints,
            List<String> independentGroups,
            VerificationStatus verificationStatus,
            String verificationDetails,
            Action decisionAction,
            MitigationKind mitigationKind) {
        List<String> corr = List.copyOf(corroboratingFingerprints);
        List<String> groups = List.copyOf(independentGroups);
        String details = verificationDetails == null ? "" : verificationDetails;
        String digest = computeDigest(
                certificateId, issuedAtEpochMillis, sessionConnectionId, check, checkVersion,
                evidenceClass, lifecycle, identityTier, primaryFingerprint, corr, groups,
                verificationStatus, details, decisionAction, mitigationKind);
        return new EvidenceCertificate(
                certificateId, issuedAtEpochMillis, sessionConnectionId, check, checkVersion,
                evidenceClass, lifecycle, identityTier, primaryFingerprint, corr, groups,
                verificationStatus, details, decisionAction, mitigationKind, digest);
    }

    /** Recomputes canonical SHA-256 and checks constant-time digest equality. */
    public boolean verifyIntegrity() {
        String expected = computeDigest(
                certificateId, issuedAtEpochMillis, sessionConnectionId, check, checkVersion,
                evidenceClass, lifecycle, identityTier, primaryFingerprint, corroboratingFingerprints,
                independentGroups, verificationStatus, verificationDetails, decisionAction, mitigationKind);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                sha256Digest.getBytes(StandardCharsets.US_ASCII));
    }

    private static String computeDigest(
            String certificateId,
            long issuedAtEpochMillis,
            long sessionConnectionId,
            String check,
            int checkVersion,
            EvidenceClass evidenceClass,
            Lifecycle lifecycle,
            IdentityTier identityTier,
            String primaryFingerprint,
            List<String> corroboratingFingerprints,
            List<String> independentGroups,
            VerificationStatus verificationStatus,
            String verificationDetails,
            Action decisionAction,
            MitigationKind mitigationKind) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, certificateId);
            feed(md, Long.toString(issuedAtEpochMillis));
            feed(md, Long.toString(sessionConnectionId));
            feed(md, check);
            feed(md, Integer.toString(checkVersion));
            feed(md, evidenceClass.code());
            feed(md, lifecycle.name());
            feed(md, identityTier.name());
            feed(md, primaryFingerprint);
            feed(md, Integer.toString(corroboratingFingerprints.size()));
            for (String fp : corroboratingFingerprints) {
                feed(md, fp);
            }
            feed(md, Integer.toString(independentGroups.size()));
            for (String g : independentGroups) {
                feed(md, g);
            }
            feed(md, verificationStatus.name());
            feed(md, verificationDetails);
            feed(md, decisionAction.name());
            feed(md, mitigationKind.name());
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void feed(MessageDigest md, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        md.update((byte) (bytes.length >>> 24));
        md.update((byte) (bytes.length >>> 16));
        md.update((byte) (bytes.length >>> 8));
        md.update((byte) bytes.length);
        md.update(bytes);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        str(sb, "certificateId", certificateId);
        num(sb, "issuedAtEpochMillis", issuedAtEpochMillis);
        num(sb, "sessionConnectionId", sessionConnectionId);
        str(sb, "check", check);
        num(sb, "checkVersion", checkVersion);
        str(sb, "evidenceClass", evidenceClass.code());
        str(sb, "lifecycle", lifecycle.name());
        str(sb, "identityTier", identityTier.name());
        str(sb, "primaryFingerprint", primaryFingerprint);
        arr(sb, "corroboratingFingerprints", corroboratingFingerprints);
        arr(sb, "independentGroups", independentGroups);
        str(sb, "verificationStatus", verificationStatus.name());
        str(sb, "verificationDetails", verificationDetails);
        str(sb, "decisionAction", decisionAction.name());
        str(sb, "mitigationKind", mitigationKind.name());
        sb.append("  \"sha256Digest\": \"").append(escape(sha256Digest)).append("\"\n}\n");
        return sb.toString();
    }

    private static void str(StringBuilder sb, String k, String v) {
        sb.append("  \"").append(k).append("\": \"").append(escape(v)).append("\",\n");
    }

    private static void num(StringBuilder sb, String k, long v) {
        sb.append("  \"").append(k).append("\": ").append(v).append(",\n");
    }

    private static void arr(StringBuilder sb, String k, List<String> vals) {
        sb.append("  \"").append(k).append("\": [");
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(escape(vals.get(i))).append('"');
        }
        sb.append("],\n");
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return out.toString();
    }
}
