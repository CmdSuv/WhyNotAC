package dev.whynotac.paper.network;

import dev.whynotac.paper.wire.WireObserver;
import dev.whynotac.paper.wire.WireTaps;
import dev.whynotac.protocol.latency.TransactionPolicy;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
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
 * <h2>Two views</h2>
 * When the connection's edge view is active ({@link WireObserver#edgeView()}), the timeline is
 * recorded at the network edge and this handler only reconciles the game's packets with it, so that
 * packets other plugins inject or cancel in between become explicit divergences. Otherwise (a client on
 * another protocol version, or a connection attached mid-session) this handler records the timeline.
 * Either way it brackets the game's physics-changing packets with transactions.
 *
 * <h2>Fail-open</h2>
 * Phase 1 is a passive observer. Whatever happens inside WhyNotAC, every packet is forwarded
 * unchanged and no exception ever reaches Netty: a bug in an anti-cheat must never kick a player.
 */
final class WhyNotChannelHandler extends ChannelDuplexHandler {

    private final PlayerSession session;
    private final PacketTranslator translator;
    private final WireObserver observer;
    private final Logger logger;
    private volatile int selfEntityId = -1;
    private int failures;
    /** WhyNotAC removes this handler itself (uninstall, or a server-list ping): the session ends. */
    private volatile boolean detached;

    WhyNotChannelHandler(PlayerSession session, PacketTranslator translator, WireObserver observer, Logger logger) {
        this.session = session;
        this.translator = translator;
        this.observer = observer;
        this.logger = logger;
    }

    WireObserver observer() {
        return observer;
    }

    /** Marks the coming removal as WhyNotAC's own (the session then ends with it). */
    void detach() {
        detached = true;
        observer.detaching();
    }

    PlayerSession session() {
        return session;
    }

    void selfEntityId(int entityId) {
        this.selfEntityId = entityId;
        observer.selfEntityId(entityId);
    }

    /** WhyNotAC was disabled or reloaded after this connection got its handlers: leave cleanly. */
    private boolean leaveIfInactive(ChannelHandlerContext ctx) {
        if (observer.active()) {
            return false;
        }
        detach();
        WireTaps.remove(ctx.pipeline());
        ctx.pipeline().remove(this);
        return true;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (leaveIfInactive(ctx)) {
            ctx.fireChannelRead(msg);
            return;
        }
        long now = System.nanoTime();
        if (msg instanceof Packet<?> packet) {
            ServerboundPacket translated;
            try {
                translated = translator.inbound(packet);
            } catch (RuntimeException | LinkageError e) {
                report("inbound " + packet.getClass().getName(), e);
                translated = new ServerboundPacket.Untranslatable(PacketTranslator.typeName(packet)); // a marker, never a silent hole
            }
            try {
                if (observer.edgeView()) {
                    observer.onGameInbound(PacketTranslator.typeName(packet), translated, now);
                } else if (!observer.recordedByEdge(PacketTranslator.typeName(packet), translated, now)) {
                    session.onInbound(translated, now);
                }
            } catch (RuntimeException | LinkageError e) {
                report("inbound session " + packet.getClass().getName(), e);
            }
            if (translated instanceof ServerboundPacket.Handshake handshake && handshake.intent() == ServerboundPacket.Handshake.Intent.STATUS) {
                // A server-list ping never becomes a player: stop observing it (handlerRemoved closes the session).
                super.channelRead(ctx, msg);
                detached = true;
                ctx.pipeline().remove(this);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (leaveIfInactive(ctx) || !(msg instanceof Packet<?> packet)) {
            super.write(ctx, msg, promise);
            return;
        }
        long now = System.nanoTime();
        if (!(packet instanceof ClientboundBundlePacket)) {
            writeOne(ctx, packet, promise, now); // the common case, without any per-write collections
            return;
        }
        List<ClientboundPacket> observed = new ArrayList<>(1);
        List<String> types = new ArrayList<>(1);
        boolean sandwich = false;
        try {
            translateOutbound(packet, observed, types);
            for (ClientboundPacket translated : observed) {
                sandwich |= TransactionPolicy.requiresSandwich(translated, selfEntityId);
            }
        } catch (RuntimeException | LinkageError e) {
            report("outbound " + packet.getClass().getName(), e);
            // Unknown effect on the client: record a marker in its place and bracket it with transactions.
            observed.clear();
            types.clear();
            observed.add(new ClientboundPacket.Untranslatable(PacketTranslator.typeName(packet)));
            types.add(PacketTranslator.typeName(packet));
            sandwich = true;
        }
        // The timeline must list packets in wire order. With a sandwich that is: transaction, the
        // packet(s), transaction. The client acknowledges the first one before applying the packet and
        // the second one after, which bounds the exact client tick in which the change took effect.
        if (sandwich) {
            writeTransaction(ctx, now);
        }
        if (!observer.edgeView()) {
            for (ClientboundPacket translated : observed) {
                session.onOutbound(translated, now);
            }
            super.write(ctx, msg, promise);
        } else {
            // The edge records it as the frame passes and reconciles it with what the game announced here.
            beginGameWrite(types, observed, now);
            super.write(ctx, msg, promise);
        }
        if (sandwich) {
            writeTransaction(ctx, System.nanoTime());
        }
    }

    /** {@link #write} for one packet that is not a bundle: the same steps, nothing allocated but the record. */
    private void writeOne(ChannelHandlerContext ctx, Packet<?> packet, ChannelPromise promise, long now) throws Exception {
        String type = PacketTranslator.typeName(packet);
        ClientboundPacket translated;
        boolean sandwich;
        try {
            translated = translator.outbound(packet);
            if (translated instanceof ClientboundPacket.Login login) {
                selfEntityId = login.entityId();
            }
            sandwich = TransactionPolicy.requiresSandwich(translated, selfEntityId);
        } catch (RuntimeException | LinkageError e) {
            report("outbound " + packet.getClass().getName(), e);
            translated = new ClientboundPacket.Untranslatable(type); // unknown effect: a marker, bracketed
            sandwich = true;
        }
        if (sandwich) {
            writeTransaction(ctx, now);
        }
        if (!observer.edgeView()) {
            session.onOutbound(translated, now);
        } else {
            try {
                observer.beginGameWrite(type, translated, now);
            } catch (RuntimeException | LinkageError e) {
                report("outbound reconciliation", e);
            }
        }
        super.write(ctx, packet, promise);
        if (sandwich) {
            writeTransaction(ctx, System.nanoTime());
        }
    }

    private void beginGameWrite(List<String> types, List<ClientboundPacket> packets, long now) {
        try {
            observer.beginGameWrite(types, packets, now);
        } catch (RuntimeException | LinkageError e) {
            report("outbound reconciliation", e);
        }
    }

    /** Translates an outbound packet, flattening bundles, without recording anything yet. */
    private void translateOutbound(Packet<?> packet, List<ClientboundPacket> out, List<String> types) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                translateOutbound(sub, out, types);
            }
            return;
        }
        ClientboundPacket translated = translator.outbound(packet);
        if (translated instanceof ClientboundPacket.Login login) {
            selfEntityId = login.entityId();
        }
        out.add(translated);
        types.add(PacketTranslator.typeName(packet));
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
        ClientboundPingPacket ping = new ClientboundPingPacket(id.getAsInt());
        ClientboundPacket.Ping translated = new ClientboundPacket.Ping(id.getAsInt());
        if (!observer.edgeView()) {
            session.onOutbound(translated, now);
            // A real promise, not voidPromise(): other plugins' handlers may attach listeners to it.
            ctx.write(ping, ctx.newPromise());
            return;
        }
        beginGameWrite(List.of(PacketTranslator.typeName(ping)), List.of(translated), now);
        ctx.write(ping, ctx.newPromise());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        session.disconnect(System.nanoTime());
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        long now = System.nanoTime();
        if (detached || !ctx.channel().isActive() || !observer.edgeView()) {
            session.disconnect(now);
        } else {
            // Another plugin removed this handler; the edge keeps recording and ends the session at disconnect.
            observer.gameViewLost(now);
        }
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
