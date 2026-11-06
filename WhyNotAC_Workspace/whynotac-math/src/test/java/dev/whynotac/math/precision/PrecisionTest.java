package dev.whynotac.math.precision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Every precision claim is checked against exact {@link BigDecimal} arithmetic, not against another double. */
class PrecisionTest {

    private static BigDecimal exact(double d) {
        return new BigDecimal(d);
    }

    @Test
    void ulpDistanceCountsRepresentableSteps() {
        assertEquals(0, Ulp.distance(0.0F, -0.0F));
        assertEquals(1, Ulp.distance(1.0F, Math.nextUp(1.0F)));
        assertEquals(2, Ulp.distance(Float.MIN_VALUE, -Float.MIN_VALUE));
        assertEquals(Long.MAX_VALUE, Ulp.distance(Float.NaN, 1.0F));
        assertEquals(1, Ulp.distance(1.0, Math.nextDown(1.0)));
        assertEquals(Long.MAX_VALUE, Ulp.distance(-Double.MAX_VALUE, Double.MAX_VALUE));
    }

    @Test
    void floatResolutionExplainsTheRotationInformationLimit() {
        assertTrue(Ulp.floatResolution(8.0F) > 1.0E-7 && Ulp.floatResolution(8.0F) < 1.0E-6);
        assertTrue(Ulp.floatResolution(Math.nextDown(16.0F)) < 1.0E-6 && Ulp.floatResolution(16.0F) > 1.0E-6);
        assertTrue(Ulp.floatResolution(10_000.0F) > 9.0E-4);
        // World-border coordinates in double still resolve far below the 1e-6 grid.
        assertTrue(Ulp.doubleResolution(29_999_984.0) < 4.0E-9);
    }

    @Test
    void toleranceCombinesAbsoluteAndUlpBounds() {
        assertTrue(Tolerance.MICRO.matches(1.0, 1.0 + 9.0E-7));
        assertFalse(Tolerance.MICRO.matches(1.0, 1.0 + 1.1E-6));
        assertTrue(Tolerance.ulps(1).matches(10_000.0F, Math.nextUp(10_000.0F)));
        assertFalse(Tolerance.EXACT.matches(Double.NaN, Double.NaN));
        assertTrue(Tolerance.EXACT.matches(0.0, -0.0));
    }

    @Test
    void exactAngleDeltaHasNoRoundingError() {
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 200_000; i++) {
            float from = (float) random.nextDouble(-1.0e7, 1.0e7);
            float to = i % 2 == 0 ? (float) random.nextDouble(-1.0e7, 1.0e7) : from + (float) random.nextDouble(-0.5, 0.5);
            BigDecimal truth = exact(to).subtract(exact(from));
            assertEquals(0, truth.compareTo(exact(ExactAngles.delta(from, to))), () -> from + " -> " + to);
            assertTrue(ExactAngles.isDeltaExact(from, to));
            double wrapped = ExactAngles.wrappedDelta(from, to);
            assertTrue(wrapped >= -180.0 && wrapped < 180.0);
            // wrapped == truth - 360k for some integer k, exactly
            BigDecimal turns = truth.subtract(exact(wrapped)).divide(BigDecimal.valueOf(360));
            assertEquals(0, turns.stripTrailingZeros().scale() <= 0 ? 0 : 1, () -> "not a whole number of turns: " + turns);
        }
    }

    @Test
    void floatSubtractionWouldHaveLostPrecision() {
        float from = 12_345.678F;
        float to = 0.123_456_7F;
        double exactDelta = ExactAngles.delta(from, to);
        double floatDelta = to - from;
        assertTrue(exactDelta != floatDelta, "this pair should expose float rounding");
    }

    @Test
    void twoSumAndTwoProductAreErrorFree() {
        SplittableRandom random = new SplittableRandom(99);
        for (int i = 0; i < 100_000; i++) {
            double a = random.nextDouble(-1.0, 1.0) * Math.pow(2, random.nextInt(-60, 60));
            double b = random.nextDouble(-1.0, 1.0) * Math.pow(2, random.nextInt(-60, 60));
            ErrorFree.Pair sum = ErrorFree.twoSum(a, b);
            assertEquals(0, exact(a).add(exact(b)).compareTo(exact(sum.hi()).add(exact(sum.lo()))));
            ErrorFree.Pair product = ErrorFree.twoProduct(a, b);
            assertEquals(0, exact(a).multiply(exact(b)).compareTo(exact(product.hi()).add(exact(product.lo()))));
        }
    }

    @Test
    void compensatedSumSurvivesTheClassicCancellationTrap() {
        // 1e16 + 1 + 1 + ... (10 000 ones) - 1e16: naive summation returns 0.
        CompensatedSum sum = new CompensatedSum().add(1.0E16);
        double naive = 1.0E16;
        for (int i = 0; i < 10_000; i++) {
            sum.add(1.0);
            naive += 1.0;
        }
        sum.add(-1.0E16);
        naive -= 1.0E16;
        assertEquals(0.0, naive);
        assertEquals(10_000.0, sum.sum());
    }

    @Test
    void compensatedSumTracksExactSumOfManySmallDeltas() {
        SplittableRandom random = new SplittableRandom(3);
        CompensatedSum sum = new CompensatedSum();
        BigDecimal truth = BigDecimal.ZERO;
        for (int i = 0; i < 300_000; i++) {
            double delta = random.nextDouble(-1.0E-3, 1.0E-3) + (i % 1000 == 0 ? 1.0E6 : 0.0);
            sum.add(delta);
            truth = truth.add(exact(delta));
        }
        double error = exact(sum.sum()).subtract(truth).abs().doubleValue();
        assertTrue(error <= Math.ulp(truth.doubleValue()), "compensated error " + error);
    }

    @Test
    void addProductCompensatesProductRounding() {
        SplittableRandom random = new SplittableRandom(11);
        CompensatedSum sum = new CompensatedSum();
        BigDecimal truth = BigDecimal.ZERO;
        for (int i = 0; i < 50_000; i++) {
            double a = random.nextDouble(-2.0, 2.0);
            double b = random.nextDouble(-2.0, 2.0);
            sum.addProduct(a, b);
            truth = truth.add(exact(a).multiply(exact(b)), MathContext.UNLIMITED);
        }
        double error = exact(sum.sum()).subtract(truth).abs().doubleValue();
        assertTrue(error <= 2 * Math.ulp(truth.doubleValue()), "dot error " + error);
    }
}
