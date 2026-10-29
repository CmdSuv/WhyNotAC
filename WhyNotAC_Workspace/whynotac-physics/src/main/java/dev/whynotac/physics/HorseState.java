package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;

/**
 * A horse (donkey, mule, skeleton or zombie horse), camel, pig, strider, happy ghast or nautilus the local player rides, as
 * the client holds it between two of its ticks ({@code AbstractHorse.tick}, {@code Camel.tick}, {@code Mob.tick},
 * {@code Strider.tick}, {@code HappyGhast.tick}, {@code AbstractNautilus.tick}): what {@link Horse} reads and writes.
 *
 * @param jumpPendingScale  {@code playerJumpPendingScale}: the rider's released jump ({@code onPlayerJump}), which the
 *                          next tick on the ground executes (a nautilus's next tick, wherever it is: its dash)
 * @param allowStandSliding {@code allowStandSliding}: a rearing horse still moves (set by the rider's jump, cleared by
 *                          a tick not rearing)
 * @param standing          {@code isStanding}: the rearing flag (the horse's data the server also sets)
 * @param standCounter      {@code standCounter}: the ticks left of a rearing the client started ({@code setStanding(20)}
 *                          when the rider jumps), 0 for none
 * @param interpolation     the interpolation the horse still runs toward where the server last put it (its steps
 *                          before the player controlled it), or null
 * @param lavaHeight        {@code getFluidHeight(FluidTags.LAVA)}: the lava its last {@code baseTick} found (a strider's
 *                          next tick reads it before its own {@code baseTick})
 * @param suffocating       {@code Strider.isSuffocating} as its last tick left it (false for any other)
 * @param dashCooldown      a nautilus's or camel's {@code dashCooldown}: the ticks before its rider may dash again (0 for any
 *                          other); -1 not known (the client may have set it before the ride: no lower than 0, no higher than
 *                          40, a camel's 55)
 * @param dashing           a camel's {@code isDashing}: its {@code DASH} data as the client holds it (false for any other)
 */
public record HorseState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround, boolean horizontalCollision,
        boolean verticalCollision, boolean verticalCollisionBelow, double fallDistance, BlockPos support, boolean onGroundNoBlocks,
        Vec3d stuckSpeedMultiplier, boolean inPowderSnow, int noJumpDelay, float jumpPendingScale, boolean allowStandSliding,
        boolean standing, int standCounter, BoatState.Interpolation interpolation, double lavaHeight, boolean suffocating, int dashCooldown,
        boolean dashing) {

    public HorseState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround, boolean horizontalCollision,
            boolean verticalCollision, boolean verticalCollisionBelow, double fallDistance, BlockPos support, boolean onGroundNoBlocks,
            Vec3d stuckSpeedMultiplier, boolean inPowderSnow, int noJumpDelay, float jumpPendingScale, boolean allowStandSliding,
            boolean standing, int standCounter, BoatState.Interpolation interpolation, double lavaHeight, boolean suffocating, int dashCooldown) {
        this(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, support,
                onGroundNoBlocks, stuckSpeedMultiplier, inPowderSnow, noJumpDelay, jumpPendingScale, allowStandSliding, standing, standCounter,
                interpolation, lavaHeight, suffocating, dashCooldown, false);
    }

    public HorseState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround, boolean horizontalCollision,
            boolean verticalCollision, boolean verticalCollisionBelow, double fallDistance, BlockPos support, boolean onGroundNoBlocks,
            Vec3d stuckSpeedMultiplier, boolean inPowderSnow, int noJumpDelay, float jumpPendingScale, boolean allowStandSliding,
            boolean standing, int standCounter, BoatState.Interpolation interpolation, double lavaHeight, boolean suffocating) {
        this(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, support,
                onGroundNoBlocks, stuckSpeedMultiplier, inPowderSnow, noJumpDelay, jumpPendingScale, allowStandSliding, standing, standCounter,
                interpolation, lavaHeight, suffocating, 0);
    }

    /** {@code EntityDimensions.makeBoundingBox}: the box of an entity {@code width} across and {@code height} tall at its feet. */
    public static Box box(double x, double y, double z, float width, float height) {
        double half = width / 2.0F;
        return new Box(x - half, y, z - half, x + half, y + height, z + half);
    }
}
