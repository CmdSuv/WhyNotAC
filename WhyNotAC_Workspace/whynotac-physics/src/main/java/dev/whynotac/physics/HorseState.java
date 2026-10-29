package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;

/**
 * A horse (donkey, mule, skeleton or zombie horse) the local player rides, as the client holds it between two of its
 * ticks ({@code AbstractHorse.tick}): what {@link Horse} reads and writes.
 *
 * @param jumpPendingScale  {@code playerJumpPendingScale}: the rider's released jump ({@code onPlayerJump}), which the
 *                          next tick on the ground executes
 * @param allowStandSliding {@code allowStandSliding}: a rearing horse still moves (set by the rider's jump, cleared by
 *                          a tick not rearing)
 * @param standing          {@code isStanding}: the rearing flag (the horse's data the server also sets)
 * @param standCounter      {@code standCounter}: the ticks left of a rearing the client started ({@code setStanding(20)}
 *                          when the rider jumps), 0 for none
 * @param interpolation     the interpolation the horse still runs toward where the server last put it (its steps
 *                          before the player controlled it), or null
 */
public record HorseState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround, boolean horizontalCollision,
        boolean verticalCollision, boolean verticalCollisionBelow, double fallDistance, BlockPos support, boolean onGroundNoBlocks,
        Vec3d stuckSpeedMultiplier, boolean inPowderSnow, int noJumpDelay, float jumpPendingScale, boolean allowStandSliding,
        boolean standing, int standCounter, BoatState.Interpolation interpolation) {

    /** {@code EntityDimensions.makeBoundingBox}: the box of an entity {@code width} across and {@code height} tall at its feet. */
    public static Box box(double x, double y, double z, float width, float height) {
        double half = width / 2.0F;
        return new Box(x - half, y, z - half, x + half, y + height, z + half);
    }
}
