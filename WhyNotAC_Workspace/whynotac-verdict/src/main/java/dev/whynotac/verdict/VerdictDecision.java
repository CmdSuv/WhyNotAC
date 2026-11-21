package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.protocol.session.PlayerSession;

/**
 * Authorization outcome emitted when active enforcement (mitigation, kick, or temporary ban) or an asynchronous
 * second-verification update resolves for a {@link VerdictCase}.
 */
public record VerdictDecision(
        VerdictCase verdictCase,
        PlayerSession session,
        Evidence evidence,
        Action action,
        MitigationKind mitigation,
        long tempBanDurationMillis,
        EvidenceCertificate certificate,
        String reason) {}
