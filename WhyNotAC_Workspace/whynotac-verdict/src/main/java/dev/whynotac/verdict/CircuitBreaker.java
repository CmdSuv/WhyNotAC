package dev.whynotac.verdict;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Operator kill switch and automatic spike circuit breaker (VER-12).
 *
 * <p>When active, every enforcement escalation above {@code ALERT}/{@code REVIEW} is suppressed so no player is
 * mitigated, kicked, or banned until cleared by an operator.
 */
public final class CircuitBreaker {

    private static final long WINDOW_MILLIS = 60_000L;

    public record Status(boolean killSwitchActive, boolean tripped, String reason, long trippedAtEpochMillis, int recentEnforcedSubjects) {}

    private record Entry(String subjectKey, long epochMillis) {}

    private final int maxUniqueSubjectsPerMinute;
    private final ArrayDeque<Entry> window = new ArrayDeque<>();
    private final Map<String, Integer> activeCounts = new HashMap<>();

    private boolean killSwitchActive;
    private boolean tripped;
    private String reason = "";
    private long trippedAtEpochMillis;

    public CircuitBreaker(int maxUniqueSubjectsPerMinute) {
        if (maxUniqueSubjectsPerMinute < 1) {
            throw new IllegalArgumentException("maxUniqueSubjectsPerMinute must be >= 1");
        }
        this.maxUniqueSubjectsPerMinute = maxUniqueSubjectsPerMinute;
    }

    /** Manually toggles the global kill switch. */
    public synchronized void setKillSwitch(boolean active, String reason) {
        this.killSwitchActive = active;
        this.reason = reason == null ? "" : reason;
        if (!active && !tripped) {
            this.reason = "";
        }
    }

    /**
     * Records that {@code subjectKey} received an active enforcement action ({@code NEUTRALIZE}, {@code KICK}, or
     * {@code TEMP_BAN}). Returns {@code true} on the call that trips the breaker.
     */
    public synchronized boolean recordEnforcedSubject(String subjectKey, long epochMillis) {
        prune(epochMillis);
        if (subjectKey != null && !subjectKey.isBlank()) {
            window.addLast(new Entry(subjectKey, epochMillis));
            activeCounts.merge(subjectKey, 1, Integer::sum);
        }
        if (!tripped && activeCounts.size() > maxUniqueSubjectsPerMinute) {
            tripped = true;
            trippedAtEpochMillis = epochMillis;
            reason = "spike: " + activeCounts.size() + " enforced subjects within 60s (limit " + maxUniqueSubjectsPerMinute + ")";
            return true;
        }
        return false;
    }

    /** True while active player enforcement must be suppressed. */
    public synchronized boolean isSuppressed() {
        return killSwitchActive || tripped;
    }

    /** Resets the automatic spike breaker (does not override manual kill switch unless explicitly disabled). */
    public synchronized void resetAutomaticTrip() {
        tripped = false;
        trippedAtEpochMillis = 0L;
        window.clear();
        activeCounts.clear();
        if (!killSwitchActive) {
            reason = "";
        }
    }

    public synchronized Status status(long nowEpochMillis) {
        prune(nowEpochMillis);
        return new Status(killSwitchActive, tripped, reason, trippedAtEpochMillis, activeCounts.size());
    }

    private void prune(long nowEpochMillis) {
        long cutoff = nowEpochMillis - WINDOW_MILLIS;
        while (!window.isEmpty() && window.peekFirst().epochMillis() < cutoff) {
            Entry e = window.pollFirst();
            activeCounts.computeIfPresent(e.subjectKey(), (k, count) -> count <= 1 ? null : count - 1);
        }
    }
}
