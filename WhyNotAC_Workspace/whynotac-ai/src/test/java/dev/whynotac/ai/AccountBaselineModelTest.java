package dev.whynotac.ai;

import dev.whynotac.math.stats.RunningMoments;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AccountBaselineModelTest {

    @Test
    void coldStartUntilEnoughBaselineAndSessionSamples() {
        AccountBaselineModel model = new AccountBaselineModel(30, 15);
        UUID player = UUID.randomUUID();

        BehavioralFeatureExtractor.Snapshot small = snapshotOf(5.0, 0.4, 20, 4.0, 20);
        assertTrue(model.assess(player, small).coldStart());

        model.mergeSession(player, small);
        // Baseline count is 20 < minBaselineSamples(30)
        assertTrue(model.assess(player, snapshotOf(5.0, 0.4, 20, 4.0, 20)).coldStart());
    }

    @Test
    void mergesSessionsAndDetectsLargeBehavioralShiftWithoutTriggeringOnConsistentPlay() {
        AccountBaselineModel model = new AccountBaselineModel(30, 15);
        UUID player = UUID.randomUUID();

        // Two historical sessions: normal ~5 tick click cadence, ~4 deg aim step (multiples of 3 keep mean exact)
        model.mergeSession(player, snapshotOf(5.0, 0.6, 42, 4.0, 42));
        model.mergeSession(player, snapshotOf(5.1, 0.6, 42, 4.1, 42));

        Map<String, RunningMoments> rolled = model.baselineSnapshot(player);
        assertEquals(84, rolled.get(BehavioralFeatureExtractor.CLICK_INTERVAL_TICKS).count());
        assertEquals(5.05, rolled.get(BehavioralFeatureExtractor.CLICK_INTERVAL_TICKS).mean(), 1e-6);

        // Consistent new session stays low z-score
        AccountBaselineModel.ShiftAssessment clean = model.assess(player, snapshotOf(5.05, 0.5, 30, 4.05, 30));
        assertFalse(clean.coldStart());
        assertTrue(clean.rmsZScore() < 1.5, "Expected low z-score for consistent play, got " + clean.rmsZScore());

        // Abrupt macro/autoclicker shift (1.0 tick click interval, 18 deg snap aim)
        AccountBaselineModel.ShiftAssessment shifted = model.assess(player, snapshotOf(1.0, 0.05, 30, 18.0, 30));
        assertFalse(shifted.coldStart());
        assertTrue(shifted.rmsZScore() > 10.0, "Expected strong z-score shift, got " + shifted.rmsZScore());
        assertFalse(shifted.topFeature().isEmpty());
    }

    @Test
    void forgetRemovesAllBaselineState() {
        AccountBaselineModel model = new AccountBaselineModel(10, 5);
        UUID player = UUID.randomUUID();
        model.mergeSession(player, snapshotOf(4.0, 0.5, 25, 3.0, 25));

        assertTrue(model.forget(player));
        assertFalse(model.forget(player));
        assertTrue(model.baselineSnapshot(player).isEmpty());
        assertTrue(model.assess(player, snapshotOf(1.0, 0.1, 15, 10.0, 15)).coldStart());
    }

    private static BehavioralFeatureExtractor.Snapshot snapshotOf(
            double clickMean, double clickSpread, int clickN, double aimMean, int aimN) {
        RunningMoments clicks = new RunningMoments();
        for (int i = 0; i < clickN; i++) {
            double offset = ((i % 3) - 1) * clickSpread;
            clicks.add(Math.max(1.0, clickMean + offset));
        }
        RunningMoments aim = new RunningMoments();
        for (int i = 0; i < aimN; i++) {
            double offset = ((i % 3) - 1) * 0.5;
            aim.add(Math.max(0.1, aimMean + offset));
        }
        return new BehavioralFeatureExtractor.Snapshot(Map.of(
                BehavioralFeatureExtractor.CLICK_INTERVAL_TICKS, clicks,
                BehavioralFeatureExtractor.AIM_ANGULAR_STEP_DEG, aim));
    }
}
