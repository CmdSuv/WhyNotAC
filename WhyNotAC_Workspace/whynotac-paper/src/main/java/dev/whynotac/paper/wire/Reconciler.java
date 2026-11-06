package dev.whynotac.paper.wire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Matches one ordered view of a packet stream against another (the client's view at the network edge
 * and the game's view next to its packet handler) and reports what only one side has.
 *
 * <p>The <em>reference</em> side announces packets with {@link #expect}; the other side reports what it
 * actually saw with {@link #match}. A match consumes the oldest pending entry with the same type and
 * content, not only the oldest entry: plugins may hold packets back for a while (ViaVersion and
 * PacketEvents both buffer around the start of the play state) and release them later, which is a
 * delay, not a divergence. Pending entries are indexed by (type, content), so a match costs the same
 * whatever is pending in front of it: entries a plugin cancelled, or a flood a client sends on
 * purpose, can never hide the right one. A packet that could not be translated has no content key
 * and matches by type alone (the oldest pending entry of its type).
 *
 * <p>A pending entry is declared missing only when both hold:
 * <ul>
 *   <li>the stream has moved past it: an entry announced after it has been matched, and
 *   <li>it has been pending for at least the grace period.
 * </ul>
 * A match that consumes nothing means the packet exists only on the matching side.
 *
 * <p>Bounded: at most {@link #CAPACITY} pending entries (the oldest is declared missing beyond that);
 * a type-only match looks at most at {@link #TYPE_ONLY_SCAN} entries. The hot path allocates one entry
 * per announced packet and nothing else. Event-loop confined.
 */
final class Reconciler<P> {

    static final int CAPACITY = 4_096;
    /** Type-only matches (untranslatable packets, rare) scan this many pending entries at most. */
    static final int TYPE_ONLY_SCAN = 256;

    /** The content key of a packet: equal keys mean the same packet; null means "could not be translated". */
    @FunctionalInterface
    interface Key<P> {
        Object of(String type, P packet);
    }

    @FunctionalInterface
    interface Missing<P> {
        void report(P packet, long nanoTime);
    }

    /** A pending packet that was still in flight when a view was lost (see {@link #drainInFlight}). */
    record InFlight<P>(String type, P packet, Object key) {}

    private static final class Entry<P> {
        final long seq;
        final long nanoTime;
        final String type;
        final P packet;
        final Object key; // null: matches by type alone
        final int hash;
        Entry<P> nextInBucket;
        boolean done;

        Entry(long seq, long nanoTime, String type, P packet, Object key, int hash) {
            this.seq = seq;
            this.nanoTime = nanoTime;
            this.type = type;
            this.packet = packet;
            this.key = key;
            this.hash = hash;
        }
    }

    private final Key<P> key;
    private final Missing<P> missing;
    private final long graceNanos;
    /** Every entry in announcement order; done ones are dropped lazily (at the head, or by compaction). */
    private final ArrayDeque<Entry<P>> order = new ArrayDeque<>();
    /** Pending entries with a key, chained per bucket in announcement order (intrusive: no allocation). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Entry<P>[] buckets = new Entry[16];
    private long nextSeq;
    private long highestMatched = -1;
    private int pending;
    private int keyed;

    Reconciler(Key<P> key, Missing<P> missing, long graceNanos) {
        this.key = key;
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
        Object k = key.of(type, packet);
        Entry<P> entry = new Entry<>(nextSeq++, nanoTime, type, packet, k, k == null ? 0 : hash(type, k));
        if (k != null) {
            link(entry);
        }
        order.addLast(entry);
        pending++;
        compactIfNeeded();
        expire(nanoTime);
    }

    /** The other side has this packet. Returns false if the reference side never announced it. */
    boolean match(String type, P packet, long nanoTime) {
        Entry<P> found = null;
        if (pending > 0) {
            Object k = key.of(type, packet);
            found = k == null ? oldestOfType(type) : older(oldestWithKey(type, k), oldestTypeOnly(type));
            if (found != null) {
                consume(found);
            }
        }
        expire(nanoTime);
        return found != null;
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

    /**
     * The matching side's view is gone (another plugin removed it). Entries the stream has already moved
     * past were cancelled (or reordered) between the two views: they are reported missing now. The
     * entries still in flight are returned, oldest first, and the reconciler is left empty.
     */
    List<InFlight<P>> drainInFlight(long nanoTime) {
        List<InFlight<P>> inFlight = new ArrayList<>();
        for (Entry<P> entry : order) {
            if (entry.done) {
                continue;
            }
            if (entry.seq < highestMatched) {
                missing.report(entry.packet, nanoTime);
            } else {
                inFlight.add(new InFlight<>(entry.type, entry.packet, entry.key));
            }
        }
        order.clear();
        Arrays.fill(buckets, null);
        pending = 0;
        keyed = 0;
        return inFlight;
    }

    int pending() {
        return pending;
    }

    /** Entries held, pending or not yet compacted away (for tests: memory stays bounded). */
    int retained() {
        return order.size();
    }

    // --- index ------------------------------------------------------------------------------------

    private static int hash(String type, Object key) {
        int h = type.hashCode() * 31 + key.hashCode();
        return h ^ (h >>> 16);
    }

    private void link(Entry<P> entry) {
        if (keyed >= buckets.length - (buckets.length >> 2)) {
            resize();
        }
        int i = entry.hash & (buckets.length - 1);
        Entry<P> tail = buckets[i];
        if (tail == null) {
            buckets[i] = entry;
        } else {
            while (tail.nextInBucket != null) {
                tail = tail.nextInBucket;
            }
            tail.nextInBucket = entry; // appended: within a bucket, equal keys stay oldest first
        }
        keyed++;
    }

    private void unlink(Entry<P> entry) {
        int i = entry.hash & (buckets.length - 1);
        Entry<P> previous = null;
        for (Entry<P> e = buckets[i]; e != null; previous = e, e = e.nextInBucket) {
            if (e == entry) {
                if (previous == null) {
                    buckets[i] = e.nextInBucket;
                } else {
                    previous.nextInBucket = e.nextInBucket;
                }
                e.nextInBucket = null;
                keyed--;
                return;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resize() {
        Entry<P>[] old = buckets;
        buckets = new Entry[old.length * 2];
        keyed = 0;
        // Relink in announcement order so equal keys stay oldest first.
        List<Entry<P>> all = new ArrayList<>();
        for (Entry<P> head : old) {
            for (Entry<P> e = head; e != null; ) {
                Entry<P> next = e.nextInBucket;
                e.nextInBucket = null;
                all.add(e);
                e = next;
            }
        }
        all.sort((a, b) -> Long.compare(a.seq, b.seq));
        for (Entry<P> e : all) {
            link(e);
        }
    }

    private Entry<P> oldestWithKey(String type, Object k) {
        int h = hash(type, k);
        for (Entry<P> e = buckets[h & (buckets.length - 1)]; e != null; e = e.nextInBucket) {
            if (e.hash == h && e.type.equals(type) && Objects.equals(e.key, k)) {
                return e;
            }
        }
        return null;
    }

    /** The oldest pending entry of this type that has no key (the reference side could not translate it). */
    private Entry<P> oldestTypeOnly(String type) {
        if (keyed == pending) {
            return null; // the common case: every pending entry has a key
        }
        int scanned = 0;
        for (Iterator<Entry<P>> it = order.iterator(); it.hasNext() && scanned < TYPE_ONLY_SCAN; ) {
            Entry<P> e = it.next();
            if (!e.done) {
                scanned++;
                if (e.key == null && e.type.equals(type)) {
                    return e;
                }
            }
        }
        return null;
    }

    /** The oldest pending entry of this type, with or without a key. */
    private Entry<P> oldestOfType(String type) {
        int scanned = 0;
        for (Iterator<Entry<P>> it = order.iterator(); it.hasNext() && scanned < TYPE_ONLY_SCAN; ) {
            Entry<P> e = it.next();
            if (!e.done) {
                scanned++;
                if (e.type.equals(type)) {
                    return e;
                }
            }
        }
        return null;
    }

    private static <P> Entry<P> older(Entry<P> a, Entry<P> b) {
        if (a == null) {
            return b;
        }
        return b == null || a.seq < b.seq ? a : b;
    }

    private void consume(Entry<P> entry) {
        drop(entry);
        highestMatched = Math.max(highestMatched, entry.seq);
    }

    private void drop(Entry<P> entry) {
        entry.done = true;
        pending--;
        if (entry.key != null) {
            unlink(entry);
        }
    }

    private Entry<P> oldestPending() {
        while (!order.isEmpty() && order.peekFirst().done) {
            order.removeFirst();
        }
        return order.peekFirst();
    }

    /** Done entries behind a pending head are only dropped lazily; keep them bounded. */
    private void compactIfNeeded() {
        if (order.size() > 2 * pending + 1_024) {
            order.removeIf(e -> e.done);
        }
    }
}
