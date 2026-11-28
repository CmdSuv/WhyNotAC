package dev.whynotac.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.math.vanilla.VanillaMath;
import java.util.SplittableRandom;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

/** {@link VanillaMath} vs the real {@code net.minecraft.util.Mth} from the 1.21.11 server, bit for bit. */
class MthConformanceTest {

    private static final int SAMPLES = 2_000_000;

    @Test
    void entireSineTableIsIdentical() {
        for (int i = 0; i < VanillaMath.SIN_TABLE_SIZE; i++) {
            double angle = i / VanillaMath.SIN_SCALE;
            Bits.same(Mth.sin(angle), VanillaMath.sin(angle), "sin index " + i);
            Bits.same(Mth.cos(angle), VanillaMath.cos(angle), "cos index " + i);
        }
    }

    @Test
    void trigonometryOnAdversarialInputs() {
        SplittableRandom random = new SplittableRandom(1211);
        for (int i = 0; i < SAMPLES; i++) {
            double x = Inputs.nextDouble(random, i);
            Bits.same(Mth.sin(x), VanillaMath.sin(x), "sin(" + x + ")");
            Bits.same(Mth.cos(x), VanillaMath.cos(x), "cos(" + x + ")");
            float f = Inputs.nextFloat(random, i);
            // The game's float call sites widen float angles: sin(yRot * DEG_TO_RAD).
            Bits.same(Mth.sin(f * Mth.DEG_TO_RAD), VanillaMath.sin(f * VanillaMath.DEG_TO_RAD), "sin(" + f + " deg)");
        }
    }

    @Test
    void roundingFunctions() {
        SplittableRandom random = new SplittableRandom(2);
        for (int i = 0; i < SAMPLES; i++) {
            double x = Inputs.nextDouble(random, i);
            float f = Inputs.nextFloat(random, i);
            assertEquals(Mth.floor(x), VanillaMath.floor(x), () -> "floor " + x);
            assertEquals(Mth.floor(f), VanillaMath.floor(f), () -> "floorf " + f);
            assertEquals(Mth.lfloor(x), VanillaMath.lfloor(x), () -> "lfloor " + x);
            assertEquals(Mth.ceil(x), VanillaMath.ceil(x), () -> "ceil " + x);
            assertEquals(Mth.ceil(f), VanillaMath.ceil(f), () -> "ceilf " + f);
            assertEquals(Mth.ceilLong(x), VanillaMath.ceilLong(x), () -> "ceilLong " + x);
            Bits.same(Mth.frac(x), VanillaMath.frac(x), "frac " + x);
            Bits.same(Mth.frac(f), VanillaMath.frac(f), "fracf " + f);
        }
    }

    @Test
    void anglesAndComparisons() {
        SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < SAMPLES; i++) {
            double x = Inputs.nextDouble(random, i);
            double y = Inputs.nextDouble(random, i + 7);
            float f = Inputs.nextFloat(random, i);
            float g = Inputs.nextFloat(random, i + 3);
            Bits.same(Mth.wrapDegrees(x), VanillaMath.wrapDegrees(x), "wrap " + x);
            Bits.same(Mth.wrapDegrees(f), VanillaMath.wrapDegrees(f), "wrapf " + f);
            Bits.same(Mth.degreesDifference(f, g), VanillaMath.degreesDifference(f, g), "diff " + f + " " + g);
            Bits.same(Mth.positiveModulo(x, 360.0), VanillaMath.positiveModulo(x, 360.0), "mod " + x);
            assertEquals(Mth.equal(x, y), VanillaMath.equal(x, y));
            assertEquals(Mth.equal(f, g), VanillaMath.equal(f, g));
            Bits.same(Mth.absMax(x, y), VanillaMath.absMax(x, y), "absMax");
        }
    }

    @Test
    void clampLerpAndLengths() {
        SplittableRandom random = new SplittableRandom(4);
        for (int i = 0; i < SAMPLES; i++) {
            double a = Inputs.nextDouble(random, i);
            double b = Inputs.nextDouble(random, i + 1);
            double c = Inputs.nextDouble(random, i + 2);
            float f = Inputs.nextFloat(random, i);
            float g = Inputs.nextFloat(random, i + 1);
            float h = Inputs.nextFloat(random, i + 2);
            Bits.same(Mth.clamp(a, b, c), VanillaMath.clamp(a, b, c), "clamp");
            Bits.same(Mth.clamp(f, g, h), VanillaMath.clamp(f, g, h), "clampf");
            Bits.same(Mth.lerp(a, b, c), VanillaMath.lerp(a, b, c), "lerp");
            Bits.same(Mth.lerp(f, g, h), VanillaMath.lerp(f, g, h), "lerpf");
            Bits.same(Mth.square(a), VanillaMath.square(a), "square");
            Bits.same(Mth.lengthSquared(a, b), VanillaMath.lengthSquared(a, b), "len2");
            Bits.same(Mth.lengthSquared(a, b, c), VanillaMath.lengthSquared(a, b, c), "len3");
            Bits.same(Mth.length(a, b, c), VanillaMath.length(a, b, c), "length");
            Bits.same(Mth.sqrt(f), VanillaMath.sqrt(f), "sqrtf");
            int ia = random.nextInt();
            int ib = random.nextInt();
            int ic = random.nextInt();
            assertEquals(Mth.clamp(ia, ib, ic), VanillaMath.clamp(ia, ib, ic));
        }
    }

    @Test
    void constants() {
        Bits.same(Mth.EPSILON, VanillaMath.EPSILON, "EPSILON");
        Bits.same(Mth.PI, VanillaMath.PI, "PI");
        Bits.same(Mth.DEG_TO_RAD, VanillaMath.DEG_TO_RAD, "DEG_TO_RAD");
        Bits.same(Mth.RAD_TO_DEG, VanillaMath.RAD_TO_DEG, "RAD_TO_DEG");
    }
}
