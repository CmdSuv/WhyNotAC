package dev.whynotac.world;

import java.util.Arrays;

/**
 * The block states of one 16x16x16 section, stored the way the chunk packet delivers them: one value,
 * or a palette plus bit-packed indices, or bit-packed global ids (most sections cost a few hundred
 * bytes). Index order is the game's: {@code (y << 8 | z << 4) | x}.
 *
 * <p>Immutable and compared by content: a block change makes a new section ({@link #with}), so the
 * identical sections many players hold, before and after the same change, are stored once
 * ({@link SectionPool}). Safe to share between threads (final fields).
 */
public final class Section {

    /** A state the packet referenced but the palette or registry does not contain (the client would fail on it). */
    public static final int INVALID = -2;

    private final int single;      // the state of every block when bits == 0
    private final int bits;
    private final long[] data;
    private final int valuesPerLong;
    private final int[] palette;   // null: data holds global ids
    private final int globalLimit; // global ids at or above this are not block states
    private final int hash;
    /**
     * Packed by {@link #of} with a palette: the palette has no duplicates, every stored value indexes it, the width is
     * {@code of}'s for its size, the unused bits are zero, so {@code of} would pack it again as it is (not part of the
     * content: a decoded section equal to it is not marked).
     */
    private final boolean canonical;

    private Section(int single, int bits, long[] data, int[] palette, int globalLimit, boolean canonical) {
        this.single = single;
        this.bits = bits;
        this.data = data;
        this.palette = palette;
        this.globalLimit = globalLimit;
        this.canonical = canonical;
        this.valuesPerLong = bits == 0 ? 0 : 64 / bits;
        this.hash = ((31 * single + bits) * 31 + Arrays.hashCode(data)) * 31 + Arrays.hashCode(palette);
    }

    static Section uniform(int state) {
        return new Section(state, 0, null, null, 0, false);
    }

    /**
     * Bit-packed values as {@code SimpleBitStorage} stores them (no value spans two longs): indices into
     * {@code palette} (whose entries are states or {@link #INVALID}), or global ids below {@code globalLimit}
     * when {@code palette} is null.
     */
    static Section packed(int bits, long[] data, int[] palette, int globalLimit) {
        return new Section(-1, bits, data, palette, globalLimit, false);
    }

    /** The number of longs {@code SimpleBitStorage} uses for {@code size} values of {@code bits} bits. */
    static int longsFor(int bits, int size) {
        int perLong = 64 / bits;
        return (size + perLong - 1) / perLong;
    }

    /** The state at section-local coordinates (0..15), or {@link #INVALID}. */
    public int get(int x, int y, int z) {
        return get(index(x, y, z));
    }

    /** The state at {@link #index}, or {@link #INVALID}. */
    public int get(int index) {
        if (bits == 0) {
            return single;
        }
        int cell = index / valuesPerLong;
        int value = (int) (data[cell] >>> (index - cell * valuesPerLong) * bits & (1L << bits) - 1);
        if (palette == null) {
            return value < globalLimit ? value : INVALID;
        }
        return value < palette.length ? palette[value] : INVALID;
    }

    /**
     * This section with the blocks at {@code indices} set to {@code states} (in order; a later entry for
     * the same index wins). Deterministic: the same changes to the same section give an equal section.
     * The palette grows as needed, the storage widens like the game's (4 bits up to 16 values, then 5..8,
     * then global ids), and a section whose values no longer fit a palette keeps global ids.
     */
    Section with(int[] indices, int[] states, int globalBits, int stateCount) {
        Section inPlace = canonical && globalLimit == stateCount ? withinPalette(indices, states, stateCount) : null;
        if (inPlace != null) {
            return inPlace;
        }
        int[] values = new int[4096];
        for (int i = 0; i < 4096; i++) {
            values[i] = get(i);
        }
        for (int i = 0; i < indices.length; i++) {
            values[indices[i]] = states[i] >= 0 && states[i] < stateCount ? states[i] : INVALID;
        }
        return of(values, palette, globalBits, stateCount);
    }

    /**
     * {@link #with} of a canonical section whose palette holds every new state, without unpacking it: {@code of} would
     * keep the palette as it is (nothing to append), so the width and every other stored value stay, only the changed
     * values are rewritten. Null where the palette lacks a state or the section turns uniform ({@code of}'s cases).
     */
    private Section withinPalette(int[] indices, int[] states, int stateCount) {
        if ((long) indices.length * palette.length > 4096) {
            return null; // looking them up costs more than unpacking
        }
        int[] positions = new int[indices.length];
        for (int i = 0; i < indices.length; i++) {
            int state = states[i] >= 0 && states[i] < stateCount ? states[i] : INVALID;
            int position = 0;
            while (position < palette.length && palette[position] != state) {
                position++;
            }
            if (position == palette.length) {
                return null;
            }
            positions[i] = position;
        }
        long[] changed = data.clone();
        long mask = (1L << bits) - 1;
        for (int i = 0; i < indices.length; i++) {
            int cell = indices[i] / valuesPerLong;
            int shift = (indices[i] - cell * valuesPerLong) * bits;
            changed[cell] = changed[cell] & ~(mask << shift) | (long) positions[i] << shift;
        }
        if (uniform(changed)) {
            return null;
        }
        return new Section(-1, bits, changed, palette, globalLimit, true);
    }

    /** Whether every one of the 4096 values in {@code packed} (this section's width) is the same. */
    private boolean uniform(long[] packed) {
        long first = packed[0] & (1L << bits) - 1;
        long full = 0;
        for (int slot = 0; slot < valuesPerLong; slot++) {
            full |= first << slot * bits;
        }
        int last = packed.length - 1;
        for (int cell = 0; cell < last; cell++) {
            if (packed[cell] != full) {
                return false;
            }
        }
        int rest = 4096 - last * valuesPerLong;
        return packed[last] == (rest == valuesPerLong ? full : full & (1L << rest * bits) - 1);
    }

    /** One block changed (see {@link #with(int[], int[], int, int)}). */
    Section with(int index, int state, int globalBits, int stateCount) {
        if (bits == 0 && state == single) {
            return this;
        }
        return with(new int[] {index}, new int[] {state}, globalBits, stateCount);
    }

    /**
     * Packs 4096 states. The palette keeps the order of {@code previousPalette} (so a changed section stays
     * close to what the packet delivered) and appends new states in index order.
     */
    private static Section of(int[] values, int[] previousPalette, int globalBits, int stateCount) {
        int first = values[0];
        boolean uniform = true;
        for (int v : values) {
            if (v != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            return uniform(first);
        }
        int[] palette = new int[16];
        int size = 0;
        PaletteIndex index = new PaletteIndex();
        if (previousPalette != null) {
            for (int i = 0; i < previousPalette.length && size <= PaletteIndex.MAX; i++) {
                if (index.add(previousPalette[i], size)) {
                    if (size == palette.length) {
                        palette = Arrays.copyOf(palette, size * 2);
                    }
                    palette[size++] = previousPalette[i];
                }
            }
        }
        for (int i = 0; i < 4096 && size <= PaletteIndex.MAX; i++) {
            if (index.add(values[i], size)) {
                if (size == palette.length) {
                    palette = Arrays.copyOf(palette, size * 2);
                }
                palette[size++] = values[i];
            }
        }
        // More than PaletteIndex.MAX states need more than 8 bits: global ids, whatever the rest holds.
        int needed = 32 - Integer.numberOfLeadingZeros(Math.max(size - 1, 1));
        boolean global = needed > 8;
        int storedBits = global ? globalBits : Math.max(4, needed);
        long[] data = new long[longsFor(storedBits, 4096)];
        int perLong = 64 / storedBits;
        for (int i = 0; i < 4096; i++) {
            long value = global ? (values[i] < 0 ? stateCount : values[i]) : index.position(values[i]);
            data[i / perLong] |= value << (i % perLong) * storedBits;
        }
        return new Section(-1, storedBits, data, global ? null : Arrays.copyOf(palette, size), stateCount, !global);
    }

    /**
     * State to palette position for {@link #of}, without boxing: open addressing over at most {@link #MAX} + 1
     * entries (one more than 8 bits can index means global ids, so {@code of} stops adding there).
     */
    private static final class PaletteIndex {
        static final int MAX = 256;
        private static final int MASK = 1023;
        private final int[] states = new int[MASK + 1];
        private final int[] positions = new int[MASK + 1]; // position + 1; 0: empty

        /** Adds {@code state} at {@code position} unless present; true if it was added. */
        boolean add(int state, int position) {
            int i = slot(state);
            while (positions[i] != 0) {
                if (states[i] == state) {
                    return false;
                }
                i = (i + 1) & MASK;
            }
            states[i] = state;
            positions[i] = position + 1;
            return true;
        }

        /** The position of a state that was added. */
        int position(int state) {
            int i = slot(state);
            while (states[i] != state || positions[i] == 0) {
                i = (i + 1) & MASK;
            }
            return positions[i] - 1;
        }

        private static int slot(int state) {
            return (state * 0x9E3779B9) >>> 22;
        }
    }

    /** Whether {@link #with} can rewrite this section in place (for tests). */
    boolean canonical() {
        return canonical;
    }

    /** An equal section as a chunk packet delivers it, not marked canonical (for tests). */
    Section asDelivered() {
        return bits == 0 ? this : packed(bits, data, palette, globalLimit);
    }

    /** Approximate retained bytes. */
    long retainedBytes() {
        return 56 + (data == null ? 0 : 16 + 8L * data.length) + (palette == null ? 0 : 16 + 4L * palette.length);
    }

    public static int index(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | x & 15;
    }

    /** Equal content (and representation). */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Section s && s.hash == hash && s.single == single && s.bits == bits
                && s.globalLimit == globalLimit && Arrays.equals(s.data, data) && Arrays.equals(s.palette, palette);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
