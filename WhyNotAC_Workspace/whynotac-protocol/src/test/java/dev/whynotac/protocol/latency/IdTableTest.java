package dev.whynotac.protocol.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IdTableTest {

    @Test
    void behavesAsAMapUnderRandomOperations() {
        for (int seed = 0; seed < 50; seed++) {
            Random random = new Random(seed);
            // few distinct keys, many of them sharing home slots: long probe chains, shifts on every removal
            int[] pool = new int[1 + random.nextInt(300)];
            for (int i = 0; i < pool.length; i++) {
                pool[i] = random.nextBoolean() ? 65_536 * (1 + random.nextInt(64)) : random.nextInt() | 0x4000_0000;
            }
            IdTable table = new IdTable();
            Map<Integer, Long> expected = new HashMap<>();
            for (int step = 0; step < 4_000; step++) {
                int key = pool[random.nextInt(pool.length)];
                switch (random.nextInt(6)) {
                    case 0, 1 -> {
                        long value = random.nextLong();
                        table.put(key, value);
                        expected.put(key, value);
                    }
                    case 2 -> assertEquals(expected.remove(key) != null, table.remove(key));
                    case 3 -> assertEquals(expected.containsKey(key), table.contains(key));
                    case 4 -> assertEquals(expected.getOrDefault(key, -7L), table.get(key, -7L));
                    default -> {
                        long now = random.nextLong();
                        long age = random.nextLong(Long.MAX_VALUE);
                        int before = expected.size();
                        expected.values().removeIf(v -> now - v >= age);
                        assertEquals(before - expected.size(), table.removeOlderThan(now, age));
                    }
                }
                assertEquals(expected.size(), table.size());
            }
            for (int key : pool) {
                assertEquals(expected.containsKey(key), table.contains(key), "seed " + seed + " key " + key);
                assertEquals(expected.getOrDefault(key, -7L), table.get(key, -7L));
            }
        }
    }

    @Test
    void reservedIdsAreNeverPresentAndCannotBeStored() {
        IdTable table = new IdTable();
        table.put(1 << 20, 5);
        for (int reserved : new int[] {0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE}) {
            assertFalse(table.contains(reserved));
            assertFalse(table.remove(reserved));
            assertEquals(-1, table.get(reserved, -1));
            assertThrows(IllegalArgumentException.class, () -> table.put(reserved, 1));
        }
        assertEquals(5, table.get(1 << 20, -1));
    }
}
