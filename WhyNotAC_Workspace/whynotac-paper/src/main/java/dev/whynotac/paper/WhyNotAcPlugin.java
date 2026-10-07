package dev.whynotac.paper;

import dev.whynotac.paper.command.WhyNotCommand;
import dev.whynotac.paper.diagnostics.Diagnostics;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.recording.write.RecordingStore;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
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
import org.bukkit.plugin.java.JavaPlugin;

/** Entry point: packet interception, per-player timelines, transaction latency tracking and (optional) session recording. */
public final class WhyNotAcPlugin extends JavaPlugin implements Listener {

    private ExecutorService analysisExecutor;
    private SessionRegistry registry;
    private PacketInterceptor interceptor;
    private RecordingStore recordings;
    private final AtomicLong listenerErrors = new AtomicLong();

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
        Diagnostics diagnostics = new Diagnostics();
        registry.addListener(diagnostics);
        saveDefaultConfig();
        try {
            recordings = RecordingSetup.install(getConfig().getConfigurationSection("recording"), getDataPath(), registry,
                    getPluginMeta().getVersion(), getLogger()).orElse(null);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Session recording could not start; continuing without it", e);
        }
        if (recordings != null) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, recordings::scheduleRetention, 20L * 3600, 20L * 3600);
        }

        interceptor = new PacketInterceptor(registry, getLogger());
        interceptor.install();
        Bukkit.getScheduler().runTask(this, interceptor::injectExisting); // connections still pending at install time
        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(interceptor::bind));
        // One stand-alone transaction per server tick keeps a continuous, per-tick latency trace.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                interceptor.sendTransaction(player);
            }
        }, 1L, 1L);
        // Another plugin may insert a handler outside the edge taps after login; move them back (every 5 s).
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                interceptor.auditPlacement(player);
            }
        }, 100L, 100L);

        getServer().getPluginManager().registerEvents(this, this);
        WhyNotCommand command = new WhyNotCommand(interceptor, registry, diagnostics);
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
