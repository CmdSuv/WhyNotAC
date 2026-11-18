package dev.whynotac.paper.diagnostics;

import dev.whynotac.ai.AiAdvisor;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.verdict.VerdictCase;
import dev.whynotac.verdict.VerdictEngine.CaseListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Structured JSONL alert &amp; case stream exporter ({@code alerts-stream.jsonl}) for SIEM,
 * Discord/Webhook forwarders, and external audit pipelines.
 */
public final class AlertStreamExporter implements CaseListener, AutoCloseable {

    private final Path outputFile;
    private final AiAdvisor aiAdvisor;
    private final Logger logger;
    private BufferedWriter writer;

    public AlertStreamExporter(Path outputFile, AiAdvisor aiAdvisor, Logger logger) {
        this.outputFile = outputFile;
        this.aiAdvisor = aiAdvisor;
        this.logger = logger;
    }

    @Override
    public synchronized void opened(VerdictCase c) {
        if (outputFile == null || c == null) {
            return;
        }
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
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();
        } catch (IOException ex) {
            if (logger != null) {
                logger.log(Level.WARNING, "Failed to write JSONL alert stream entry to " + outputFile, ex);
            }
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
    }
}
