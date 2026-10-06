package dev.whynotac.recording.write;

import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.Deflater;

/**
 * Owns the recordings directory and the single I/O thread that writes every recording.
 *
 * <p>Producers (analysis threads) never touch the disk: sealed blocks are queued for the I/O thread,
 * subject to a byte budget. A block that does not fit the budget is rejected, and the writer turns
 * that into an explicit loss marker in the next block. Compression happens on the I/O thread.
 */
public final class RecordingStore implements AutoCloseable {

    /**
     * @param maxQueuedBytes upper bound of raw block bytes waiting for the I/O thread
     * @param maxAge         recordings older than this are deleted
     * @param maxTotalBytes  oldest recordings are deleted while the directory exceeds this
     */
    public record Settings(long maxQueuedBytes, Duration maxAge, long maxTotalBytes, int targetBlockBytes, long maxBlockAgeNanos) {
        public static Settings defaults() {
            return new Settings(64L << 20, Duration.ofDays(14), 2L << 30, RecordingWriter.DEFAULT_TARGET_BLOCK_BYTES,
                    RecordingWriter.DEFAULT_MAX_BLOCK_AGE_NANOS);
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC);

    private final Path directory;
    private final Settings settings;
    private final Logger logger;
    private final ExecutorService io;
    private final AtomicLong queuedBytes = new AtomicLong();
    private final Set<Path> open = ConcurrentHashMap.newKeySet();
    private final AtomicLong rejectedBlocks = new AtomicLong();
    private final AtomicLong failedRecordings = new AtomicLong();
    private Deflater deflater; // I/O thread only

    public RecordingStore(Path directory, Settings settings, Logger logger) {
        this.directory = directory;
        this.settings = settings;
        this.logger = logger;
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WhyNotAC Recording I/O");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Settings settings() {
        return settings;
    }

    public Path directory() {
        return directory;
    }

    /** Starts a recording; the file is created lazily by the I/O thread. */
    public RecordingWriter open(RecordingHeader header) {
        Instant start = Instant.ofEpochMilli(header.startEpochMillis());
        String base = TIME.format(start) + "-" + header.recordingId().toString().substring(0, 8);
        Path partial = directory.resolve(DAY.format(start)).resolve(base + RecordingFormat.PARTIAL_SUFFIX);
        Path finished = partial.resolveSibling(base + RecordingFormat.FILE_SUFFIX);
        open.add(partial);
        FileSink sink = new FileSink(header, partial, finished);
        return new RecordingWriter(sink, settings.targetBlockBytes(), settings.maxBlockAgeNanos());
    }

    /** Blocks rejected so far because the I/O queue was over budget. */
    public long rejectedBlocks() {
        return rejectedBlocks.get();
    }

    public long failedRecordings() {
        return failedRecordings.get();
    }

    /** Schedules retention enforcement on the I/O thread. */
    public void scheduleRetention() {
        io.execute(this::enforceRetention);
    }

    /** Deletes recordings beyond the age limit, then the oldest ones while over the size limit. */
    void enforceRetention() {
        try {
            if (!Files.isDirectory(directory)) {
                return;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(directory, 2)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(RecordingFormat.FILE_SUFFIX)
                                || p.getFileName().toString().endsWith(RecordingFormat.PARTIAL_SUFFIX))
                        .filter(p -> !open.contains(p))
                        .forEach(files::add);
            }
            files.sort(Comparator.comparing(RecordingStore::modified));
            Instant cutoff = Instant.now().minus(settings.maxAge());
            long total = 0;
            for (Path file : files) {
                total += Files.size(file);
            }
            for (Path file : files) {
                boolean tooOld = modified(file).toInstant().isBefore(cutoff);
                if (tooOld || total > settings.maxTotalBytes()) {
                    long size = Files.size(file);
                    Files.deleteIfExists(file);
                    total -= size;
                }
            }
            try (DirectoryStream<Path> days = Files.newDirectoryStream(directory, Files::isDirectory)) {
                for (Path day : days) {
                    try (Stream<Path> entries = Files.list(day)) {
                        if (entries.findAny().isEmpty()) {
                            Files.deleteIfExists(day);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "WhyNotAC recording retention failed", e);
        }
    }

    private static FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    /** Waits for queued writes to finish (bounded), then stops the I/O thread. */
    @Override
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("WhyNotAC recording I/O did not finish within 10 s; " + open.size() + " recording(s) stay .part");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private Deflater deflater() {
        if (deflater == null) {
            deflater = new Deflater(Deflater.BEST_SPEED);
        }
        return deflater;
    }

    /** Per-recording sink; every disk operation runs on the I/O thread, in submission order. */
    private final class FileSink implements BlockSink {

        private final RecordingHeader header;
        private final Path partial;
        private final Path finished;
        private FileChannel channel; // I/O thread only
        private boolean failed;      // I/O thread only

        FileSink(RecordingHeader header, Path partial, Path finished) {
            this.header = header;
            this.partial = partial;
            this.finished = finished;
        }

        @Override
        public boolean accept(Block block) {
            long size = block.raw().length;
            if (queuedBytes.addAndGet(size) > settings.maxQueuedBytes()) {
                queuedBytes.addAndGet(-size);
                rejectedBlocks.incrementAndGet();
                return false;
            }
            try {
                io.execute(() -> {
                    try {
                        write(block.encode(deflater()));
                    } finally {
                        queuedBytes.addAndGet(-size);
                    }
                });
                return true;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                queuedBytes.addAndGet(-size);
                rejectedBlocks.incrementAndGet();
                return false;
            }
        }

        @Override
        public void close(long totalEvents, long lostEvents) {
            try {
                io.execute(() -> finish(totalEvents, lostEvents));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                open.remove(partial); // store is shutting down: the file stays .part and remains readable
            }
        }

        private void write(byte[] bytes) {
            if (failed) {
                return;
            }
            try {
                if (channel == null) {
                    Files.createDirectories(partial.getParent());
                    channel = FileChannel.open(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    writeFully(header.encode());
                }
                writeFully(bytes);
            } catch (IOException e) {
                fail(e);
            }
        }

        private void finish(long totalEvents, long lostEvents) {
            try {
                ByteSink end = new ByteSink(32);
                EventCodec.Encoder.encodeEnd(totalEvents, lostEvents, end);
                // The end record travels in its own block so that it is checksummed like everything else.
                write(new Block(0, 0, 0, 0, 0, end.toByteArray()).encode(deflater()));
                if (!failed && channel != null) {
                    channel.force(true);
                    channel.close();
                    Files.move(partial, finished, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException e) {
                fail(e);
            } finally {
                open.remove(partial);
            }
        }

        private void writeFully(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }

        private void fail(IOException e) {
            if (!failed) {
                failed = true;
                failedRecordings.incrementAndGet();
                logger.log(Level.WARNING, "WhyNotAC recording " + partial + " failed; the rest of this session is not recorded", e);
                try {
                    if (channel != null) {
                        channel.close();
                    }
                } catch (IOException ignored) {
                    // already failing
                }
            }
        }
    }
}
