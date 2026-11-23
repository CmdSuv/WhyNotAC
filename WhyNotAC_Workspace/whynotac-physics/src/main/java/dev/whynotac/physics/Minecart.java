package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;
import java.util.Set;

/**
 * Vanilla 1.21.11 {@code AbstractMinecart} / {@code OldMinecartBehavior} rail motion, speed bounds, and
 * classification constants. Used by ride tracking and dismount-bound analysis to distinguish valid rail
 * momentum from impossible vehicle speeds without false positives on powered rails, downhill slopes, or
 * the experimental {@code minecartMaxSpeed} gamerule.
 */
public final class Minecart {

    /** Vanilla default max rail speed ({@code OldMinecartBehavior.getMaxSpeed}: {@code 8.0 / 20.0 = 0.4} blocks/tick). */
    public static final double DEFAULT_MAX_RAIL_SPEED = 8.0 / 20.0;
    /** Vanilla underwater max rail speed ({@code 4.0 / 20.0 = 0.2} blocks/tick). */
    public static final double WATER_MAX_RAIL_SPEED = 4.0 / 20.0;
    /** Vanilla rail slope acceleration ({@code 0.0078125 = 1.0 / 128.0} blocks/tick^2, half in water). */
    public static final double SLOPE_ACCELERATION = 0.0078125;
    /** Vanilla powered rail forward boost ({@code 0.06} blocks/tick when moving along rail axis). */
    public static final double POWERED_RAIL_BOOST = 0.06;
    /** Vanilla powered rail launch velocity from near-rest ({@code 0.02} against a solid block). */
    public static final double POWERED_RAIL_LAUNCH = 0.02;
    /** Vanilla occupied minecart natural rail drag ({@code 0.997} when ridden, {@code 0.96} when unoccupied). */
    public static final double OCCUPIED_RAIL_DRAG = 0.997;
    public static final double UNOCCUPIED_RAIL_DRAG = 0.96;
    /** Vanilla unpowered powered-rail braking factor ({@code 0.5} when speed >= 0.03, else 0). */
    public static final double UNPOWERED_BRAKE_FACTOR = 0.5;
    public static final double UNPOWERED_STOP_THRESHOLD = 0.03;
    /** Vanilla off-rail drag (ground {@code 0.5}, airborne {@code 0.95}). */
    public static final double GROUND_DRAG = 0.5;
    public static final double AIR_DRAG = 0.95;
    /** Vanilla vertical gravity ({@code 0.04} in air, {@code 0.005} in water). */
    public static final double GRAVITY_AIR = 0.04;
    public static final double GRAVITY_WATER = 0.005;
    /** Vanilla minecart hitbox dimensions ({@code 0.98 x 0.70}). */
    public static final float WIDTH = 0.98F;
    public static final float HEIGHT = 0.7F;

    public static final Set<String> MINECART_TYPES = Set.of(
            "minecraft:minecart",
            "minecraft:chest_minecart",
            "minecraft:furnace_minecart",
            "minecraft:tnt_minecart",
            "minecraft:hopper_minecart",
            "minecraft:command_block_minecart",
            "minecraft:spawner_minecart");

    public static final Set<String> RAIL_BLOCKS = Set.of(
            "minecraft:rail",
            "minecraft:powered_rail",
            "minecraft:detector_rail",
            "minecraft:activator_rail");

    private Minecart() {}

    public static boolean isMinecart(String entityType) {
        return entityType != null && MINECART_TYPES.contains(entityType);
    }

    public static boolean isRail(String blockKey) {
        return blockKey != null && RAIL_BLOCKS.contains(blockKey);
    }

    /**
     * Exact per-tick horizontal rail speed cap ({@code OldMinecartBehavior.getMaxSpeed}):
     * {@code 0.4} blocks/tick on dry rails, {@code 0.2} in water, or scaled proportionally when
     * {@code minecartMaxSpeedBlocksPerSec > 8.0}.
     */
    public static double maxSpeedPerTick(boolean inWater, double minecartMaxSpeedBlocksPerSec) {
        double base = Double.isFinite(minecartMaxSpeedBlocksPerSec) && minecartMaxSpeedBlocksPerSec > 0.0
                ? minecartMaxSpeedBlocksPerSec / 20.0
                : DEFAULT_MAX_RAIL_SPEED;
        return inWater ? base * 0.5 : base;
    }

    /**
     * One tick of vanilla 1.21.11 {@code OldMinecartBehavior} horizontal rail velocity update along a straight
     * or ascending rail ({@code north_south}, {@code east_west}, {@code ascending_east}, {@code ascending_west},
     * {@code ascending_north}, {@code ascending_south}) with slope gravity, powered-rail boost/braking,
     * natural drag, and speed clamping.
     */
    public static Vec3d stepStraightRailVelocity(Vec3d velocity, String railShape, boolean isPoweredRail,
            boolean powered, boolean occupied, boolean inWater, double maxSpeedBlocksPerSec) {
        if (velocity == null || !velocity.isFinite() || railShape == null) {
            return Vec3d.ZERO;
        }
        double vx = velocity.x();
        double vy = velocity.y();
        double vz = velocity.z();
        double slope = inWater ? SLOPE_ACCELERATION * 0.5 : SLOPE_ACCELERATION;
        switch (railShape) {
            case "ascending_east" -> vx -= slope;
            case "ascending_west" -> vx += slope;
            case "ascending_north" -> vz += slope;
            case "ascending_south" -> vz -= slope;
            default -> { }
        }
        // Project onto rail axis (X for east_west/ascending_east/ascending_west, Z for north_south/ascending_north/ascending_south)
        boolean xAxis = "east_west".equals(railShape) || "ascending_east".equals(railShape) || "ascending_west".equals(railShape);
        boolean zAxis = "north_south".equals(railShape) || "ascending_north".equals(railShape) || "ascending_south".equals(railShape);
        if (xAxis) {
            vz = 0.0;
        } else if (zAxis) {
            vx = 0.0;
        }
        double speed = Math.hypot(vx, vz);
        if (isPoweredRail && !powered) {
            if (speed < UNPOWERED_STOP_THRESHOLD) {
                vx = 0.0;
                vz = 0.0;
            } else {
                vx *= UNPOWERED_BRAKE_FACTOR;
                vz *= UNPOWERED_BRAKE_FACTOR;
            }
            vy = 0.0;
        } else if (isPoweredRail && powered && speed > 0.01) {
            vx += (vx / speed) * POWERED_RAIL_BOOST;
            vz += (vz / speed) * POWERED_RAIL_BOOST;
        }
        // Natural drag
        double drag = occupied ? OCCUPIED_RAIL_DRAG : UNOCCUPIED_RAIL_DRAG;
        vx *= drag;
        vy *= 0.0;
        vz *= drag;
        if (inWater) {
            vx *= 0.95;
            vz *= 0.95;
        }
        // Clamp per-axis speed to rail max speed (OldMinecartBehavior clamps movement to [-cap, +cap])
        double cap = maxSpeedPerTick(inWater, maxSpeedBlocksPerSec);
        vx = Math.max(-cap, Math.min(cap, vx));
        vz = Math.max(-cap, Math.min(cap, vz));
        return new Vec3d(vx, vy, vz);
    }

    /** One tick of vanilla off-rail motion (ground or airborne drag + gravity). */
    public static Vec3d stepOffRailVelocity(Vec3d velocity, boolean onGround, boolean inWater) {
        if (velocity == null || !velocity.isFinite()) {
            return Vec3d.ZERO;
        }
        double gravity = inWater ? GRAVITY_WATER : GRAVITY_AIR;
        double drag = onGround ? GROUND_DRAG : AIR_DRAG;
        double vx = velocity.x() * drag;
        double vy = (velocity.y() - gravity) * drag;
        double vz = velocity.z() * drag;
        return new Vec3d(vx, vy, vz);
    }
}
