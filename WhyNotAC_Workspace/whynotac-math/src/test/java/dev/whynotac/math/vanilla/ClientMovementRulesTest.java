package dev.whynotac.math.vanilla;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientMovementRulesTest {

    @Test
    void positionThresholdIsStrictAndSquared() {
        assertFalse(ClientMovementRules.sendsPosition(2.0E-4, 0.0, 0.0, 0));
        assertTrue(ClientMovementRules.sendsPosition(Math.nextUp(2.0E-4), 0.0, 0.0, 0));
        // Diagonal: each axis below the threshold, total length above it.
        assertTrue(ClientMovementRules.sendsPosition(1.5E-4, 1.5E-4, 0.0, 0));
        assertTrue(ClientMovementRules.sendsPosition(0.0, 0.0, 0.0, 20));
        assertFalse(ClientMovementRules.sendsPosition(0.0, 0.0, 0.0, 19));
    }

    @Test
    void anyRotationChangeIsSent() {
        assertTrue(ClientMovementRules.sendsRotation(Math.nextUp(90.0F), 90.0F, 0.0F, 0.0F));
        assertFalse(ClientMovementRules.sendsRotation(90.0F, 90.0F, -12.0F, -12.0F));
    }
}
