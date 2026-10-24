package dev.whynotac.math.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.MouseTurnModel.CountRange;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class MouseTurnModelTest {

    @Test
    void multiplierUsesWidenedFloatLiterals() {
        // 0.6F and 0.2F are not 0.6 and 0.2: the client's 100% sensitivity is not exactly 1.0x.
        double d = 0.5 * 0.6000000238418579 + 0.20000000298023224;
        assertEquals(d * d * d * 8.0, MouseTurnModel.multiplier(0.5, false));
        assertEquals(d * d * d, MouseTurnModel.multiplier(0.5, true));
        assertTrue(MouseTurnModel.multiplier(0.5, false) != 1.0);
    }

    @Test
    void incrementCastsToFloatBeforeScaling() {
        double m = MouseTurnModel.multiplier(0.37, false);
        float expected = (float) (3 * m) * 0.15F;
        assertEquals(expected, MouseTurnModel.countIncrement(3, m));
    }

    @Test
    void pitchClampsAtNinetyDegrees() {
        double m = MouseTurnModel.multiplier(1.0, false);
        assertEquals(90.0F, MouseTurnModel.turnPitch(89.0F, 1000 * m));
        assertEquals(-90.0F, MouseTurnModel.turnPitch(-89.0F, -1000 * m));
    }

    @Test
    void inverseAlwaysContainsTheTrueCount() {
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < 50_000; i++) {
            double sensitivity = random.nextDouble();
            double m = MouseTurnModel.multiplier(sensitivity, random.nextInt(10) == 0);
            float from = (float) random.nextDouble(-50_000.0, 50_000.0);
            long counts = random.nextLong(-400, 401);
            float to = MouseTurnModel.turnYaw(from, counts * m);
            CountRange range = MouseTurnModel.yawCountsFor(from, to, m);
            assertTrue(range.min() <= counts && counts <= range.max(),
                    () -> "counts " + counts + " not in " + range + " from " + from + " m " + m);
        }
    }

    @Test
    void smallYawIsUniquelyInvertible() {
        double m = MouseTurnModel.multiplier(0.5, false);
        for (long counts = -200; counts <= 200; counts++) {
            float to = MouseTurnModel.turnYaw(12.5F, counts * m);
            CountRange range = MouseTurnModel.yawCountsFor(12.5F, to, m);
            assertTrue(range.isUnique(), "counts " + counts + " -> " + range);
            assertEquals(counts, range.min());
        }
    }

    @Test
    void hugeYawDestroysInformationAndTheInverseSaysSo() {
        // ulp(1e6f) = 0.0625 deg, the 0%-sensitivity step is ~0.0096 deg: several counts land on the same float.
        double m = MouseTurnModel.multiplier(0.0, false);
        float from = 1.0e6F;
        float to = MouseTurnModel.turnYaw(from, 3 * m);
        CountRange range = MouseTurnModel.yawCountsFor(from, to, m);
        assertTrue(range.size() > 1, "expected ambiguity, got " + range);
    }

    @Test
    void offLatticeRotationHasNoIntegerExplanation() {
        double m = MouseTurnModel.multiplier(0.5, false);
        float to = MouseTurnModel.turnYaw(0.0F, 1.5 * m); // half a cursor count: impossible with integer input
        assertTrue(MouseTurnModel.yawCountsFor(0.0F, to, m).isEmpty());
    }
}
