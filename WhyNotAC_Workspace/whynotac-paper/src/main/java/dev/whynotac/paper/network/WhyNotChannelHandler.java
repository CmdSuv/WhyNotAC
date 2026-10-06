package dev.whynotac.paper.network;

import dev.whynotac.protocol.latency.TransactionPolicy;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;

/**
 * Per-connection Netty handler, installed directly in front of the game's {@code packet_handler}.
 *
 * <p>At this position inbound messages are fully decoded {@link Packet} objects (after decryption,
 * decompression and framing), and every outbound packet the server writes passes here before the
 * {@code unbundler} and {@code encoder}. Inbound and outbound traffic of a connection run on its
 * single event-loop thread, so the timestamps taken here are a consistent per-player clock.
 *
 * <h2>Fail-open</h2>
 * Phase 1 is a passive observer. Whatever happens inside WhyNotAC, every packet is forwarded
 * unchanged and no exception ever reaches Netty: a bug in an anti-cheat must never kick a player.
 */
final class WhyNotChannelHandler extends ChannelDuplexHandler {

    private final PlayerSession session;
    private final PacketTranslator translator;
    private final Logger logger;
    private volatile int selfEntityId = -1;
    private int failures;

    WhyNotChannelHandler(PlayerSession session, PacketTranslator translator, Logger logger) {
        this.session = session;
        this.translator = translator;
        this.logger = logger;
    }

    PlayerSession session() {
        return session;
    }

    void selfEntityId(int entityId) {
        this.selfEntityId = entityId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        long now = System.nanoTime();
        if (msg instanceof Packet<?> packet) {
            try {
                session.onInbound(translator.inbound(packet), now);
            } catch (RuntimeException | LinkageError e) {
                report("inbound " + packet.getClass().getName(), e);
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof Packet<?> packet)) {
            super.write(ctx, msg, promise);
            return;
        }
        long now = System.nanoTime();
        List<ClientboundPacket> observed = new ArrayList<>(1);
        boolean sandwich = false;
        try {
            translateOutbound(packet, observed);
            for (ClientboundPacket translated : observed) {
                sandwich |= TransactionPolicy.requiresSandwich(translated, selfEntityId);
            }
        } catch (RuntimeException | LinkageError e) {
            report("outbound " + packet.getClass().getName(), e);
            observed.clear();
            sandwich = false;
        }
        // The timeline must list packets in wire order. With a sandwich that is: transaction, the
        // packet(s), transaction. The client acknowledges the first one before applying the packet and
        // the second one after, which bounds the exact client tick in which the change took effect.
        if (sandwich) {
            writeTransaction(ctx, now);
        }
        for (ClientboundPacket translated : observed) {
            session.onOutbound(translated, now);
        }
        super.write(ctx, msg, promise);
        if (sandwich) {
            writeTransaction(ctx, System.nanoTime());
        }
    }

    /** Translates an outbound packet, flattening bundles, without recording anything yet. */
    private void translateOutbound(Packet<?> packet, List<ClientboundPacket> out) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                translateOutbound(sub, out);
            }
            return;
        }
        ClientboundPacket translated = translator.outbound(packet);
        if (translated instanceof ClientboundPacket.Login login) {
            selfEntityId = login.entityId();
        }
        out.add(translated);
    }

    /**
     * Writes a transaction ping towards the network, skipping handlers behind this one (including
     * this handler's own {@code write}), and records it as sent.
     */
    private void writeTransaction(ChannelHandlerContext ctx, long now) {
        OptionalInt id = session.allocateTransaction();
        if (id.isEmpty()) {
            return;
        }
        session.onOutbound(new ClientboundPacket.Ping(id.getAsInt()), now);
        // A real promise, not voidPromise(): other plugins' handlers may attach listeners to it.
        ctx.write(new ClientboundPingPacket(id.getAsInt()), ctx.newPromise());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        session.disconnect(System.nanoTime());
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        session.disconnect(System.nanoTime());
    }

    private void report(String what, Throwable error) {
        // Event-loop confined; rate-limited so a systematic bug cannot flood the console.
        failures++;
        if (failures <= 5 || Integer.bitCount(failures) == 1) {
            logger.log(Level.WARNING, "WhyNotAC could not observe " + what + " on " + session + " (failure #" + failures
                    + "); the packet was forwarded unchanged", error);
        }
    }
}
