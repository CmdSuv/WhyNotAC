package dev.whynotac.recording.write;

import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.EventCodec;

/**
 * Turns one session's events into sealed blocks. A block is sealed when its raw size reaches
 * {@code targetBlockBytes} or when it spans more than {@code maxBlockAgeNanos} of event time, which
 * bounds how much a crash can lose. Confined to the session's analysis thread.
 */
public final class RecordingWriter {

    public static final int DEFAULT_TARGET_BLOCK_BYTES = 64 * 1024;
    public static final long DEFAULT_MAX_BLOCK_AGE_NANOS = 5_000_000_000L;

    private final BlockSink sink;
    private final int targetBlockBytes;
    private final long maxBlockAgeNanos;
    private final EventCodec.Encoder encoder = new EventCodec.Encoder();
    private final ByteSink current;

    private boolean blockOpen;
    private long baseSequence;
    private long baseNanoTime;
    private long baseClientTick;
    private long lastClientTick;
    private int eventsInBlock;
    private long lostPending;
    private long totalEvents;
    private long lostTotal;
    private boolean closed;

    public RecordingWriter(BlockSink sink, int targetBlockBytes, long maxBlockAgeNanos) {
        this.sink = sink;
        this.targetBlockBytes = targetBlockBytes;
        this.maxBlockAgeNanos = maxBlockAgeNanos;
        this.current = new ByteSink(targetBlockBytes + 1024);
    }

    public void append(TimelineEvent event) {
        if (closed) {
            throw new IllegalStateException("recording already closed");
        }
        if (blockOpen && event.nanoTime() - baseNanoTime > maxBlockAgeNanos) {
            seal();
        }
        if (!blockOpen) {
            blockOpen = true;
            baseSequence = event.sequence();
            baseNanoTime = event.nanoTime();
            baseClientTick = lastClientTick;
            encoder.reset(baseSequence, baseNanoTime, baseClientTick);
        }
        encoder.encode(event, current);
        if (event instanceof TimelineEvent.Inbound inbound) {
            lastClientTick = inbound.clientTick();
        }
        eventsInBlock++;
        totalEvents++;
        if (current.size() >= targetBlockBytes) {
            seal();
        }
    }

    /**
     * Records that {@code events} events were lost at this point of the timeline (e.g. dropped before
     * login completed). The open block is sealed first, so the loss marker sits exactly between the
     * events written so far and the next one.
     */
    public void reportLost(long events) {
        if (events <= 0) {
            return;
        }
        seal();
        lostPending += events;
        lostTotal += events;
    }

    public void close() {
        if (closed) {
            return;
        }
        seal();
        closed = true;
        sink.close(totalEvents, lostTotal);
    }

    private void seal() {
        if (!blockOpen) {
            return;
        }
        Block block = new Block(baseSequence, baseNanoTime, baseClientTick, eventsInBlock, lostPending, current.toByteArray());
        if (sink.accept(block)) {
            lostPending = 0;
        } else {
            lostPending += eventsInBlock;
            lostTotal += eventsInBlock;
        }
        current.reset();
        eventsInBlock = 0;
        blockOpen = false;
    }

    public long totalEvents() {
        return totalEvents;
    }

    public long lostEvents() {
        return lostTotal;
    }
}
