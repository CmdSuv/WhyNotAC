package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;

/**
 * The client's own player between two ticks: everything {@link OnFoot} reads or writes. Several fields
 * never reach the server ({@code noJumpDelay}, {@code sprintTriggerTime}, the supporting block, the
 * crouching flag); a caller that does not know them must try every value it cannot rule out.
 *
 * @param sprintModifier    the movement speed attribute holds the sprinting modifier: set with the flag by
 *                          {@code setSprinting}, but the server can change either alone (its own flags and
 *                          attributes, echoed back)
 * @param keys              {@code input.keyPresses}: the keys the last tick polled
 * @param support           {@code mainSupportingBlockPos}, null when empty
 * @param stuckSpeedMultiplier set by blocks the box was inside last tick (cobwebs, berry bushes); zero otherwise
 * @param fallDistance      NaN when not known: every use of it then makes the tick {@link Colliders.Unknown}
 * @param swimming          the shared swimming flag ({@code isSwimming}): set by {@code updateSwimming}, but the server
 *                          can echo its own
 * @param eyeInWater        {@code isEyeInFluid(FluidTags.WATER)}: the eye was in water at the last tick's start
 *                          ({@code updateFluidOnEyes}); the next tick's {@code isUnderWater} reads it
 * @param fallFlying        the shared gliding flag ({@code isFallFlying}): set by the client when it starts gliding, cleared
 *                          only by the server's own (echoed back) or a climbable block in the glide
 * @param inPowderSnow      {@code isInPowderSnow}: the tick's move went through powder snow ({@code InsideBlockEffectType.FREEZE});
 *                          the next tick's {@code wasInPowderSnow}
 * @param inWater           {@code wasTouchingWater}, as the tick's {@code baseTick} left it: what an item use before the next
 *                          tick reads ({@code isInWater}, a riptide's {@code isInWaterOrRain}); null when not known
 * @param spinAttackTicks   {@code LivingEntity.autoSpinAttackTicks}, the ticks a riptide's spin attack still runs (set to 20
 *                          by the release, down one each tick while above 0); under 0 when not known, at most its opposite
 */
public record PlayerState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround,
        boolean horizontalCollision, boolean minorHorizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
        double fallDistance, boolean sprinting, boolean sprintModifier, boolean crouching, Pose pose, Keys keys, int noJumpDelay, int sprintTriggerTime,
        int autoJumpTime, BlockPos support, boolean onGroundNoBlocks, Vec3d stuckSpeedMultiplier, boolean swimming, boolean eyeInWater,
        boolean fallFlying, boolean inPowderSnow, Boolean inWater, int spinAttackTicks) {

    /** A state whose last {@code baseTick}'s water flag is not known, not in a spin attack. */
    public PlayerState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround, boolean horizontalCollision,
            boolean minorHorizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow, double fallDistance, boolean sprinting,
            boolean sprintModifier, boolean crouching, Pose pose, Keys keys, int noJumpDelay, int sprintTriggerTime, int autoJumpTime, BlockPos support,
            boolean onGroundNoBlocks, Vec3d stuckSpeedMultiplier, boolean swimming, boolean eyeInWater, boolean fallFlying, boolean inPowderSnow) {
        this(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision, verticalCollisionBelow,
                fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support, onGroundNoBlocks,
                stuckSpeedMultiplier, swimming, eyeInWater, fallFlying, inPowderSnow, null, 0);
    }

    /** A state not in powder snow. */
    public PlayerState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround, boolean horizontalCollision,
            boolean minorHorizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow, double fallDistance, boolean sprinting,
            boolean sprintModifier, boolean crouching, Pose pose, Keys keys, int noJumpDelay, int sprintTriggerTime, int autoJumpTime, BlockPos support,
            boolean onGroundNoBlocks, Vec3d stuckSpeedMultiplier, boolean swimming, boolean eyeInWater, boolean fallFlying) {
        this(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision, verticalCollisionBelow,
                fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support, onGroundNoBlocks,
                stuckSpeedMultiplier, swimming, eyeInWater, fallFlying, false);
    }

    /** A state not gliding. */
    public PlayerState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround, boolean horizontalCollision,
            boolean minorHorizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow, double fallDistance, boolean sprinting,
            boolean sprintModifier, boolean crouching, Pose pose, Keys keys, int noJumpDelay, int sprintTriggerTime, int autoJumpTime, BlockPos support,
            boolean onGroundNoBlocks, Vec3d stuckSpeedMultiplier, boolean swimming, boolean eyeInWater) {
        this(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision, verticalCollisionBelow,
                fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support, onGroundNoBlocks,
                stuckSpeedMultiplier, swimming, eyeInWater, false, false);
    }

    /** This state with the water flags. */
    public PlayerState withWater(boolean swimming, boolean eyeInWater) {
        return new PlayerState(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision,
                verticalCollisionBelow, fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support,
                onGroundNoBlocks, stuckSpeedMultiplier, swimming, eyeInWater, fallFlying, inPowderSnow, inWater, spinAttackTicks);
    }

    /** This state with the gliding flag. */
    public PlayerState withFallFlying(boolean fallFlying) {
        return new PlayerState(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision,
                verticalCollisionBelow, fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support,
                onGroundNoBlocks, stuckSpeedMultiplier, swimming, eyeInWater, fallFlying, inPowderSnow, inWater, spinAttackTicks);
    }

    /** This state with its spin attack's count ({@code autoSpinAttackTicks}; under 0: not known, at most its opposite). */
    public PlayerState withSpinAttackTicks(int ticks) {
        return new PlayerState(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision,
                verticalCollisionBelow, fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support,
                onGroundNoBlocks, stuckSpeedMultiplier, swimming, eyeInWater, fallFlying, inPowderSnow, inWater, ticks);
    }

    /** This state with the powder snow flag. */
    public PlayerState withInPowderSnow(boolean inPowderSnow) {
        return new PlayerState(x, y, z, deltaMovement, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision,
                verticalCollisionBelow, fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support,
                onGroundNoBlocks, stuckSpeedMultiplier, swimming, eyeInWater, fallFlying, inPowderSnow, inWater, spinAttackTicks);
    }

    /** The poses the step handles ({@code Player.POSES}; SWIMMING is crawling too). */
    public enum Pose {
        STANDING(0.6F, 1.8F, 1.62F),
        CROUCHING(0.6F, 1.5F, 1.27F),
        SWIMMING(0.6F, 0.6F, 0.4F),
        FALL_FLYING(0.6F, 0.6F, 0.4F),
        SPIN_ATTACK(0.6F, 0.6F, 0.4F);

        final float width;
        final float height;
        /** {@code EntityDimensions.eyeHeight}. */
        final float eyeHeight;

        Pose(float width, float height, float eyeHeight) {
            this.width = width;
            this.height = height;
            this.eyeHeight = eyeHeight;
        }

        /** {@code EntityDimensions.makeBoundingBox(position)}: the box is rebuilt from the position, not moved. */
        public Box box(double x, double y, double z) {
            float half = width / 2.0F;
            return Box.of(x - half, y, z - half, x + half, y + height, z + half);
        }
    }

    /** {@code Input}: the keys of one tick, in the record's order. */
    public record Keys(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {

        public static final Keys NONE = new Keys(false, false, false, false, false, false, false);

        Keys withJump() {
            return new Keys(forward, backward, left, right, true, shift, sprint);
        }
    }

    public Box box() {
        return pose.box(x, y, z);
    }
}
