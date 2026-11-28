package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import org.junit.jupiter.api.Test;

class MinecartTest {

    @Test
    void classifiesMinecartsAndRailsAndRespectsSpeedBounds() {
        assertTrue(Minecart.isMinecart("minecraft:minecart"));
        assertTrue(Minecart.isMinecart("minecraft:chest_minecart"));
        assertTrue(Minecart.isMinecart("minecraft:tnt_minecart"));
        assertFalse(Minecart.isMinecart("minecraft:oak_boat"));
        assertFalse(Minecart.isMinecart(null));

        assertTrue(Minecart.isRail("minecraft:rail"));
        assertTrue(Minecart.isRail("minecraft:powered_rail"));
        assertTrue(Minecart.isRail("minecraft:detector_rail"));
        assertTrue(Minecart.isRail("minecraft:activator_rail"));
        assertFalse(Minecart.isRail("minecraft:stone"));

        assertEquals(0.4, Minecart.maxSpeedPerTick(false, 8.0), 1.0e-12);
        assertEquals(0.2, Minecart.maxSpeedPerTick(true, 8.0), 1.0e-12);
        assertEquals(1.0, Minecart.maxSpeedPerTick(false, 20.0), 1.0e-12);
        assertEquals(0.5, Minecart.maxSpeedPerTick(true, 20.0), 1.0e-12);
    }

    @Test
    void poweredRailAcceleratesAndClampsToDefaultRailCap() {
        Vec3d v = new Vec3d(0.1, 0.0, 0.0);
        for (int i = 0; i < 20; i++) {
            v = Minecart.stepStraightRailVelocity(v, "east_west", true, true, true, false, 8.0);
        }
        assertEquals(0.4, v.x(), 1.0e-9);
        assertEquals(0.0, v.y(), 1.0e-9);
        assertEquals(0.0, v.z(), 1.0e-9);

        // Unpowered powered rail brakes cleanly to zero
        Vec3d braked = Minecart.stepStraightRailVelocity(new Vec3d(0.2, 0.0, 0.0), "east_west", true, false, true, false, 8.0);
        assertEquals(0.2 * 0.5 * Minecart.OCCUPIED_RAIL_DRAG, braked.x(), 1.0e-9);
        Vec3d stopped = Minecart.stepStraightRailVelocity(new Vec3d(0.02, 0.0, 0.0), "east_west", true, false, true, false, 8.0);
        assertEquals(Vec3d.ZERO, stopped);

        // Off-rail air drag and gravity
        Vec3d air = Minecart.stepOffRailVelocity(new Vec3d(0.4, 0.0, 0.0), false, false);
        assertEquals(0.4 * 0.95, air.x(), 1.0e-9);
        assertEquals(-0.04 * 0.95, air.y(), 1.0e-9);
    }
}
