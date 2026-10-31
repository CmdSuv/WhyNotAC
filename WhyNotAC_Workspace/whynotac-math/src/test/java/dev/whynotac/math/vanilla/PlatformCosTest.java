package dev.whynotac.math.vanilla;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * The transcribed intrinsics against the JVM's own {@code Math.cos}, and against values each platform gives.
 *
 * <p>Checked once over every float in {@code [-pi/2, pi/2]} (2 140 282 808 arguments): {@code X86_64} equals this
 * x86_64 HotSpot's {@code Math.cos} on all of them; {@code AARCH64} equals an aarch64 HotSpot's (Temurin 21.0.13, run
 * under QEMU) on all of them. The platforms differ: x86_64 from fdlibm on 1 397 840 arguments, aarch64 from fdlibm on
 * 239 002, the two intrinsics from each other on 1 362 054.
 */
class PlatformCosTest {

    private static long bits(double d) {
        return Double.doubleToRawLongBits(d);
    }

    @Test
    void thisJvmsCosineIsOneOfThePlatformsOnEveryPitchSampled() {
        PlatformCos current = PlatformCos.current();
        assertNotNull(current, "this JVM's Math.cos is none of the transcribed ones");
        int end = Float.floatToRawIntBits((float) PlatformCos.MAX_ARGUMENT);
        for (int b = 0; b <= end; b += 997) {
            for (int sign = 0; sign < 2; sign++) {
                float f = Float.intBitsToFloat(b | sign << 31);
                assertEquals(bits(Math.cos(f)), bits(current.cos(f)), current + " at " + f);
            }
        }
        assertEquals(bits(Math.cos((float) PlatformCos.MAX_ARGUMENT)), bits(current.cos((float) PlatformCos.MAX_ARGUMENT)));
    }

    @Test
    void eachPlatformGivesItsOwnCosine() {
        // x86_64 one ulp above at a small pitch, aarch64 one ulp above at another; most pitches all agree
        assertEquals(0x3feffffc871d5354L, bits(PlatformCos.X86_64.cos(0.0018197135F)));
        assertEquals(0x3feffffc871d5353L, bits(PlatformCos.AARCH64.cos(0.0018197135F)));
        assertEquals(0x3feffffc871d5353L, bits(PlatformCos.FDLIBM.cos(0.0018197135F)));
        assertEquals(0x3fefcb0ed30797b9L, bits(PlatformCos.X86_64.cos(0.113750875F)));
        assertEquals(0x3fefcb0ed30797baL, bits(PlatformCos.AARCH64.cos(0.113750875F)));
        assertEquals(0x3fefcb0ed30797b9L, bits(PlatformCos.FDLIBM.cos(0.113750875F)));
        for (PlatformCos p : PlatformCos.values()) {
            assertEquals(0x3fd730de64830d95L, bits(p.cos(1.2F)), p.name()); // past pi/4: aarch64's reduction and sine kernel
            assertEquals(0x3fe8799656bf1a0dL, bits(p.cos(-0.7F)), p.name());
            assertEquals(0xbe6777a5cf72ceccL, bits(p.cos((float) PlatformCos.MAX_ARGUMENT)), p.name()); // the float pitch of 90 degrees
            assertEquals(bits(1.0), bits(p.cos(0.0)), p.name());
            assertEquals(bits(1.0), bits(p.cos(-1.0E-30)), p.name());
        }
    }

    @Test
    void theProbesTellEveryPairApart() {
        PlatformCos[] all = PlatformCos.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                boolean apart = false;
                for (float probe : PlatformCos.PROBES) {
                    apart |= all[i].cos(probe) != all[j].cos(probe);
                }
                assertNotEquals(false, apart, all[i] + " and " + all[j]);
            }
        }
    }

    @Test
    void fdlibmIsStrictMath() {
        for (int k = -600; k <= 600; k++) {
            float f = k * 0.15F * (float) (Math.PI / 180.0);
            assertEquals(bits(StrictMath.cos(f)), bits(PlatformCos.FDLIBM.cos(f)));
        }
    }
}
