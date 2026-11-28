package dev.whynotac.math.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RunningCovarianceMatrixTest {

    @Test
    void mergeMatchesSinglePassAndComputesTikhonovMahalanobisDistance() {
        RunningCovarianceMatrix full = new RunningCovarianceMatrix(3);
        RunningCovarianceMatrix a = new RunningCovarianceMatrix(3);
        RunningCovarianceMatrix b = new RunningCovarianceMatrix(3);

        double[][] samples = {
                {4.0, 2.0, 0.4},
                {5.0, 2.5, 0.5},
                {4.5, 2.2, 0.45},
                {5.5, 2.8, 0.55},
                {4.2, 2.1, 0.42},
                {5.2, 2.6, 0.52}
        };
        for (int i = 0; i < samples.length; i++) {
            full.add(samples[i]);
            (i < 3 ? a : b).add(samples[i]);
        }
        RunningCovarianceMatrix merged = a.copy().merge(b);
        assertEquals(full.count(), merged.count());
        for (int i = 0; i < 3; i++) {
            assertEquals(full.mean()[i], merged.mean()[i], 1.0e-12);
            for (int j = 0; j < 3; j++) {
                assertEquals(full.sampleCovariance()[i][j], merged.sampleCovariance()[i][j], 1.0e-12);
            }
        }

        double nearDist = merged.mahalanobisDistance(new double[] {4.8, 2.4, 0.48}, 1.0e-3);
        double correlatedAnomaly = merged.mahalanobisDistance(new double[] {4.8, 2.4, 2.0}, 1.0e-3);
        assertTrue(nearDist < 1.0, "In-distribution point should have small D_M: " + nearDist);
        assertTrue(correlatedAnomaly > 10.0, "Off-manifold anomaly should have large D_M: " + correlatedAnomaly);
    }
}
