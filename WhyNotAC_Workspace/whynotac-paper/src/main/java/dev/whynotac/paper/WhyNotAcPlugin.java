package dev.whynotac.paper;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.verdict.ShadowVerdict;
import dev.whynotac.paper.cases.Alerts;
import dev.whynotac.paper.cases.CaseStore;
import dev.whynotac.paper.command.WhyNotCommand;
import dev.whynotac.paper.diagnostics.Diagnostics;
import dev.whynotac.paper.diagnostics.TickCost;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.paper.world.EntityVerifier;
import dev.whynotac.paper.world.InventoryVerifier;
import dev.whynotac.paper.world.WorldVerifier;
import dev.whynotac.protocol.latency.LatencySnapshot;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.recording.write.RecordingStore;
import dev.whynotac.world.ClientEntitiesTracker;
import dev.whynotac.world.ClientInventoryTracker;
import dev.whynotac.world.Replicas;
import dev.whynotac.world.ItemPrototypes;
import dev.whynotac.world.ClientWorldTracker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Entry point: packet interception, per-player timelines, transaction latency tracking and (optional) session recording. */
public final class WhyNotAcPlugin extends JavaPlugin implements Listener {

    private ExecutorService analysisExecutor;
    private SessionRegistry registry;
    private PacketInterceptor interceptor;
    private RecordingStore recordings;
    private final ShadowVerdict verdict = new ShadowVerdict();
    private CaseStore cases;
    private ClientWorldTracker worldTracker;
    private ClientEntitiesTracker entitiesTracker;
    private ClientInventoryTracker inventoryTracker;
    private Alerts alerts;
    private final AtomicLong listenerErrors = new AtomicLong();
    private final TickCost transactionLoop = new TickCost();
    private Diagnostics diagnostics;

    @Override
    public void onEnable() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        analysisExecutor = Executors.newFixedThreadPool(threads, daemonThreads("WhyNotAC Analysis"));
        registry = new SessionRegistry(analysisExecutor, SessionRegistry.DEFAULT_MAILBOX_CAPACITY, (session, event, error) -> {
            long count = listenerErrors.incrementAndGet();
            if (count <= 10 || Long.bitCount(count) == 1) {
                getLogger().log(Level.WARNING, "Analysis listener failed on " + session + " at " + event + " (error #" + count + ")", error);
            }
        });
        diagnostics = new Diagnostics();
        registry.addListener(diagnostics);
        saveDefaultConfig();
        RecordingSetup.Recording recording = null;
        try {
            recording = RecordingSetup.install(getConfig().getConfigurationSection("recording"), getDataPath(), registry,
                    getPluginMeta().getVersion(), getLogger()).orElse(null);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Session recording could not start; continuing without it", e);
        }
        recordings = recording == null ? null : recording.store();
        // Checks run after the recorder, so a case can name the recording that holds its window.
        RecordingSetup.Recording recorder = recording;
        cases = new CaseStore(getDataPath().resolve("cases.db"), getDataPath().resolve("cases"),
                session -> recorder == null ? java.util.Optional.empty() : recorder.listener().recordingFile(session),
                getPluginMeta().getVersion(), getLogger());
        cases.retain(Math.max(1, getConfig().getInt("cases.retention-days", 90)));
        alerts = new Alerts(this);
        verdict.addListener(cases);
        verdict.addListener(alerts);
        // The client's world, before every check that reads it (listeners see an event in registration order).
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        worldTracker = replicas.world();
        entitiesTracker = replicas.entities();
        inventoryTracker = replicas.inventory();
        Checks.all(verdict, replicas).forEach(registry::addListener);
        if (recordings != null) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, recordings::scheduleRetention, 20L * 3600, 20L * 3600);
        }

        boolean edge = getConfig().getBoolean("observation.edge", true);
        boolean perTickTransactions = getConfig().getBoolean("observation.per-tick-transactions", true);
        boolean flushTransactions = getConfig().getBoolean("observation.flush-transactions", true);
        if (!edge || !perTickTransactions || !flushTransactions) {
            getLogger().warning("WhyNotAC observation reduced by config.yml: edge=" + edge + ", per-tick-transactions=" + perTickTransactions
                    + ", flush-transactions=" + flushTransactions
                    + " (detection quality is lower; meant for measurements and troubleshooting)");
        }
        interceptor = new PacketInterceptor(registry, getLogger(), edge, flushTransactions);
        interceptor.install();
        Bukkit.getScheduler().runTask(this, interceptor::injectExisting); // connections still pending at install time
        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(interceptor::bind));
        // One stand-alone transaction per server tick keeps a continuous, per-tick latency trace.
        if (perTickTransactions) {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                long[] cost = transactionLoop.begin();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    interceptor.sendTransaction(player);
                }
                transactionLoop.end(cost);
            }, 1L, 1L);
        }
        // Another plugin may insert a handler outside the edge taps after login; move them back (every 5 s).
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                interceptor.auditPlacement(player);
            }
        }, 100L, 100L);

        getServer().getPluginManager().registerEvents(this, this);
        WhyNotCommand command = new WhyNotCommand(interceptor, registry, diagnostics, this::stats, cases,
                new WorldVerifier(interceptor, worldTracker, this), new EntityVerifier(interceptor, entitiesTracker, this),
                new InventoryVerifier(interceptor, inventoryTracker, ItemPrototypes.vanilla(), this), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("whynotac", "WhyNotAC diagnostics", List.of("wnac"), command));
        getLogger().info("WhyNotAC packet interceptor attached (" + threads + " analysis threads)");
    }

    @Override
    public void onDisable() {
        if (interceptor != null) {
            interceptor.uninstall();
            // Detaching closes every session on its network thread; wait (bounded) until each one's
            // Disconnected has been delivered, so recordings are finalised instead of left as .part.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!registry.sessions().isEmpty() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!registry.sessions().isEmpty()) {
                getLogger().warning(registry.sessions().size() + " session(s) were still open at shutdown");
            }
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            try {
                if (!analysisExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    analysisExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                analysisExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (recordings != null) {
            recordings.close(); // drains queued blocks (bounded) and finalises files
        }
        if (cases != null) {
            cases.close();
        }
    }

    /** One line of JSON for {@code /whynotac stats}: totals since start-up, for operators and the load test. */
    private String stats() {
        Diagnostics.Totals totals = diagnostics.totals();
        long now = System.nanoTime();
        int pendingMax = 0;
        long withheld = 0;
        for (PlayerSession session : registry.sessions()) {
            LatencySnapshot latency = session.latency(now);
            pendingMax = Math.max(pendingMax, latency.pending());
            withheld += latency.withheld() ? 1 : 0;
        }
        StringBuilder anomalies = new StringBuilder();
        for (Map.Entry<?, Long> entry : totals.anomalies().entrySet()) {
            anomalies.append(anomalies.isEmpty() ? "" : ",").append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return String.format(Locale.ROOT, "{\"sessionsLive\":%d,\"queuedEvents\":%d,\"sessions\":%d,\"ended\":%d,\"inbound\":%d,"
                        + "\"outbound\":%d,\"lostEvents\":%d,\"inboundDivergences\":%d,\"outboundDivergences\":%d,\"anomalies\":{%s},"
                        + "\"pendingTransactionsMax\":%d,\"withheldNow\":%d,\"listenerErrors\":%d,\"recordingRejectedBlocks\":%d,"
                        + "\"recordingFailed\":%d,\"transactionLoop\":{\"runs\":%d,\"meanWallMicros\":%.3f,\"meanCpuMicros\":%.3f,\"maxWallMicros\":%.3f},"
                        + "\"cases\":{\"opened\":%d,\"stored\":%d,\"dropped\":%d,\"rejectedCandidates\":%d},"
                        + "\"world\":{\"retainedBytes\":%d,\"sharedSections\":%d,\"worlds\":%d},"
                        + "\"entities\":{\"ticks\":%d,\"branches\":%d,\"merges\":%d,\"overflows\":%d,\"losses\":%d}}",
                registry.sessions().size(), registry.queuedEvents(), totals.sessions(), totals.ended(), totals.inbound(),
                totals.outbound(), totals.lostEvents(), totals.inboundDivergences(), totals.outboundDivergences(), anomalies,
                pendingMax, withheld, listenerErrors.get(), recordings == null ? 0 : recordings.rejectedBlocks(),
                recordings == null ? 0 : recordings.failedRecordings(), transactionLoop.runs(), transactionLoop.meanWallMicros(),
                transactionLoop.meanCpuMicros(), transactionLoop.maxWallMicros(),
                verdict.cases(), cases == null ? 0 : cases.storedCount(), cases == null ? 0 : cases.droppedCount(),
                verdict.rejections().size(), worldTracker == null ? 0 : worldTracker.retainedBytes(),
                worldTracker == null ? 0 : worldTracker.sharedSections(), worldTracker == null ? 0 : worldTracker.worldsCreated(),
                entitiesTracker == null ? 0 : entitiesTracker.totals().ticks(), entitiesTracker == null ? 0 : entitiesTracker.totals().branches(),
                entitiesTracker == null ? 0 : entitiesTracker.totals().merges(), entitiesTracker == null ? 0 : entitiesTracker.totals().overflows(),
                entitiesTracker == null ? 0 : entitiesTracker.totals().losses());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (alerts != null) {
            alerts.forget(event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (interceptor.bind(event.getPlayer()).isEmpty()) {
            getLogger().warning("No WhyNotAC session on " + event.getPlayer().getName() + "'s connection; they are not observed");
        }
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + " #" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
