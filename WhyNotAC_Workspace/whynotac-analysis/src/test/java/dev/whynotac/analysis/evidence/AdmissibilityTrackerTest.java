package dev.whynotac.analysis.evidence;

import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdmissibilityTrackerTest {

    @Test
    void upgradesToAuthPluginConfirmedStrictlyAfterLoginSequence() {
        AdmissibilityTracker tracker = new AdmissibilityTracker();
        long seq = 0;
        tracker.onEvent(new Observation(seq++, 10L, Source.WIRE, Source.WIRE, 774, "wire"));
        tracker.onEvent(new ClientFact(seq++, 20L, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        tracker.onEvent(new ClientFact(seq++, 30L, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        tracker.onEvent(new ClientFact(seq++, 40L, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        tracker.onEvent(new ClientFact(seq++, 50L, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "offline"));

        // Pre-login window stays UNAUTHENTICATED
        Admissibility preLogin = tracker.evaluate(10L);
        assertTrue(preLogin.admissible());
        assertEquals(IdentityTier.UNAUTHENTICATED, preLogin.identity());

        // Login confirmation arrives at seq = 20
        long loginSeq = 20L;
        tracker.onEvent(new ClientFact(loginSeq, 60L, Fact.IDENTITY, "AUTH_PLUGIN_CONFIRMED", Trust.AUTHENTIC, "AuthMe"));

        // A window straddling login (10..25) must remain UNAUTHENTICATED
        Admissibility straddling = tracker.evaluate(10L);
        assertTrue(straddling.admissible());
        assertEquals(IdentityTier.UNAUTHENTICATED, straddling.identity());

        // Windows starting at or after login confirmation carry AUTH_PLUGIN_CONFIRMED
        Admissibility postLogin = tracker.evaluate(loginSeq);
        assertTrue(postLogin.admissible());
        assertEquals(IdentityTier.AUTH_PLUGIN_CONFIRMED, postLogin.identity());
    }

    @Test
    void claimedIdentityNeverElevatesAboveUnauthenticated() {
        AdmissibilityTracker tracker = new AdmissibilityTracker();
        tracker.onEvent(new Observation(0L, 10L, Source.WIRE, Source.WIRE, 774, "wire"));
        tracker.onEvent(new ClientFact(1L, 20L, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        tracker.onEvent(new ClientFact(2L, 30L, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        tracker.onEvent(new ClientFact(3L, 40L, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        tracker.onEvent(new ClientFact(4L, 50L, Fact.IDENTITY, "AUTH_PLUGIN_CONFIRMED", Trust.CLAIMED, "spoofed"));

        Admissibility result = tracker.evaluate(10L);
        assertTrue(result.admissible());
        assertEquals(IdentityTier.UNAUTHENTICATED, result.identity());
    }

    @Test
    void evaluateTranslatorSafeAllowsKnownJavaTranslatorWhileRejectingStrictAndBedrock() {
        AdmissibilityTracker tracker = new AdmissibilityTracker();
        tracker.onEvent(new Observation(0L, 10L, Source.WIRE, Source.WIRE, 774, "wire"));
        tracker.onEvent(new ClientFact(1L, 20L, Fact.NATIVE_PROTOCOL, "773", Trust.FORMAT_BOUND, "ViaVersion"));
        tracker.onEvent(new ClientFact(2L, 30L, Fact.TRANSLATOR, "viaversion", Trust.FORMAT_BOUND, "ViaVersion"));
        tracker.onEvent(new ClientFact(3L, 40L, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        tracker.onEvent(new ClientFact(4L, 50L, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "mojang"));

        // Strict evaluate rejects non-774 / translator
        org.junit.jupiter.api.Assertions.assertFalse(tracker.evaluate(10L).admissible());
        // Translator-safe evaluate accepts known Java translator with WIRE + CLIENT transactions
        Admissibility safe = tracker.evaluateTranslatorSafe(10L);
        assertTrue(safe.admissible());
        assertEquals(IdentityTier.AUTHENTICATED, safe.identity());

        // Bedrock is still rejected by evaluateTranslatorSafe
        tracker.onEvent(new ClientFact(5L, 60L, Fact.BEDROCK, "true", Trust.FORMAT_BOUND, "Floodgate"));
        org.junit.jupiter.api.Assertions.assertFalse(tracker.evaluateTranslatorSafe(10L).admissible());
    }
}
