package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.checks.AttackReach.Box;
import dev.whynotac.analysis.checks.AttackReach.Eye;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.world.ClientInventory.Reach;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AttackReachTest {

    private static final Eye ORIGIN = new Eye(0.0, 0.0, 0.0, 0.0, 0.0);
    private static final Set<Reach> HAND = Set.of(Reach.NONE);

    /** A 1x1x1 box whose near face is {@code d} away along x, centred on the eye in y and z. */
    private static Box at(double d) {
        return new Box(d, -0.5, -0.5, d + 1.0, 0.5, 0.5);
    }

    private static Reach range(float max, float margin) {
        return new Reach(new Stack.AttackRange(0.0F, max, 0.0F, 5.0F, margin, 1.0F), true);
    }

    @Test
    void distanceIsToTheNearestPointOfEveryEyePosition() {
        assertEquals(3.0, AttackReach.distance(ORIGIN, at(3.0)), 1e-12);
        assertEquals(2.5, AttackReach.distance(new Eye(0.0, 0.0, 0.0, 0.0, 0.5), at(3.0)), 1e-12);
        assertEquals(0.0, AttackReach.distance(ORIGIN, new Box(-1, -1, -1, 1, 1, 1)), 0.0);
        // the eye's height spans [0, 2]: a box above at y 3..4 is 1 away
        assertEquals(1.0, AttackReach.distance(new Eye(0.0, 0.0, 0.0, 2.0, 0.0), new Box(-0.5, 3.0, -0.5, 0.5, 4.0, 0.5)), 1e-12);
        assertEquals(5.0, AttackReach.distance(ORIGIN, new Box(3.0, 0.0, 4.0, 4.0, 1.0, 5.0)), 1e-12);
    }

    @Test
    void anEmptyHandReachesStrictlyLessThanTheAttribute() {
        assertFalse(AttackReach.outOfReach(ORIGIN, at(2.99), 0.0, HAND, HAND, 3.0, false));
        assertTrue(AttackReach.outOfReach(ORIGIN, at(3.01), 0.0, HAND, HAND, 3.0, false));
        assertFalse(AttackReach.outOfReach(ORIGIN, at(4.99), 0.0, HAND, HAND, 5.0, true));
        assertTrue(AttackReach.outOfReach(ORIGIN, at(5.01), 0.0, HAND, HAND, 5.0, true));
    }

    @Test
    void thePickRadiusInflatesTheTarget() {
        // a projectile's box is picked inflated by 1
        assertFalse(AttackReach.outOfReach(ORIGIN, at(3.9), 1.0, HAND, HAND, 3.0, false));
        assertTrue(AttackReach.outOfReach(ORIGIN, at(4.1), 1.0, HAND, HAND, 3.0, false));
    }

    @Test
    void anAttackRangeIsBoundedByItsMaximumAndMargin() {
        Set<Reach> stick = Set.of(range(4.5F, 0.25F));
        assertFalse(AttackReach.outOfReach(ORIGIN, at(4.74), 0.0, stick, stick, 3.0, false));
        assertTrue(AttackReach.outOfReach(ORIGIN, at(4.76), 0.0, stick, stick, 3.0, false));
        // in creative the creative maximum (5) applies
        assertFalse(AttackReach.outOfReach(ORIGIN, at(5.2), 0.0, stick, stick, 3.0, true));
        assertTrue(AttackReach.outOfReach(ORIGIN, at(5.3), 0.0, stick, stick, 3.0, true));
    }

    @Test
    void aShortAttackRangeCutsTheVanillaPick() {
        // the pick (no attack_range then) allows 3, the stack at the attack allows only 2.3
        Set<Reach> dagger = Set.of(range(2.0F, 0.3F));
        assertFalse(AttackReach.outOfReach(ORIGIN, at(2.2), 0.0, HAND, dagger, 3.0, false));
        assertTrue(AttackReach.outOfReach(ORIGIN, at(2.5), 0.0, HAND, dagger, 3.0, false));
    }

    @Test
    void anAttackRangeAtThePickIsNotBoundedByTheAttribute() {
        // picked with a long-range stack, attacked with an empty hand after a hotbar key in the same tick
        Set<Reach> pick = Set.of(range(6.0F, 0.3F));
        assertFalse(AttackReach.outOfReach(ORIGIN, at(10.0), 0.0, pick, HAND, 3.0, false));
    }

    @Test
    void anyPossiblePairExplainsTheAttack() {
        Set<Reach> pick = Set.of(Reach.NONE, range(6.0F, 0.3F));
        Set<Reach> check = Set.of(Reach.NONE, range(2.0F, 0.0F));
        assertFalse(AttackReach.outOfReach(ORIGIN, at(5.0), 0.0, pick, check, 3.0, false));
        Set<Reach> shortOnly = Set.of(range(2.0F, 0.0F), range(2.5F, 0.0F));
        assertTrue(AttackReach.outOfReach(ORIGIN, at(2.6), 0.0, pick, shortOnly, 3.0, false));
    }

    @Test
    void anUnknownReachClaimsNothing() {
        Set<Reach> unknown = Set.of(Reach.NONE, Reach.UNKNOWN);
        assertFalse(AttackReach.outOfReach(ORIGIN, at(50.0), 0.0, unknown, HAND, 3.0, false));
        assertFalse(AttackReach.outOfReach(ORIGIN, at(50.0), 0.0, HAND, unknown, 3.0, false));
    }
}
