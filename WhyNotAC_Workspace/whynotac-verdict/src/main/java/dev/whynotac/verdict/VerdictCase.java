package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.protocol.session.PlayerSession;
import java.util.Set;

/**
 * Opened verdict case corresponding to one admissible finding (preserves compatibility with dossier/alert consumers
 * while carrying dependency-group correlation metrics and an integrity-sealed {@link EvidenceCertificate}).
 */
public record VerdictCase(
        String id,
        long openedEpochMillis,
        PlayerSession session,
        Evidence evidence,
        Lifecycle lifecycle,
        DependencyGroup dependencyGroup,
        Set<Action> permittedActions,
        Action authorizedAction,
        MitigationKind mitigation,
        double correlatedEnforcementScore,
        double reviewPriorityScore,
        int independentAdvantageGroups,
        boolean suppressedBySafetyGate,
        EvidenceCertificate certificate) {

    /** Alias matching {@code ShadowVerdict.Case#createdEpochMillis()}. */
    public long createdEpochMillis() {
        return openedEpochMillis;
    }
}
