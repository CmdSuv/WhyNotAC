package dev.whynotac.paper.wire;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Matches one ordered view of a packet stream against another (the client's view at the network edge
 * and the game's view next to its packet handler) and reports what only one side has.
 *
 * <p>The <em>reference</em> side announces packets with {@link #expect}; the other side reports what it
 * actually saw with {@link #match}. A match may consume any pending entry, not only the oldest: plugins
 * may hold packets back for a while (ViaVersion and PacketEvents both buffer around the start of the
 * play state) and release them later, which is a delay, not a divergence. A pending entry is declared
 * missing only when both hold:
 * <ul>
 *   <li>the stream has moved past it: an entry announced after it has been matched, and
 *   <li>it has been pending for at least the grace period.
 * </ul>
 * A match that consumes nothing means the packet exists only on the matching side.
 *
 * <p>Bounded: at most {@link #CAPACITY} pending entries (the oldest is declared missing beyond that),
 * and a match scans at most {@link #SCAN} of them, so a plugin that cancels a flood of packets costs a
 * bounded amount of work per packet. Event-loop confined.
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

    private record Entry<P>(long seq, long nanoTime, String type, P packet) {}

    private final ArrayDeque<Entry<P>> pending = new ArrayDeque<>();
    private final Same<P> same;
    private final Missing<P> missing;
    private final long graceNanos;
    private long nextSeq;
    private long highestMatched = -1;

    Reconciler(Same<P> same, Missing<P> missing, long graceNanos) {
        this.same = same;
        this.missing = missing;
        this.graceNanos = graceNanos;
    }

    /** The reference side has this packet. */
    void expect(String type, P packet, long nanoTime) {
        if (pending.size() >= CAPACITY) {
            missing.report(pending.removeFirst().packet(), nanoTime);
        }
        pending.addLast(new Entry<>(nextSeq++, nanoTime, type, packet));
        expire(nanoTime);
    }

    /** The other side has this packet. Returns false if the reference side never announced it. */
    boolean match(String type, P packet, long nanoTime) {
        int scanned = 0;
        for (Iterator<Entry<P>> it = pending.iterator(); it.hasNext() && scanned < SCAN; scanned++) {
            Entry<P> entry = it.next();
            if (same.test(entry.type(), entry.packet(), type, packet)) {
                it.remove();
                highestMatched = Math.max(highestMatched, entry.seq());
                expire(nanoTime);
                return true;
            }
        }
        expire(nanoTime);
        return false;
    }

    /** Reports entries the stream has moved past for longer than the grace period. */
    void expire(long nanoTime) {
        while (!pending.isEmpty()) {
            Entry<P> head = pending.peekFirst();
            if (head.seq() >= highestMatched || nanoTime - head.nanoTime() < graceNanos) {
                return;
            }
            pending.removeFirst();
            missing.report(head.packet(), nanoTime);
        }
    }

    int pending() {
        return pending.size();
    }
}
