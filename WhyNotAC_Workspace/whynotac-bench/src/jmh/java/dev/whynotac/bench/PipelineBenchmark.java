package dev.whynotac.bench;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
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

/**
 * What one packet costs on the network thread: NMS-to-model translation, and the session's stamping,
 * sequencing and enqueueing. {@code ingestEndToEnd} is the complete per-packet work WhyNotAC adds to
 * a Netty event loop (listeners run inline here, with a no-op listener, so dispatch is included too).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "-Xmx1g")
public class PipelineBenchmark {

    private PacketTranslator translator;
    private ServerboundMovePlayerPacket nmsMove;
    private ClientboundSetEntityMotionPacket nmsMotion;
    private ServerboundPacket.MovePlayer modelMove;
    private PlayerSession session;
    private long nanoTime;

    @Setup
    public void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        translator = new PacketTranslator();
        nmsMove = new ServerboundMovePlayerPacket.PosRot(102.25, 64.0, -33.5, 1234.5F, -12.25F, true, false);
        nmsMotion = new ClientboundSetEntityMotionPacket(42, new Vec3(0.3, 0.36, -0.2));
        modelMove = (ServerboundPacket.MovePlayer) translator.inbound(nmsMove);
        SessionRegistry registry = new SessionRegistry(Runnable::run, SessionRegistry.DEFAULT_MAILBOX_CAPACITY, (s, e, t) -> { });
        registry.addListener((s, e) -> { });
        session = registry.open("bench");
    }

    @Benchmark
    public Object translateInboundMove() {
        return translator.inbound(nmsMove);
    }

    @Benchmark
    public Object translateOutboundMotion() {
        return translator.outbound(nmsMotion);
    }

    @Benchmark
    public void ingestModelPacket() {
        session.onInbound(modelMove, nanoTime++);
    }

    @Benchmark
    public void ingestEndToEnd() {
        session.onInbound(translator.inbound(nmsMove), nanoTime++);
    }
}
