package dev.whynotac.world;

/**
 * Decodes the body of a {@code level_chunk_with_light} packet (everything after the two coordinates)
 * into block sections, reading exactly what the 1.21.11 client reads:
 *
 * <ol>
 *   <li>heightmaps: VarInt count, then per entry VarInt type and a VarInt-prefixed long array (skipped);</li>
 *   <li>the section data: a VarInt length (at most 2 MiB, as the client checks) and that many bytes,
 *       holding {@code sections} sections back to back (the client reads as many as its dimension has
 *       and ignores the rest);</li>
 *   <li>block entities and light follow; they do not change block states and are not decoded here.</li>
 * </ol>
 *
 * A section is a short (non-empty block count), the block states container and the biomes container.
 * A container is a signed byte of bits per entry, a palette and a fixed-size long array whose length
 * the client derives from the bits it <em>stores</em>, which is not always the byte on the wire:
 * <ul>
 *   <li>blocks: 0 = one value; 1..4 = a linear palette stored at 4 bits; 5..8 = a hash-map palette at
 *       that width; anything else (also negative) = global ids at {@code ceillog2(block states)} bits;</li>
 *   <li>biomes: 0 = one value; 1..3 = a linear palette at that width; anything else = global ids. The
 *       client stores those at {@code ceillog2(its biome registry size)} bits, which WhyNotAC does not
 *       know; the byte on the wire is used instead, which is the same for every vanilla server (it writes
 *       its own storage width, and both sides share the registry).</li>
 * </ul>
 */
public final class ChunkDecoder {

    /** The client's limit for the section data ({@code ClientboundLevelChunkPacketData}). */
    static final int MAX_SECTION_BYTES = 2_097_152;
    private static final int BLOCKS = 4096;
    private static final int BIOMES = 64;

    private ChunkDecoder() {}

    /** The chunk could not be read the way the client reads it. */
    public static final class FormatException extends Exception {
        FormatException(String message) {
            super(message);
        }
    }

    /**
     * @param sections how many sections the client's dimension has ({@code height / 16})
     * @return the sections, bottom to top
     */
    public static Section[] decode(byte[] body, int sections, BlockStates states) throws FormatException {
        Reader in = new Reader(body, 0, body.length);
        int heightmaps = in.varInt();
        if (heightmaps < 0 || heightmaps > 64) {
            throw new FormatException("heightmap count " + heightmaps);
        }
        for (int i = 0; i < heightmaps; i++) {
            in.varInt(); // type
            int longs = in.varInt();
            if (longs < 0 || longs > in.remaining() / 8) {
                throw new FormatException("heightmap length " + longs);
            }
            in.skip(longs * 8);
        }
        int length = in.varInt();
        if (length < 0 || length > MAX_SECTION_BYTES) {
            throw new FormatException("section data length " + length); // the client throws here too
        }
        if (length > in.remaining()) {
            throw new FormatException("section data length " + length + " exceeds the packet (" + in.remaining() + " bytes left)");
        }
        Reader data = new Reader(body, in.position(), length);
        Section[] out = new Section[sections];
        for (int i = 0; i < sections; i++) {
            data.readShort(); // non-empty block count: the client trusts it for rendering only
            out[i] = blocks(data, states.globalPaletteBits(), states.size());
            biomes(data);
        }
        return out;
    }

    private static Section blocks(Reader in, int globalBits, int stateCount) throws FormatException {
        int wireBits = in.readByte();
        int stored;
        boolean global = false;
        if (wireBits == 0) {
            return Section.uniform(checked(in.varInt(), stateCount));
        } else if (wireBits >= 1 && wireBits <= 4) {
            stored = 4;
        } else if (wireBits >= 5 && wireBits <= 8) {
            stored = wireBits;
        } else {
            stored = globalBits;
            global = true;
        }
        int[] palette = global ? null : palette(in);
        if (palette != null) {
            for (int i = 0; i < palette.length; i++) {
                palette[i] = checked(palette[i], stateCount);
            }
        }
        long[] data = in.longs(Section.longsFor(stored, BLOCKS));
        return Section.packed(stored, data, palette, stateCount);
    }

    /** A palette entry that is no block state: the client fails when it reads it. */
    private static int checked(int state, int stateCount) {
        return state >= 0 && state < stateCount ? state : Section.INVALID;
    }

    private static void biomes(Reader in) throws FormatException {
        int wireBits = in.readByte();
        if (wireBits == 0) {
            in.varInt();
            return;
        }
        if (wireBits >= 1 && wireBits <= 3) {
            palette(in);
        } else if (wireBits < 0 || wireBits > 32) {
            throw new FormatException("biome bits " + wireBits);
        }
        in.skip(8 * Section.longsFor(wireBits, BIOMES));
    }

    private static int[] palette(Reader in) throws FormatException {
        int size = in.varInt();
        if (size < 0 || size > 4096) {
            throw new FormatException("palette size " + size);
        }
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = in.varInt();
        }
        return palette;
    }

    /** Big-endian reads over a byte range, as Netty's {@code ByteBuf}. */
    static final class Reader {
        private final byte[] bytes;
        private int position;
        private final int limit;

        Reader(byte[] bytes, int offset, int length) {
            this.bytes = bytes;
            this.position = offset;
            this.limit = offset + length;
        }

        int position() {
            return position;
        }

        int remaining() {
            return limit - position;
        }

        void skip(int count) throws FormatException {
            need(count);
            position += count;
        }

        int readByte() throws FormatException {
            need(1);
            return bytes[position++];
        }

        int readShort() throws FormatException {
            need(2);
            int value = (short) ((bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF);
            position += 2;
            return value;
        }

        long readLong() throws FormatException {
            need(8);
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = value << 8 | bytes[position + i] & 0xFF;
            }
            position += 8;
            return value;
        }

        long[] longs(int count) throws FormatException {
            need(count * 8);
            long[] out = new long[count];
            for (int i = 0; i < count; i++) {
                out[i] = readLong();
            }
            return out;
        }

        /** As {@code VarInt.read}: at most 5 bytes. */
        int varInt() throws FormatException {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                int b = readByte();
                value |= (b & 0x7F) << 7 * i;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new FormatException("VarInt too big");
        }

        private void need(int count) throws FormatException {
            if (count < 0 || count > limit - position) {
                throw new FormatException("needs " + count + " bytes, " + (limit - position) + " left");
            }
        }
    }
}
