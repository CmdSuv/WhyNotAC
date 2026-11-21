package dev.whynotac.analysis.evidence;

/** The evidence classes of docs/POLICY.md. Which actions each may trigger is decided by the policy alone. */
public enum EvidenceClass {
    /** P-A: impossible under vanilla with every uncertainty bounded, and an advantage; re-verified. */
    ADVANTAGE_PROOF("P-A"),
    /** P-M: a sequence the vanilla client never sends: the client is modified (not necessarily cheating). */
    MODIFICATION_PROOF("P-M"),
    /** D: an e-value whose validity comes from the experiment's design (random probes, honeypots). */
    DESIGN_STATISTIC("D"),
    /** S: a model-based signal (statistics, AI). */
    NOMINAL_STATISTIC("S"),
    /** C: context for review, not evidence. */
    CONTEXT("C");

    private final String code;

    EvidenceClass(String code) {
        this.code = code;
    }

    /** The short name used in docs/POLICY.md. */
    public String code() {
        return code;
    }
}
