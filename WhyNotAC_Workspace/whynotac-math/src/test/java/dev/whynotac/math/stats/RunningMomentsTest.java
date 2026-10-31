package dev.whynotac.math.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class RunningMomentsTest {

    private static final MathContext MC = new MathContext(60);

    /** Reference central moments m2..m4 computed in 60-digit decimal arithmetic. */
    private static double[] exactMoments(double[] xs) {
        BigDecimal n = BigDecimal.valueOf(xs.length);
        BigDecimal sum = BigDecimal.ZERO;
        for (double x : xs) {
            sum = sum.add(new BigDecimal(x));
        }
        BigDecimal mean = sum.divide(n, MC);
        BigDecimal m2 = BigDecimal.ZERO;
        BigDecimal m3 = BigDecimal.ZERO;
        BigDecimal m4 = BigDecimal.ZERO;
        for (double x : xs) {
            BigDecimal d = new BigDecimal(x).subtract(mean, MC);
            BigDecimal d2 = d.multiply(d, MC);
            m2 = m2.add(d2, MC);
            m3 = m3.add(d2.multiply(d, MC), MC);
            m4 = m4.add(d2.multiply(d2, MC), MC);
        }
        double variance = m2.divide(n, MC).doubleValue();
        double skew = Math.sqrt(xs.length) * m3.doubleValue() / Math.pow(m2.doubleValue(), 1.5);
        double kurt = xs.length * m4.doubleValue() / (m2.doubleValue() * m2.doubleValue()) - 3.0;
        return new double[] {mean.doubleValue(), variance, skew, kurt};
    }

    private static void assertRelative(double expected, double actual, double relative, String what) {
        double scale = Math.max(Math.abs(expected), 1.0E-300);
        assertTrue(Math.abs(actual - expected) / scale <= relative, what + ": expected " + expected + " got " + actual);
    }

    @Test
    void matchesExactMomentsOnSkewedData() {
        SplittableRandom random = new SplittableRandom(1);
        double[] xs = new double[20_000];
        RunningMoments moments = new RunningMoments();
        for (int i = 0; i < xs.length; i++) {
            xs[i] = -Math.log(1.0 - random.nextDouble()) * 0.05; // exponential: skew 2, excess kurtosis 6
            moments.add(xs[i]);
        }
        double[] truth = exactMoments(xs);
        assertRelative(truth[0], moments.mean(), 1.0E-12, "mean");
        assertRelative(truth[1], moments.populationVariance(), 1.0E-11, "variance");
        assertRelative(truth[2], moments.skewness(), 1.0E-9, "skewness");
        assertRelative(truth[3], moments.excessKurtosis(), 1.0E-9, "kurtosis");
    }

    @Test
    void keepsPrecisionWhereTheTextbookFormulaCollapses() {
        // Mean 1e7 (a world coordinate), spread 1e-6: E[x^2]-E[x]^2 has no correct digits left here.
        SplittableRandom random = new SplittableRandom(2);
        double[] xs = new double[50_000];
        RunningMoments moments = new RunningMoments();
        double sum = 0;
        double sumSq = 0;
        for (int i = 0; i < xs.length; i++) {
            xs[i] = 1.0E7 + random.nextDouble(-1.0E-6, 1.0E-6);
            moments.add(xs[i]);
            sum += xs[i];
            sumSq += xs[i] * xs[i];
        }
        double naiveVariance = sumSq / xs.length - (sum / xs.length) * (sum / xs.length);
        double[] truth = exactMoments(xs);
        assertRelative(truth[1], moments.populationVariance(), 1.0E-9, "shifted welford variance");
        assertRelative(truth[0], moments.mean(), 1.0E-15, "mean");
        assertTrue(Math.abs(naiveVariance - truth[1]) / truth[1] > 1.0, "naive formula unexpectedly survived: " + naiveVariance);
    }

    @Test
    void mergeEqualsSequentialAccumulation() {
        SplittableRandom random = new SplittableRandom(3);
        RunningMoments all = new RunningMoments();
        RunningMoments left = new RunningMoments();
        RunningMoments right = new RunningMoments();
        for (int i = 0; i < 30_000; i++) {
            double x = random.nextDouble() < 0.1 ? random.nextDouble(100, 200) : random.nextDouble(-1, 1);
            all.add(x);
            (i < 7_000 ? left : right).add(x);
        }
        RunningMoments merged = left.copy().merge(right);
        assertEquals(all.count(), merged.count());
        assertRelative(all.mean(), merged.mean(), 1.0E-12, "mean");
        assertRelative(all.populationVariance(), merged.populationVariance(), 1.0E-12, "variance");
        assertRelative(all.skewness(), merged.skewness(), 1.0E-10, "skewness");
        assertRelative(all.excessKurtosis(), merged.excessKurtosis(), 1.0E-10, "kurtosis");
        assertEquals(all.min(), merged.min());
        assertEquals(all.max(), merged.max());
    }

    @Test
    void mergeAcrossDifferentShiftsKeepsPrecision() {
        SplittableRandom random = new SplittableRandom(4);
        double[] xs = new double[40_000];
        RunningMoments left = new RunningMoments();
        RunningMoments right = new RunningMoments();
        for (int i = 0; i < xs.length; i++) {
            xs[i] = (i < 20_000 ? 1.0E7 : 1.0E7 + 3.0E-6) + random.nextDouble(-1.0E-6, 1.0E-6);
            (i < 20_000 ? left : right).add(xs[i]);
        }
        double[] truth = exactMoments(xs);
        RunningMoments merged = left.copy().merge(right);
        assertRelative(truth[1], merged.populationVariance(), 1.0E-9, "merged variance");
        assertRelative(truth[2], merged.skewness(), 1.0E-6, "merged skewness");
    }

    @Test
    void undefinedStatisticsAreNaN() {
        RunningMoments moments = new RunningMoments();
        assertTrue(Double.isNaN(moments.mean()));
        moments.add(5.0).add(5.0);
        assertEquals(0.0, moments.populationVariance());
        assertTrue(Double.isNaN(moments.skewness()));
    }
}
