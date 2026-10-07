package dev.whynotac.paper.wire;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Matches one ordered view of a packet stream against another (the client's view at the network edge
 * and the game's view next to its packet handler) and reports what only one side has.
 *
 * <p>The <em>reference</em> side announces packets with {@link #expect}; the other side reports what it
 * actually saw with {@link #match}. A match may consume any pending entry of the same packet type, not
 * only the oldest: plugins may hold packets back for a while (ViaVersion and PacketEvents both buffer
 * around the start of the play state) and release them later, which is a delay, not a divergence.
 * Matching looks only at entries of the same type, so stale entries of other types (cancelled by a
 * plugin, or a flood a client sends on purpose) can never hide the right one. A pending entry is
 * declared missing only when both hold:
 * <ul>
 *   <li>the stream has moved past it: an entry announced after it has been matched, and
 *   <li>it has been pending for at least the grace period.
 * </ul>
 * A match that consumes nothing means the packet exists only on the matching side.
 *
 * <p>Bounded: at most {@link #CAPACITY} pending entries (the oldest is declared missing beyond that),
 * and a match compares at most {@link #SCAN} entries of its own type. Event-loop confined.
 */
final class Reconciler<P> {

    static final int CAPACITY = 4_096;
    static final int SCAN = 256;

    @FunctionalInterface
    interface Same<P> {
        boolean test(String typeA, P a, String typeB, P b);
    }

    @FunctionalInterface
    interface Missing<P> {
        void report(P packet, long nanoTime);
    }

    private static final class Entry<P> {
        final long seq;
        final long nanoTime;
        final String type;
        final P packet;
        boolean done;

        Entry(long seq, long nanoTime, String type, P packet) {
            this.seq = seq;
            this.nanoTime = nanoTime;
            this.type = type;
            this.packet = packet;
        }
    }

    /** Pending entries per type, oldest first. */
    private final Map<String, ArrayDeque<Entry<P>>> byType = new HashMap<>();
    /** Every entry in announcement order; matched ones are dropped lazily when they reach the head. */
    private final ArrayDeque<Entry<P>> order = new ArrayDeque<>();
    private final Same<P> same;
    private final Missing<P> missing;
    private final long graceNanos;
    private long nextSeq;
    private long highestMatched = -1;
    private int pending;

    Reconciler(Same<P> same, Missing<P> missing, long graceNanos) {
        this.same = same;
        this.missing = missing;
        this.graceNanos = graceNanos;
    }

    /** The reference side has this packet. */
    void expect(String type, P packet, long nanoTime) {
        if (pending >= CAPACITY) {
            Entry<P> oldest = oldestPending();
            if (oldest != null) {
                drop(oldest);
                missing.report(oldest.packet, nanoTime);
            }
        }
        Entry<P> entry = new Entry<>(nextSeq++, nanoTime, type, packet);
        byType.computeIfAbsent(type, t -> new ArrayDeque<>()).addLast(entry);
        order.addLast(entry);
        pending++;
        expire(nanoTime);
    }

    /** The other side has this packet. Returns false if the reference side never announced it. */
    boolean match(String type, P packet, long nanoTime) {
        ArrayDeque<Entry<P>> candidates = byType.get(type);
        boolean found = false;
        if (candidates != null) {
            int scanned = 0;
            for (Iterator<Entry<P>> it = candidates.iterator(); it.hasNext() && scanned < SCAN; scanned++) {
                Entry<P> entry = it.next();
                if (same.test(entry.type, entry.packet, type, packet)) {
                    it.remove();
                    entry.done = true;
                    pending--;
                    highestMatched = Math.max(highestMatched, entry.seq);
                    found = true;
                    break;
                }
            }
            if (candidates.isEmpty()) {
                byType.remove(type);
            }
        }
        expire(nanoTime);
        return found;
    }

    /** Reports entries the stream has moved past for longer than the grace period. */
    void expire(long nanoTime) {
        while (!order.isEmpty()) {
            Entry<P> head = order.peekFirst();
            if (head.done) {
                order.removeFirst();
                continue;
            }
            if (head.seq >= highestMatched || nanoTime - head.nanoTime < graceNanos) {
                return;
            }
            order.removeFirst();
            drop(head);
            missing.report(head.packet, nanoTime);
        }
    }

    private Entry<P> oldestPending() {
        while (!order.isEmpty() && order.peekFirst().done) {
            order.removeFirst();
        }
        return order.peekFirst();
    }

    /** Removes a pending entry from its type queue (it stays in {@link #order} until it reaches the head). */
    private void drop(Entry<P> entry) {
        entry.done = true;
        pending--;
        ArrayDeque<Entry<P>> queue = byType.get(entry.type);
        if (queue != null) {
            queue.remove(entry);
            if (queue.isEmpty()) {
                byType.remove(entry.type);
            }
        }
    }

    int pending() {
        return pending;
    }
}
