package dev.whynotac.paper;

import dev.whynotac.ai.AiAdvisor;
import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.ServerClock;
import dev.whynotac.math.stats.RunningCovarianceMatrix;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.paper.ai.LiveProbeDriver;
import dev.whynotac.paper.cases.Alerts;
import dev.whynotac.paper.cases.CaseStore;
import dev.whynotac.paper.command.WhyNotCommand;
import dev.whynotac.paper.diagnostics.AlertStreamExporter;
import dev.whynotac.paper.diagnostics.Diagnostics;
import dev.whynotac.paper.diagnostics.HealthModel;
import dev.whynotac.paper.diagnostics.MetricsExporter;
import dev.whynotac.paper.diagnostics.TickCost;
import dev.whynotac.paper.identity.AuthPluginHook;
import dev.whynotac.paper.identity.ClientFacts;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.paper.network.PaperNetworkBridge;
import dev.whynotac.paper.privacy.SubjectRequests;
import dev.whynotac.paper.verdict.PaperVerdictBridge;
import dev.whynotac.paper.world.EntityVerifier;
import dev.whynotac.paper.world.InventoryVerifier;
import dev.whynotac.paper.world.SelfVerifier;
import dev.whynotac.paper.world.WorldVerifier;
import dev.whynotac.protocol.latency.LatencySnapshot;
import dev.whynotac.protocol.network.NetworkEnvelope;
import dev.whynotac.protocol.network.NetworkSecret;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.recording.write.RecordingStore;
import dev.whynotac.storage.StorageManager;
import dev.whynotac.storage.SubjectProfileStore;
import dev.whynotac.verdict.SecondVerifier;
import dev.whynotac.verdict.VerdictConfig;
import dev.whynotac.verdict.VerdictEngine;
import net.minecraft.SharedConstants;
import dev.whynotac.world.ClientEntitiesTracker;
import dev.whynotac.world.ClientInventoryTracker;
import dev.whynotac.world.ClientSelfTracker;
import dev.whynotac.world.Replicas;
import dev.whynotac.world.ItemPrototypes;
import dev.whynotac.world.ClientWorldTracker;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
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
    private VerdictEngine verdict;
    private PaperVerdictBridge verdictBridge;
    private StorageManager storage;
    private AiAdvisor aiAdvisor;
    private AuthPluginHook authPluginHook;
    private MetricsExporter metricsExporter;
    private AlertStreamExporter alertStreamExporter;
    private PaperNetworkBridge networkBridge;
    private CaseStore cases;
    private ClientWorldTracker worldTracker;
    private ClientEntitiesTracker entitiesTracker;
    private ClientInventoryTracker inventoryTracker;
    private ClientSelfTracker selfTracker;
    private dev.whynotac.world.ClientTagsTracker tagsTracker;
    private Alerts alerts;
    private final AtomicLong listenerErrors = new AtomicLong();
    private final TickCost transactionLoop = new TickCost();
    private Diagnostics diagnostics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int configuredThreads = getConfig().getInt("analysis.threads", 0);
        int threads = configuredThreads > 0
                ? configuredThreads
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        analysisExecutor = Executors.newFixedThreadPool(threads, daemonThreads("WhyNotAC Analysis"));
        registry = new SessionRegistry(analysisExecutor, SessionRegistry.DEFAULT_MAILBOX_CAPACITY, (session, event, error) -> {
            long count = listenerErrors.incrementAndGet();
            if (count <= 10 || Long.bitCount(count) == 1) {
                getLogger().log(Level.WARNING, "Analysis listener failed on " + session + " at " + event + " (error #" + count + ")", error);
            }
        });
        diagnostics = new Diagnostics();
        registry.addListener(diagnostics);
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
                verdictCase -> {
                    if (aiAdvisor == null || verdictCase.session() == null) {
                        return java.util.Optional.empty();
                    }
                    dev.whynotac.ai.BehavioralFeatureExtractor.Snapshot snap = aiAdvisor.extractor().snapshot(verdictCase.session());
                    dev.whynotac.ai.AiAdvisor.IntrinsicRiskAssessment intrinsic = aiAdvisor.assessIntrinsicRisk(snap);
                    dev.whynotac.ai.AiAdvisor.CasePriority prio = aiAdvisor.priorityOf(verdictCase.id());
                    java.util.UUID pid = verdictCase.session().identity() != null ? verdictCase.session().identity().playerId() : null;
                    dev.whynotac.ai.AccountBaselineModel.ShiftAssessment shift = pid == null
                            ? new dev.whynotac.ai.AccountBaselineModel.ShiftAssessment(true, 0.0, "", 0.0)
                            : aiAdvisor.baselineModel().assess(pid, snap);
                    dev.whynotac.ai.EValueProcess ep = pid == null ? null : aiAdvisor.probeScheduler().processFor(pid);
                    return java.util.Optional.of(new CaseStore.AiTelemetry(
                            prio != null ? prio.score0To100() : intrinsic.score0To100(),
                            intrinsic.score0To100(),
                            intrinsic.dominantSignal(),
                            shift.rmsZScore(),
                            shift.topFeature(),
                            ep != null ? ep.wealth() : 1.0));
                },
                getPluginMeta().getVersion(), getLogger());
        int caseRetentionDays = Math.max(1, getConfig().getInt("cases.retention-days", 90));
        cases.retain(caseRetentionDays);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> cases.retain(caseRetentionDays), 20L * 3600, 20L * 3600);
        // VAL-3: the server's monotonic clock against its wall clock, which client-clock's claims rest on
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> ServerClock.SERVER.sample(System.nanoTime(), System.currentTimeMillis()), 20L, 20L);
        alerts = new Alerts(this);
        VerdictConfig verdictConfig = PaperVerdictBridge.loadConfig(getConfig().getConfigurationSection("verdict"), getLogger());
        verdict = new VerdictEngine(verdictConfig, SecondVerifier.ReplaySource.fromRecordingPaths(
                session -> recorder == null ? java.util.Optional.empty() : recorder.listener().recordingFile(session)));
        long probeMinCooldown = Math.max(1L, getConfig().getLong("ai.active-probes.min-cooldown-ticks", 200L));
        long probeMaxJitter = Math.max(0L, getConfig().getLong("ai.active-probes.max-jitter-ticks", 400L));
        aiAdvisor = new AiAdvisor(verdict, new dev.whynotac.ai.ProbeScheduler(probeMinCooldown, probeMaxJitter, 0.001, 0x5A17_C0DEL));
        registry.addListener(aiAdvisor);
        // Register aiAdvisor first so CaseStore and AlertStreamExporter read populated AI triage scores on case open
        verdict.addCaseListener(aiAdvisor);
        verdict.addCaseListener(cases);
        verdict.addCaseListener(alerts);
        verdictBridge = new PaperVerdictBridge(this, verdictConfig);
        verdict.addEnforcementListener(verdictBridge);
        try {
            String buildTag = getPluginMeta().getVersion() + "-p" + SharedConstants.getProtocolVersion();
            storage = new StorageManager(getDataPath().resolve("storage"), buildTag, getLogger());
            storage.restoreFalseBanBudget(verdict.falseBanBudget(), verdictConfig.falseBanBudgetWindowMillis(), System.currentTimeMillis());
            verdict.banWaveQueue().attachJournal(getDataPath().resolve("storage").resolve("ban-wave-queue.jsonl"));
            verdict.addCaseListener(storage);
            verdict.addEnforcementListener(storage);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "WhyNotAC immutable storage audit ledger failed to initialize; suppressing active enforcement to SHADOW", e);
            verdict.circuitBreaker().setKillSwitch(true, "storage ledger error: " + e.getMessage());
        }
        getServer().getPluginManager().registerEvents(verdictBridge, this);
        Bukkit.getScheduler().runTaskTimer(this, verdictBridge::tickOcclusionVisibility, 10L, 10L);
        // The client's world, before every check that reads it (listeners see an event in registration order).
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        worldTracker = replicas.world();
        entitiesTracker = replicas.entities();
        inventoryTracker = replicas.inventory();
        selfTracker = replicas.self();
        tagsTracker = replicas.tags();
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

        boolean trustAuthPlugins = getConfig().getBoolean("identity.trust-auth-plugins", true);
        authPluginHook = new AuthPluginHook(trustAuthPlugins);
        List<String> authPlugins = AuthPluginHook.installedPluginNames();
        if (trustAuthPlugins && !authPlugins.isEmpty() && !ClientFacts.policy().authenticated()) {
            getLogger().info("WhyNotAC offline auth plugin integration active (" + String.join(", ", authPlugins)
                    + "): sessions upgrade to AUTH_PLUGIN_CONFIRMED strictly after login confirmation");
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                long nanoNow = System.nanoTime();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    interceptor.session(player).ifPresent(session ->
                            authPluginHook.checkAndUpgrade(session, player.getUniqueId(), player.getName(), nanoNow)
                                    .ifPresent(provider -> warmBaselineAsync(player.getUniqueId())));
                }
            }, 20L, 20L);
        }

        boolean healthWatchdog = getConfig().getBoolean("observability.health-watchdog", true);
        if (healthWatchdog) {
            MetricsExporter.HealthWatchdog watchdog = new MetricsExporter.HealthWatchdog();
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> watchdog.evaluateTransition(health()).ifPresent(msg -> {
                if (msg.contains("RECOVERED")) {
                    getLogger().info(msg);
                } else {
                    getLogger().warning(msg);
                }
            }), 100L, 100L);
        }
        int metricsPort = getConfig().getInt("observability.metrics-http-port", 0);
        String metricsAuthToken = getConfig().getString("observability.metrics-auth-token", "");
        if (metricsPort > 0) {
            try {
                metricsExporter = MetricsExporter.startLocalHttp("127.0.0.1", metricsPort, metricsAuthToken, this::metricsSnapshot);
                getLogger().info("WhyNotAC OpenMetrics endpoint listening on http://127.0.0.1:" + metricsExporter.boundPort() + "/metrics");
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "WhyNotAC failed to bind metrics HTTP port " + metricsPort, e);
            }
        }
        String alertStreamFile = getConfig().getString("observability.alert-stream-file", "alerts-stream.jsonl");
        String webhookUrl = getConfig().getString("observability.webhook-url", "");
        Path alertStreamPath = (alertStreamFile != null && !alertStreamFile.isBlank())
                ? getDataPath().resolve(alertStreamFile.trim())
                : null;
        if (alertStreamPath != null || (webhookUrl != null && !webhookUrl.isBlank())) {
            alertStreamExporter = new AlertStreamExporter(alertStreamPath, aiAdvisor, getLogger(), webhookUrl);
            verdict.addCaseListener(alertStreamExporter);
        }
        if (getConfig().getBoolean("network-bridge.enabled", false)) {
            String secretName = getConfig().getString("network-bridge.secret-file", "network-secret.key");
            long maxSkewMs = Math.max(1L, getConfig().getLong("network-bridge.max-clock-skew-seconds", 60L)) * 1000L;
            try {
                byte[] secret = NetworkSecret.loadOrCreate(getDataPath().resolve(secretName));
                networkBridge = new PaperNetworkBridge(
                        secret, maxSkewMs, registry::byPlayer, aiAdvisor.baselineModel(), verdict.banWaveQueue(),
                        frame -> {
                            if (!isEnabled()) {
                                return;
                            }
                            if (Bukkit.isPrimaryThread()) {
                                getServer().sendPluginMessage(this, NetworkEnvelope.CHANNEL, frame);
                            } else {
                                Bukkit.getScheduler().runTask(this, () -> {
                                    if (isEnabled()) {
                                        getServer().sendPluginMessage(this, NetworkEnvelope.CHANNEL, frame);
                                    }
                                });
                            }
                        },
                        System::currentTimeMillis, getLogger());
                networkBridge.setRemoteErasureHandler(subject -> {
                    aiAdvisor.forget(subject);
                    verdict.forgetSubject(subject, System.currentTimeMillis());
                    if (cases != null) {
                        cases.subject(subject, true, r -> { });
                    }
                    if (storage != null) {
                        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                            try {
                                storage.forgetSubject(subject, System.currentTimeMillis());
                            } catch (IOException ex) {
                                getLogger().log(Level.WARNING, "WhyNotAC remote GDPR erasure failed for " + subject, ex);
                            }
                        });
                    }
                });
                PaperNetworkBridge.registerBukkitChannels(this, networkBridge);
                getLogger().info("WhyNotAC Velocity companion network bridge enabled (" + NetworkEnvelope.CHANNEL + ")");
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "WhyNotAC failed to initialize network bridge secret", e);
            }
        }

        aiAdvisor.addCleanSessionObserver((playerId, identity, sessionSnapshot, updatedBaseline) -> {
            if (storage != null && !updatedBaseline.isEmpty()) {
                Map<String, SubjectProfileStore.FeatureSummary> summaries = new TreeMap<>();
                updatedBaseline.forEach((k, m) -> {
                    if (m.count() > 0 && Double.isFinite(m.mean())) {
                        double var = Double.isFinite(m.sampleVariance()) ? m.sampleVariance() : 0.0;
                        summaries.put(k, new SubjectProfileStore.FeatureSummary(m.count(), m.mean(), var));
                    }
                });
                RunningCovarianceMatrix cov = aiAdvisor.baselineModel().covarianceSnapshot(playerId);
                SubjectProfileStore.CovarianceSummary covSummary = (cov != null && cov.count() > 0)
                        ? new SubjectProfileStore.CovarianceSummary(cov.dim(), cov.count(), cov.mean(), cov.flatCoMoments())
                        : null;
                storage.saveBehavioralBaselineAsync(playerId, summaries, covSummary, System.currentTimeMillis());
            }
            if (networkBridge != null) {
                sessionSnapshot.features().forEach((name, moments) ->
                        networkBridge.sendProfileSummary(playerId, identity, name, moments, 0));
                if (sessionSnapshot.combatCovariance() != null && sessionSnapshot.combatCovariance().count() > 0) {
                    networkBridge.sendCovarianceSummary(playerId, identity, sessionSnapshot.combatCovariance());
                }
            }
        });

        if (getConfig().getBoolean("ai.active-probes.enabled", false)) {
            long probeTtl = Math.max(4L, getConfig().getLong("ai.active-probes.ttl-ticks", 10L));
            Bukkit.getScheduler().runTaskTimer(this, new LiveProbeDriver(this, interceptor, aiAdvisor, probeTtl), 5L, 5L);
            getLogger().info("WhyNotAC active behind-FOV design probes enabled (ttl=" + probeTtl + " ticks, DESIGN_STATISTIC)");
        }

        getServer().getPluginManager().registerEvents(this, this);
        WhyNotCommand command = new WhyNotCommand(interceptor, registry, diagnostics, this::stats, this::health,
                () -> MetricsExporter.formatOpenMetrics(metricsSnapshot()), cases,
                new WorldVerifier(interceptor, worldTracker, this), new EntityVerifier(interceptor, entitiesTracker, this),
                new InventoryVerifier(interceptor, inventoryTracker, ItemPrototypes.vanilla(), this), new SelfVerifier(interceptor, selfTracker, tagsTracker, this),
                new SubjectRequests(getDataPath().resolve("recordings"), getDataPath().resolve(RecordingSetup.SECRET_FILE), cases, storage,
                        subject -> {
                            aiAdvisor.forget(subject);
                            verdict.forgetSubject(subject, System.currentTimeMillis());
                            if (networkBridge != null) {
                                networkBridge.sendEnforcementNotice(subject, "GDPR_FORGET", "", "", 0L, "gdpr erasure");
                            }
                        }, this),
                verdict, storage, aiAdvisor, networkBridge, this::reloadConfiguration, this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("whynotac", "WhyNotAC diagnostics", List.of("wnac"), command));
        getLogger().info("WhyNotAC packet interceptor attached (" + threads + " analysis threads)");
    }

    @Override
    public void onDisable() {
        if (verdictBridge != null) {
            verdictBridge.restoreAllOccluded();
        }
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
        if (verdict != null) {
            verdict.close();
        }
        if (storage != null) {
            storage.close();
        }
        if (cases != null) {
            cases.close();
        }
        if (metricsExporter != null) {
            metricsExporter.close();
        }
        if (alertStreamExporter != null) {
            alertStreamExporter.close();
        }
    }

    /** Hot-reloads {@code config.yml} verdict settings without dropping active sessions. */
    public String reloadConfiguration(String operator) {
        reloadConfig();
        VerdictConfig updated = PaperVerdictBridge.loadConfig(getConfig().getConfigurationSection("verdict"), getLogger());
        if (verdict != null) {
            verdict.updateConfig(updated);
        }
        if (verdictBridge != null) {
            verdictBridge.updateConfig(updated);
        }
        String summary = String.format(Locale.ROOT,
                "armed=%d (%s) mitigations=%b hideInfo=%b autoKick=%b autoBan=%b",
                updated.armedChecks().size(),
                String.join(",", updated.armedChecks()),
                updated.mitigationsEnabled(),
                updated.hidesInformationEnabled(),
                updated.autoKickEnabled(),
                updated.autoBanEnabled());
        if (storage != null) {
            storage.recordConfigReloadAsync(operator, summary, System.currentTimeMillis());
        }
        getLogger().info("WhyNotAC configuration reloaded by " + operator + ": " + summary);
        return summary;
    }

    /** One line of JSON for {@code /whynotac stats}: totals since start-up, for operators and the load test. */
    private String stats() {
        Diagnostics.Totals totals = diagnostics.totals();
        long now = System.nanoTime();
        int pendingMax = 0;
        long withheld = 0;
        long budgetYields = 0;
        for (PlayerSession session : registry.sessions()) {
            LatencySnapshot latency = session.latency(now);
            pendingMax = Math.max(pendingMax, latency.pending());
            withheld += latency.withheld() ? 1 : 0;
            budgetYields += session.budgetYields();
        }
        StringBuilder margins = new StringBuilder();
        verdict.margins().forEach((check, m) -> margins.append(margins.isEmpty() ? "" : ",").append(String.format(Locale.ROOT,
                "\"%s\":{\"observed\":%d,\"smallest\":%s}", check, m.observed(), Double.isFinite(m.smallest()) ? String.format(Locale.ROOT, "%.6f", m.smallest()) : "null")));
        StringBuilder anomalies = new StringBuilder();
        for (Map.Entry<?, Long> entry : totals.anomalies().entrySet()) {
            anomalies.append(anomalies.isEmpty() ? "" : ",").append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        HealthModel.Evaluation healthEval = health();
        return String.format(Locale.ROOT, "{\"sessionsLive\":%d,\"queuedEvents\":%d,\"sessions\":%d,\"ended\":%d,\"inbound\":%d,"
                        + "\"outbound\":%d,\"lostEvents\":%d,\"inboundDivergences\":%d,\"outboundDivergences\":%d,\"anomalies\":{%s},"
                        + "\"pendingTransactionsMax\":%d,\"withheldNow\":%d,\"budgetYieldsLive\":%d,\"sessionGapBursts\":%d,\"listenerErrors\":%d,\"recordingRejectedBlocks\":%d,"
                        + "\"recordingFailed\":%d,\"transactionLoop\":{\"runs\":%d,\"meanWallMicros\":%.3f,\"meanCpuMicros\":%.3f,\"maxWallMicros\":%.3f},"
                        + "\"cases\":{\"opened\":%d,\"stored\":%d,\"dropped\":%d,\"rejectedCandidates\":%d},\"margins\":{%s},\"serverClock\":{\"divergence\":%s,\"trusted\":%b},"
                        + "\"world\":{\"retainedBytes\":%d,\"sharedSections\":%d,\"worlds\":%d},"
                        + "\"entities\":{\"ticks\":%d,\"branches\":%d,\"merges\":%d,\"overflows\":%d,\"losses\":%d},"
                        + "\"banWavePending\":%d,\"health\":%s}",
                registry.sessions().size(), registry.queuedEvents(), totals.sessions(), totals.ended(), totals.inbound(),
                totals.outbound(), totals.lostEvents(), totals.inboundDivergences(), totals.outboundDivergences(), anomalies,
                pendingMax, withheld, budgetYields, aiAdvisor == null ? 0L : aiAdvisor.sessionGapBursts(), listenerErrors.get(), recordings == null ? 0 : recordings.rejectedBlocks(),
                recordings == null ? 0 : recordings.failedRecordings(), transactionLoop.runs(), transactionLoop.meanWallMicros(),
                transactionLoop.meanCpuMicros(), transactionLoop.maxWallMicros(),
                verdict.cases(), cases == null ? 0 : cases.storedCount(), cases == null ? 0 : cases.droppedCount(),
                verdict.rejections().size(), margins,
                Double.isNaN(ServerClock.SERVER.divergence()) ? "null" : String.format(Locale.ROOT, "%.6f", ServerClock.SERVER.divergence()),
                ServerClock.SERVER.trusted(), worldTracker == null ? 0 : worldTracker.retainedBytes(),
                worldTracker == null ? 0 : worldTracker.sharedSections(), worldTracker == null ? 0 : worldTracker.worldsCreated(),
                entitiesTracker == null ? 0 : entitiesTracker.totals().ticks(), entitiesTracker == null ? 0 : entitiesTracker.totals().branches(),
                entitiesTracker == null ? 0 : entitiesTracker.totals().merges(), entitiesTracker == null ? 0 : entitiesTracker.totals().overflows(),
                entitiesTracker == null ? 0 : entitiesTracker.totals().losses(),
                verdict.banWaveQueue().pendingCount(), healthEval.toJson());
    }

    /** Operational health evaluation (OBS-01) for {@code /whynotac health} and {@code /whynotac stats}. */
    private HealthModel.Evaluation health() {
        Diagnostics.Totals totals = diagnostics.totals();
        long nowMs = System.currentTimeMillis();
        var cb = verdict.circuitBreaker().status(nowMs);
        var fb = verdict.falseBanBudget().snapshot(nowMs);
        return HealthModel.evaluate(new HealthModel.Inputs(
                registry.queuedEvents(),
                SessionRegistry.DEFAULT_GLOBAL_QUEUE_CAPACITY,
                totals.lostEvents(),
                listenerErrors.get(),
                recordings == null ? 0L : recordings.failedRecordings(),
                recordings == null ? 0L : recordings.rejectedBlocks(),
                cb.tripped(),
                cb.killSwitchActive(),
                fb.locked(),
                transactionLoop.recentWallMicros(),
                storage != null));
    }

    private MetricsExporter.Snapshot metricsSnapshot() {
        Diagnostics.Totals totals = diagnostics.totals();
        long liveBudgetYields = 0L;
        for (PlayerSession session : registry.sessions()) {
            liveBudgetYields += session.budgetYields();
        }
        return new MetricsExporter.Snapshot(
                registry.sessions().size(),
                totals.sessions(),
                registry.queuedEvents(),
                totals.inbound(),
                totals.outbound(),
                totals.lostEvents(),
                totals.inboundDivergences(),
                totals.outboundDivergences(),
                listenerErrors.get(),
                verdict.cases(),
                cases == null ? 0L : cases.storedCount(),
                cases == null ? 0L : cases.droppedCount(),
                recordings == null ? 0L : recordings.failedRecordings(),
                recordings == null ? 0L : recordings.rejectedBlocks(),
                verdict.banWaveQueue().pendingCount(),
                transactionLoop.meanWallMicros(),
                transactionLoop.maxWallMicros(),
                verdict.margins(),
                health(),
                liveBudgetYields,
                aiAdvisor == null ? 0L : aiAdvisor.sessionGapBursts());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (alerts != null) {
            alerts.forget(event.getPlayer().getName());
        }
        if (interceptor != null && authPluginHook != null) {
            interceptor.session(event.getPlayer()).ifPresent(s -> authPluginHook.forgetConnection(s.connectionId()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var bound = interceptor.bind(event.getPlayer());
        if (bound.isEmpty()) {
            getLogger().warning("No WhyNotAC session on " + event.getPlayer().getName() + "'s connection; they are not observed");
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        if (ClientFacts.policy().authenticated()) {
            warmBaselineAsync(uuid);
        } else if (authPluginHook != null) {
            authPluginHook.checkAndUpgrade(bound.get(), uuid, event.getPlayer().getName(), System.nanoTime())
                    .ifPresent(provider -> warmBaselineAsync(uuid));
        }
    }

    private void warmBaselineAsync(UUID playerId) {
        if (storage == null || aiAdvisor == null || playerId == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                storage.profiles().find(playerId).ifPresent(profile -> {
                    if (!profile.baselineFeatures().isEmpty()) {
                        Map<String, RunningMoments> restored = new LinkedHashMap<>();
                        profile.baselineFeatures().forEach((name, fs) ->
                                restored.put(name, RunningMoments.fromSummary(fs.count(), fs.mean(), fs.sampleVariance())));
                        aiAdvisor.baselineModel().seedBaselineIfEmpty(playerId, restored);
                    }
                    SubjectProfileStore.CovarianceSummary cov = profile.combatCovariance();
                    if (cov != null && cov.dim() > 0 && cov.count() > 0) {
                        aiAdvisor.baselineModel().seedCovarianceIfEmpty(playerId,
                                RunningCovarianceMatrix.fromSummary(cov.dim(), cov.count(), cov.mean(), cov.flatCoMoments()));
                    }
                });
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "WhyNotAC could not load persisted baseline for " + playerId, e);
            }
        });
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
