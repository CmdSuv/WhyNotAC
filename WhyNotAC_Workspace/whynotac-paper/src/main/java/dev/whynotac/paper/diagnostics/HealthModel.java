package dev.whynotac.paper.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Operational health model (OBS-01): synthesizes queue backpressure, dropped/lost timeline events, listener failures,
 * recording I/O errors, circuit breaker state, false-ban budget lock, audit ledger integrity, and per-tick latency
 * into a three-state health summary ({@link Status#GREEN}, {@link Status#DEGRADED}, {@link Status#CRITICAL}).
 */
public final class HealthModel {

    public enum Status { GREEN, DEGRADED, CRITICAL }

    public record Inputs(
            long queuedEvents,
            long globalQueueCapacity,
            long lostEvents,
            long listenerErrors,
            long recordingFailed,
            long recordingRejectedBlocks,
            boolean circuitBreakerTripped,
            boolean killSwitchActive,
            boolean falseBanBudgetLocked,
            double transactionMaxWallMicros,
            boolean auditLedgerValid) {}

    public record Evaluation(Status status, List<String> reasons) {
        public String toJson() {
            StringBuilder sb = new StringBuilder("{\"status\":\"");
            sb.append(status.name()).append("\",\"reasons\":[");
            for (int i = 0; i < reasons.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escapeJson(reasons.get(i))).append('"');
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    private HealthModel() {}

    public static Evaluation evaluate(Inputs in) {
        List<String> critical = new ArrayList<>();
        List<String> degraded = new ArrayList<>();

        if (in.killSwitchActive()) {
            critical.add("kill-switch active (active enforcement suppressed)");
        }
        if (in.circuitBreakerTripped()) {
            critical.add("circuit breaker tripped");
        }
        if (in.falseBanBudgetLocked()) {
            critical.add("false-ban budget locked");
        }
        if (in.listenerErrors() > 0) {
            critical.add("listener errors=" + in.listenerErrors());
        }
        if (in.lostEvents() > 0) {
            critical.add("lost timeline events=" + in.lostEvents());
        }
        if (!in.auditLedgerValid()) {
            critical.add("audit ledger unavailable or invalid");
        }

        if (in.globalQueueCapacity() > 0 && in.queuedEvents() > in.globalQueueCapacity() / 2) {
            degraded.add(String.format(Locale.ROOT, "analysis queue utilization %d/%d",
                    in.queuedEvents(), in.globalQueueCapacity()));
        }
        if (in.recordingFailed() > 0 || in.recordingRejectedBlocks() > 0) {
            degraded.add("recording backpressure/errors failed=" + in.recordingFailed()
                    + " rejectedBlocks=" + in.recordingRejectedBlocks());
        }
        if (in.transactionMaxWallMicros() > 5_000.0) {
            degraded.add(String.format(Locale.ROOT, "high transaction tick wall=%.1f us",
                    in.transactionMaxWallMicros()));
        }

        if (!critical.isEmpty()) {
            List<String> all = new ArrayList<>(critical);
            all.addAll(degraded);
            return new Evaluation(Status.CRITICAL, List.copyOf(all));
        }
        if (!degraded.isEmpty()) {
            return new Evaluation(Status.DEGRADED, List.copyOf(degraded));
        }
        return new Evaluation(Status.GREEN, List.of());
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
