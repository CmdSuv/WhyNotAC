package dev.whynotac.physics;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code AbstractBoat.tick()} of a boat the client's own player steers, bit for bit as the 1.21.11 client runs it
 * (the boat is then local-authoritative): its status ({@code getStatus}: under water, in it, on land with the ground's
 * friction, in the air), the fluids' currents ({@code Entity.baseTick}), {@code floatBoat} (gravity, buoyancy, friction,
 * and the landing on water from the air), {@code controlBoat} (the turn and the paddle's push from the keys),
 * {@code Entity.move} with collisions (no step up) and the boat's own fall count ({@code checkFallDamage}), then the
 * blocks it passed through, twice ({@code applyEffectsFromBlocks} is called twice a tick: the second sweep from where
 * the tick started).
 *
 * <p>The scope is any boat, chest boat or raft (all {@link BoatState#WIDTH} by {@link BoatState#HEIGHT}, none overriding
 * the movement), steered by the local player, not interpolating toward a position the server sent, in water, on land or
 * in the air, against the world border or not, past the blocks that move it from inside (cobwebs, powder snow, honey
 * slides, bubble columns). The block shapes are the player's ({@link Colliders}) with what a boat's context holds: it
 * never descends and walks on no powder snow. Outside the scope, or where the client's world is not known,
 * {@link Colliders.Unknown} is thrown. Sources: the decompiled client, {@code AbstractBoat} and {@code Entity}.
 */
public final class Boat {

    /** {@code AbstractBoat.getDefaultGravity}. */
    static final double GRAVITY = 0.04;
    /**
     * Blocks whose {@code entityInside} changes a boat's motion on the client: the next tick's stuck multiplier (cobwebs,
     * powder snow, which holds an entity that is not living wherever it meets it) or the motion (honey slides, bubble
     * columns closed above). A berry bush holds only living entities; a bubble column open above moves a boat on the
     * server alone ({@code AbstractBoat.onAboveBubbleColumn}).
     */
    static final java.util.Set<String> MOVING_INSIDE = java.util.Set.of("minecraft:bubble_column", "minecraft:honey_block", "minecraft:cobweb",
            Colliders.POWDER_SNOW);

    /** {@code AbstractBoat.setInput}: the keys the passenger held at its last {@code rideTick}, which this tick steers by. */
    public record Input(boolean left, boolean right, boolean up, boolean down) {
        public static final Input NONE = new Input(false, false, false, false);
    }

    /**
     * What the tick needs beyond the boat and the world.
     *
     * @param noGravity the boat's no-gravity flag ({@code isNoGravity}): {@code getGravity} is 0
     * @param border    the client's world border at the move ({@link Border}); null when it is far
     * @param entities  the boxes of the entities the boat collides with ({@code canVehicleCollide}: those that can be
     *                  collided with or are pushable, but its own passengers), as the client holds them during the tick
     */
    public record Conditions(boolean noGravity, Border border, List<Box> entities) {
        public static final Conditions NONE = new Conditions(false, null, List.of());

        public Conditions {
            entities = List.copyOf(entities);
        }
    }

    private final Colliders.World world;
    private final BlockStates states;
    private final Conditions conditions;

    private double x;
    private double y;
    private double z;
    private Vec3d delta;
    private float yRot;
    private final float xRot;
    private float deltaRotation;
    private BoatState.Status status;
    private final BoatState.Status oldStatus;
    /** {@code waterLevel}: set by this tick's {@code getStatus}. */
    private double waterLevel;
    /** {@code landFriction}: set by this tick's {@code getStatus} when on land (halved after its use, which nothing reads). */
    private float landFriction;
    private double lastYd;
    private double fallDistance;
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean verticalCollision;
    private boolean verticalCollisionBelow;
    private BlockPos support;
    private boolean onGroundNoBlocks;
    private Vec3d stuck;
    /** {@code oldPosition()}: where the tick started ({@code setOldPosAndRot}). */
    private final Vec3d oldPosition;
    /** {@code movementThisTick}. */
    private final ArrayList<Terrain.Movement> movements = new ArrayList<>(1);

    private Boat(BoatState pre, Colliders.World world, Conditions conditions) {
        this.world = world;
        this.states = world.states();
        this.conditions = conditions;
        x = pre.x();
        y = pre.y();
        z = pre.z();
        delta = pre.deltaMovement();
        yRot = pre.yRot();
        xRot = pre.xRot();
        deltaRotation = pre.deltaRotation();
        oldStatus = pre.status();
        lastYd = pre.lastYd();
        fallDistance = pre.fallDistance();
        onGround = pre.onGround();
        horizontalCollision = pre.horizontalCollision();
        verticalCollision = pre.verticalCollision();
        verticalCollisionBelow = pre.verticalCollisionBelow();
        support = pre.support();
        onGroundNoBlocks = pre.onGroundNoBlocks();
        stuck = pre.stuckSpeedMultiplier();
        oldPosition = new Vec3d(x, y, z);
    }

    /** The boat after one client tick steered by {@code input}. */
    public static BoatState tick(BoatState pre, Input input, Colliders.World world, Conditions conditions) throws Colliders.Unknown {
        Boat tick = new Boat(pre, world, conditions);
        tick.run(input);
        return tick.state();
    }

    private BoatState state() {
        return new BoatState(x, y, z, delta, yRot, xRot, deltaRotation, status, lastYd, fallDistance, onGround, horizontalCollision, verticalCollision,
                verticalCollisionBelow, support, onGroundNoBlocks, stuck);
    }

    private void run(Input input) throws Colliders.Unknown {
        // --- AbstractBoat.tick: the status, from where the boat is ---
        Box start = box();
        requireInScope(start, "at the start of the tick");
        status = getStatus(start);
        // --- Entity.baseTick: water's current (in water the fall is forgotten), then lava's; a fall in lava halved ---
        Terrain.FluidPush water = Terrain.fluidPushing(world, start, BlockStates.FLUID_WATER, 0.014, delta, false);
        delta = water.delta();
        if (water.touching()) {
            fallDistance = 0.0; // resetFallDistance
        }
        Boolean fast = world.fastLava();
        Terrain.FluidPush lava = Terrain.fluidPushing(world, start, BlockStates.FLUID_LAVA, fast == null ? Double.NaN : fast ? 0.007 : 0.0023333333333333335,
                delta, false);
        delta = lava.delta();
        if (lava.height() > 0.0) { // isInLava (not a new entity's first tick)
            fallDistance *= 0.5;
        }
        // --- the local player steers it: floatBoat, controlBoat, then the move ---
        floatBoat();
        controlBoat(input);
        move(delta);
        requireInScope(box(), "at the end of the move");
        applyEffectsFromBlocks();
        applyEffectsFromBlocks();
    }

    /**
     * {@code AbstractBoat.getStatus}: under water ({@code isUnderwater}: the water's top above the box's, flowing water
     * first; {@code waterLevel} the box's top), in it ({@code checkInWater}: the water's top in the cells at the box's
     * bottom above it; {@code waterLevel} the highest top there), on land ({@code getGroundFriction} above 0, kept in
     * {@code landFriction}), else in the air.
     */
    private BoatState.Status getStatus(Box box) throws Colliders.Unknown {
        BoatState.Status under = isUnderwater(box);
        if (under != null) {
            waterLevel = box.maxY();
            return under;
        }
        if (checkInWater(box)) {
            return BoatState.Status.IN_WATER;
        }
        float friction = groundFriction(box);
        if (friction > 0.0F) {
            landFriction = friction;
            return BoatState.Status.ON_LAND;
        }
        return BoatState.Status.IN_AIR;
    }

    /** {@code AbstractBoat.isUnderwater}: water whose top is above {@code maxY + 0.001} in the cells of the box's top layer. */
    private BoatState.Status isUnderwater(Box box) throws Colliders.Unknown {
        double top = box.maxY() + 0.001;
        boolean under = false;
        for (int bx = Colliders.floor(box.minX()); bx < VanillaMath.ceil(box.maxX()); bx++) {
            for (int by = Colliders.floor(box.maxY()); by < VanillaMath.ceil(top); by++) {
                for (int bz = Colliders.floor(box.minZ()); bz < VanillaMath.ceil(box.maxZ()); bz++) {
                    BlockPos p = new BlockPos(bx, by, bz);
                    int s = state(p);
                    if (states.fluidKind(s) == BlockStates.FLUID_WATER && top < by + Terrain.fluidHeight(world, p, s, BlockStates.FLUID_WATER)) {
                        if (!Terrain.fluidSource(states, s)) {
                            return BoatState.Status.UNDER_FLOWING_WATER;
                        }
                        under = true;
                    }
                }
            }
        }
        return under ? BoatState.Status.UNDER_WATER : null;
    }

    /** {@code AbstractBoat.checkInWater}: the cells at the box's bottom ({@code minY} up to {@code minY + 0.001}). */
    private boolean checkInWater(Box box) throws Colliders.Unknown {
        boolean in = false;
        waterLevel = -Double.MAX_VALUE;
        for (int bx = Colliders.floor(box.minX()); bx < VanillaMath.ceil(box.maxX()); bx++) {
            for (int by = Colliders.floor(box.minY()); by < VanillaMath.ceil(box.minY() + 0.001); by++) {
                for (int bz = Colliders.floor(box.minZ()); bz < VanillaMath.ceil(box.maxZ()); bz++) {
                    BlockPos p = new BlockPos(bx, by, bz);
                    int s = state(p);
                    if (states.fluidKind(s) == BlockStates.FLUID_WATER) {
                        float top = by + Terrain.fluidHeight(world, p, s, BlockStates.FLUID_WATER); // int + float, in float
                        waterLevel = Math.max(top, waterLevel);
                        in |= box.minY() < top;
                    }
                }
            }
        }
        return in;
    }

    /**
     * {@code AbstractBoat.getGroundFriction}: the mean friction of the blocks (lily pads aside) whose collision shape
     * (the empty context's: the table's) meets the slab 0.001 below the box, over the box's cells and one around them,
     * the outer corners and the outer layers' rims left out. 0 / 0 is NaN: in the air.
     */
    private float groundFriction(Box box) throws Colliders.Unknown {
        Box below = new Box(box.minX(), box.minY() - 0.001, box.minZ(), box.maxX(), box.minY(), box.maxZ());
        int x0 = Colliders.floor(below.minX()) - 1;
        int x1 = VanillaMath.ceil(below.maxX()) + 1;
        int y0 = Colliders.floor(below.minY()) - 1;
        int y1 = VanillaMath.ceil(below.maxY()) + 1;
        int z0 = Colliders.floor(below.minZ()) - 1;
        int z1 = VanillaMath.ceil(below.maxZ()) + 1;
        Voxels slab = VoxelCollisions.create(below);
        float sum = 0.0F;
        int count = 0;
        for (int bx = x0; bx < x1; bx++) {
            for (int bz = z0; bz < z1; bz++) {
                int edges = (bx != x0 && bx != x1 - 1 ? 0 : 1) + (bz != z0 && bz != z1 - 1 ? 0 : 1);
                if (edges == 2) {
                    continue;
                }
                for (int by = y0; by < y1; by++) {
                    if (edges <= 0 || by != y0 && by != y1 - 1) {
                        int s = state(new BlockPos(bx, by, bz));
                        if (states.block(s).equals("minecraft:lily_pad")) { // WaterlilyBlock
                            continue;
                        }
                        dev.whynotac.world.Shape shape = emptyContextShape(s, bx, by, bz);
                        if (!shape.isEmpty() && VoxelCollisions.overlaps(Voxels.moved(shape, bx, by, bz), slab)) {
                            sum += states.friction(s);
                            count++;
                        }
                    }
                }
            }
        }
        return sum / count;
    }

    /**
     * {@code state.getCollisionShape(level, pos)} ({@code CollisionContext.empty()}): the table's shape, which was made with
     * that context, but where it depends on the world (shulker boxes, moving pistons) or on an offset (bamboo, pointed
     * dripstone). Liquids and powder snow are empty to it, scaffolding is its stable shape: the table's.
     */
    private dev.whynotac.world.Shape emptyContextShape(int s, int bx, int by, int bz) throws Colliders.Unknown {
        dev.whynotac.world.Shape shape = states.collisionShape(s);
        String block = states.block(s);
        if (states.has(s, BlockStates.DYNAMIC_SHAPE) && !block.equals(Colliders.POWDER_SNOW) && !block.equals(Colliders.SCAFFOLDING)) {
            throw new Colliders.Unknown(block + " (shape depends on the world) under a boat at " + bx + " " + by + " " + bz);
        }
        if (states.has(s, BlockStates.OFFSET) && !shape.isEmpty()) {
            throw new Colliders.Unknown(block + " (offset shape) under a boat at " + bx + " " + by + " " + bz);
        }
        return shape;
    }

    /**
     * {@code AbstractBoat.floatBoat}: from the air onto water the boat is set on the surface ({@code getWaterLevelAbove},
     * where the box fits there), its vertical motion stopped, with neither gravity nor friction that tick; otherwise
     * gravity ({@code 7.0E-4} under flowing water), the status's friction on the motion across and on the turn, and the
     * water's lift (in it, by how deep the bottom is; under it, {@code 0.01F}).
     */
    private void floatBoat() throws Colliders.Unknown {
        double gravity = -(conditions.noGravity() ? 0.0 : GRAVITY); // -getGravity()
        double buoyancy = 0.0;
        float friction = 0.05F;
        if (oldStatus == BoatState.Status.IN_AIR && status != BoatState.Status.IN_AIR && status != BoatState.Status.ON_LAND) {
            // waterLevel = getY(1.0): not read again this tick
            double top = (waterLevelAbove() - BoatState.HEIGHT) + 0.101; // float - float, then + double
            if (noCollision(box().move(0.0, top - y, 0.0))) {
                y = top;
                delta = delta.multiply(1.0, 0.0, 1.0);
                lastYd = 0.0;
            }
            status = BoatState.Status.IN_WATER;
            return;
        }
        switch (status) {
            case IN_WATER -> {
                buoyancy = (waterLevel - y) / BoatState.HEIGHT;
                friction = 0.9F;
            }
            case UNDER_FLOWING_WATER -> {
                gravity = -7.0E-4;
                friction = 0.9F;
            }
            case UNDER_WATER -> {
                buoyancy = 0.01F;
                friction = 0.45F;
            }
            case IN_AIR -> friction = 0.9F;
            case ON_LAND -> friction = landFriction;
        }
        delta = new Vec3d(delta.x() * friction, delta.y() + gravity, delta.z() * friction);
        deltaRotation *= friction;
        if (buoyancy > 0.0) {
            delta = new Vec3d(delta.x(), (delta.y() + buoyancy * (GRAVITY / 0.65)) * 0.75, delta.z()); // getDefaultGravity, not getGravity
        }
    }

    /**
     * {@code AbstractBoat.getWaterLevelAbove}: from the box's top layer up to where it was a tick before
     * ({@code ceil(maxY - lastYd)}), the first layer the water does not fill, at the height of its highest water
     * ({@code y + height} in float); above them all, the layer after.
     */
    private float waterLevelAbove() throws Colliders.Unknown {
        Box box = box();
        int x0 = Colliders.floor(box.minX());
        int x1 = VanillaMath.ceil(box.maxX());
        int y0 = Colliders.floor(box.maxY());
        int y1 = VanillaMath.ceil(box.maxY() - lastYd);
        int z0 = Colliders.floor(box.minZ());
        int z1 = VanillaMath.ceil(box.maxZ());
        layers:
        for (int by = y0; by < y1; by++) {
            float height = 0.0F;
            for (int bx = x0; bx < x1; bx++) {
                for (int bz = z0; bz < z1; bz++) {
                    BlockPos p = new BlockPos(bx, by, bz);
                    int s = state(p);
                    if (states.fluidKind(s) == BlockStates.FLUID_WATER) {
                        height = Math.max(height, Terrain.fluidHeight(world, p, s, BlockStates.FLUID_WATER));
                    }
                    if (height >= 1.0F) {
                        continue layers;
                    }
                }
            }
            if (height < 1.0F) {
                return by + height;
            }
        }
        return y1 + 1;
    }

    /**
     * {@code AbstractBoat.controlBoat}: left and right turn it a degree more each (their fading turn kept in
     * {@code deltaRotation}), a turn alone pushes 0.005 ahead, forward 0.04 and back -0.005, along the new heading
     * ({@code Mth.sin}, {@code Mth.cos} of the float angle). The paddles' state goes to the server (not the motion).
     */
    private void controlBoat(Input in) {
        float push = 0.0F;
        if (in.left()) {
            deltaRotation--;
        }
        if (in.right()) {
            deltaRotation++;
        }
        if (in.right() != in.left() && !in.up() && !in.down()) {
            push += 0.005F;
        }
        float turned = yRot + deltaRotation;
        if (Float.isFinite(turned)) { // setYRot discards a rotation that is not finite
            yRot = turned;
        }
        if (in.up()) {
            push += 0.04F;
        }
        if (in.down()) {
            push -= 0.005F;
        }
        delta = delta.add(VanillaMath.sin(-yRot * VanillaMath.DEG_TO_RAD) * push, 0.0, VanillaMath.cos(yRot * VanillaMath.DEG_TO_RAD) * push);
    }

    /** {@code CollisionContext.of(boat)}: its height now, never descending, no leather boots (it is no living entity). */
    private Colliders.Context context() {
        return new Colliders.Context(y, false, x, z, conditions.border(), fallDistance, Boolean.FALSE);
    }

    /**
     * {@code Level.getEntityCollisions(boat, query)}: the boxes of the entities the boat collides with that meet the query
     * inflated by {@code 1.0E-7}; none for a query smaller than {@code 1.0E-7} ({@code AABB.getSize}).
     */
    private List<Voxels> entityCollisions(Box query) {
        if (conditions.entities().isEmpty()) {
            return List.of();
        }
        double size = ((query.maxX() - query.minX()) + (query.maxY() - query.minY()) + (query.maxZ() - query.minZ())) / 3.0;
        if (size < 1.0E-7) {
            return List.of();
        }
        Box near = query.inflate(1.0E-7, 1.0E-7, 1.0E-7);
        List<Voxels> out = new ArrayList<>();
        for (Box b : conditions.entities()) {
            if (near.intersects(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
                out.add(VoxelCollisions.create(b));
            }
        }
        return out;
    }

    /** {@code CollisionGetter.noCollision(boat, box)}: no block, no entity it collides with, and not the border's shape. */
    private boolean noCollision(Box box) throws Colliders.Unknown {
        return entityCollisions(box).isEmpty() && Colliders.noCollision(world, context(), box);
    }

    /** {@code Entity.move(MoverType.SELF, movement)} for the boat (no step up: {@code maxUpStep} 0). */
    private void move(Vec3d movement) throws Colliders.Unknown {
        if (stuck.lengthSqr() > 1.0E-7) {
            movement = movement.multiply(stuck);
            stuck = Vec3d.ZERO;
            delta = Vec3d.ZERO;
        }
        Box box = box();
        Vec3d collided = Collide.collide(world, context(), box, movement, onGround, 0.0F,
                entityCollisions(box.expandTowards(movement.x(), movement.y(), movement.z())));
        double length = collided.lengthSqr();
        if (length > 1.0E-7 || movement.lengthSqr() - length < 1.0E-7) {
            if (fallDistance != 0.0 && length >= 1.0 && Terrain.mayResetTheFall(world, x, y, z, collided)) {
                throw new Colliders.Unknown("a fall reset by a ray (not modelled)");
            }
            Vec3d from = new Vec3d(x, y, z);
            x += collided.x();
            y += collided.y();
            z += collided.z();
            movements.add(new Terrain.Movement(from, new Vec3d(x, y, z), movement)); // addMovementThisTick
        }
        boolean xHit = !VanillaMath.equal(movement.x(), collided.x());
        boolean zHit = !VanillaMath.equal(movement.z(), collided.z());
        horizontalCollision = xHit || zHit;
        verticalCollision = movement.y() != collided.y();
        verticalCollisionBelow = verticalCollision && movement.y() < 0.0;
        onGround = verticalCollisionBelow;
        checkSupportingBlock(collided);
        int legacyState = state(onPos(0.2F)); // getOnPosLegacy
        // AbstractBoat.checkFallDamage: the motion before the move kept, the fall counted but over water
        lastYd = delta.y();
        if (onGround) {
            fallDistance = 0.0;
        } else if (states.fluidKind(state(below(BlockPos.containing(x, y, z)))) != BlockStates.FLUID_WATER && collided.y() < 0.0) { // blockPosition().below()
            fallDistance -= (float) collided.y();
        }
        if (horizontalCollision) {
            delta = new Vec3d(xHit ? 0.0 : delta.x(), delta.y(), zHit ? 0.0 : delta.z());
        }
        if (movement.y() != collided.y()) {
            updateEntityMovementAfterFallOn(states.block(legacyState));
        }
        float speedFactor = Terrain.blockSpeedFactor(world, support, x, y, z);
        delta = delta.multiply(speedFactor, 1.0, speedFactor);
    }

    /**
     * {@code Block.updateEntityMovementAfterFallOn} for the block below ({@code getOnPosLegacy}), after a vertical
     * collision: the vertical motion stops; a slime block or a bed bounces a falling boat back instead (by 0.8, an entity
     * that is not living; on a bed times 0.66 more). A boat never suppresses the bounce (it never sneaks).
     */
    private void updateEntityMovementAfterFallOn(String block) {
        boolean slime = block.equals("minecraft:slime_block");
        boolean bed = block.endsWith("_bed");
        if (!(slime || bed)) {
            delta = delta.multiply(1.0, 0.0, 1.0);
            return;
        }
        if (delta.y() < 0.0) {
            double notLiving = 0.8;
            delta = new Vec3d(delta.x(), slime ? -delta.y() * notLiving : -delta.y() * 0.66F * notLiving, delta.z());
        }
    }

    /** {@code Entity.checkSupportingBlock(onGround, movement)}, after the position moved. */
    private void checkSupportingBlock(Vec3d movement) throws Colliders.Unknown {
        if (onGround) {
            Box box = box();
            Box below = Box.of(box.minX(), box.minY() - 1.0E-6, box.minZ(), box.maxX(), box.minY(), box.maxZ());
            BlockPos found = Colliders.supportingBlock(world, context(), below, x, y, z);
            if (found != null || onGroundNoBlocks) {
                support = found;
            } else {
                found = Colliders.supportingBlock(world, context(), below.move(-movement.x(), 0.0, -movement.z()), x, y, z);
                support = found;
            }
            onGroundNoBlocks = found == null;
        } else {
            onGroundNoBlocks = false;
            support = null;
        }
    }

    /**
     * {@code Entity.applyEffectsFromBlocks}: on the ground, the block below's {@code stepOn} (a slime block slows the boat
     * while its vertical motion is under 0.1: {@code 0.4 + |y| * 0.2} across), then the blocks the box passed through
     * along the tick's moves, or from where the tick started when there are none (as at the second call).
     */
    private void applyEffectsFromBlocks() throws Colliders.Unknown {
        List<Terrain.Movement> moves = Terrain.finalMovements(movements, oldPosition, new Vec3d(x, y, z));
        movements.clear();
        if (onGround && states.block(state(onPos(0.2F))).equals("minecraft:slime_block")) {
            double vy = Math.abs(delta.y());
            if (vy < 0.1) { // isSteppingCarefully: never for a boat
                double factor = 0.4 + vy * 0.2;
                delta = delta.multiply(factor, 1.0, factor);
            }
        }
        Terrain.checkInsideBlocks(world, moves, BoatState::box, new Terrain.Inside() {
            @Override
            public boolean meets(int s, BlockPos pos, Vec3d from, Vec3d to) throws Colliders.Unknown {
                // getEntityInsideCollisionShape: the full block, but powder snow's own shape for the boat (the falling slab) where it has one
                return MOVING_INSIDE.contains(states.block(s)) && (!states.block(s).equals(Colliders.POWDER_SNOW)
                        || Colliders.powderSnowEntered(context(), BoatState.box(from.x(), from.y(), from.z()), to.subtract(from), pos.x(), pos.y(), pos.z()));
            }

            @Override
            public void inside(int s, BlockPos pos, boolean intersects) throws Colliders.Unknown {
                entityInside(s, pos, intersects);
            }
        });
    }

    /**
     * The {@code entityInside} of the blocks that move a boat: a cobweb or powder snow holds the next move back
     * ({@code makeStuckInBlock}, the fall forgotten), honey slows a fall down its side ({@code HoneyBlock.doSlideMovement}),
     * a bubble column the box meets lifts or drags it where its top is closed ({@code onInsideBubbleColumn}).
     */
    private void entityInside(int s, BlockPos pos, boolean intersects) throws Colliders.Unknown {
        switch (states.block(s)) {
            case "minecraft:cobweb" -> {
                fallDistance = 0.0;
                stuck = new Vec3d(0.25, 0.05F, 0.25);
            }
            case Colliders.POWDER_SNOW -> {
                fallDistance = 0.0;
                stuck = new Vec3d(0.9F, 1.5, 0.9F);
            }
            case "minecraft:honey_block" -> {
                if (isSlidingDown(pos)) {
                    double old = delta.y() / 0.98F + 0.08; // getOldDeltaY
                    double slid = (-0.05 - 0.08) * 0.98F; // getNewDeltaY(-0.05)
                    if (old < -0.13) {
                        double factor = -0.05 / old;
                        delta = new Vec3d(delta.x() * factor, slid, delta.z() * factor);
                    } else {
                        delta = new Vec3d(delta.x(), slid, delta.z());
                    }
                    fallDistance = 0.0;
                }
            }
            case "minecraft:bubble_column" -> {
                if (intersects) {
                    boolean drag = Terrain.property(states, s, "drag").equals("true");
                    int above = state(pos.above());
                    if (states.has(above, BlockStates.DYNAMIC_SHAPE)) {
                        throw new Colliders.Unknown(states.block(above) + " (shape depends on the world) over a bubble column at " + pos);
                    }
                    if (!(states.collisionShape(above).isEmpty() && states.fluidKind(above) == BlockStates.FLUID_NONE)) { // onInsideBubbleColumn
                        delta = new Vec3d(delta.x(), drag ? Math.max(-0.3, delta.y() - 0.03) : Math.min(0.7, delta.y() + 0.06), delta.z());
                        fallDistance = 0.0;
                    }
                }
            }
            default -> throw new Colliders.Unknown(states.block(s) + " inside the boat at " + pos + " (not modelled)");
        }
    }

    /** {@code HoneyBlock.isSlidingDown} for the boat: falling in the air, below the top, against a side of the block. */
    private boolean isSlidingDown(BlockPos pos) {
        if (onGround || y > pos.y() + 0.9375 - 1.0E-7 || delta.y() / 0.98F + 0.08 >= -0.08) {
            return false;
        }
        double dx = Math.abs(pos.x() + 0.5 - x);
        double dz = Math.abs(pos.z() + 0.5 - z);
        double edge = 0.4375 + BoatState.WIDTH / 2.0F;
        return dx + 1.0E-7 > edge || dz + 1.0E-7 > edge;
    }

    /** No block in the cells the box spans holds a fluid but water and lava. */
    private void requireInScope(Box box, String when) throws Colliders.Unknown {
        for (int bx = Colliders.floor(box.minX()); bx <= Colliders.floor(box.maxX()); bx++) {
            for (int by = Colliders.floor(box.minY()); by <= Colliders.floor(box.maxY()); by++) {
                for (int bz = Colliders.floor(box.minZ()); bz <= Colliders.floor(box.maxZ()); bz++) {
                    int s = state(new BlockPos(bx, by, bz));
                    if (states.fluidKind(s) == BlockStates.FLUID_OTHER) {
                        throw new Colliders.Unknown(states.block(s) + " inside the boat " + when + " (not modelled)");
                    }
                }
            }
        }
    }

    private static BlockPos below(BlockPos p) {
        return p.atY(p.y() - 1);
    }

    private Box box() {
        return BoatState.box(x, y, z);
    }

    private BlockPos onPos(float offset) throws Colliders.Unknown {
        return Terrain.onPos(world, support, x, y, z, offset);
    }

    private int state(BlockPos pos) throws Colliders.Unknown {
        return Terrain.state(world, pos);
    }
}
