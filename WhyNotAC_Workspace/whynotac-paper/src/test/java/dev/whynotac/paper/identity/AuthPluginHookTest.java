package dev.whynotac.paper.identity;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthPluginHookTest {

    @Test
    void emitsAuthPluginConfirmedOnceOnLoginAndRespectsDisabledFlag() {
        AtomicBoolean authed = new AtomicBoolean(false);
        AuthPluginHook disabledHook = new AuthPluginHook(false, List.of((id, name) -> Optional.of("AuthMe")));

        AdmissibilityTracker tracker = new AdmissibilityTracker();
        SessionRegistry reg = new SessionRegistry(Runnable::run, 64, (s, e, err) -> {
            throw new AssertionError("Listener error", err);
        });
        reg.addListener((s, e) -> tracker.onEvent(e));

        PlayerSession session = reg.open("127.0.0.1:25565");
        UUID playerId = UUID.randomUUID();
        session.onObservation(Source.WIRE, Source.WIRE, 774, "wire", 1L);
        session.onFact(Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "edge", 2L);
        session.onFact(Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "vanilla", 3L);
        session.onFact(Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "client", 4L);
        session.onFact(Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "offline", 5L);

        assertTrue(disabledHook.checkAndUpgrade(session, playerId, "OfflinePlayer", 6L).isEmpty());

        AuthPluginHook hook = new AuthPluginHook(true, List.of(
                (id, name) -> { throw new RuntimeException("broken secondary plugin"); },
                (id, name) -> authed.get() ? Optional.of("AuthMe") : Optional.empty()
        ));

        // Before /login completes -> fail-open, stays UNAUTHENTICATED
        assertTrue(hook.checkAndUpgrade(session, playerId, "OfflinePlayer", 10L).isEmpty());
        assertFalse(hook.isConfirmed(session));
        Admissibility beforeLogin = tracker.evaluate(100L);
        assertEquals(IdentityTier.UNAUTHENTICATED, beforeLogin.identity());

        // Player completes /login -> AuthMe probe confirms; emits AUTH_PLUGIN_CONFIRMED once
        authed.set(true);
        Optional<String> provider = hook.checkAndUpgrade(session, playerId, "OfflinePlayer", 20L);
        assertEquals(Optional.of("AuthMe"), provider);
        assertTrue(hook.isConfirmed(session));
        // Second call is idempotent (no duplicate fact emission)
        assertTrue(hook.checkAndUpgrade(session, playerId, "OfflinePlayer", 30L).isEmpty());

        // New window opened after confirmation has AUTH_PLUGIN_CONFIRMED tier
        long seqNow = ((TimelineEvent) new Observation(0, 0, Source.WIRE, Source.WIRE, 774, "")).sequence();
        Admissibility afterLogin = tracker.evaluate(10L);
        assertEquals(IdentityTier.AUTH_PLUGIN_CONFIRMED, afterLogin.identity());
        assertEquals(0L, seqNow);

        hook.forgetConnection(session.connectionId());
        assertFalse(hook.isConfirmed(session));
    }
}
