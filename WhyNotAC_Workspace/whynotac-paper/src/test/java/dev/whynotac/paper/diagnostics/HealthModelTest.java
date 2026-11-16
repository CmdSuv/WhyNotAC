package dev.whynotac.paper.diagnostics;

import dev.whynotac.paper.diagnostics.HealthModel.Evaluation;
import dev.whynotac.paper.diagnostics.HealthModel.Inputs;
import dev.whynotac.paper.diagnostics.HealthModel.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HealthModelTest {

    @Test
    void classifiesGreenDegradedAndCriticalStatesAccurately() {
        Evaluation green = HealthModel.evaluate(new Inputs(
                100, 524_288, 0, 0, 0, 0, false, false, false, 450.0, true));
        assertEquals(Status.GREEN, green.status());
        assertTrue(green.reasons().isEmpty());
        assertEquals("{\"status\":\"GREEN\",\"reasons\":[]}", green.toJson());

        Evaluation degraded = HealthModel.evaluate(new Inputs(
                300_000, 524_288, 0, 0, 0, 12, false, false, false, 6_200.0, true));
        assertEquals(Status.DEGRADED, degraded.status());
        assertEquals(3, degraded.reasons().size());
        assertTrue(degraded.reasons().stream().anyMatch(r -> r.contains("sustained transaction tick wall")));

        Evaluation critical = HealthModel.evaluate(new Inputs(
                10, 524_288, 2, 0, 0, 0, true, false, false, 100.0, false));
        assertEquals(Status.CRITICAL, critical.status());
        assertFalse(critical.reasons().isEmpty());
        assertTrue(critical.reasons().stream().anyMatch(r -> r.contains("circuit breaker")));
        assertTrue(critical.reasons().stream().anyMatch(r -> r.contains("lost timeline events")));
        assertTrue(critical.reasons().stream().anyMatch(r -> r.contains("audit ledger")));
    }

    @Test
    void tickCostRecentWallDecaysAfterTransientWarmupSpike() {
        TickCost cost = new TickCost();
        long[] spike = cost.begin();
        spike[0] -= 12_000_000L; // 12 ms transient warmup/GC spike
        cost.end(spike);

        assertTrue(cost.maxWallMicros() >= 12_000.0);
        Evaluation duringSpike = HealthModel.evaluate(new Inputs(
                10, 524_288, 0, 0, 0, 0, false, false, false, cost.recentWallMicros(), true));
        assertEquals(Status.DEGRADED, duringSpike.status());

        for (int i = 0; i < 60; i++) {
            cost.end(cost.begin());
        }

        assertTrue(cost.maxWallMicros() >= 12_000.0);
        assertTrue(cost.recentWallMicros() < 5_000.0, "expected EWMA to decay, got " + cost.recentWallMicros());
        Evaluation recovered = HealthModel.evaluate(new Inputs(
                10, 524_288, 0, 0, 0, 0, false, false, false, cost.recentWallMicros(), true));
        assertEquals(Status.GREEN, recovered.status());
    }
}
