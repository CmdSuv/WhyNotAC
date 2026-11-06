package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link Section#with}: the in-place rewrite gives the section the full repacking gives, value for value and equal. */
class SectionTest {

    private static final int STATES = 1_000;
    private static final int GLOBAL_BITS = 10;

    @Test
    void rewritingInPlaceGivesWhatRepackingGives() {
        int inPlace = 0;
        for (int seed = 0; seed < 20; seed++) {
            Random random = new Random(seed);
            Section section = Section.uniform(0);
            int[] expected = new int[4096];
            int pool = 2 + random.nextInt(6); // states the changes draw from; it grows past 16 and 256 now and then
            for (int step = 0; step < 300; step++) {
                if (random.nextInt(40) == 0) {
                    pool = Math.min(STATES + 2, pool + 1 + random.nextInt(random.nextBoolean() ? 20 : 300));
                }
                int count = random.nextInt(10) == 0 ? 1 + random.nextInt(300) : 1 + random.nextInt(8);
                int[] indices = new int[count];
                int[] states = new int[count];
                int near = random.nextInt(4096);
                for (int i = 0; i < count; i++) {
                    indices[i] = random.nextBoolean() ? random.nextInt(4096) : (near + random.nextInt(64)) & 4095;
                    int state = random.nextInt(pool);
                    states[i] = state == STATES ? -1 : state == STATES + 1 ? STATES + 7 : state; // invalid ones too
                    expected[indices[i]] = states[i] >= 0 && states[i] < STATES ? states[i] : Section.INVALID;
                }
                inPlace += section.canonical() ? 1 : 0;
                Section fast = section.with(indices, states, GLOBAL_BITS, STATES);
                Section repacked = section.repack(indices, states, GLOBAL_BITS, STATES);
                assertEquals(repacked, fast, "seed " + seed + " step " + step);
                assertEquals(repacked.hashCode(), fast.hashCode());
                for (int i = 0; i < 4096; i++) {
                    assertEquals(expected[i], fast.get(i), "seed " + seed + " step " + step + " index " + i);
                }
                section = fast;
            }
        }
        assertTrue(inPlace > 3_000, "the in-place rewrite was tried: " + inPlace);
    }

    @Test
    void aChangeThatLeavesOneStateGivesTheUniformSection() {
        Section one = Section.uniform(0).with(5, 7, GLOBAL_BITS, STATES);
        assertTrue(one.canonical());
        assertEquals(Section.uniform(0), one.with(5, 0, GLOBAL_BITS, STATES));
        assertEquals(Section.uniform(7), one.with(allBut(5), states(4095, 7), GLOBAL_BITS, STATES));
        // 19 states, 5 bits: the last long holds 4 values; the one left is the palette's last
        int[] first = new int[18];
        int[] others = new int[18];
        for (int i = 0; i < 18; i++) {
            first[i] = i;
            others[i] = 10 + i;
        }
        Section wide = Section.uniform(9).with(first, others, GLOBAL_BITS, STATES);
        assertTrue(wide.canonical());
        assertEquals(Section.uniform(9), wide.with(first, states(18, 9), GLOBAL_BITS, STATES));
    }

    @Test
    void aSectionAsDeliveredEndsEqualWhicheverWayItIsRewritten() {
        Section one = Section.uniform(0).with(5, 7, GLOBAL_BITS, STATES);
        Section delivered = one.asDelivered();
        assertFalse(delivered.canonical());
        assertTrue(delivered.rewritesInPlace(), "laid out as of packs it");
        assertEquals(one, delivered);
        assertEquals(one.with(6, 7, GLOBAL_BITS, STATES), delivered.with(6, 7, GLOBAL_BITS, STATES));
        assertTrue(delivered.with(6, 7, GLOBAL_BITS, STATES).canonical());
        // packed for another registry: repacked for this one
        assertEquals(delivered.with(6, 7, GLOBAL_BITS, 500), one.with(6, 7, GLOBAL_BITS, 500));
    }

    /**
     * Sections as a chunk packet may deliver them, well formed or with one flaw {@code of} would not reproduce: a palette
     * entry twice, a wider storage than needed, a set unused bit, a stored index past the palette. Only the well formed
     * ones are rewritten in place, and every rewrite equals unpacking and packing, representation and all.
     */
    @Test
    void deliveredLayoutsGiveWhatRepackingGives() {
        int[] inPlace = new int[5];
        int[] built = new int[5];
        for (int seed = 0; seed < 400; seed++) {
            Random random = new Random(seed);
            int flaw = seed % 5;
            int size = 1 + random.nextInt(flaw == 2 ? 100 : 40);
            int[] palette = random.ints(0, STATES).distinct().limit(size).toArray();
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(size - 1, 1)));
            if (flaw == 1) {
                if (size < 2) {
                    continue;
                }
                palette[size - 1] = palette[0];
            }
            if (flaw == 2) {
                if (bits == 8) {
                    continue;
                }
                bits++;
            }
            int perLong = 64 / bits;
            long[] data = new long[Section.longsFor(bits, 4096)];
            int[] values = new int[4096];
            for (int i = 0; i < 4096; i++) {
                values[i] = random.nextInt(size);
                data[i / perLong] |= (long) values[i] << (i % perLong) * bits;
            }
            if (flaw == 3) {
                int last = data.length - 1;
                int usedInLast = (4096 - last * perLong) * bits;
                if (perLong * bits < 64 && random.nextBoolean()) {
                    data[random.nextInt(last)] |= 1L << 63;
                } else if (usedInLast < 64) {
                    data[last] |= 1L << 63;
                } else {
                    continue;
                }
            }
            if (flaw == 4) {
                if (size == 1 << bits) {
                    continue;
                }
                int i = random.nextInt(4096);
                data[i / perLong] |= (long) size << (i % perLong) * bits;
            }
            Section section = Section.packed(bits, data, palette, STATES);
            built[flaw]++;
            inPlace[flaw] += section.rewritesInPlace() ? 1 : 0;
            for (int step = 0; step < 4; step++) {
                int count = 1 + random.nextInt(random.nextBoolean() ? 6 : 200);
                int[] indices = new int[count];
                int[] states = new int[count];
                for (int i = 0; i < count; i++) {
                    indices[i] = random.nextInt(4096);
                    states[i] = random.nextInt(8) == 0 ? random.nextInt(STATES) : palette[random.nextInt(size)];
                }
                int[] expected = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    expected[i] = section.get(i);
                }
                for (int i = 0; i < count; i++) {
                    expected[indices[i]] = states[i];
                }
                Section rewritten = section.with(indices, states, GLOBAL_BITS, STATES);
                assertEquals(section.repack(indices, states, GLOBAL_BITS, STATES), rewritten, "seed " + seed + " step " + step);
                for (int i = 0; i < 4096; i++) {
                    assertEquals(expected[i], rewritten.get(i), "seed " + seed + " step " + step + " index " + i);
                }
                section = rewritten;
            }
        }
        assertEquals(built[0], inPlace[0], "every well formed section is rewritten in place");
        for (int flaw = 1; flaw < 5; flaw++) {
            assertTrue(built[flaw] > 20, "flaw " + flaw + " built " + built[flaw] + " times");
            assertEquals(0, inPlace[flaw], "a section with flaw " + flaw + " is repacked");
        }
    }

    private static int[] allBut(int index) {
        int[] indices = new int[4095];
        for (int i = 0, n = 0; i < 4096; i++) {
            if (i != index) {
                indices[n++] = i;
            }
        }
        return indices;
    }

    private static int[] states(int count, int state) {
        int[] states = new int[count];
        Arrays.fill(states, state);
        return states;
    }
}
