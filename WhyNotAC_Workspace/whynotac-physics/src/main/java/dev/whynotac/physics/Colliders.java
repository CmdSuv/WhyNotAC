package dev.whynotac.physics;

import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Shape;
import java.util.ArrayList;
import java.util.List;

/**
 * The shapes the client's own player collides with ({@code Entity.collectColliders}): the solid entities
 * near it, then the world border when close, then the blocks ({@code BlockCollisions}), in the game's
 * order. Where the client's shape is not known (an unknown block, one whose shape depends on the world,
 * on an offset not modelled yet or on the entity), {@link Unknown} is thrown: no claim can rest on it.
 */
public final class Colliders {

    /** The client's blocks: {@link ClientWorld#UNKNOWN} where WhyNotAC does not know them. */
    public interface World {
        BlockStates states();

        int blockState(int x, int y, int z);
    }

    /** A collision shape the client has that WhyNotAC cannot reproduce. */
    public static final class Unknown extends Exception {
        public Unknown(String reason) {
            super(reason, null, false, false);
        }
    }

    private Colliders() {}

    /** {@code Entity.collectColliders(player, level, entityColliders, box)}. The world border must be far (not modelled yet). */
    public static List<Voxels> collect(World world, List<Voxels> entityColliders, Box box) throws Unknown {
        List<Voxels> colliders = new ArrayList<>(entityColliders.size() + 8);
        colliders.addAll(entityColliders);
        blocks(world, box, colliders);
        return colliders;
    }

    /** {@code Level.getBlockCollisions(player, box)}: {@code BlockCollisions}, cell by cell in {@code Cursor3D} order. */
    static void blocks(World world, Box box, List<Voxels> out) throws Unknown {
        BlockStates states = world.states();
        int x0 = floor(box.minX() - VoxelCollisions.EPSILON) - 1;
        int x1 = floor(box.maxX() + VoxelCollisions.EPSILON) + 1;
        int y0 = floor(box.minY() - VoxelCollisions.EPSILON) - 1;
        int y1 = floor(box.maxY() + VoxelCollisions.EPSILON) + 1;
        int z0 = floor(box.minZ() - VoxelCollisions.EPSILON) - 1;
        int z1 = floor(box.maxZ() + VoxelCollisions.EPSILON) + 1;
        Voxels entityShape = null;
        // Cursor3D: x fastest, then y, then z; cells on 1, 2 or 3 faces of the range are faces, edges, corners.
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int type = (x == x0 || x == x1 ? 1 : 0) + (y == y0 || y == y1 ? 1 : 0) + (z == z0 || z == z1 ? 1 : 0);
                    if (type == 3) {
                        continue;
                    }
                    int state = world.blockState(x, y, z);
                    if (state == ClientWorld.UNKNOWN) {
                        throw new Unknown("unknown block at " + x + " " + y + " " + z);
                    }
                    Shape shape = states.collisionShape(state);
                    boolean dynamic = states.has(state, BlockStates.DYNAMIC_SHAPE);
                    // hasLargeCollisionShape(): true without a shape cache (dynamic shapes), else beyond the unit cube
                    if (type == 1 && !(dynamic || isLarge(shape))) {
                        continue;
                    }
                    if (type == 2 && !states.block(state).equals("minecraft:moving_piston")) {
                        continue;
                    }
                    shape = playerShape(states, state, shape, x, y, z);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    if (states.has(state, BlockStates.FULL_BLOCK)) {
                        if (box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                            out.add(Voxels.moved(shape, x, y, z));
                        }
                    } else {
                        Voxels moved = Voxels.moved(shape, x, y, z);
                        if (entityShape == null) {
                            entityShape = VoxelCollisions.create(box);
                        }
                        if (VoxelCollisions.overlaps(moved, entityShape)) {
                            out.add(moved);
                        }
                    }
                }
            }
        }
    }

    /** {@code EntityCollisionContext.getCollisionShape} for a player: the table's shape where it is the client's. */
    private static Shape playerShape(BlockStates states, int state, Shape shape, int x, int y, int z) throws Unknown {
        if (states.has(state, BlockStates.DYNAMIC_SHAPE)) {
            throw new Unknown(states.block(state) + " (shape depends on the world) at " + x + " " + y + " " + z);
        }
        if (states.has(state, BlockStates.CONTEXTUAL_COLLISION)) {
            String block = states.block(state);
            if (block.equals("minecraft:water") || block.equals("minecraft:lava")) {
                return Shape.EMPTY; // LiquidBlock: solid only to entities that stand on fluids, never a player
            }
            throw new Unknown(block + " (shape depends on the player) at " + x + " " + y + " " + z);
        }
        if (states.has(state, BlockStates.OFFSET) && !shape.isEmpty()) {
            throw new Unknown(states.block(state) + " (offset shape) at " + x + " " + y + " " + z);
        }
        return shape;
    }

    private static boolean isLarge(Shape shape) {
        if (shape.isEmpty()) {
            return false;
        }
        Voxels v = Voxels.of(shape);
        for (Axis axis : Axis.values()) {
            if (v.min(axis) < 0.0 || v.max(axis) > 1.0) {
                return true;
            }
        }
        return false;
    }

    /** {@code Mth.floor(double)}. */
    static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
