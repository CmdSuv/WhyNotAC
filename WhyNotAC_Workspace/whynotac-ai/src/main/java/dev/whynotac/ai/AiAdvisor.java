package dev.whynotac.ai;

import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.network.NetworkEnvelope;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.verdict.VerdictCase;
import dev.whynotac.verdict.VerdictEngine;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Advisory AI facade uniting {@link BehavioralFeatureExtractor}, {@link AccountBaselineModel},
 * {@link ProbeScheduler}, and review queue triage scoring (AI-00..AI-05).
 *
 * <p>Strictly non-punitive per {@code docs/POLICY.md}: computes priority scores and schedules probe experiments
 * only; never issues mitigations, kicks, or bans. Long-lived persistence/sync hooks only fire for verified
 * ({@code AUTHENTICATED_*} / {@code AUTH_PLUGIN_CONFIRMED}) clean sessions.
 */
public final class AiAdvisor implements TimelineListener, VerdictEngine.CaseListener {

    public record CasePriority(String caseId, double score0To100, AccountBaselineModel.ShiftAssessment shift, double eValueWealth) {}

    /**
     * Within-session cross-feature risk assessment (works even on cold-start accounts with no prior history):
     * scores robotic click regularity, zero-jerk combat tracking, attack-tick snap acceleration, and rapid
     * container looting strictly for review triage.
     */
    public record IntrinsicRiskAssessment(double score0To100, String dominantSignal) {}

    /** Notified on clean disconnect of an authenticated session so baseline moments can persist and sync. */
    @FunctionalInterface
    public interface CleanSessionObserver {
        void onCleanAuthenticatedSession(
                UUID playerId,
                Identity identity,
                BehavioralFeatureExtractor.Snapshot sessionSnapshot,
                Map<String, RunningMoments> updatedBaseline);
    }

    private final BehavioralFeatureExtractor extractor = new BehavioralFeatureExtractor();
    private final AccountBaselineModel baselineModel = new AccountBaselineModel();
    private final ProbeScheduler probeScheduler;
    private final DesignProbeEngine designProbeEngine;
    private final Map<String, CasePriority> prioritizedCases = new ConcurrentHashMap<>();
    private final Map<String, UUID> caseSubjects = new ConcurrentHashMap<>();
    private final Set<Long> flaggedConnections = ConcurrentHashMap.newKeySet();
    private final Set<Long> mergedConnections = ConcurrentHashMap.newKeySet();
    private final Map<Long, Identity> authenticatedConnections = new ConcurrentHashMap<>();
    private final List<CleanSessionObserver> cleanObservers = new CopyOnWriteArrayList<>();

    public AiAdvisor() {
        this((s, e) -> { });
    }

    public AiAdvisor(EvidenceSink sink) {
        this(sink, new ProbeScheduler());
    }

    public AiAdvisor(EvidenceSink sink, ProbeScheduler probeScheduler) {
        this.probeScheduler = java.util.Objects.requireNonNull(probeScheduler, "probeScheduler");
        this.designProbeEngine = new DesignProbeEngine(this.probeScheduler, sink);
    }

    public DesignProbeEngine designProbeEngine() {
        return designProbeEngine;
    }

    public void addCleanSessionObserver(CleanSessionObserver observer) {
        if (observer != null) {
            cleanObservers.add(observer);
        }
    }

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
        designProbeEngine.onEvent(session, event);
        if (event instanceof ClientFact fact && fact.fact() == Fact.IDENTITY && fact.trust() == Trust.AUTHENTIC) {
            try {
                Identity parsed = Identity.valueOf(fact.value());
                if (NetworkEnvelope.isLongLivedAuthenticated(parsed)) {
                    authenticatedConnections.put(session.connectionId(), parsed);
                }
            } catch (IllegalArgumentException ignored) { }
        } else if (event instanceof TimelineEvent.Disconnected) {
            long connId = session.connectionId();
            boolean flagged = flaggedConnections.remove(connId);
            Identity authedIdentity = authenticatedConnections.remove(connId);
            PlayerSession.Identity id = session.identity();
            if (!flagged && id != null && mergedConnections.add(connId)) {
                BehavioralFeatureExtractor.Snapshot snap = extractor.snapshot(session);
                baselineModel.mergeSession(id.playerId(), snap);
                if (authedIdentity != null && NetworkEnvelope.isLongLivedAuthenticated(authedIdentity)) {
                    Map<String, RunningMoments> updated = baselineModel.baselineSnapshot(id.playerId());
                    for (CleanSessionObserver obs : cleanObservers) {
                        obs.onCleanAuthenticatedSession(id.playerId(), authedIdentity, snap, updated);
                    }
                }
            }
            mergedConnections.remove(connId);
        }
    }

    @Override
    public void opened(VerdictCase c) {
        PlayerSession session = c.session();
        UUID playerId = session != null && session.identity() != null ? session.identity().playerId() : null;
        AccountBaselineModel.ShiftAssessment shift = new AccountBaselineModel.ShiftAssessment(true, 0.0, "", 0.0);
        IntrinsicRiskAssessment intrinsic = new IntrinsicRiskAssessment(0.0, "none");
        double wealth = 1.0;
        if (playerId != null && session != null) {
            if (c.evidence().evidenceClass() == EvidenceClass.ADVANTAGE_PROOF
                    || c.evidence().evidenceClass() == EvidenceClass.MODIFICATION_PROOF) {
                flaggedConnections.add(session.connectionId());
            }
            BehavioralFeatureExtractor.Snapshot snap = extractor.snapshot(session);
            shift = baselineModel.assess(playerId, snap);
            intrinsic = assessIntrinsicRisk(snap);
            EValueProcess ep = probeScheduler.processFor(playerId);
            if (ep != null) {
                wealth = ep.wealth();
            }
            caseSubjects.put(c.id(), playerId);
        }
        double score = Math.min(100.0, computePriorityScore(c, shift, wealth) + Math.min(15.0, intrinsic.score0To100() * 0.25));
        prioritizedCases.put(c.id(), new CasePriority(c.id(), score, shift, wealth));
    }

    public CasePriority priorityOf(String caseId) {
        return prioritizedCases.get(caseId);
    }

    /** Evaluates within-session cross-feature anomaly risk (0..100) without requiring historical sessions. */
    public IntrinsicRiskAssessment assessIntrinsicRisk(BehavioralFeatureExtractor.Snapshot snap) {
        if (snap == null) {
            return new IntrinsicRiskAssessment(0.0, "none");
        }
        double risk = 0.0;
        String dominant = "none";
        double bestContribution = 0.0;

        RunningMoments clicks = snap.features().get(BehavioralFeatureExtractor.CLICK_INTERVAL_TICKS);
        if (clicks != null && clicks.count() >= 15) {
            double mean = clicks.mean();
            double sd = clicks.sampleStdDev();
            if (Double.isFinite(mean) && Double.isFinite(sd) && mean <= 3.5 && sd <= 0.18) {
                double contrib = Math.min(35.0, (0.20 - sd) * 160.0 + (3.8 - mean) * 4.0);
                risk += contrib;
                if (contrib > bestContribution) {
                    bestContribution = contrib;
                    dominant = "low-click-variance";
                }
            }
        }

        RunningMoments combatStep = snap.features().get(BehavioralFeatureExtractor.COMBAT_AIM_STEP_DEG);
        RunningMoments combatJerk = snap.features().get(BehavioralFeatureExtractor.COMBAT_AIM_JERK_DEG);
        if (combatStep != null && combatJerk != null && combatStep.count() >= 15 && combatJerk.count() >= 15) {
            double stepMean = combatStep.mean();
            double jerkMean = combatJerk.mean();
            if (Double.isFinite(stepMean) && Double.isFinite(jerkMean) && stepMean >= 1.5 && jerkMean <= 0.08) {
                double contrib = Math.min(35.0, (0.10 - jerkMean) * 250.0 + Math.min(10.0, stepMean));
                risk += contrib;
                if (contrib > bestContribution) {
                    bestContribution = contrib;
                    dominant = "zero-jerk-combat-aim";
                }
            }
        }

        RunningMoments snapRatio = snap.features().get(BehavioralFeatureExtractor.ATTACK_SNAP_RATIO);
        if (snapRatio != null && snapRatio.count() >= 6) {
            double ratioMean = snapRatio.mean();
            if (Double.isFinite(ratioMean) && ratioMean >= 4.5) {
                double contrib = Math.min(25.0, (ratioMean - 3.5) * 4.0);
                risk += contrib;
                if (contrib > bestContribution) {
                    bestContribution = contrib;
                    dominant = "attack-snap-acceleration";
                }
            }
        }

        RunningMoments containerClicks = snap.features().get(BehavioralFeatureExtractor.CONTAINER_CLICK_INTERVAL_TICKS);
        if (containerClicks != null && containerClicks.count() >= 8) {
            double cMean = containerClicks.mean();
            if (Double.isFinite(cMean) && cMean <= 1.2) {
                double contrib = Math.min(20.0, (1.5 - cMean) * 14.0);
                risk += contrib;
                if (contrib > bestContribution) {
                    dominant = "rapid-container-cadence";
                }
            }
        }

        return new IntrinsicRiskAssessment(Math.min(100.0, risk), dominant);
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
