package dev.whynotac.paper.diagnostics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.whynotac.analysis.evidence.Margins;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * OpenMetrics / Prometheus text exporter and health-transition watchdog (OBS-01).
 *
 * <p>Supports {@code /whynotac metrics} on demand and, when configured with a positive port, a local
 * loopback HTTP server serving {@code /metrics} and {@code /health}.
 */
public final class MetricsExporter implements AutoCloseable {

    public record Snapshot(
            int sessionsLive,
            long sessionsTotal,
            long queuedEvents,
            long inboundTotal,
            long outboundTotal,
            long lostEvents,
            long inboundDivergences,
            long outboundDivergences,
            long listenerErrors,
            long casesOpened,
            long casesStored,
            long casesDropped,
            long recordingFailed,
            long recordingRejectedBlocks,
            int banWavePending,
            double transactionMeanWallMicros,
            double transactionMaxWallMicros,
            Map<String, Margins.Margin> margins,
            HealthModel.Evaluation health,
            long sessionBudgetYields,
            long sessionGapBursts) {

        public Snapshot(
                int sessionsLive,
                long sessionsTotal,
                long queuedEvents,
                long inboundTotal,
                long outboundTotal,
                long lostEvents,
                long inboundDivergences,
                long outboundDivergences,
                long listenerErrors,
                long casesOpened,
                long casesStored,
                long casesDropped,
                long recordingFailed,
                long recordingRejectedBlocks,
                int banWavePending,
                double transactionMeanWallMicros,
                double transactionMaxWallMicros,
                Map<String, Margins.Margin> margins,
                HealthModel.Evaluation health) {
            this(sessionsLive, sessionsTotal, queuedEvents, inboundTotal, outboundTotal, lostEvents,
                    inboundDivergences, outboundDivergences, listenerErrors, casesOpened, casesStored,
                    casesDropped, recordingFailed, recordingRejectedBlocks, banWavePending,
                    transactionMeanWallMicros, transactionMaxWallMicros, margins, health, 0L, 0L);
        }
    }

    /** Detects state transitions (GREEN <-> DEGRADED <-> CRITICAL) for operator alerting without spam. */
    public static final class HealthWatchdog {
        private HealthModel.Status lastStatus = HealthModel.Status.GREEN;

        public synchronized Optional<String> evaluateTransition(HealthModel.Evaluation current) {
            if (current.status() == lastStatus) {
                return Optional.empty();
            }
            HealthModel.Status prev = lastStatus;
            lastStatus = current.status();
            if (current.status() == HealthModel.Status.GREEN) {
                return Optional.of("WhyNotAC health RECOVERED: " + prev + " -> GREEN");
            }
            return Optional.of("WhyNotAC health transition " + prev + " -> " + current.status()
                    + ": " + String.join("; ", current.reasons()));
        }
    }

    private final HttpServer server;
    private final ExecutorService httpExecutor;

    private MetricsExporter(HttpServer server, ExecutorService httpExecutor) {
        this.server = server;
        this.httpExecutor = httpExecutor;
    }

    public static MetricsExporter startLocalHttp(String host, int port, Supplier<Snapshot> snapshotSupplier) throws IOException {
        return startLocalHttp(host, port, "", snapshotSupplier);
    }

    public static MetricsExporter startLocalHttp(String host, int port, String bearerToken, Supplier<Snapshot> snapshotSupplier)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        String expectedAuth = (bearerToken == null || bearerToken.isBlank()) ? null : "Bearer " + bearerToken.trim();
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WhyNotAC MetricsHTTP");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(exec);
        server.createContext("/metrics", exchange -> {
            if (!isAuthorized(exchange, expectedAuth)) {
                rejectUnauthorized(exchange);
                return;
            }
            byte[] body = formatOpenMetrics(snapshotSupplier.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/health", exchange -> {
            if (!isAuthorized(exchange, expectedAuth)) {
                rejectUnauthorized(exchange);
                return;
            }
            HealthModel.Evaluation eval = snapshotSupplier.get().health();
            int code = eval.status() == HealthModel.Status.CRITICAL ? 503 : 200;
            byte[] body = (eval.toJson() + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return new MetricsExporter(server, exec);
    }

    private static boolean isAuthorized(HttpExchange exchange, String expectedAuthHeader) {
        if (expectedAuthHeader == null) {
            return true;
        }
        String provided = exchange.getRequestHeaders().getFirst("Authorization");
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedAuthHeader.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static void rejectUnauthorized(HttpExchange exchange) throws IOException {
        byte[] body = "{\"error\":\"unauthorized\"}\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"WhyNotAC\"");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(401, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        httpExecutor.shutdownNow();
    }

    public static String formatOpenMetrics(Snapshot s) {
        StringBuilder out = new StringBuilder(1024);
        int healthCode = switch (s.health().status()) {
            case GREEN -> 0;
            case DEGRADED -> 1;
            case CRITICAL -> 2;
        };
        gauge(out, "whynotac_health_status", "Health state (0=GREEN, 1=DEGRADED, 2=CRITICAL)", healthCode);
        gauge(out, "whynotac_sessions_live", "Active player sessions", s.sessionsLive());
        counter(out, "whynotac_sessions_total", "Total player sessions observed since startup", s.sessionsTotal());
        gauge(out, "whynotac_queued_events", "Queued timeline events across all sessions", s.queuedEvents());
        counter(out, "whynotac_inbound_packets_total", "Inbound modeled packets", s.inboundTotal());
        counter(out, "whynotac_outbound_packets_total", "Outbound modeled packets", s.outboundTotal());
        counter(out, "whynotac_lost_events_total", "Dropped timeline events", s.lostEvents());
        counter(out, "whynotac_divergences_inbound_total", "Inbound edge vs game divergences", s.inboundDivergences());
        counter(out, "whynotac_divergences_outbound_total", "Outbound edge vs game divergences", s.outboundDivergences());
        counter(out, "whynotac_listener_errors_total", "Analysis listener uncaught exceptions", s.listenerErrors());
        counter(out, "whynotac_cases_opened_total", "Verdict cases opened", s.casesOpened());
        counter(out, "whynotac_cases_stored_total", "Cases written to SQLite/dossier storage", s.casesStored());
        counter(out, "whynotac_cases_dropped_total", "Cases dropped due to storage failure", s.casesDropped());
        counter(out, "whynotac_recording_failed_total", "Failed session recordings", s.recordingFailed());
        counter(out, "whynotac_recording_rejected_blocks_total", "Recording blocks dropped by queue budget", s.recordingRejectedBlocks());
        gauge(out, "whynotac_ban_wave_pending", "Queued delayed temporary bans", s.banWavePending());
        gauge(out, "whynotac_transaction_loop_mean_wall_micros", "Mean main-thread tick transaction wall time (us)", s.transactionMeanWallMicros());
        gauge(out, "whynotac_transaction_loop_max_wall_micros", "Max main-thread tick transaction wall time (us)", s.transactionMaxWallMicros());
        counter(out, "whynotac_session_budget_yields_total", "Per-session analysis slice budget yields (fairness protection)", s.sessionBudgetYields());
        counter(out, "whynotac_session_gap_bursts_total", "Per-session repeated Gap blinding bursts (>=3 gaps in 60s)", s.sessionGapBursts());

        if (s.margins() != null && !s.margins().isEmpty()) {
            out.append("# HELP whynotac_check_min_margin Smallest honest safety margin observed per check.\n");
            out.append("# TYPE whynotac_check_min_margin gauge\n");
            s.margins().forEach((check, margin) -> {
                if (Double.isFinite(margin.smallest())) {
                    out.append(String.format(Locale.ROOT,
                            "whynotac_check_min_margin{check=\"%s\"} %.6f\n", escapeLabel(check), margin.smallest()));
                }
            });
        }
        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n");
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder out, String name, String help, double value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n");
        out.append(String.format(Locale.ROOT, "%s %.6f\n", name, value));
    }

    private static void counter(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" counter\n");
        out.append(name).append(' ').append(value).append('\n');
    }

    private static String escapeLabel(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
