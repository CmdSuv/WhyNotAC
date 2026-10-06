package dev.whynotac.paper;

import dev.whynotac.paper.command.WhyNotCommand;
import dev.whynotac.paper.diagnostics.Diagnostics;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.protocol.session.SessionRegistry;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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

/** Phase 1 entry point: packet interception, per-player timelines and transaction latency tracking. */
public final class WhyNotAcPlugin extends JavaPlugin implements Listener {

    private ExecutorService analysisExecutor;
    private SessionRegistry registry;
    private PacketInterceptor interceptor;
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
