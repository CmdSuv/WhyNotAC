package dev.whynotac.paper.network;

import dev.whynotac.ai.AccountBaselineModel;
import dev.whynotac.ai.BehavioralFeatureExtractor;
import dev.whynotac.math.stats.RunningCovarianceMatrix;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.network.NetworkEnvelope;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.verdict.BanWaveQueue;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Paper-side authenticated companion channel for Velocity ({@code whynotac:bridge}, NET-01 / NET-22).
 */
public final class PaperNetworkBridge implements PluginMessageListener {

    @FunctionalInterface
    public interface FrameSender {
        void send(byte[] frame);
    }

    private final byte[] secret;
    private final long maxSkewMillis;
    private final Function<UUID, Optional<PlayerSession>> sessionLookup;
    private final AccountBaselineModel baselineModel;
    private final BanWaveQueue banWaveQueue;
    private final FrameSender frameSender;
    private final LongSupplier wallClockMillis;
    private final Logger logger;
    private volatile Consumer<UUID> remoteErasureHandler;
    private volatile RedisClusterBus clusterBus;

    private final AtomicLong receivedValid = new AtomicLong();
    private final AtomicLong rejectedInvalid = new AtomicLong();
    private final AtomicLong sentFrames = new AtomicLong();

    public PaperNetworkBridge(
            byte[] secret,
            long maxSkewMillis,
            Function<UUID, Optional<PlayerSession>> sessionLookup,
            AccountBaselineModel baselineModel,
            BanWaveQueue banWaveQueue,
            FrameSender frameSender,
            LongSupplier wallClockMillis,
            Logger logger) {
        this.secret = secret.clone();
        this.maxSkewMillis = maxSkewMillis;
        this.sessionLookup = sessionLookup;
        this.baselineModel = baselineModel;
        this.banWaveQueue = banWaveQueue;
        this.frameSender = frameSender;
        this.wallClockMillis = wallClockMillis;
        this.logger = logger;
    }

    public static void registerBukkitChannels(Plugin plugin, PaperNetworkBridge bridge) {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, NetworkEnvelope.CHANNEL, bridge);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, NetworkEnvelope.CHANNEL);
    }

    /** Optional hook run when a signed remote {@code GDPR_FORGET} notice arrives from another backend via proxy. */
    public void setRemoteErasureHandler(Consumer<UUID> handler) {
        this.remoteErasureHandler = handler;
    }

    /** Attaches an optional Redis Pub/Sub cluster bus so broadcasts also reach empty backends. */
    public void attachClusterBus(RedisClusterBus bus) {
        this.clusterBus = bus;
    }

    private void broadcastFrame(byte[] frame) {
        frameSender.send(frame);
        RedisClusterBus bus = this.clusterBus;
        if (bus != null) {
            bus.publish(frame);
        }
        sentFrames.incrementAndGet();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!NetworkEnvelope.CHANNEL.equals(channel)) {
            return;
        }
        handleInboundFrame(message);
    }

    public boolean handleInboundFrame(byte[] frame) {
        try {
            long nowMs = wallClockMillis.getAsLong();
            NetworkEnvelope.Payload payload = NetworkEnvelope.decodeAndVerify(frame, secret, nowMs, maxSkewMillis);
            receivedValid.incrementAndGet();
            long nano = System.nanoTime();
            switch (payload) {
                case NetworkEnvelope.ProxyClientEdge edge -> sessionLookup.apply(edge.playerId()).ifPresent(session -> {
                    session.onFact(Fact.IDENTITY, edge.identity().name(),
                            NetworkEnvelope.isLongLivedAuthenticated(edge.identity()) ? Trust.AUTHENTIC : Trust.CLAIMED,
                            "signed velocity companion", nano);
                    session.onFact(Fact.NATIVE_PROTOCOL, Integer.toString(edge.clientProtocol()),
                            Trust.FORMAT_BOUND, "signed velocity companion", nano);
                    session.onFact(Fact.TRANSLATOR, edge.translator(),
                            Trust.FORMAT_BOUND, "signed velocity companion", nano);
                    session.onFact(Fact.TRANSACTION_ORIGIN, edge.transactionOrigin(),
                            Trust.FORMAT_BOUND, "signed velocity companion", nano);
                    if (edge.bedrock()) {
                        session.onFact(Fact.BEDROCK, "true", Trust.AUTHENTIC, "signed velocity companion", nano);
                    }
                });
                case NetworkEnvelope.ProfileSummarySync sync -> {
                    if (baselineModel != null && NetworkEnvelope.isLongLivedAuthenticated(sync.identity())) {
                        RunningMoments rm = RunningMoments.fromSummary(sync.sampleCount(), sync.mean(), sync.sampleVariance());
                        baselineModel.mergeSession(sync.playerId(),
                                new BehavioralFeatureExtractor.Snapshot(Map.of(sync.featureName(), rm)));
                    }
                }
                case NetworkEnvelope.CovarianceSummarySync cov -> {
                    if (baselineModel != null && NetworkEnvelope.isLongLivedAuthenticated(cov.identity())
                            && cov.dim() == BehavioralFeatureExtractor.COMBAT_COVARIANCE_DIM) {
                        RunningCovarianceMatrix matrix = RunningCovarianceMatrix.fromSummary(
                                cov.dim(), cov.sampleCount(), cov.mean(), cov.flatCoMoments());
                        baselineModel.mergeSession(cov.playerId(),
                                new BehavioralFeatureExtractor.Snapshot(Map.of(), 0.0, matrix));
                    }
                }
                case NetworkEnvelope.EnforcementNotice notice -> {
                    if (banWaveQueue != null && ("OVERTURN".equals(notice.kind()) || "GDPR_FORGET".equals(notice.kind()))) {
                        banWaveQueue.cancelSubject(notice.playerId(), notice.issuedAtMillis());
                    }
                    if ("GDPR_FORGET".equals(notice.kind())) {
                        if (baselineModel != null) {
                            baselineModel.forget(notice.playerId());
                        }
                        Consumer<UUID> erasure = remoteErasureHandler;
                        if (erasure != null) {
                            erasure.accept(notice.playerId());
                        }
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            long count = rejectedInvalid.incrementAndGet();
            if (logger != null && (count <= 5 || Long.bitCount(count) == 1)) {
                logger.log(Level.WARNING, "Rejected invalid whynotac:bridge frame (#" + count + "): " + e.getMessage());
            }
            return false;
        }
    }

    /** Signs and transmits an authenticated profile summary to the proxy for cross-server sync. */
    public void sendProfileSummary(UUID playerId, Identity identity, String featureName, RunningMoments moments, int falseBanIncidents) {
        if (!NetworkEnvelope.isLongLivedAuthenticated(identity) || moments == null || moments.count() == 0) {
            return;
        }
        byte[] frame = NetworkEnvelope.encode(new NetworkEnvelope.ProfileSummarySync(
                wallClockMillis.getAsLong(), playerId, identity, featureName,
                moments.count(), moments.mean(), moments.sampleVariance(), falseBanIncidents), secret);
        broadcastFrame(frame);
    }

    /** Signs and transmits an authenticated multivariate combat covariance summary to the proxy for cross-server sync. */
    public void sendCovarianceSummary(UUID playerId, Identity identity, RunningCovarianceMatrix cov) {
        if (!NetworkEnvelope.isLongLivedAuthenticated(identity) || cov == null || cov.count() <= 0) {
            return;
        }
        byte[] frame = NetworkEnvelope.encode(new NetworkEnvelope.CovarianceSummarySync(
                wallClockMillis.getAsLong(), playerId, identity, cov.dim(), cov.count(), cov.mean(), cov.flatCoMoments()), secret);
        broadcastFrame(frame);
    }

    /** Signs and transmits an enforcement/appeal/erasure broadcast across the proxy network. */
    public void sendEnforcementNotice(UUID playerId, String kind, String caseId, String digest, long durationMs, String reason) {
        byte[] frame = NetworkEnvelope.encode(new NetworkEnvelope.EnforcementNotice(
                wallClockMillis.getAsLong(), playerId, kind, caseId, digest, durationMs, reason), secret);
        broadcastFrame(frame);
    }

    public long receivedValid() {
        return receivedValid.get();
    }

    public long rejectedInvalid() {
        return rejectedInvalid.get();
    }

    public long sentFrames() {
        return sentFrames.get();
    }
}
