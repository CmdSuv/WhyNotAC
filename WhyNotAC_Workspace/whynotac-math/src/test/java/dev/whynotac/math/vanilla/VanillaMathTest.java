package dev.whynotac.math.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VanillaMathTest {

    /**
     * The client builds its table with {@code Math.sin}, which may differ from {@code StrictMath.sin}
     * by an ulp depending on JVM and CPU. Prove that this can never change a float entry: every value
     * lies more than 2 double ulps away from the nearest float rounding boundary, while
     * {@code Math.sin} is specified to be within 1 ulp of the exact result (and so is
     * {@code StrictMath.sin}), so any compliant implementation rounds to the same float.
     */
    @Test
    void sineTableIsIdenticalOnEveryCompliantJvm() {
        double minMargin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < VanillaMath.SIN_TABLE_SIZE; i++) {
            double angle = i / VanillaMath.SIN_SCALE;
            double strict = StrictMath.sin(angle);
            float entry = VanillaMath.sinTableEntry(i);
            assertEquals((float) strict, entry, "entry " + i);
            assertEquals((float) Math.sin(angle), entry, "Math.sin disagrees at " + i);
            if (strict == 0.0) {
                continue; // index 0: sin(0) = 0 exactly
            }
            double lowerBoundary = (entry + (double) Math.nextDown(entry)) / 2.0;
            double upperBoundary = (entry + (double) Math.nextUp(entry)) / 2.0;
            double margin = Math.min(strict - lowerBoundary, upperBoundary - strict) / Math.ulp(strict);
            minMargin = Math.min(minMargin, margin);
        }
        assertTrue(minMargin > 2.0, "closest table value to a float rounding boundary: " + minMargin + " ulps");
    }

    @Test
    void lookupTruncatesTowardZeroThenMasks() {
        // (long) truncates toward zero, so a small negative angle reads index 0, not 65535.
        assertEquals(0, VanillaMath.sinIndex(-1.0E-9));
        assertEquals(65535, VanillaMath.sinIndex(-1.0 / VanillaMath.SIN_SCALE));
        assertEquals(16384, VanillaMath.cosIndex(0.0));
        assertEquals(1.0F, VanillaMath.cos(0.0));
        assertEquals(0.0F, VanillaMath.sin(0.0));
        assertEquals(VanillaMath.sin(Math.PI / 2), VanillaMath.cos(0.0));
        // Eight full turns wrap through the mask (the scaled angle may truncate one slot differently).
        int direct = VanillaMath.sinIndex(0.25);
        int wrapped = VanillaMath.sinIndex(0.25 + 2 * Math.PI * 8);
        assertTrue(Math.abs(direct - wrapped) <= 1, direct + " vs " + wrapped);
    }

    @Test
    void floorAndCeilMatchVanillaEdgeCases() {
        assertEquals(-1, VanillaMath.floor(-0.5));
        assertEquals(-1, VanillaMath.floor(-1.0));
        assertEquals(0, VanillaMath.floor(-0.0));
        assertEquals(2, VanillaMath.ceil(1.0000001));
        assertEquals(Integer.MAX_VALUE, VanillaMath.floor(1.0E20)); // saturating (int) cast, as in vanilla
        assertEquals(-3L, VanillaMath.lfloor(-2.5));
        assertEquals(0.5, VanillaMath.frac(-1.5));
        assertEquals(3L, VanillaMath.ceilLong(2.0000000001));
    }

    @Test
    void wrapDegreesMapsIntoHalfOpenInterval() {
        assertEquals(-180.0F, VanillaMath.wrapDegrees(180.0F));
        assertEquals(-180.0F, VanillaMath.wrapDegrees(-180.0F));
        assertEquals(-180.0F, VanillaMath.wrapDegrees(540.0F));
        assertEquals(179.0F, VanillaMath.wrapDegrees(-181.0F));
        assertEquals(0.0, VanillaMath.wrapDegrees(720.0));
        assertEquals(20.0F, VanillaMath.degreesDifference(350.0F, 360.0F + 10.0F));
        assertEquals(-20.0F, VanillaMath.degreesDifference(10.0F, 350.0F));
    }

    @Test
    void clampPropagatesNaNLikeVanilla() {
        assertTrue(Double.isNaN(VanillaMath.clamp(Double.NaN, 0.0, 1.0)));
        assertEquals(0.0, VanillaMath.clamp(-5.0, 0.0, 1.0));
        assertEquals(1.0F, VanillaMath.clamp(5.0F, 0.0F, 1.0F));
    }

    @Test
    void doubleEqualUsesTheWidenedFloatEpsilon() {
        double floatEpsilon = 1.0E-5F; // 9.99999974737875E-6, not 1e-5
        assertTrue(VanillaMath.equal(0.0, Math.nextDown(floatEpsilon)));
        assertFalse(VanillaMath.equal(0.0, floatEpsilon));
        assertFalse(VanillaMath.equal(0.0, 1.0E-5));
    }
}
