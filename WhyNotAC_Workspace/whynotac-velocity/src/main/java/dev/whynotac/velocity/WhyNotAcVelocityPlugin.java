package dev.whynotac.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.whynotac.protocol.network.NetworkEnvelope;
import dev.whynotac.protocol.network.NetworkSecret;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

/**
 * Velocity proxy companion plugin for WhyNotAC (NET-01 / NET-22).
 *
 * <p>Uses a shared HMAC-SHA256 secret ({@code network-secret.key}) to sign true proxy-edge metadata when players
 * connect to backend Paper servers and to authenticate cross-server profile/enforcement relays.
 */
@Plugin(
        id = "whynotac-velocity",
        name = "WhyNotAC-Velocity",
        version = "0.1.0-SNAPSHOT",
        description = "Authenticated edge declaration and cross-server bridge for WhyNotAC",
        authors = {"WhyNotAC"}
)
public final class WhyNotAcVelocityPlugin {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("whynotac", "bridge");
    private static final long MAX_SKEW_MILLIS = 60_000L;
    private static final int SERVER_NATIVE_PROTOCOL = 774;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile byte[] secret;

    private final AtomicLong sentEdges = new AtomicLong();
    private final AtomicLong relayedProfiles = new AtomicLong();
    private final AtomicLong relayedEnforcements = new AtomicLong();
    private final AtomicLong rejectedFrames = new AtomicLong();

    @Inject
    public WhyNotAcVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent event) {
        try {
            secret = NetworkSecret.loadOrCreate(dataDirectory.resolve("network-secret.key"));
        } catch (IOException e) {
            logger.error("Failed to initialize WhyNotAC network bridge secret; bridge disabled", e);
            return;
        }
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("wnacproxy").plugin(this).build(),
                new ProxyCommand());
        logger.info("WhyNotAC Velocity companion active (channel={})", CHANNEL.getId());
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        byte[] key = secret;
        if (key == null) {
            return;
        }
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(serverConn -> {
            int clientProtocol = player.getProtocolVersion().getProtocol();
            boolean onlineMode = proxy.getConfiguration().isOnlineMode();
            boolean bedrock = isBedrock(player.getUniqueId());
            boolean proxyTranslatorPlugin = proxy.getPluginManager().isLoaded("viaversion")
                    || proxy.getPluginManager().isLoaded("geyser-velocity");
            byte[] frame = buildSignedEdgeEnvelope(
                    player.getUniqueId(), clientProtocol, onlineMode, bedrock, proxyTranslatorPlugin,
                    System.currentTimeMillis(), key);
            serverConn.sendPluginMessage(CHANNEL, frame);
            sentEdges.incrementAndGet();
        });
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        byte[] key = secret;
        if (key == null) {
            rejectedFrames.incrementAndGet();
            return;
        }
        // Only accept bridge relays originating from backend servers, never directly from clients.
        if (event.getSource() instanceof Player) {
            rejectedFrames.incrementAndGet();
            return;
        }
        Optional<byte[]> validated = validateRelayFrame(event.getData(), key, System.currentTimeMillis());
        if (validated.isEmpty()) {
            rejectedFrames.incrementAndGet();
            return;
        }
        byte[] verifiedData = validated.get();
        try {
            NetworkEnvelope.Payload decoded = NetworkEnvelope.decodeAndVerify(verifiedData, key, System.currentTimeMillis(), MAX_SKEW_MILLIS);
            if (decoded instanceof NetworkEnvelope.ProfileSummarySync) {
                relayedProfiles.incrementAndGet();
            } else if (decoded instanceof NetworkEnvelope.EnforcementNotice) {
                relayedEnforcements.incrementAndGet();
            } else {
                return;
            }
        } catch (IOException e) {
            rejectedFrames.incrementAndGet();
            return;
        }
        String originName = event.getSource() instanceof ServerConnection sc
                ? sc.getServerInfo().getName()
                : null;
        for (RegisteredServer server : proxy.getAllServers()) {
            if (shouldForwardTo(originName, server.getServerInfo().getName(), server.getPlayersConnected().size())) {
                server.sendPluginMessage(CHANNEL, verifiedData);
            }
        }
    }

    /**
     * Whether a validated backend relay frame originating from {@code originServerName} should be forwarded to
     * {@code targetServerName}: skips the originating server to prevent double-counting profile merges or self-echoes,
     * and skips servers with no connected carrier players.
     */
    public static boolean shouldForwardTo(String originServerName, String targetServerName, int targetPlayersConnected) {
        if (targetPlayersConnected <= 0 || targetServerName == null) {
            return false;
        }
        return originServerName == null || !originServerName.equals(targetServerName);
    }

    /** Builds a signed {@link NetworkEnvelope.ProxyClientEdge} describing the upstream client connection. */
    public static byte[] buildSignedEdgeEnvelope(UUID playerId, int clientProtocol, boolean proxyOnlineMode,
            boolean bedrock, boolean proxyTranslatorLoaded, long nowMillis, byte[] hmacKey) {
        String translator = bedrock ? "geyser"
                : (clientProtocol != SERVER_NATIVE_PROTOCOL && proxyTranslatorLoaded ? "viaversion-proxy" : "none");
        String txOrigin = clientProtocol >= 755 ? "CLIENT" : "TRANSLATOR";
        Identity identity = (proxyOnlineMode || bedrock) ? Identity.AUTHENTICATED_BY_PROXY : Identity.UNAUTHENTICATED;
        NetworkEnvelope.ProxyClientEdge payload = new NetworkEnvelope.ProxyClientEdge(
                nowMillis, playerId, clientProtocol, translator, txOrigin, identity, bedrock);
        return NetworkEnvelope.encode(payload, hmacKey);
    }

    /** Verifies HMAC signature, clock skew, and non-client relay policy before forwarding across servers. */
    public static Optional<byte[]> validateRelayFrame(byte[] frame, byte[] hmacKey, long nowMillis) {
        try {
            NetworkEnvelope.Payload payload = NetworkEnvelope.decodeAndVerify(frame, hmacKey, nowMillis, MAX_SKEW_MILLIS);
            if (payload instanceof NetworkEnvelope.ProfileSummarySync || payload instanceof NetworkEnvelope.EnforcementNotice) {
                return Optional.of(frame);
            }
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private boolean isBedrock(UUID playerId) {
        return proxy.getPluginManager().getPlugin("floodgate").map(container -> {
            Optional<?> inst = container.getInstance();
            if (inst.isEmpty()) {
                return false;
            }
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, inst.get().getClass().getClassLoader());
                Object api = apiClass.getMethod("getInstance").invoke(null);
                return Boolean.TRUE.equals(apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, playerId));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return false;
            }
        }).orElse(false);
    }

    private final class ProxyCommand implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("whynotac.proxy.admin");
        }

        @Override
        public void execute(Invocation invocation) {
            invocation.source().sendMessage(Component.text(
                    "WhyNotAC-Velocity active | servers=" + proxy.getAllServers().size()
                            + " sentEdges=" + sentEdges.get()
                            + " relayedProfiles=" + relayedProfiles.get()
                            + " relayedEnforcements=" + relayedEnforcements.get()
                            + " rejected=" + rejectedFrames.get(),
                    NamedTextColor.GOLD));
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return List.of("status");
        }
    }
}
