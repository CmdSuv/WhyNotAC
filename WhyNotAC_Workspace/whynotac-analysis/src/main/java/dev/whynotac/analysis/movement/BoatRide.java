package dev.whynotac.analysis.movement;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Boat;
import dev.whynotac.physics.BoatState;
import dev.whynotac.physics.BlockPos;
import dev.whynotac.physics.Border;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientWorld;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The boat the client's own player steers, tick by tick (decompiled 1.21.11): every state it may be in
 * ({@link BoatState}), the keys it steers by ({@code AbstractBoat.setInput} at its passenger's last {@code rideTick}) and
 * its no-gravity flag. While the player is its controlling passenger the client moves it with its own physics
 * ({@code isLocalInstanceAuthoritative}): each client tick it runs {@code AbstractBoat.tick} (sending
 * {@code PaddleBoat}), then the passenger's tick sends its rotation and {@code MoveVehicle} (the boat after its tick:
 * position, rotation, ground flag). A tick is explained when a state, run through {@link Boat}, gives exactly that.
 *
 * <h2>The mount</h2>
 * Until the mount the client moved the boat by interpolation alone and zeroed its motion each tick; the entity replica
 * keeps its state at the mount ({@link ClientEntities#mounted}). From it: the position, rotation and interpolation, the
 * ground flag, the motion of a {@code SetEntityMotion} since its last tick (else zero), the status its last tick set
 * (from where that tick started, the blocks there unchanged since), no fall, no turn, no keys, the stuck multiplier of
 * each block that may have held it in a past tick (none, a cobweb's, powder snow's: {@code makeStuckInBlock} holds until
 * the boat moves itself). Not followed, so no claim until the ride ends: a mount the client may have processed a tick
 * earlier, a boat the replica did not follow (lost, or ridden before), its first tick, blocks near it changed since its
 * last tick or one there that moves it from inside ({@link Boat#MOVING_INSIDE}), and the player near enough to have
 * pushed it in that tick ({@code AbstractBoat.tick} pushes the client's own player, and itself back).
 *
 * <h2>The ride</h2>
 * Packets about the boat: {@code SetEntityMotion} sets its motion ({@code lerpMotion}), its no-gravity flag follows its
 * data; position syncs only set the codec's base. Anything else that moves it or the player ends the claim: the
 * server's {@code MoveVehicle} (the client snaps it and answers), a teleport of it or of the player, a packet WhyNotAC
 * cannot read. No claim either while an entity it may collide with ({@code canVehicleCollide}) may be near, or near a
 * world border not known exactly.
 */
final class BoatRide {

    /** More states than this end the claim. */
    static final int MAX_STATES = 64;
    /** Entities the boat may meet: their boxes searched this much beyond where its tick may take it. */
    static final double ENTITY_MARGIN = 1.5;
    /** A world border not known exactly collides within a few blocks: this much is kept free of it. */
    static final double BORDER_MARGIN = 16.0;
    /** {@code makeStuckInBlock} values a boat may hold from its ticks before the mount: none, a cobweb's, powder snow's. */
    static final List<Vec3d> STUCK = List.of(Vec3d.ZERO, new Vec3d(0.25, 0.05F, 0.25), new Vec3d(0.9F, 1.5, 0.9F));
    /** {@code sendPosition}'s threshold: a position not sent is within this of the last one sent. */
    static final double UNSENT = 2.0E-4;

    final int vehicle;
    /** The mount's sequence: where the claim's state starts. */
    final long since;
    /** The states it may be in after its last tick (empty while {@link #unclaimed}). */
    List<BoatState> states;
    /** What its next tick steers by. */
    Boat.Input input = Boat.Input.NONE;
    boolean noGravity;
    /** Why the ride is not followed (until it ends), or null. */
    String unclaimed;

    private BoatRide(int vehicle, long since) {
        this.vehicle = vehicle;
        this.since = since;
        this.states = List.of();
    }

    static BoatRide unclaimed(int vehicle, long since, String why) {
        BoatRide ride = new BoatRide(vehicle, since);
        ride.unclaimed = why;
        return ride;
    }

    void unclaim(String why) {
        if (unclaimed == null) {
            unclaimed = why;
            states = List.of();
        }
    }

    /**
     * The ride from the mount: the replica's boat ({@code mounted}), the world ({@code view} reads it as the client may
     * hold it, {@code world} for its changes), and where the player was during the boat's last tick ({@code positions},
     * each within {@link #UNSENT} of the client's; null: not known).
     */
    static BoatRide start(int vehicle, long since, List<ClientEntities.Mounted> mounted, Colliders.World view, ClientWorld world,
            List<Vec3d> positions) {
        if (mounted == null) {
            return unclaimed(vehicle, since, "a vehicle the replica did not follow before the mount");
        }
        if (mounted.size() != 1 || !mounted.getFirst().exact()) {
            return unclaimed(vehicle, since, "the boat's state at the mount not known exactly");
        }
        ClientEntities.Mounted m = mounted.getFirst();
        if (!m.everTicked() || m.lastTickStart() == null) {
            return unclaimed(vehicle, since, "the boat's first tick");
        }
        Vec3d start = m.lastTickStart();
        Vec3d at = m.position();
        Box last = BoatState.box(start.x(), start.y(), start.z());
        Box now = BoatState.box(at.x(), at.y(), at.z());
        Box swept = Box.of(Math.min(last.minX(), now.minX()), Math.min(last.minY(), now.minY()), Math.min(last.minZ(), now.minZ()),
                Math.max(last.maxX(), now.maxX()), Math.max(last.maxY(), now.maxY()), Math.max(last.maxZ(), now.maxZ()));
        int x0 = (int) Math.floor(swept.minX()) - 1;
        int y0 = (int) Math.floor(swept.minY()) - 1;
        int z0 = (int) Math.floor(swept.minZ()) - 1;
        int x1 = (int) Math.floor(swept.maxX()) + 1;
        int y1 = (int) Math.floor(swept.maxY()) + 1;
        int z1 = (int) Math.floor(swept.maxZ()) + 1;
        if (world.mayHaveChangedIn(x0, y0, z0, x1, y1, z1, m.lastTickLower() + 1)) {
            return unclaimed(vehicle, since, "blocks near the boat may have changed since its last tick");
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int state = view.blockState(x, y, z);
                    if (state == ClientWorld.UNKNOWN || Boat.MOVING_INSIDE.contains(view.states().block(state))) {
                        return unclaimed(vehicle, since, "a block near the boat may move it from inside");
                    }
                }
            }
        }
        if (positions == null || positions.isEmpty()) {
            return unclaimed(vehicle, since, "where the player was in the boat's last tick not known");
        }
        // AbstractBoat.tick, at its end: getEntities(boat, box.inflate(0.2F, -0.01F, 0.2F), pushableBy) holds the client's
        // own player when its box meets that (AABB.intersects), and the boat pushes it and itself
        Box push = now.inflate(0.2F, -0.01F, 0.2F);
        for (Vec3d p : positions) {
            double r = UNSENT + 1.0E-6;
            if (push.intersects(p.x() - 0.3 - r, p.y() - r, p.z() - 0.3 - r, p.x() + 0.3 + r, p.y() + 1.8 + r, p.z() + 0.3 + r)) {
                return unclaimed(vehicle, since, "the player may have pushed the boat before the mount");
            }
        }
        if (m.onGround() < 0) {
            return unclaimed(vehicle, since, "the boat's ground flag not known");
        }
        boolean onGround = m.onGround() == 1;
        BoatState.Status status;
        BlockPos support = null;
        try {
            status = Boat.status(start.x(), start.y(), start.z(), view);
            if (onGround) {
                if (m.groundSetAt() == null) {
                    return unclaimed(vehicle, since, "the boat's ground flag not known");
                }
                support = Boat.supportingBlock(m.groundSetAt().x(), m.groundSetAt().y(), m.groundSetAt().z(), view);
                if (support == null) {
                    return unclaimed(vehicle, since, "the boat on the ground with no block below it");
                }
            }
        } catch (Colliders.Unknown e) {
            return unclaimed(vehicle, since, e.getMessage());
        }
        BoatState.Interpolation interpolation = m.steps() == 0 ? null : new BoatState.Interpolation(m.steps(), m.target(), m.targetYRot(),
                m.targetXRot(), m.previousPosition(), m.previousYRot(), m.previousXRot());
        Vec3d motion = m.motion() == null ? Vec3d.ZERO : m.motion();
        List<BoatState> states = new ArrayList<>(STUCK.size());
        for (Vec3d stuck : STUCK) {
            states.add(new BoatState(at.x(), at.y(), at.z(), motion, m.yRot(), m.xRot(), 0.0F, status, 0.0, 0.0, onGround, false, false, false,
                    support, false, stuck, interpolation));
        }
        BoatRide ride = new BoatRide(vehicle, since);
        ride.states = states;
        ride.noGravity = m.noGravity();
        return ride;
    }

    /** {@code SetEntityMotion} for the boat: {@code lerpMotion}, in every state. */
    void setMotion(Vec3d motion) {
        if (unclaimed != null || !motion.isFinite()) {
            return;
        }
        List<BoatState> out = new ArrayList<>(states.size());
        for (BoatState s : states) {
            out.add(new BoatState(s.x(), s.y(), s.z(), motion, s.yRot(), s.xRot(), s.deltaRotation(), s.status(), s.lastYd(), s.fallDistance(),
                    s.onGround(), s.horizontalCollision(), s.verticalCollision(), s.verticalCollisionBelow(), s.support(), s.onGroundNoBlocks(),
                    s.stuckSpeedMultiplier(), s.interpolation()));
        }
        states = out;
    }

    /** The outcome of one ride tick: null when explained, else why not (with the prediction closest to what was sent). */
    record Step(boolean explained, String reason, BoatState closest) {}

    /**
     * One client tick of the ride: the boat's tick from each state, steered by {@link #input}, must send the client's
     * {@code PaddleBoat} and {@code MoveVehicle}; then the keys of this tick ({@code rideTick}) steer the next. Returns
     * null when nothing is claimed ({@link #unclaimed} says why).
     */
    Step tick(List<ServerboundPacket.PaddleBoat> paddles, List<ServerboundPacket.MoveVehicle> moves, PlayerState.Keys keys, Colliders.World view,
            ClientEntities entities, int self, ClientBorder border) {
        if (unclaimed != null) {
            return null;
        }
        if (paddles.size() != 1 || moves.size() != 1) {
            unclaim("not one paddle and one vehicle move in the tick");
            return null;
        }
        ServerboundPacket.PaddleBoat paddle = paddles.getFirst();
        ServerboundPacket.MoveVehicle move = moves.getFirst();
        boolean[] expected = Boat.paddles(input);
        Border collision;
        try {
            for (BoatState s : states) {
                Box box = s.box();
                Vec3d d = s.deltaMovement();
                double reach = Math.abs(d.x()) + Math.abs(d.y()) + Math.abs(d.z()) + 1.0;
                if (s.interpolation() != null) {
                    Vec3d t = s.interpolation().target();
                    reach += Math.abs(t.x() - s.x()) + Math.abs(t.y() - s.y()) + Math.abs(t.z() - s.z());
                }
                if (!border.known() && !border.farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN + reach)) {
                    throw new MovementTracker.Lose("near a world border not known exactly");
                }
                double m = reach + ENTITY_MARGIN;
                if (entities.mayHaveVehicleColliderNear(self, vehicle, box.minX() - m, box.minY() - m, box.minZ() - m, box.maxX() + m,
                        box.maxY() + m, box.maxZ() + m, MovementTracker.UNKNOWN_ENTITY_RADIUS)) {
                    throw new MovementTracker.Lose("an entity the boat may meet is near");
                }
            }
            collision = border.collision();
        } catch (MovementTracker.Lose e) {
            unclaim(e.getMessage());
            return null;
        }
        Set<BoatState> next = new LinkedHashSet<>();
        BoatState closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (BoatState s : states) {
            BoatState after;
            try {
                after = Boat.tick(s, input, view, new Boat.Conditions(noGravity, collision, List.of()));
            } catch (Colliders.Unknown e) {
                unclaim(e.getMessage());
                return null;
            }
            Vec3d p = move.position();
            boolean same = Double.compare(after.x(), p.x()) == 0 && Double.compare(after.y(), p.y()) == 0 && Double.compare(after.z(), p.z()) == 0
                    && Float.compare(after.yRot(), move.yaw()) == 0 && Float.compare(after.xRot(), move.pitch()) == 0
                    && after.onGround() == move.onGround() && paddle.left() == expected[0] && paddle.right() == expected[1];
            if (same) {
                next.add(after);
            } else {
                double distance = Math.abs(after.x() - p.x()) + Math.abs(after.y() - p.y()) + Math.abs(after.z() - p.z());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = after;
                }
            }
        }
        input = new Boat.Input(keys.left(), keys.right(), keys.forward(), keys.backward());
        if (next.isEmpty()) {
            unclaim("unexplained boat movement");
            return new Step(false, "no vanilla client steers a boat so", closest);
        }
        if (next.size() > MAX_STATES) {
            unclaim("more than " + MAX_STATES + " boat states");
            return new Step(true, null, null);
        }
        states = List.copyOf(next);
        return new Step(true, null, null);
    }
}
