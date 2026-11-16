package dev.whynotac.velocity;

import dev.whynotac.protocol.network.NetworkEnvelope;
import dev.whynotac.protocol.network.NetworkSecret;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WhyNotAcVelocityPluginTest {

    @Test
    void loadsPersistentSecretAndBuildsVerifiedProxyEdgeFrames(@TempDir Path dir) throws IOException {
        Path keyFile = dir.resolve("network-secret.key");
        byte[] key1 = NetworkSecret.loadOrCreate(keyFile);
        byte[] key2 = NetworkSecret.loadOrCreate(keyFile);
        assertArrayEquals(key1, key2);
        assertEquals(32, key1.length);

        UUID id = UUID.randomUUID();
        long now = 1_700_000_000_000L;
        byte[] frame = WhyNotAcVelocityPlugin.buildSignedEdgeEnvelope(id, 774, true, false, false, now, key1);
        NetworkEnvelope.Payload decoded = NetworkEnvelope.decodeAndVerify(frame, key1, now, 60_000L);
        NetworkEnvelope.ProxyClientEdge edge = assertInstanceOf(NetworkEnvelope.ProxyClientEdge.class, decoded);
        assertEquals(774, edge.clientProtocol());
        assertEquals("none", edge.translator());
        assertEquals("CLIENT", edge.transactionOrigin());
        assertEquals(Identity.AUTHENTICATED_BY_PROXY, edge.identity());

        // Older translated client -> reports translator present
        byte[] oldFrame = WhyNotAcVelocityPlugin.buildSignedEdgeEnvelope(id, 754, true, false, true, now, key1);
        NetworkEnvelope.ProxyClientEdge oldEdge = assertInstanceOf(NetworkEnvelope.ProxyClientEdge.class,
                NetworkEnvelope.decodeAndVerify(oldFrame, key1, now, 60_000L));
        assertEquals("viaversion-proxy", oldEdge.translator());
        assertEquals("TRANSLATOR", oldEdge.transactionOrigin());

        // Relay validation rejects forged / client-originating ProxyClientEdge frames
        assertTrue(WhyNotAcVelocityPlugin.validateRelayFrame(frame, key1, now).isEmpty());

        byte[] syncFrame = NetworkEnvelope.encode(new NetworkEnvelope.ProfileSummarySync(
                now, id, Identity.AUTHENTICATED_BY_PROXY, "click_interval_ticks", 50, 5.2, 0.3, 0), key1);
        Optional<byte[]> validRelay = WhyNotAcVelocityPlugin.validateRelayFrame(syncFrame, key1, now);
        assertTrue(validRelay.isPresent());
    }
}
