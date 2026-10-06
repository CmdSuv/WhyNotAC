package dev.whynotac.conformance;

import java.util.SplittableRandom;

/** Adversarial input generators: ordinary values, boundaries, huge magnitudes and IEEE special values. */
final class Inputs {

    private static final double[] SPECIAL_DOUBLES = {
        0.0, -0.0, 1.0, -1.0, 0.5, -0.5, Math.PI, -Math.PI, 2 * Math.PI, 1.0E-5, 1.0E-5F, 2.0E-4,
        Double.MIN_VALUE, -Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, -Double.MAX_VALUE,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
        Integer.MAX_VALUE, Integer.MIN_VALUE, (double) Integer.MAX_VALUE + 1, Long.MAX_VALUE, Long.MIN_VALUE,
        29_999_984.0, -29_999_984.0, 180.0, -180.0, 360.0, 540.0, 179.99999999999997,
    };

    private Inputs() {}

    static double nextDouble(SplittableRandom random, int i) {
        if (i < SPECIAL_DOUBLES.length) {
            return SPECIAL_DOUBLES[i];
        }
        return switch (i % 6) {
            case 0 -> random.nextDouble(-1.0, 1.0);
            case 1 -> random.nextDouble(-400.0, 400.0);
            case 2 -> random.nextDouble(-3.0e7, 3.0e7);
            case 3 -> Math.scalb(random.nextDouble(-1.0, 1.0), random.nextInt(-80, 80));
            case 4 -> Double.longBitsToDouble(random.nextLong()); // any bit pattern
            default -> Math.rint(random.nextDouble(-1000, 1000)) + (random.nextBoolean() ? 0.5 : 0.0); // halfway cases
        };
    }

    static float nextFloat(SplittableRandom random, int i) {
        if (i < SPECIAL_DOUBLES.length) {
            return (float) SPECIAL_DOUBLES[i];
        }
        return switch (i % 5) {
            case 0 -> (float) random.nextDouble(-90.0, 90.0);
            case 1 -> (float) random.nextDouble(-720.0, 720.0);
            case 2 -> (float) random.nextDouble(-1.0e7, 1.0e7);
            case 3 -> Float.intBitsToFloat(random.nextInt());
            default -> (float) Math.rint(random.nextDouble(-1000, 1000));
        };
    }
}
