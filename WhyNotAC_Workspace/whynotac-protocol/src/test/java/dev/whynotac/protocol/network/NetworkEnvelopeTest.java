package dev.whynotac.protocol.network;

import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NetworkEnvelopeTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsAllPayloadTypesAndRejectsTamperingOrReplay() throws IOException {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000_000L;

        NetworkEnvelope.ProxyClientEdge edge = new NetworkEnvelope.ProxyClientEdge(
                now, player, 774, "none", "CLIENT", Identity.AUTHENTICATED_BY_PROXY, false);
        byte[] encodedEdge = NetworkEnvelope.encode(edge, KEY);
        assertEquals(edge, NetworkEnvelope.decodeAndVerify(encodedEdge, KEY, now + 500L, 60_000L));

        NetworkEnvelope.ProfileSummarySync sync = new NetworkEnvelope.ProfileSummarySync(
                now, player, Identity.AUTHENTICATED_MOJANG, "click_interval_ticks", 120L, 4.5, 0.25, 0);
        assertEquals(sync, NetworkEnvelope.decodeAndVerify(NetworkEnvelope.encode(sync, KEY), KEY, now, 60_000L));

        NetworkEnvelope.EnforcementNotice notice = new NetworkEnvelope.EnforcementNotice(
                now, player, "TEMP_BAN", "case-1", "deadbeef", 86_400_000L, "corroborated");
        assertEquals(notice, NetworkEnvelope.decodeAndVerify(NetworkEnvelope.encode(notice, KEY), KEY, now, 60_000L));

        // Tamper 1 byte -> HMAC fails
        byte[] tampered = encodedEdge.clone();
        tampered[10] ^= 0x01;
        assertThrows(IOException.class, () -> NetworkEnvelope.decodeAndVerify(tampered, KEY, now, 60_000L));

        // Expired replay timestamp -> rejected
        assertThrows(IOException.class, () -> NetworkEnvelope.decodeAndVerify(encodedEdge, KEY, now + 120_000L, 60_000L));
    }

    @Test
    void forbidsCrossServerProfileSyncForUnauthenticatedIdentities() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new NetworkEnvelope.ProfileSummarySync(
                1000L, player, Identity.UNAUTHENTICATED, "click_interval_ticks", 10, 5.0, 0.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new NetworkEnvelope.ProfileSummarySync(
                1000L, player, Identity.CLAIMED_BY_PROXY, "click_interval_ticks", 10, 5.0, 0.1, 0));
    }
}
