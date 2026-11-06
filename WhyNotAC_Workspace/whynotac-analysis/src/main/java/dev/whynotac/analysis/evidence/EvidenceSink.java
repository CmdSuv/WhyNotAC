package dev.whynotac.analysis.evidence;

import dev.whynotac.protocol.session.PlayerSession;

/** Where checks deliver what they found. Called on the analysis thread of the session. */
public interface EvidenceSink {

    /** An admissible finding. */
    void accept(PlayerSession session, Evidence evidence);

    /** A candidate the admissibility gate rejected (kept for statistics; never evidence). */
    default void rejected(PlayerSession session, String check, Admissibility admissibility) {}

    /**
     * How close an observation that is no finding came to the check's limit ({@code VAL-4}): {@code slack} in the
     * check's own unit, 0 at the limit. Only for checks with a limit to come close to; kept for statistics.
     */
    default void margin(PlayerSession session, String check, double slack) {}
}
