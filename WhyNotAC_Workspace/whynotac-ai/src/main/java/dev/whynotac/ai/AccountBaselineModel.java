package dev.whynotac.ai;

import dev.whynotac.math.stats.RunningCovarianceMatrix;
import dev.whynotac.math.stats.RunningMoments;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-account multi-session behavioral baseline model (AI-02): uses exact {@link RunningMoments#merge} to roll
 * sessions into long-term profiles without storing raw packet traces, and flags large behavioral shifts
 * (mean drift AND variance-collapse / spread shift) strictly for human review priority.
 */
public final class AccountBaselineModel {

    public record ShiftAssessment(boolean coldStart, double rmsZScore, String topFeature, double topZScore) {}

    private static final double MIN_SHRINKAGE_VARIANCE = 1e-4;
    private static final double MIN_SPREAD_SE_FLOOR = 0.02;
    private static final double SPREAD_DEADZONE_Z = 1.5;
    private static final long DEFAULT_MIN_BASELINE_SAMPLES = 30;
    private static final long DEFAULT_MIN_SESSION_SAMPLES = 15;

    private final long minBaselineSamples;
    private final long minSessionSamples;
    private final Map<UUID, Map<String, RunningMoments>> baselines = new ConcurrentHashMap<>();
    private final Map<UUID, RunningCovarianceMatrix> covarianceBaselines = new ConcurrentHashMap<>();

    public AccountBaselineModel() {
        this(DEFAULT_MIN_BASELINE_SAMPLES, DEFAULT_MIN_SESSION_SAMPLES);
    }

    public AccountBaselineModel(long minBaselineSamples, long minSessionSamples) {
        this.minBaselineSamples = Math.max(5, minBaselineSamples);
        this.minSessionSamples = Math.max(5, minSessionSamples);
    }

    /** Evaluates how strongly {@code current} diverges from {@code subject}'s historical baseline (zero mutation). */
    public ShiftAssessment assess(UUID subject, BehavioralFeatureExtractor.Snapshot current) {
        Objects.requireNonNull(subject, "subject");
        Map<String, RunningMoments> history = baselines.get(subject);
        if (history == null || history.isEmpty()) {
            return new ShiftAssessment(true, 0.0, "", 0.0);
        }
        double sumSqZ = 0.0;
        int compared = 0;
        String worstFeature = "";
        double worstZ = 0.0;

        for (Map.Entry<String, RunningMoments> entry : current.features().entrySet()) {
            RunningMoments curr = entry.getValue();
            RunningMoments base = history.get(entry.getKey());
            if (base == null || base.count() < minBaselineSamples || curr.count() < minSessionSamples) {
                continue;
            }
            double baseMean = base.mean();
            double baseVar = base.sampleVariance();
            double currMean = curr.mean();
            double currVar = curr.sampleVariance();
            if (!Double.isFinite(baseMean) || !Double.isFinite(baseVar) || !Double.isFinite(currMean)) {
                continue;
            }
            // 1. Location shift: standard error of the session mean under baseline spread + floor shrinkage
            double seMean = Math.sqrt(Math.max(baseVar, MIN_SHRINKAGE_VARIANCE) / curr.count() + MIN_SHRINKAGE_VARIANCE);
            double zMean = Math.abs(currMean - baseMean) / seMean;

            // 2. Dispersion shift (catches same-mean robotic variance collapse or explosive snap spread)
            double zSpread = 0.0;
            if (Double.isFinite(currVar) && baseVar >= 1e-3) {
                double baseStd = Math.sqrt(baseVar);
                double currStd = Math.sqrt(Math.max(0.0, currVar));
                double seStd = Math.sqrt(baseVar / (2.0 * curr.count()) + MIN_SPREAD_SE_FLOOR * MIN_SPREAD_SE_FLOOR);
                double rawSpreadZ = Math.abs(currStd - baseStd) / seStd;
                // Collapse penalty when session variance drops below 20% of human baseline spread
                if (baseStd >= 0.15 && currStd <= baseStd * 0.20) {
                    rawSpreadZ += (baseStd / Math.max(0.02, currStd)) * 0.75;
                }
                zSpread = Math.max(0.0, rawSpreadZ - SPREAD_DEADZONE_Z);
            }

            double z = Math.hypot(zMean, zSpread);
            sumSqZ += z * z;
            compared++;
            if (z > worstZ) {
                worstZ = z;
                worstFeature = entry.getKey();
            }
        }
        RunningCovarianceMatrix baseCov = covarianceBaselines.get(subject);
        RunningCovarianceMatrix currCov = current.combatCovariance();
        if (baseCov != null && currCov != null && baseCov.count() >= minBaselineSamples && currCov.count() >= 5) {
            double mahalanobis = baseCov.mahalanobisDistance(currCov.mean(), 0.05);
            if (Double.isFinite(mahalanobis)) {
                double normZ = mahalanobis / Math.sqrt(currCov.dim());
                sumSqZ += normZ * normZ;
                compared++;
                if (normZ > worstZ) {
                    worstZ = normZ;
                    worstFeature = "combat_covariance_mahalanobis";
                }
            }
        }
        if (compared == 0) {
            return new ShiftAssessment(true, 0.0, "", 0.0);
        }
        return new ShiftAssessment(false, Math.sqrt(sumSqZ / compared), worstFeature, worstZ);
    }

    /** Merges a trusted/finished session snapshot into {@code subject}'s long-term baseline. */
    public void mergeSession(UUID subject, BehavioralFeatureExtractor.Snapshot snapshot) {
        Objects.requireNonNull(subject, "subject");
        Map<String, RunningMoments> history = baselines.computeIfAbsent(subject, id -> new ConcurrentHashMap<>());
        snapshot.features().forEach((name, moments) -> {
            if (moments.count() == 0) {
                return;
            }
            history.compute(name, (k, existing) -> {
                RunningMoments target = existing == null ? new RunningMoments() : existing.copy();
                target.merge(moments);
                return target;
            });
        });
        if (snapshot.combatCovariance() != null && snapshot.combatCovariance().count() > 0) {
            covarianceBaselines.compute(subject, (k, existing) -> {
                RunningCovarianceMatrix target = existing == null
                        ? new RunningCovarianceMatrix(BehavioralFeatureExtractor.COMBAT_COVARIANCE_DIM)
                        : existing.copy();
                target.merge(snapshot.combatCovariance());
                return target;
            });
        }
    }

    /** Seeds persisted baseline moments once (no-op if this subject already has a loaded baseline in memory). */
    public void seedBaselineIfEmpty(UUID subject, Map<String, RunningMoments> persisted) {
        if (subject == null || persisted == null || persisted.isEmpty()) {
            return;
        }
        baselines.computeIfAbsent(subject, id -> {
            Map<String, RunningMoments> map = new ConcurrentHashMap<>();
            persisted.forEach((k, v) -> {
                if (v != null && v.count() > 0) {
                    map.put(k, v.copy());
                }
            });
            return map;
        });
    }

    /** Seeds persisted multivariate combat covariance once (no-op if already loaded in memory). */
    public void seedCovarianceIfEmpty(UUID subject, RunningCovarianceMatrix persisted) {
        if (subject == null || persisted == null || persisted.count() <= 0) {
            return;
        }
        covarianceBaselines.computeIfAbsent(subject, id -> persisted.copy());
    }

    /** Returns an isolated copy of {@code subject}'s persisted multivariate combat covariance, or null if absent. */
    public RunningCovarianceMatrix covarianceSnapshot(UUID subject) {
        RunningCovarianceMatrix cov = subject == null ? null : covarianceBaselines.get(subject);
        return cov == null ? null : cov.copy();
    }

    public Map<String, RunningMoments> baselineSnapshot(UUID subject) {
        Map<String, RunningMoments> history = baselines.get(subject);
        if (history == null) {
            return Map.of();
        }
        Map<String, RunningMoments> copy = new LinkedHashMap<>();
        history.forEach((k, v) -> copy.put(k, v.copy()));
        return Collections.unmodifiableMap(copy);
    }

    public boolean forget(UUID subject) {
        covarianceBaselines.remove(subject);
        return baselines.remove(subject) != null;
    }
}
