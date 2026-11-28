package dev.whynotac.paper.diagnostics;

import dev.whynotac.analysis.evidence.Margins;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetricsExporterTest {

    @Test
    void formatsOpenMetricsServesLocalHttpAndTracksHealthTransitions() throws IOException, InterruptedException {
        Margins margins = new Margins();
        margins.add("attack-reach", 0.25);

        MetricsExporter.Snapshot greenSnap = new MetricsExporter.Snapshot(
                3, 10, 4, 1200, 1500, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 12.5, 48.0,
                margins.snapshot(), new HealthModel.Evaluation(HealthModel.Status.GREEN, List.of()));

        String prom = MetricsExporter.formatOpenMetrics(greenSnap);
        assertTrue(prom.contains("whynotac_health_status 0\n"));
        assertTrue(prom.contains("whynotac_sessions_live 3\n"));
        assertTrue(prom.contains("whynotac_check_min_margin{check=\"attack-reach\"} 0.250000\n"));

        MetricsExporter.HealthWatchdog watchdog = new MetricsExporter.HealthWatchdog();
        assertTrue(watchdog.evaluateTransition(greenSnap.health()).isEmpty());
        Optional<String> degradedMsg = watchdog.evaluateTransition(
                new HealthModel.Evaluation(HealthModel.Status.DEGRADED, List.of("high queue")));
        assertTrue(degradedMsg.isPresent());
        assertTrue(degradedMsg.get().contains("GREEN -> DEGRADED"));
        Optional<String> recoveredMsg = watchdog.evaluateTransition(greenSnap.health());
        assertTrue(recoveredMsg.isPresent());
        assertTrue(recoveredMsg.get().contains("RECOVERED"));

        try (MetricsExporter http = MetricsExporter.startLocalHttp("127.0.0.1", 0, () -> greenSnap)) {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> metricsResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.boundPort() + "/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metricsResp.statusCode());
            assertTrue(metricsResp.body().contains("whynotac_sessions_total 10"));

            HttpResponse<String> healthResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.boundPort() + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, healthResp.statusCode());
            assertTrue(healthResp.body().contains("\"GREEN\""));
        }
    }
}
