package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;
import java.util.List;

/** {@code Entity.collide}: the movement the player's box can make through the colliders, stepping up where it may. */
public final class Collide {

    private Collide() {}

    /**
     * {@code Entity.collide(movement)} for the box {@code box}. {@code entityColliders} are the solid entities
     * near the box ({@code level.getEntityCollisions(this, box.expandTowards(movement))}).
     */
    public static Vec3d collide(Colliders.World world, Box box, Vec3d movement, boolean onGround, float maxUpStep, List<Voxels> entityColliders)
            throws Colliders.Unknown {
        Vec3d collided = movement.lengthSqr() == 0.0 ? movement : collideBoundingBox(world, movement, box, entityColliders);
        boolean xCollided = movement.x() != collided.x();
        boolean yCollided = movement.y() != collided.y();
        boolean zCollided = movement.z() != collided.z();
        boolean landed = yCollided && movement.y() < 0.0;
        if (maxUpStep > 0.0F && (landed || onGround) && (xCollided || zCollided)) {
            Box base = landed ? box.move(0.0, collided.y(), 0.0) : box;
            Box stepBox = base.expandTowards(movement.x(), maxUpStep, movement.z());
            if (!landed) {
                stepBox = stepBox.expandTowards(0.0, -1.0E-5F, 0.0);
            }
            List<Voxels> colliders = Colliders.collect(world, entityColliders, stepBox);
            float collidedY = (float) collided.y();
            for (float height : candidateStepUpHeights(base, colliders, maxUpStep, collidedY)) {
                Vec3d step = collideWithShapes(new Vec3d(movement.x(), height, movement.z()), base, colliders);
                if (step.horizontalDistanceSqr() > collided.horizontalDistanceSqr()) {
                    double dy = box.minY() - base.minY();
                    return step.subtract(0.0, dy, 0.0);
                }
            }
        }
        return collided;
    }

    /** {@code Entity.collideBoundingBox}. */
    public static Vec3d collideBoundingBox(Colliders.World world, Vec3d movement, Box box, List<Voxels> entityColliders) throws Colliders.Unknown {
        List<Voxels> colliders = Colliders.collect(world, entityColliders, box.expandTowards(movement.x(), movement.y(), movement.z()));
        return collideWithShapes(movement, box, colliders);
    }

    /**
     * {@code Entity.collectCandidateStepUpHeights}: every collider's Y coordinates relative to the box's bottom,
     * from 0 up to the step height (each shape's coordinates are ascending, so the first above ends it),
     * except the height the plain collision reached; distinct, ascending.
     */
    public static float[] candidateStepUpHeights(Box box, List<Voxels> colliders, float maxUpStep, float collidedY) {
        float[] set = new float[4];
        int size = 0;
        for (Voxels shape : colliders) {
            int n = shape.size(Axis.Y);
            for (int i = 0; i <= n; i++) {
                float height = (float) (shape.coord(Axis.Y, i) - box.minY());
                if (!(height < 0.0F) && height != collidedY) {
                    if (height > maxUpStep) {
                        break;
                    }
                    boolean present = false;
                    for (int j = 0; j < size; j++) {
                        if (Float.floatToIntBits(set[j]) == Float.floatToIntBits(height)) { // FloatArraySet
                            present = true;
                            break;
                        }
                    }
                    if (!present) {
                        if (size == set.length) {
                            set = java.util.Arrays.copyOf(set, size * 2);
                        }
                        set[size++] = height;
                    }
                }
            }
        }
        float[] heights = java.util.Arrays.copyOf(set, size);
        java.util.Arrays.sort(heights);
        return heights;
    }

    /** {@code Entity.collideWithShapes}: axis by axis, Y first, then the larger horizontal component last. */
    public static Vec3d collideWithShapes(Vec3d movement, Box box, List<Voxels> colliders) {
        if (colliders.isEmpty()) {
            return movement;
        }
        double[] result = {0.0, 0.0, 0.0};
        // Direction.axisStepOrder: YZX if |x| < |z|, else YXZ
        Axis[] order = Math.abs(movement.x()) < Math.abs(movement.z()) ? new Axis[] {Axis.Y, Axis.Z, Axis.X} : new Axis[] {Axis.Y, Axis.X, Axis.Z};
        for (Axis axis : order) {
            double distance = axis.choose(movement.x(), movement.y(), movement.z());
            if (distance != 0.0) {
                result[axis.ordinal()] = VoxelCollisions.collide(axis, box.move(result[0], result[1], result[2]), colliders, distance);
            }
        }
        return new Vec3d(result[0], result[1], result[2]);
    }
}
