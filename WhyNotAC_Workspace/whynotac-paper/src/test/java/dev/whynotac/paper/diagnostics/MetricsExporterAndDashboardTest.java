package dev.whynotac.paper.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MetricsExporterAndDashboardTest {

    private static MetricsExporter.Snapshot sampleSnapshot() {
        HealthModel.Evaluation health = new HealthModel.Evaluation(HealthModel.Status.GREEN, List.of());
        return new MetricsExporter.Snapshot(
                3, 12, 5, 1000, 900, 0, 0, 0, 0, 2, 2, 0, 0, 0, 1, 14.5, 42.0,
                Map.of(), health, 4L, 1L, 7L, 128L, 15L, 14L);
    }

    private static MetricsExporter.DashboardStatus sampleDashboard() {
        return new MetricsExporter.DashboardStatus(
                sampleSnapshot(), false, false, "", 0L, 1, false, 7L, 128L, 15L, 14L,
                List.of(new MetricsExporter.CaseSummary("case-12345678", 1_700_000_000_000L,
                        "<script>bad</script>", "movement-simulation", "P-A", "SHADOW", "hover")));
    }

    @Test
    void enforcesBearerAuthCspHeadersPathValidationHotTokenRotationAndEmptyTokenRefusal() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

        // 1. Dashboard enabled with EMPTY token MUST refuse exposure with 403!
        try (MetricsExporter noToken = MetricsExporter.startLocalHttp(
                "127.0.0.1", 0, "", true,
                MetricsExporterAndDashboardTest::sampleSnapshot,
                MetricsExporterAndDashboardTest::sampleDashboard)) {
            assertFalse(noToken.isDashboardActive());
            String base = "http://127.0.0.1:" + noToken.boundPort();
            HttpResponse<String> rDash = client.send(HttpRequest.newBuilder(URI.create(base + "/dashboard")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rDash.statusCode(), "Dashboard must refuse exposure when metrics-auth-token is blank");
            HttpResponse<String> rApi = client.send(HttpRequest.newBuilder(URI.create(base + "/api/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rApi.statusCode());
        }

        // 2. Dashboard enabled with valid Bearer token
        try (MetricsExporter exporter = MetricsExporter.startLocalHttp(
                "127.0.0.1", 0, "top-secret-token-24", true,
                MetricsExporterAndDashboardTest::sampleSnapshot,
                MetricsExporterAndDashboardTest::sampleDashboard)) {
            assertTrue(exporter.isDashboardActive());
            String base = "http://127.0.0.1:" + exporter.boundPort();

            // Missing Bearer -> 401
            HttpResponse<String> noAuth = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/dashboard")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, noAuth.statusCode());

            // Wrong Bearer -> 401
            HttpResponse<String> badAuth = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/status"))
                            .header("Authorization", "Bearer wrong-token")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, badAuth.statusCode());

            // Valid Bearer on /dashboard -> 200 + strict CSP + nosniff + DENY
            HttpResponse<String> dashOk = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/dashboard"))
                            .header("Authorization", "Bearer top-secret-token-24")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dashOk.statusCode());
            assertEquals(OperatorDashboardHtml.CSP_HEADER, dashOk.headers().firstValue("Content-Security-Policy").orElse(null));
            assertEquals("nosniff", dashOk.headers().firstValue("X-Content-Type-Options").orElse(null));
            assertEquals("DENY", dashOk.headers().firstValue("X-Frame-Options").orElse(null));
            assertTrue(dashOk.body().contains("WhyNotAC Live Operator Dashboard"));

            // Valid Bearer on /api/status -> 200 JSON with live telemetry
            HttpResponse<String> apiOk = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/status"))
                            .header("Authorization", "Bearer top-secret-token-24")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, apiOk.statusCode());
            assertNotNull(apiOk.headers().firstValue("Content-Security-Policy").orElse(null));
            assertTrue(apiOk.body().contains("\"batchesCommitted\":7"));
            assertTrue(apiOk.body().contains("\"casesBatched\":128"));
            assertTrue(apiOk.body().contains("\"published\":15"));
            assertTrue(apiOk.body().contains("\"sessionGapBursts\":1"));

            // Valid Bearer on /metrics includes new Phase 23 WAL & Redis counters
            HttpResponse<String> metricsOk = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/metrics"))
                            .header("Authorization", "Bearer top-secret-token-24")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metricsOk.statusCode());
            assertTrue(metricsOk.body().contains("whynotac_cases_batches_committed_total 7"));
            assertTrue(metricsOk.body().contains("whynotac_redis_published_total 15"));

            // Path traversal / subpath rejection -> 400
            HttpResponse<String> traversal = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/dashboard/%2e%2e/etc/passwd"))
                            .header("Authorization", "Bearer top-secret-token-24")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, traversal.statusCode(), "Path traversal must be strictly rejected");

            // Hot-reload Bearer token rotation
            exporter.updateBearerToken("rotated-token-99");
            HttpResponse<String> oldRejected = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/health"))
                            .header("Authorization", "Bearer top-secret-token-24")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, oldRejected.statusCode());

            HttpResponse<String> newAccepted = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/health"))
                            .header("Authorization", "Bearer rotated-token-99")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, newAccepted.statusCode());
        }
    }
}
