package dev.whynotac.physics;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code AbstractHorse.tick()} of a horse (donkey, mule, skeleton or zombie horse) the local player rides and
 * controls (saddled, the player its first passenger: {@code getControllingPassenger}), bit for bit as the 1.21.11 client
 * runs it; a pig's ({@code Mob.tick}), a strider's ({@code Strider.tick}, {@link Strider}) and a happy ghast's
 * ({@code HappyGhast.tick}, {@link Flying}) alike. The tick runs
 * before its rider's: {@code Entity.baseTick} (fluid currents, a fall in lava halved), then
 * {@code LivingEntity.aiStep}: the interpolation's step toward where the server last put it, the motion's small parts
 * zeroed (a non-player's: each horizontal axis below 0.003), and {@code travelRidden}: the rider's input
 * ({@code getRiddenInput}: half its strafe, a quarter of a backward walk, nothing while rearing on the ground),
 * {@code tickRidden} (the rider's yaw and half its pitch; on the ground the jump the rider released, {@code
 * executeRidersJump}) and {@code travel} with the movement speed attribute ({@code getRiddenSpeed}): in water or lava
 * ({@code travelInFluid}, a horse that floats while ridden lifted by 0.04 in deep water) or in the air
 * ({@code travelInAir}: friction, the input, {@code Entity.move} with the step height, gravity and drag); then the
 * blocks it stands on and passed through ({@code applyEffectsFromBlocks}). Last the rearing the client started runs
 * down ({@code standCounter}, its end clearing the flag), and a tick that ends not rearing clears
 * {@code allowStandSliding}.
 *
 * <p>The scope is a horse whose attributes the caller gives ({@link Conditions}), away from blocks it does not model
 * ({@link Colliders.Unknown}). Sources: the decompiled client, {@code AbstractHorse}, {@code LivingEntity} and
 * {@code Entity}.
 */
public final class Horse {

    /** {@code BlockTags.CLIMBABLE} ({@link OnFoot#CLIMBABLE}): a horse climbs too. */
    static final java.util.Set<String> CLIMBABLE = OnFoot.CLIMBABLE;
    /** {@code EntityTypeTags.CAN_FLOAT_WHILE_RIDDEN} (vanilla): ridden in deep water, these rise ({@code floatInWaterWhileRidden}). */
    public static final java.util.Set<String> FLOATS_WHILE_RIDDEN = java.util.Set.of("minecraft:horse", "minecraft:zombie_horse", "minecraft:mule",
            "minecraft:donkey", "minecraft:camel", "minecraft:camel_husk");
    /** Blocks whose {@code entityInside} changes a horse's motion on the client ({@link OnFoot#MOVING_INSIDE}). */
    static final java.util.Set<String> MOVING_INSIDE = OnFoot.MOVING_INSIDE;

    /**
     * What its tick reads of its rider: {@code xxa}, {@code zza} and {@code jumping} (as its last tick left them), its
     * rotation now.
     */
    public record Rider(float xxa, float zza, float yRot, float xRot, boolean jumping) {
        public Rider(float xxa, float zza, float yRot, float xRot) {
            this(xxa, zza, yRot, xRot, false);
        }
    }

    /** The effects on the horse the tick reads: jump boost's level (0: none), slow falling, levitation's level, dolphin's grace. */
    public record Effects(int jumpBoost, boolean slowFalling, int levitation, boolean dolphinsGrace) {
        public static final Effects NONE = new Effects(0, false, 0, false);
    }

    /**
     * What the tick needs beyond the horse and the world.
     *
     * @param speed             {@code getRiddenSpeed}: the movement speed attribute's value, as a float
     * @param jumpStrength      the jump strength attribute's value
     * @param stepHeight        {@code maxUpStep}: the step height attribute's value, at least 1 under a player
     * @param gravity           {@code getGravity}: the gravity attribute's value, 0 with no gravity
     * @param waterSlowDown     {@code getWaterSlowDown}: 0.8, a skeleton horse's 0.96
     * @param floatsWhileRidden its type is in {@code CAN_FLOAT_WHILE_RIDDEN}
     * @param width             its box, as its type and age size it
     * @param border            the client's world border at the move ({@link Border}); null when it is far
     * @param entities          the boxes of the entities it collides with ({@code canCollideWith}), as the client holds them
     * @param strider           a strider's own tick ({@link Strider}), or null
     * @param flying            a happy ghast's ({@link Flying}), or null
     */
    public record Conditions(float speed, double jumpStrength, float stepHeight, double gravity, Effects effects, float waterSlowDown,
            boolean floatsWhileRidden, float width, float height, Border border, List<Box> entities, Strider strider, Flying flying) {
        public Conditions {
            entities = List.copyOf(entities);
        }

        public Conditions(float speed, double jumpStrength, float stepHeight, double gravity, Effects effects, float waterSlowDown,
                boolean floatsWhileRidden, float width, float height, Border border, List<Box> entities, Strider strider) {
            this(speed, jumpStrength, stepHeight, gravity, effects, waterSlowDown, floatsWhileRidden, width, height, border, entities, strider, null);
        }

        public Conditions(float speed, double jumpStrength, float stepHeight, double gravity, Effects effects, float waterSlowDown,
                boolean floatsWhileRidden, float width, float height, Border border, List<Box> entities) {
            this(speed, jumpStrength, stepHeight, gravity, effects, waterSlowDown, floatsWhileRidden, width, height, border, entities, null, null);
        }
    }

    /**
     * A happy ghast ({@code HappyGhast}): it flies where its rider steers ({@code getRiddenInput}: the rider's strafe, ahead
     * along the rider's pitch, backward half as fast, 0.5 up while it jumps, all times 3.9 times the flying speed attribute),
     * turns 8% of the way to its rider's yaw each tick ({@code tickRidden}), and moves with no gravity
     * ({@code travelFlying}: the same speed in water, lava and air, then 0.8, 0.5 or 0.91 of its motion); its fall is never
     * counted ({@code checkFallDamage} does nothing).
     *
     * @param speed      {@code (float) flying speed * 5 / 3}
     * @param inputScale {@code 3.9F * flying speed}
     */
    public record Flying(float speed, double inputScale) {
        public static Flying of(double flyingSpeed) {
            return new Flying((float) flyingSpeed * 5.0F / 3.0F, 3.9F * flyingSpeed);
        }
    }

    /**
     * A strider ({@code Strider}): it stands on lava ({@code canStandOnFluid}: lava's stable shape holds it, and on lava it
     * travels as on land), and its tick ({@code Strider.tick}) sets its suffocation first and floats it in lava last
     * ({@code floatStrider}). Its ridden speed ({@code getRiddenSpeed}) is the movement speed attribute times 0.55, or
     * times 0.35 with the attribute's suffocating modifier, and its boost's factor.
     *
     * @param speed            {@code getRiddenSpeed} not suffocating
     * @param suffocatingSpeed {@code getRiddenSpeed} suffocating
     * @param warmBlocks       {@code BlockTags.STRIDER_WARM_BLOCKS}: a strider at or above one, or in lava its last tick, is
     *                         not suffocating; null for a NoAI strider, whose client never sets it (the state's flag stands)
     */
    public record Strider(float speed, float suffocatingSpeed, java.util.Set<String> warmBlocks) {}

    private final Colliders.World world;
    private final BlockStates states;
    private final Conditions conditions;

    private double x;
    private double y;
    private double z;
    private Vec3d delta;
    private float yRot;
    private float xRot;
    private boolean onGround;
    private boolean horizontalCollision;
    private boolean verticalCollision;
    private boolean verticalCollisionBelow;
    private double fallDistance;
    private BlockPos support;
    private boolean onGroundNoBlocks;
    private Vec3d stuck;
    private boolean inPowderSnow;
    private boolean wasInPowderSnow;
    private int noJumpDelay;
    private float jumpPendingScale;
    private boolean allowStandSliding;
    private boolean standing;
    private int standCounter;
    private BoatState.Interpolation interpolation;
    private boolean wasTouchingWater;
    private double waterHeight;
    private double lavaHeight;
    private boolean suffocating;
    /** {@code getSpeed()}: what {@code travelRidden} set ({@code getRiddenSpeed}). */
    private float speed;
    /** {@code oldPosition()}: where the tick started ({@code setOldPosAndRot}). */
    private final Vec3d oldPosition;
    /** {@code movementThisTick}. */
    private final ArrayList<Terrain.Movement> movements = new ArrayList<>(2);

    private Horse(HorseState pre, Colliders.World world, Conditions conditions) {
        this.world = world;
        this.states = world.states();
        this.conditions = conditions;
        x = pre.x();
        y = pre.y();
        z = pre.z();
        delta = pre.deltaMovement();
        yRot = pre.yRot();
        xRot = pre.xRot();
        onGround = pre.onGround();
        horizontalCollision = pre.horizontalCollision();
        verticalCollision = pre.verticalCollision();
        verticalCollisionBelow = pre.verticalCollisionBelow();
        fallDistance = pre.fallDistance();
        support = pre.support();
        onGroundNoBlocks = pre.onGroundNoBlocks();
        stuck = pre.stuckSpeedMultiplier();
        inPowderSnow = pre.inPowderSnow();
        noJumpDelay = pre.noJumpDelay();
        jumpPendingScale = pre.jumpPendingScale();
        allowStandSliding = pre.allowStandSliding();
        standing = pre.standing();
        standCounter = pre.standCounter();
        interpolation = pre.interpolation();
        lavaHeight = pre.lavaHeight();
        suffocating = pre.suffocating();
        speed = conditions.speed();
        oldPosition = new Vec3d(x, y, z);
    }

    /** The horse after one client tick under {@code rider}. */
    public static HorseState tick(HorseState pre, Rider rider, Colliders.World world, Conditions conditions) throws Colliders.Unknown {
        Horse tick = new Horse(pre, world, conditions);
        tick.run(rider);
        return tick.state();
    }

    /**
     * {@code PlayerRideableJumping.getPlayerJumpPendingScale}: the scale a released riding jump of {@code power} (the
     * rider's {@code jumpRidingScale} times 100, floored) sets.
     */
    public static float jumpPendingScale(int power) {
        return power >= 90 ? 1.0F : 0.4F + 0.4F * power / 90.0F;
    }

    /**
     * The input a passenger's tick leaves for the horse's next ({@code LocalPlayer.applyInput}): the move vector of its
     * keys, through {@code modifyInput}, which slows it for neither an item in use nor sneaking while it rides (both
     * need {@code !isPassenger}). Returns {xxa, zza}.
     */
    public static float[] riderInput(PlayerState.Keys keys) {
        float[] v = OnFoot.impulses(keys);
        return OnFoot.modifyInput(v[0], v[1], null, false, OnFoot.SNEAKING_SPEED);
    }

    /**
     * {@code AbstractHorse.onPlayerJump(power)} on a saddled horse, as the rider's tick releases a riding jump
     * ({@code sendRidingJump} sends the power): a jump from then on lets a rearing horse slide, it rears
     * ({@code standIfPossible}: {@code setStanding(20)}, on the client only when it runs its own AI, {@code
     * effectiveAi}: not a NoAI horse) and the jump waits for the next tick on the ground.
     */
    public static HorseState onPlayerJump(HorseState s, int power, boolean effectiveAi) {
        boolean slide = s.allowStandSliding();
        boolean standing = s.standing();
        int standCounter = s.standCounter();
        if (power < 0) {
            power = 0;
        } else {
            slide = true;
            if (effectiveAi) {
                standing = true;
                standCounter = 20;
            }
        }
        return new HorseState(s.x(), s.y(), s.z(), s.deltaMovement(), s.yRot(), s.xRot(), s.onGround(), s.horizontalCollision(), s.verticalCollision(),
                s.verticalCollisionBelow(), s.fallDistance(), s.support(), s.onGroundNoBlocks(), s.stuckSpeedMultiplier(), s.inPowderSnow(),
                s.noJumpDelay(), jumpPendingScale(power), slide, standing, standCounter, s.interpolation(), s.lavaHeight(), s.suffocating());
    }

    /**
     * {@code Entity.checkSupportingBlock(true, null)} for a horse at {@code (x, y, z)} ({@code setOnGround(true)} from a
     * packet): the supporting block below its box, null when there is none.
     */
    public static BlockPos supportingBlock(double x, double y, double z, Colliders.World world, Conditions conditions) throws Colliders.Unknown {
        Box box = HorseState.box(x, y, z, conditions.width(), conditions.height());
        Box below = Box.of(box.minX(), box.minY() - 1.0E-6, box.minZ(), box.maxX(), box.minY(), box.maxZ());
        return Colliders.supportingBlock(world, new Colliders.Context(y, false, x, z, conditions.border(), 0.0, Boolean.FALSE, conditions.strider() != null),
                below, x, y, z);
    }

    private HorseState state() {
        return new HorseState(x, y, z, delta, yRot, xRot, onGround, horizontalCollision, verticalCollision, verticalCollisionBelow, fallDistance, support,
                onGroundNoBlocks, stuck, inPowderSnow, noJumpDelay, jumpPendingScale, allowStandSliding, standing, standCounter, interpolation,
                lavaHeight, suffocating);
    }

    private void run(Rider rider) throws Colliders.Unknown {
        Box start = box();
        requireInScope(start, start, "at the start of the tick");
        Strider strider = conditions.strider();
        if (strider != null) {
            // --- Strider.tick: not NoAI, the client sets its suffocation from the blocks at and under it (getBlockStateOnLegacy)
            // and the lava its last baseTick found; its speed modifier follows ---
            if (strider.warmBlocks() != null) {
                suffocating = !(strider.warmBlocks().contains(states.block(state(BlockPos.containing(x, y, z))))
                        || strider.warmBlocks().contains(states.block(state(onPos(0.2F)))) || lavaHeight > 0.0);
            }
            speed = suffocating ? strider.suffocatingSpeed() : strider.speed();
        }
        // --- Entity.baseTick: powder snow's flags, water's current (in water the fall is forgotten), then lava's ---
        wasInPowderSnow = inPowderSnow;
        inPowderSnow = false;
        waterHeight = 0.0; // fluidHeight.clear()
        lavaHeight = 0.0;
        updateInWaterStateAndDoWaterCurrentPushing();
        Boolean fast = world.fastLava();
        updateFluidHeightAndDoFluidPushing(BlockStates.FLUID_LAVA, fast == null ? Double.NaN : fast ? 0.007 : 0.0023333333333333335);
        if (lavaHeight > 0.0) { // isInLava (not a new entity's first tick)
            fallDistance *= 0.5;
        }
        // --- LivingEntity.aiStep ---
        if (noJumpDelay > 0) {
            noJumpDelay--;
        }
        interpolate();
        double dx = Math.abs(delta.x()) < 0.003 ? 0.0 : delta.x(); // not a player's: each axis alone
        double dy = Math.abs(delta.y()) < 0.003 ? 0.0 : delta.y();
        double dz = Math.abs(delta.z()) < 0.003 ? 0.0 : delta.z();
        delta = new Vec3d(dx, dy, dz);
        noJumpDelay = 0; // its own jumping is never set on the client
        if (conditions.effects().slowFalling() || conditions.effects().levitation() > 0) {
            fallDistance = 0.0; // resetFallDistance
        }
        Flying flying = conditions.flying();
        if (flying != null) {
            // travelRidden: HappyGhast.getRiddenInput, tickRidden, then travel (travelFlying)
            float up = 0.0F;
            float ahead = 0.0F;
            if (rider.zza() != 0.0F) {
                float cos = VanillaMath.cos(rider.xRot() * VanillaMath.DEG_TO_RAD);
                float sin = -VanillaMath.sin(rider.xRot() * VanillaMath.DEG_TO_RAD);
                if (rider.zza() < 0.0F) {
                    cos *= -0.5F;
                    sin *= -0.5F;
                }
                up = sin;
                ahead = cos;
            }
            if (rider.jumping()) {
                up += 0.5F;
            }
            Vec3d steered = new Vec3d(rider.xxa(), up, ahead).scale(flying.inputScale());
            float turned = yRot + VanillaMath.wrapDegrees(rider.yRot() - yRot) * 0.08F;
            setRot(turned, rider.xRot() * 0.5F);
            travelFlying(steered, flying.speed());
            applyEffectsFromBlocks();
            return;
        }
        // travelRidden: getRiddenInput, tickRidden, then travel at the ridden speed
        Vec3d input;
        if (onGround && jumpPendingScale == 0.0F && standing && !allowStandSliding) {
            input = Vec3d.ZERO;
        } else {
            float strafe = rider.xxa() * 0.5F;
            float forward = rider.zza();
            if (forward <= 0.0F) {
                forward *= 0.25F;
            }
            input = new Vec3d(strafe, 0.0, forward);
        }
        setRot(rider.yRot(), rider.xRot() * 0.5F); // getRiddenRotation
        if (onGround) {
            if (jumpPendingScale > 0.0F) { // && !isJumping(): never on the client
                executeRidersJump(jumpPendingScale, input);
            }
            jumpPendingScale = 0.0F;
        }
        // shouldTravelInFluid: in water or lava, unless it stands on the fluid where it is (a strider on lava)
        boolean standsOnFluid = strider != null && states.fluidKind(state(BlockPos.containing(x, y, z))) == BlockStates.FLUID_LAVA;
        if ((wasTouchingWater || lavaHeight > 0.0) && !standsOnFluid) {
            travelInFluid(input);
        } else {
            travelInAir(input);
        }
        applyEffectsFromBlocks();
        // --- AbstractHorse.tick: the client's rearing runs down (clearStanding at its end); a tick not rearing ends the sliding ---
        if (standCounter > 0 && --standCounter <= 0) {
            standing = false;
            standCounter = 0;
        }
        if (!standing) {
            allowStandSliding = false;
        }
        if (strider != null && lavaHeight > 0.0) {
            // --- Strider.floatStrider (isInLava): above lava's stable shape with no lava over it, on the ground; else lifted ---
            BlockPos at = BlockPos.containing(x, y, z);
            if (y > at.y() + 0.5 - 1.0E-5F && states.fluidKind(state(at.above())) != BlockStates.FLUID_LAVA) {
                onGround = true; // setOnGround: checkSupportingBlock(true, null)
                checkSupportingBlock(null);
            } else {
                delta = delta.scale(0.5).add(0.0, 0.05, 0.0);
            }
        }
    }

    /**
     * {@code AbstractHorse.executeRidersJump}: the jump's power ({@code getJumpPower(scale)}: the attribute times the scale
     * and the block's jump factor, plus jump boost's) as the vertical motion, and walking forward a push of 0.4 times the
     * scale ahead.
     */
    private void executeRidersJump(float scale, Vec3d input) throws Colliders.Unknown {
        float boost = conditions.effects().jumpBoost() > 0 ? 0.1F * conditions.effects().jumpBoost() : 0.0F; // 0.1 * (amplifier + 1)
        double power = (float) conditions.jumpStrength() * scale * blockJumpFactor() + boost;
        delta = new Vec3d(delta.x(), power, delta.z());
        if (input.z() > 0.0) {
            float sin = VanillaMath.sin(yRot * VanillaMath.DEG_TO_RAD);
            float cos = VanillaMath.cos(yRot * VanillaMath.DEG_TO_RAD);
            delta = delta.add(-0.4F * sin * scale, 0.0, 0.4F * cos * scale);
        }
    }

    /** {@code Entity.getBlockJumpFactor}: the block at the feet, else the one below that affects movement. */
    private float blockJumpFactor() throws Colliders.Unknown {
        float atFeet = states.jumpFactor(state(BlockPos.containing(x, y, z)));
        return atFeet == 1.0 ? states.jumpFactor(state(onPos(0.500001F))) : atFeet;
    }

    /** {@code LivingEntity.travelInAir}, through {@code handleRelativeFrictionAndCalculateMovement}. */
    private void travelInAir(Vec3d input) throws Colliders.Unknown {
        BlockPos below = onPos(0.500001F);
        float friction = onGround ? states.friction(state(below)) : 1.0F;
        float drag = friction * 0.91F;
        float flyingSpeed = onGround ? speed * (0.21600002F / (friction * friction * friction)) : speed * 0.1F; // getFlyingSpeed
        delta = delta.add(Vec3d.inputVector(input, flyingSpeed, yRot));
        delta = handleOnClimbable(delta);
        Box before = box();
        move(delta);
        requireInScope(before, box(), "along the move");
        Vec3d after = delta;
        if (horizontalCollision && onClimbable()) { // its own jumping is never set; it walks on no powder snow
            after = new Vec3d(after.x(), 0.2, after.z());
        }
        double vy = after.y();
        int levitation = conditions.effects().levitation();
        if (levitation > 0) {
            vy += (0.05 * levitation - after.y()) * 0.2;
        } else if (world.isLoaded(below.x() >> 4, below.z() >> 4)) {
            vy -= effectiveGravity(delta.y() <= 0.0); // the motion the move left, not the climb's 0.2
        } else {
            throw new Colliders.Unknown("movement in an unloaded chunk");
        }
        delta = new Vec3d(after.x() * drag, vy * 0.98F, after.z() * drag);
    }

    /** {@code LivingEntity.travelFlying(input, speed, speed, speed)}: no gravity; in water, lava or air its own drag. */
    private void travelFlying(Vec3d input, float speed) throws Colliders.Unknown {
        Box before = box();
        delta = delta.add(Vec3d.inputVector(input, speed, yRot)); // moveRelative
        move(delta);
        requireInScope(before, box(), "along the move");
        if (wasTouchingWater) {
            delta = delta.scale(0.8F);
        } else if (lavaHeight > 0.0) { // isInLava
            delta = delta.scale(0.5);
        } else {
            delta = delta.scale(0.91F);
        }
    }

    /** {@code getEffectiveGravity}: slow falling holds a fall to 0.01. */
    private double effectiveGravity(boolean falling) {
        return falling && conditions.effects().slowFalling() ? Math.min(conditions.gravity(), 0.01) : conditions.gravity();
    }

    /**
     * {@code LivingEntity.travelInFluid}: in water {@code travelInWater} (its slowdown, 0.02 of the input, the move, 0.2 up
     * on a climbable walked into, gravity a sixteenth, the jump out at a wall) and {@code floatInWaterWhileRidden}; in
     * lava {@code travelInLava}.
     */
    private void travelInFluid(Vec3d input) throws Colliders.Unknown {
        boolean falling = delta.y() <= 0.0;
        double startY = y;
        double gravity = effectiveGravity(falling);
        Box before = box();
        if (wasTouchingWater) {
            float slowDown = conditions.effects().dolphinsGrace() ? 0.96F : conditions.waterSlowDown(); // not sprinting; no water movement efficiency
            delta = delta.add(Vec3d.inputVector(input, 0.02F, yRot)); // moveRelative
            move(delta);
            requireInScope(before, box(), "along the move");
            Vec3d v = delta;
            if (horizontalCollision && onClimbable()) {
                v = new Vec3d(v.x(), 0.2, v.z());
            }
            v = v.multiply(slowDown, 0.8F, slowDown);
            delta = fluidFallingAdjustedMovement(gravity, falling, v);
            jumpOutOfFluid(startY);
            if (conditions.floatsWhileRidden() && waterHeight > 0.4) { // floatInWaterWhileRidden: isVehicle, over the jump threshold
                delta = delta.add(0.0, 0.04F, 0.0);
            }
        } else {
            delta = delta.add(Vec3d.inputVector(input, 0.02F, yRot));
            move(delta);
            requireInScope(before, box(), "along the move");
            if (lavaHeight <= 0.4) { // getFluidJumpThreshold: its eye is above 0.4
                delta = fluidFallingAdjustedMovement(gravity, falling, delta.multiply(0.5, 0.8F, 0.5));
            } else {
                delta = delta.scale(0.5);
            }
            if (gravity != 0.0) {
                delta = delta.add(0.0, -gravity / 4.0, 0.0);
            }
            jumpOutOfFluid(startY);
        }
    }

    /** {@code getFluidFallingAdjustedMovement} (never sprinting): gravity a sixteenth, a slow sink held at 0.003. */
    private static Vec3d fluidFallingAdjustedMovement(double gravity, boolean falling, Vec3d v) {
        if (gravity == 0.0) {
            return v;
        }
        double vy;
        if (falling && Math.abs(v.y() - 0.005) >= 0.003 && Math.abs(v.y() - gravity / 16.0) < 0.003) {
            vy = -0.003;
        } else {
            vy = v.y() - gravity / 16.0;
        }
        return new Vec3d(v.x(), vy, v.z());
    }

    /** {@code jumpOutOfFluid}: against a wall, with room a little above, the motion turns 0.3 up. */
    private void jumpOutOfFluid(double startY) throws Colliders.Unknown {
        if (horizontalCollision && isFree(delta.x(), delta.y() + 0.6F - y + startY, delta.z())) {
            delta = new Vec3d(delta.x(), 0.3F, delta.z());
        }
    }

    /** {@code Entity.isFree}: the box moved collides with nothing and holds no fluid ({@code containsAnyLiquid}). */
    private boolean isFree(double dx, double dy, double dz) throws Colliders.Unknown {
        Box moved = box().move(dx, dy, dz);
        if (!noCollision(moved)) {
            return false;
        }
        for (int bx = Colliders.floor(moved.minX()); bx < VanillaMath.ceil(moved.maxX()); bx++) {
            for (int by = Colliders.floor(moved.minY()); by < VanillaMath.ceil(moved.maxY()); by++) {
                for (int bz = Colliders.floor(moved.minZ()); bz < VanillaMath.ceil(moved.maxZ()); bz++) {
                    if (states.fluidKind(state(new BlockPos(bx, by, bz))) != BlockStates.FLUID_NONE) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * {@code InterpolationHandler.interpolate} ({@link Boat}'s): the target moves with the horse when the box at the moved
     * target is free, then a step of {@code 1 / steps} toward it and {@code setRot}.
     */
    private void interpolate() throws Colliders.Unknown {
        if (interpolation == null) {
            return;
        }
        BoatState.Interpolation i = interpolation;
        double d = 1.0 / i.steps();
        Vec3d target = i.target();
        if (i.previousPosition() != null) {
            Vec3d shifted = target.add(new Vec3d(x, y, z).subtract(i.previousPosition()));
            if (noCollision(HorseState.box(shifted.x(), shifted.y(), shifted.z(), conditions.width(), conditions.height()))) {
                target = shifted;
            }
        }
        float targetYRot = i.targetYRot();
        float targetXRot = i.targetXRot();
        if (i.previousPosition() != null) {
            targetYRot += yRot - i.previousYRot();
            targetXRot += xRot - i.previousXRot();
        }
        double nx = VanillaMath.lerp(d, x, target.x());
        double ny = VanillaMath.lerp(d, y, target.y());
        double nz = VanillaMath.lerp(d, z, target.z());
        float nyRot = (float) VanillaMath.rotLerp(d, (double) yRot, (double) targetYRot);
        float nxRot = (float) VanillaMath.lerp(d, (double) xRot, (double) targetXRot);
        x = nx;
        y = ny;
        z = nz;
        setRot(nyRot, nxRot);
        interpolation = i.steps() == 1 ? null
                : new BoatState.Interpolation(i.steps() - 1, target, targetYRot, targetXRot, new Vec3d(x, y, z), yRot, xRot);
    }

    /** {@code Entity.setRot}: {@code setYRot(yRot % 360)}, {@code setXRot(xRot % 360)}; non-finite values are discarded, the pitch clamped. */
    private void setRot(float newYRot, float newXRot) {
        float wrappedY = newYRot % 360.0F;
        if (Float.isFinite(wrappedY)) {
            yRot = wrappedY;
        }
        float wrappedX = newXRot % 360.0F;
        if (Float.isFinite(wrappedX)) {
            xRot = Math.clamp(wrappedX % 360.0F, -90.0F, 90.0F);
        }
    }

    /** {@code Entity.move(MoverType.SELF, movement)} for a living entity, with its step height. */
    private void move(Vec3d movement) throws Colliders.Unknown {
        if (stuck.lengthSqr() > 1.0E-7) {
            movement = movement.multiply(stuck);
            stuck = Vec3d.ZERO;
            delta = Vec3d.ZERO;
        }
        Box box = box();
        Vec3d collided = Collide.collide(world, context(), box, movement, onGround, conditions.stepHeight(),
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
        // checkFallDamage: LivingEntity's looks for water again when not in it (and pushes), then Entity's counts the fall; a
        // strider's in lava only forgets the fall
        if (conditions.flying() != null) {
            // HappyGhast.checkFallDamage: nothing
        } else if (conditions.strider() != null && lavaHeight > 0.0) {
            fallDistance = 0.0;
        } else {
            if (!wasTouchingWater) {
                updateInWaterStateAndDoWaterCurrentPushing();
            }
            if (!wasTouchingWater && collided.y() < 0.0) {
                fallDistance -= (float) collided.y();
            }
            if (onGround) {
                fallDistance = 0.0;
            }
        }
        if (horizontalCollision) {
            delta = new Vec3d(xHit ? 0.0 : delta.x(), delta.y(), zHit ? 0.0 : delta.z());
        }
        if (movement.y() != collided.y()) {
            updateEntityMovementAfterFallOn(states.block(legacyState));
        }
        float speedFactor = Terrain.blockSpeedFactor(world, support, x, y, z); // no movement efficiency: lerp(0, factor, 1)
        delta = delta.multiply(speedFactor, 1.0, speedFactor);
    }

    /**
     * {@code Block.updateEntityMovementAfterFallOn}: the vertical motion stops; a slime block or a bed bounces a falling
     * horse back (a living entity's factor 1.0; on a bed times 0.66). A horse never suppresses the bounce.
     */
    private void updateEntityMovementAfterFallOn(String block) {
        boolean slime = block.equals("minecraft:slime_block");
        boolean bed = block.endsWith("_bed");
        if (!(slime || bed)) {
            delta = delta.multiply(1.0, 0.0, 1.0);
            return;
        }
        if (delta.y() < 0.0) {
            delta = new Vec3d(delta.x(), slime ? -delta.y() : -delta.y() * 0.66F, delta.z());
        }
    }

    /** {@code Entity.checkSupportingBlock(onGround, movement)}, after the position moved; a null movement searches no further. */
    private void checkSupportingBlock(Vec3d movement) throws Colliders.Unknown {
        if (onGround) {
            Box box = box();
            Box below = Box.of(box.minX(), box.minY() - 1.0E-6, box.minZ(), box.maxX(), box.minY(), box.maxZ());
            BlockPos found = Colliders.supportingBlock(world, context(), below, x, y, z);
            if (found != null || onGroundNoBlocks) {
                support = found;
            } else if (movement != null) {
                found = Colliders.supportingBlock(world, context(), below.move(-movement.x(), 0.0, -movement.z()), x, y, z);
                support = found;
            }
            onGroundNoBlocks = found == null;
        } else {
            onGroundNoBlocks = false;
            support = null;
        }
    }

    /** {@code updateInWaterStateAndDoWaterCurrentPushing}: in water the current pushes and the fall is forgotten. */
    private void updateInWaterStateAndDoWaterCurrentPushing() throws Colliders.Unknown {
        if (updateFluidHeightAndDoFluidPushing(BlockStates.FLUID_WATER, 0.014)) {
            fallDistance = 0.0; // resetFallDistance
            wasTouchingWater = true;
        } else {
            wasTouchingWater = false;
        }
    }

    private boolean updateFluidHeightAndDoFluidPushing(byte kind, double scale) throws Colliders.Unknown {
        Terrain.FluidPush push = Terrain.fluidPushing(world, box(), kind, scale, delta, false);
        delta = push.delta();
        if (kind == BlockStates.FLUID_WATER) {
            waterHeight = push.height();
        } else {
            lavaHeight = push.height();
        }
        return push.touching();
    }

    /**
     * {@code Entity.applyEffectsFromBlocks}: on the ground, a slime block below slows the horse while its vertical motion
     * is under 0.1 ({@code 0.4 + |y| * 0.2} across; a horse never steps carefully), then the blocks the box passed through
     * along the tick's moves, or from where the tick started when there are none.
     */
    private void applyEffectsFromBlocks() throws Colliders.Unknown {
        List<Terrain.Movement> moves = Terrain.finalMovements(movements, oldPosition, new Vec3d(x, y, z));
        movements.clear();
        if (onGround && states.block(state(onPos(0.2F))).equals("minecraft:slime_block")) {
            double vy = Math.abs(delta.y());
            if (vy < 0.1) {
                double factor = 0.4 + vy * 0.2;
                delta = delta.multiply(factor, 1.0, factor);
            }
        }
        Terrain.checkInsideBlocks(world, moves, (px, py, pz) -> HorseState.box(px, py, pz, conditions.width(), conditions.height()), new Terrain.Inside() {
            @Override
            public boolean meets(int s, BlockPos pos, Vec3d from, Vec3d to) throws Colliders.Unknown {
                return MOVING_INSIDE.contains(states.block(s)) && (!states.block(s).equals(Colliders.POWDER_SNOW)
                        || Colliders.powderSnowEntered(context(), HorseState.box(from.x(), from.y(), from.z(), conditions.width(), conditions.height()),
                                to.subtract(from), pos.x(), pos.y(), pos.z()));
            }

            @Override
            public void inside(int s, BlockPos pos, boolean intersects) throws Colliders.Unknown {
                entityInside(s, pos, intersects);
            }
        });
    }

    /**
     * The {@code entityInside} of the blocks that move a horse: a cobweb, a berry bush or powder snow at its feet holds the
     * next move back ({@code makeStuckInBlock}, the fall forgotten), honey slows a fall down its side, a bubble column the
     * box meets lifts or drags it (more where its top is open).
     */
    private void entityInside(int s, BlockPos pos, boolean intersects) throws Colliders.Unknown {
        switch (states.block(s)) {
            case "minecraft:cobweb" -> {
                fallDistance = 0.0;
                stuck = new Vec3d(0.25, 0.05F, 0.25);
            }
            case "minecraft:sweet_berry_bush" -> {
                fallDistance = 0.0;
                stuck = new Vec3d(0.8F, 0.75, 0.8F);
            }
            case Colliders.POWDER_SNOW -> {
                if (states.block(state(new BlockPos(Colliders.floor(x), Colliders.floor(y), Colliders.floor(z)))).equals(Colliders.POWDER_SNOW)) {
                    fallDistance = 0.0;
                    stuck = new Vec3d(0.9F, 1.5, 0.9F);
                }
                inPowderSnow = true;
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
                    if (states.collisionShape(above).isEmpty() && states.fluidKind(above) == BlockStates.FLUID_NONE) { // onAboveBubbleColumn
                        delta = new Vec3d(delta.x(), drag ? Math.max(-0.9, delta.y() - 0.03) : Math.min(1.8, delta.y() + 0.1), delta.z());
                    } else { // onInsideBubbleColumn
                        delta = new Vec3d(delta.x(), drag ? Math.max(-0.3, delta.y() - 0.03) : Math.min(0.7, delta.y() + 0.06), delta.z());
                        fallDistance = 0.0;
                    }
                }
            }
            default -> throw new Colliders.Unknown(states.block(s) + " inside the horse at " + pos + " (not modelled)");
        }
    }

    /** {@code HoneyBlock.isSlidingDown}: falling in the air, below the top, against a side of the block. */
    private boolean isSlidingDown(BlockPos pos) {
        if (onGround || y > pos.y() + 0.9375 - 1.0E-7 || delta.y() / 0.98F + 0.08 >= -0.08) {
            return false;
        }
        double dx = Math.abs(pos.x() + 0.5 - x);
        double dz = Math.abs(pos.z() + 0.5 - z);
        double edge = 0.4375 + conditions.width() / 2.0F;
        return dx + 1.0E-7 > edge || dz + 1.0E-7 > edge;
    }

    /**
     * {@code LivingEntity.onClimbable}: the block at the feet ({@code getInBlockState}) is climbable, or an open trapdoor
     * over a ladder facing the same way ({@code trapdoorUsableAsLadder}).
     */
    private boolean onClimbable() throws Colliders.Unknown {
        BlockPos feet = BlockPos.containing(x, y, z);
        int s = state(feet);
        String block = states.block(s);
        if (CLIMBABLE.contains(block)) {
            return true;
        }
        if (block.endsWith("_trapdoor") && Terrain.property(states, s, "open").equals("true")) {
            int below = state(new BlockPos(feet.x(), feet.y() - 1, feet.z()));
            return states.block(below).equals("minecraft:ladder") && Terrain.property(states, below, "facing").equals(Terrain.property(states, s, "facing"));
        }
        return false;
    }

    /** {@code handleOnClimbable}: on a climbable block the motion is held to 0.15 a tick (no ladder holds a horse still). */
    private Vec3d handleOnClimbable(Vec3d d) throws Colliders.Unknown {
        if (!onClimbable()) {
            return d;
        }
        fallDistance = 0.0; // resetFallDistance
        return new Vec3d(VanillaMath.clamp(d.x(), -0.15F, 0.15F), Math.max(d.y(), -0.15F), VanillaMath.clamp(d.z(), -0.15F, 0.15F));
    }

    /** No block in the cells the two boxes span holds a fluid but water and lava. */
    private void requireInScope(Box a, Box b, String when) throws Colliders.Unknown {
        for (int bx = Colliders.floor(Math.min(a.minX(), b.minX())); bx <= Colliders.floor(Math.max(a.maxX(), b.maxX())); bx++) {
            for (int by = Colliders.floor(Math.min(a.minY(), b.minY())); by <= Colliders.floor(Math.max(a.maxY(), b.maxY())); by++) {
                for (int bz = Colliders.floor(Math.min(a.minZ(), b.minZ())); bz <= Colliders.floor(Math.max(a.maxZ(), b.maxZ())); bz++) {
                    int s = state(new BlockPos(bx, by, bz));
                    if (states.fluidKind(s) == BlockStates.FLUID_OTHER) {
                        throw new Colliders.Unknown(states.block(s) + " inside the horse " + when + " (not modelled)");
                    }
                }
            }
        }
    }

    /** {@code CollisionGetter.noCollision(horse, box)}: no block, no entity it collides with, and not the border's shape. */
    private boolean noCollision(Box box) throws Colliders.Unknown {
        return entityCollisions(box).isEmpty() && Colliders.noCollision(world, context(), box);
    }

    /** {@code Level.getEntityCollisions(horse, query)} ({@link Boat}'s). */
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

    /** A horse neither sneaks (no scaffolding descends under it) nor wears leather boots (powder snow holds it); a strider stands on lava. */
    private Colliders.Context context() {
        return new Colliders.Context(y, false, x, z, conditions.border(), fallDistance, Boolean.FALSE, conditions.strider() != null);
    }

    private Box box() {
        return HorseState.box(x, y, z, conditions.width(), conditions.height());
    }

    private BlockPos onPos(float offset) throws Colliders.Unknown {
        return Terrain.onPos(world, support, x, y, z, offset);
    }

    private int state(BlockPos pos) throws Colliders.Unknown {
        return Terrain.state(world, pos);
    }
}
