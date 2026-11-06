package dev.whynotac.math.vanilla;

/**
 * {@code Math.cos} as the client's JVM computes it. The game calls it where no table stands in (gliding:
 * {@code LivingEntity.updateFallFlyingMovement}), and HotSpot makes it an intrinsic per CPU: its result is within an ulp
 * of the exact cosine, as {@link StrictMath#cos} is, but not the same double. Each platform below is that intrinsic,
 * transcribed operation by operation for the arguments a pitch can give ({@code |x| <= pi/2}, {@link #MAX_ARGUMENT}):
 *
 * <ul>
 *   <li>{@link #X86_64}: {@code StubGenerator::generate_libmCos} ({@code stubGenerator_x86_64_cos.cpp}, Intel's libm:
 *       a reduction by pi/32, a 64-entry table, a polynomial and compensated sums, each SSE lane an IEEE operation).
 *       Windows, Linux and Intel macOS on x86_64.
 *   <li>{@link #AARCH64}: {@code MacroAssembler::generate_dsin_dcos} ({@code macroAssembler_aarch64_trig.cpp}): fdlibm's
 *       kernels with fused multiply-adds ({@link Math#fma}) and its own operation order. Apple silicon, Linux on ARM.
 *   <li>{@link #FDLIBM}: {@link StrictMath#cos}, what a JVM without the intrinsic computes.
 * </ul>
 *
 * Over every float pitch, {@code X86_64} and {@code FDLIBM} differ in about one in 1500 ({@code PlatformCosTest}).
 */
public enum PlatformCos {
    X86_64 {
        @Override
        public double cos(double x) {
            return x86(x);
        }
    },
    AARCH64 {
        @Override
        public double cos(double x) {
            return aarch64(x);
        }
    },
    FDLIBM {
        @Override
        public double cos(double x) {
            return StrictMath.cos(x);
        }
    };

    /** The largest argument transcribed: a pitch of 90 degrees in radians, as the game computes it. */
    public static final double MAX_ARGUMENT = 90.0F * (float) (Math.PI / 180.0);

    /** This platform's {@code Math.cos(x)}, for {@code |x| <= MAX_ARGUMENT}. */
    public abstract double cos(double x);

    /** The platform this JVM's {@code Math.cos} is, from arguments where they differ; null if it is none of them. */
    public static PlatformCos current() {
        return Current.PLATFORM;
    }

    /** Initialized on first use, after the tables. */
    private static final class Current {
        static final PlatformCos PLATFORM = detect();
    }

    private static PlatformCos detect() {
        for (PlatformCos p : values()) {
            boolean all = true;
            for (float probe : PROBES) {
                all &= Double.doubleToRawLongBits(Math.cos(probe)) == Double.doubleToRawLongBits(p.cos(probe));
            }
            if (all) {
                return p;
            }
        }
        return null;
    }

    /** Arguments where the platforms' results differ ({@code PlatformCosTest} keeps each pair apart on one). */
    static final float[] PROBES = {0.0018197135F, 0.113750875F};

    // --- x86_64 ---------------------------------------------------------------------------------------------------

    private static double bits(long b) {
        return Double.longBitsToDouble(b);
    }

    private static final double PI32INV = bits(0x40245F306DC9C883L);
    private static final double ONEHALF = bits(0x3FE0000000000000L);
    private static final long SIGN_MASK = 0x8000000000000000L;
    private static final double P_1 = bits(0x3FB921FB54400000L);
    private static final double P_2 = bits(0x3D90B4611A600000L);
    private static final double P_3 = bits(0x3B63198A2E037073L);
    private static final double SC_1_LO = bits(0xBFC5555555555555L);
    private static final double SC_1_HI = bits(0xBFE0000000000000L);
    private static final double SC_2_LO = bits(0x3F81111111111111L);
    private static final double SC_2_HI = bits(0x3FA5555555555555L);
    private static final double SC_3_LO = bits(0xBF2A01A01A01A01AL);
    private static final double SC_3_HI = bits(0xBF56C16C16C16C17L);
    private static final double SC_4_LO = bits(0x3EC71DE3A556C734L);
    private static final double SC_4_HI = bits(0x3EFA01A01A01A01AL);
    /** {@code Ctable}: for B = M pi / 32, M = 0..63, four doubles each (read as T0..T3 below). */
    private static final long[] C_TABLE = {
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0x3FF0000000000000L,
            0xBF73B92E176D6D31L, 0x3FB917A6BC29B42CL, 0xBC3E2718E0000000L, 0x3FF0000000000000L,
            0xBF93AD06011469FBL, 0x3FC8F8B83C69A60BL, 0xBC626D19C0000000L, 0x3FF0000000000000L,
            0xBFA60BEA939D225AL, 0x3FD294062ED59F06L, 0xBC75D28DA0000000L, 0x3FF0000000000000L,
            0xBFB37CA1866B95CFL, 0x3FD87DE2A6AEA963L, 0xBC672CEDE0000000L, 0x3FF0000000000000L,
            0xBFBE3A6873FA1279L, 0x3FDE2B5D3806F63BL, 0x3C5E0D8920000000L, 0x3FF0000000000000L,
            0xBFC592675BC57974L, 0x3FE1C73B39AE68C8L, 0x3C8B25DD20000000L, 0x3FF0000000000000L,
            0xBFCD0DFE53ABA2FDL, 0x3FE44CF325091DD6L, 0x3C68076A20000000L, 0x3FF0000000000000L,
            0x3FCA827999FCEF32L, 0x3FE6A09E667F3BCDL, 0xBC8BDD3420000000L, 0x3FE0000000000000L,
            0x3FC133CC94247758L, 0x3FE8BC806B151741L, 0xBC82C5E120000000L, 0x3FE0000000000000L,
            0x3FAC73B39AE68C87L, 0x3FEA9B66290EA1A3L, 0x3C39F630E0000000L, 0x3FE0000000000000L,
            0xBF9D4A2C7F909C4EL, 0x3FEC38B2F180BDB1L, 0xBC76E0B180000000L, 0x3FE0000000000000L,
            0xBFBE087565455A75L, 0x3FED906BCF328D46L, 0x3C7457E620000000L, 0x3FE0000000000000L,
            0x3FA4A03176ACF82DL, 0x3FEE9F4156C62DDAL, 0x3C8760B1E0000000L, 0x3FD0000000000000L,
            0xBFAC1D1F0E5967D5L, 0x3FEF6297CFF75CB0L, 0x3C75621720000000L, 0x3FD0000000000000L,
            0xBF9BA1650F592F50L, 0x3FEFD88DA3D12526L, 0xBC887DF640000000L, 0x3FC0000000000000L,
            0x0000000000000000L, 0x3FF0000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0x3F9BA1650F592F50L, 0x3FEFD88DA3D12526L, 0xBC887DF640000000L, 0xBFC0000000000000L,
            0x3FAC1D1F0E5967D5L, 0x3FEF6297CFF75CB0L, 0x3C75621720000000L, 0xBFD0000000000000L,
            0xBFA4A03176ACF82DL, 0x3FEE9F4156C62DDAL, 0x3C8760B1E0000000L, 0xBFD0000000000000L,
            0x3FBE087565455A75L, 0x3FED906BCF328D46L, 0x3C7457E620000000L, 0xBFE0000000000000L,
            0x3F9D4A2C7F909C4EL, 0x3FEC38B2F180BDB1L, 0xBC76E0B180000000L, 0xBFE0000000000000L,
            0xBFAC73B39AE68C87L, 0x3FEA9B66290EA1A3L, 0x3C39F630E0000000L, 0xBFE0000000000000L,
            0xBFC133CC94247758L, 0x3FE8BC806B151741L, 0xBC82C5E120000000L, 0xBFE0000000000000L,
            0xBFCA827999FCEF32L, 0x3FE6A09E667F3BCDL, 0xBC8BDD3420000000L, 0xBFE0000000000000L,
            0x3FCD0DFE53ABA2FDL, 0x3FE44CF325091DD6L, 0x3C68076A20000000L, 0xBFF0000000000000L,
            0x3FC592675BC57974L, 0x3FE1C73B39AE68C8L, 0x3C8B25DD20000000L, 0xBFF0000000000000L,
            0x3FBE3A6873FA1279L, 0x3FDE2B5D3806F63BL, 0x3C5E0D8920000000L, 0xBFF0000000000000L,
            0x3FB37CA1866B95CFL, 0x3FD87DE2A6AEA963L, 0xBC672CEDE0000000L, 0xBFF0000000000000L,
            0x3FA60BEA939D225AL, 0x3FD294062ED59F06L, 0xBC75D28DA0000000L, 0xBFF0000000000000L,
            0x3F93AD06011469FBL, 0x3FC8F8B83C69A60BL, 0xBC626D19C0000000L, 0xBFF0000000000000L,
            0x3F73B92E176D6D31L, 0x3FB917A6BC29B42CL, 0xBC3E2718E0000000L, 0xBFF0000000000000L,
            0x0000000000000000L, 0x0000000000000000L, 0x0000000000000000L, 0xBFF0000000000000L,
            0x3F73B92E176D6D31L, 0xBFB917A6BC29B42CL, 0x3C3E2718E0000000L, 0xBFF0000000000000L,
            0x3F93AD06011469FBL, 0xBFC8F8B83C69A60BL, 0x3C626D19C0000000L, 0xBFF0000000000000L,
            0x3FA60BEA939D225AL, 0xBFD294062ED59F06L, 0x3C75D28DA0000000L, 0xBFF0000000000000L,
            0x3FB37CA1866B95CFL, 0xBFD87DE2A6AEA963L, 0x3C672CEDE0000000L, 0xBFF0000000000000L,
            0x3FBE3A6873FA1279L, 0xBFDE2B5D3806F63BL, 0xBC5E0D8920000000L, 0xBFF0000000000000L,
            0x3FC592675BC57974L, 0xBFE1C73B39AE68C8L, 0xBC8B25DD20000000L, 0xBFF0000000000000L,
            0x3FCD0DFE53ABA2FDL, 0xBFE44CF325091DD6L, 0xBC68076A20000000L, 0xBFF0000000000000L,
            0xBFCA827999FCEF32L, 0xBFE6A09E667F3BCDL, 0x3C8BDD3420000000L, 0xBFE0000000000000L,
            0xBFC133CC94247758L, 0xBFE8BC806B151741L, 0x3C82C5E120000000L, 0xBFE0000000000000L,
            0xBFAC73B39AE68C87L, 0xBFEA9B66290EA1A3L, 0xBC39F630E0000000L, 0xBFE0000000000000L,
            0x3F9D4A2C7F909C4EL, 0xBFEC38B2F180BDB1L, 0x3C76E0B180000000L, 0xBFE0000000000000L,
            0x3FBE087565455A75L, 0xBFED906BCF328D46L, 0xBC7457E620000000L, 0xBFE0000000000000L,
            0xBFA4A03176ACF82DL, 0xBFEE9F4156C62DDAL, 0xBC8760B1E0000000L, 0xBFD0000000000000L,
            0x3FAC1D1F0E5967D5L, 0xBFEF6297CFF75CB0L, 0xBC75621720000000L, 0xBFD0000000000000L,
            0x3F9BA1650F592F50L, 0xBFEFD88DA3D12526L, 0x3C887DF640000000L, 0xBFC0000000000000L,
            0x0000000000000000L, 0xBFF0000000000000L, 0x0000000000000000L, 0x0000000000000000L,
            0xBF9BA1650F592F50L, 0xBFEFD88DA3D12526L, 0x3C887DF640000000L, 0x3FC0000000000000L,
            0xBFAC1D1F0E5967D5L, 0xBFEF6297CFF75CB0L, 0xBC75621720000000L, 0x3FD0000000000000L,
            0x3FA4A03176ACF82DL, 0xBFEE9F4156C62DDAL, 0xBC8760B1E0000000L, 0x3FD0000000000000L,
            0xBFBE087565455A75L, 0xBFED906BCF328D46L, 0xBC7457E620000000L, 0x3FE0000000000000L,
            0xBF9D4A2C7F909C4EL, 0xBFEC38B2F180BDB1L, 0x3C76E0B180000000L, 0x3FE0000000000000L,
            0x3FAC73B39AE68C87L, 0xBFEA9B66290EA1A3L, 0xBC39F630E0000000L, 0x3FE0000000000000L,
            0x3FC133CC94247758L, 0xBFE8BC806B151741L, 0x3C82C5E120000000L, 0x3FE0000000000000L,
            0x3FCA827999FCEF32L, 0xBFE6A09E667F3BCDL, 0x3C8BDD3420000000L, 0x3FE0000000000000L,
            0xBFCD0DFE53ABA2FDL, 0xBFE44CF325091DD6L, 0xBC68076A20000000L, 0x3FF0000000000000L,
            0xBFC592675BC57974L, 0xBFE1C73B39AE68C8L, 0xBC8B25DD20000000L, 0x3FF0000000000000L,
            0xBFBE3A6873FA1279L, 0xBFDE2B5D3806F63BL, 0xBC5E0D8920000000L, 0x3FF0000000000000L,
            0xBFB37CA1866B95CFL, 0xBFD87DE2A6AEA963L, 0x3C672CEDE0000000L, 0x3FF0000000000000L,
            0xBFA60BEA939D225AL, 0xBFD294062ED59F06L, 0x3C75D28DA0000000L, 0x3FF0000000000000L,
            0xBF93AD06011469FBL, 0xBFC8F8B83C69A60BL, 0x3C626D19C0000000L, 0x3FF0000000000000L,
            0xBF73B92E176D6D31L, 0xBFB917A6BC29B42CL, 0x3C3E2718E0000000L, 0x3FF0000000000000L,
    };

    /**
     * The stub's main path ({@code 2^-252 <= |x| < 90112}) and its small one, register by register: an SSE register
     * is two lanes ({@code lo}, {@code hi}), a packed operation the same IEEE operation on each.
     */
    private static double x86(double x) {
        long bits = Double.doubleToRawLongBits(x);
        int rax = ((int) (bits >>> 32) & 0x7FFF0000) - 0x30300000;
        if (Integer.compareUnsigned(rax, 0x10C50000) > 0) {
            if (rax > 0x10C50000) {
                throw new IllegalArgumentException("not transcribed: " + x); // the large path
            }
            return 1.0 - Math.abs(x); // |x| < 2^-252: pinsrw clears the sign, then ONE - x
        }
        double y = PI32INV * x + bits(Double.doubleToRawLongBits(ONEHALF) | bits & SIGN_MASK); // mulsd, por, addpd
        int n = (int) y; // cvttsd2si
        double fn = n; // cvtsi2sd
        double m1 = P_1 * fn;
        int t = ((n + 1865232) & 63) * 4; // + 16 (cos x = sin(x + pi/2)), mod 64
        double t0 = bits(C_TABLE[t]);
        double t1 = bits(C_TABLE[t + 1]);
        double t2 = bits(C_TABLE[t + 2]);
        double t3 = bits(C_TABLE[t + 3]);
        double m2 = P_2 * fn; // mulpd xmm2 (both lanes)
        double r1 = x - m1; // subsd xmm0; subsd xmm4
        double m3 = fn * P_3; // mulsd xmm1
        double r = r1 - m2; // subsd xmm4
        double x5lo = SC_4_LO * r1; // mulpd xmm5, xmm0 ([r1, r1])
        double x5hi = SC_4_HI * r1;
        double x7 = t1 * r; // mulsd xmm7, xmm4
        double x3 = r1 - r; // subsd xmm3, xmm4
        x5lo *= r; // mulpd xmm5, xmm0 ([r, r])
        x5hi *= r;
        double r2 = r * r; // mulpd xmm0, xmm0
        x3 -= m2; // subsd xmm3, xmm2
        double x1 = m3 - x3; // subsd xmm1, xmm3
        double x2lo = t0 + t3; // addsd xmm2, xmm3 (xmm2 = [T0, T1])
        double x2hi = t1;
        x7 -= x2lo; // subsd xmm7, xmm2
        x2lo *= r; // mulsd xmm2, xmm4
        double x6lo = SC_2_LO * r2; // mulpd xmm6, xmm0
        double x6hi = SC_2_HI * r2;
        x3 = t3 * r; // movq xmm3, T3; mulsd xmm3, xmm4
        x2lo *= r2; // mulpd xmm2, xmm0
        x2hi *= r2;
        double r4 = r2 * r2; // mulpd xmm0, xmm0
        x5lo += SC_3_LO; // addpd xmm5, SC_3
        x5hi += SC_3_HI;
        double x4 = r * t0; // mulsd xmm4, T0
        x6lo += SC_1_LO; // addpd xmm6, SC_1
        x6hi += SC_1_HI;
        x5lo *= r4; // mulpd xmm5, xmm0
        x5hi *= r4;
        double x0 = x3; // movdqu xmm0, xmm3
        x3 += t1; // addsd xmm3, T1
        x1 *= x7; // mulpd xmm1, xmm7 (low lane)
        x7 = x4; // movdqu xmm7, xmm4
        x4 += x3; // addsd xmm4, xmm3
        x6lo += x5lo; // addpd xmm6, xmm5
        x6hi += x5hi;
        double x5 = t1 - x3; // movq xmm5, T1; subsd xmm5, xmm3
        x3 -= x4; // subsd xmm3, xmm4
        x1 += t2; // addsd xmm1, T2
        x6lo *= x2lo; // mulpd xmm6, xmm2
        x6hi *= x2hi;
        x0 += x5; // addsd xmm0, xmm5
        x3 += x7; // addsd xmm3, xmm7
        x0 += x1;
        x0 += x3;
        x0 += x6lo;
        x0 += x6hi; // unpckhpd xmm6; addsd
        x0 += x4;
        return x0;
    }

    // --- aarch64 --------------------------------------------------------------------------------------------------

    private static final double PIO2_1 = bits(0x3FF921FB54400000L);
    private static final double PIO2_1T = bits(0x3DD0B4611A626331L);
    private static final double PIO2_2 = bits(0x3DD0B4611A600000L);
    private static final double PIO2_2T = bits(0x3BA3198A2E037073L);
    /** {@code StubRoutines::aarch64::_dsin_coef}: S1..S6. */
    private static final double S1 = bits(0xBFC5555555555549L);
    private static final double S2 = bits(0x3F8111111110F8A6L);
    private static final double S3 = bits(0xBF2A01A019C161D5L);
    private static final double S4 = bits(0x3EC71DE357B1FE7DL);
    private static final double S5 = bits(0xBE5AE5E68A2B9CEBL);
    private static final double S6 = bits(0x3DE5D93A5ACFD57CL);
    /** {@code StubRoutines::aarch64::_dcos_coef}: C1..C6. */
    private static final double C1 = bits(0x3FA555555555554CL);
    private static final double C2 = bits(0xBF56C16C16C15177L);
    private static final double C3 = bits(0x3EFA01A019CB1590L);
    private static final double C4 = bits(0xBE927E4F809C52ADL);
    private static final double C5 = bits(0x3E21EE9EBDB4B1C4L);
    private static final double C6 = bits(0xBDA8FAE9BE8838D4L);

    /**
     * {@code generate_dsin_dcos(isCos = true)} for {@code |x| < 3 pi / 4}: tiny, {@code |x| <= pi/4} straight into the
     * kernel, else the reduction by pi/2 (n = +-1, no fused operation there) and the sine kernel. {@code fmaddd(d, n, m,
     * a)} is {@code a + n * m}, {@code fmsubd} {@code a - n * m}, {@code fnmsub} {@code n * m - a}, each rounded once.
     */
    private static double aarch64(double x) {
        long bits = Double.doubleToRawLongBits(x);
        long absX = bits & 0x7FFFFFFFFFFFFFFFL;
        long ix = absX >>> 32;
        if (0x3e400000L > ix) {
            return 1.0; // TINY_X
        }
        if (ix <= 0x3fe921fbL) {
            return kernelCos(x, 0.0, ix); // EARLY_CASE: y1 cleared
        }
        if (ix >= 0x4002d97cL) {
            throw new IllegalArgumentException("not transcribed: " + x); // medium and large reductions
        }
        double y0;
        double y1;
        int n;
        if (bits >= 0) {
            double z = x - PIO2_1;
            n = 1;
            if (ix != 0x3ff921fbL) {
                y0 = z - PIO2_1T;
                y1 = (z - y0) - PIO2_1T;
            } else {
                z -= PIO2_2;
                y0 = z - PIO2_2T;
                y1 = (z - y0) - PIO2_2T;
            }
        } else {
            double z = x + PIO2_1;
            n = -1;
            if (ix != 0x3ff921fbL) {
                y0 = z + PIO2_1T;
                y1 = (z - y0) + PIO2_1T;
            } else {
                z += PIO2_2;
                y0 = z + PIO2_2T;
                y1 = (z - y0) + PIO2_2T;
            }
        }
        int sign = n ^ (n >>> 1); // eorw absX, n, n, LSR 1
        double v = (n & 1) != 0 ? kernelSin(y0, y1) : kernelCos(y0, y1, ix);
        return (sign & 1) != 0 ? -v : v;
    }

    /** {@code generate_kernel_cos}: {@code ix} is the high word of the stub's own argument, not of {@code x}. */
    private static double kernelCos(double x, double y, long ix) {
        double z = x * x;
        double r = Math.fma(z, C6, C5);
        r = Math.fma(z, r, C4);
        y = x * y;
        r = Math.fma(z, r, C3);
        r = Math.fma(z, r, C2);
        double zz = z * z; // the register x becomes z^2
        r = Math.fma(z, r, C1);
        if (ix <= 0x3FD33333L) {
            double v = Math.fma(-zz, r, y); // fmsubd(v0, x, r, y)
            v = Math.fma(0.5, z, v); // fmaddd(v0, half, z, v0)
            return 1.0 - v;
        }
        double qx = ix > 0x3FE90000L ? 0.28125 : bits((long) (int) (ix - 0x00200000L) << 32);
        double c = Math.fma(zz, r, -y); // fnmsub(C6, x, r, y)
        double h = Math.fma(0.5, z, -qx); // fnmsub(h, half, z, qx)
        double a = 1.0 - qx;
        c = h - c;
        return a - c;
    }

    /** {@code generate_kernel_sin(iyIsOne = true)}. */
    private static double kernelSin(double x, double y) {
        double z = x * x;
        double v = z * x;
        double r = Math.fma(z, S6, S5);
        r = Math.fma(z, r, S4);
        r = Math.fma(z, r, S3);
        r = Math.fma(z, r, S2);
        double s = 0.5 * y; // fmuld(S6, half, y)
        s = Math.fma(-v, r, s); // fmsubd(S6, v, r, S6)
        s = Math.fma(-z, s, y); // fmsubd(S6, z, S6, y)
        s = Math.fma(v, S1, s); // fmaddd(S6, v, S1, S6)
        return x + s;
    }
}
