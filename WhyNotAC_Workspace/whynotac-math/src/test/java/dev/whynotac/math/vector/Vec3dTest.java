package dev.whynotac.math.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.whynotac.math.vanilla.VanillaMath;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class Vec3dTest {

    @Test
    void normalizeThresholdIsTheWidenedFloatEpsilon() {
        double threshold = 1.0E-5F;
        assertSame(Vec3d.ZERO, new Vec3d(Math.nextDown(threshold), 0, 0).normalize());
        assertEquals(new Vec3d(1, 0, 0), new Vec3d(threshold, 0, 0).normalize());
    }

    @Test
    void equalityFollowsDoubleCompareLikeVanilla() {
        assertNotEquals(new Vec3d(0.0, 0.0, 0.0), new Vec3d(-0.0, 0.0, 0.0));
        assertEquals(new Vec3d(Double.NaN, 0, 0), new Vec3d(Double.NaN, 0, 0));
    }

    @Test
    void viewVectorUsesFloatProducts() {
        float pitch = 23.4F;
        float yaw = -131.7F;
        float cosYaw = VanillaMath.cos(-yaw * VanillaMath.DEG_TO_RAD);
        float sinYaw = VanillaMath.sin(-yaw * VanillaMath.DEG_TO_RAD);
        float cosPitch = VanillaMath.cos(pitch * VanillaMath.DEG_TO_RAD);
        float sinPitch = VanillaMath.sin(pitch * VanillaMath.DEG_TO_RAD);
        float xProduct = sinYaw * cosPitch; // rounded to float before widening
        assertEquals(new Vec3d(xProduct, -sinPitch, cosYaw * cosPitch), Vec3d.viewVector(pitch, yaw));
        assertEquals(new Vec3d(0.0, -0.0, 1.0), Vec3d.viewVector(0.0F, 0.0F));
    }

    @Test
    void inputVectorIgnoresTinyInputAndNormalisesOnlyAboveOne() {
        assertSame(Vec3d.ZERO, Vec3d.inputVector(new Vec3d(1.0E-4, 0, 1.0E-4), 0.1F, 0.0F));
        // Diagonal sprint input (0.98, 0, 0.98) has length^2 > 1 and is normalised before scaling.
        Vec3d diagonal = Vec3d.inputVector(new Vec3d(0.98, 0, 0.98), 0.1F, 0.0F);
        assertEquals(new Vec3d(0.98, 0, 0.98).normalize().scale(0.1F).x(), diagonal.x());
        // Straight input below length 1 is not normalised.
        Vec3d straight = Vec3d.inputVector(new Vec3d(0, 0, 0.98), 0.1F, 0.0F);
        assertEquals(0.98 * 0.1F, straight.z());
    }

    @Test
    void mutableAndImmutablePathsProduceIdenticalBits() {
        SplittableRandom random = new SplittableRandom(5);
        for (int i = 0; i < 10_000; i++) {
            Vec3d a = new Vec3d(random.nextDouble(-10, 10), random.nextDouble(-10, 10), random.nextDouble(-10, 10));
            Vec3d b = new Vec3d(random.nextDouble(-1, 1), random.nextDouble(-1, 1), random.nextDouble(-1, 1));
            double f = random.nextDouble(0.5, 1.0);
            Vec3d immutable = a.add(b).scale(f).multiply(0.91, 0.98, 0.91);
            Vec3d mutable = a.toMutable().add(b).scale(f).multiply(0.91, 0.98, 0.91).toImmutable();
            assertEquals(immutable, mutable);
            assertEquals(immutable.lengthSqr(), mutable.lengthSqr());
        }
    }
}
