package dev.whynotac.paper.network;

import dev.whynotac.paper.identity.ClientFacts;
import dev.whynotac.paper.wire.WireObserver;
import dev.whynotac.paper.wire.WireProtocols;
import dev.whynotac.paper.wire.WireTaps;
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
 * Installs {@link WhyNotChannelHandler} into every player connection, and the edge taps
 * ({@link WireTaps}) into every connection created after WhyNotAC enabled.
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
    private final WireProtocols protocols = new WireProtocols(() -> MinecraftServer.getServer().registryAccess());
    private final Logger logger;
    /** Off after uninstall: the registered listener then does nothing (it is never removed, see install). */
    private volatile boolean enabled;
    private volatile ClientFacts.Policy identityPolicy;
    private final boolean edgeEnabled;

    public PacketInterceptor(SessionRegistry registry, Logger logger) {
        this(registry, logger, true);
    }

    /** @param edgeEnabled false: no edge taps, every connection uses the game's view (declared per session) */
    public PacketInterceptor(SessionRegistry registry, Logger logger, boolean edgeEnabled) {
        this.registry = registry;
        this.logger = logger;
        this.edgeEnabled = edgeEnabled;
    }

    /**
     * Paper keeps channel listeners in a plain {@code LinkedHashMap} that event loops iterate inside
     * {@code initChannel} while plugins add and remove entries from the main thread. Removing an entry
     * is a structural change: racing with a connection being accepted, it throws
     * {@code ConcurrentModificationException} inside {@code initChannel} and that player's connection
     * is closed. So the listener is registered under a fixed key and never removed. Registering it
     * again (re-enable) only replaces the value, which is not structural, and uninstall switches it
     * off with a flag.
     */
    public void install() {
        identityPolicy = ClientFacts.policy();
        if (!identityPolicy.authenticated()) {
            logger.warning("WhyNotAC: player identities on this server are not authenticated (" + identityPolicy.detail()
                    + "). Evidence stays session-scoped and nothing is ever acted on per account automatically.");
        }
        enabled = true;
        ChannelInitializeListenerHolder.addListener(LISTENER_KEY, channel -> {
            if (enabled) {
                inject(channel, true);
            }
        });
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
                channel.eventLoop().execute(() -> inject(channel, false));
            }
        }
    }

    public void uninstall() {
        enabled = false; // never removeListener: see install()
        for (Connection connection : existingConnections()) {
            Channel channel = connection.channel;
            if (channel != null) {
                channel.eventLoop().execute(() -> {
                    ChannelPipeline pipeline = channel.pipeline();
                    if (pipeline.get(HANDLER_NAME) instanceof WhyNotChannelHandler handler) {
                        handler.detach();
                    }
                    WireTaps.remove(pipeline);
                    if (pipeline.get(HANDLER_NAME) != null) {
                        pipeline.remove(HANDLER_NAME); // handlerRemoved() closes the session cleanly
                    }
                });
            }
        }
    }

    /**
     * {@code fresh}: the connection was just created and nothing was read yet, so the edge can follow
     * it from the first byte. A connection that existed before WhyNotAC enabled gets the game's view.
     */
    private void inject(Channel channel, boolean fresh) {
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) instanceof WhyNotChannelHandler || pipeline.get(ANCHOR) == null) {
                return;
            }
            // Handlers left by an earlier WhyNotAC instance (a reload) are not ours to keep.
            for (String name : List.of(WireTaps.INBOUND, WireTaps.OUTBOUND, HANDLER_NAME)) {
                if (pipeline.get(name) != null) {
                    pipeline.remove(name);
                }
            }
            SocketAddress address = channel.remoteAddress();
            PlayerSession session = registry.open(address == null ? "unknown" : address.toString());
            WireObserver observer = new WireObserver(session, translator, protocols, logger, upstreamTranslation(channel));
            observer.activeWhile(() -> enabled);
            pipeline.addBefore(ANCHOR, HANDLER_NAME, new WhyNotChannelHandler(session, translator, observer, logger));
            boolean edge = false;
            if (edgeEnabled && fresh && pipeline.get("splitter") != null && pipeline.get("prepender") != null) {
                try {
                    WireTaps.install(pipeline, observer, logger);
                    edge = true;
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "WhyNotAC could not attach its edge taps to " + channel + "; using the game's view", e);
                    WireTaps.remove(pipeline);
                }
            }
            if (!edge) {
                observer.useGameView(!edgeEnabled ? "edge view disabled in config.yml (observation.edge)"
                        : fresh ? "edge taps unavailable" : "attached mid-session", System.nanoTime());
            }
        } catch (RuntimeException e) {
            // Fail open: an injection failure leaves the connection exactly as vanilla built it.
            logger.log(Level.WARNING, "WhyNotAC could not attach to " + channel, e);
        }
    }

    /** Why this connection's bytes are not the client's own, or null for a direct connection. */
    private String upstreamTranslation(Channel channel) {
        if (channel instanceof io.netty.channel.local.LocalChannel) {
            return "Geyser (in-process Bedrock translation)";
        }
        ClientFacts.Policy policy = identityPolicy;
        if (policy != null && (policy.topology().equals("VELOCITY_MODERN") || policy.topology().equals("BUNGEE_LEGACY"))) {
            return "the " + policy.topology() + " proxy (it may translate other client versions)";
        }
        return null;
    }

    /** Links a joined player to the session on their connection and returns it. */
    public Optional<PlayerSession> bind(Player player) {
        Optional<WhyNotChannelHandler> handler = handler(player);
        handler.ifPresent(h -> {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            h.selfEntityId(handle.getId());
            long now = System.nanoTime();
            h.session().bind(player.getUniqueId(), player.getName(), handle.getId(), now);
            ClientFacts.emit(h.session(), player, handle.connection.connection.channel, h.observer().status(), identityPolicy, now);
        });
        return handler.map(WhyNotChannelHandler::session);
    }

    /** Handler names of the player's Netty pipeline, head (network) to tail (game), for interop diagnostics. */
    public Optional<List<String>> pipeline(Player player) {
        if (!(player instanceof CraftPlayer craft) || craft.getHandle().connection == null) {
            return Optional.empty();
        }
        Channel channel = craft.getHandle().connection.connection.channel;
        return channel == null ? Optional.empty() : Optional.of(List.copyOf(channel.pipeline().names()));
    }

    public Optional<PlayerSession> session(Player player) {
        return handler(player).map(WhyNotChannelHandler::session);
    }

    /** The edge view's state for diagnostics. */
    public Optional<WireObserver.Status> edge(Player player) {
        return handler(player).map(h -> h.observer().status());
    }

    /**
     * Re-checks that the player's edge taps still sit at the outer edge of the pipeline (another plugin
     * may have inserted itself outside them) and repairs it on the connection's event loop.
     */
    public void auditPlacement(Player player) {
        if (!(player instanceof CraftPlayer craft) || craft.getHandle().connection == null) {
            return;
        }
        Channel channel = craft.getHandle().connection.connection.channel;
        if (channel == null || !channel.isOpen()) {
            return;
        }
        channel.eventLoop().execute(() -> {
            try {
                if (WireTaps.ensurePlacement(channel.pipeline())) {
                    logger.info("WhyNotAC moved its edge taps back to the network edge of " + player.getName()
                            + " (another plugin had inserted a handler outside them)");
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "WhyNotAC could not audit the pipeline of " + player.getName(), e);
            }
        });
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
        // instanceof, not a cast: after a reload the pipeline may still hold the old instance's handler.
        return channel.pipeline().get(HANDLER_NAME) instanceof WhyNotChannelHandler handler ? Optional.of(handler) : Optional.empty();
    }

    private static List<Connection> existingConnections() {
        List<Connection> connections = MinecraftServer.getServer().getConnection().getConnections();
        synchronized (connections) {
            return new ArrayList<>(connections);
        }
    }
}
