package dev.whynotac.protocol.session;

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
 */
final class SerialMailbox {

    /** Events processed per drain before yielding the thread to other sessions. */
    private static final int BATCH = 256;

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

    /** True if {@code events} more events fit under both this session's and the server-wide bound. */
    boolean hasRoomFor(int events) {
        return (long) size.get() + events <= capacity && globalSize.get() + events <= globalCapacity;
    }

    void enqueue(TimelineEvent event) {
        queue.add(event);
        size.incrementAndGet();
        globalSize.incrementAndGet();
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
                    size.decrementAndGet();
                    globalSize.decrementAndGet();
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
