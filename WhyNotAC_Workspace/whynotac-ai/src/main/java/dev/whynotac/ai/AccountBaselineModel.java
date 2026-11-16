package dev.whynotac.ai;

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
 * (e.g. account sharing / macro profile swap) strictly for human review priority.
 */
public final class AccountBaselineModel {

    public record ShiftAssessment(boolean coldStart, double rmsZScore, String topFeature, double topZScore) {}

    private static final double MIN_SHRINKAGE_VARIANCE = 1e-4;
    private static final long DEFAULT_MIN_BASELINE_SAMPLES = 30;
    private static final long DEFAULT_MIN_SESSION_SAMPLES = 15;

    private final long minBaselineSamples;
    private final long minSessionSamples;
    private final Map<UUID, Map<String, RunningMoments>> baselines = new ConcurrentHashMap<>();

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
            if (!Double.isFinite(baseMean) || !Double.isFinite(baseVar)) {
                continue;
            }
            // Standard error of the session mean under baseline spread + floor shrinkage
            double se = Math.sqrt(Math.max(baseVar, MIN_SHRINKAGE_VARIANCE) / curr.count() + MIN_SHRINKAGE_VARIANCE);
            double z = Math.abs(curr.mean() - baseMean) / se;
            sumSqZ += z * z;
            compared++;
            if (z > worstZ) {
                worstZ = z;
                worstFeature = entry.getKey();
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
        snapshot.features().forEach((name, moments) -> history.compute(name, (k, existing) -> {
            RunningMoments target = existing == null ? new RunningMoments() : existing.copy();
            target.merge(moments);
            return target;
        }));
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
        return baselines.remove(subject) != null;
    }
}
