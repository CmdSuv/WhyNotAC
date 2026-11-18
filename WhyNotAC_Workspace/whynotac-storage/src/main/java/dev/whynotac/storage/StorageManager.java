package dev.whynotac.storage;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.verdict.CircuitBreaker;
import dev.whynotac.verdict.FalseBanBudget;
import dev.whynotac.verdict.VerdictCase;
import dev.whynotac.verdict.VerdictDecision;
import dev.whynotac.verdict.VerdictEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coordinates {@link AuditLedger}, {@link SubjectProfileStore}, and {@link ReviewWorkflow} on a single async
 * writer thread so analysis and server threads never block on disk I/O while guaranteeing sequential hash chaining.
 */
public final class StorageManager implements VerdictEngine.CaseListener, VerdictEngine.EnforcementListener, AutoCloseable {

    private final AuditLedger ledger;
    private final SubjectProfileStore profiles;
    private final ReviewWorkflow reviews;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WhyNotAC-StorageWriter");
        t.setDaemon(true);
        return t;
    });

    public StorageManager(Path dataDirectory, String buildTag, Logger logger) throws IOException {
        this.ledger = new AuditLedger(
                dataDirectory.resolve("audit-ledger.jsonl"),
                dataDirectory.resolve("audit-secret.key"),
                buildTag);
        this.profiles = new SubjectProfileStore(dataDirectory.resolve("profiles"));
        this.reviews = new ReviewWorkflow();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public AuditLedger ledger() {
        return ledger;
    }

    public SubjectProfileStore profiles() {
        return profiles;
    }

    public ReviewWorkflow reviews() {
        return reviews;
    }

    /** Seeds {@code falseBanBudget} with recent persisted overturn/mismatch incidents across server restarts. */
    public void restoreFalseBanBudget(FalseBanBudget budget, long windowMillis, long nowMillis) {
        try {
            List<Long> incidents = profiles.loadRecentIncidents(nowMillis - windowMillis);
            for (long ts : incidents) {
                budget.recordIncident("restored", "persisted false-ban incident", ts);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "WhyNotAC could not restore persisted false-ban incidents", e);
        }
    }

    @Override
    public void opened(VerdictCase c) {
        UUID uuid = playerUuid(c.session());
        long ts = c.openedEpochMillis();
        reviews.open(c.id(), ts);
        writer.execute(() -> {
            try {
                ledger.append(AuditLedger.EntryKind.CASE_OPENED, ts, c.id(),
                        c.certificate().sha256Digest(), uuid,
                        c.evidence().check() + " (" + c.lifecycle() + ")");
                if (uuid != null) {
                    profiles.recordCase(uuid, c.evidence().check(), ts);
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "WhyNotAC failed to persist opened case in storage ledger", e);
            }
        });
    }

    @Override
    public void mitigate(VerdictDecision d) {
        appendDecisionAsync(AuditLedger.EntryKind.MITIGATION_APPLIED, d);
    }

    @Override
    public void kick(VerdictDecision d) {
        appendDecisionAsync(AuditLedger.EntryKind.KICK_ISSUED, d);
    }

    @Override
    public void tempBan(VerdictDecision d) {
        UUID uuid = playerUuid(d.session());
        long ts = d.certificate().issuedAtEpochMillis();
        writer.execute(() -> {
            try {
                ledger.append(AuditLedger.EntryKind.TEMP_BAN_ISSUED, ts, d.verdictCase().id(),
                        d.certificate().sha256Digest(), uuid,
                        d.evidence().check() + " (" + d.reason() + ")");
                if (uuid != null) {
                    profiles.recordAutoBan(uuid, ts);
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "WhyNotAC failed to persist temp-ban in storage ledger", e);
            }
        });
    }

    @Override
    public void verificationRejected(VerdictDecision d) {
        UUID uuid = playerUuid(d.session());
        long ts = d.certificate().issuedAtEpochMillis();
        writer.execute(() -> {
            try {
                ledger.append(AuditLedger.EntryKind.VERIFICATION_REJECTED, ts, d.verdictCase().id(),
                        d.certificate().sha256Digest(), uuid, d.reason());
                if (uuid != null) {
                    profiles.recordFalseBanIncident(uuid, ts);
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "WhyNotAC failed to record verification rejection", e);
            }
        });
    }

    @Override
    public void circuitBreakerTripped(CircuitBreaker.Status status) {
        writer.execute(() -> {
            try {
                ledger.append(AuditLedger.EntryKind.CIRCUIT_BREAKER_TRIPPED, status.trippedAtEpochMillis(), "",
                        "", null, status.reason());
            } catch (IOException e) {
                logger.log(Level.WARNING, "WhyNotAC failed to record circuit-breaker trip", e);
            }
        });
    }

    /** Persists updated behavioral baseline moments asynchronously for an authenticated account. */
    public void saveBehavioralBaselineAsync(UUID playerId, java.util.Map<String, SubjectProfileStore.FeatureSummary> features, long epochMillis) {
        if (playerId == null || features == null || features.isEmpty()) {
            return;
        }
        writer.execute(() -> {
            try {
                profiles.saveBaselineFeatures(playerId, features, epochMillis);
            } catch (IOException e) {
                logger.log(Level.WARNING, "WhyNotAC failed to persist behavioral baseline", e);
            }
        });
    }

    /** Removes persisted subject profile and appends a pseudonym-free erasure marker to the immutable ledger. */
    public boolean forgetSubject(UUID playerId, long epochMillis) throws IOException {
        try {
            return writer.submit(() -> {
                boolean deleted = profiles.forget(playerId);
                ledger.append(AuditLedger.EntryKind.SUBJECT_FORGOTTEN, epochMillis, "", "", null,
                        deleted ? "profile erased (gdpr)" : "gdpr erasure requested (no profile stored)");
                return deleted;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for storage erasure", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("storage erasure failed", e.getCause());
        }
    }

    private void appendDecisionAsync(AuditLedger.EntryKind kind, VerdictDecision d) {
        UUID uuid = playerUuid(d.session());
        long ts = d.certificate().issuedAtEpochMillis();
        writer.execute(() -> {
            try {
                ledger.append(kind, ts, d.verdictCase().id(), d.certificate().sha256Digest(), uuid,
                        d.evidence().check() + " -> " + d.mitigation());
            } catch (IOException e) {
                logger.log(Level.WARNING, "WhyNotAC failed to record enforcement decision", e);
            }
        });
    }

    private static UUID playerUuid(PlayerSession session) {
        return session != null && session.identity() != null ? session.identity().playerId() : null;
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("WhyNotAC storage writer did not finish within 5 s");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        ledger.close();
    }

    /** Appends an operator-initiated hot configuration reload entry to the audit ledger. */
    public void recordConfigReloadAsync(String operator, String details, long epochMillis) {
        writer.execute(() -> {
            try {
                ledger.append(AuditLedger.EntryKind.CONFIG_RELOAD, epochMillis, "", "", null,
                        (operator == null ? "console" : operator) + ": " + (details == null ? "" : details));
            } catch (IOException e) {
                logger.log(Level.WARNING, "WhyNotAC failed to record config reload audit entry", e);
            }
        });
    }
}
