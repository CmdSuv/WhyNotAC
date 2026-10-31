package dev.whynotac.conformance;

import static org.junit.jupiter.api.Assertions.fail;

import dev.whynotac.math.vector.Vec3d;
import net.minecraft.world.phys.Vec3;

/** Bitwise assertions: "equal" here means identical IEEE-754 bit patterns, NaN payload aside. */
final class Bits {

    private Bits() {}

    static void same(float expected, float actual, Object context) {
        if (Float.floatToIntBits(expected) != Float.floatToIntBits(actual)) {
            fail("float mismatch at " + context + ": vanilla " + expected + " (0x" + Integer.toHexString(Float.floatToIntBits(expected))
                    + ") vs whynotac " + actual + " (0x" + Integer.toHexString(Float.floatToIntBits(actual)) + ")");
        }
    }

    static void same(double expected, double actual, Object context) {
        if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(actual)) {
            fail("double mismatch at " + context + ": vanilla " + expected + " vs whynotac " + actual);
        }
    }

    static void same(Vec3 expected, Vec3d actual, Object context) {
        same(expected.x, actual.x(), context + ".x");
        same(expected.y, actual.y(), context + ".y");
        same(expected.z, actual.z(), context + ".z");
    }

    static Vec3 vanilla(Vec3d v) {
        return new Vec3(v.x(), v.y(), v.z());
    }
}
