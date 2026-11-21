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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Structured JSONL alert &amp; case stream exporter ({@code alerts-stream.jsonl}) with optional
 * asynchronous HTTP POST webhook forwarding for SIEM, Discord/Slack, and external audit pipelines.
 */
public final class AlertStreamExporter implements CaseListener, AutoCloseable {

    static final int MAX_WEBHOOK_QUEUE = 512;

    private final Path outputFile;
    private final AiAdvisor aiAdvisor;
    private final Logger logger;
    private final URI webhookUri;
    private final ExecutorService webhookExecutor;
    private final HttpClient httpClient;
    private final AtomicLong droppedWebhooks = new AtomicLong();
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
            this.webhookExecutor = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_WEBHOOK_QUEUE),
                    r -> {
                        Thread t = new Thread(r, "WhyNotAC-AlertWebhook");
                        t.setDaemon(true);
                        return t;
                    },
                    (r, exec) -> droppedWebhooks.incrementAndGet());
            this.httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .executor(this.webhookExecutor)
                    .build();
        } else {
            this.webhookExecutor = null;
            this.httpClient = null;
        }
    }

    public long droppedWebhooks() {
        return droppedWebhooks.get();
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
            dispatchWebhookWithRetry(c.id(), jsonLine, 0);
        }
    }

    private void dispatchWebhookWithRetry(String caseId, String jsonLine, int attempt) {
        HttpRequest request = HttpRequest.newBuilder(webhookUri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(jsonLine, StandardCharsets.UTF_8))
                .build();
        try {
            CompletableFuture<HttpResponse<Void>> fut = httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            fut.whenComplete((resp, err) -> {
                boolean retryable = err != null || (resp != null && (resp.statusCode() == 429 || resp.statusCode() >= 500));
                if (retryable && attempt == 0 && webhookExecutor != null && !webhookExecutor.isShutdown()) {
                    CompletableFuture.delayedExecutor(250L, TimeUnit.MILLISECONDS, webhookExecutor)
                            .execute(() -> dispatchWebhookWithRetry(caseId, jsonLine, 1));
                    return;
                }
                if (logger == null) {
                    return;
                }
                if (err != null) {
                    logger.log(Level.FINE, "Alert webhook delivery failed for case " + caseId, err);
                } else if (resp != null && resp.statusCode() >= 400) {
                    logger.warning("Alert webhook HTTP " + resp.statusCode() + " for case " + caseId);
                }
            });
        } catch (RejectedExecutionException rejected) {
            droppedWebhooks.incrementAndGet();
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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
