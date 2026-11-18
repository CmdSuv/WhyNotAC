package dev.whynotac.ai;

/**
 * Sequential nonnegative martingale e-process for randomized design experiments ({@code EvidenceClass.DESIGN_STATISTIC})
 * (AI-04).
 *
 * <p>For binary outcome {@code outcome in {0, 1}} with known baseline success rate {@code nullProbability in (0, 1)}
 * by experimental design, updates wealth {@code E_t = E_{t-1} * (1 + lambda * (outcome - nullProbability))}.
 * Provided {@code |lambda|} satisfies nonnegativity bounds ({@code 1 - lambda * nullProbability >= 0} and
 * {@code 1 + lambda * (1 - nullProbability) >= 0}), {@code {E_t}} is a test martingale under H0 with
 * {@code P_H0(sup_t E_t >= 1 / alpha) <= alpha} by Ville's inequality at any optional stopping time.
 */
public final class EValueProcess {

    private final double alphaThreshold;
    private double wealth = 1.0;
    private long observations;
    private double hits;
    private double lastLambda;

    public EValueProcess(double alpha) {
        if (!Double.isFinite(alpha) || alpha <= 0.0 || alpha >= 1.0) {
            throw new IllegalArgumentException("alpha must be in (0, 1)");
        }
        this.alphaThreshold = 1.0 / alpha;
    }

    /**
     * Updates the sequential test martingale using a predictable plug-in Kelly/GRO betting fraction
     * {@code lambda_t} computed strictly from prior observations {@code F_{t-1}}, preserving Ville's
     * inequality {@code E_H0[E_t | F_{t-1}] <= E_{t-1}} at every optional stopping time.
     */
    public synchronized double observeAdaptive(double outcome01, double nullMean) {
        if (outcome01 < 0.0 || outcome01 > 1.0 || nullMean <= 0.0 || nullMean >= 1.0) {
            throw new IllegalArgumentException("Invalid outcome/nullMean bounds");
        }
        double priorAltMean = (hits + 0.5) / (observations + 1.0);
        double kellyLambda = priorAltMean > nullMean
                ? (priorAltMean - nullMean) / (nullMean * (1.0 - nullMean))
                : 0.5;
        double boundedLambda = Math.max(0.5, Math.min(0.95 / nullMean, kellyLambda));
        return observe(outcome01, nullMean, boundedLambda);
    }

    /** Updates the sequential test martingale with one randomized experiment outcome in [0, 1]. */
    public synchronized double observe(double outcome01, double nullMean, double betFraction) {
        if (outcome01 < 0.0 || outcome01 > 1.0 || nullMean <= 0.0 || nullMean >= 1.0) {
            throw new IllegalArgumentException("Invalid outcome/nullMean bounds");
        }
        // Clamp lambda into valid martingale domain: [-1 / (1 - p0), 1 / p0] * safetyMargin
        double maxPosLambda = 0.99 / nullMean;
        double minNegLambda = -0.99 / (1.0 - nullMean);
        double lambda = Math.max(minNegLambda, Math.min(maxPosLambda, betFraction));
        lastLambda = lambda;

        double factor = 1.0 + lambda * (outcome01 - nullMean);
        if (factor < 0.0) {
            factor = 0.0;
        }
        wealth *= factor;
        if (!Double.isFinite(wealth)) {
            wealth = Double.MAX_VALUE;
        }
        observations++;
        hits += outcome01;
        return wealth;
    }

    public synchronized double wealth() {
        return wealth;
    }

    public double threshold() {
        return alphaThreshold;
    }

    public synchronized boolean rejectedNull() {
        return wealth >= alphaThreshold;
    }

    public synchronized long observations() {
        return observations;
    }

    public synchronized double observedMean() {
        return observations == 0 ? 0.0 : hits / observations;
    }

    public synchronized double lastLambda() {
        return lastLambda;
    }

    public synchronized void reset() {
        wealth = 1.0;
        observations = 0L;
        hits = 0.0;
        lastLambda = 0.0;
    }
}
