package dev.whynotac.protocol.latency;

/**
 * Transaction id to {@code long}: open addressing with linear probing over plain arrays, so the hot
 * path of every ping (allocate, sent, pong) touches a few cache lines and allocates nothing.
 *
 * <p>Ids are never in {@link TransactionTracker#isReservedForOthers the reserved short range}, so
 * {@code 0} marks an empty slot; a reserved id (a foreign pong may carry any) is simply never present.
 * The table grows by doubling and keeps at most half its slots in use; removal shifts the probe chain
 * back, so lookups never cross deleted slots. Not thread-safe.
 */
final class IdTable {

    private static final int EMPTY = 0;

    private int[] keys = new int[16];
    private long[] values = new long[16];
    private int size;

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    private static int home(int key, int mask) {
        int h = key * 0x9E37_79B9;
        return (h ^ (h >>> 16)) & mask;
    }

    private int indexOf(int key) {
        if (TransactionTracker.isReservedForOthers(key)) {
            return -1;
        }
        int mask = keys.length - 1;
        for (int i = home(key, mask); ; i = (i + 1) & mask) {
            int k = keys[i];
            if (k == key) {
                return i;
            }
            if (k == EMPTY) {
                return -1;
            }
        }
    }

    boolean contains(int key) {
        return indexOf(key) >= 0;
    }

    /** The value of {@code key}, or {@code absent}. */
    long get(int key, long absent) {
        int i = indexOf(key);
        return i < 0 ? absent : values[i];
    }

    /** Sets {@code key} (never a reserved id) to {@code value}. */
    void put(int key, long value) {
        if (TransactionTracker.isReservedForOthers(key)) {
            throw new IllegalArgumentException("reserved transaction id " + key);
        }
        int i = indexOf(key);
        if (i >= 0) {
            values[i] = value;
            return;
        }
        if (2 * (size + 1) > keys.length) {
            grow();
        }
        insert(key, value);
        size++;
    }

    private void insert(int key, long value) {
        int mask = keys.length - 1;
        int i = home(key, mask);
        while (keys[i] != EMPTY) {
            i = (i + 1) & mask;
        }
        keys[i] = key;
        values[i] = value;
    }

    private void grow() {
        int[] oldKeys = keys;
        long[] oldValues = values;
        keys = new int[oldKeys.length * 2];
        values = new long[oldKeys.length * 2];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                insert(oldKeys[i], oldValues[i]);
            }
        }
    }

    /** Removes {@code key}; false if it was not present. */
    boolean remove(int key) {
        int gap = indexOf(key);
        if (gap < 0) {
            return false;
        }
        int mask = keys.length - 1;
        for (int j = (gap + 1) & mask; keys[j] != EMPTY; j = (j + 1) & mask) {
            // an entry may fill the gap unless its home lies cyclically in (gap, j]
            if (((j - home(keys[j], mask)) & mask) >= ((j - gap) & mask)) {
                keys[gap] = keys[j];
                values[gap] = values[j];
                gap = j;
            }
        }
        keys[gap] = EMPTY;
        values[gap] = 0;
        size--;
        return true;
    }

    /** Removes every entry with {@code now - value >= age} and returns how many there were. */
    int removeOlderThan(long now, long age) {
        int removed = 0;
        int[] expired = null;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY && now - values[i] >= age) {
                if (expired == null) {
                    expired = new int[size];
                }
                expired[removed++] = keys[i];
            }
        }
        for (int n = 0; n < removed; n++) {
            remove(expired[n]);
        }
        return removed;
    }
}
