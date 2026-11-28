package dev.whynotac.paper.identity;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * Offline authentication plugin integration ({@code Identity.AUTH_PLUGIN_CONFIRMED} from {@code docs/POLICY.md}):
 * checks installed offline login plugins via reflection only (fail-open, zero compile dependencies) and upgrades
 * an offline connection once the player completes login.
 *
 * <p>Supported plugins: AuthMeReloaded ({@code AuthMe}), nLogin ({@code nLogin}), LibreLogin ({@code LibreLogin}),
 * and OpeNLogin ({@code OpeNLogin}).
 */
public final class AuthPluginHook {

    /** Testable provider abstraction returning empty if absent/unauthenticated, or plugin name when authenticated. */
    @FunctionalInterface
    public interface AuthProbe {
        Optional<String> authenticatedPlugin(UUID uuid, String playerName);
    }

    private final boolean enabled;
    private final List<AuthProbe> probes;
    private final Set<Long> confirmedConnections = ConcurrentHashMap.newKeySet();

    public AuthPluginHook(boolean enabled) {
        this(enabled, defaultBukkitProbes());
    }

    AuthPluginHook(boolean enabled, List<AuthProbe> probes) {
        this.enabled = enabled;
        this.probes = List.copyOf(probes);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Returns true if Bukkit currently has any supported offline auth plugin enabled. */
    public static List<String> installedPluginNames() {
        PluginManager pm = Bukkit.getPluginManager();
        if (pm == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : List.of("AuthMe", "nLogin", "LibreLogin", "OpeNLogin")) {
            Plugin p = pm.getPlugin(name);
            if (p != null && p.isEnabled()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * If {@code session} has not been confirmed yet and any configured offline auth plugin confirms the player's login,
     * emits {@code Fact.IDENTITY = AUTH_PLUGIN_CONFIRMED} with {@code Trust.AUTHENTIC} once and returns the plugin name.
     */
    public Optional<String> checkAndUpgrade(PlayerSession session, UUID uuid, String playerName, long nanoTime) {
        if (!enabled || session == null || uuid == null || confirmedConnections.contains(session.connectionId())) {
            return Optional.empty();
        }
        for (AuthProbe probe : probes) {
            Optional<String> provider = safeQuery(probe, uuid, playerName);
            if (provider.isPresent()) {
                if (confirmedConnections.add(session.connectionId())) {
                    session.onFact(Fact.IDENTITY, Identity.AUTH_PLUGIN_CONFIRMED.name(), Trust.AUTHENTIC,
                            provider.get() + " confirmed offline login", nanoTime);
                    return provider;
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public boolean isConfirmed(PlayerSession session) {
        return session != null && confirmedConnections.contains(session.connectionId());
    }

    public void forgetConnection(long connectionId) {
        confirmedConnections.remove(connectionId);
    }

    private static Optional<String> safeQuery(AuthProbe probe, UUID uuid, String playerName) {
        try {
            return probe.authenticatedPlugin(uuid, playerName);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static List<AuthProbe> defaultBukkitProbes() {
        return List.of(
                (uuid, name) -> checkAuthMe(name),
                (uuid, name) -> checkNickUc("nLogin", "com.nickuc.login.api.nLoginAPI", "getApi", name),
                (uuid, name) -> checkLibreLogin(uuid),
                (uuid, name) -> checkNickUc("OpeNLogin", "com.nickuc.openlogin.bukkit.api.OpeNLoginAPI", "getApi", name)
        );
    }

    private static Optional<String> checkAuthMe(String playerName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("AuthMe");
        if (plugin == null || !plugin.isEnabled() || playerName == null) {
            return Optional.empty();
        }
        try {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null) {
                return Optional.empty();
            }
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, plugin.getClass().getClassLoader());
            Object api = apiClass.getMethod("getInstance").invoke(null);
            boolean authed = Boolean.TRUE.equals(apiClass.getMethod("isAuthenticated", Player.class).invoke(api, player));
            return authed ? Optional.of("AuthMe") : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static Optional<String> checkNickUc(String pluginName, String apiClassName, String accessor, String playerName) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null || !plugin.isEnabled() || playerName == null) {
            return Optional.empty();
        }
        try {
            Class<?> apiClass = Class.forName(apiClassName, true, plugin.getClass().getClassLoader());
            Object api;
            try {
                api = apiClass.getMethod(accessor).invoke(null);
            } catch (NoSuchMethodException staticOnly) {
                api = null;
            }
            Method isAuthed = apiClass.getMethod("isAuthenticated", String.class);
            boolean authed = Boolean.TRUE.equals(isAuthed.invoke(api, playerName));
            return authed ? Optional.of(pluginName) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static Optional<String> checkLibreLogin(UUID uuid) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("LibreLogin");
        if (plugin == null || !plugin.isEnabled() || uuid == null) {
            return Optional.empty();
        }
        try {
            Method getAuthProvider = plugin.getClass().getMethod("getAuthorizationProvider");
            Object authProvider = getAuthProvider.invoke(plugin);
            if (authProvider == null) {
                return Optional.empty();
            }
            // Support either isAuthorized(UUID) or isAuthorized(Player)
            try {
                Method byUuid = authProvider.getClass().getMethod("isAuthorized", UUID.class);
                if (Boolean.TRUE.equals(byUuid.invoke(authProvider, uuid))) {
                    return Optional.of("LibreLogin");
                }
            } catch (NoSuchMethodException ignored) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    Method byPlayer = authProvider.getClass().getMethod("isAuthorized", Object.class);
                    if (Boolean.TRUE.equals(byPlayer.invoke(authProvider, p))) {
                        return Optional.of("LibreLogin");
                    }
                }
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
