package dev.whynotac.paper.wire;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
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
 * What the network edge adds per packet, on top of {@code PipelineBenchmark}: decoding the frame with
 * the game's codec, translating it, recording it, and reconciling it with the game's view.
 * {@code outboundUnmodelled} is the common case (chunks, entity moves): only the packet id is read.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "-Xmx1g")
public class EdgeBenchmark {

    private WireObserver observer;
    private ByteBuf moveFrame;
    private ServerboundPacket move;
    private ByteBuf motionFrame;
    private ClientboundPacket motion;
    private ByteBuf chunkFrame;
    private ClientboundPacket chunk;
    private long nanoTime;

    @Setup
    public void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        SessionRegistry registry = new SessionRegistry(Runnable::run, SessionRegistry.DEFAULT_MAILBOX_CAPACITY, (s, e, t) -> { });
        registry.addListener((s, e) -> { });
        PlayerSession session = registry.open("bench");
        PacketTranslator translator = new PacketTranslator();
        observer = new WireObserver(session, translator, new WireProtocols(() -> registries), Logger.getAnonymousLogger());
        observer.startInPlay(0);

        var serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), () -> true);
        var clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
        ServerboundMovePlayerPacket nmsMove = new ServerboundMovePlayerPacket.PosRot(102.25, 64.0, -33.5, 1234.5F, -12.25F, true, false);
        moveFrame = Unpooled.buffer();
        serverbound.codec().encode(moveFrame, nmsMove);
        move = translator.inbound(nmsMove);
        ClientboundSetEntityMotionPacket nmsMotion = new ClientboundSetEntityMotionPacket(42, new Vec3(0.3, 0.36, -0.2));
        motionFrame = Unpooled.buffer();
        clientbound.codec().encode(motionFrame, nmsMotion);
        motion = translator.outbound(nmsMotion);
        // A chunk-sized frame of an unmodelled type: id of level_chunk_with_light plus 8 KiB of body.
        int[] chunkId = {-1};
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (type.id().getPath().equals("level_chunk_with_light")) {
                chunkId[0] = id;
            }
        });
        chunkFrame = Unpooled.buffer();
        VarInt.write(chunkFrame, chunkId[0]);
        chunkFrame.writeZero(8192);
        chunk = new ClientboundPacket.Unmodeled("minecraft:level_chunk_with_light");
    }

    @Benchmark
    public void inboundMove() {
        long now = nanoTime++;
        observer.onWireInbound(moveFrame, now);
        observer.onGameInbound("minecraft:move_player_pos_rot", move, now);
    }

    @Benchmark
    public void outboundMotion() {
        long now = nanoTime++;
        observer.beginGameWrite(List.of("minecraft:set_entity_motion"), List.of(motion), now);
        observer.commitOutbound(observer.beforeWireOutbound(motionFrame, now), now);
    }

    @Benchmark
    public void outboundUnmodelled() {
        long now = nanoTime++;
        observer.beginGameWrite(List.of("minecraft:level_chunk_with_light"), List.of(chunk), now);
        observer.commitOutbound(observer.beforeWireOutbound(chunkFrame, now), now);
    }
}
