package dev.whynotac.world;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Stores identical compact sections once, for every world (player) that holds them. Players near each
 * other receive the same chunks byte for byte, so on a busy server most sections are shared: the
 * replica's memory then grows with the area the players cover, not with their number.
 *
 * <p>Reference-counted: a world {@link #intern}s a section when it stores it and {@link #release}s it
 * when it drops it; the pool forgets a section when no world holds it. Thread-safe (worlds live on
 * different analysis threads).
 */
public final class SectionPool {

    private static final class Entry {
        final Section section;
        int references;

        Entry(Section section) {
            this.section = section;
        }
    }

    private final ConcurrentHashMap<Section, Entry> entries = new ConcurrentHashMap<>();
    private final LongAdder uniqueBytes = new LongAdder();

    /** The pooled section equal to {@code section} (possibly {@code section} itself), with one more reference. */
    Section intern(Section section) {
        Entry entry = entries.compute(section, (key, existing) -> {
            Entry e = existing != null ? existing : new Entry(key);
            if (existing == null) {
                uniqueBytes.add(key.retainedBytes());
            }
            e.references++;
            return e;
        });
        return entry.section;
    }

    /** Drops one reference to a section {@link #intern} returned. */
    void release(Section section) {
        entries.computeIfPresent(section, (key, e) -> {
            if (--e.references > 0) {
                return e;
            }
            uniqueBytes.add(-e.section.retainedBytes());
            return null;
        });
    }

    /** Bytes of the distinct sections held. */
    public long uniqueBytes() {
        return uniqueBytes.sum();
    }

    /** Distinct sections held. */
    public int size() {
        return entries.size();
    }
}
