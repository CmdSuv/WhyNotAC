package dev.whynotac.interop;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.logging.Logger;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** What PacketEvents does, with raw Netty: bytes written below the game's encoder, and a byte-level filter. */
final class RawEdge implements InteropProbePlugin.Edge {

    private final Logger logger;
    private final ProtocolInfo<ClientGamePacketListener> play;
    private final int motionId;

    RawEdge(Logger logger) {
        this.logger = logger;
        this.play = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(MinecraftServer.getServer().registryAccess()));
        ByteBuf probe = io.netty.buffer.Unpooled.buffer();
        play.codec().encode(probe, new ClientboundSetEntityMotionPacket(0, Vec3.ZERO));
        this.motionId = VarInt.read(probe);
        probe.release();
    }

    @Override
    public String name() {
        return "raw-netty";
    }

    private static Channel channel(Player player) {
        return ((CraftPlayer) player).getHandle().connection.connection.channel;
    }

    @Override
    public void inject(Player player, Vector v) {
        Channel channel = channel(player);
        int entityId = player.getEntityId();
        channel.eventLoop().execute(() -> {
            ByteBuf frame = channel.alloc().buffer();
            play.codec().encode(frame, new ClientboundSetEntityMotionPacket(entityId, new Vec3(v.getX(), v.getY(), v.getZ())));
            // From the encoder's context: the frame starts below the encoder, exactly like sendPacketSilently.
            channel.pipeline().context("encoder").writeAndFlush(frame);
        });
    }

    @Override
    public void cancelNextVelocity(Player player) {
        Channel channel = channel(player);
        int entityId = player.getEntityId();
        channel.eventLoop().execute(() -> channel.pipeline().addBefore("encoder", "wnprobe-cancel", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf frame) {
                    ByteBuf view = frame.duplicate();
                    if (VarInt.read(view) == motionId && VarInt.read(view) == entityId) {
                        frame.release();
                        promise.setSuccess();
                        ctx.pipeline().remove(this);
                        logger.info("[wnprobe] raw-netty cancelled a velocity packet");
                        return;
                    }
                }
                ctx.write(msg, promise);
            }
        }));
    }
}
