package dev.whynotac.conformance;

import dev.whynotac.math.vanilla.MouseTurnModel;
import dev.whynotac.math.vector.Vec3d;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.SplittableRandom;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Rotation and movement-vector math vs the real {@code Entity} methods.
 *
 * <p>{@code Entity} cannot be constructed without a world, so the test bootstraps the registries and
 * allocates an instance without running any constructor. The methods under test only read and write the rotation fields, which is
 * exactly the state we want to control.
 */
class EntityMathConformanceTest {

    private static Entity entity;
    private static Method getInputVector;

    @BeforeAll
    static void allocate() throws ReflectiveOperationException {
        // Entity's static initialiser touches the built-in registries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        entity = (Entity) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, ItemEntity.class);
        getInputVector = Entity.class.getDeclaredMethod("getInputVector", Vec3.class, float.class, float.class);
        getInputVector.setAccessible(true);
    }

    @Test
    void viewVector() {
        SplittableRandom random = new SplittableRandom(30);
        for (int i = 0; i < 1_000_000; i++) {
            float pitch = Inputs.nextFloat(random, i);
            float yaw = Inputs.nextFloat(random, i + 1);
            Bits.same(entity.calculateViewVector(pitch, yaw), Vec3d.viewVector(pitch, yaw), "view " + pitch + "," + yaw);
        }
    }

    @Test
    void inputVector() throws ReflectiveOperationException {
        SplittableRandom random = new SplittableRandom(31);
        float[] speeds = {0.1F, 0.02F, 0.026F, 0.13F, 0.6F * 0.1F, 0.3F};
        for (int i = 0; i < 300_000; i++) {
            // Realistic impulses (keys x 0.98, sneak/use-item slowdowns) and arbitrary ones.
            Vec3d input = i % 2 == 0
                    ? new Vec3d((random.nextInt(3) - 1) * 0.98F * (random.nextBoolean() ? 0.3F : 1.0F), 0.0, (random.nextInt(3) - 1) * 0.98F)
                    : new Vec3d(random.nextDouble(-2, 2), random.nextDouble(-1, 1), random.nextDouble(-2, 2));
            float speed = speeds[i % speeds.length];
            float yaw = Inputs.nextFloat(random, i);
            Vec3 expected = (Vec3) getInputVector.invoke(null, Bits.vanilla(input), speed, yaw);
            Bits.same(expected, Vec3d.inputVector(input, speed, yaw), "input " + input + " speed " + speed + " yaw " + yaw);
        }
    }

    @Test
    void mouseTurnSequencesIncludingHugeYaw() {
        SplittableRandom random = new SplittableRandom(32);
        for (int session = 0; session < 2_000; session++) {
            double sensitivity = random.nextDouble();
            boolean scoping = random.nextInt(8) == 0;
            double multiplier = MouseTurnModel.multiplier(sensitivity, scoping);
            float yaw = session % 4 == 0 ? (float) random.nextDouble(-2.0e6, 2.0e6) : (float) random.nextDouble(-360, 360);
            float pitch = (float) random.nextDouble(-90, 90);
            entity.setYRot(yaw);
            entity.setXRot(pitch);
            for (int frame = 0; frame < 500; frame++) {
                double dx = random.nextInt(-60, 61) * (random.nextInt(20) == 0 ? 0.5 : 1.0); // occasional fractional cursor deltas
                double dy = random.nextInt(-40, 41);
                entity.turn(dx * multiplier, dy * multiplier);
                yaw = MouseTurnModel.turnYaw(yaw, dx * multiplier);
                pitch = MouseTurnModel.turnPitch(pitch, dy * multiplier);
                Bits.same(entity.getYRot(), yaw, "yaw session " + session + " frame " + frame);
                Bits.same(entity.getXRot(), pitch, "pitch session " + session + " frame " + frame);
            }
        }
    }
}
