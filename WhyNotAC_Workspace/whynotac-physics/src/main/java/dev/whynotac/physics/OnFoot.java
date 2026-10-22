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
 * <p>The scope is a survival player on foot with default attributes but the speed ({@link Speed}) and the
 * effects the step reads ({@link Effects}), using an item or not ({@link ItemUse}),
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
     * @param speed            the movement speed attribute's value ({@code AttributeInstance.getValue})
     * @param effects          the player's active effects the step reads
     * @param use              the item the player is using during the tick, or null
     */
    public record Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled, Speed speed, Effects effects, ItemUse use) {
        public static final Conditions VANILLA_DEFAULTS = new Conditions(true, DEFAULT_SPRINT_WINDOW, false, Speed.VANILLA, Effects.NONE, null);

        public Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled) {
            this(foodAllowsSprint, sprintWindow, autoJumpEnabled, Speed.VANILLA, Effects.NONE, null);
        }

        public Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled, Speed speed) {
            this(foodAllowsSprint, sprintWindow, autoJumpEnabled, speed, Effects.NONE, null);
        }

        public Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled, Speed speed, Effects effects) {
            this(foodAllowsSprint, sprintWindow, autoJumpEnabled, speed, effects, null);
        }
    }

    /**
     * The item in use ({@code LocalPlayer.isUsingItem}): its {@code minecraft:use_effects}.
     *
     * @param canSprint       {@code UseEffects.canSprint}; false makes the use slow ({@code isSlowDueToUsingItem}): no
     *                        sprint starts and the double-tap window closes (a sprint already on goes on)
     * @param speedMultiplier {@code UseEffects.speedMultiplier}: the input is scaled by it ({@code modifyInput})
     */
    public record ItemUse(boolean canSprint, float speedMultiplier) {
        /** {@code UseEffects.DEFAULT}: what an item without its own {@code use_effects} has. */
        public static final ItemUse DEFAULT = new ItemUse(false, 0.2F);
    }

    /**
     * The active effects the on-foot step reads, each potency {@code amplifier + 1} (0: not active; the client
     * clamps the amplifier to 0..255).
     *
     * @param jumpBoost   {@code getJumpBoostPower} ({@code 0.1F} a level) and the auto-jump's reach ({@code 0.75F} a level)
     * @param levitation  {@code travelInAir}: the vertical motion pulled toward {@code 0.05} a level, in place of gravity
     * @param slowFalling {@code getEffectiveGravity}: at most {@code 0.01} while not rising; the fall distance reset
     * @param blindness   {@code isMobilityRestricted}: sprinting is not possible
     */
    public record Effects(int jumpBoost, int levitation, boolean slowFalling, boolean blindness) {
        public static final Effects NONE = new Effects(0, 0, false, false);

        public Effects {
            if (jumpBoost < 0 || jumpBoost > 256 || levitation < 0 || levitation > 256) {
                throw new IllegalArgumentException("effect level out of 0..256");
            }
        }
    }

    /** The movement speed attribute ({@code AttributeInstance}), as {@code setSprinting} changes it during the tick. */
    @FunctionalInterface
    public interface Speed {
        /** The player's base and no modifier but sprinting's: {@code 0.1F}, times {@code 1 + 0.3F} when sprinting. */
        Speed VANILLA = sprintModifier -> sprintModifier ? BASE_SPEED * (1.0 + SPRINT_MODIFIER) : BASE_SPEED;

        /** {@code getValue()}, the sprinting modifier there or not as the player's state says. */
        double value(boolean sprintModifier);

        /** {@code LivingEntity.setSprinting}: the sprinting modifier removed, then added back when sprinting. */
        default Speed setSprinting(boolean sprinting) {
            return this;
        }
    }

    /**
     * One tick's result, with what it read that its state does not say.
     *
     * @param readSprintTrigger  the tick met a forward press it could start sprinting on: what it did depended on
     *                           {@code sprintTriggerTime}; otherwise the counter only counted down (or was cleared)
     * @param readSprintWindow   the tick started the double-tap window: {@code sprintTriggerTime} was set to the option
     * @param autoJumpTriggered  with auto-jump enabled, the tick set {@code autoJumpTime} to 1 (the next tick jumps)
     * @param speed              the speed attribute after the tick's {@code setSprinting} calls
     */
    public record Step(PlayerState state, boolean readSprintTrigger, boolean readSprintWindow, boolean autoJumpTriggered, Speed speed) {}

    private final Colliders.World world;
    private final BlockStates states;
    private final Conditions conditions;

    // the player during the tick
    private double x;
    private double y;
    private double z;
    private Vec3d delta;
    private final float yRot;
    private final float xRot;
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean minorHorizontalCollision;
    private boolean verticalCollision;
    private boolean verticalCollisionBelow;
    private double fallDistance;
    private boolean sprinting;
    private boolean sprintModifier;
    private boolean crouching;
    private boolean readSprintTrigger;
    private Speed speed;
    private boolean readSprintWindow;
    private boolean autoJumpTriggered;
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
        this.speed = conditions.speed();
        x = pre.x();
        y = pre.y();
        z = pre.z();
        delta = pre.deltaMovement();
        yRot = pre.yRot();
        xRot = pre.xRot();
        onGround = pre.onGround();
        horizontalCollision = pre.horizontalCollision();
        minorHorizontalCollision = pre.minorHorizontalCollision();
        verticalCollision = pre.verticalCollision();
        verticalCollisionBelow = pre.verticalCollisionBelow();
        fallDistance = pre.fallDistance();
        sprinting = pre.sprinting();
        sprintModifier = pre.sprintModifier();
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
        return step(pre, keys, world, conditions).state();
    }

    /** {@link #tick}, with what the tick read beyond its state. */
    public static Step step(PlayerState pre, PlayerState.Keys keys, Colliders.World world, Conditions conditions) throws Colliders.Unknown {
        if (pre.pose() == PlayerState.Pose.SWIMMING) {
            throw new Colliders.Unknown("crawling");
        }
        OnFoot tick = new OnFoot(pre, world, conditions);
        tick.run(keys);
        return new Step(tick.state(), tick.readSprintTrigger, tick.readSprintWindow, tick.autoJumpTriggered, tick.speed);
    }

    /**
     * {@code LivingEntity.onClimbable} for {@code p} where it stands, as a player neither flying nor a spectator (see the
     * instance method): what {@code isPushable} asks when another entity's tick pushes the player between its ticks.
     */
    public static boolean onClimbable(PlayerState p, Colliders.World world) throws Colliders.Unknown {
        return new OnFoot(p, world, Conditions.VANILLA_DEFAULTS).onClimbable();
    }

    /**
     * Whether the step reads every one of these block states alike wherever it meets them: the same collision geometry,
     * flags and friction, speed and jump factors, none holding a fluid; a block the step or {@link Colliders} tells
     * apart by its name ({@link #namedInMovement}) only as the very same state. A block that may hold any of them then
     * moves the player as the one it holds.
     */
    public static boolean sameForMovement(BlockStates states, int[] ids) {
        int first = ids[0];
        for (int id : ids) {
            if (id == first) {
                continue;
            }
            if (namedInMovement(states.block(first)) || namedInMovement(states.block(id)) || holdsFluid(states, id)
                    || holdsFluid(states, first) || states.flags(id) != states.flags(first)
                    || !states.collisionShape(id).sameGeometry(states.collisionShape(first))
                    || Float.floatToIntBits(states.friction(id)) != Float.floatToIntBits(states.friction(first))
                    || Float.floatToIntBits(states.speedFactor(id)) != Float.floatToIntBits(states.speedFactor(first))
                    || Float.floatToIntBits(states.jumpFactor(id)) != Float.floatToIntBits(states.jumpFactor(first))) {
                return false;
            }
        }
        return true;
    }

    private static boolean holdsFluid(BlockStates states, int id) {
        String fluid = states.fluid(id);
        return fluid != null && !fluid.isEmpty();
    }

    /** The blocks the step and {@link Colliders} look up by name (or name and properties): keep in step with them. */
    static boolean namedInMovement(String block) {
        return MOVING_INSIDE.contains(block) || CLIMBABLE.contains(block) || block.equals("minecraft:slime_block") || block.endsWith("_bed")
                || block.endsWith("_trapdoor") || block.endsWith("_wall") || block.endsWith("_fence") || block.endsWith("_fence_gate")
                || block.equals("minecraft:water") || block.equals("minecraft:lava") || block.equals("minecraft:moving_piston");
    }

    private PlayerState state() {
        return new PlayerState(x, y, z, delta, yRot, xRot, onGround, horizontalCollision, minorHorizontalCollision, verticalCollision,
                verticalCollisionBelow, fallDistance, sprinting, sprintModifier, crouching, pose, keys, noJumpDelay, sprintTriggerTime, autoJumpTime, support,
                onGroundNoBlocks, stuck);
    }

    private void run(PlayerState.Keys polled) throws Colliders.Unknown {
        // baseTick's fluid checks use the box deflated by 0.001
        Box start = pose.box(x, y, z);
        requireNoEffects(start.inflate(-0.001, -0.001, -0.001), start, "at the start of the tick");
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
        moveTowardsClosestSpace(); // !noPhysics
        boolean slowDueToUse = conditions.use() != null && !conditions.use().canSprint(); // isSlowDueToUsingItem
        if (previousShift || slowDueToUse || keys.backward()) {
            sprintTriggerTime = 0;
        }
        boolean forward = moveY > 1.0E-5F;
        boolean movingSlowly = crouching;
        boolean sprintPossible = !conditions.effects().blindness() && conditions.foodAllowsSprint(); // isSprintingPossible
        if (!sprinting && forward && sprintPossible && !slowDueToUse && !movingSlowly) { // canStartSprinting
            if (!previousForward) {
                readSprintTrigger = true;
                if (sprintTriggerTime > 0) {
                    setSprinting(true);
                } else {
                    sprintTriggerTime = conditions.sprintWindow();
                    readSprintWindow = true;
                }
            }
            if (keys.sprint()) {
                setSprinting(true);
            }
        }
        if (sprinting && (!sprintPossible || !forward || horizontalCollision && !minorHorizontalCollision)) { // shouldStopRunSprinting
            setSprinting(false);
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
        if (conditions.effects().slowFalling() || conditions.effects().levitation() > 0) {
            fallDistance = 0.0; // resetFallDistance
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

    /** {@code LivingEntity.setSprinting}: the flag and the attribute's sprinting modifier together. */
    private void setSprinting(boolean value) throws Colliders.Unknown {
        sprinting = value;
        sprintModifier = value;
        speed = speed.setSprinting(value);
        if (speed == null) {
            throw new Colliders.Unknown("more speed modifiers than the client's table holds unresized");
        }
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
            if (conditions.use() != null) {
                vx *= conditions.use().speedMultiplier();
                vy *= conditions.use().speedMultiplier();
            }
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
        int jumpBoost = conditions.effects().jumpBoost();
        float power = JUMP_STRENGTH * 1.0F * blockJumpFactor() + (jumpBoost > 0 ? 0.1F * (float) jumpBoost : 0.0F);
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
        // handleRelativeFrictionAndCalculateMovement
        delta = delta.add(Vec3d.inputVector(input, frictionInfluencedSpeed(friction), yRot));
        delta = handleOnClimbable(delta);
        Box before = pose.box(x, y, z);
        move(delta);
        // checkInsideBlocks sweeps the box (deflated by 1.0E-5F) along the move
        requireNoEffects(before.inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), pose.box(x, y, z).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), "along the move");
        Vec3d moved = delta;
        if ((horizontalCollision || jumping) && onClimbable()) { // asked again after the move, at the new feet block
            moved = new Vec3d(moved.x(), 0.2, moved.z());
        }
        double vy = moved.y();
        int levitation = conditions.effects().levitation();
        if (levitation > 0) {
            vy += (0.05 * levitation - moved.y()) * 0.2;
        } else if (world.isLoaded(below.x() >> 4, below.z() >> 4)) {
            vy -= moved.y() <= 0.0 && conditions.effects().slowFalling() ? Math.min(GRAVITY, 0.01) : GRAVITY; // getEffectiveGravity
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
            return speed() * (0.21600002F / (friction * friction * friction));
        }
        return sprinting ? 0.025999999F : 0.02F;
    }

    /** {@code Player.getSpeed()}: the attribute's value as a float. */
    private float speed() {
        return (float) speed.value(sprintModifier);
    }

    /** {@code LocalPlayer.move(MoverType.SELF, movement)}: {@code Entity.move}, then auto-jump on the horizontal step taken. */
    private void move(Vec3d movement) throws Colliders.Unknown {
        double startX = x;
        double startZ = z;
        entityMove(movement);
        if (conditions.autoJumpEnabled()) {
            updateAutoJump((float) (x - startX), (float) (z - startZ));
        }
    }

    /**
     * {@code LocalPlayer.updateAutoJump}: walking at a step between 0.5 and 1.2 high (no jump boost in scope)
     * with room above the head, the next tick jumps.
     */
    private void updateAutoJump(float stepX, float stepZ) throws Colliders.Unknown {
        boolean moving = moveX * moveX + moveY * moveY > 0.0F; // input.getMoveVector(): normalised, not modified
        if (!(autoJumpTime <= 0 && onGround && !keys.shift() && moving && blockJumpFactor() >= 1.0)) { // canAutoJump
            return;
        }
        Vec3d position = new Vec3d(x, y, z);
        Vec3d target = position.add(stepX, 0.0, stepZ);
        Vec3d direction = new Vec3d(stepX, 0.0, stepZ);
        float speed = speed();
        float lengthSqr = (float) direction.lengthSqr();
        if (lengthSqr <= 0.001F) {
            float ix = speed * moveX;
            float iz = speed * moveY;
            float sin = VanillaMath.sin(yRot * VanillaMath.DEG_TO_RAD);
            float cos = VanillaMath.cos(yRot * VanillaMath.DEG_TO_RAD);
            direction = new Vec3d(ix * cos - iz * sin, direction.y(), iz * cos + ix * sin);
            lengthSqr = (float) direction.lengthSqr();
            if (lengthSqr <= 0.001F) {
                return;
            }
        }
        float inverse = 1.0F / (float) Math.sqrt(lengthSqr); // Mth.invSqrt: org.joml.Math.invsqrt
        Vec3d unit = direction.scale(inverse);
        Vec3d forward = Vec3d.directionFromRotation(xRot, yRot);
        float facing = (float) (forward.x() * unit.x() + forward.z() * unit.z());
        if (facing < -0.15F) {
            return;
        }
        Box box = pose.box(x, y, z);
        BlockPos head = BlockPos.containing(x, box.maxY(), z);
        if (!collisionShape(head).isEmpty()) {
            return;
        }
        head = head.above();
        if (!collisionShape(head).isEmpty()) {
            return;
        }
        float maxRise = 1.2F;
        if (conditions.effects().jumpBoost() > 0) {
            maxRise += conditions.effects().jumpBoost() * 0.75F;
        }
        float reach = Math.max(speed * 7.0F, 1.0F / inverse);
        Vec3d from = position;
        Vec3d to = target.add(unit.scale(reach));
        float width = pose.width;
        float height = pose.height;
        Box area = Box.of(from.x(), from.y(), from.z(), to.x(), to.y() + height, to.z()).inflate(width, 0.0, width);
        from = from.add(0.0, 0.51F, 0.0);
        to = to.add(0.0, 0.51F, 0.0);
        Vec3d side = unit.cross(new Vec3d(0.0, 1.0, 0.0));
        Vec3d half = side.scale(width * 0.5F);
        Vec3d leftFrom = from.subtract(half);
        Vec3d leftTo = to.subtract(half);
        Vec3d rightFrom = from.add(half);
        Vec3d rightTo = to.add(half);
        float top = Float.MIN_VALUE;
        List<Voxels> colliders = new java.util.ArrayList<>();
        Colliders.blocks(world, area, colliders); // level.getCollisions: no solid entity in scope
        search:
        for (Voxels shape : colliders) {
            for (int i = 0; i < shape.boxCount(); i++) { // VoxelShape.toAabbs
                Box b = shape.box(i);
                if (b.intersects(leftFrom, leftTo) || b.intersects(rightFrom, rightTo)) {
                    top = (float) b.maxY();
                    BlockPos centre = BlockPos.containing(VanillaMath.lerp(0.5, b.minX(), b.maxX()), VanillaMath.lerp(0.5, b.minY(), b.maxY()),
                            VanillaMath.lerp(0.5, b.minZ(), b.maxZ()));
                    for (int up = 1; up < maxRise; up++) {
                        BlockPos above = centre.atY(centre.y() + up);
                        Voxels aboveShape = collisionShape(above);
                        if (!aboveShape.isEmpty()) {
                            top = (float) aboveShape.max(Axis.Y) + above.y();
                            if (top - y > maxRise) {
                                return;
                            }
                        }
                        if (up > 1) {
                            head = head.above();
                            if (!collisionShape(head).isEmpty()) {
                                return;
                            }
                        }
                    }
                    break search;
                }
            }
        }
        if (top != Float.MIN_VALUE) {
            float rise = (float) (top - y);
            if (!(rise <= 0.5F) && !(rise > maxRise)) {
                autoJumpTime = 1;
                autoJumpTriggered = true;
            }
        }
    }

    /** {@code blockState.getCollisionShape(level, pos, CollisionContext.of(player))}, unmoved. */
    private Voxels collisionShape(BlockPos pos) throws Colliders.Unknown {
        return Voxels.of(Colliders.playerShape(states, state(pos), pos.x(), pos.y(), pos.z()));
    }

    /** {@code Entity.move(MoverType.SELF, movement)} for the local player. */
    private void entityMove(Vec3d movement) throws Colliders.Unknown {
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
            updateEntityMovementAfterFallOn(states.block(legacyState));
        }
        float speedFactor = blockSpeedFactor();
        delta = delta.multiply(speedFactor, 1.0, speedFactor);
    }

    /**
     * {@code Block.updateEntityMovementAfterFallOn} for the block below the feet ({@code getOnPosLegacy}), after a vertical
     * collision: the vertical motion stops; on a slime block or a bed a falling player bounces instead
     * ({@code bounceUp}: the motion reversed, times 0.66 on a bed; a living entity's factor 1.0), unless shift
     * suppresses it ({@code isSuppressingBounce}). Bouncing blocks leave an upward motion as it is.
     */
    private void updateEntityMovementAfterFallOn(String block) {
        boolean slime = block.equals("minecraft:slime_block");
        boolean bed = block.endsWith("_bed");
        if (!(slime || bed) || keys.shift()) {
            delta = delta.multiply(1.0, 0.0, 1.0);
            return;
        }
        if (delta.y() < 0.0) {
            double living = 1.0;
            delta = new Vec3d(delta.x(), slime ? -delta.y() * living : -delta.y() * 0.66F * living, delta.z());
        }
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
        if (!onGround && Double.isNaN(fallDistance)) {
            throw new Colliders.Unknown("the fall distance is not known");
        }
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

    /**
     * {@code Entity.applyEffectsFromBlocks}: on the ground, the block below the feet's {@code stepOn} (a slime block slows
     * a player not sneaking while its vertical motion is under 0.1: {@code 0.4 + |y| * 0.2} horizontally; no other block's
     * changes the motion); no block with an effect was inside the box (checked along the move).
     */
    private void applyEffectsFromBlocks() throws Colliders.Unknown {
        if (onGround) {
            String block = states.block(state(onPos(0.2F)));
            if (block.equals("minecraft:slime_block")) {
                double vy = Math.abs(delta.y());
                if (vy < 0.1 && !keys.shift()) { // isSteppingCarefully: isShiftKeyDown
                    double factor = 0.4 + vy * 0.2;
                    delta = delta.multiply(factor, 1.0, factor);
                }
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
    /**
     * {@code LocalPlayer.moveTowardsClosestSpace} at the box's four corners (0.35 of the width out), in the client's
     * order: a corner whose column suffocates pushes the player 0.1 toward the nearest free side.
     */
    private void moveTowardsClosestSpace() throws Colliders.Unknown {
        double w = pose.width * 0.35;
        moveTowardsClosestSpace(x - w, z + w);
        moveTowardsClosestSpace(x - w, z - w);
        moveTowardsClosestSpace(x + w, z - w);
        moveTowardsClosestSpace(x + w, z + w);
    }

    private void moveTowardsClosestSpace(double px, double pz) throws Colliders.Unknown {
        BlockPos pos = BlockPos.containing(px, y, pz);
        if (!suffocatesAt(pos.x(), pos.z())) {
            return;
        }
        double dx = px - pos.x();
        double dz = pz - pos.z();
        // Direction.WEST, EAST, NORTH, SOUTH: the distance to that side of the cell, if the cell beyond is free
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        int[] best = null;
        double min = Double.MAX_VALUE;
        for (int[] d : directions) {
            double v = d[0] != 0 ? dx : dz;
            double distance = d[0] + d[1] > 0 ? 1.0 - v : v;
            if (distance < min && !suffocatesAt(pos.x() + d[0], pos.z() + d[1])) {
                min = distance;
                best = d;
            }
        }
        if (best != null) {
            delta = best[0] != 0 ? new Vec3d(0.1 * best[0], delta.y(), delta.z()) : new Vec3d(delta.x(), delta.y(), 0.1 * best[1]);
        }
    }

    /** {@code LocalPlayer.suffocatesAt}: the cell's column, the box's height, deflated by {@code 1.0E-7}. */
    private boolean suffocatesAt(int cx, int cz) throws Colliders.Unknown {
        Box box = pose.box(x, y, z);
        Box column = Box.of(cx, box.minY(), cz, cx + 1.0, box.maxY(), cz + 1.0).inflate(-1.0E-7, -1.0E-7, -1.0E-7);
        return Colliders.collidesWithSuffocatingBlock(world, column);
    }

    /**
     * {@code Entity.getOnPos(offset)}: the supporting block's column at the offset below the feet; the supporting block
     * itself where it is a fence and the offset at most 0.5, a wall or a fence gate ({@code BlockTags.FENCES},
     * {@code BlockTags.WALLS}, {@code FenceGateBlock}: in 1.21.11 exactly the blocks named so).
     */
    private BlockPos onPos(float offset) throws Colliders.Unknown {
        if (support != null) {
            if (!(offset > 1.0E-5F)) {
                return support;
            }
            String block = states.block(state(support));
            boolean fence = block.endsWith("_fence");
            boolean wallOrGate = block.endsWith("_wall") || block.endsWith("_fence_gate");
            return (!((double) offset <= 0.5) || !fence) && !wallOrGate ? support.atY(Colliders.floor(y - offset)) : support;
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

    /**
     * {@code LivingEntity.onClimbable} for a player neither flying nor a spectator: the block at the feet
     * ({@code getInBlockState}) is climbable, or an open trapdoor over a ladder facing the same way
     * ({@code trapdoorUsableAsLadder}). Scaffolding is not followed: its shape depends on the player.
     */
    private boolean onClimbable() throws Colliders.Unknown {
        BlockPos feet = BlockPos.containing(x, y, z);
        int s = state(feet);
        String block = states.block(s);
        if (block.equals("minecraft:scaffolding")) {
            throw new Colliders.Unknown("scaffolding at the feet (not modelled)");
        }
        if (CLIMBABLE.contains(block)) {
            return true;
        }
        if (block.endsWith("_trapdoor") && property(s, "open").equals("true")) {
            int below = state(new BlockPos(feet.x(), feet.y() - 1, feet.z()));
            return states.block(below).equals("minecraft:ladder") && property(below, "facing").equals(property(s, "facing"));
        }
        return false;
    }

    /** {@code handleOnClimbable}: on a climbable block the motion is held to 0.15 a tick, and shift holds the player still. */
    private Vec3d handleOnClimbable(Vec3d d) throws Colliders.Unknown {
        if (!onClimbable()) {
            return d;
        }
        fallDistance = 0.0; // resetFallDistance
        double cx = VanillaMath.clamp(d.x(), -0.15F, 0.15F);
        double cz = VanillaMath.clamp(d.z(), -0.15F, 0.15F);
        double cy = Math.max(d.y(), -0.15F);
        if (cy < 0.0 && keys.shift()) { // isSuppressingSlidingDownLadder: isShiftKeyDown (scaffolding excluded above)
            cy = 0.0;
        }
        return new Vec3d(cx, cy, cz);
    }

    /** A block state's property value ({@code key=value,...}), or empty. */
    private String property(int state, String key) {
        for (String kv : states.properties(state).split(",")) {
            if (kv.startsWith(key + "=")) {
                return kv.substring(key.length() + 1);
            }
        }
        return "";
    }

    private int state(BlockPos pos) throws Colliders.Unknown {
        int s = world.blockState(pos.x(), pos.y(), pos.z());
        if (s == dev.whynotac.world.ClientWorld.UNKNOWN) {
            throw new Colliders.Unknown("unknown block at " + pos);
        }
        return s;
    }
}
