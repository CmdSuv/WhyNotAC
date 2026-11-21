package dev.whynotac.bench;

import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.math.vanilla.MouseTurnModel;
import dev.whynotac.math.vector.Vec3d;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Building blocks the physics and aim layers will call per tick and per hypothesis. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "-Xmx1g")
public class MathBenchmark {

    private Vec3d input;
    private Vec3d knockback;
    private float yaw;
    private double multiplier;
    private float fromYaw;
    private float toYaw;
    private RunningMoments moments;
    private double sample;

    @Setup
    public void setUp() {
        input = new Vec3d(0.98, 0, 0.98);
        knockback = new Vec3d(0.3123, 0.36, -0.2211);
        yaw = 1234.5F;
        multiplier = MouseTurnModel.multiplier(0.5, false);
        fromYaw = 12_345.678F;
        toYaw = MouseTurnModel.turnYaw(fromYaw, 17 * multiplier);
        moments = new RunningMoments();
    }

    @Benchmark
    public Vec3d inputVector() {
        yaw += 0.15F;
        return Vec3d.inputVector(input, 0.1F, yaw);
    }

    @Benchmark
    public Vec3d viewVector() {
        yaw += 0.15F;
        return Vec3d.viewVector(-12.5F, yaw);
    }

    @Benchmark
    public Vec3d knockbackQuantisation() {
        return LpVec3.quantize(knockback);
    }

    @Benchmark
    public MouseTurnModel.CountRange mouseInverse() {
        return MouseTurnModel.yawCountsFor(fromYaw, toYaw, multiplier);
    }

    @Benchmark
    public double runningMomentsAdd() {
        sample += 0.001;
        return moments.add(sample).mean();
    }
}
