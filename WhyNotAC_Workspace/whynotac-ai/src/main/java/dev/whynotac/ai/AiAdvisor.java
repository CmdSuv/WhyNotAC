package dev.whynotac.ai;

import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.verdict.VerdictCase;
import dev.whynotac.verdict.VerdictEngine;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advisory AI facade uniting {@link BehavioralFeatureExtractor}, {@link AccountBaselineModel},
 * {@link ProbeScheduler}, and review queue triage scoring (AI-00..AI-05).
 *
 * <p>Strictly non-punitive per {@code docs/POLICY.md}: computes priority scores and schedules probe experiments
 * only; never issues mitigations, kicks, or bans.
 */
public final class AiAdvisor implements TimelineListener, VerdictEngine.CaseListener {

    public record CasePriority(String caseId, double score0To100, AccountBaselineModel.ShiftAssessment shift, double eValueWealth) {}

    private final BehavioralFeatureExtractor extractor = new BehavioralFeatureExtractor();
    private final AccountBaselineModel baselineModel = new AccountBaselineModel();
    private final ProbeScheduler probeScheduler = new ProbeScheduler();
    private final Map<String, CasePriority> prioritizedCases = new ConcurrentHashMap<>();
    private final Map<String, UUID> caseSubjects = new ConcurrentHashMap<>();
    private final Set<Long> flaggedConnections = ConcurrentHashMap.newKeySet();
    private final Set<Long> mergedConnections = ConcurrentHashMap.newKeySet();

    public BehavioralFeatureExtractor extractor() {
        return extractor;
    }

    public AccountBaselineModel baselineModel() {
        return baselineModel;
    }

    public ProbeScheduler probeScheduler() {
        return probeScheduler;
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        extractor.onEvent(session, event);
        if (event instanceof TimelineEvent.Disconnected) {
            long connId = session.connectionId();
            boolean flagged = flaggedConnections.remove(connId);
            PlayerSession.Identity id = session.identity();
            if (!flagged && id != null && mergedConnections.add(connId)) {
                baselineModel.mergeSession(id.playerId(), extractor.snapshot(session));
            }
            mergedConnections.remove(connId);
        }
    }

    @Override
    public void opened(VerdictCase c) {
        PlayerSession session = c.session();
        UUID playerId = session != null && session.identity() != null ? session.identity().playerId() : null;
        AccountBaselineModel.ShiftAssessment shift = new AccountBaselineModel.ShiftAssessment(true, 0.0, "", 0.0);
        double wealth = 1.0;
        if (playerId != null && session != null) {
            if (c.evidence().evidenceClass() == EvidenceClass.ADVANTAGE_PROOF
                    || c.evidence().evidenceClass() == EvidenceClass.MODIFICATION_PROOF) {
                flaggedConnections.add(session.connectionId());
            }
            shift = baselineModel.assess(playerId, extractor.snapshot(session));
            EValueProcess ep = probeScheduler.processFor(playerId);
            if (ep != null) {
                wealth = ep.wealth();
            }
            caseSubjects.put(c.id(), playerId);
        }
        double score = computePriorityScore(c, shift, wealth);
        prioritizedCases.put(c.id(), new CasePriority(c.id(), score, shift, wealth));
    }

    public CasePriority priorityOf(String caseId) {
        return prioritizedCases.get(caseId);
    }

    /** Records a clean session's behavioral snapshot once into the subject's multi-session baseline. */
    public void mergeCleanSession(UUID playerId, PlayerSession session) {
        if (playerId != null && session != null && mergedConnections.add(session.connectionId())) {
            baselineModel.mergeSession(playerId, extractor.snapshot(session));
        }
    }

    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        baselineModel.forget(playerId);
        probeScheduler.forget(playerId);
        caseSubjects.entrySet().removeIf(entry -> {
            if (playerId.equals(entry.getValue())) {
                prioritizedCases.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    static double computePriorityScore(VerdictCase c, AccountBaselineModel.ShiftAssessment shift, double wealth) {
        double deterministicBoost = Math.min(40.0, c.correlatedEnforcementScore() * 20.0);
        double reviewStatBoost = Math.min(25.0, c.reviewPriorityScore() * 25.0);
        double shiftBoost = shift.coldStart() ? 0.0 : Math.min(15.0, shift.rmsZScore() * 3.0);
        double eValueBoost = wealth > 1.0 ? Math.min(20.0, Math.log10(wealth) * 6.0) : 0.0;
        return Math.min(100.0, deterministicBoost + reviewStatBoost + shiftBoost + eValueBoost);
    }
}
