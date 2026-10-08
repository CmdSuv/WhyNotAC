package dev.whynotac.world;

/**
 * The block states of one 16x16x16 section, stored the way the chunk packet delivers them: one value,
 * or a palette plus bit-packed indices, or global ids bit-packed. Decoding keeps that compact form
 * (most sections cost a few hundred bytes); the first block change expands it to one {@code char}
 * per block (8 KiB). Index order is the game's: {@code (y << 8 | z << 4) | x}.
 *
 * <p>Owned by one {@link ClientWorld} and confined to its analysis thread.
 */
public final class Section {

    /** A state the packet referenced but the palette or registry does not contain (the client would fail on it). */
    public static final int INVALID = -2;
    private static final char INVALID_CHAR = '￿';

    private int single;          // >= 0 or INVALID: every block has this state (when data == null and states == null)
    private int bits;
    private long[] data;
    private int valuesPerLong;
    private int[] palette;       // null: data holds global ids
    private int globalLimit;     // global ids at or above this are not block states
    private char[] states;       // expanded, after the first change

    private Section(int single, int bits, long[] data, int[] palette, int globalLimit) {
        this.single = single;
        this.bits = bits;
        this.data = data;
        this.palette = palette;
        this.globalLimit = globalLimit;
        this.valuesPerLong = bits == 0 ? 0 : 64 / bits;
    }

    static Section uniform(int state) {
        return new Section(state, 0, null, null, 0);
    }

    /**
     * Bit-packed values as {@code SimpleBitStorage} stores them (no value spans two longs): indices into
     * {@code palette} (whose entries are states or {@link #INVALID}), or global ids below {@code globalLimit}
     * when {@code palette} is null.
     */
    static Section packed(int bits, long[] data, int[] palette, int globalLimit) {
        return new Section(-1, bits, data, palette, globalLimit);
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
        if (states != null) {
            char c = states[index];
            return c == INVALID_CHAR ? INVALID : c;
        }
        if (data == null) {
            return single;
        }
        int cell = index / valuesPerLong;
        int value = (int) (data[cell] >>> (index - cell * valuesPerLong) * bits & (1L << bits) - 1);
        if (palette == null) {
            return value < globalLimit ? value : INVALID;
        }
        return value < palette.length ? palette[value] : INVALID;
    }

    void set(int index, int state) {
        if (states == null) {
            char[] expanded = new char[4096];
            for (int i = 0; i < expanded.length; i++) {
                int s = get(i);
                expanded[i] = s < 0 ? INVALID_CHAR : (char) s;
            }
            states = expanded;
            data = null;
            palette = null;
        }
        states[index] = state < 0 ? INVALID_CHAR : (char) state;
    }

    /** True if every block is {@code state} without expanding. */
    boolean isUniform(int state) {
        return states == null && data == null && single == state;
    }

    /** Approximate retained bytes (for memory accounting). */
    long retainedBytes() {
        if (states != null) {
            return 16 + 8192;
        }
        return 48 + (data == null ? 0 : 16 + 8L * data.length) + (palette == null ? 0 : 16 + 4L * palette.length);
    }

    public static int index(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | x & 15;
    }
}
