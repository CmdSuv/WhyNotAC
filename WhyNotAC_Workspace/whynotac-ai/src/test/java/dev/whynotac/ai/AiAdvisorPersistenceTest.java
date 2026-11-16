package dev.whynotac.ai;

import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AiAdvisorPersistenceTest {

    @Test
    void firesCleanObserverOnlyForAuthenticatedCleanSessionsAndSeedsWithoutDoubleCounting() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 4096, (s, e, err) -> {
            throw new AssertionError("Listener error", err);
        });
        AiAdvisor advisor = new AiAdvisor();
        registry.addListener(advisor);

        List<UUID> persistedIds = new ArrayList<>();
        advisor.addCleanSessionObserver((id, identity, snap, updated) -> persistedIds.add(id));

        // 1) Unauthenticated offline session disconnects -> must NOT fire persistent/network sync observer
        UUID unauthedPlayer = UUID.randomUUID();
        PlayerSession sUnauth = registry.open("127.0.0.1:100");
        sUnauth.bind(unauthedPlayer, "Offline", 1, 100L);
        sUnauth.onFact(Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "offline", 110L);
        feedTicks(sUnauth, 20);
        sUnauth.disconnect(2_000_000_000L);
        assertTrue(persistedIds.isEmpty());

        // 2) Authenticated session disconnects -> fires persistence/sync observer
        UUID authedPlayer = UUID.randomUUID();
        PlayerSession sAuth = registry.open("127.0.0.1:101");
        sAuth.bind(authedPlayer, "MojangPlayer", 2, 100L);
        sAuth.onFact(Fact.IDENTITY, Identity.AUTHENTICATED_MOJANG.name(), Trust.AUTHENTIC, "online", 110L);
        feedTicks(sAuth, 25);
        sAuth.disconnect(3_000_000_000L);
        assertEquals(List.of(authedPlayer), persistedIds);

        // 3) Seeding baseline on restart restores stats, without overwriting non-empty in-memory state
        AccountBaselineModel restarted = new AccountBaselineModel(10, 5);
        RunningMoments persisted = RunningMoments.fromSummary(25L, 50.0, 4.0);
        restarted.seedBaselineIfEmpty(authedPlayer, Map.of(BehavioralFeatureExtractor.TICK_DELTA_MS, persisted));
        assertEquals(25L, restarted.baselineSnapshot(authedPlayer).get(BehavioralFeatureExtractor.TICK_DELTA_MS).count());
        assertFalse(restarted.baselineSnapshot(authedPlayer).isEmpty());
        // Calling seedBaselineIfEmpty again must be a no-op
        restarted.seedBaselineIfEmpty(authedPlayer,
                Map.of(BehavioralFeatureExtractor.TICK_DELTA_MS, RunningMoments.fromSummary(999L, 1.0, 1.0)));
        assertEquals(25L, restarted.baselineSnapshot(authedPlayer).get(BehavioralFeatureExtractor.TICK_DELTA_MS).count());
    }

    private static void feedTicks(PlayerSession s, int count) {
        long nano = 1_000_000_000L;
        for (int i = 0; i < count; i++) {
            s.onInbound(new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, i * 2.0f, 0.0f, false, true, true, false), nano);
            if (i % 5 == 0) {
                s.onInbound(new ServerboundPacket.InteractEntity(3, ServerboundPacket.InteractEntity.Kind.ATTACK, Hand.MAIN_HAND, Vec3d.ZERO, false), nano + 50_000L);
            }
            s.onInbound(new ServerboundPacket.ClientTickEnd(), nano + 50_000_000L);
            nano += 50_000_000L;
        }
    }
}
