package dev.whynotac.paper.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import org.junit.jupiter.api.Test;

/** Which identities may ever carry long-lived state, for every way a Paper server can be configured. */
class ClientFactsTest {

    private static ClientFacts.Policy policy(boolean online, boolean bungee, boolean bungeeOnline, boolean velocity, boolean velocityOnline) {
        return ClientFacts.policy(online, bungee, bungeeOnline, velocity, velocityOnline, false);
    }

    @Test
    void onlyVerifiedLoginsAreAuthenticated() {
        assertEquals(Identity.AUTHENTICATED_MOJANG, policy(true, false, false, false, false).identity());
        assertEquals(Identity.UNAUTHENTICATED, policy(false, false, false, false, false).identity(), "offline mode: any name, any UUID");
        // Velocity modern forwarding: Paper verifies the HMAC; trust follows the proxy's own online mode.
        assertEquals(Identity.AUTHENTICATED_BY_PROXY, policy(false, false, false, true, true).identity());
        assertEquals(Identity.UNAUTHENTICATED, policy(false, false, false, true, false).identity());
        // Legacy bungee forwarding is plain text: never more than a claim, whatever the proxy says.
        assertEquals(Identity.CLAIMED_BY_PROXY, policy(false, true, true, false, false).identity());
        assertEquals(Identity.UNAUTHENTICATED, policy(false, true, false, false, false).identity());
        assertFalse(policy(false, true, true, false, false).authenticated());
        assertTrue(policy(true, false, false, false, false).authenticated());
    }

    @Test
    void velocityTakesPrecedenceOverBungeeAndTopologyIsNamed() {
        assertEquals("VELOCITY_MODERN", policy(false, true, true, true, true).topology());
        assertEquals("BUNGEE_LEGACY", policy(false, true, true, false, false).topology());
        assertEquals("DIRECT", policy(true, false, false, false, false).topology());
        assertEquals("PROXY_PROTOCOL", ClientFacts.policy(true, false, false, false, false, true).topology());
    }
}
