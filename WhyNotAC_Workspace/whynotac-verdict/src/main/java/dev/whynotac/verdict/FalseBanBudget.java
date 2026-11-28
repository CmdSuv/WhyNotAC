package dev.whynotac.verdict;

import java.util.ArrayDeque;

/**
 * Sliding false-ban budget ledger (VER-08).
 *
 * <p>Tracks automatic temporary bans issued and overturned/re-verification failures. Once the overturn or mismatch
 * budget is exhausted inside the configured window, automatic temporary bans lock down until an operator resets the ledger.
 */
public final class FalseBanBudget {

    public record Snapshot(long totalAutoBansIssued, int recentIncidents, int budgetLimit, boolean locked, String lockReason) {}

    private record Incident(String caseId, String reason, long epochMillis) {}

    private final int maxBudgetPerWindow;
    private final long windowMillis;
    private final ArrayDeque<Incident> incidents = new ArrayDeque<>();
    private long totalAutoBansIssued;
    private boolean locked;
    private String lockReason = "";

    public FalseBanBudget(int maxBudgetPerWindow, long windowMillis) {
        if (maxBudgetPerWindow < 0) {
            throw new IllegalArgumentException("maxBudgetPerWindow must be >= 0");
        }
        if (windowMillis < 1_000L) {
            throw new IllegalArgumentException("windowMillis must be >= 1000 ms");
        }
        this.maxBudgetPerWindow = maxBudgetPerWindow;
        this.windowMillis = windowMillis;
        if (maxBudgetPerWindow == 0) {
            this.locked = true;
            this.lockReason = "false-ban budget set to 0 (automatic temp-bans locked)";
        }
    }

    public synchronized void recordAutoBanIssued() {
        totalAutoBansIssued++;
    }

    /**
     * Records a false-ban overturn or independent re-verification mismatch. Returns {@code true} if the ledger just locked.
     */
    public synchronized boolean recordIncident(String caseId, String reason, long epochMillis) {
        prune(epochMillis);
        incidents.addLast(new Incident(caseId == null ? "" : caseId, reason == null ? "" : reason, epochMillis));
        if (!locked && incidents.size() >= Math.max(1, maxBudgetPerWindow)) {
            locked = true;
            lockReason = "false-ban budget exhausted (" + incidents.size() + "/" + maxBudgetPerWindow + "): " + reason;
            return true;
        }
        return false;
    }

    public synchronized boolean isLocked() {
        return locked;
    }

    public synchronized void reset() {
        incidents.clear();
        if (maxBudgetPerWindow == 0) {
            locked = true;
            lockReason = "false-ban budget set to 0 (automatic temp-bans locked)";
        } else {
            locked = false;
            lockReason = "";
        }
    }

    public synchronized Snapshot snapshot(long nowEpochMillis) {
        prune(nowEpochMillis);
        return new Snapshot(totalAutoBansIssued, incidents.size(), maxBudgetPerWindow, locked, lockReason);
    }

    private void prune(long nowEpochMillis) {
        long cutoff = nowEpochMillis - windowMillis;
        while (!incidents.isEmpty() && incidents.peekFirst().epochMillis() < cutoff) {
            incidents.pollFirst();
        }
    }
}
