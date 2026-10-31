package dev.whynotac.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.math.vanilla.PositionCodec;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import java.util.SplittableRandom;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic the client uses to place other entities, vs the real 1.21.11 classes, bit for bit:
 * the relative-move codec, the angle bytes and the rotation interpolation.
 */
class EntityTrackingConformanceTest {

    private static final int SAMPLES = 1_000_000;

    @Test
    void relativeMovesDecodeLikeTheClient() {
        SplittableRandom random = new SplittableRandom(4096);
        VecDeltaCodec codec = new VecDeltaCodec();
        for (int i = 0; i < SAMPLES; i++) {
            double bx = coordinate(random, i);
            double by = coordinate(random, i + 1);
            double bz = coordinate(random, i + 2);
            long xa = delta(random);
            long ya = delta(random);
            long za = delta(random);
            codec.setBase(new Vec3(bx, by, bz));
            Vec3 expected = codec.decode(xa, ya, za);
            Vec3d actual = PositionCodec.decode(new Vec3d(bx, by, bz), xa, ya, za);
            Bits.same(expected.x, actual.x(), "x of base " + bx + " + " + xa);
            Bits.same(expected.y, actual.y(), "y of base " + by + " + " + ya);
            Bits.same(expected.z, actual.z(), "z of base " + bz + " + " + za);
            // And the server side: the delta written for a target decodes back onto the grid.
            Vec3 target = new Vec3(coordinate(random, i + 3), coordinate(random, i + 4), coordinate(random, i + 5));
            assertEquals(codec.encodeX(target), PositionCodec.delta(bx, target.x), () -> "encodeX " + bx + " -> " + target.x);
            assertEquals(codec.encodeY(target), PositionCodec.delta(by, target.y));
            assertEquals(codec.encodeZ(target), PositionCodec.delta(bz, target.z));
        }
    }

    @Test
    void anAllZeroMoveReturnsTheBaseItself() {
        Vec3d base = new Vec3d(0.1, 64.000123, -7.3);
        assertEquals(base, PositionCodec.decode(base, 0, 0, 0));
        assertEquals(0.1, PositionCodec.decode(base, 0, 1, 0).x(), "a zero component keeps the base's own, off-grid value");
    }

    @Test
    void angleBytesRoundLikeTheClient() {
        for (int b = -128; b < 128; b++) {
            Bits.same(Mth.unpackDegrees((byte) b), VanillaMath.unpackDegrees((byte) b), "unpack " + b);
        }
        SplittableRandom random = new SplittableRandom(256);
        for (int i = 0; i < SAMPLES; i++) {
            float f = Inputs.nextFloat(random, i);
            assertEquals(Mth.packDegrees(f), VanillaMath.packDegrees(f), () -> "pack " + f);
        }
    }

    @Test
    void rotationInterpolationIsExact() {
        SplittableRandom random = new SplittableRandom(3);
        for (int i = 0; i < SAMPLES; i++) {
            float a = Inputs.nextFloat(random, i);
            float b = Inputs.nextFloat(random, i + 1);
            float d = (float) (1.0 / (1 + random.nextInt(10)));
            Bits.same(Mth.rotLerp(d, a, b), VanillaMath.rotLerp(d, a, b), "rotLerp f " + d + " " + a + " " + b);
            double dd = 1.0 / (1 + random.nextInt(10));
            Bits.same(Mth.rotLerp(dd, (double) a, (double) b), VanillaMath.rotLerp(dd, (double) a, (double) b), "rotLerp d " + dd + " " + a + " " + b);
        }
    }

    /** Mostly world coordinates on and off the 1/4096 grid, sometimes adversarial doubles. */
    private static double coordinate(SplittableRandom random, int i) {
        return switch (random.nextInt(4)) {
            case 0 -> random.nextInt(-30_000_000, 30_000_000) / 4096.0 * random.nextInt(1, 4097);
            case 1 -> random.nextDouble(-3.0e7, 3.0e7);
            case 2 -> random.nextInt(-1000, 1000) + random.nextDouble();
            default -> Inputs.nextDouble(random, i);
        };
    }

    private static long delta(SplittableRandom random) {
        return random.nextInt(5) == 0 ? 0 : random.nextInt(Short.MIN_VALUE, Short.MAX_VALUE + 1);
    }
}
