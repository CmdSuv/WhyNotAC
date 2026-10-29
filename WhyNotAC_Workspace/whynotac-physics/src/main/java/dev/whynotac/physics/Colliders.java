package dev.whynotac.physics;

import dev.whynotac.math.vector.Vec3d;
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

        /** {@code level.hasChunkAt}: the client holds this chunk. */
        boolean isLoaded(int chunkX, int chunkZ);

        /**
         * The level's {@code gameplay/fast_lava} ({@code environmentAttributes().getDimensionValue}): lava's current pushes
         * 0.007 rather than 0.0023333333333333335. Null when not known.
         */
        default Boolean fastLava() {
            return null;
        }
    }

    /**
     * {@code EntityCollisionContext} for the player, as far as block shapes read it: the feet's height when the context
     * was made ({@code getY()}) and whether the player descends ({@code isDescending}: the shift key). Null: not known.
     * For the world border ({@code isInsideCloseToBorder(entity, box)}): where the player is ({@code getX()},
     * {@code getZ()}) and the client's border; a null border is far. For powder snow ({@code PowderSnowBlock}, which reads
     * the entity itself): its fall distance (NaN: not known) and whether it wears leather boots (null: not known). For a
     * liquid ({@code LiquidBlock}): whether the entity stands on lava ({@code canStandOnFluid}: a strider), never a player.
     */
    public record Context(double entityBottom, boolean descending, double entityX, double entityZ, Border border, double fallDistance,
            Boolean leatherBoots, boolean standsOnLava) {
        public Context(double entityBottom, boolean descending) {
            this(entityBottom, descending, 0.0, 0.0, null, Double.NaN, null);
        }

        public Context(double entityBottom, boolean descending, double entityX, double entityZ, Border border, double fallDistance,
                Boolean leatherBoots) {
            this(entityBottom, descending, entityX, entityZ, border, fallDistance, leatherBoots, false);
        }

        /** {@code borderCollision(player, box)}: the border's shape when the player is close to it, else null. */
        Voxels border(Box box) {
            return border != null && border.closeTo(entityX, entityZ, box) ? border.shape() : null;
        }
    }

    /** A collision shape the client has that WhyNotAC cannot reproduce. */
    public static final class Unknown extends Exception {
        public Unknown(String reason) {
            super(reason, null, false, false);
        }
    }

    private Colliders() {}

    /** {@code Entity.collectColliders(player, level, entityColliders, box)}: the entities, the border when close, the blocks. */
    public static List<Voxels> collect(World world, Context context, List<Voxels> entityColliders, Box box) throws Unknown {
        List<Voxels> colliders = new ArrayList<>(entityColliders.size() + 8);
        colliders.addAll(entityColliders);
        Voxels border = context == null ? null : context.border(box);
        if (border != null) {
            colliders.add(border);
        }
        blocks(world, context, box, colliders);
        return colliders;
    }

    /**
     * {@code CollisionGetter.noCollision(player, box)} with no solid entity near: no block shape in the box, and the
     * border's shape, when the player is close to it, not overlapping the box ({@code noBorderCollision}).
     */
    public static boolean noCollision(World world, Context context, Box box) throws Unknown {
        List<Voxels> shapes = new ArrayList<>();
        blocks(world, context, box, shapes, null);
        if (!shapes.isEmpty()) {
            return false;
        }
        Voxels border = context == null ? null : context.border(box);
        return border == null || !VoxelCollisions.overlaps(border, VoxelCollisions.create(box));
    }

    /**
     * {@code CollisionGetter.collidesWithSuffocatingBlock(player, box)}: {@code BlockCollisions} keeping only the
     * blocks that suffocate ({@link BlockStates#SUFFOCATING}). A block whose shape depends on the world is not known.
     */
    public static boolean collidesWithSuffocatingBlock(World world, Box box) throws Unknown {
        List<Voxels> shapes = new ArrayList<>();
        blocks(world, null, box, shapes, null, true);
        return !shapes.isEmpty();
    }

    /**
     * {@code CollisionGetter.findSupportingBlock(player, box)}: of the blocks colliding with the box, the one
     * whose centre is nearest to {@code (px, py, pz)}; on a tie the greatest by {@code Vec3i.compareTo}
     * (y, then z, then x). Null if none.
     */
    public static BlockPos supportingBlock(World world, Context context, Box box, double px, double py, double pz) throws Unknown {
        List<BlockPos> positions = new ArrayList<>();
        blocks(world, context, box, new ArrayList<>(), positions);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : positions) {
            double dx = pos.x() + 0.5 - px;
            double dy = pos.y() + 0.5 - py;
            double dz = pos.z() + 0.5 - pz;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance || distance == bestDistance && (best == null || best.compareTo(pos) < 0)) {
                best = pos;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** {@code Level.getBlockCollisions(player, box)}: {@code BlockCollisions}, cell by cell in {@code Cursor3D} order. */
    static void blocks(World world, Context context, Box box, List<Voxels> out) throws Unknown {
        blocks(world, context, box, out, null);
    }

    /** As {@link #blocks(World, Context, Box, List)}, also collecting each shape's block position when {@code positions} is not null. */
    static void blocks(World world, Context context, Box box, List<Voxels> out, List<BlockPos> positions) throws Unknown {
        blocks(world, context, box, out, positions, false);
    }

    private static void blocks(World world, Context context, Box box, List<Voxels> out, List<BlockPos> positions, boolean onlySuffocating)
            throws Unknown {
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
                    if (onlySuffocating && states.block(state).equals(POWDER_SNOW)) {
                        continue; // isSuffocating: its empty-context shape is empty
                    }
                    if (onlySuffocating) {
                        if (dynamic && !states.block(state).equals(SCAFFOLDING)) { // scaffolding's empty-context shape never is a full block
                            throw new Unknown(states.block(state) + " (whether it suffocates depends on the world) at " + x + " " + y + " " + z);
                        }
                        if (!states.has(state, BlockStates.SUFFOCATING)) {
                            continue;
                        }
                    }
                    // hasLargeCollisionShape(): true without a shape cache (dynamic shapes), else beyond the unit cube
                    if (type == 1 && !(dynamic || isLarge(shape))) {
                        continue;
                    }
                    if (type == 2 && !states.block(state).equals("minecraft:moving_piston")) {
                        continue;
                    }
                    if (states.block(state).equals(POWDER_SNOW) && !box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0)) {
                        continue; // each shape it may have (the full block, the falling slab, none) is within its cell: whatever decides it
                    }
                    shape = context != null && context.standsOnLava() && states.block(state).equals(LAVA) ? standingOnLava(world, states, state, context, x, y, z)
                            : playerShape(states, state, shape, context, x, y, z);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    boolean collides;
                    Voxels moved = Voxels.moved(shape, x, y, z);
                    // BlockCollisions: Shapes.block() itself (a full block's table shape, powder snow's own) meets the box as a box
                    if (states.has(state, BlockStates.FULL_BLOCK) || shape == FULL_BLOCK) {
                        collides = box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                    } else {
                        if (entityShape == null) {
                            entityShape = VoxelCollisions.create(box);
                        }
                        collides = VoxelCollisions.overlaps(moved, entityShape);
                    }
                    if (collides) {
                        out.add(moved);
                        if (positions != null) {
                            positions.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /** {@code state.getCollisionShape(level, pos, CollisionContext.of(player))}: the table's shape where it is the client's. */
    public static Shape playerShape(BlockStates states, int state, Context context, int x, int y, int z) throws Unknown {
        return playerShape(states, state, states.collisionShape(state), context, x, y, z);
    }

    static final String SCAFFOLDING = "minecraft:scaffolding";

    /** {@code EntityCollisionContext.getCollisionShape} for a player: the table's shape where it is the client's. */
    private static Shape playerShape(BlockStates states, int state, Shape shape, Context context, int x, int y, int z) throws Unknown {
        if (states.block(state).equals(SCAFFOLDING)) {
            return scaffolding(states, state, shape, context, x, y, z);
        }
        if (states.block(state).equals(POWDER_SNOW)) {
            return powderSnow(context, x, y, z);
        }
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

    /**
     * {@code ScaffoldingBlock.getCollisionShape}: above the block's top and not descending, its stable shape (the table's:
     * the empty context counts as above); else, for an unsupported bottom block ({@code distance} not 0, {@code bottom})
     * with the feet above its floor, the floor's slab ({@code Block.column(16, 0, 2)}, built as a two-layer snow block's
     * collision is); else nothing. {@code isAbove}: {@code entityBottom > y + shape.max(Y) - 1.0E-5F}.
     */
    private static Shape scaffolding(BlockStates states, int state, Shape stable, Context context, int x, int y, int z) throws Unknown {
        if (context == null) {
            throw new Unknown("scaffolding (its shape depends on the player) at " + x + " " + y + " " + z);
        }
        if (context.entityBottom() > y + 1.0 - 1.0E-5F && !context.descending()) {
            return stable;
        }
        String p = states.properties(state);
        if (!p.contains("distance=0") && p.contains("bottom=true") && context.entityBottom() > y + 0.0 - 1.0E-5F) {
            return unstableBottom(states);
        }
        return Shape.EMPTY;
    }

    static final String LAVA = "minecraft:lava";
    /** {@code LiquidBlock.SHAPE_STABLE}: {@code Block.column(16, 0, 8)}. */
    static final Shape LAVA_STABLE = VoxelCollisions.create(new Box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)).shape();

    /**
     * {@code LiquidBlock.getCollisionShape} for an entity that stands on lava: above the stable shape
     * ({@code isAbove(SHAPE_STABLE, pos, true)}), a source ({@code LEVEL} 0) with no lava above it
     * ({@code EntityCollisionContext.canStandOnFluid}: the fluid above not the same), the stable shape; else nothing.
     */
    private static Shape standingOnLava(World world, BlockStates states, int state, Context context, int x, int y, int z) throws Unknown {
        if (!(context.entityBottom() > y + 0.5 - 1.0E-5F) || !Terrain.property(states, state, "level").equals("0")) {
            return Shape.EMPTY;
        }
        int above = world.blockState(x, y + 1, z);
        if (above == ClientWorld.UNKNOWN) {
            throw new Unknown("unknown block over lava at " + x + " " + (y + 1) + " " + z);
        }
        return states.fluidKind(above) == BlockStates.FLUID_LAVA ? Shape.EMPTY : LAVA_STABLE;
    }

    static final String POWDER_SNOW = "minecraft:powder_snow";
    /** {@code PowderSnowBlock.FALLING_COLLISION_SHAPE}: {@code Shapes.box(0, 0, 0, 1, 0.9F, 1)}. */
    static final Shape POWDER_SNOW_FALLING = VoxelCollisions.create(new Box(0.0, 0.0, 0.0, 1.0, 0.9F, 1.0)).shape();
    /**
     * Whether {@code checkInsideBlocks} meets the powder snow at (x, y, z) for a move of the box {@code from} by
     * {@code move}: its inside shape ({@code getEntityInsideCollisionShape}: its shape for the entity, {@code Shapes.block()}
     * where that is empty) is {@code Shapes.block()} itself, or the move meets the falling slab
     * ({@code collidedWithShapeMovingFrom} with {@code FALLING_COLLISION_SHAPE.move(pos).toAabbs()}).
     */
    public static boolean powderSnowEntered(Context context, Box from, Vec3d move, int x, int y, int z) throws Unknown {
        if (powderSnow(context, x, y, z) != POWDER_SNOW_FALLING) {
            return true;
        }
        return InsideBlocks.collidedAlongVector(from, move, List.of(Voxels.moved(POWDER_SNOW_FALLING, x, y, z).box(0)));
    }

    /** {@code Shapes.block()}: what the block's own shape is. */
    static final Shape FULL_BLOCK = VoxelCollisions.create(new Box(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)).shape();

    /**
     * {@code PowderSnowBlock.getCollisionShape} for the player: falling more than 2.5, a slab 0.9 high; else, wearing
     * leather boots ({@code canEntityWalkOnPowderSnow}: the player is no {@code powder_snow_walkable_mobs}) above its top
     * ({@code isAbove(Shapes.block(), pos, false)}) and not descending, the full block; else nothing.
     */
    static Shape powderSnow(Context context, int x, int y, int z) throws Unknown {
        if (context == null || Double.isNaN(context.fallDistance())) {
            throw new Unknown("powder snow (its shape depends on the player's fall) at " + x + " " + y + " " + z);
        }
        if (context.fallDistance() > 2.5) {
            return POWDER_SNOW_FALLING;
        }
        if (context.descending() || !(context.entityBottom() > y + 1.0 - 1.0E-5F)) {
            return Shape.EMPTY;
        }
        if (context.leatherBoots() == null) {
            throw new Unknown("powder snow under a player whose boots are not known at " + x + " " + y + " " + z);
        }
        return context.leatherBoots() ? FULL_BLOCK : Shape.EMPTY;
    }

    /** {@code ScaffoldingBlock.SHAPE_UNSTABLE_BOTTOM}: {@code SnowLayerBlock.SHAPES[1]} is the very same call. */
    private static Shape unstableBottom(BlockStates states) {
        for (int s = states.firstStateOf("minecraft:snow"); s >= 0 && s < states.size() && states.block(s).equals("minecraft:snow"); s++) {
            if (states.properties(s).equals("layers=2")) {
                return states.collisionShape(s);
            }
        }
        throw new IllegalStateException("no two-layer snow in the block data");
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
