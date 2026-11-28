package dev.whynotac.verdict;

import dev.whynotac.analysis.policy.ActionPolicy;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Delayed/jittered temporary-ban wave queue (VER-12).
 *
 * <p>When configured with {@code banWaveDelayMillis > 0}, holds automatic temporary bans until a jittered
 * wave delay elapses, then re-verifies every safety invariant at execution time ({@link EvidenceCertificate}
 * integrity, {@link CircuitBreaker}, {@link FalseBanBudget}, {@link ActionPolicy}, and cancellation state)
 * before issuing the ban.
 */
public final class BanWaveQueue implements AutoCloseable {

    private static final int MAX_CANCELLED_SUBJECTS = 10_000;
    public static final long MAX_RECOVERY_AGE_MILLIS = 86_400_000L; // 24 hours

    /** One unexpired, uncancelled ban-wave entry recovered from {@code ban-wave-queue.jsonl} after restart. */
    public record RecoveredEntry(
            String caseId,
            UUID playerId,
            String check,
            String certificateSha256,
            long queuedAtMillis,
            long delayMillis,
            long remainingDelayMillis) {}

    private volatile VerdictConfig config;
    private final CircuitBreaker circuitBreaker;
    private final FalseBanBudget falseBanBudget;
    private final ScheduledExecutorService scheduler;
    private final SplittableRandom rng;
    private final Map<UUID, Long> cancelledBeforeMillis = new ConcurrentHashMap<>();
    private final Queue<UUID> cancelledOrder = new ConcurrentLinkedQueue<>();
    private final Set<ScheduledFuture<?>> pendingFutures = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final Object journalLock = new Object();
    private volatile Path journalFile;

    public BanWaveQueue(VerdictConfig config, CircuitBreaker circuitBreaker, FalseBanBudget falseBanBudget) {
        this(config, circuitBreaker, falseBanBudget, 0xB417_4E12L);
    }

    BanWaveQueue(VerdictConfig config, CircuitBreaker circuitBreaker, FalseBanBudget falseBanBudget, long seed) {
        this.config = Objects.requireNonNull(config, "config");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.falseBanBudget = Objects.requireNonNull(falseBanBudget, "falseBanBudget");
        this.rng = new SplittableRandom(seed);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WhyNotAC BanWaveQueue");
            t.setDaemon(true);
            return t;
        });
    }

    /** Enqueues a verified temporary ban (executes immediately when {@code banWaveDelayMillis == 0}). */
    public void enqueue(VerdictDecision decision, Consumer<VerdictDecision> executor) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(executor, "executor");
        long baseDelay = config.banWaveDelayMillis();
        if (baseDelay <= 0L) {
            executeIfSafe(decision, executor);
            return;
        }
        long delayMillis = computeJitteredDelay(baseDelay);
        pendingCount.incrementAndGet();
        appendJournalEntry("QUEUED", decision, delayMillis);
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                executeIfSafe(decision, executor);
            } finally {
                pendingCount.decrementAndGet();
                if (holder[0] != null) {
                    pendingFutures.remove(holder[0]);
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        holder[0] = future;
        pendingFutures.add(future);
    }

    /** Atomically updates the active verdict config so hot-reloads immediately govern pending waves. */
    public void updateConfig(VerdictConfig newConfig) {
        this.config = Objects.requireNonNull(newConfig, "newConfig");
    }

    /** Attaches an optional append-only JSONL journal recording queued, cancelled, and executed ban wave events. */
    public void attachJournal(Path journalPath) {
        this.journalFile = journalPath;
    }

    /** Prevents any queued ban opened at or before {@code cancelledAtMillis} from firing (e.g. overturn/GDPR). */
    public void cancelSubject(UUID playerId, long cancelledAtMillis) {
        if (playerId != null) {
            boolean isNew = cancelledBeforeMillis.putIfAbsent(playerId, cancelledAtMillis) == null;
            if (isNew) {
                cancelledOrder.add(playerId);
                while (cancelledBeforeMillis.size() > MAX_CANCELLED_SUBJECTS) {
                    UUID evicted = cancelledOrder.poll();
                    if (evicted == null) {
                        break;
                    }
                    cancelledBeforeMillis.remove(evicted);
                }
            } else {
                cancelledBeforeMillis.merge(playerId, cancelledAtMillis, Math::max);
            }
            appendCancelJournalEntry(playerId, cancelledAtMillis);
        }
    }

    /**
     * Scans a persisted {@code ban-wave-queue.jsonl} journal on startup and reschedules every unexpired
     * (within 24h), uncancelled, unexecuted {@code QUEUED} entry with full execution-time invariant checks
     * ({@code autoBanEnabled}, {@code armedChecks}, {@link CircuitBreaker}, {@link FalseBanBudget},
     * cancellation cutoff, and 64-hex {@code certificateSha256}). Returns the number of recovered entries.
     */
    public int recoverFromJournal(Path journalPath, long nowMillis, Consumer<RecoveredEntry> executor) throws IOException {
        Objects.requireNonNull(journalPath, "journalPath");
        Objects.requireNonNull(executor, "executor");
        if (!Files.isRegularFile(journalPath)) {
            return 0;
        }
        Map<String, RecoveredEntry> pendingByCase = new java.util.LinkedHashMap<>();
        java.util.Set<String> completedCases = new java.util.HashSet<>();
        Map<UUID, Long> journalCancellations = new java.util.HashMap<>();
        for (String line : Files.readAllLines(journalPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String state = readJsonString(line, "state");
            if (state == null) {
                continue;
            }
            long ts = readJsonLong(line, "timestampMs", 0L);
            String caseId = readJsonString(line, "caseId");
            String playerStr = readJsonString(line, "playerId");
            UUID playerId = null;
            if (playerStr != null && !playerStr.isEmpty()) {
                try {
                    playerId = UUID.fromString(playerStr);
                } catch (IllegalArgumentException ignored) { }
            }
            switch (state) {
                case "QUEUED" -> {
                    String check = readJsonString(line, "check");
                    String cert = readJsonString(line, "certificateSha256");
                    long delayMs = Math.max(0L, readJsonLong(line, "delayMs", 0L));
                    if (caseId != null && !caseId.isEmpty() && playerId != null
                            && check != null && !check.isEmpty()
                            && cert != null && cert.length() == 64) {
                        long remaining = Math.max(50L, (ts + delayMs) - nowMillis);
                        pendingByCase.put(caseId, new RecoveredEntry(caseId, playerId, check, cert, ts, delayMs, remaining));
                    }
                }
                case "EXECUTED" -> {
                    if (caseId != null) {
                        completedCases.add(caseId);
                    }
                }
                case "CANCELLED" -> {
                    if (playerId != null) {
                        journalCancellations.merge(playerId, ts, Math::max);
                    }
                }
                default -> { }
            }
        }
        for (Map.Entry<UUID, Long> cancel : journalCancellations.entrySet()) {
            cancelledBeforeMillis.merge(cancel.getKey(), cancel.getValue(), Math::max);
        }
        int recovered = 0;
        for (RecoveredEntry entry : pendingByCase.values()) {
            if (completedCases.contains(entry.caseId())) {
                continue;
            }
            if (nowMillis - entry.queuedAtMillis() > MAX_RECOVERY_AGE_MILLIS) {
                continue;
            }
            Long cutoff = cancelledBeforeMillis.get(entry.playerId());
            if (cutoff != null && entry.queuedAtMillis() <= cutoff) {
                continue;
            }
            pendingCount.incrementAndGet();
            recovered++;
            ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
            ScheduledFuture<?> future = scheduler.schedule(() -> {
                try {
                    executeRecoveredIfSafe(entry, executor);
                } finally {
                    pendingCount.decrementAndGet();
                    if (holder[0] != null) {
                        pendingFutures.remove(holder[0]);
                    }
                }
            }, entry.remainingDelayMillis(), TimeUnit.MILLISECONDS);
            holder[0] = future;
            pendingFutures.add(future);
        }
        return recovered;
    }

    private void executeRecoveredIfSafe(RecoveredEntry entry, Consumer<RecoveredEntry> executor) {
        VerdictConfig active = this.config;
        if (!active.autoBanEnabled() || !isHex64(entry.certificateSha256())) {
            return;
        }
        if (circuitBreaker.isSuppressed() || falseBanBudget.isLocked()) {
            return;
        }
        Long cutoff = cancelledBeforeMillis.get(entry.playerId());
        if (cutoff != null && entry.queuedAtMillis() <= cutoff) {
            return;
        }
        if (!active.armedChecks().contains(entry.check())) {
            return;
        }
        falseBanBudget.recordAutoBanIssued();
        appendRawJournalLine("{\"timestampMs\":" + System.currentTimeMillis()
                + ",\"state\":\"EXECUTED\",\"caseId\":\"" + escapeJson(entry.caseId())
                + "\",\"playerId\":\"" + entry.playerId()
                + "\",\"check\":\"" + escapeJson(entry.check())
                + "\",\"delayMs\":0,\"certificateSha256\":\"" + escapeJson(entry.certificateSha256()) + "\"}\n");
        executor.accept(entry);
    }

    private static boolean isHex64(String s) {
        if (s == null || s.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void appendCancelJournalEntry(UUID playerId, long cancelledAtMillis) {
        appendRawJournalLine("{\"timestampMs\":" + cancelledAtMillis
                + ",\"state\":\"CANCELLED\",\"caseId\":\"\",\"playerId\":\"" + playerId
                + "\",\"check\":\"\",\"delayMs\":0,\"certificateSha256\":\"\"}\n");
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String readJsonString(String json, String key) {
        String token = "\"" + key + "\":\"";
        int idx = json.indexOf(token);
        if (idx < 0) {
            return null;
        }
        int i = idx + token.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && i < json.length()) {
                sb.append(json.charAt(i++));
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private static long readJsonLong(String json, String key, long def) {
        String token = "\"" + key + "\":";
        int idx = json.indexOf(token);
        if (idx < 0) {
            return def;
        }
        int i = idx + token.length();
        int start = i;
        if (i < json.length() && json.charAt(i) == '-') {
            i++;
        }
        while (i < json.length() && json.charAt(i) >= '0' && json.charAt(i) <= '9') {
            i++;
        }
        if (i == start) {
            return def;
        }
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int pendingCount() {
        return Math.max(0, pendingCount.get());
    }

    private long computeJitteredDelay(long baseDelay) {
        long min = Math.max(1L, baseDelay / 2L);
        long max = Math.max(min + 1L, baseDelay + baseDelay / 4L);
        synchronized (rng) {
            return min + rng.nextLong(max - min + 1L);
        }
    }

    private void executeIfSafe(VerdictDecision decision, Consumer<VerdictDecision> executor) {
        VerdictConfig active = this.config;
        if (!active.autoBanEnabled() || !decision.certificate().verifyIntegrity()) {
            return;
        }
        if (circuitBreaker.isSuppressed() || falseBanBudget.isLocked()) {
            return;
        }
        UUID subjectId = decision.session() != null && decision.session().identity() != null
                ? decision.session().identity().playerId()
                : null;
        if (subjectId != null) {
            Long cutoff = cancelledBeforeMillis.get(subjectId);
            if (cutoff != null && decision.verdictCase().openedEpochMillis() <= cutoff) {
                return;
            }
        }
        boolean allowed = active.armedChecks().contains(decision.evidence().check())
                && ActionPolicy.enabled(decision.evidence().evidenceClass(), Action.TEMP_BAN,
                        decision.evidence().admissibility().identity(), Lifecycle.ARMED, true);
        if (!allowed) {
            return;
        }
        falseBanBudget.recordAutoBanIssued();
        appendJournalEntry("EXECUTED", decision, 0L);
        executor.accept(decision);
    }

    private void appendJournalEntry(String state, VerdictDecision decision, long delayMillis) {
        Path file = this.journalFile;
        if (file == null || decision == null) {
            return;
        }
        UUID subjectId = decision.session() != null && decision.session().identity() != null
                ? decision.session().identity().playerId()
                : null;
        String line = "{\"timestampMs\":" + System.currentTimeMillis()
                + ",\"state\":\"" + state
                + "\",\"caseId\":\"" + escapeJson(decision.verdictCase().id())
                + "\",\"playerId\":\"" + (subjectId == null ? "" : subjectId)
                + "\",\"check\":\"" + escapeJson(decision.evidence().check())
                + "\",\"delayMs\":" + delayMillis
                + ",\"certificateSha256\":\"" + escapeJson(decision.certificate().sha256Digest()) + "\"}\n";
        appendRawJournalLine(line);
    }

    private void appendRawJournalLine(String line) {
        Path file = this.journalFile;
        if (file == null) {
            return;
        }
        synchronized (journalLock) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                try (FileChannel ch = FileChannel.open(file,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer buf = ByteBuffer.wrap(bytes);
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                    ch.force(true);
                }
            } catch (IOException ignored) {
                // Non-fatal: in-memory wave queue and AuditLedger continue operating
            }
        }
    }

    @Override
    public void close() {
        for (ScheduledFuture<?> future : pendingFutures) {
            future.cancel(false);
        }
        scheduler.shutdownNow();
    }
}
