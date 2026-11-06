package dev.whynotac.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.math.vector.Vec3d;
import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** {@link Vec3d} vs the real {@code net.minecraft.world.phys.Vec3}, bit for bit. */
class Vec3ConformanceTest {

    private static final int SAMPLES = 500_000;

    private static Vec3d next(SplittableRandom random, int i) {
        return new Vec3d(Inputs.nextDouble(random, i), Inputs.nextDouble(random, i + 11), Inputs.nextDouble(random, i + 23));
    }

    @Test
    void arithmetic() {
        SplittableRandom random = new SplittableRandom(10);
        for (int i = 0; i < SAMPLES; i++) {
            Vec3d a = next(random, i);
            Vec3d b = next(random, i + 5);
            double s = Inputs.nextDouble(random, i + 2);
            Vec3 va = Bits.vanilla(a);
            Vec3 vb = Bits.vanilla(b);
            Bits.same(va.add(vb), a.add(b), "add");
            Bits.same(va.subtract(vb), a.subtract(b), "subtract");
            Bits.same(va.vectorTo(vb), a.vectorTo(b), "vectorTo");
            Bits.same(va.scale(s), a.scale(s), "scale");
            Bits.same(va.multiply(vb), a.multiply(b), "multiply");
            Bits.same(va.reverse(), a.reverse(), "reverse");
            Bits.same(va.cross(vb), a.cross(b), "cross");
            Bits.same(va.normalize(), a.normalize(), "normalize " + a);
            Bits.same(va.horizontal(), a.horizontal(), "horizontal");
            Bits.same(va.lerp(vb, s), a.lerp(b, s), "lerp");
            Bits.same(va.dot(vb), a.dot(b), "dot");
            Bits.same(va.length(), a.length(), "length");
            Bits.same(va.lengthSqr(), a.lengthSqr(), "lengthSqr");
            Bits.same(va.horizontalDistance(), a.horizontalDistance(), "hdist");
            Bits.same(va.horizontalDistanceSqr(), a.horizontalDistanceSqr(), "hdist2");
            Bits.same(va.distanceTo(vb), a.distanceTo(b), "distanceTo");
            Bits.same(va.distanceToSqr(vb), a.distanceToSqr(b), "distanceToSqr");
            assertEquals(va.isFinite(), a.isFinite());
            assertEquals(va.equals(vb), a.equals(b));
        }
    }

    @Test
    void rotations() {
        SplittableRandom random = new SplittableRandom(11);
        for (int i = 0; i < SAMPLES; i++) {
            Vec3d a = next(random, i);
            float angle = Inputs.nextFloat(random, i);
            Vec3 va = Bits.vanilla(a);
            Bits.same(va.xRot(angle), a.xRot(angle), "xRot");
            Bits.same(va.yRot(angle), a.yRot(angle), "yRot");
            Bits.same(va.zRot(angle), a.zRot(angle), "zRot");
            float pitch = Inputs.nextFloat(random, i + 1);
            float yaw = Inputs.nextFloat(random, i + 2);
            Bits.same(Vec3.directionFromRotation(pitch, yaw), Vec3d.directionFromRotation(pitch, yaw), "direction " + pitch + "," + yaw);
        }
    }
}
