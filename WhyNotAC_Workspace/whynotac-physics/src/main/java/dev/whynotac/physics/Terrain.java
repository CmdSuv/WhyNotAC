package dev.whynotac.physics;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.List;

/**
 * What {@code Entity} reads of the client's blocks and fluids, alike for every entity that moves itself ({@link OnFoot},
 * {@link Boat}): the fluids' heights and currents, the fall's ray, the block below that slows a move, and the sweep of
 * the blocks a tick's moves passed through ({@code checkInsideBlocks}). Where the client's world is not known,
 * {@link Colliders.Unknown} is thrown.
 */
final class Terrain {

    private Terrain() {}

    static int state(Colliders.World world, BlockPos pos) throws Colliders.Unknown {
        int s = world.blockState(pos.x(), pos.y(), pos.z());
        if (s == dev.whynotac.world.ClientWorld.UNKNOWN) {
            throw new Colliders.Unknown("unknown block at " + pos);
        }
        return s;
    }

    /** A block state's property value ({@code key=value,...}), or empty. */
    static String property(BlockStates states, int state, String key) {
        for (String kv : states.properties(state).split(",")) {
            if (kv.startsWith(key + "=")) {
                return kv.substring(key.length() + 1);
            }
        }
        return "";
    }

    /** {@code FluidState.isSource}: a still fluid, not one flowing or falling ({@code minecraft:water}, not {@code flowing_water}). */
    static boolean fluidSource(BlockStates states, int s) {
        String fluid = states.fluid(s);
        return fluid != null && (fluid.startsWith("minecraft:water/") || fluid.startsWith("minecraft:lava/"));
    }

    /** {@code FlowingFluid.getHeight}: a full block under fluid of its own kind, else its own height. */
    static float fluidHeight(Colliders.World world, BlockPos p, int s, byte kind) throws Colliders.Unknown {
        return world.states().fluidKind(state(world, p.atY(p.y() + 1))) == kind ? 1.0F : ownHeight(world.states(), s);
    }

    /** {@code FluidState.getOwnHeight}: {@code amount / 9}, 0 for no fluid. */
    static float ownHeight(BlockStates states, int s) {
        return states.fluidAmount(s) / 9.0F;
    }

    /** {@code Direction.Plane.HORIZONTAL}: north, east, south, west, as x and z steps. */
    private static final int[][] HORIZONTAL = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * {@code FlowingFluid.getFlow}: toward lower neighbours (through a passable neighbour to the fluid of its kind below
     * it), and for a falling fluid next to a sturdy face (or one above it) mostly down.
     */
    static Vec3d flow(Colliders.World world, BlockPos pos, int s, byte kind) throws Colliders.Unknown {
        BlockStates states = world.states();
        float own = ownHeight(states, s);
        double fx = 0.0;
        double fz = 0.0;
        for (int[] d : HORIZONTAL) {
            BlockPos n = new BlockPos(pos.x() + d[0], pos.y(), pos.z() + d[1]);
            int ns = state(world, n);
            if (!affectsFlow(states, ns, kind)) {
                continue;
            }
            float f = ownHeight(states, ns);
            float g = 0.0F;
            if (f == 0.0F) {
                if (!states.has(ns, BlockStates.BLOCKS_MOTION)) {
                    int below = state(world, n.atY(n.y() - 1));
                    if (affectsFlow(states, below, kind)) {
                        f = ownHeight(states, below);
                        if (f > 0.0F) {
                            g = own - (f - 0.8888889F);
                        }
                    }
                }
            } else if (f > 0.0F) {
                g = own - f;
            }
            if (g != 0.0F) {
                fx += d[0] * g;
                fz += d[1] * g;
            }
        }
        Vec3d flow = new Vec3d(fx, 0.0, fz);
        if (states.fluidFalling(s)) {
            for (int[] d : HORIZONTAL) {
                BlockPos n = new BlockPos(pos.x() + d[0], pos.y(), pos.z() + d[1]);
                if (solidFace(world, n, d, kind) || solidFace(world, n.atY(n.y() + 1), d, kind)) {
                    flow = flow.normalize().add(0.0, -6.0, 0.0);
                    break;
                }
            }
        }
        return flow.normalize();
    }

    /** {@code FlowingFluid.affectsFlow}: no fluid, or the same kind ({@code isSame}). */
    private static boolean affectsFlow(BlockStates states, int s, byte kind) {
        byte k = states.fluidKind(s);
        return k == BlockStates.FLUID_NONE || k == kind;
    }

    /** {@code FlowingFluid.isSolidFace} for a horizontal side: not the same fluid, not ice, and the face sturdy. */
    private static boolean solidFace(Colliders.World world, BlockPos n, int[] d, byte kind) throws Colliders.Unknown {
        BlockStates states = world.states();
        int s = state(world, n);
        if (states.fluidKind(s) == kind) {
            return false;
        }
        String block = states.block(s);
        if (block.equals("minecraft:ice") || block.equals("minecraft:frosted_ice")) { // IceBlock
            return false;
        }
        if (states.has(s, BlockStates.DYNAMIC_SHAPE)) {
            throw new Colliders.Unknown(block + " beside a falling fluid (its faces depend on the world)");
        }
        int flag = d[1] < 0 ? BlockStates.STURDY_NORTH : d[0] > 0 ? BlockStates.STURDY_EAST : d[1] > 0 ? BlockStates.STURDY_SOUTH : BlockStates.STURDY_WEST;
        return states.has(s, flag);
    }

    /** What {@link #fluidPushing} found: the box touches the fluid, its height above the box's bottom, the motion after the push. */
    record FluidPush(boolean touching, double height, Vec3d delta) {}

    /**
     * {@code Entity.updateFluidHeightAndDoFluidPushing(tag, scale)} for the box {@code full} of an entity pushed by
     * fluids: over the cells of the box deflated by 0.001, the fluid's height above the box's bottom and the mean of the
     * flows of the cells it reaches (scaled by that height below 0.4), normalised but for a player, times {@code scale}
     * (NaN: not known, which only a push needs). A chunk the client may not hold near the box is not known (the client
     * then skips the fluid altogether).
     */
    static FluidPush fluidPushing(Colliders.World world, Box full, byte kind, double scale, Vec3d delta, boolean player) throws Colliders.Unknown {
        BlockStates states = world.states();
        Box near = full.inflate(1.0, 1.0, 1.0); // touchingUnloadedChunk
        for (int cx = Colliders.floor(near.minX()) >> 4; cx <= VanillaMath.ceil(near.maxX()) >> 4; cx++) {
            for (int cz = Colliders.floor(near.minZ()) >> 4; cz <= VanillaMath.ceil(near.maxZ()) >> 4; cz++) {
                if (!world.isLoaded(cx, cz)) {
                    throw new Colliders.Unknown("water next to a chunk the client may not hold");
                }
            }
        }
        Box box = full.inflate(-0.001, -0.001, -0.001);
        double height = 0.0;
        boolean touching = false;
        Vec3d push = Vec3d.ZERO;
        int count = 0;
        for (int bx = Colliders.floor(box.minX()); bx < VanillaMath.ceil(box.maxX()); bx++) {
            for (int by = Colliders.floor(box.minY()); by < VanillaMath.ceil(box.maxY()); by++) {
                for (int bz = Colliders.floor(box.minZ()); bz < VanillaMath.ceil(box.maxZ()); bz++) {
                    BlockPos p = new BlockPos(bx, by, bz);
                    int s = state(world, p);
                    if (states.fluidKind(s) != kind) {
                        continue;
                    }
                    double top = by + fluidHeight(world, p, s, kind); // int + float, in float
                    if (top >= box.minY()) {
                        touching = true;
                        height = Math.max(top - box.minY(), height);
                        Vec3d flow = flow(world, p, s, kind);
                        if (height < 0.4) {
                            flow = flow.scale(height);
                        }
                        push = push.add(flow);
                        count++;
                    }
                }
            }
        }
        if (push.length() > 0.0) {
            if (count > 0) {
                push = push.scale(1.0 / count);
            }
            if (!player) {
                push = push.normalize();
            }
            if (Double.isNaN(scale)) {
                throw new Colliders.Unknown("lava's current in a level whose lava speed is not known");
            }
            push = push.scale(scale);
            if (Math.abs(delta.x()) < 0.003 && Math.abs(delta.z()) < 0.003 && push.length() < 0.0045000000000000005) {
                push = push.normalize().scale(0.0045000000000000005);
            }
            delta = delta.add(push);
        }
        return new FluidPush(touching, height, delta);
    }

    /** The most cells a move's ray is followed through ({@link #mayResetTheFall}); beyond, it is not followed. */
    private static final int MAX_RAY_CELLS = 512;

    /**
     * {@code Entity.move}: a move of a block or more while falling casts a ray from the feet along it, at most 8 long
     * ({@code Level.clip} with {@code ClipContext.Block.FALLDAMAGE_RESETTING}, {@code ClipContext.Fluid.WATER}), and the
     * fall resets where it meets a block of {@link OnFoot#FALL_DAMAGE_RESETTING} (its shape a full block) or water. Where it
     * meets them is not modelled: false only if no cell the move's ray can pass through ({@code traverseBlocks}: within
     * the move's bounds) holds either.
     */
    static boolean mayResetTheFall(Colliders.World world, double x, double y, double z, Vec3d move) throws Colliders.Unknown {
        BlockStates states = world.states();
        double margin = 1.0E-6; // traverseBlocks starts and ends 1.0E-7 beyond the segment's ends
        int fromX = (int) Math.floor(Math.min(x, x + move.x()) - margin);
        int fromY = (int) Math.floor(Math.min(y, y + move.y()) - margin);
        int fromZ = (int) Math.floor(Math.min(z, z + move.z()) - margin);
        int toX = (int) Math.floor(Math.max(x, x + move.x()) + margin);
        int toY = (int) Math.floor(Math.max(y, y + move.y()) + margin);
        int toZ = (int) Math.floor(Math.max(z, z + move.z()) + margin);
        if ((long) (toX - fromX + 1) * (toY - fromY + 1) * (toZ - fromZ + 1) > MAX_RAY_CELLS) {
            return true;
        }
        for (int bx = fromX; bx <= toX; bx++) {
            for (int bz = fromZ; bz <= toZ; bz++) {
                if (!world.isLoaded(bx >> 4, bz >> 4)) {
                    return true; // the client's ray may meet what it holds there
                }
                for (int by = fromY; by <= toY; by++) {
                    int s = state(world, new BlockPos(bx, by, bz));
                    if (OnFoot.FALL_DAMAGE_RESETTING.contains(states.block(s)) || states.fluidKind(s) == BlockStates.FLUID_WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code Entity.getOnPos(offset)}: the supporting block's column at the offset below the position; the supporting
     * block itself where it is a fence and the offset at most 0.5, a wall or a fence gate ({@code BlockTags.FENCES},
     * {@code BlockTags.WALLS}, {@code FenceGateBlock}: in 1.21.11 exactly the blocks named so).
     */
    static BlockPos onPos(Colliders.World world, BlockPos support, double x, double y, double z, float offset) throws Colliders.Unknown {
        if (support != null) {
            if (!(offset > 1.0E-5F)) {
                return support;
            }
            String block = world.states().block(state(world, support));
            boolean fence = block.endsWith("_fence");
            boolean wallOrGate = block.endsWith("_wall") || block.endsWith("_fence_gate");
            return (!((double) offset <= 0.5) || !fence) && !wallOrGate ? support.atY(Colliders.floor(y - offset)) : support;
        }
        return new BlockPos(Colliders.floor(x), Colliders.floor(y - offset), Colliders.floor(z));
    }

    /** {@code Entity.getBlockSpeedFactor}: the block at the position, else (but in water) the one below that affects movement. */
    static float blockSpeedFactor(Colliders.World world, BlockPos support, double x, double y, double z) throws Colliders.Unknown {
        BlockStates states = world.states();
        int feet = state(world, BlockPos.containing(x, y, z));
        float factor = states.speedFactor(feet);
        String block = states.block(feet);
        if (!block.equals("minecraft:water") && !block.equals("minecraft:bubble_column")) {
            return factor == 1.0 ? states.speedFactor(state(world, onPos(world, support, x, y, z, 0.500001F))) : factor;
        }
        return factor;
    }

    /** {@code Entity.Movement}: a move's start and end, and the movement asked for (null: none, a plain sweep). */
    record Movement(Vec3d from, Vec3d to, Vec3d original) {}

    /**
     * {@code Entity.applyEffectsFromBlocks()}'s list ({@code finalMovementsThisTick}): the tick's moves, or the way from
     * where the tick started when it made none, and the way on to where the entity is when it moved since its last move.
     */
    static List<Movement> finalMovements(List<Movement> movements, Vec3d oldPosition, Vec3d position) {
        java.util.ArrayList<Movement> moves = new java.util.ArrayList<>(movements);
        if (moves.isEmpty()) {
            moves.add(new Movement(oldPosition, position, null));
        } else if (moves.getLast().to().distanceToSqr(position) > 9.9999994E-11F) {
            moves.add(new Movement(moves.getLast().to(), position, null));
        }
        return moves;
    }

    /** The entity's box at a position ({@code makeBoundingBox}). */
    @FunctionalInterface
    interface BoxAt {
        Box at(double x, double y, double z);
    }

    /** The blocks a sweep meets from inside, for the entity sweeping. */
    interface Inside {
        /**
         * The block acts on the entity and the sweep from {@code from} to {@code to} meets it: its inside shape
         * ({@code getEntityInsideCollisionShape}) is {@code Shapes.block()}, or the move collided with it.
         */
        boolean meets(int state, BlockPos pos, Vec3d from, Vec3d to) throws Colliders.Unknown;

        /** Its {@code entityInside}; {@code intersects}: the box meets the cell, or the sweep was a block or longer. */
        void inside(int state, BlockPos pos, boolean intersects) throws Colliders.Unknown;
    }

    /**
     * {@code Entity.checkInsideBlocks(List<Movement>)}: each move swept in at most 16 steps, a move asked for split per
     * axis ({@code Direction.axisStepOrder}: y, then x and z, the larger last) and, the steps spent, its end once more;
     * a block acts once a sweep of the list, in the order the sweeps meet it.
     */
    static void checkInsideBlocks(Colliders.World world, List<Movement> moves, BoxAt box, Inside inside) throws Colliders.Unknown {
        java.util.Set<BlockPos> visited = new java.util.HashSet<>();
        for (Movement m : moves) {
            Vec3d from = m.from();
            Vec3d travel = m.to().subtract(m.from());
            int budget = 16;
            if (m.original() != null && travel.lengthSqr() > 0.0) {
                boolean zBeforeX = Math.abs(m.original().x()) < Math.abs(m.original().z()); // YZX, else YXZ
                Axis[] order = zBeforeX ? new Axis[] {Axis.Y, Axis.Z, Axis.X} : new Axis[] {Axis.Y, Axis.X, Axis.Z};
                for (Axis axis : order) {
                    double d = axis.choose(travel.x(), travel.y(), travel.z());
                    if (d != 0.0) {
                        // Vec3.relative(axis.getPositive(), d)
                        Vec3d next = new Vec3d(from.x() + d * (axis == Axis.X ? 1 : 0), from.y() + d * (axis == Axis.Y ? 1 : 0),
                                from.z() + d * (axis == Axis.Z ? 1 : 0));
                        budget -= checkInsideBlocks(world, from, next, budget, visited, box, inside);
                        from = next;
                    }
                }
            } else {
                budget -= checkInsideBlocks(world, m.from(), m.to(), 16, visited, box, inside);
            }
            if (budget <= 0) {
                checkInsideBlocks(world, m.to(), m.to(), 1, visited, box, inside);
            }
        }
    }

    /**
     * One sweep of {@code checkInsideBlocks}: the box at {@code to}, deflated by {@code 1.0E-5F}, through the cells from
     * {@code from} ({@link InsideBlocks}), stopping at the step budget; the steps it took.
     */
    private static int checkInsideBlocks(Colliders.World world, Vec3d from, Vec3d to, int budget, java.util.Set<BlockPos> visited, BoxAt boxAt,
            Inside inside) throws Colliders.Unknown {
        BlockStates states = world.states();
        Box box = boxAt.at(to.x(), to.y(), to.z()).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F);
        boolean far = from.distanceToSqr(to) > 0.9999900000002526 * 0.9999900000002526;
        int[] last = {0};
        InsideBlocks.forEachBlockIntersectedBetween(from, to, box, (bx, by, bz, step) -> {
            if (step >= budget) {
                return false;
            }
            last[0] = step;
            BlockPos pos = new BlockPos(bx, by, bz);
            int s = state(world, pos);
            if (!states.has(s, BlockStates.AIR) && inside.meets(s, pos, from, to) && visited.add(pos)) {
                inside.inside(s, pos, far || box.intersects(bx, by, bz, bx + 1, by + 1, bz + 1));
            }
            return true;
        });
        return last[0] + 1;
    }
}
