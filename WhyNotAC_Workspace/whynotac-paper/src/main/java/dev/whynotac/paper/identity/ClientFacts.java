package dev.whynotac.paper.identity;

import dev.whynotac.paper.wire.WireObserver;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalChannel;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.spigotmc.SpigotConfig;

/**
 * What the server can establish about a joining client, each fact with its trust level (see
 * {@code TimelineEvent.ClientFact}). Third-party APIs (ViaVersion, Floodgate, Geyser) are reached
 * through their own plugin's class loader, so none of them is a dependency and any of them may be
 * absent, old or broken without affecting WhyNotAC (every lookup is fail-open).
 */
public final class ClientFacts {

    /** First protocol with {@code ClientboundPingPacket} (1.17). Older clients cannot answer a transaction themselves. */
    static final int FIRST_PROTOCOL_WITH_PING = 755;

    private ClientFacts() {}

    /** How this server establishes identities, from its own configuration (the same for every connection). */
    public record Policy(Identity identity, String topology, String detail) {
        /** True if long-lived state (profiles, bans) may be keyed by the identities this server sees. */
        public boolean authenticated() {
            return identity == Identity.AUTHENTICATED_MOJANG || identity == Identity.AUTHENTICATED_BY_PROXY;
        }
    }

    public static Policy policy() {
        GlobalConfiguration.Proxies proxies = GlobalConfiguration.get().proxies;
        return policy(Bukkit.getOnlineMode(), SpigotConfig.bungee, proxies.bungeeCord.onlineMode, proxies.velocity.enabled,
                proxies.velocity.onlineMode, proxies.proxyProtocol);
    }

    /** The decision itself, separate from where the configuration comes from (unit-tested). */
    static Policy policy(boolean onlineMode, boolean bungee, boolean bungeeOnlineMode, boolean velocity, boolean velocityOnlineMode,
            boolean proxyProtocol) {
        if (velocity) {
            // Paper rejects logins whose forwarding HMAC does not verify; the proxy authenticated them if it runs in online mode.
            return velocityOnlineMode
                    ? new Policy(Identity.AUTHENTICATED_BY_PROXY, "VELOCITY_MODERN", "velocity modern forwarding (HMAC-verified), proxy in online mode")
                    : new Policy(Identity.UNAUTHENTICATED, "VELOCITY_MODERN", "velocity modern forwarding, proxy in offline mode");
        }
        if (bungee) {
            // Legacy forwarding is plain text in the handshake: anyone who can reach this port can claim any UUID.
            return new Policy(bungeeOnlineMode ? Identity.CLAIMED_BY_PROXY : Identity.UNAUTHENTICATED, "BUNGEE_LEGACY",
                    "legacy bungee forwarding: this server cannot verify identities (firewall the port or use modern forwarding)");
        }
        String topology = proxyProtocol ? "PROXY_PROTOCOL" : "DIRECT";
        return onlineMode
                ? new Policy(Identity.AUTHENTICATED_MOJANG, topology, "online mode")
                : new Policy(Identity.UNAUTHENTICATED, topology, "offline mode: anyone can join under any name");
    }

    /** Records the facts about a player who just joined. Never throws. */
    public static void emit(PlayerSession session, Player player, Channel channel, WireObserver.Status edge, Policy policy, long now) {
        try {
            UUID id = player.getUniqueId();
            boolean inProcess = channel instanceof LocalChannel; // only code inside this server can create one (Geyser-Spigot)
            Optional<Boolean> floodgate = call("floodgate", "org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", id);
            Optional<Boolean> geyser = call("Geyser-Spigot", "org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", id);

            if (floodgate.orElse(false)) {
                session.onFact(Fact.IDENTITY, Identity.AUTHENTICATED_BY_PROXY.name(), Trust.AUTHENTIC, "floodgate (Xbox account, key-verified)", now);
            } else {
                session.onFact(Fact.IDENTITY, policy.identity().name(), policy.authenticated() ? Trust.AUTHENTIC : Trust.CLAIMED, policy.detail(), now);
            }
            session.onFact(Fact.TOPOLOGY, inProcess ? "GEYSER_LOCAL" : policy.topology(), Trust.AUTHENTIC,
                    inProcess ? "in-process local channel" : "server configuration", now);

            boolean bedrock = floodgate.orElse(false) || geyser.orElse(false);
            if (floodgate.isPresent() || geyser.isPresent()) {
                session.onFact(Fact.BEDROCK, Boolean.toString(bedrock), Trust.AUTHENTIC, floodgate.isPresent() ? "floodgate api" : "geyser api", now);
            }

            int protocol = edge.clientProtocol();
            String protocolSource = "handshake bytes at the network edge";
            if (protocol < 0) {
                Optional<Integer> via = viaVersion(id);
                protocol = via.orElse(-1);
                protocolSource = "viaversion api";
            }
            if (protocol >= 0) {
                int server = SharedConstants.getProtocolVersion();
                session.onFact(Fact.NATIVE_PROTOCOL, Integer.toString(protocol), Trust.FORMAT_BOUND, protocolSource, now);
                session.onFact(Fact.TRANSLATOR, protocol == server ? "none" : translatorName(), Trust.FORMAT_BOUND,
                        protocol == server ? "client speaks the server's protocol" : "client protocol " + protocol + " != " + server, now);
                session.onFact(Fact.TRANSACTION_ORIGIN, protocol < FIRST_PROTOCOL_WITH_PING ? "TRANSLATOR" : "CLIENT", Trust.FORMAT_BOUND,
                        protocol < FIRST_PROTOCOL_WITH_PING
                                ? "pre-1.17 clients have no ping packet: the translator answers transactions on their behalf"
                                : "the client answers transactions itself", now);
            }
        } catch (RuntimeException | LinkageError e) {
            // Facts are best-effort; their absence means "unknown", never "clean".
        }
    }

    private static String translatorName() {
        for (String name : new String[] {"ViaVersion", "ViaBackwards", "ViaRewind"}) {
            if (Bukkit.getPluginManager().isPluginEnabled(name)) {
                return name.toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "unknown";
    }

    private static Optional<Integer> viaVersion(UUID id) {
        Plugin via = Bukkit.getPluginManager().getPlugin("ViaVersion");
        if (via == null || !via.isEnabled()) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName("com.viaversion.viaversion.api.Via", true, via.getClass().getClassLoader());
            Object api = type.getMethod("getAPI").invoke(null);
            return Optional.of((Integer) api.getClass().getMethod("getPlayerVersion", UUID.class).invoke(api, id));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** {@code staticAccessor()} on the plugin's API class, then {@code test(uuid)}; empty if absent or failing. */
    private static Optional<Boolean> call(String pluginName, String apiClass, String staticAccessor, String test, UUID id) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null || !plugin.isEnabled()) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(apiClass, true, plugin.getClass().getClassLoader());
            Object api = type.getMethod(staticAccessor).invoke(null);
            return Optional.of((Boolean) type.getMethod(test, UUID.class).invoke(api, id));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
