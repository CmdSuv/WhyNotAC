package dev.whynotac.paper.verdict;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.verdict.BanWaveQueue;
import dev.whynotac.verdict.CircuitBreaker;
import dev.whynotac.verdict.MitigationKind;
import dev.whynotac.verdict.VerdictConfig;
import dev.whynotac.verdict.VerdictDecision;
import dev.whynotac.verdict.VerdictEngine;
import io.papermc.paper.ban.BanListType;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Paper platform adapter for {@link VerdictEngine}: loads {@link VerdictConfig} from {@code config.yml},
 * executes soft mitigations when {@code Action.NEUTRALIZE} fires on {@code ARMED} deterministic checks,
 * and dispatches {@code KICK}/{@code TEMP_BAN} actions onto the server main thread.
 */
public final class PaperVerdictBridge implements VerdictEngine.EnforcementListener, Listener {

    /**
     * Bridges the 1-second check report cooldown so deterministic continuous cheats cannot land
     * unmitigated hits/actions in a cooldown hole while recovering automatically once clean.
     */
    static final long MITIGATION_TTL_NANOS = TimeUnit.MILLISECONDS.toNanos(1_250);
    private static final int SAFE_TRAIL_SIZE = 3;
    private static final long HIDE_INFORMATION_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final double HIDE_INFORMATION_DISTANCE_SQ = 16.0 * 16.0;
    private static final double HIDE_INFORMATION_MAX_DISTANCE_SQ = 64.0 * 64.0;
    static final int MAX_RAYTRACES_PER_TICK = 32;
    private static final double MAX_SIMULATION_SETBACK_DISTANCE_SQ = 64.0 * 64.0;

    private final Plugin plugin;
    private final Logger logger;
    private volatile boolean mitigationsEnabled;
    private volatile boolean hidesInformationEnabled;

    private final Map<UUID, Long> cancelAttackUntilNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cancelBlockUntilNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> setbackCooldownUntilNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Location>> safeTrails = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hideInfoUntilNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenTargetsByObserver = new ConcurrentHashMap<>();

    public PaperVerdictBridge(Plugin plugin, VerdictConfig config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.mitigationsEnabled = config.mitigationsEnabled();
        this.hidesInformationEnabled = config.hidesInformationEnabled();
    }

    /** Atomically updates runtime mitigation/occlusion flags after {@code /whynotac reload}. */
    public void updateConfig(VerdictConfig config) {
        if (config != null) {
            this.mitigationsEnabled = config.mitigationsEnabled();
            this.hidesInformationEnabled = config.hidesInformationEnabled();
            if (!this.mitigationsEnabled) {
                cancelAttackUntilNanos.clear();
                cancelBlockUntilNanos.clear();
                setbackCooldownUntilNanos.clear();
            }
            if (!this.hidesInformationEnabled && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, this::restoreAllOccluded);
            }
        }
    }

    /** Restores visibility for all observers with occluded players (primary thread; reload / plugin disable). */
    public void restoreAllOccluded() {
        for (UUID observerId : java.util.List.copyOf(hideInfoUntilNanos.keySet())) {
            hideInfoUntilNanos.remove(observerId);
            Player observer = Bukkit.getPlayer(observerId);
            if (observer != null && observer.isOnline()) {
                restoreAllHidden(observer);
            } else {
                hiddenTargetsByObserver.remove(observerId);
            }
        }
        for (UUID observerId : java.util.List.copyOf(hiddenTargetsByObserver.keySet())) {
            Player observer = Bukkit.getPlayer(observerId);
            if (observer != null && observer.isOnline()) {
                restoreAllHidden(observer);
            } else {
                hiddenTargetsByObserver.remove(observerId);
            }
        }
    }

    public static VerdictConfig loadConfig(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return VerdictConfig.shadowDefaults();
        }
        Set<String> armed = new LinkedHashSet<>();
        for (String id : section.getStringList("armed-checks")) {
            if (!"design-probe".equals(id) && Checks.version(id) < 0) {
                logger.warning("WhyNotAC config verdict.armed-checks ignores unknown check '" + id + "'");
                continue;
            }
            armed.add(id);
        }
        int tempBanHours = Math.max(1, section.getInt("temp-ban-hours", 24));
        int maxPenalizedPerMin = Math.max(1, section.getInt("max-enforced-players-per-minute", 5));
        int maxFalseBanBudget = Math.max(0, section.getInt("max-false-ban-budget", 1));
        int falseBanWindowDays = Math.max(1, section.getInt("false-ban-window-days", 7));
        int halfLifeSec = Math.max(1, section.getInt("correlation-half-life-seconds", 120));
        double minMitigate = Math.max(0.1, section.getDouble("min-score-for-mitigation", 1.0));
        double minKick = Math.max(0.1, section.getDouble("min-score-for-kick", 3.0));
        long banWaveDelaySeconds = Math.max(0L, section.getLong("ban-wave-delay-seconds", 0L));
        return VerdictConfig.builder()
                .armedChecks(armed)
                .mitigationsEnabled(section.getBoolean("mitigations", false))
                .hidesInformationEnabled(section.getBoolean("hide-information", false))
                .autoKickEnabled(section.getBoolean("auto-kick", false))
                .autoBanEnabled(section.getBoolean("auto-ban", false))
                .secondVerifierEnabled(section.getBoolean("second-verifier", true))
                .requireCorroborationForTempBan(section.getBoolean("require-corroboration-for-ban", true))
                .tempBanDurationMillis(TimeUnit.HOURS.toMillis(tempBanHours))
                .maxUniquePenalizedPlayersPerMinute(maxPenalizedPerMin)
                .maxFalseBanBudgetPerWindow(maxFalseBanBudget)
                .falseBanBudgetWindowMillis(TimeUnit.DAYS.toMillis(falseBanWindowDays))
                .correlationHalfLifeNanos(TimeUnit.SECONDS.toNanos(halfLifeSec))
                .minScoreForMitigation(minMitigate)
                .minScoreForKick(minKick)
                .banWaveDelayMillis(TimeUnit.SECONDS.toMillis(banWaveDelaySeconds))
                .build();
    }

    @Override
    public void hideInformation(VerdictDecision decision) {
        if (!hidesInformationEnabled || !plugin.isEnabled()) {
            return;
        }
        UUID uuid = decision.session().identity() != null ? decision.session().identity().playerId() : null;
        if (uuid == null) {
            return;
        }
        long deadline = System.nanoTime() + HIDE_INFORMATION_TTL_NANOS;
        hideInfoUntilNanos.put(uuid, deadline);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player observer = Bukkit.getPlayer(uuid);
            if (observer != null && observer.isOnline()) {
                refreshOccludedVisibility(observer, deadline);
            }
        });
    }

    /** Called periodically (e.g. every 10 ticks) to update or restore occluded ESP visibility safely on main thread. */
    public void tickOcclusionVisibility() {
        if (!hidesInformationEnabled) {
            if (!hideInfoUntilNanos.isEmpty() || !hiddenTargetsByObserver.isEmpty()) {
                restoreAllOccluded();
            }
            return;
        }
        if (hideInfoUntilNanos.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        int remainingBudget = MAX_RAYTRACES_PER_TICK;
        for (Map.Entry<UUID, Long> entry : hideInfoUntilNanos.entrySet()) {
            UUID observerId = entry.getKey();
            Player observer = Bukkit.getPlayer(observerId);
            if (observer == null || !observer.isOnline()) {
                hideInfoUntilNanos.remove(observerId);
                hiddenTargetsByObserver.remove(observerId);
                continue;
            }
            if (now > entry.getValue()) {
                hideInfoUntilNanos.remove(observerId);
                restoreAllHidden(observer);
            } else if (remainingBudget > 0) {
                remainingBudget = refreshOccludedVisibility(observer, entry.getValue(), remainingBudget);
            }
        }
    }

    private int refreshOccludedVisibility(Player observer, long deadlineNanos) {
        return refreshOccludedVisibility(observer, deadlineNanos, MAX_RAYTRACES_PER_TICK);
    }

    private int refreshOccludedVisibility(Player observer, long deadlineNanos, int raytraceBudget) {
        if (System.nanoTime() > deadlineNanos) {
            restoreAllHidden(observer);
            return raytraceBudget;
        }
        int budget = raytraceBudget;
        Set<UUID> hidden = hiddenTargetsByObserver.computeIfAbsent(observer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        Location eye = observer.getEyeLocation();
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(observer.getUniqueId())) {
                continue;
            }
            boolean sameWorld = target.getWorld().equals(observer.getWorld());
            if (!sameWorld) {
                if (hidden.remove(target.getUniqueId())) {
                    observer.showPlayer(plugin, target);
                }
                continue;
            }
            double distSq = eye.distanceSquared(target.getLocation());
            if (distSq <= HIDE_INFORMATION_DISTANCE_SQ || distSq > HIDE_INFORMATION_MAX_DISTANCE_SQ) {
                if (hidden.remove(target.getUniqueId())) {
                    observer.showPlayer(plugin, target);
                }
                continue;
            }
            if (budget <= 0) {
                continue;
            }
            budget--;
            boolean shouldHide = !observer.hasLineOfSight(target);
            if (shouldHide) {
                if (hidden.add(target.getUniqueId())) {
                    observer.hidePlayer(plugin, target);
                }
            } else if (hidden.remove(target.getUniqueId())) {
                observer.showPlayer(plugin, target);
            }
        }
        return budget;
    }

    private void restoreAllHidden(Player observer) {
        Set<UUID> hidden = hiddenTargetsByObserver.remove(observer.getUniqueId());
        if (hidden == null) {
            return;
        }
        for (UUID targetId : hidden) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null && target.isOnline()) {
                observer.showPlayer(plugin, target);
            }
        }
    }

    @Override
    public void mitigate(VerdictDecision decision) {
        if (!mitigationsEnabled || decision.mitigation() == MitigationKind.NONE) {
            return;
        }
        UUID uuid = decision.session().identity() != null ? decision.session().identity().playerId() : null;
        if (uuid == null) {
            return;
        }
        long deadline = System.nanoTime() + MITIGATION_TTL_NANOS;
        switch (decision.mitigation()) {
            case CANCEL_IMPOSSIBLE_HIT -> cancelAttackUntilNanos.put(uuid, deadline);
            case CANCEL_IMPOSSIBLE_BLOCK_ACTION -> cancelBlockUntilNanos.put(uuid, deadline);
            case SETBACK_POSITION -> {
                setbackCooldownUntilNanos.put(uuid, deadline);
                if (!plugin.isEnabled()) {
                    return;
                }
                Map<String, String> details = decision.evidence() != null ? decision.evidence().details() : Map.of();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) {
                        return;
                    }
                    Location current = p.getLocation();
                    Location predicted = simulationPredictedLocation(current, details);
                    if (predicted != null) {
                        p.teleport(predicted);
                        return;
                    }
                    Deque<Location> trail = safeTrails.get(uuid);
                    Location safe = earliestSafeLocation(trail);
                    if (safe != null && safe.getWorld() != null && safe.getWorld().equals(p.getWorld())) {
                        p.teleport(safe);
                    }
                });
            }
            case NONE -> { }
        }
    }

    /**
     * Extracts the bit-exact simulation-predicted coordinates from {@code MovementCheck} evidence details
     * ({@code predicted="x,y,z ..."}) or {@code VelocityCheck} ({@code baseY} with current X/Z) when valid and
     * within {@link #MAX_SIMULATION_SETBACK_DISTANCE_SQ} of {@code currentLocation}.
     */
    static Location simulationPredictedLocation(Location currentLocation, Map<String, String> details) {
        if (currentLocation == null || details == null || details.isEmpty()) {
            return null;
        }
        String predicted = details.get("predicted");
        if (predicted != null && !predicted.isBlank()) {
            int space = predicted.indexOf(' ');
            String coords = space > 0 ? predicted.substring(0, space) : predicted.trim();
            String[] parts = coords.split(",");
            if (parts.length == 3) {
                try {
                    double x = Double.parseDouble(parts[0]);
                    double y = Double.parseDouble(parts[1]);
                    double z = Double.parseDouble(parts[2]);
                    if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                        double dx = x - currentLocation.getX();
                        double dy = y - currentLocation.getY();
                        double dz = z - currentLocation.getZ();
                        if (dx * dx + dy * dy + dz * dz <= MAX_SIMULATION_SETBACK_DISTANCE_SQ) {
                            return new Location(currentLocation.getWorld(), x, y, z,
                                    currentLocation.getYaw(), currentLocation.getPitch());
                        }
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    @Override
    public void kick(VerdictDecision decision) {
        if (!plugin.isEnabled()) {
            return;
        }
        UUID uuid = decision.session().identity() != null ? decision.session().identity().playerId() : null;
        if (uuid == null) {
            return;
        }
        String certId = decision.certificate().certificateId();
        String certShort = certId.substring(0, Math.min(8, certId.length()));
        String msg = "WhyNotAC: " + decision.evidence().check() + " (" + certShort + ")";
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.kick(Component.text(msg, NamedTextColor.RED));
            }
        });
    }

    @Override
    public void tempBan(VerdictDecision decision) {
        if (!plugin.isEnabled()) {
            return;
        }
        UUID uuid = decision.session().identity() != null ? decision.session().identity().playerId() : null;
        if (uuid == null) {
            return;
        }
        String name = decision.session().identity().name();
        String certId = decision.certificate().certificateId();
        String certShort = certId.substring(0, Math.min(8, certId.length()));
        String digest = decision.certificate().sha256Digest();
        String digestShort = digest.substring(0, Math.min(12, digest.length()));
        String reason = "WhyNotAC automated check (" + decision.evidence().check() + ", cert " + certShort + ")";
        Instant expires = Instant.now().plusMillis(decision.tempBanDurationMillis());
        logger.warning("WhyNotAC issuing temp-ban for " + name + " (" + uuid + "): " + reason
                + " digest=" + digestShort);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            var profile = online != null ? online.getPlayerProfile() : Bukkit.createProfile(uuid, name);
            Bukkit.getBanList(BanListType.PROFILE).addBan(profile, reason, Date.from(expires), "WhyNotAC");
            if (online != null && online.isOnline()) {
                online.kick(Component.text(reason, NamedTextColor.RED));
            }
        });
    }

    /** Executes an unexpired ban-wave entry recovered from {@code ban-wave-queue.jsonl} after restart. */
    public void executeRecoveredBan(BanWaveQueue.RecoveredEntry entry, long tempBanDurationMillis) {
        if (!plugin.isEnabled() || entry == null || entry.playerId() == null) {
            return;
        }
        UUID uuid = entry.playerId();
        String digest = entry.certificateSha256();
        String digestShort = digest == null ? "?" : digest.substring(0, Math.min(12, digest.length()));
        String reason = "WhyNotAC automated check (" + entry.check() + ", recovered cert " + digestShort + ")";
        Instant expires = Instant.now().plusMillis(Math.max(TimeUnit.HOURS.toMillis(1), tempBanDurationMillis));
        logger.warning("WhyNotAC issuing recovered ban-wave temp-ban for " + uuid + " (case " + entry.caseId()
                + ", check=" + entry.check() + ", digest=" + digestShort + ")");
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(uuid);
            var profile = online != null ? online.getPlayerProfile() : Bukkit.createProfile(uuid, null);
            Bukkit.getBanList(BanListType.PROFILE).addBan(profile, reason, Date.from(expires), "WhyNotAC");
            if (online != null && online.isOnline()) {
                online.kick(Component.text(reason, NamedTextColor.RED));
            }
        });
    }

    @Override
    public void verificationRejected(VerdictDecision decision) {
        String name = decision.session().identity() != null ? decision.session().identity().name() : "?";
        logger.warning("WhyNotAC aborted automatic temp-ban for " + name + " (" + decision.evidence().check()
                + "): SecondVerifier did not reproduce finding (" + decision.reason()
                + "); false-ban budget locked=" + decision.verdictCase().suppressedBySafetyGate());
    }

    @Override
    public void circuitBreakerTripped(CircuitBreaker.Status status) {
        String alert = "[WhyNotAC] CIRCUIT BREAKER TRIPPED (" + status.reason() + ") — degraded all enforcement to SHADOW!";
        logger.severe(alert);
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Component text = Component.text(alert, NamedTextColor.RED);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("whynotac.alerts")) {
                    player.sendMessage(text);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!mitigationsEnabled) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        Long cooldown = setbackCooldownUntilNanos.get(uuid);
        if (cooldown != null && System.nanoTime() < cooldown) {
            return;
        }
        Deque<Location> trail = safeTrails.computeIfAbsent(uuid, k -> new ArrayDeque<>(SAFE_TRAIL_SIZE));
        recordSafeStep(trail, event.getFrom());
    }

    static void recordSafeStep(Deque<Location> trail, Location from) {
        if (from == null) {
            return;
        }
        synchronized (trail) {
            if (trail.size() >= SAFE_TRAIL_SIZE) {
                trail.pollFirst();
            }
            trail.addLast(from.clone());
        }
    }

    static Location earliestSafeLocation(Deque<Location> trail) {
        if (trail == null) {
            return null;
        }
        synchronized (trail) {
            return trail.peekFirst();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!mitigationsEnabled || !(event.getDamager() instanceof Player player)) {
            return;
        }
        Long until = cancelAttackUntilNanos.get(player.getUniqueId());
        if (until != null && System.nanoTime() <= until) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!mitigationsEnabled) {
            return;
        }
        Long until = cancelBlockUntilNanos.get(event.getPlayer().getUniqueId());
        if (until != null && System.nanoTime() <= until) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!mitigationsEnabled) {
            return;
        }
        Long until = cancelBlockUntilNanos.get(event.getPlayer().getUniqueId());
        if (until != null && System.nanoTime() <= until) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cancelAttackUntilNanos.remove(uuid);
        cancelBlockUntilNanos.remove(uuid);
        setbackCooldownUntilNanos.remove(uuid);
        safeTrails.remove(uuid);
        hideInfoUntilNanos.remove(uuid);
        hiddenTargetsByObserver.remove(uuid);
        hiddenTargetsByObserver.values().forEach(set -> set.remove(uuid));
    }
}
