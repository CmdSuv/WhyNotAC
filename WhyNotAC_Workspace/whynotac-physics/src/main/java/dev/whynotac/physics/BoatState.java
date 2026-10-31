package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;

/**
 * A boat (any {@code AbstractBoat}: boats, chest boats, rafts) the client's own player steers, between two of its ticks:
 * everything {@link Boat} reads or writes. Its motion, turn and status never reach the server ({@code MoveVehicle}
 * carries the position, rotation and ground flag); a caller that does not know them must try every value it cannot rule
 * out.
 *
 * @param deltaRotation        {@code AbstractBoat.deltaRotation}: the turn of the last tick, kept while it fades
 * @param status               the status the last tick ended with ({@code AbstractBoat.status}, the next tick's
 *                             {@code oldStatus}); null for a boat that never ticked
 * @param lastYd               the vertical motion before the last tick's move ({@code checkFallDamage}), 0 after a landing
 *                             on water: how far above the box {@code getWaterLevelAbove} looks
 * @param fallDistance         NaN when not known: every use of it then makes the tick {@link Colliders.Unknown}
 * @param support              {@code mainSupportingBlockPos}, null when empty
 * @param stuckSpeedMultiplier set by blocks the box was inside last tick (cobwebs, powder snow); zero otherwise
 * @param interpolation        its {@code InterpolationHandler} toward a position the server sent before the player took
 *                             it, while steps are left; null when none runs
 */
public record BoatState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, float deltaRotation, Status status,
        double lastYd, double fallDistance, boolean onGround, boolean horizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
        BlockPos support, boolean onGroundNoBlocks, Vec3d stuckSpeedMultiplier, Interpolation interpolation) {

    /** A boat no interpolation moves. */
    public BoatState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, float deltaRotation, Status status, double lastYd,
            double fallDistance, boolean onGround, boolean horizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
            BlockPos support, boolean onGroundNoBlocks, Vec3d stuckSpeedMultiplier) {
        this(x, y, z, deltaMovement, yRot, xRot, deltaRotation, status, lastYd, fallDistance, onGround, horizontalCollision, verticalCollision,
                verticalCollisionBelow, support, onGroundNoBlocks, stuckSpeedMultiplier, null);
    }

    /**
     * {@code InterpolationHandler} with steps left: the target ({@code InterpolationData}) and where its last step (or its
     * start) left the boat ({@code previousTickPosition}, {@code previousTickRot}; null when cancelled).
     */
    public record Interpolation(int steps, Vec3d target, float targetYRot, float targetXRot, Vec3d previousPosition, float previousYRot,
            float previousXRot) {
        public Interpolation {
            if (steps <= 0) {
                throw new IllegalArgumentException("no steps left");
            }
        }
    }

    /** {@code EntityType.Builder.sized(1.375F, 0.5625F)}: every boat, chest boat and raft. */
    public static final float WIDTH = 1.375F;
    public static final float HEIGHT = 0.5625F;

    /** {@code AbstractBoat.Status}, in its order. */
    public enum Status {
        IN_WATER,
        UNDER_WATER,
        UNDER_FLOWING_WATER,
        ON_LAND,
        IN_AIR
    }

    /** {@code EntityDimensions.makeBoundingBox} at a position: the half width in float ({@code width / 2.0F}). */
    public static Box box(double x, double y, double z) {
        double half = WIDTH / 2.0F;
        return new Box(x - half, y, z - half, x + half, y + HEIGHT, z + half);
    }

    public Box box() {
        return box(x, y, z);
    }
}
