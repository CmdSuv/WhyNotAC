package dev.whynotac.protocol.session;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>The bound protects the server from a client that floods packets faster than analysis runs.
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

    SerialMailbox(Executor executor, int capacity, Consumer<TimelineEvent> consumer) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be at least 2 (a gap marker travels with the next event)");
        }
        this.executor = executor;
        this.capacity = capacity;
        this.consumer = consumer;
    }

    /** True if {@code events} more events fit under the bound. */
    boolean hasRoomFor(int events) {
        return size.get() + events <= capacity;
    }

    void enqueue(TimelineEvent event) {
        queue.add(event);
        size.incrementAndGet();
        if (scheduled.compareAndSet(false, true)) {
            executor.execute(this::drain);
        }
    }

    int size() {
        return size.get();
    }

    private void drain() {
        try {
            for (int i = 0; i < BATCH; i++) {
                TimelineEvent event = queue.poll();
                if (event == null) {
                    break;
                }
                size.decrementAndGet();
                consumer.accept(event);
            }
        } finally {
            scheduled.set(false);
            if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) {
                executor.execute(this::drain);
            }
        }
    }
}
