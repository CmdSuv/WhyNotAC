package dev.whynotac.verdict;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.verdict.EvidenceCertificate.VerificationStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Independent deterministic second verifier for {@code P-A} findings (VER-05, VER-06).
 *
 * <p>Prepares a replay handle synchronously on the session's analysis thread (so session-confined state like
 * {@code RecordingListener.recordingFile(session)} is read safely), then executes an isolated replay on a
 * background worker with a fresh {@link SessionRegistry}, fresh vanilla replicas, and a fresh check instance.
 */
public final class SecondVerifier implements AutoCloseable {

    /** Worker-safe supplier of timeline events for one verification job. */
    @FunctionalInterface
    public interface ReplayHandle {
        Optional<Iterable<TimelineEvent>> open() throws IOException;
    }

    /** Analysis-thread factory creating a worker-safe {@link ReplayHandle} for {@code session}. */
    @FunctionalInterface
    public interface ReplaySource {
        ReplayHandle prepare(PlayerSession session);

        static ReplaySource none() {
            return session -> Optional::empty;
        }

        /**
         * Reads the finished {@code .wnrec} path returned by {@code pathLookup} on the analysis thread, falling back
         * on the worker to the live {@code .wnrec.part} file when the session has not disconnected yet, or to the
         * session's bounded in-memory event ring buffer ({@link PlayerSession#recentEventsSnapshot()}) when disk
         * recording is disabled or has not flushed yet.
         */
        static ReplaySource fromRecordingPaths(Function<PlayerSession, Optional<Path>> pathLookup) {
            return session -> {
                Optional<Path> finished = (session == null || pathLookup == null) ? Optional.empty() : pathLookup.apply(session);
                List<TimelineEvent> memoryFallback = session == null ? List.of() : session.recentEventsSnapshot();
                return () -> {
                    if (finished.isPresent()) {
                        Path path = finished.get();
                        if (Files.isRegularFile(path)) {
                            return Optional.of(RecordingReader.open(path));
                        }
                        Path partial = path.resolveSibling(path.getFileName() + ".part");
                        if (Files.isRegularFile(partial)) {
                            return Optional.of(RecordingReader.open(partial));
                        }
                    }
                    if (!memoryFallback.isEmpty()) {
                        return Optional.of(memoryFallback);
                    }
                    return Optional.empty();
                };
            };
        }
    }

    public record VerificationResult(VerificationStatus status, String details, long elapsedNanos, int reproducedCount) {}

    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);

    private final ReplaySource replaySource;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public SecondVerifier(ReplaySource replaySource, int workerThreads) {
        this(replaySource, Executors.newFixedThreadPool(Math.max(1, workerThreads), r -> {
            Thread t = new Thread(r, "WhyNotAC-SecondVerifier-" + THREAD_ID.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return t;
        }), true);
    }

    SecondVerifier(ReplaySource replaySource, ExecutorService executor, boolean ownsExecutor) {
        this.replaySource = replaySource == null ? ReplaySource.none() : replaySource;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** Prepares replay handle on the caller (analysis) thread, then runs verification asynchronously. */
    public CompletableFuture<VerificationResult> verifyAsync(PlayerSession session, Evidence expected) {
        if (executor.isShutdown()) {
            return CompletableFuture.completedFuture(
                    new VerificationResult(VerificationStatus.ERROR, "second verifier shut down", 0L, 0));
        }
        ReplayHandle handle = replaySource.prepare(session);
        long connectionId = session == null ? -1L : session.connectionId();
        return CompletableFuture.supplyAsync(() -> verifyHandle(connectionId, handle, expected), executor);
    }

    /** Runs independent replay synchronously (analysis thread / deterministic unit tests). */
    public VerificationResult verifyNow(PlayerSession session, Evidence expected) {
        ReplayHandle handle = replaySource.prepare(session);
        long connectionId = session == null ? -1L : session.connectionId();
        return verifyHandle(connectionId, handle, expected);
    }

    private VerificationResult verifyHandle(long connectionId, ReplayHandle handle, Evidence expected) {
        long start = System.nanoTime();
        if (expected == null) {
            return new VerificationResult(VerificationStatus.ERROR, "null evidence", System.nanoTime() - start, 0);
        }
        int currentVersion = Checks.version(expected.check());
        if (currentVersion < 0 || currentVersion != expected.checkVersion()) {
            return new VerificationResult(VerificationStatus.ERROR,
                    "check version mismatch: expected v" + expected.checkVersion() + ", running v" + currentVersion,
                    System.nanoTime() - start, 0);
        }
        Iterable<TimelineEvent> events;
        try {
            Optional<Iterable<TimelineEvent>> opened = handle.open();
            if (opened.isEmpty()) {
                return new VerificationResult(VerificationStatus.NO_RECORDING,
                        "no recording available for independent second verification",
                        System.nanoTime() - start, 0);
            }
            events = opened.get();
        } catch (IOException | RuntimeException e) {
            return new VerificationResult(VerificationStatus.ERROR,
                    "cannot open recording: " + e.getMessage(),
                    System.nanoTime() - start, 0);
        }

        List<Evidence> found = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        EvidenceSink sink = new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence evidence) {
                found.add(evidence);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility admissibility) {}
        };
        TimelineListener isolatedCheck = Checks.create(expected.check(), sink);
        if (isolatedCheck == null) {
            return new VerificationResult(VerificationStatus.ERROR,
                    "unknown check " + expected.check(),
                    System.nanoTime() - start, 0);
        }

        SessionRegistry isolatedRegistry = new SessionRegistry(Runnable::run, 1, (s, ev, err) -> errors.add(err));
        isolatedRegistry.addListener(isolatedCheck);
        String replayId = "verify:" + (connectionId < 0 ? "offline" : connectionId);
        PlayerSession replaySession = isolatedRegistry.openReplay(replayId);
        long maxSequenceSeen = Long.MIN_VALUE;
        TimelineEvent last = null;
        for (TimelineEvent event : events) {
            replaySession.deliver(event);
            maxSequenceSeen = Math.max(maxSequenceSeen, event.sequence());
            last = event;
        }
        if (!replaySession.isClosed()) {
            long seq = last == null ? 0L : last.sequence() + 1L;
            long nanos = last == null ? 0L : last.nanoTime();
            replaySession.deliver(new TimelineEvent.Disconnected(seq, nanos));
        }

        long elapsed = System.nanoTime() - start;
        if (!errors.isEmpty()) {
            return new VerificationResult(VerificationStatus.ERROR,
                    "replay listener error: " + errors.getFirst().getMessage(), elapsed, found.size());
        }
        String expectedFp = expected.fingerprint();
        boolean matched = found.stream().anyMatch(e -> e.fingerprint().equals(expectedFp));
        if (matched) {
            return new VerificationResult(VerificationStatus.VERIFIED,
                    "reproduced fingerprint via isolated replay (" + found.size() + " findings)", elapsed, found.size());
        }
        if (maxSequenceSeen < expected.toSequence()) {
            return new VerificationResult(VerificationStatus.NO_RECORDING,
                    "recording has reached seq " + maxSequenceSeen + " (< window end " + expected.toSequence() + ")",
                    elapsed, found.size());
        }
        return new VerificationResult(VerificationStatus.MISMATCH,
                "expected fingerprint not reproduced over seq " + expected.fromSequence() + ".." + expected.toSequence()
                        + " (findings=" + found.size() + ")",
                elapsed, found.size());
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }
}
