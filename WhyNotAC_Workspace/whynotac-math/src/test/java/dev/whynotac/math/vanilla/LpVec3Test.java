package dev.whynotac.math.vanilla;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LpVec3Test {

    @Test
    void tinyVectorsEncodeAsZero() {
        assertArrayEquals(new byte[] {0}, LpVec3.encode(new Vec3d(3.0E-5, -3.0E-5, 0.0)));
        assertEquals(Vec3d.ZERO, LpVec3.quantize(new Vec3d(1.0E-6, 2.0E-6, -3.0E-6)));
        assertEquals(Vec3d.ZERO, LpVec3.quantize(new Vec3d(Double.NaN, 0.0, 0.0)));
    }

    @Test
    void wireLayoutForAKnownVector() {
        // max component 0.4 -> scale 1; x: round((0.4*0.5+0.5)*32766) = 22936, y: 16383, z: round(0.45*32766) = 14745
        Vec3d v = new Vec3d(0.4, 0.0, -0.1);
        long expected = 1L | 22936L << 3 | 16383L << 18 | 14745L << 33;
        byte[] bytes = LpVec3.encode(v);
        assertEquals(6, bytes.length);
        assertEquals((byte) expected, bytes[0]);
        assertEquals((byte) (expected >> 8), bytes[1]);
        int high = (int) (expected >> 16);
        assertEquals((byte) (high >>> 24), bytes[2]);
        assertEquals((byte) high, bytes[5]);
    }

    @Test
    void quantizationErrorStaysWithinTheAdvertisedBound() {
        SplittableRandom random = new SplittableRandom(0x5EED_1211L);
        for (int i = 0; i < 200_000; i++) {
            double range = switch (i % 4) {
                case 0 -> 0.5;     // typical knockback
                case 1 -> 3.9;     // wind charge / mace launches
                case 2 -> 40.0;    // continuation (VarInt) path
                default -> 1.0E-3; // near the zero threshold
            };
            Vec3d v = new Vec3d(random.nextDouble(-range, range), random.nextDouble(-range, range), random.nextDouble(-range, range));
            Vec3d q = LpVec3.quantize(v);
            double bound = LpVec3.maxAxisError(v);
            if (q.equals(Vec3d.ZERO)) {
                assertTrue(v.maxAbsComponent() < LpVec3.ABS_MIN_VALUE, () -> "unexpected zero for " + v);
                continue;
            }
            assertTrue(Math.abs(q.x() - v.x()) <= bound, () -> v + " -> " + q);
            assertTrue(Math.abs(q.y() - v.y()) <= bound, () -> v + " -> " + q);
            assertTrue(Math.abs(q.z() - v.z()) <= bound, () -> v + " -> " + q);
        }
    }

    @Test
    void normalKnockbackLosesAboutThirtyTimesTheAnalysisGrid() {
        // The reason quantize() exists: the client never sees the server's exact knockback.
        Vec3d knockback = new Vec3d(0.3123456789, 0.36, -0.2210987654);
        Vec3d seen = LpVec3.quantize(knockback);
        double error = Math.max(Math.abs(seen.x() - knockback.x()), Math.abs(seen.z() - knockback.z()));
        assertTrue(error > 1.0E-6, "error " + error);
        assertTrue(error <= 1.0 / 32766.0 + 1.0E-15, "error " + error);
    }

    @Test
    void varIntRoundTrip() {
        for (int value : new int[] {0, 1, 127, 128, 255, 25565, 2097151, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
            assertEquals(value, VarInts.decode(VarInts.encode(value), 0));
        }
        assertEquals(5, VarInts.encode(-1).length);
    }
}
