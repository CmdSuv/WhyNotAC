package dev.whynotac.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.math.vector.Vec3d;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.SplittableRandom;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * {@link LpVec3} vs the real {@code net.minecraft.network.LpVec3}: identical wire bytes, identical
 * decoded vectors. This is the knockback the client actually applies.
 */
class LpVec3ConformanceTest {

    @Test
    void identicalBytesAndDecodedVectors() {
        SplittableRandom random = new SplittableRandom(20);
        for (int i = 0; i < 1_000_000; i++) {
            double range = switch (i % 5) {
                case 0 -> 0.5;
                case 1 -> 4.0;
                case 2 -> 1.0E3;
                case 3 -> 1.0E-4;
                default -> 2.0E10; // beyond ABS_MAX: exercises clamping and the VarInt scale
            };
            Vec3d v = i < 64
                    ? new Vec3d(Inputs.nextDouble(random, i), Inputs.nextDouble(random, i + 1), Inputs.nextDouble(random, i + 2))
                    : new Vec3d(random.nextDouble(-range, range), random.nextDouble(-range, range), random.nextDouble(-range, range));

            ByteBuf buf = Unpooled.buffer();
            net.minecraft.network.LpVec3.write(buf, Bits.vanilla(v));
            byte[] vanillaBytes = ByteBufUtil.getBytes(buf);
            byte[] ours = LpVec3.encode(v);
            assertArrayEquals(vanillaBytes, ours, () -> "bytes differ for " + v);

            Vec3 decoded = net.minecraft.network.LpVec3.read(Unpooled.wrappedBuffer(vanillaBytes));
            Bits.same(decoded, LpVec3.decode(ours), "decode " + v);
            Bits.same(decoded, LpVec3.quantize(v), "quantize " + v);
            buf.release();
        }
    }
}
