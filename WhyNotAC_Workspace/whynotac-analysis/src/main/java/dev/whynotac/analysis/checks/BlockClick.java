package dev.whynotac.analysis.checks;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Shape;

/**
 * Whether a block click ({@code ServerboundUseItemOnPacket}'s hit) can be the vanilla pick's, along one look from one
 * pose's eye.
 *
 * <p>Decompiled 1.21.11: without an {@code attack_range} item the hit is {@code LocalPlayer.pick}'s
 * {@code Entity.pick}: {@code Level.clip} from the eye along {@code getViewVector(1) * d}, {@code d} the larger of
 * {@code block_interaction_range} and {@code entity_interaction_range}, with the {@code OUTLINE} shapes; kept only if
 * its location is nearer than {@code block_interaction_range} ({@code filterHitResult}). The clip stops at the first
 * cell whose shape the segment meets ({@code VoxelShape.clip}): either the point a thousandth of the segment from the
 * eye is inside the shape (the location is that point, the face {@code getApproximateNearest(delta).getOpposite()},
 * {@code inside}), or {@code AABB.clip} of the shape's boxes: the location where the segment enters a box from outside,
 * on that box's face, the face the segment crosses inwards (tested only for a delta above 10⁻⁷ along its axis; the
 * other two coordinates within 10⁻⁷ of the box). The packet carries the location as float offsets from the block.
 *
 * <p>So the click is the pick's only if: the location is on the look's line from the eye, ahead of it ({@link Stage#LOOK});
 * the face is the one the look enters through ({@link Stage#FACE}); the location is on that face of a box of an
 * outline the client may hold there ({@link Stage#CURSOR}; never claimed where its outline is not
 * {@link BlockStates#outlineExact} or not known); it is in reach ({@link Stage#REACH}); and no block certainly stops
 * the look before it ({@link Stage#BLOCKS}, {@link ClientWorld#certainOutlineHitBefore}).
 */
final class BlockClick {

    /** Where the location may be off the client's (its float offsets, the client's own arithmetic). */
    static final double CURSOR = 1.0E-6;
    /** {@code AABB.clipPoint}'s tolerance on a face, and the smallest delta along an axis it tests. */
    static final double CLIP_TOLERANCE = 1.0E-7;
    /** {@code VoxelShape.clip}: the point at this fraction of the segment. */
    static final double INSIDE_FRACTION = 0.001;

    /** The tests in order; a click fails at the first it does not pass. */
    enum Stage { LOOK, FACE, CURSOR, REACH, BLOCKS }

    /** Why the click is not the pick's along this look from this eye: the stage, and for reach and blocks how far. */
    record Failure(Stage stage, double distance, double gap, int wallX, int wallY, int wallZ) {

        static Failure at(Stage stage) {
            return new Failure(stage, Double.NaN, Double.NaN, 0, 0, 0);
        }
    }

    private BlockClick() {}

    /**
     * Null if the click may be the pick's along {@code v} from an eye of {@code eye} (its box widened by
     * {@link AttackAim#EPSILON}); else the first stage it fails.
     *
     * @param world the client's world, or null where it is not known (no claim from the outline or from blocks)
     * @param blockHigh the largest {@code block_interaction_range} the client may have
     * @param pickLow the smallest pick length {@code d} the client may have
     * @param pickHigh the largest
     * @param before the client's first block action of the tick: its pick saw the world before it
     */
    static Failure judge(ClientWorld world, BlockPosition pos, BlockFace face, Vec3d location, boolean inside, double[] v, AttackReach.Eye eye,
            double blockHigh, double pickLow, double pickHigh, int before) {
        double r = eye.radius() + AttackAim.EPSILON;
        double[] lo = {eye.x() - r, eye.y0() - r, eye.z() - r};
        double[] hi = {eye.x() + r, eye.y1() + r, eye.z() + r};
        double[] at = {location.x(), location.y(), location.z()};
        // LOOK: the location minus s times the look is an eye, for s ahead (for inside, at the segment's thousandth; how far
        // along it the rest may be is REACH's, the block range being the shorter of the segment's two)
        double sLow = inside ? INSIDE_FRACTION * pickLow * (1 - 1e-9) : 0.0;
        double sHigh = inside ? INSIDE_FRACTION * pickHigh * (1 + 1e-9) : Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (v[i] == 0.0) {
                if (at[i] < lo[i] - CURSOR || at[i] > hi[i] + CURSOR) {
                    return Failure.at(Stage.LOOK);
                }
                continue;
            }
            double a = (at[i] - hi[i] - CURSOR) / v[i];
            double b = (at[i] - lo[i] + CURSOR) / v[i];
            sLow = Math.max(sLow, Math.min(a, b));
            sHigh = Math.min(sHigh, Math.max(a, b));
        }
        if (!(sLow <= sHigh) || !inside && sHigh <= 0.0) {
            return Failure.at(Stage.LOOK);
        }
        // FACE
        if (inside ? !nearestOpposite(v, face) : !entersThrough(v, face, pickHigh)) {
            return Failure.at(Stage.FACE);
        }
        // CURSOR
        if (world != null && !onOutline(world, pos, face, at, inside, before)) {
            return Failure.at(Stage.CURSOR);
        }
        if (inside) {
            return null; // a thousandth of the segment: in reach, nothing between
        }
        // REACH: filterHitResult keeps it only nearer than block_interaction_range
        double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double nearest = Math.max(sLow, 0.0) * norm;
        if (nearest >= blockHigh + CURSOR) {
            return new Failure(Stage.REACH, nearest, Double.NaN, 0, 0, 0);
        }
        // BLOCKS: every ray from the eyes is stopped before the nearest place the location can be along it
        if (world != null && sLow > 0.0) {
            double stop = world.certainOutlineHitBefore(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], v[0], v[1], v[2], sLow, before);
            if (stop < sLow) {
                double y = (lo[1] + hi[1]) / 2.0;
                return new Failure(Stage.BLOCKS, nearest, (sLow - stop) * norm, (int) Math.floor((lo[0] + hi[0]) / 2.0 + v[0] * stop),
                        (int) Math.floor(y + v[1] * stop), (int) Math.floor((lo[2] + hi[2]) / 2.0 + v[2] * stop));
            }
        }
        return null;
    }

    /**
     * Null if a dig ({@code START_DESTROY_BLOCK} or {@code STOP_DESTROY_BLOCK}: the block and face of the tick's pick,
     * {@code MultiPlayerGameMode.startDestroyBlock}/{@code continueDestroyBlock}) may be the pick's along {@code v} from an
     * eye of {@code eye}; else the first stage it fails ({@link Stage#CURSOR}: no outline the client may hold there). The
     * hit is either the point a thousandth of the segment ahead, inside the block's outline, with the face opposite the
     * look's nearest axis, or where a ray from an eye enters a box of the outline through that face, from outside, in reach
     * and before any block certainly stops every ray. Nothing is claimed where the outline is not known exactly.
     */
    static Failure judgeDig(ClientWorld world, BlockPosition pos, BlockFace face, double[] v, AttackReach.Eye eye, double blockHigh,
            double pickLow, double pickHigh, int before) {
        if (world == null) {
            return null;
        }
        int[] possible = world.possibleStatesBefore(pos.x(), pos.y(), pos.z(), before);
        BlockStates states = world.states();
        if (possible == null || world.hasUnlocatedPredictionBefore(before)) {
            return null;
        }
        for (int state : possible) {
            if (!states.isValid(state) || !states.outlineExact(state)) {
                return null;
            }
        }
        double r = eye.radius() + AttackAim.EPSILON;
        double[] lo = {eye.x() - r, eye.y0() - r, eye.z() - r};
        double[] hi = {eye.x() + r, eye.y1() + r, eye.z() + r};
        double[] origin = {pos.x(), pos.y(), pos.z()};
        boolean anyBox = false;
        boolean met = false;
        double entry = Double.POSITIVE_INFINITY; // the nearest s at which a ray from an eye enters a box through the face
        boolean nearest = nearestOpposite(v, face);
        for (int state : possible) {
            Shape outline = states.outlineShape(state);
            for (int b = 0; b < outline.boxCount(); b++) {
                anyBox = true;
                double[] min = new double[3];
                double[] max = new double[3];
                for (int i = 0; i < 3; i++) {
                    min[i] = origin[i] + outline.box(b, i);
                    max[i] = origin[i] + outline.box(b, i + 3);
                }
                // every offset from an eye to the box, widened by the clip's tolerance
                AttackReach.Box offsets = new AttackReach.Box(min[0] - hi[0] - CURSOR, min[1] - hi[1] - CURSOR, min[2] - hi[2] - CURSOR,
                        max[0] - lo[0] + CURSOR, max[1] - lo[1] + CURSOR, max[2] - lo[2] + CURSOR);
                if (nearest && meetsWithin(v, offsets, INSIDE_FRACTION * pickLow * (1 - 1e-9), INSIDE_FRACTION * pickHigh * (1 + 1e-9))) {
                    return null; // the inside hit: a thousandth of the segment, in the outline
                }
                met |= AttackAim.meets(v, offsets);
                entry = Math.min(entry, faceEntry(v, face, min, max, lo, hi, pickHigh));
            }
        }
        if (!anyBox) {
            return Failure.at(Stage.CURSOR);
        }
        if (!met) {
            return Failure.at(Stage.LOOK);
        }
        if (entry == Double.POSITIVE_INFINITY) {
            return Failure.at(Stage.FACE);
        }
        double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (entry * norm >= blockHigh + CURSOR) {
            return new Failure(Stage.REACH, entry * norm, Double.NaN, 0, 0, 0);
        }
        double stop = world.certainOutlineHitBefore(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], v[0], v[1], v[2], entry, before);
        if (stop < entry) {
            double y = (lo[1] + hi[1]) / 2.0;
            return new Failure(Stage.BLOCKS, entry * norm, (entry - stop) * norm, (int) Math.floor((lo[0] + hi[0]) / 2.0 + v[0] * stop),
                    (int) Math.floor(y + v[1] * stop), (int) Math.floor((lo[2] + hi[2]) / 2.0 + v[2] * stop));
        }
        return null;
    }

    /** Whether the ray from the origin along {@code v} is in the box for some {@code s} in {@code [from, to]}. */
    private static boolean meetsWithin(double[] v, AttackReach.Box d, double from, double to) {
        double low = from;
        double high = to;
        double[] min = {d.minX(), d.minY(), d.minZ()};
        double[] max = {d.maxX(), d.maxY(), d.maxZ()};
        for (int i = 0; i < 3 && low <= high; i++) {
            if (v[i] == 0.0) {
                if (min[i] > 0.0 || max[i] < 0.0) {
                    return false;
                }
                continue;
            }
            double a = min[i] / v[i];
            double b = max[i] / v[i];
            low = Math.max(low, Math.min(a, b));
            high = Math.min(high, Math.max(a, b));
        }
        return low <= high;
    }

    /**
     * The smallest {@code s > 0} at which a ray along {@code v} from an eye in {@code [lo, hi]} crosses the box's
     * {@code face} inwards inside the face (within the clip's tolerance): where it enters the box through it; positive
     * infinity if none does.
     */
    static double faceEntry(double[] v, BlockFace face, double[] min, double[] max, double[] lo, double[] hi, double pickHigh) {
        if (!entersThrough(v, face, pickHigh)) {
            return Double.POSITIVE_INFINITY;
        }
        int axis = face.stepX() != 0 ? 0 : face.stepY() != 0 ? 1 : 2;
        double plane = face.stepX() + face.stepY() + face.stepZ() > 0 ? max[axis] : min[axis];
        double low = Math.min((plane - hi[axis]) / v[axis], (plane - lo[axis]) / v[axis]);
        double high = Math.max((plane - hi[axis]) / v[axis], (plane - lo[axis]) / v[axis]);
        low = Math.max(low, 0.0);
        for (int i = 0; i < 3 && low <= high; i++) {
            if (i == axis) {
                continue;
            }
            double from = min[i] - CLIP_TOLERANCE - CURSOR - hi[i];
            double to = max[i] + CLIP_TOLERANCE + CURSOR - lo[i];
            if (v[i] == 0.0) {
                if (from > 0.0 || to < 0.0) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            low = Math.max(low, Math.min(from / v[i], to / v[i]));
            high = Math.min(high, Math.max(from / v[i], to / v[i]));
        }
        return low <= high && high > 0.0 ? Math.max(low, Double.MIN_VALUE) : Double.POSITIVE_INFINITY;
    }

    /** {@code AABB.getDirection}: the face a segment along {@code v} (times at most {@code pickHigh}) can enter a box through. */
    static boolean entersThrough(double[] v, BlockFace face, double pickHigh) {
        // the face's outward normal points against the motion: WEST (-x) is entered moving +x
        double along = -(face.stepX() * v[0] + face.stepY() * v[1] + face.stepZ() * v[2]);
        return along * pickHigh > CLIP_TOLERANCE;
    }

    /**
     * {@code Direction.getApproximateNearest(delta).getOpposite()} for {@code delta = v * d} (floats): {@code face} is the
     * opposite of the axis direction nearest the look, or of one within rounding of it.
     */
    static boolean nearestOpposite(double[] v, BlockFace face) {
        float x = (float) v[0];
        float y = (float) v[1];
        float z = (float) v[2];
        float best = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z));
        float dot = -(face.stepX() * x + face.stepY() * y + face.stepZ() * z); // the look against the face's normal
        return dot > 0.0F && dot >= best - 1.0E-6F;
    }

    /** Whether the location is where a clip of some outline the client may hold at {@code pos} reports it ({@code before} its own prediction). */
    private static boolean onOutline(ClientWorld world, BlockPosition pos, BlockFace face, double[] at, boolean inside, int before) {
        int[] possible = world.possibleStatesBefore(pos.x(), pos.y(), pos.z(), before);
        BlockStates states = world.states();
        if (possible == null || world.hasUnlocatedPredictionBefore(before)) {
            return true; // not known: no claim
        }
        for (int state : possible) {
            if (!states.isValid(state) || !states.outlineExact(state)) {
                return true;
            }
        }
        double[] origin = {pos.x(), pos.y(), pos.z()};
        int axis = face.stepX() != 0 ? 0 : face.stepY() != 0 ? 1 : 2;
        boolean positive = face.stepX() + face.stepY() + face.stepZ() > 0;
        for (int state : possible) {
            Shape outline = states.outlineShape(state);
            for (int b = 0; b < outline.boxCount(); b++) {
                boolean on = true;
                for (int i = 0; i < 3 && on; i++) {
                    double min = origin[i] + outline.box(b, i);
                    double max = origin[i] + outline.box(b, i + 3);
                    if (inside) {
                        on = min - CURSOR <= at[i] && at[i] <= max + CURSOR;
                    } else if (i == axis) {
                        on = Math.abs(at[i] - (positive ? max : min)) <= CURSOR;
                    } else {
                        on = min - CLIP_TOLERANCE - CURSOR <= at[i] && at[i] <= max + CLIP_TOLERANCE + CURSOR;
                    }
                }
                if (on) {
                    return true;
                }
            }
        }
        return false;
    }
}
