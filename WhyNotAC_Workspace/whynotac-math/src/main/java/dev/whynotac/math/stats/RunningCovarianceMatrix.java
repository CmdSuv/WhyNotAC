package dev.whynotac.math.stats;

import java.util.Arrays;

/**
 * Single-pass, numerically stable multivariate mean and sample covariance accumulator (Welford/Chan)
 * with exact parallel {@link #merge(RunningCovarianceMatrix)} and Tikhonov-regularized Mahalanobis
 * distance {@code sqrt((x - mu)^T (Sigma + lambda I)^(-1) (x - mu))}.
 *
 * <p>Used for correlated behavioral feature tuples where individual marginal Z-scores appear mild while
 * their joint combination is statistically anomalous. Mutable and not thread-safe.
 */
public final class RunningCovarianceMatrix {

    private final int dim;
    private long count;
    private final double[] mean;
    private final double[][] coMoments;

    public RunningCovarianceMatrix(int dim) {
        if (dim <= 0) {
            throw new IllegalArgumentException("dim must be positive: " + dim);
        }
        this.dim = dim;
        this.mean = new double[dim];
        this.coMoments = new double[dim][dim];
    }

    public int dim() {
        return dim;
    }

    public long count() {
        return count;
    }

    /** Incorporates one observation vector of length {@link #dim()}. */
    public RunningCovarianceMatrix add(double... x) {
        if (x == null || x.length != dim) {
            throw new IllegalArgumentException("Expected vector length " + dim);
        }
        for (double v : x) {
            if (!Double.isFinite(v)) {
                return this;
            }
        }
        double[] dx = new double[dim];
        for (int i = 0; i < dim; i++) {
            dx[i] = x[i] - mean[i];
        }
        count++;
        double invN = 1.0 / count;
        for (int i = 0; i < dim; i++) {
            mean[i] += dx[i] * invN;
        }
        for (int i = 0; i < dim; i++) {
            double d2 = x[i] - mean[i];
            for (int j = i; j < dim; j++) {
                coMoments[i][j] += d2 * dx[j];
                coMoments[j][i] = coMoments[i][j];
            }
        }
        return this;
    }

    /** Exact Chan et al. multivariate merge of {@code other} into this accumulator. */
    public RunningCovarianceMatrix merge(RunningCovarianceMatrix other) {
        if (other == null || other.dim != dim) {
            throw new IllegalArgumentException("Dimension mismatch for covariance merge");
        }
        if (other.count == 0) {
            return this;
        }
        if (this.count == 0) {
            this.count = other.count;
            System.arraycopy(other.mean, 0, this.mean, 0, dim);
            for (int i = 0; i < dim; i++) {
                System.arraycopy(other.coMoments[i], 0, this.coMoments[i], 0, dim);
            }
            return this;
        }
        long na = this.count;
        long nb = other.count;
        long n = na + nb;
        double[] delta = new double[dim];
        for (int i = 0; i < dim; i++) {
            delta[i] = other.mean[i] - this.mean[i];
        }
        double scale = ((double) na * (double) nb) / n;
        for (int i = 0; i < dim; i++) {
            for (int j = i; j < dim; j++) {
                double combined = coMoments[i][j] + other.coMoments[i][j] + delta[i] * delta[j] * scale;
                coMoments[i][j] = combined;
                coMoments[j][i] = combined;
            }
        }
        double fracB = (double) nb / n;
        for (int i = 0; i < dim; i++) {
            mean[i] += delta[i] * fracB;
        }
        this.count = n;
        return this;
    }

    public RunningCovarianceMatrix copy() {
        RunningCovarianceMatrix copy = new RunningCovarianceMatrix(dim);
        copy.count = this.count;
        System.arraycopy(this.mean, 0, copy.mean, 0, dim);
        for (int i = 0; i < dim; i++) {
            System.arraycopy(this.coMoments[i], 0, copy.coMoments[i], 0, dim);
        }
        return copy;
    }

    public double[] mean() {
        return Arrays.copyOf(mean, dim);
    }

    /** Returns {@code (coMoments[i][j] / (count - 1))}, or zero matrix if {@code count < 2}. */
    public double[][] sampleCovariance() {
        double[][] cov = new double[dim][dim];
        if (count < 2) {
            return cov;
        }
        double denom = count - 1.0;
        for (int i = 0; i < dim; i++) {
            for (int j = 0; j < dim; j++) {
                cov[i][j] = coMoments[i][j] / denom;
            }
        }
        return cov;
    }

    /**
     * Computes Tikhonov-regularized Mahalanobis distance
     * {@code sqrt((x - mean)^T (Sigma + regLambda * I)^(-1) (x - mean))} via Cholesky decomposition.
     */
    public double mahalanobisDistance(double[] x, double regLambda) {
        if (x == null || x.length != dim || count < 2) {
            return Double.NaN;
        }
        double lambda = Math.max(1.0e-6, regLambda);
        double[][] a = sampleCovariance();
        double[] diff = new double[dim];
        for (int i = 0; i < dim; i++) {
            if (!Double.isFinite(x[i])) {
                return Double.NaN;
            }
            diff[i] = x[i] - mean[i];
            a[i][i] += lambda;
        }

        // Cholesky factorization A = L * L^T
        double[][] l = new double[dim][dim];
        for (int i = 0; i < dim; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (!(sum > 0.0) || !Double.isFinite(sum)) {
                        return Double.NaN;
                    }
                    l[i][j] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }

        // Forward solve L * y = diff -> D_M = ||y||_2
        double[] y = new double[dim];
        double dSq = 0.0;
        for (int i = 0; i < dim; i++) {
            double sum = diff[i];
            for (int k = 0; k < i; k++) {
                sum -= l[i][k] * y[k];
            }
            y[i] = sum / l[i][i];
            dSq += y[i] * y[i];
        }
        return Double.isFinite(dSq) ? Math.sqrt(Math.max(0.0, dSq)) : Double.NaN;
    }
}
