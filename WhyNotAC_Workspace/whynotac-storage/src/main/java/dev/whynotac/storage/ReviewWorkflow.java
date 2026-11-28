package dev.whynotac.storage;

import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.verdict.VerdictCase;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Four-eyes review, appeal resolution, and plain-language explanation workflow (ADM-02, VER-15).
 *
 * <p>A binding staff confirmation or permanent escalation requires two distinct reviewers ("four-eyes"
 * safeguard) so no single reviewer mistake or compromised staff account can silently finalize a permanent ban.
 * When constructed with a persistence path, every review/appeal state transition is appended durably as JSONL
 * and reloaded across server restarts while keeping in-memory state bounded.
 */
public final class ReviewWorkflow {

    static final int MAX_IN_MEMORY_RECORDS = 50_000;

    public enum Status {
        OPEN,
        FIRST_REVIEWED,
        CONFIRMED_FOUR_EYES,
        APPEALED,
        UPHELD,
        OVERTURNED
    }

    public record ReviewRecord(
            String caseId,
            Status status,
            String primaryReviewer,
            String secondaryReviewer,
            String notes,
            long updatedEpochMillis) {}

    private final Map<String, ReviewRecord> records = new ConcurrentHashMap<>();
    private final Queue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final Path backingFile;
    private final Object fileLock = new Object();

    /** In-memory workflow (tests). */
    public ReviewWorkflow() {
        this.backingFile = null;
    }

    /** Restart-durable workflow backed by an append-only JSONL state log at {@code backingFile}. */
    public ReviewWorkflow(Path backingFile) throws IOException {
        this.backingFile = Objects.requireNonNull(backingFile, "backingFile");
        loadFromDisk();
    }

    public ReviewRecord open(String caseId, long epochMillis) {
        String id = Objects.requireNonNull(caseId, "caseId");
        ReviewRecord rec = records.computeIfAbsent(id, k -> {
            trackKey(k);
            return new ReviewRecord(k, Status.OPEN, null, null, "", epochMillis);
        });
        return rec;
    }

    /** Records the first review pass by {@code reviewer}. */
    public ReviewRecord recordFirstReview(String caseId, String reviewer, String notes, long epochMillis) {
        String cleanReviewer = requireReviewer(reviewer);
        ReviewRecord updated = records.compute(caseId, (id, existing) -> {
            if (existing == null) {
                trackKey(id);
            }
            return new ReviewRecord(
                    id,
                    Status.FIRST_REVIEWED,
                    cleanReviewer,
                    existing != null ? existing.secondaryReviewer() : null,
                    notes == null ? "" : notes,
                    epochMillis);
        });
        persistIfConfigured(updated);
        return updated;
    }

    /**
     * Records the independent second reviewer confirmation. Fails if {@code secondReviewer} matches the first
     * reviewer or if no first review exists yet.
     */
    public ReviewRecord confirmSecondReview(String caseId, String secondReviewer, String notes, long epochMillis) {
        String cleanSecond = requireReviewer(secondReviewer);
        ReviewRecord updated = records.compute(caseId, (id, existing) -> {
            if (existing == null || existing.primaryReviewer() == null) {
                throw new IllegalStateException("case " + caseId + " requires a first review before second-reviewer confirmation");
            }
            if (existing.primaryReviewer().equalsIgnoreCase(cleanSecond)) {
                throw new IllegalArgumentException("four-eyes violation: second reviewer must differ from " + existing.primaryReviewer());
            }
            String combinedNotes = existing.notes().isBlank() ? (notes == null ? "" : notes)
                    : (notes == null || notes.isBlank() ? existing.notes() : existing.notes() + "; " + notes);
            return new ReviewRecord(id, Status.CONFIRMED_FOUR_EYES, existing.primaryReviewer(), cleanSecond, combinedNotes, epochMillis);
        });
        persistIfConfigured(updated);
        return updated;
    }

    public ReviewRecord submitAppeal(String caseId, String reason, long epochMillis) {
        ReviewRecord updated = records.compute(caseId, (id, existing) -> {
            if (existing == null) {
                trackKey(id);
            }
            return new ReviewRecord(
                    id,
                    Status.APPEALED,
                    existing != null ? existing.primaryReviewer() : null,
                    existing != null ? existing.secondaryReviewer() : null,
                    reason == null ? "" : reason,
                    epochMillis);
        });
        persistIfConfigured(updated);
        return updated;
    }

    /** Resolves an appeal with distinct reviewer check when confirming ({@code upheld == true}). */
    public ReviewRecord resolveAppeal(String caseId, String reviewer, boolean upheld, String notes, long epochMillis) {
        String cleanReviewer = requireReviewer(reviewer);
        ReviewRecord updated = records.compute(caseId, (id, existing) -> {
            if (upheld && existing != null && existing.primaryReviewer() != null
                    && existing.primaryReviewer().equalsIgnoreCase(cleanReviewer)) {
                throw new IllegalArgumentException("appeal upholding requires a different reviewer than " + existing.primaryReviewer());
            }
            if (existing == null) {
                trackKey(id);
            }
            return new ReviewRecord(
                    id,
                    upheld ? Status.UPHELD : Status.OVERTURNED,
                    existing != null && existing.primaryReviewer() != null ? existing.primaryReviewer() : cleanReviewer,
                    upheld ? cleanReviewer : (existing != null ? existing.secondaryReviewer() : null),
                    notes == null ? "" : notes,
                    epochMillis);
        });
        persistIfConfigured(updated);
        return updated;
    }

    public Optional<ReviewRecord> status(String caseId) {
        return Optional.ofNullable(records.get(caseId));
    }

    private void trackKey(String caseId) {
        insertionOrder.add(caseId);
        while (records.size() > MAX_IN_MEMORY_RECORDS) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                break;
            }
            records.remove(oldest);
        }
    }

    private void loadFromDisk() throws IOException {
        if (backingFile == null || !Files.isRegularFile(backingFile)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(backingFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                ReviewRecord parsed = parseJsonLine(line);
                if (parsed != null) {
                    boolean added = records.put(parsed.caseId(), parsed) == null;
                    if (added) {
                        trackKey(parsed.caseId());
                    }
                }
            }
        }
    }

    private void persistIfConfigured(ReviewRecord record) {
        if (backingFile == null || record == null) {
            return;
        }
        synchronized (fileLock) {
            try {
                Path parent = backingFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] bytes = (toJsonLine(record) + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(backingFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer buf = ByteBuffer.wrap(bytes);
                    while (buf.hasRemaining()) {
                        channel.write(buf);
                    }
                    channel.force(true);
                }
            } catch (IOException ignored) {
                // Non-fatal: in-memory state and AuditLedger still preserve workflow state
            }
        }
    }

    private static String toJsonLine(ReviewRecord r) {
        return "{\"caseId\":\"" + escape(r.caseId())
                + "\",\"status\":\"" + r.status().name()
                + "\",\"primary\":" + (r.primaryReviewer() == null ? "null" : "\"" + escape(r.primaryReviewer()) + "\"")
                + ",\"secondary\":" + (r.secondaryReviewer() == null ? "null" : "\"" + escape(r.secondaryReviewer()) + "\"")
                + ",\"notes\":\"" + escape(r.notes() == null ? "" : r.notes())
                + "\",\"updated\":" + r.updatedEpochMillis() + "}";
    }

    private static ReviewRecord parseJsonLine(String line) {
        Map<String, Object> obj;
        try {
            obj = MinimalJson.parseObject(line);
        } catch (RuntimeException ignored) {
            return null;
        }
        String caseId = MinimalJson.getString(obj, "caseId");
        String statusStr = MinimalJson.getString(obj, "status");
        if (caseId == null || statusStr == null) {
            return null;
        }
        Status status;
        try {
            status = Status.valueOf(statusStr);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String primary = MinimalJson.getString(obj, "primary");
        String secondary = MinimalJson.getString(obj, "secondary");
        String notes = MinimalJson.getString(obj, "notes");
        long updated = MinimalJson.getLong(obj, "updated", 0L);
        return new ReviewRecord(caseId, status, primary, secondary, notes == null ? "" : notes, updated);
    }

    private static String escape(String s) {
        return MinimalJson.escape(s);
    }

    /** Generates a clear, non-technical plain-language explanation suitable for staff and player appeal views (VER-15). */
    public static String plainLanguageExplanation(VerdictCase c) {
        Evidence e = c.evidence();
        String classDesc = switch (e.evidenceClass()) {
            case ADVANTAGE_PROOF -> "Mathematically impossible under vanilla 1.21.11 client mechanics with all lag and uncertainty bounded";
            case MODIFICATION_PROOF -> "Client sent a protocol pattern that an unmodified vanilla client never sends (not proof of unfair advantage by itself)";
            case DESIGN_STATISTIC -> "Randomized design probe signal (informational only, never triggers punishment)";
            case NOMINAL_STATISTIC -> "Statistical pattern differing from typical baseline (informational only, never triggers punishment)";
            case CONTEXT -> "Session context note for staff review";
        };
        return "Case " + c.id().substring(0, Math.min(8, c.id().length()))
                + " | Check: " + e.check() + " (" + e.evidenceClass().code() + ", group " + c.dependencyGroup().id() + ")"
                + " | Reason: " + classDesc + ": " + e.summary()
                + " | Window: seq " + e.fromSequence() + ".." + e.toSequence()
                + " | Identity tier: " + e.admissibility().identity()
                + " | Action: " + c.authorizedAction()
                + (c.certificate().evidenceClass() == EvidenceClass.ADVANTAGE_PROOF
                        ? " | Re-verification: " + c.certificate().verificationStatus() : "");
    }

    private static String requireReviewer(String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new IllegalArgumentException("reviewer must be non-blank");
        }
        return reviewer.strip();
    }
}
