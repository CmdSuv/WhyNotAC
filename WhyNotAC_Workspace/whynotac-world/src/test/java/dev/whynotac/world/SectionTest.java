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
                Section repacked = section.asDelivered().with(indices, states, GLOBAL_BITS, STATES);
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
    void aSectionAsDeliveredIsNotRewrittenInPlaceButEndsEqual() {
        Section one = Section.uniform(0).with(5, 7, GLOBAL_BITS, STATES);
        Section delivered = one.asDelivered();
        assertFalse(delivered.canonical());
        assertEquals(one, delivered);
        assertEquals(one.with(6, 7, GLOBAL_BITS, STATES), delivered.with(6, 7, GLOBAL_BITS, STATES));
        assertTrue(delivered.with(6, 7, GLOBAL_BITS, STATES).canonical());
        // packed for another registry: repacked for this one
        assertEquals(delivered.with(6, 7, GLOBAL_BITS, 500), one.with(6, 7, GLOBAL_BITS, 500));
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
