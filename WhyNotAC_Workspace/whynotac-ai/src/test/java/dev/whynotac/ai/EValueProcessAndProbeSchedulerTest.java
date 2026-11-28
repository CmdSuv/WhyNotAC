package dev.whynotac.ai;

import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EValueProcessAndProbeSchedulerTest {

    @Test
    void villesInequalityHoldsUnderNullHypothesisInMonteCarlo() {
        double alpha = 0.01;
        double p0 = 0.05;
        int trials = 2000;
        int horizon = 200;
        SplittableRandom rng = new SplittableRandom(0xBEEF_1234L);

        int rejections = 0;
        for (int t = 0; t < trials; t++) {
            EValueProcess ep = new EValueProcess(alpha);
            for (int step = 0; step < horizon; step++) {
                double outcome = rng.nextDouble() < p0 ? 1.0 : 0.0;
                ep.observe(outcome, p0, 8.0);
                if (ep.rejectedNull()) {
                    rejections++;
                    break;
                }
            }
        }
        double empiricalRate = (double) rejections / trials;
        assertTrue(empiricalRate <= alpha + 0.01,
                "Ville's inequality violated: empirical false rejection rate=" + empiricalRate + " alpha=" + alpha);
    }

    @Test
    void martingaleQuicklyRejectsStrongAlternative() {
        EValueProcess ep = new EValueProcess(0.001);
        SplittableRandom rng = new SplittableRandom(0xCAFE_99L);
        double p1 = 0.60;
        double p0 = 0.05;
        for (int step = 0; step < 60 && !ep.rejectedNull(); step++) {
            ep.observe(rng.nextDouble() < p1 ? 1.0 : 0.0, p0, 1.0);
        }
        assertTrue(ep.rejectedNull());
        assertTrue(ep.wealth() >= 1000.0);
        assertTrue(ep.observedMean() > 0.3);

        ep.reset();
        assertEquals(1.0, ep.wealth());
        assertEquals(0L, ep.observations());
        assertFalse(ep.rejectedNull());
    }

    @Test
    void rejectsInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new EValueProcess(0.0));
        assertThrows(IllegalArgumentException.class, () -> new EValueProcess(1.0));
        EValueProcess ep = new EValueProcess(0.05);
        assertThrows(IllegalArgumentException.class, () -> ep.observe(1.5, 0.1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> ep.observe(0.0, 0.0, 1.0));
    }

    @Test
    void probeSchedulerHonorsCombatCooldownJitterAndForget() {
        ProbeScheduler scheduler = new ProbeScheduler(100, 50, 0.01, 42L);
        UUID player = UUID.randomUUID();

        // Out of combat -> never schedules
        assertNull(scheduler.maybeSchedule(player, 1000L, false));

        // First combat tick starts initial cooldown [100..150]
        assertNull(scheduler.maybeSchedule(player, 100L, true));
        assertNull(scheduler.maybeSchedule(player, 199L, true));

        // Once tick >= 250, guaranteed past initial cooldown + max jitter
        ProbeScheduler.ProbePlan plan = scheduler.maybeSchedule(player, 260L, true);
        assertNotNull(plan);
        assertEquals(260L, plan.scheduledAtTick());
        assertTrue(plan.distanceBlocks() >= 2.05 && plan.distanceBlocks() <= 2.75,
                "Decoy distance must stay inside vanilla 3.0 reach: " + plan.distanceBlocks());
        assertTrue(Math.abs(plan.yawOffsetDeg()) >= 130.0f,
                "Decoy yaw offset must stay behind honest camera FOV: " + plan.yawOffsetDeg());
        assertTrue(plan.effectiveTtlTicks(10L) >= 4L);

        // Immediately afterwards, in cooldown again
        assertNull(scheduler.maybeSchedule(player, 270L, true));

        // Record probe hits until null rejected
        for (int i = 0; i < 5; i++) {
            scheduler.recordOutcome(player, 1.0, 0.05);
        }
        assertNotNull(scheduler.processFor(player));
        assertTrue(scheduler.processFor(player).wealth() > 1.0);

        scheduler.forget(player);
        assertNull(scheduler.processFor(player));
    }
}
