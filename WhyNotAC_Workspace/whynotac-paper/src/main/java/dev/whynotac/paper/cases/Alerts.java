package dev.whynotac.paper.cases;

import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.analysis.verdict.ShadowVerdict;
import dev.whynotac.protocol.session.PlayerSession;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Staff alerts for opened cases, when the policy permits an alert. A SHADOW check's alerts go only to
 * {@value #SHADOW_PERMISSION} (and the console); an armed check's to {@value #PERMISSION}. At most one
 * alert per player and check every {@link #MIN_INTERVAL_MILLIS} ms; the rest are counted into the next.
 */
public final class Alerts implements ShadowVerdict.CaseListener {

    public static final String PERMISSION = "whynotac.alerts";
    public static final String SHADOW_PERMISSION = "whynotac.alerts.shadow";
    static final long MIN_INTERVAL_MILLIS = 10_000;

    private final Plugin plugin;
    private final Map<String, long[]> lastAlert = new ConcurrentHashMap<>(); // key -> {epoch millis, suppressed}

    public Alerts(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void opened(ShadowVerdict.Case c) {
        if (!c.permittedActions().contains(Action.ALERT)) {
            return;
        }
        PlayerSession.Identity player = c.session().identity();
        String name = player == null ? c.session().remoteAddress() : player.name();
        String key = name + "/" + c.evidence().check();
        long now = c.createdEpochMillis();
        long[] last = lastAlert.computeIfAbsent(key, k -> new long[] {Long.MIN_VALUE / 2, 0});
        long suppressed;
        synchronized (last) {
            if (now - last[0] < MIN_INTERVAL_MILLIS) {
                last[1]++;
                return;
            }
            suppressed = last[1];
            last[0] = now;
            last[1] = 0;
        }
        boolean shadow = c.lifecycle() == Lifecycle.SHADOW;
        String text = "[WhyNotAC]" + (shadow ? " [shadow]" : "") + " " + name + " " + c.evidence().check() + " ("
                + c.evidence().evidenceClass().code() + "): " + c.evidence().summary()
                + (suppressed > 0 ? " (+" + suppressed + " more)" : "") + " - case " + c.id().substring(0, 8);
        String permission = shadow ? SHADOW_PERMISSION : PERMISSION;
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info(text);
            Component message = Component.text(text, shadow ? NamedTextColor.GRAY : NamedTextColor.RED);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(permission)) {
                    staff.sendMessage(message);
                }
            }
        });
    }

    /** Forgets a player's rate-limit state (when they leave). */
    public void forget(String name) {
        lastAlert.keySet().removeIf(k -> k.startsWith(name + "/"));
    }
}
