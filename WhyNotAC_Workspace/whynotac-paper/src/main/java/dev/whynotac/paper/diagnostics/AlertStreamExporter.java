package dev.whynotac.paper.diagnostics;

import dev.whynotac.ai.AiAdvisor;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.verdict.VerdictCase;
import dev.whynotac.verdict.VerdictEngine.CaseListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Structured JSONL alert &amp; case stream exporter ({@code alerts-stream.jsonl}) with optional
 * asynchronous HTTP POST webhook forwarding for SIEM, Discord/Slack, and external audit pipelines.
 */
public final class AlertStreamExporter implements CaseListener, AutoCloseable {

    private final Path outputFile;
    private final AiAdvisor aiAdvisor;
    private final Logger logger;
    private final URI webhookUri;
    private final ExecutorService webhookExecutor;
    private final HttpClient httpClient;
    private BufferedWriter writer;

    public AlertStreamExporter(Path outputFile, AiAdvisor aiAdvisor, Logger logger) {
        this(outputFile, aiAdvisor, logger, null);
    }

    public AlertStreamExporter(Path outputFile, AiAdvisor aiAdvisor, Logger logger, String webhookUrl) {
        this.outputFile = outputFile;
        this.aiAdvisor = aiAdvisor;
        this.logger = logger;
        URI parsed = parseWebhookUri(webhookUrl, logger);
        this.webhookUri = parsed;
        if (parsed != null) {
            this.webhookExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "WhyNotAC-AlertWebhook");
                t.setDaemon(true);
                return t;
            });
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .executor(this.webhookExecutor)
                    .build();
        } else {
            this.webhookExecutor = null;
            this.httpClient = null;
        }
    }

    private static URI parseWebhookUri(String webhookUrl, Logger logger) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }
        String trimmed = webhookUrl.trim();
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return uri;
            }
            if (logger != null) {
                logger.warning("Ignoring observability.webhook-url with unsupported scheme: " + trimmed);
            }
        } catch (IllegalArgumentException ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Invalid observability.webhook-url: " + trimmed, ex);
            }
        }
        return null;
    }

    @Override
    public synchronized void opened(VerdictCase c) {
        if (c == null || (outputFile == null && httpClient == null)) {
            return;
        }
        PlayerSession.Identity id = c.session() != null ? c.session().identity() : null;
        String playerId = id != null && id.playerId() != null ? id.playerId().toString() : "";
        String playerName = id != null && id.name() != null ? id.name() : "";
        AiAdvisor.CasePriority prio = aiAdvisor != null ? aiAdvisor.priorityOf(c.id()) : null;
        double aiScore = prio != null ? prio.score0To100() : 0.0;

        String jsonLine = String.format(
                Locale.ROOT,
                "{\"timestampMs\":%d,\"caseId\":\"%s\",\"check\":\"%s\",\"checkVersion\":%d,"
                        + "\"evidenceClass\":\"%s\",\"lifecycle\":\"%s\",\"action\":\"%s\","
                        + "\"mitigation\":\"%s\",\"playerId\":\"%s\",\"playerName\":\"%s\","
                        + "\"identity\":\"%s\",\"aiPriorityScore\":%.2f,"
                        + "\"fingerprint\":\"%s\",\"summary\":\"%s\"}",
                System.currentTimeMillis(),
                escape(c.id()),
                escape(c.evidence().check()),
                c.evidence().checkVersion(),
                escape(c.evidence().evidenceClass().code()),
                c.lifecycle().name(),
                c.authorizedAction().name(),
                c.mitigation().name(),
                escape(playerId),
                escape(playerName),
                c.evidence().admissibility().identity().name(),
                aiScore,
                escape(c.evidence().fingerprint()),
                escape(c.evidence().summary()));

        if (outputFile != null) {
            try {
                if (writer == null) {
                    Path parent = outputFile.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    writer = Files.newBufferedWriter(
                            outputFile,
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                }
                writer.write(jsonLine);
                writer.newLine();
                writer.flush();
            } catch (IOException ex) {
                if (logger != null) {
                    logger.log(Level.WARNING, "Failed to write JSONL alert stream entry to " + outputFile, ex);
                }
            }
        }
        if (httpClient != null && webhookUri != null) {
            HttpRequest request = HttpRequest.newBuilder(webhookUri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonLine, StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<HttpResponse<Void>> fut = httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            fut.whenComplete((resp, err) -> {
                if (logger == null) {
                    return;
                }
                if (err != null) {
                    logger.log(Level.FINE, "Alert webhook delivery failed for case " + c.id(), err);
                } else if (resp != null && resp.statusCode() >= 400) {
                    logger.warning("Alert webhook HTTP " + resp.statusCode() + " for case " + c.id());
                }
            });
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            } finally {
                writer = null;
            }
        }
        if (webhookExecutor != null) {
            webhookExecutor.shutdown();
            try {
                webhookExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
