package dev.whynotac.analysis.evidence;

import java.util.Map;
import java.util.TreeMap;

/**
 * One finding of one check over one window of a session's timeline.
 *
 * <p>Evidence is a pure function of the timeline: replaying the session's recording through the same
 * check version produces the same {@link #fingerprint()}, which is how a dossier is re-verified.
 * It names no player: identity is the case store's business, so the same evidence comes out of a
 * pseudonymised recording.
 *
 * @param fromSequence first timeline sequence of the window (inclusive)
 * @param toSequence   last timeline sequence of the window (inclusive)
 * @param details      check-specific facts, sorted by key so the fingerprint is stable
 */
public record Evidence(String check, int checkVersion, EvidenceClass evidenceClass, long fromSequence, long toSequence,
        long fromNanoTime, long toNanoTime, String summary, Map<String, String> details, Admissibility admissibility) {

    public Evidence {
        details = new TreeMap<>(details);
    }

    /** What re-verification compares: everything that follows from the timeline. */
    public String fingerprint() {
        return check + "@" + checkVersion + " " + evidenceClass.code() + " [" + fromSequence + ".." + toSequence + "] " + details;
    }
}
