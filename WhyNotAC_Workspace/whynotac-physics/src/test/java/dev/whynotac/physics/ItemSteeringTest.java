package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@code ItemBasedSteering} (decompiled 1.21.11): {@code boostTime++ > boostTimeTotal()} ends the boost one tick past its
 * total, and the factor rises to 2.15 halfway.
 */
class ItemSteeringTest {

    @Test
    void theBoostEndsTheTickAfterItsCountPassesTheTotal() {
        ItemSteering s = ItemSteering.synced();
        for (int i = 0; i <= 140; i++) {
            s = s.tick(140);
            assertTrue(s.boosting(), "tick " + i);
        }
        assertEquals(141, s.boostTime());
        s = s.tick(140);
        assertFalse(s.boosting(), "141 > 140");
        assertEquals(142, s.boostTime());
        assertEquals(1.0F, s.factor(140));
        assertEquals(s, s.tick(140), "a boost over counts no more");
    }

    @Test
    void theFactorIsTheClientsFloatSine() {
        assertEquals(1.0F, ItemSteering.synced().factor(140));
        ItemSteering half = new ItemSteering(true, 70);
        assertEquals(1.0F + 1.15F * dev.whynotac.math.vanilla.VanillaMath.sin(70.0F / 140 * (float) Math.PI), half.factor(140));
        assertEquals(2.15F, half.factor(140), 1.0e-6F);
        assertEquals(1.0F, ItemSteering.NONE.factor(140));
    }
}
