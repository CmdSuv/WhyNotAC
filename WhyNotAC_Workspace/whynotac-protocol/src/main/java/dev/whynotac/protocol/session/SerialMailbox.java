package dev.whynotac.protocol.session;

import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Bounded actor mailbox: many producers, one logical consumer at a time.
 *
 * <p>Producers (network threads) only enqueue and maybe schedule a drain, so they never wait for
 * analysis. The drain runs on a shared executor, at most one drain per mailbox at a time, which
 * gives each session strict FIFO processing without locks in the consumers. The {@code scheduled}
 * flag's CAS plus the concurrent queue provide the happens-before edge between drains that may run
 * on different pool threads.
 *
 * <p>If the executor rejects work (it was shut down), delivery degrades to caller-runs instead of
 * silently stranding queued events: shutdown must still deliver every {@code Disconnected}.
 *
 * <p>Two bounds protect the server: one per session (a client flooding packets faster than analysis
 * runs) and one shared by all sessions (many connections at once, e.g. a bot attack), so the total
 * memory held by queued events stays bounded whatever the number of connections.
 * Overflow is handled by the caller ({@link PlayerSession}), which turns dropped events into an
 * explicit {@link TimelineEvent.Gap}.
 *
 * <p>Both bounds count units, not events: an event weighs one unit (about the measured 112 bytes of an
 * ordinary event) plus one per {@link #BYTES_PER_UNIT} bytes it carries (chunk data), so a burst of
 * chunks is bounded by its memory, not by its count.
 */
final class SerialMailbox {

    /** Events processed per drain before yielding the thread to other sessions. */
    private static final int BATCH = 256;
    /** Payload bytes per additional unit of weight. */
    static final int BYTES_PER_UNIT = 128;

    private final Queue<TimelineEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Executor executor;
    private final Consumer<TimelineEvent> consumer;
    private final int capacity;
    private final AtomicLong globalSize;
    private final long globalCapacity;

    SerialMailbox(Executor executor, int capacity, AtomicLong globalSize, long globalCapacity, Consumer<TimelineEvent> consumer) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2 (a gap marker travels with the next event)");
        }
        this.executor = executor;
        this.capacity = capacity;
        this.globalSize = globalSize;
        this.globalCapacity = globalCapacity;
        this.consumer = consumer;
    }

    /** The units an event occupies (see the class comment). */
    static int weight(TimelineEvent event) {
        return event instanceof TimelineEvent.Outbound out ? weight(out.packet()) : 1;
    }

    /** The units an outbound event carrying {@code packet} occupies. */
    static int weight(ClientboundPacket packet) {
        return packet instanceof ClientboundPacket.LevelChunk chunk && chunk.body() != null ? 1 + chunk.body().length / BYTES_PER_UNIT : 1;
    }

    /** True if {@code units} more units fit under both this session's and the server-wide bound. */
    boolean hasRoomFor(int units) {
        return (long) size.get() + units <= capacity && globalSize.get() + units <= globalCapacity;
    }

    void enqueue(TimelineEvent event) {
        int units = weight(event);
        queue.add(event);
        size.addAndGet(units);
        globalSize.addAndGet(units);
        if (scheduled.compareAndSet(false, true)) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException shutDown) {
                drain(); // executor is gone (shutdown): deliver on the caller rather than lose events
            }
        }
    }

    int size() {
        return size.get();
    }

    /** Is any event still waiting for delivery? */
    boolean isIdle() {
        return size.get() == 0 && !scheduled.get();
    }

    private void drain() {
        boolean continueInline;
        do {
            continueInline = false;
            try {
                for (int i = 0; i < BATCH; i++) {
                    TimelineEvent event = queue.poll();
                    if (event == null) {
                        break;
                    }
                    int units = weight(event);
                    size.addAndGet(-units);
                    globalSize.addAndGet(-units);
                    consumer.accept(event);
                }
            } finally {
                scheduled.set(false);
            }
            if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) {
                try {
                    executor.execute(this::drain);
                } catch (RejectedExecutionException shutDown) {
                    continueInline = true; // keep draining here; iterative, so no recursion depth
                }
            }
        } while (continueInline);
    }
}
