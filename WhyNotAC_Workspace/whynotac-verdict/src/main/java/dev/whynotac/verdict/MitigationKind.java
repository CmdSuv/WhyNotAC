package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.EvidenceClass;

/**
 * Platform-neutral soft mitigation hints permitted under {@code Action.NEUTRALIZE}.
 *
 * <p>Only deterministic classes ({@code P-A} / {@code P-M}) may produce non-{@link #NONE} mitigations;
 * statistical ({@code S}), design-experiment ({@code D}), and context ({@code C}) findings always yield {@link #NONE}.
 */
public enum MitigationKind {
    NONE,
    CANCEL_IMPOSSIBLE_HIT,
    CANCEL_IMPOSSIBLE_BLOCK_ACTION,
    SETBACK_POSITION;

    /** Maps a deterministic finding's check to its soft mitigation kind. */
    public static MitigationKind forCheck(String checkId, EvidenceClass evidenceClass) {
        if (evidenceClass != EvidenceClass.ADVANTAGE_PROOF && evidenceClass != EvidenceClass.MODIFICATION_PROOF) {
            return NONE;
        }
        return switch (checkId) {
            case "attack-reach", "attack-aim" -> CANCEL_IMPOSSIBLE_HIT;
            case "break-time", "use-aim" -> CANCEL_IMPOSSIBLE_BLOCK_ACTION;
            case "movement", "velocity-response" -> SETBACK_POSITION;
            default -> NONE;
        };
    }
}
