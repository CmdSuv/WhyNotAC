package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.evidence.Margins;
import dev.whynotac.analysis.policy.ActionPolicy;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.verdict.EvidenceCertificate.VerificationStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Verdict layer entrypoint (VER-01..VER-12): receives admissible evidence from checks, correlates findings per
 * {@link DependencyGroup}, enforces {@link ActionPolicy}, applies {@link CircuitBreaker} and {@link FalseBanBudget},
 * runs {@link SecondVerifier} asynchronously on {@code P-A} ban candidates, and issues {@link EvidenceCertificate}s.
 */
public final class VerdictEngine implements EvidenceSink, AutoCloseable {

    /** Receives every opened case on the analysis thread (alerts, SQLite case store, dossiers). */
    public interface CaseListener {
        void opened(VerdictCase c);
    }

    /** Receives authorized enforcement actions and async second-verification outcomes. */
    public interface EnforcementListener {
        default void hideInformation(VerdictDecision decision) {}

        default void mitigate(VerdictDecision decision) {}

        default void kick(VerdictDecision decision) {}

        default void tempBan(VerdictDecision decision) {}

        default void verificationRejected(VerdictDecision decision) {}

        default void circuitBreakerTripped(CircuitBreaker.Status status) {}
    }

    private static final PlayerSession.AttachmentKey SESSION_STATE = new PlayerSession.AttachmentKey();
    private static final double WEIGHT_ADVANTAGE_PROOF = 1.0;
    private static final double WEIGHT_MODIFICATION_PROOF = 0.5;
    private static final double REVIEW_WEIGHT_DESIGN_STATISTIC = 0.25;
    private static final double REVIEW_WEIGHT_NOMINAL_STATISTIC = 0.15;
    private static final double REVIEW_WEIGHT_CONTEXT = 0.05;
    private static final int MAX_RECONNECT_SUBJECTS = 10_000;

    private static final class SessionState {
        final Map<DependencyGroup, Double> groupScores = new EnumMap<>(DependencyGroup.class);
        final Set<DependencyGroup> advantageProofGroups = EnumSet.noneOf(DependencyGroup.class);
        final List<String> corroboratingFingerprints = new ArrayList<>();
        long lastNanoTime = Long.MIN_VALUE;
        double reviewPriorityScore;
        boolean banDispatchedOrPending;
        boolean seededFromReconnect;
    }

    private record SubjectCorrelationSnapshot(
            Map<DependencyGroup, Double> groupScores,
            Set<DependencyGroup> advantageProofGroups,
            List<String> corroboratingFingerprints,
            long lastNanoTime,
            double reviewPriorityScore,
            long lastConnectionId) {}

    private volatile VerdictConfig config;
    private final CircuitBreaker circuitBreaker;
    private final FalseBanBudget falseBanBudget;
    private final SecondVerifier secondVerifier;
    private final BanWaveQueue banWaveQueue;
    private final LongSupplier wallClockMillis;
    private final List<CaseListener> caseListeners = new CopyOnWriteArrayList<>();
    private final List<EnforcementListener> enforcementListeners = new CopyOnWriteArrayList<>();
    private final Margins margins = new Margins();
    private final Map<String, Long> rejectionsByCheck = new ConcurrentHashMap<>();
    private final Map<UUID, SubjectCorrelationSnapshot> subjectCorrelations = new ConcurrentHashMap<>();
    private final Queue<UUID> subjectCorrelationOrder = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalAccepted = new AtomicLong();
    private final AtomicLong totalRejected = new AtomicLong();

    public VerdictEngine(VerdictConfig config, SecondVerifier.ReplaySource replaySource) {
        this(config, replaySource, System::currentTimeMillis);
    }

    VerdictEngine(VerdictConfig config, SecondVerifier.ReplaySource replaySource, LongSupplier wallClockMillis) {
        this.config = Objects.requireNonNull(config, "config");
        this.circuitBreaker = new CircuitBreaker(config.maxUniquePenalizedPlayersPerMinute());
        this.falseBanBudget = new FalseBanBudget(config.maxFalseBanBudgetPerWindow(), config.falseBanBudgetWindowMillis());
        this.secondVerifier = new SecondVerifier(replaySource, config.secondVerifierWorkers());
        this.banWaveQueue = new BanWaveQueue(config, circuitBreaker, falseBanBudget);
        this.wallClockMillis = Objects.requireNonNull(wallClockMillis, "wallClockMillis");
    }

    public VerdictConfig config() {
        return config;
    }

    /** Atomically updates the active verdict configuration without resetting session states. */
    public void updateConfig(VerdictConfig newConfig) {
        VerdictConfig checked = Objects.requireNonNull(newConfig, "newConfig");
        this.config = checked;
        this.banWaveQueue.updateConfig(checked);
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public FalseBanBudget falseBanBudget() {
        return falseBanBudget;
    }

    public BanWaveQueue banWaveQueue() {
        return banWaveQueue;
    }

    /** Clears cross-reconnect correlation state and pending ban wave entries for {@code playerId} (GDPR / overturn). */
    public void forgetSubject(UUID playerId, long nowMillis) {
        if (playerId != null) {
            subjectCorrelations.remove(playerId);
            banWaveQueue.cancelSubject(playerId, nowMillis);
        }
    }

    private void saveReconnectSnapshot(UUID playerId, SubjectCorrelationSnapshot snap) {
        boolean isNew = subjectCorrelations.put(playerId, snap) == null;
        if (isNew) {
            subjectCorrelationOrder.add(playerId);
            while (subjectCorrelations.size() > MAX_RECONNECT_SUBJECTS) {
                UUID evicted = subjectCorrelationOrder.poll();
                if (evicted == null) {
                    break;
                }
                subjectCorrelations.remove(evicted);
            }
        }
    }

    public void addCaseListener(CaseListener listener) {
        caseListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void addEnforcementListener(EnforcementListener listener) {
        enforcementListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void accept(PlayerSession session, Evidence evidence) {
        totalAccepted.incrementAndGet();
        SessionState state = session.attachment(SESSION_STATE, SessionState::new);
        long nowMillis = wallClockMillis.getAsLong();
        DependencyGroup group = DependencyGroup.ofCheck(evidence.check());
        boolean configuredArmed = config.armedChecks().contains(evidence.check());
        boolean suppressed = circuitBreaker.isSuppressed();
        Lifecycle effectiveLifecycle = configuredArmed && !suppressed ? Lifecycle.ARMED : Lifecycle.SHADOW;
        IdentityTier identity = evidence.admissibility().identity();
        EvidenceClass evidenceClass = evidence.evidenceClass();

        double correlatedScore;
        double reviewScore;
        int distinctAdvantageGroups;
        List<String> groupNames;
        List<String> fingerprintsSnapshot;
        UUID authenticatedUuid = (identity == IdentityTier.AUTHENTICATED || identity == IdentityTier.AUTH_PLUGIN_CONFIRMED)
                && session.identity() != null ? session.identity().playerId() : null;

        synchronized (state) {
            if (!state.seededFromReconnect && authenticatedUuid != null) {
                state.seededFromReconnect = true;
                SubjectCorrelationSnapshot prior = subjectCorrelations.get(authenticatedUuid);
                if (prior != null && prior.lastConnectionId() != session.connectionId()) {
                    state.groupScores.putAll(prior.groupScores());
                    state.advantageProofGroups.addAll(prior.advantageProofGroups());
                    state.corroboratingFingerprints.addAll(prior.corroboratingFingerprints());
                    state.lastNanoTime = prior.lastNanoTime();
                    state.reviewPriorityScore = prior.reviewPriorityScore();
                }
            }
            decay(state, evidence.toNanoTime(), config.correlationHalfLifeNanos());
            double weight = enforcementWeight(evidenceClass);
            if (weight > 0.0) {
                double cap = weight * config.sameGroupCapMultiplier();
                double current = state.groupScores.getOrDefault(group, 0.0);
                state.groupScores.put(group, Math.min(cap, current + weight));
                if (evidenceClass == EvidenceClass.ADVANTAGE_PROOF) {
                    state.advantageProofGroups.add(group);
                }
            } else {
                state.reviewPriorityScore += reviewWeight(evidenceClass);
            }
            state.corroboratingFingerprints.add(evidence.fingerprint());
            while (state.corroboratingFingerprints.size() > 32) {
                state.corroboratingFingerprints.removeFirst();
            }

            correlatedScore = 0.0;
            for (double v : state.groupScores.values()) {
                correlatedScore += v;
            }
            reviewScore = state.reviewPriorityScore;
            distinctAdvantageGroups = state.advantageProofGroups.size();
            groupNames = new ArrayList<>();
            for (DependencyGroup g : state.groupScores.keySet()) {
                groupNames.add(g.id());
            }
            if (groupNames.isEmpty()) {
                groupNames.add(group.id());
            }
            fingerprintsSnapshot = List.copyOf(state.corroboratingFingerprints);
            if (authenticatedUuid != null) {
                saveReconnectSnapshot(authenticatedUuid, new SubjectCorrelationSnapshot(
                        new EnumMap<>(state.groupScores),
                        EnumSet.copyOf(state.advantageProofGroups.isEmpty()
                                ? EnumSet.noneOf(DependencyGroup.class)
                                : state.advantageProofGroups),
                        fingerprintsSnapshot,
                        state.lastNanoTime,
                        state.reviewPriorityScore,
                        session.connectionId()));
            }
        }

        Set<Action> permitted = permittedActions(evidenceClass, identity, effectiveLifecycle);
        Action chosen = defaultPassiveAction(permitted);
        MitigationKind mitigation = MitigationKind.NONE;
        VerificationStatus verificationStatus = VerificationStatus.NOT_NEEDED;
        String verificationDetails = "";
        boolean pendingAsyncTempBanVerification = false;
        boolean newlyEnforced = false;

        if (effectiveLifecycle == Lifecycle.ARMED && !suppressed) {
            if (config.hidesInformationEnabled()
                    && ActionPolicy.enabled(evidenceClass, Action.HIDE_INFORMATION, identity, effectiveLifecycle, false)) {
                chosen = Action.HIDE_INFORMATION;
            }
            if (isDeterministic(evidenceClass)) {
                if (ActionPolicy.enabled(evidenceClass, Action.NEUTRALIZE, identity, effectiveLifecycle, false)
                        && config.mitigationsEnabled()
                        && correlatedScore >= config.minScoreForMitigation()) {
                    MitigationKind candidateMitigation = MitigationKind.forCheck(evidence.check(), evidenceClass);
                    if (candidateMitigation != MitigationKind.NONE) {
                        chosen = Action.NEUTRALIZE;
                        mitigation = candidateMitigation;
                        newlyEnforced = true;
                    }
                }
                if (ActionPolicy.enabled(evidenceClass, Action.KICK, identity, effectiveLifecycle, config.autoKickEnabled())
                        && config.autoKickEnabled()
                        && correlatedScore >= config.minScoreForKick()) {
                    chosen = Action.KICK;
                    newlyEnforced = true;
                }
                if (evidenceClass == EvidenceClass.ADVANTAGE_PROOF
                        && config.autoBanEnabled()
                        && !falseBanBudget.isLocked()
                        && ActionPolicy.enabled(evidenceClass, Action.TEMP_BAN, identity, effectiveLifecycle, true)) {
                    boolean hasMultipleAdvantageGroups = distinctAdvantageGroups >= 2;
                    if (!config.requireCorroborationForTempBan() || hasMultipleAdvantageGroups) {
                        chosen = Action.TEMP_BAN;
                        newlyEnforced = true;
                        verificationStatus = VerificationStatus.NOT_NEEDED;
                        verificationDetails = hasMultipleAdvantageGroups
                                ? "corroborated by " + distinctAdvantageGroups + " independent P-A groups"
                                : "corroboration waived by config";
                    } else if (config.secondVerifierEnabled()) {
                        synchronized (state) {
                            if (!state.banDispatchedOrPending) {
                                pendingAsyncTempBanVerification = true;
                                verificationStatus = VerificationStatus.PENDING;
                                verificationDetails = "queued for independent second verification";
                            }
                        }
                    }
                }
            }
        }

        if (newlyEnforced) {
            String subjectKey = subjectKey(session);
            boolean trippedNow = circuitBreaker.recordEnforcedSubject(subjectKey, nowMillis);
            if (trippedNow || circuitBreaker.isSuppressed()) {
                suppressed = true;
                chosen = defaultPassiveAction(permittedActions(evidenceClass, identity, Lifecycle.SHADOW));
                mitigation = MitigationKind.NONE;
                effectiveLifecycle = Lifecycle.SHADOW;
                permitted = permittedActions(evidenceClass, identity, Lifecycle.SHADOW);
                if (pendingAsyncTempBanVerification) {
                    pendingAsyncTempBanVerification = false;
                    verificationStatus = VerificationStatus.NOT_NEEDED;
                    verificationDetails = "";
                }
                if (trippedNow) {
                    CircuitBreaker.Status status = circuitBreaker.status(nowMillis);
                    for (EnforcementListener l : enforcementListeners) {
                        l.circuitBreakerTripped(status);
                    }
                }
            }
        }

        if (pendingAsyncTempBanVerification && !suppressed) {
            synchronized (state) {
                if (state.banDispatchedOrPending) {
                    pendingAsyncTempBanVerification = false;
                    verificationStatus = VerificationStatus.NOT_NEEDED;
                    verificationDetails = "";
                } else {
                    state.banDispatchedOrPending = true;
                }
            }
        }

        String caseId = UUID.randomUUID().toString();
        String certId = UUID.randomUUID().toString();
        EvidenceCertificate certificate = EvidenceCertificate.issue(
                certId, nowMillis, session.connectionId(), evidence.check(), evidence.checkVersion(),
                evidenceClass, effectiveLifecycle, identity, evidence.fingerprint(),
                fingerprintsSnapshot, groupNames, verificationStatus, verificationDetails, chosen, mitigation);

        VerdictCase verdictCase = new VerdictCase(
                caseId, nowMillis, session, evidence, effectiveLifecycle, group, permitted, chosen,
                mitigation, correlatedScore, reviewScore, distinctAdvantageGroups, suppressed, certificate);

        for (CaseListener listener : caseListeners) {
            listener.opened(verdictCase);
        }

        if (!suppressed && effectiveLifecycle == Lifecycle.ARMED) {
            dispatchEnforcement(verdictCase, session, evidence, chosen, mitigation, certificate);
        }

        if (pendingAsyncTempBanVerification) {
            scheduleAsyncTempBanVerification(verdictCase, state, fingerprintsSnapshot, groupNames);
        }
    }

    private void dispatchEnforcement(VerdictCase verdictCase, PlayerSession session, Evidence evidence,
            Action chosen, MitigationKind mitigation, EvidenceCertificate certificate) {
        switch (chosen) {
            case HIDE_INFORMATION -> {
                VerdictDecision d = new VerdictDecision(verdictCase, session, evidence, Action.HIDE_INFORMATION,
                        MitigationKind.NONE, 0L, certificate, "soft anti-ESP occlusion");
                for (EnforcementListener l : enforcementListeners) {
                    l.hideInformation(d);
                }
            }
            case NEUTRALIZE -> {
                VerdictDecision d = new VerdictDecision(verdictCase, session, evidence, Action.NEUTRALIZE,
                        mitigation, 0L, certificate, "deterministic violation mitigation");
                for (EnforcementListener l : enforcementListeners) {
                    l.mitigate(d);
                }
            }
            case KICK -> {
                VerdictDecision d = new VerdictDecision(verdictCase, session, evidence, Action.KICK,
                        mitigation, 0L, certificate, "correlated violation kick");
                for (EnforcementListener l : enforcementListeners) {
                    l.kick(d);
                }
            }
            case TEMP_BAN -> {
                VerdictDecision d = new VerdictDecision(verdictCase, session, evidence, Action.TEMP_BAN,
                        mitigation, config.tempBanDurationMillis(), certificate, certificate.verificationDetails());
                banWaveQueue.enqueue(d, approved -> {
                    for (EnforcementListener l : enforcementListeners) {
                        l.tempBan(approved);
                    }
                });
            }
            default -> { }
        }
    }

    private void scheduleAsyncTempBanVerification(VerdictCase initialCase, SessionState state,
            List<String> fingerprintsSnapshot, List<String> groupNames) {
        PlayerSession session = initialCase.session();
        Evidence evidence = initialCase.evidence();
        secondVerifier.verifyAsync(session, evidence).whenComplete((result, error) -> {
            long resolvedAt = wallClockMillis.getAsLong();
            if (error != null) {
                synchronized (state) {
                    state.banDispatchedOrPending = false;
                }
                return;
            }
            if (result.status() == VerificationStatus.VERIFIED) {
                boolean allowed = config.autoBanEnabled()
                        && config.armedChecks().contains(evidence.check())
                        && !circuitBreaker.isSuppressed()
                        && !falseBanBudget.isLocked()
                        && ActionPolicy.enabled(evidence.evidenceClass(), Action.TEMP_BAN,
                                evidence.admissibility().identity(), Lifecycle.ARMED, true);
                if (!allowed) {
                    synchronized (state) {
                        state.banDispatchedOrPending = false;
                    }
                    return;
                }
                boolean tripped = circuitBreaker.recordEnforcedSubject(subjectKey(session), resolvedAt);
                if (tripped || circuitBreaker.isSuppressed()) {
                    synchronized (state) {
                        state.banDispatchedOrPending = false;
                    }
                    if (tripped) {
                        CircuitBreaker.Status status = circuitBreaker.status(resolvedAt);
                        for (EnforcementListener l : enforcementListeners) {
                            l.circuitBreakerTripped(status);
                        }
                    }
                    return;
                }
                EvidenceCertificate verifiedCert = EvidenceCertificate.issue(
                        initialCase.certificate().certificateId(), resolvedAt, session.connectionId(),
                        evidence.check(), evidence.checkVersion(), evidence.evidenceClass(),
                        Lifecycle.ARMED, evidence.admissibility().identity(), evidence.fingerprint(),
                        fingerprintsSnapshot, groupNames, VerificationStatus.VERIFIED,
                        result.details(), Action.TEMP_BAN, initialCase.mitigation());
                VerdictDecision decision = new VerdictDecision(initialCase, session, evidence, Action.TEMP_BAN,
                        initialCase.mitigation(), config.tempBanDurationMillis(), verifiedCert, result.details());
                banWaveQueue.enqueue(decision, approved -> {
                    for (EnforcementListener l : enforcementListeners) {
                        l.tempBan(approved);
                    }
                });
            } else if (result.status() == VerificationStatus.MISMATCH) {
                synchronized (state) {
                    state.banDispatchedOrPending = false;
                }
                falseBanBudget.recordIncident(initialCase.id(), result.details(), resolvedAt);
                EvidenceCertificate rejectedCert = EvidenceCertificate.issue(
                        initialCase.certificate().certificateId(), resolvedAt, session.connectionId(),
                        evidence.check(), evidence.checkVersion(), evidence.evidenceClass(),
                        initialCase.lifecycle(), evidence.admissibility().identity(), evidence.fingerprint(),
                        fingerprintsSnapshot, groupNames, VerificationStatus.MISMATCH,
                        result.details(), Action.REVIEW, MitigationKind.NONE);
                VerdictDecision rejectedDecision = new VerdictDecision(initialCase, session, evidence, Action.REVIEW,
                        MitigationKind.NONE, 0L, rejectedCert, result.details());
                for (EnforcementListener l : enforcementListeners) {
                    l.verificationRejected(rejectedDecision);
                }
            } else {
                synchronized (state) {
                    state.banDispatchedOrPending = false;
                }
            }
        });
    }

    @Override
    public void rejected(PlayerSession session, String check, dev.whynotac.analysis.evidence.Admissibility admissibility) {
        totalRejected.incrementAndGet();
        rejectionsByCheck.merge(check, 1L, Long::sum);
    }

    @Override
    public void margin(PlayerSession session, String check, double slack) {
        margins.add(check, slack);
    }

    public long cases() {
        return totalAccepted.get();
    }

    public Map<String, Long> rejections() {
        return Map.copyOf(rejectionsByCheck);
    }

    public Map<String, Margins.Margin> margins() {
        return margins.snapshot();
    }

    public long acceptedCount() {
        return totalAccepted.get();
    }

    public long rejectedCount() {
        return totalRejected.get();
    }

    private Set<Action> permittedActions(EvidenceClass evidenceClass, IdentityTier identity, Lifecycle lifecycle) {
        Set<Action> permitted = EnumSet.noneOf(Action.class);
        for (Action action : Action.values()) {
            boolean optIn = switch (action) {
                case KICK -> config.autoKickEnabled();
                case TEMP_BAN -> config.autoBanEnabled();
                default -> false;
            };
            if (ActionPolicy.enabled(evidenceClass, action, identity, lifecycle, optIn)) {
                if (action == Action.NEUTRALIZE && !config.mitigationsEnabled()) {
                    continue;
                }
                if (action == Action.HIDE_INFORMATION && !config.hidesInformationEnabled()) {
                    continue;
                }
                permitted.add(action);
            }
        }
        return Collections.unmodifiableSet(permitted);
    }

    private static Action defaultPassiveAction(Set<Action> permitted) {
        return permitted.contains(Action.ALERT) ? Action.ALERT : Action.REVIEW;
    }

    private static boolean isDeterministic(EvidenceClass evidenceClass) {
        return evidenceClass == EvidenceClass.ADVANTAGE_PROOF || evidenceClass == EvidenceClass.MODIFICATION_PROOF;
    }

    private static double enforcementWeight(EvidenceClass evidenceClass) {
        return switch (evidenceClass) {
            case ADVANTAGE_PROOF -> WEIGHT_ADVANTAGE_PROOF;
            case MODIFICATION_PROOF -> WEIGHT_MODIFICATION_PROOF;
            case DESIGN_STATISTIC, NOMINAL_STATISTIC, CONTEXT -> 0.0;
        };
    }

    private static double reviewWeight(EvidenceClass evidenceClass) {
        return switch (evidenceClass) {
            case DESIGN_STATISTIC -> REVIEW_WEIGHT_DESIGN_STATISTIC;
            case NOMINAL_STATISTIC -> REVIEW_WEIGHT_NOMINAL_STATISTIC;
            case CONTEXT -> REVIEW_WEIGHT_CONTEXT;
            default -> 0.0;
        };
    }

    private static void decay(SessionState state, long eventNanos, long halfLifeNanos) {
        if (state.lastNanoTime != Long.MIN_VALUE && eventNanos > state.lastNanoTime) {
            long dt = eventNanos - state.lastNanoTime;
            double factor = Math.pow(0.5, (double) dt / halfLifeNanos);
            state.groupScores.replaceAll((g, score) -> score * factor);
            state.reviewPriorityScore *= factor;
        }
        if (state.lastNanoTime == Long.MIN_VALUE || eventNanos > state.lastNanoTime) {
            state.lastNanoTime = eventNanos;
        }
    }

    private static String subjectKey(PlayerSession session) {
        UUID playerId = session.identity() != null ? session.identity().playerId() : null;
        return playerId != null ? playerId.toString() : ("conn:" + session.connectionId());
    }

    @Override
    public void close() {
        banWaveQueue.close();
        secondVerifier.close();
    }
}
