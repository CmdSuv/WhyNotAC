package dev.whynotac.physics;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.List;

/**
 * One {@code LocalPlayer.tick()} of a player on foot, bit for bit as the 1.21.11 client runs it: input,
 * crouching and sprinting ({@code LocalPlayer.aiStep}), jumping ({@code LivingEntity.aiStep}), the walk
 * ({@code travelInAir}: friction, the input impulse, {@code Entity.move} with collisions and step-up,
 * gravity and drag) and the pose for the next tick ({@code Player.updatePlayerPose}).
 *
 * <p>The scope is a survival player on foot with default attributes and no effects, not using an item,
 * not riding, flying, gliding or swimming, on blocks without side effects and away from solid entities and
 * the world border. Outside it, or where the client's world is not known, {@link Colliders.Unknown} is
 * thrown: no claim can rest on that tick. Sources: the decompiled client, {@code LocalPlayer},
 * {@code Player}, {@code LivingEntity} and {@code Entity}.
 */
public final class OnFoot {

    /** {@code Attributes.MOVEMENT_SPEED}: the player's base ({@code 0.1F} as a double). */
    static final double BASE_SPEED = 0.1F;
    /** The sprinting modifier: {@code ADD_MULTIPLIED_TOTAL} {@code 0.3F}. */
    static final double SPRINT_MODIFIER = 0.3F;
    static final float SNEAKING_SPEED = (float) 0.3;
    static final float JUMP_STRENGTH = (float) (double) 0.42F; // the attribute stores 0.42F as a double
    static final float STEP_HEIGHT = (float) 0.6;
    static final double GRAVITY = 0.08;
    /** {@code options.sprintWindow()} default. */
    public static final int DEFAULT_SPRINT_WINDOW = 7;
    /**
     * Blocks whose {@code entityInside} changes the player's movement on the client: velocity (bubble
     * columns, honey slides) or the next tick's stuck multiplier (cobwebs, berry bushes, powder snow).
     */
    static final java.util.Set<String> MOVING_INSIDE = java.util.Set.of("minecraft:bubble_column", "minecraft:honey_block", "minecraft:cobweb",
            "minecraft:sweet_berry_bush", "minecraft:powder_snow");
    /** {@code BlockTags.CLIMBABLE}; trapdoors can be ladders too ({@code trapdoorUsableAsLadder}). */
    static final java.util.Set<String> CLIMBABLE = java.util.Set.of("minecraft:ladder", "minecraft:vine", "minecraft:scaffolding",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
            "minecraft:cave_vines", "minecraft:cave_vines_plant");

    /**
     * What the step needs beyond the player and the world.
     *
     * @param foodAllowsSprint {@code hasEnoughFoodToDoExhaustiveManoeuvres}: food above 6, or allowed to fly
     * @param sprintWindow     the client's {@code sprintWindow} option (double-tap window, 0..10)
     * @param autoJumpEnabled  {@code LocalPlayer.autoJumpEnabled}: the option, but true during a new player's first tick
     */
    public record Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled) {
        public static final Conditions VANILLA_DEFAULTS = new Conditions(true, DEFAULT_SPRINT_WINDOW, false);
    }

    private final Colliders.World world;
    private final BlockStates states;
    private final Conditions conditions;

    // the player during the tick
    private double x;
    private double y;
    private double z;
    private Vec3d delta;
    private final float yRot;
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean minorHorizontalCollision;
    private boolean verticalCollision;
    private boolean verticalCollisionBelow;
    private double fallDistance;
    private boolean sprinting;
    private boolean crouching;
    private PlayerState.Pose pose;
    private PlayerState.Keys keys;
    private int noJumpDelay;
    private int sprintTriggerTime;
    private int autoJumpTime;
    private BlockPos support;
    private boolean onGroundNoBlocks;
    private Vec3d stuck;
    private float xxa;
    private float zza;
    private boolean jumping;
    private float moveX;
    private float moveY;

    private OnFoot(PlayerState pre, Colliders.World world, Conditions conditions) {
        this.world = world;
        this.states = world.states();
        this.conditions = conditions;
        x = pre.x();
        y = pre.y();
        z = pre.z();
        delta = pre.deltaMovement();
        yRot = pre.yRot();
        onGround = pre.onGround();
        horizontalCollision = pre.horizontalCollision();
        minorHorizontalCollision = pre.minorHorizontalCollision();
        verticalCollision = pre.verticalCollision();
        verticalCollisionBelow = pre.verticalCollisionBelow();
        fallDistance = pre.fallDistance();
        sprinting = pre.sprinting();
        crouching = pre.crouching();
        pose = pre.pose();
        keys = pre.keys();
        noJumpDelay = pre.noJumpDelay();
        sprintTriggerTime = pre.sprintTriggerTime();
        autoJumpTime = pre.autoJumpTime();
        support = pre.support();
        onGroundNoBlocks = pre.onGroundNoBlocks();
        stuck = pre.stuckSpeedMultiplier();
    }

    /** The player after one client tick in which the keys were {@code keys}. */
    public static PlayerState tick(PlayerState pre, PlayerState.Keys keys, Colliders.World world, Conditions conditions) throws Colliders.Unknown {
        if (pre.pose() == PlayerState.Pose.SWIMMING) {
            throw new Colliders.Unknown("crawling");
        }
        OnFoot tick = new OnFoot(pre, world, conditions);
        tick.run(keys);
        return tick.state(pre.xRot());
    }

    private PlayerState state(float xRot) {
        return new PlayerState(x, y, z, delta, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision,
                verticalCollisionBelow, fallDistance, sprinting, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support,
                onGroundNoBlocks, stuck);
    }

    private void run(PlayerState.Keys polled) throws Colliders.Unknown {
        // baseTick's fluid checks use the box deflated by 0.001; the feet block decides climbing
        Box start = pose.box(x, y, z);
        requireNoEffects(start.inflate(-0.001, -0.001, -0.001), start, "at the start of the tick");
        requireNotClimbing();
        // --- LocalPlayer.aiStep ---
        if (sprintTriggerTime > 0) {
            sprintTriggerTime--;
        }
        boolean previousShift = keys.shift();
        moveVector(keys); // the previous tick's vector, from its keys
        boolean previousForward = moveY > 1.0E-5F;
        crouching = fits(PlayerState.Pose.CROUCHING) && (previousShift || !fits(PlayerState.Pose.STANDING));
        keys = polled;
        moveVector(keys);
        if (autoJumpTime > 0) {
            autoJumpTime--;
            keys = keys.withJump(); // input.makeJump(): the vector stays
        }
        requireNoSuffocation();
        if (previousShift || keys.backward()) {
            sprintTriggerTime = 0;
        }
        boolean forward = moveY > 1.0E-5F;
        boolean movingSlowly = crouching;
        if (!sprinting && forward && conditions.foodAllowsSprint() && !movingSlowly) { // canStartSprinting
            if (!previousForward) {
                if (sprintTriggerTime > 0) {
                    sprinting = true;
                } else {
                    sprintTriggerTime = conditions.sprintWindow();
                }
            }
            if (keys.sprint()) {
                sprinting = true;
            }
        }
        if (sprinting && (!conditions.foodAllowsSprint() || !forward || horizontalCollision && !minorHorizontalCollision)) { // shouldStopRunSprinting
            sprinting = false;
        }

        // --- LivingEntity.aiStep ---
        if (noJumpDelay > 0) {
            noJumpDelay--;
        }
        double dx = delta.x();
        double dy = delta.y();
        double dz = delta.z();
        if (delta.horizontalDistanceSqr() < 9.0E-6) {
            dx = 0.0;
            dz = 0.0;
        }
        if (Math.abs(delta.y()) < 0.003) {
            dy = 0.0;
        }
        delta = new Vec3d(dx, dy, dz);
        applyInput(movingSlowly);
        if (jumping) {
            if (onGround && noJumpDelay == 0) {
                jumpFromGround();
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }
        travelInAir(new Vec3d(xxa, 0.0, zza));
        applyEffectsFromBlocks();

        // --- Player.tick after LivingEntity.tick ---
        double clampedX = VanillaMath.clamp(x, -2.9999999E7, 2.9999999E7);
        double clampedZ = VanillaMath.clamp(z, -2.9999999E7, 2.9999999E7);
        if (clampedX != x || clampedZ != z) {
            x = clampedX;
            z = clampedZ;
        }
        updatePlayerPose();
    }

    /** {@code KeyboardInput.tick}: the strafe and forward impulses, normalised ({@code Vec2.normalized}). */
    private void moveVector(PlayerState.Keys k) {
        float forward = k.forward() == k.backward() ? 0.0F : k.forward() ? 1.0F : -1.0F;
        float strafe = k.left() == k.right() ? 0.0F : k.left() ? 1.0F : -1.0F;
        float length = (float) Math.sqrt(strafe * strafe + forward * forward);
        if (length < 1.0E-4F) {
            moveX = 0.0F;
            moveY = 0.0F;
        } else {
            moveX = strafe / length;
            moveY = forward / length;
        }
    }

    /** {@code LocalPlayer.applyInput} / {@code modifyInput}: all in floats. */
    private void applyInput(boolean movingSlowly) {
        float vx = moveX;
        float vy = moveY;
        if (vx * vx + vy * vy != 0.0F) {
            vx *= 0.98F;
            vy *= 0.98F;
            if (movingSlowly) {
                vx *= SNEAKING_SPEED;
                vy *= SNEAKING_SPEED;
            }
            // modifyInputSpeedForSquareMovement
            float length = (float) Math.sqrt(vx * vx + vy * vy);
            if (length > 0.0F) {
                float inverse = 1.0F / length;
                float ux = vx * inverse;
                float uy = vy * inverse;
                float ax = Math.abs(ux);
                float ay = Math.abs(uy);
                float ratio = ay > ax ? ax / ay : ay / ax;
                float toSquare = (float) Math.sqrt(1.0F + ratio * ratio);
                float scale = Math.min(length * toSquare, 1.0F);
                vx = ux * scale;
                vy = uy * scale;
            }
        }
        xxa = vx;
        zza = vy;
        jumping = keys.jump();
    }

    /** {@code LivingEntity.jumpFromGround}. */
    private void jumpFromGround() throws Colliders.Unknown {
        float power = JUMP_STRENGTH * 1.0F * blockJumpFactor();
        if (!(power <= 1.0E-5F)) {
            delta = new Vec3d(delta.x(), Math.max(power, delta.y()), delta.z());
            if (sprinting) {
                float radians = yRot * VanillaMath.DEG_TO_RAD;
                Vec3d boost = new Vec3d(-VanillaMath.sin(radians) * 0.2, 0.0, VanillaMath.cos(radians) * 0.2);
                if (boost.isFinite()) {
                    delta = delta.add(boost);
                }
            }
        }
    }

    /** {@code Entity.getBlockJumpFactor}: the block at the feet, else the one below that affects movement. */
    private float blockJumpFactor() throws Colliders.Unknown {
        BlockPos feet = BlockPos.containing(x, y, z);
        float atFeet = states.jumpFactor(state(feet));
        return atFeet == 1.0 ? states.jumpFactor(state(onPos(0.500001F))) : atFeet;
    }

    /** {@code LivingEntity.travelInAir}. */
    private void travelInAir(Vec3d input) throws Colliders.Unknown {
        BlockPos below = onPos(0.500001F);
        float friction = onGround ? states.friction(state(below)) : 1.0F;
        float drag = friction * 0.91F;
        // handleRelativeFrictionAndCalculateMovement (not climbing: the box is in air)
        delta = delta.add(Vec3d.inputVector(input, frictionInfluencedSpeed(friction), yRot));
        Box before = pose.box(x, y, z);
        move(delta);
        requireNotClimbing(); // handleRelativeFrictionAndCalculateMovement asks again after the move
        // checkInsideBlocks sweeps the box (deflated by 1.0E-5F) along the move
        requireNoEffects(before.inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), pose.box(x, y, z).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), "along the move");
        Vec3d moved = delta;
        double vy = moved.y();
        if (world.isLoaded(below.x() >> 4, below.z() >> 4)) {
            vy -= GRAVITY;
        } else if (y > minY()) {
            vy = -0.1;
        } else {
            vy = 0.0;
        }
        delta = new Vec3d(moved.x() * drag, vy * 0.98F, moved.z() * drag);
    }

    private int minY() throws Colliders.Unknown {
        throw new Colliders.Unknown("movement in an unloaded chunk");
    }

    /** {@code getFrictionInfluencedSpeed}: on the ground the speed attribute scaled by friction, else the air speed. */
    private float frictionInfluencedSpeed(float friction) {
        if (onGround) {
            double speed = BASE_SPEED;
            if (sprinting) {
                speed *= 1.0 + SPRINT_MODIFIER;
            }
            return (float) speed * (0.21600002F / (friction * friction * friction));
        }
        return sprinting ? 0.025999999F : 0.02F;
    }

    /** {@code Entity.move(MoverType.SELF, movement)} for the local player. */
    private void move(Vec3d movement) throws Colliders.Unknown {
        if (stuck.lengthSqr() > 1.0E-7) {
            movement = movement.multiply(stuck);
            stuck = Vec3d.ZERO;
            delta = Vec3d.ZERO;
        }
        movement = maybeBackOffFromEdge(movement);
        Box box = pose.box(x, y, z);
        Vec3d collided = Collide.collide(world, box, movement, onGround, STEP_HEIGHT, List.of());
        double length = collided.lengthSqr();
        if (length > 1.0E-7 || movement.lengthSqr() - length < 1.0E-7) {
            if (fallDistance != 0.0 && length >= 1.0) {
                throw new Colliders.Unknown("a fall reset by a ray (not modelled)");
            }
            x += collided.x();
            y += collided.y();
            z += collided.z();
        }
        boolean xHit = !VanillaMath.equal(movement.x(), collided.x());
        boolean zHit = !VanillaMath.equal(movement.z(), collided.z());
        horizontalCollision = xHit || zHit;
        verticalCollision = movement.y() != collided.y();
        verticalCollisionBelow = verticalCollision && movement.y() < 0.0;
        onGround = verticalCollisionBelow;
        checkSupportingBlock(collided);
        minorHorizontalCollision = horizontalCollision && isHorizontalCollisionMinor(collided);
        BlockPos legacy = onPos(0.2F);
        int legacyState = state(legacy);
        // checkFallDamage (the box is in air, so not in water)
        if (collided.y() < 0.0) {
            fallDistance -= (float) collided.y();
        }
        if (onGround) {
            fallDistance = 0.0;
        }
        if (horizontalCollision) {
            delta = new Vec3d(xHit ? 0.0 : delta.x(), delta.y(), zHit ? 0.0 : delta.z());
        }
        if (movement.y() != collided.y()) {
            String block = states.block(legacyState);
            if (block.equals("minecraft:slime_block") || block.endsWith("_bed")) {
                throw new Colliders.Unknown(block + " bounce (not modelled)");
            }
            delta = delta.multiply(1.0, 0.0, 1.0); // Block.updateEntityMovementAfterFallOn
        }
        float speedFactor = blockSpeedFactor();
        delta = delta.multiply(speedFactor, 1.0, speedFactor);
    }

    /** {@code Player.maybeBackOffFromEdge}: a sneaking player on the ground does not walk off an edge it could fall down. */
    private Vec3d maybeBackOffFromEdge(Vec3d movement) throws Colliders.Unknown {
        float step = STEP_HEIGHT;
        if (!(movement.y() > 0.0) && keys.shift() && isAboveGround(step)) {
            double mx = movement.x();
            double mz = movement.z();
            double sx = Math.signum(mx) * 0.05;
            double sz = Math.signum(mz) * 0.05;
            while (mx != 0.0 && canFallAtLeast(mx, 0.0, step)) {
                if (Math.abs(mx) <= 0.05) {
                    mx = 0.0;
                    break;
                }
                mx -= sx;
            }
            while (mz != 0.0 && canFallAtLeast(0.0, mz, step)) {
                if (Math.abs(mz) <= 0.05) {
                    mz = 0.0;
                    break;
                }
                mz -= sz;
            }
            while (mx != 0.0 && mz != 0.0 && canFallAtLeast(mx, mz, step)) {
                if (Math.abs(mx) <= 0.05) {
                    mx = 0.0;
                } else {
                    mx -= sx;
                }
                if (Math.abs(mz) <= 0.05) {
                    mz = 0.0;
                } else {
                    mz -= sz;
                }
            }
            return new Vec3d(mx, movement.y(), mz);
        }
        return movement;
    }

    private boolean isAboveGround(float step) throws Colliders.Unknown {
        return onGround || fallDistance < step && !canFallAtLeast(0.0, 0.0, step - fallDistance);
    }

    /** {@code Player.canFallAtLeast}: nothing below the box, moved by (dx, dz), down to {@code height}. */
    private boolean canFallAtLeast(double dx, double dz, double height) throws Colliders.Unknown {
        Box box = pose.box(x, y, z);
        return Colliders.noCollision(world, Box.of(box.minX() + 1.0E-7 + dx, box.minY() - height - 1.0E-7, box.minZ() + 1.0E-7 + dz,
                box.maxX() - 1.0E-7 + dx, box.minY(), box.maxZ() - 1.0E-7 + dz));
    }

    /** {@code Entity.checkSupportingBlock(onGround, movement)}, after the position moved. */
    private void checkSupportingBlock(Vec3d movement) throws Colliders.Unknown {
        if (onGround) {
            Box box = pose.box(x, y, z);
            Box below = Box.of(box.minX(), box.minY() - 1.0E-6, box.minZ(), box.maxX(), box.minY(), box.maxZ());
            BlockPos found = Colliders.supportingBlock(world, below, x, y, z);
            if (found != null || onGroundNoBlocks) {
                support = found;
            } else {
                found = Colliders.supportingBlock(world, below.move(-movement.x(), 0.0, -movement.z()), x, y, z);
                support = found;
            }
            onGroundNoBlocks = found == null;
        } else {
            onGroundNoBlocks = false;
            support = null;
        }
    }

    /** {@code LocalPlayer.isHorizontalCollisionMinor}: the collision is within 8 degrees of the walking direction. */
    private boolean isHorizontalCollisionMinor(Vec3d movement) {
        float radians = yRot * VanillaMath.DEG_TO_RAD;
        double sin = VanillaMath.sin(radians);
        double cos = VanillaMath.cos(radians);
        double wx = xxa * cos - zza * sin;
        double wz = zza * cos + xxa * sin;
        double walk = wx * wx + wz * wz;
        double moved = movement.x() * movement.x() + movement.z() * movement.z();
        if (!(walk < 1.0E-5F) && !(moved < 1.0E-5F)) {
            double dot = wx * movement.x() + wz * movement.z();
            double angle = StrictMath.acos(dot / Math.sqrt(walk * moved));
            return angle < 0.13962634F;
        }
        return false;
    }

    /** {@code Entity.getBlockSpeedFactor} (no movement-efficiency attribute, not flying or gliding). */
    private float blockSpeedFactor() throws Colliders.Unknown {
        int feet = state(BlockPos.containing(x, y, z));
        float factor = states.speedFactor(feet);
        String block = states.block(feet);
        if (!block.equals("minecraft:water") && !block.equals("minecraft:bubble_column")) {
            return factor == 1.0 ? states.speedFactor(state(onPos(0.500001F))) : factor;
        }
        return factor;
    }

    /** {@code LivingEntity.applyEffectsFromBlocks}: no block with an effect was inside the box (checked along the move); stepping on slime is not modelled. */
    private void applyEffectsFromBlocks() throws Colliders.Unknown {
        if (onGround) {
            String block = states.block(state(onPos(0.2F)));
            if (block.equals("minecraft:slime_block")) {
                throw new Colliders.Unknown("stepping on slime (not modelled)");
            }
        }
    }

    /** {@code Player.updatePlayerPose}, with the current shift key. */
    private void updatePlayerPose() throws Colliders.Unknown {
        if (fits(PlayerState.Pose.SWIMMING)) {
            PlayerState.Pose desired = keys.shift() ? PlayerState.Pose.CROUCHING : PlayerState.Pose.STANDING;
            if (fits(desired)) {
                pose = desired;
            } else if (fits(PlayerState.Pose.CROUCHING)) {
                pose = PlayerState.Pose.CROUCHING;
            } else {
                pose = PlayerState.Pose.SWIMMING;
            }
        }
    }

    /** {@code canPlayerFitWithinBlocksAndEntitiesWhen(pose)}. */
    private boolean fits(PlayerState.Pose candidate) throws Colliders.Unknown {
        return Colliders.noCollision(world, candidate.box(x, y, z).inflate(-1.0E-7, -1.0E-7, -1.0E-7));
    }

    /** {@code moveTowardsClosestSpace} at the four corners: modelled only when no block could suffocate there. */
    private void requireNoSuffocation() throws Colliders.Unknown {
        Box box = pose.box(x, y, z);
        double w = pose.width * 0.35;
        double[][] corners = {{x - w, z + w}, {x - w, z - w}, {x + w, z - w}, {x + w, z + w}};
        for (double[] c : corners) {
            BlockPos p = BlockPos.containing(c[0], y, c[1]);
            Box column = Box.of(p.x(), box.minY(), p.z(), p.x() + 1.0, box.maxY(), p.z() + 1.0).inflate(-1.0E-7, -1.0E-7, -1.0E-7);
            if (!Colliders.noCollision(world, column)) {
                throw new Colliders.Unknown("a block where the player could suffocate (not modelled)");
            }
        }
    }

    /** {@code Entity.getOnPos(offset)}: the supporting block's column at the offset below the feet. */
    private BlockPos onPos(float offset) throws Colliders.Unknown {
        if (support != null) {
            if (!(offset > 1.0E-5F)) {
                return support;
            }
            String block = states.block(state(support));
            if (block.endsWith("_wall") || block.endsWith("_fence") || block.endsWith("_fence_gate")) {
                throw new Colliders.Unknown("standing on " + block + " (not modelled)");
            }
            return support.atY(Colliders.floor(y - offset));
        }
        return new BlockPos(Colliders.floor(x), Colliders.floor(y - offset), Colliders.floor(z));
    }

    /** No block in the cells the two boxes span holds a fluid or moves the player from inside ({@link #MOVING_INSIDE}). */
    private void requireNoEffects(Box a, Box b, String when) throws Colliders.Unknown {
        int x0 = Colliders.floor(Math.min(a.minX(), b.minX()));
        int x1 = Colliders.floor(Math.max(a.maxX(), b.maxX()));
        int y0 = Colliders.floor(Math.min(a.minY(), b.minY()));
        int y1 = Colliders.floor(Math.max(a.maxY(), b.maxY()));
        int z0 = Colliders.floor(Math.min(a.minZ(), b.minZ()));
        int z1 = Colliders.floor(Math.max(a.maxZ(), b.maxZ()));
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int s = state(new BlockPos(bx, by, bz));
                    String fluid = states.fluid(s);
                    if (fluid != null && !fluid.isEmpty() || MOVING_INSIDE.contains(states.block(s))) {
                        throw new Colliders.Unknown(states.block(s) + " inside the box " + when + " (not modelled)");
                    }
                }
            }
        }
    }

    /** {@code onClimbable()}: the feet block is climbable (a trapdoor might be). */
    private void requireNotClimbing() throws Colliders.Unknown {
        String block = states.block(state(BlockPos.containing(x, y, z)));
        if (CLIMBABLE.contains(block) || block.endsWith("_trapdoor")) {
            throw new Colliders.Unknown("climbing " + block + " (not modelled)");
        }
    }

    private int state(BlockPos pos) throws Colliders.Unknown {
        int s = world.blockState(pos.x(), pos.y(), pos.z());
        if (s == dev.whynotac.world.ClientWorld.UNKNOWN) {
            throw new Colliders.Unknown("unknown block at " + pos);
        }
        return s;
    }
}
