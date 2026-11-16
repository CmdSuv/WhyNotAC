package dev.whynotac.storage;

import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.verdict.VerdictCase;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Four-eyes review, appeal resolution, and plain-language explanation workflow (ADM-02, VER-15).
 *
 * <p>A binding staff confirmation or permanent escalation requires two distinct reviewers ("four-eyes"
 * safeguard) so no single reviewer mistake or compromised staff account can silently finalize a permanent ban.
 */
public final class ReviewWorkflow {

    public enum Status {
        OPEN,
        FIRST_REVIEWED,
        CONFIRMED_FOUR_EYES,
        APPEALED,
        UPHELD,
        OVERTURNED
    }

    public record ReviewRecord(
            String caseId,
            Status status,
            String primaryReviewer,
            String secondaryReviewer,
            String notes,
            long updatedEpochMillis) {}

    private final Map<String, ReviewRecord> records = new ConcurrentHashMap<>();

    public ReviewRecord open(String caseId, long epochMillis) {
        return records.computeIfAbsent(Objects.requireNonNull(caseId, "caseId"),
                id -> new ReviewRecord(id, Status.OPEN, null, null, "", epochMillis));
    }

    /** Records the first review pass by {@code reviewer}. */
    public ReviewRecord recordFirstReview(String caseId, String reviewer, String notes, long epochMillis) {
        String cleanReviewer = requireReviewer(reviewer);
        return records.compute(caseId, (id, existing) -> new ReviewRecord(
                id,
                Status.FIRST_REVIEWED,
                cleanReviewer,
                existing != null ? existing.secondaryReviewer() : null,
                notes == null ? "" : notes,
                epochMillis));
    }

    /**
     * Records the independent second reviewer confirmation. Fails if {@code secondReviewer} matches the first
     * reviewer or if no first review exists yet.
     */
    public ReviewRecord confirmSecondReview(String caseId, String secondReviewer, String notes, long epochMillis) {
        String cleanSecond = requireReviewer(secondReviewer);
        return records.compute(caseId, (id, existing) -> {
            if (existing == null || existing.primaryReviewer() == null) {
                throw new IllegalStateException("case " + caseId + " requires a first review before second-reviewer confirmation");
            }
            if (existing.primaryReviewer().equalsIgnoreCase(cleanSecond)) {
                throw new IllegalArgumentException("four-eyes violation: second reviewer must differ from " + existing.primaryReviewer());
            }
            String combinedNotes = existing.notes().isBlank() ? (notes == null ? "" : notes)
                    : (notes == null || notes.isBlank() ? existing.notes() : existing.notes() + "; " + notes);
            return new ReviewRecord(id, Status.CONFIRMED_FOUR_EYES, existing.primaryReviewer(), cleanSecond, combinedNotes, epochMillis);
        });
    }

    public ReviewRecord submitAppeal(String caseId, String reason, long epochMillis) {
        return records.compute(caseId, (id, existing) -> new ReviewRecord(
                id,
                Status.APPEALED,
                existing != null ? existing.primaryReviewer() : null,
                existing != null ? existing.secondaryReviewer() : null,
                reason == null ? "" : reason,
                epochMillis));
    }

    /** Resolves an appeal with distinct reviewer check when confirming ({@code upheld == true}). */
    public ReviewRecord resolveAppeal(String caseId, String reviewer, boolean upheld, String notes, long epochMillis) {
        String cleanReviewer = requireReviewer(reviewer);
        return records.compute(caseId, (id, existing) -> {
            if (upheld && existing != null && existing.primaryReviewer() != null
                    && existing.primaryReviewer().equalsIgnoreCase(cleanReviewer)) {
                throw new IllegalArgumentException("appeal upholding requires a different reviewer than " + existing.primaryReviewer());
            }
            return new ReviewRecord(
                    id,
                    upheld ? Status.UPHELD : Status.OVERTURNED,
                    existing != null && existing.primaryReviewer() != null ? existing.primaryReviewer() : cleanReviewer,
                    upheld ? cleanReviewer : (existing != null ? existing.secondaryReviewer() : null),
                    notes == null ? "" : notes,
                    epochMillis);
        });
    }

    public Optional<ReviewRecord> status(String caseId) {
        return Optional.ofNullable(records.get(caseId));
    }

    /** Generates a clear, non-technical plain-language explanation suitable for staff and player appeal views (VER-15). */
    public static String plainLanguageExplanation(VerdictCase c) {
        Evidence e = c.evidence();
        String classDesc = switch (e.evidenceClass()) {
            case ADVANTAGE_PROOF -> "Mathematically impossible under vanilla 1.21.11 client mechanics with all lag and uncertainty bounded";
            case MODIFICATION_PROOF -> "Client sent a protocol pattern that an unmodified vanilla client never sends (not proof of unfair advantage by itself)";
            case DESIGN_STATISTIC -> "Randomized design probe signal (informational only, never triggers punishment)";
            case NOMINAL_STATISTIC -> "Statistical pattern differing from typical baseline (informational only, never triggers punishment)";
            case CONTEXT -> "Session context note for staff review";
        };
        return "Case " + c.id().substring(0, Math.min(8, c.id().length()))
                + " | Check: " + e.check() + " (" + e.evidenceClass().code() + ", group " + c.dependencyGroup().id() + ")"
                + " | Reason: " + classDesc + ": " + e.summary()
                + " | Window: seq " + e.fromSequence() + ".." + e.toSequence()
                + " | Identity tier: " + e.admissibility().identity()
                + " | Action: " + c.authorizedAction()
                + (c.certificate().evidenceClass() == EvidenceClass.ADVANTAGE_PROOF
                        ? " | Re-verification: " + c.certificate().verificationStatus() : "");
    }

    private static String requireReviewer(String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("reviewer must be non-blank");
        }
        return reviewer.strip();
    }
}
