package dev.whynotac.paper.network;

import dev.whynotac.ai.AccountBaselineModel;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.network.NetworkEnvelope;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import dev.whynotac.verdict.BanWaveQueue;
import dev.whynotac.verdict.CircuitBreaker;
import dev.whynotac.verdict.FalseBanBudget;
import dev.whynotac.verdict.VerdictConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PaperNetworkBridgeTest {

    private static final byte[] KEY = "bridge-test-secret-key-256bits!!".getBytes(StandardCharsets.UTF_8);

    @Test
    void authenticatesProxyEdgeSyncsBaselinesCancelsOnNoticeAndRejectsTampering() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, err) -> { });
        List<TimelineEvent> events = new ArrayList<>();
        registry.addListener((s, e) -> events.add(e));

        UUID playerId = UUID.randomUUID();
        PlayerSession session = registry.open("127.0.0.1:25565");
        session.bind(playerId, "ProxyPlayer", 1, 100L);

        AccountBaselineModel baseline = new AccountBaselineModel(10, 5);
        BanWaveQueue wave = new BanWaveQueue(VerdictConfig.shadowDefaults(), new CircuitBreaker(5),
                new FalseBanBudget(1, TimeUnit.DAYS.toMillis(1)));
        List<byte[]> outbound = new ArrayList<>();
        long now = 1_700_000_000_000L;

        PaperNetworkBridge bridge = new PaperNetworkBridge(
                KEY, 60_000L, id -> id.equals(playerId) ? Optional.of(session) : Optional.empty(),
                baseline, wave, outbound::add, () -> now, Logger.getLogger("test"));

        byte[] edgeFrame = NetworkEnvelope.encode(new NetworkEnvelope.ProxyClientEdge(
                now, playerId, 774, "none", "CLIENT", Identity.AUTHENTICATED_BY_PROXY, false), KEY);
        assertTrue(bridge.handleInboundFrame(edgeFrame));
        assertTrue(events.stream().anyMatch(e -> e instanceof TimelineEvent.ClientFact f
                && f.fact() == TimelineEvent.ClientFact.Fact.IDENTITY
                && "AUTHENTICATED_BY_PROXY".equals(f.value())));

        // Sync authenticated profile moments across servers
        RunningMoments stats = new RunningMoments();
        for (int i = 0; i < 40; i++) {
            stats.add(5.0 + (i % 3) * 0.1);
        }
        bridge.sendProfileSummary(playerId, Identity.AUTHENTICATED_BY_PROXY, "click_interval_ticks", stats, 0);
        assertEquals(1, outbound.size());
        assertTrue(bridge.handleInboundFrame(outbound.getFirst()));
        assertEquals(40L, baseline.baselineSnapshot(playerId).get("click_interval_ticks").count());

        // GDPR_FORGET notice wipes synced baseline
        byte[] forgetFrame = NetworkEnvelope.encode(new NetworkEnvelope.EnforcementNotice(
                now, playerId, "GDPR_FORGET", "", "", 0L, "gdpr"), KEY);
        assertTrue(bridge.handleInboundFrame(forgetFrame));
        assertTrue(baseline.baselineSnapshot(playerId).isEmpty());

        // Tampered frame rejected safely
        byte[] tampered = edgeFrame.clone();
        tampered[5] ^= 0x55;
        assertFalse(bridge.handleInboundFrame(tampered));
        assertEquals(1L, bridge.rejectedInvalid());
        wave.close();
    }
}
