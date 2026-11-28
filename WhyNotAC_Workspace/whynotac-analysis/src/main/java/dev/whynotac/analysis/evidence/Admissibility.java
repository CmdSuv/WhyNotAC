package dev.whynotac.analysis.evidence;

import java.util.List;

/**
 * Whether a window may yield evidence at all (docs/POLICY.md, "Client profilleri ve kabul
 * edilebilirlik"), with the reasons when not. Missing information is never treated as clean.
 *
 * @param identity the player's identity tier at the time (decides which actions the policy allows)
 */
public record Admissibility(boolean admissible, List<String> reasons, IdentityTier identity) {

    public Admissibility {
        reasons = List.copyOf(reasons);
    }

    /** How far the player's identity is established (docs/POLICY.md, "Kimlik katmanları"). */
    public enum IdentityTier {
        /** Mojang (online mode) or a proxy that proved it (Velocity modern forwarding, Floodgate). */
        AUTHENTICATED,
        /** Offline mode, after a login plugin confirmed the account (planned). */
        AUTH_PLUGIN_CONFIRMED,
        /** Offline mode, legacy forwarding, or not known yet: evidence stays with the session. */
        UNAUTHENTICATED
    }
}
