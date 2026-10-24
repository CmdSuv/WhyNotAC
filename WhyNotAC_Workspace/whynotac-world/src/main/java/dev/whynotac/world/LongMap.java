package dev.whynotac.world;

import java.util.Objects;

/**
 * An open-addressing map from {@code long} keys to non-null values, for lookups on per-tick paths: no
 * boxing, no entry objects. Linear probing with backward-shift deletion; any key is allowed (an empty slot
 * is a null value). Not thread-safe.
 */
final class LongMap<V> {

    private long[] keys;
    private Object[] values;
    private int mask;
    private int size;

    LongMap() {
        allocate(16);
    }

    int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        for (int i = slot(key); ; i = (i + 1) & mask) {
            Object value = values[i];
            if (value == null) {
                return null;
            }
            if (keys[i] == key) {
                return (V) value;
            }
        }
    }

    void put(long key, V value) {
        Objects.requireNonNull(value, "value");
        int i = slot(key);
        for (; values[i] != null; i = (i + 1) & mask) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
        }
        keys[i] = key;
        values[i] = value;
        if (++size * 2 > values.length) {
            rehash(values.length * 2);
        }
    }

    @SuppressWarnings("unchecked")
    V remove(long key) {
        for (int i = slot(key); values[i] != null; i = (i + 1) & mask) {
            if (keys[i] == key) {
                V old = (V) values[i];
                closeGap(i);
                size--;
                return old;
            }
        }
        return null;
    }

    void clear() {
        java.util.Arrays.fill(values, null);
        size = 0;
    }

    /** Calls {@code action} for every entry; {@code action} may change values but must not add or remove keys. */
    @SuppressWarnings("unchecked")
    void forEach(Entry<V> action) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                action.accept(keys[i], (V) values[i]);
            }
        }
    }

    interface Entry<V> {
        void accept(long key, V value);
    }

    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ h >>> 32) & mask;
    }

    /** Backward-shift deletion: later entries of the cluster move into the gap when their probe path crosses it. */
    private void closeGap(int gap) {
        for (int i = (gap + 1) & mask; values[i] != null; i = (i + 1) & mask) {
            int home = slot(keys[i]);
            if (((i - home) & mask) >= ((i - gap) & mask)) {
                keys[gap] = keys[i];
                values[gap] = values[i];
                gap = i;
            }
        }
        values[gap] = null;
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new Object[capacity];
        mask = capacity - 1;
    }

    private void rehash(int capacity) {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        allocate(capacity);
        for (int j = 0; j < oldValues.length; j++) {
            if (oldValues[j] != null) {
                int i = slot(oldKeys[j]);
                while (values[i] != null) {
                    i = (i + 1) & mask;
                }
                keys[i] = oldKeys[j];
                values[i] = oldValues[j];
            }
        }
    }
}
