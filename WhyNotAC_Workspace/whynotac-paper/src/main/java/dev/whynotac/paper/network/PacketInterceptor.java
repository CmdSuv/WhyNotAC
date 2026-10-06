package dev.whynotac.paper.network;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Installs {@link WhyNotChannelHandler} into every player connection.
 *
 * <p>New connections are caught through Paper's {@link ChannelInitializeListenerHolder}, which runs
 * at the end of the server's own {@code ChannelInitializer}, after {@code packet_handler} exists and
 * before the first byte is read (auto-read is still off at that point). Connections that already
 * exist when WhyNotAC enables (late load) are injected on their own event loop.
 */
public final class PacketInterceptor {

    public static final String HANDLER_NAME = "whynotac_interceptor";
    private static final String ANCHOR = "packet_handler";
    private static final Key LISTENER_KEY = Key.key("whynotac", "interceptor");

    private final SessionRegistry registry;
    private final PacketTranslator translator = new PacketTranslator();
    private final Logger logger;

    public PacketInterceptor(SessionRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    public void install() {
        ChannelInitializeListenerHolder.addListener(LISTENER_KEY, this::inject);
        injectExisting();
    }

    /**
     * Injects connections that were created before {@link #install()}. Idempotent; call it again one
     * tick after install to catch connections that were still in the server's pending queue.
     */
    public void injectExisting() {
        for (Connection connection : existingConnections()) {
            Channel channel = connection.channel;
            if (channel != null && channel.isOpen()) {
                channel.eventLoop().execute(() -> inject(channel));
            }
        }
    }

    public void uninstall() {
        ChannelInitializeListenerHolder.removeListener(LISTENER_KEY);
        for (Connection connection : existingConnections()) {
            Channel channel = connection.channel;
            if (channel != null) {
                channel.eventLoop().execute(() -> {
                    ChannelPipeline pipeline = channel.pipeline();
                    if (pipeline.get(HANDLER_NAME) != null) {
                        pipeline.remove(HANDLER_NAME); // handlerRemoved() closes the session cleanly
                    }
                });
            }
        }
    }

    private void inject(Channel channel) {
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null || pipeline.get(ANCHOR) == null) {
                return;
            }
            SocketAddress address = channel.remoteAddress();
            PlayerSession session = registry.open(address == null ? "unknown" : address.toString());
            pipeline.addBefore(ANCHOR, HANDLER_NAME, new WhyNotChannelHandler(session, translator, logger));
        } catch (RuntimeException e) {
            // Fail open: an injection failure leaves the connection exactly as vanilla built it.
            logger.log(Level.WARNING, "WhyNotAC could not attach to " + channel, e);
        }
    }

    /** Links a joined player to the session on their connection and returns it. */
    public Optional<PlayerSession> bind(Player player) {
        Optional<WhyNotChannelHandler> handler = handler(player);
        handler.ifPresent(h -> {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            h.selfEntityId(handle.getId());
            h.session().bind(player.getUniqueId(), player.getName(), handle.getId(), System.nanoTime());
        });
        return handler.map(WhyNotChannelHandler::session);
    }

    public Optional<PlayerSession> session(Player player) {
        return handler(player).map(WhyNotChannelHandler::session);
    }

    /** Sends one stand-alone transaction (used every server tick for continuous latency tracking). */
    public void sendTransaction(Player player) {
        Optional<WhyNotChannelHandler> handler = handler(player);
        if (handler.isEmpty()) {
            return;
        }
        OptionalInt id = handler.get().session().allocateTransaction();
        if (id.isPresent()) {
            // Through the game's own send path: correct protocol state, Paper's flush handling; the
            // handler records the exact moment it reaches the pipeline.
            ((CraftPlayer) player).getHandle().connection.send(new ClientboundPingPacket(id.getAsInt()));
        }
    }

    private static Optional<WhyNotChannelHandler> handler(Player player) {
        if (!(player instanceof CraftPlayer craft) || craft.getHandle().connection == null) {
            return Optional.empty();
        }
        Channel channel = craft.getHandle().connection.connection.channel;
        if (channel == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((WhyNotChannelHandler) channel.pipeline().get(HANDLER_NAME));
    }

    private static List<Connection> existingConnections() {
        List<Connection> connections = MinecraftServer.getServer().getConnection().getConnections();
        synchronized (connections) {
            return new ArrayList<>(connections);
        }
    }
}
