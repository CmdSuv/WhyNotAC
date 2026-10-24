package dev.whynotac.analysis.evidence;

import dev.whynotac.protocol.session.PlayerSession;

/** Where checks deliver what they found. Called on the analysis thread of the session. */
public interface EvidenceSink {

    /** An admissible finding. */
    void accept(PlayerSession session, Evidence evidence);

    /** A candidate the admissibility gate rejected (kept for statistics; never evidence). */
    default void rejected(PlayerSession session, String check, Admissibility admissibility) {}
}
