package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LongMapTest {

    @Test
    void behavesLikeAHashMapUnderRandomOperations() {
        for (int seed = 0; seed < 50; seed++) {
            Random random = new Random(seed);
            LongMap<Long> map = new LongMap<>();
            Map<Long, Long> reference = new HashMap<>();
            // Few distinct keys, many of them colliding chunk keys, so clusters form and removals shift them.
            long[] keys = new long[64];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = i < 8 ? Long.MIN_VALUE + i : ClientWorld.chunkKey(random.nextInt(9) - 4, random.nextInt(9) - 4) + (long) random.nextInt(3) * 1024;
            }
            for (int step = 0; step < 20_000; step++) {
                long key = keys[random.nextInt(keys.length)];
                switch (random.nextInt(4)) {
                    case 0, 1 -> {
                        long value = random.nextLong();
                        map.put(key, value);
                        reference.put(key, value);
                    }
                    case 2 -> assertEquals(reference.remove(key), map.remove(key), "remove " + key + " at step " + step + ", seed " + seed);
                    default -> {
                        if (random.nextInt(1000) == 0) {
                            map.clear();
                            reference.clear();
                        }
                    }
                }
                assertEquals(reference.size(), map.size());
                for (long k : keys) {
                    assertEquals(reference.get(k), map.get(k), "get " + k + " at step " + step + ", seed " + seed);
                }
            }
            Map<Long, Long> seen = new HashMap<>();
            map.forEach(seen::put);
            assertEquals(reference, seen);
        }
    }

    @Test
    void growsPastItsInitialCapacity() {
        LongMap<Integer> map = new LongMap<>();
        for (int i = 0; i < 10_000; i++) {
            map.put(ClientWorld.chunkKey(i % 100, i / 100), i);
        }
        assertEquals(10_000, map.size());
        for (int i = 0; i < 10_000; i++) {
            assertEquals(i, map.get(ClientWorld.chunkKey(i % 100, i / 100)));
        }
        for (int i = 0; i < 10_000; i += 2) {
            assertEquals(i, map.remove(ClientWorld.chunkKey(i % 100, i / 100)));
        }
        for (int i = 0; i < 10_000; i++) {
            assertEquals(i % 2 == 0 ? null : (Integer) i, map.get(ClientWorld.chunkKey(i % 100, i / 100)));
        }
    }
}
