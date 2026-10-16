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
 */
public record PlayerState(double x, double y, double z, Vec3d deltaMovement, float yRot, float xRot, boolean onGround,
        boolean horizontalCollision, boolean minorHorizontalCollision, boolean verticalCollision, boolean verticalCollisionBelow,
        double fallDistance, boolean sprinting, boolean sprintModifier, boolean crouching, Pose pose, Keys keys, int noJumpDelay, int sprintTriggerTime,
        int autoJumpTime, BlockPos support, boolean onGroundNoBlocks, Vec3d stuckSpeedMultiplier) {

    /** The poses the on-foot step handles. */
    public enum Pose {
        STANDING(0.6F, 1.8F),
        CROUCHING(0.6F, 1.5F),
        SWIMMING(0.6F, 0.6F);

        final float width;
        final float height;

        Pose(float width, float height) {
            this.width = width;
            this.height = height;
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
