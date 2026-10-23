package dev.whynotac.physics;

import dev.whynotac.math.vanilla.PlatformCos;
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
 * <p>The scope is a survival player on foot, in water or lava or crawling, or gliding with an elytra in the air
 * ({@code travelFallFlying}), with default attributes but the speed ({@link Speed}) and the effects the step reads
 * ({@link Effects}), using an item or not ({@link ItemUse}), not riding or flying, not gliding in a fluid, away from
 * powder snow, solid entities and the world border; the fluids' currents and
 * the blocks that move the player from inside (cobwebs, berry bushes, honey slides, bubble columns) are followed.
 * Outside it, or where the client's world is not known, {@link Colliders.Unknown} is thrown: no claim can rest on that
 * tick. Sources: the decompiled client, {@code LocalPlayer},
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
    /** Its collision and its hold depend on the player's boots and fall ({@code PowderSnowBlock}): not followed. */
    static final String POWDER_SNOW = "minecraft:powder_snow";
    /** {@code BlockTags.CLIMBABLE}; trapdoors can be ladders too ({@code trapdoorUsableAsLadder}). */
    public static final java.util.Set<String> CLIMBABLE = java.util.Set.of("minecraft:ladder", "minecraft:vine", "minecraft:scaffolding",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
            "minecraft:cave_vines", "minecraft:cave_vines_plant");
    /** {@code BlockTags.FALL_DAMAGE_RESETTING}: the climbable blocks, sweet berry bushes and cobwebs. */
    public static final java.util.Set<String> FALL_DAMAGE_RESETTING = java.util.stream.Stream.concat(CLIMBABLE.stream(),
            java.util.stream.Stream.of("minecraft:sweet_berry_bush", "minecraft:cobweb")).collect(java.util.stream.Collectors.toUnmodifiableSet());
    /** {@code BlockTags.CAN_GLIDE_THROUGH} (its {@code #cave_vines} expanded): not climbable while gliding. */
    public static final java.util.Set<String> CAN_GLIDE_THROUGH = java.util.Set.of("minecraft:vine", "minecraft:twisting_vines",
            "minecraft:twisting_vines_plant", "minecraft:weeping_vines", "minecraft:weeping_vines_plant", "minecraft:cave_vines",
            "minecraft:cave_vines_plant");

    /**
     * What the step needs beyond the player and the world.
     *
     * @param foodAllowsSprint {@code hasEnoughFoodToDoExhaustiveManoeuvres}: food above 6, or allowed to fly
     * @param sprintWindow     the client's {@code sprintWindow} option (double-tap window, 0..10)
     * @param autoJumpEnabled  {@code LocalPlayer.autoJumpEnabled}: the option, but true during a new player's first tick
     * @param speed            the movement speed attribute's value ({@code AttributeInstance.getValue})
     * @param effects          the player's active effects the step reads
     * @param use              the item the player is using during the tick, or null
     * @param glider           an item in its equippable slot can glide ({@code LivingEntity.canGlideUsing}: the glider
     *                         component, not about to break): a new jump press in the air then starts gliding
     * @param cosine           the client's {@code Math.cos} ({@link PlatformCos}): a glide reads it
     *                         ({@code updateFallFlyingMovement}), and it is an intrinsic of the client's CPU
     */
    public record Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled, Speed speed, Effects effects, ItemUse use,
            boolean glider, PlatformCos cosine) {
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

        public Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled, Speed speed, Effects effects, ItemUse use) {
            this(foodAllowsSprint, sprintWindow, autoJumpEnabled, speed, effects, use, false);
        }

        public Conditions(boolean foodAllowsSprint, int sprintWindow, boolean autoJumpEnabled, Speed speed, Effects effects, ItemUse use,
                boolean glider) {
            this(foodAllowsSprint, sprintWindow, autoJumpEnabled, speed, effects, use, glider, PlatformCos.X86_64);
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
     * @param dolphinsGrace {@code travelInWater}: the water's slowdown is 0.96
     * @param weaving     {@code WebBlock.entityInside}: a cobweb holds the player back less ({@code 0.5, 0.25, 0.5})
     */
    public record Effects(int jumpBoost, int levitation, boolean slowFalling, boolean blindness, boolean dolphinsGrace, boolean weaving) {
        public static final Effects NONE = new Effects(0, 0, false, false, false, false);

        public Effects {
            if (jumpBoost < 0 || jumpBoost > 256 || levitation < 0 || levitation > 256) {
                throw new IllegalArgumentException("effect level out of 0..256");
            }
        }

        public Effects(int jumpBoost, int levitation, boolean slowFalling, boolean blindness) {
            this(jumpBoost, levitation, slowFalling, blindness, false, false);
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
     * @param startedGliding     the tick started gliding: the client sent {@code START_FALL_FLYING}
     * @param readCosine         the tick glided: its motion depended on the client's {@code Math.cos}
     *                           ({@link Conditions#cosine}, {@link #glideCosine})
     */
    public record Step(PlayerState state, boolean readSprintTrigger, boolean readSprintWindow, boolean autoJumpTriggered, Speed speed,
            boolean startedGliding, boolean readCosine) {
        public Step(PlayerState state, boolean readSprintTrigger, boolean readSprintWindow, boolean autoJumpTriggered, Speed speed) {
            this(state, readSprintTrigger, readSprintWindow, autoJumpTriggered, speed, false, false);
        }

        public Step(PlayerState state, boolean readSprintTrigger, boolean readSprintWindow, boolean autoJumpTriggered, Speed speed,
                boolean startedGliding) {
            this(state, readSprintTrigger, readSprintWindow, autoJumpTriggered, speed, startedGliding, false);
        }
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
    private boolean swimming;
    private boolean fallFlying;
    private boolean startedGliding;
    private boolean readCosine;
    /** {@code fluidOnEyes} holds water: set by this tick's {@code updateFluidOnEyes}, read as the last tick's before it. */
    private boolean eyeInWater;
    /** {@code Player.wasUnderwater}: what {@code LocalPlayer.isUnderWater} returns this tick. */
    private boolean wasUnderwater;
    private boolean wasTouchingWater;
    /** {@code getFluidHeight(FluidTags.WATER)}. */
    private double waterHeight;
    /** {@code getFluidHeight(FluidTags.LAVA)}: {@code isInLava} when above 0. */
    private double lavaHeight;
    private float xxa;
    private float zza;
    private boolean jumping;
    private float moveX;
    private float moveY;
    /** {@code oldPosition()}: where the tick started. */
    private final Vec3d oldPosition;
    /** {@code movementThisTick}: the moves {@code Entity.move} made during the tick. */
    private final java.util.ArrayList<Movement> movements = new java.util.ArrayList<>(2);

    /** {@code Entity.Movement}: a move's start and end, and the movement asked for (null: none, a plain sweep). */
    private record Movement(Vec3d from, Vec3d to, Vec3d original) {}

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
        swimming = pre.swimming();
        eyeInWater = pre.eyeInWater();
        fallFlying = pre.fallFlying();
        oldPosition = new Vec3d(x, y, z);
    }

    /** The player after one client tick in which the keys were {@code keys}. */
    public static PlayerState tick(PlayerState pre, PlayerState.Keys keys, Colliders.World world, Conditions conditions) throws Colliders.Unknown {
        return step(pre, keys, world, conditions).state();
    }

    /** {@link #tick}, with what the tick read beyond its state. */
    public static Step step(PlayerState pre, PlayerState.Keys keys, Colliders.World world, Conditions conditions) throws Colliders.Unknown {
        OnFoot tick = new OnFoot(pre, world, conditions);
        tick.run(keys);
        return new Step(tick.state(), tick.readSprintTrigger, tick.readSprintWindow, tick.autoJumpTriggered, tick.speed, tick.startedGliding,
                tick.readCosine);
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
                onGroundNoBlocks, stuck, swimming, eyeInWater, fallFlying);
    }

    private void run(PlayerState.Keys polled) throws Colliders.Unknown {
        // --- Player.tick: updateIsUnderwater, from the last tick's eye ---
        wasUnderwater = eyeInWater;
        // --- Entity.baseTick: water and lava (no other fluid, no powder snow), the eye, swimming, a fall in lava halved ---
        Box start = pose.box(x, y, z);
        requireInScope(start.inflate(-0.001, -0.001, -0.001), start, "at the start of the tick");
        updateInWaterStateAndDoFluidPushing();
        updateFluidOnEyes();
        updateSwimming();
        if (lavaHeight > 0.0) { // isInLava (not a new entity's first tick: its fall distance is 0 then)
            fallDistance *= 0.5;
        }
        // --- LocalPlayer.aiStep ---
        if (sprintTriggerTime > 0) {
            sprintTriggerTime--;
        }
        boolean previousShift = keys.shift();
        boolean previousJump = keys.jump(); // aiStep reads the keys before input.tick()
        moveVector(keys); // the previous tick's vector, from its keys
        boolean previousForward = moveY > 1.0E-5F;
        crouching = !swimming && fits(PlayerState.Pose.CROUCHING) && (previousShift || !fits(PlayerState.Pose.STANDING));
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
        // isMovingSlowly: crouching or crawling (isVisuallyCrawling: visually swimming, which a glide's pose is once it stopped)
        boolean movingSlowly = crouching || (pose == PlayerState.Pose.SWIMMING || !fallFlying && pose == PlayerState.Pose.FALL_FLYING) && !wasTouchingWater;
        boolean sprintAllowed = !conditions.effects().blindness() && conditions.foodAllowsSprint();
        boolean sprintPossible = sprintAllowed && !(wasTouchingWater && !wasUnderwater); // isSprintingPossible: not in shallow water
        if (!sprinting && forward && sprintPossible && !slowDueToUse && (!fallFlying || wasUnderwater) && (!movingSlowly || wasUnderwater)) { // canStartSprinting
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
        if (sprinting) {
            if (swimming) {
                if (!sprintAllowed || !wasTouchingWater || !forward && !onGround && !keys.shift()) { // shouldStopSwimSprinting
                    setSprinting(false);
                }
            } else if (!sprintPossible || !forward || horizontalCollision && !minorHorizontalCollision) { // shouldStopRunSprinting
                setSprinting(false);
            }
        }
        // a new jump press: tryToStartFallFlying (not gliding, canGlide: in the air, no levitation, a glider; not in water)
        if (keys.jump() && !previousJump && !onClimbable() && !fallFlying && !onGround && conditions.effects().levitation() == 0 && conditions.glider()
                && !wasTouchingWater) {
            fallFlying = true; // startFallFlying, and START_FALL_FLYING sent
            startedGliding = true;
        }
        if (wasTouchingWater && keys.shift()) {
            delta = delta.add(0.0, -0.04F, 0.0); // goDownInWater
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
            boolean inLava = lavaHeight > 0.0;
            double height = inLava ? lavaHeight : waterHeight;
            boolean inWater = wasTouchingWater && height > 0.0;
            double threshold = fluidJumpThreshold();
            if (!inWater || onGround && !(height > threshold)) {
                if (!inLava || onGround && !(height > threshold)) {
                    if ((onGround || inWater && height <= threshold) && noJumpDelay == 0) {
                        jumpFromGround();
                        noJumpDelay = 10;
                    }
                } else {
                    delta = delta.add(0.0, 0.04F, 0.0); // jumpInLiquid(LAVA)
                }
            } else {
                delta = delta.add(0.0, 0.04F, 0.0); // jumpInLiquid(WATER)
            }
        } else {
            noJumpDelay = 0;
        }
        if (fallFlying && delta.y() > -0.5 && fallDistance > 1.0) {
            fallDistance = 1.0; // updateFallFlying: checkFallDistanceAccumulation (the rest is the server's)
        }
        if (conditions.effects().slowFalling() || conditions.effects().levitation() > 0) {
            fallDistance = 0.0; // resetFallDistance
        }
        Vec3d input = new Vec3d(xxa, 0.0, zza);
        if (swimming) { // Player.travel: the look's pitch draws a swimmer up or down
            double look = -VanillaMath.sin(xRot * VanillaMath.DEG_TO_RAD); // getLookAngle().y
            double pull = look < -0.2 ? 0.085 : 0.06;
            if (look <= 0.0 || jumping || states.fluidKind(state(BlockPos.containing(x, y + 1.0 - 0.1, z))) != BlockStates.FLUID_NONE) {
                delta = delta.add(0.0, (look - delta.y()) * pull, 0.0);
            }
        }
        if (fallFlying && (wasTouchingWater || lavaHeight > 0.0)) {
            throw new Colliders.Unknown("gliding in a fluid");
        }
        if (wasTouchingWater) { // LivingEntity.travel: shouldTravelInFluid, then travelInFluid, gliding, the air
            travelInWater(input);
        } else if (lavaHeight > 0.0) {
            travelInLava(input);
        } else if (fallFlying) {
            travelFallFlying(input);
        } else {
            travelInAir(input);
        }
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
        requireInScope(before.inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), pose.box(x, y, z).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), "along the move");
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

    /** {@code LivingEntity.travelFallFlying}: a climbable block ends the glide after a step in the air. */
    private void travelFallFlying(Vec3d input) throws Colliders.Unknown {
        if (onClimbable()) {
            travelInAir(input);
            fallFlying = false; // stopFallFlying
            return;
        }
        delta = updateFallFlyingMovement(delta);
        Box before = pose.box(x, y, z);
        move(delta);
        requireInScope(before.inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), pose.box(x, y, z).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), "along the move");
    }

    /** The {@code Math.cos} of a glide at this pitch on {@code platform}: {@code updateFallFlyingMovement}'s, of the float pitch. */
    public static double glideCosine(PlatformCos platform, float xRot) {
        return platform.cos(xRot * (float) (Math.PI / 180.0));
    }

    /** {@code LivingEntity.updateFallFlyingMovement}, in its own order and precision ({@code Math.cos} of the float pitch). */
    private Vec3d updateFallFlyingMovement(Vec3d d) {
        Vec3d look = Vec3d.viewVector(xRot, yRot); // getLookAngle
        float pitch = xRot * (float) (Math.PI / 180.0);
        double lookHorizontal = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        double speed = d.horizontalDistance();
        double gravity = d.y() <= 0.0 && conditions.effects().slowFalling() ? Math.min(GRAVITY, 0.01) : GRAVITY; // getEffectiveGravity
        double cosPitch = glideCosine(conditions.cosine(), xRot);
        readCosine = true;
        double lift = cosPitch * cosPitch; // Mth.square
        d = d.add(0.0, gravity * (-1.0 + lift * 0.75), 0.0);
        if (d.y() < 0.0 && lookHorizontal > 0.0) {
            double sink = d.y() * -0.1 * lift;
            d = d.add(look.x() * sink / lookHorizontal, sink, look.z() * sink / lookHorizontal);
        }
        if (pitch < 0.0F && lookHorizontal > 0.0) {
            double climb = speed * -VanillaMath.sin(pitch) * 0.04;
            d = d.add(-look.x() * climb / lookHorizontal, climb * 3.2, -look.z() * climb / lookHorizontal);
        }
        if (lookHorizontal > 0.0) {
            d = d.add((look.x() / lookHorizontal * speed - d.x()) * 0.1, 0.0, (look.z() / lookHorizontal * speed - d.z()) * 0.1);
        }
        return d.multiply(0.99F, 0.98F, 0.99F);
    }

    private int minY() throws Colliders.Unknown {
        throw new Colliders.Unknown("movement in an unloaded chunk");
    }

    /**
     * {@code LivingEntity.travelInWater} (from {@code travelInFluid}): the water's slowdown (0.8, sprinting 0.9, 0.96 with
     * dolphin's grace; no water movement efficiency), 0.02 of input, the move, 0.2 up on a climbable block walked into,
     * gravity as {@code getFluidFallingAdjustedMovement} takes it, and the jump out of the water at a wall.
     */
    private void travelInWater(Vec3d input) throws Colliders.Unknown {
        boolean falling = delta.y() <= 0.0;
        double startY = y;
        double gravity = falling && conditions.effects().slowFalling() ? Math.min(GRAVITY, 0.01) : GRAVITY; // getEffectiveGravity
        float slowDown = sprinting ? 0.9F : 0.8F;
        float waterSpeed = 0.02F;
        if (conditions.effects().dolphinsGrace()) {
            slowDown = 0.96F;
        }
        delta = delta.add(Vec3d.inputVector(input, waterSpeed, yRot)); // moveRelative
        Box before = pose.box(x, y, z);
        move(delta);
        requireInScope(before.inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), pose.box(x, y, z).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), "along the move");
        Vec3d v = delta;
        if (horizontalCollision && onClimbable()) {
            v = new Vec3d(v.x(), 0.2, v.z());
        }
        v = v.multiply(slowDown, 0.8F, slowDown);
        delta = fluidFallingAdjustedMovement(gravity, falling, v);
        jumpOutOfFluid(startY);
    }

    /**
     * {@code LivingEntity.travelInLava} (from {@code travelInFluid}): 0.02 of input, the move, then shallow lava (its
     * height at most the jump threshold) slows by 0.5 across and 0.8 up as {@code getFluidFallingAdjustedMovement} takes
     * it, deeper lava by 0.5 all round; a quarter of gravity, and the jump out of it at a wall.
     */
    private void travelInLava(Vec3d input) throws Colliders.Unknown {
        boolean falling = delta.y() <= 0.0;
        double startY = y;
        double gravity = falling && conditions.effects().slowFalling() ? Math.min(GRAVITY, 0.01) : GRAVITY; // getEffectiveGravity
        delta = delta.add(Vec3d.inputVector(input, 0.02F, yRot)); // moveRelative
        Box before = pose.box(x, y, z);
        move(delta);
        requireInScope(before.inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), pose.box(x, y, z).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F), "along the move");
        if (lavaHeight <= fluidJumpThreshold()) {
            delta = fluidFallingAdjustedMovement(gravity, falling, delta.multiply(0.5, 0.8F, 0.5));
        } else {
            delta = delta.scale(0.5);
        }
        if (gravity != 0.0) {
            delta = delta.add(0.0, -gravity / 4.0, 0.0);
        }
        jumpOutOfFluid(startY);
    }

    /** {@code getFluidFallingAdjustedMovement}: a sprinter keeps its motion; otherwise gravity a sixteenth, a slow sink held. */
    private Vec3d fluidFallingAdjustedMovement(double gravity, boolean falling, Vec3d v) {
        if (gravity != 0.0 && !sprinting) {
            double vy;
            if (falling && Math.abs(v.y() - 0.005) >= 0.003 && Math.abs(v.y() - gravity / 16.0) < 0.003) {
                vy = -0.003;
            } else {
                vy = v.y() - gravity / 16.0;
            }
            return new Vec3d(v.x(), vy, v.z());
        }
        return v;
    }

    /** {@code jumpOutOfFluid}: against a wall, with room a little above, the motion turns 0.3 up. */
    private void jumpOutOfFluid(double startY) throws Colliders.Unknown {
        if (horizontalCollision && isFree(delta.x(), delta.y() + 0.6F - y + startY, delta.z())) {
            delta = new Vec3d(delta.x(), 0.3F, delta.z());
        }
    }

    /** {@code getFluidJumpThreshold}: 0.4, 0 for an eye below 0.4. */
    private double fluidJumpThreshold() {
        return pose.eyeHeight < 0.4 ? 0.0 : 0.4;
    }

    /** {@code Entity.isFree}: the box moved collides with no block and holds no fluid ({@code containsAnyLiquid}). */
    private boolean isFree(double dx, double dy, double dz) throws Colliders.Unknown {
        Box box = pose.box(x, y, z).move(dx, dy, dz);
        if (!Colliders.noCollision(world, context(), box)) {
            return false;
        }
        for (int bx = Colliders.floor(box.minX()); bx < VanillaMath.ceil(box.maxX()); bx++) {
            for (int by = Colliders.floor(box.minY()); by < VanillaMath.ceil(box.maxY()); by++) {
                for (int bz = Colliders.floor(box.minZ()); bz < VanillaMath.ceil(box.maxZ()); bz++) {
                    if (states.fluidKind(state(new BlockPos(bx, by, bz))) != BlockStates.FLUID_NONE) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * {@code Entity.updateInWaterStateAndDoFluidPushing}: the heights forgotten, water's current (below), then lava's, its
     * push 0.007 where the level's lava is fast ({@code gameplay/fast_lava}: the nether's), 0.0023333333333333335 elsewhere.
     */
    private void updateInWaterStateAndDoFluidPushing() throws Colliders.Unknown {
        waterHeight = 0.0; // fluidHeight.clear()
        lavaHeight = 0.0;
        updateInWaterStateAndDoWaterCurrentPushing();
        Boolean fast = world.fastLava();
        updateFluidHeightAndDoFluidPushing(BlockStates.FLUID_LAVA, fast == null ? Double.NaN : fast ? 0.007 : 0.0023333333333333335);
    }

    /** {@code Entity.updateInWaterStateAndDoWaterCurrentPushing}: in water the fall distance is reset. */
    private void updateInWaterStateAndDoWaterCurrentPushing() throws Colliders.Unknown {
        if (updateFluidHeightAndDoFluidPushing(BlockStates.FLUID_WATER, 0.014)) {
            fallDistance = 0.0; // resetFallDistance
            wasTouchingWater = true;
        } else {
            wasTouchingWater = false;
        }
    }

    /**
     * {@code Entity.updateFluidHeightAndDoFluidPushing(tag, scale)} for a player (pushed, the push not normalised): over the
     * cells of the box deflated by 0.001, the fluid's height above the box's bottom and the mean of the flows of the cells
     * it reaches (scaled by that height below 0.4), times {@code scale} (NaN: not known, which only a push needs). A chunk
     * the client may not hold near the box is not known (the client then skips the fluid altogether).
     */
    private boolean updateFluidHeightAndDoFluidPushing(byte kind, double scale) throws Colliders.Unknown {
        Box full = pose.box(x, y, z);
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
                    int s = state(p);
                    if (states.fluidKind(s) != kind) {
                        continue;
                    }
                    double top = by + fluidHeight(p, s, kind); // int + float, in float
                    if (top >= box.minY()) {
                        touching = true;
                        height = Math.max(top - box.minY(), height);
                        Vec3d flow = flow(p, s, kind);
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
            if (Double.isNaN(scale)) {
                throw new Colliders.Unknown("lava's current in a level whose lava speed is not known");
            }
            push = push.scale(scale);
            if (Math.abs(delta.x()) < 0.003 && Math.abs(delta.z()) < 0.003 && push.length() < 0.0045000000000000005) {
                push = push.normalize().scale(0.0045000000000000005);
            }
            delta = delta.add(push);
        }
        if (kind == BlockStates.FLUID_WATER) {
            waterHeight = height;
        } else {
            lavaHeight = height;
        }
        return touching;
    }

    /** {@code FlowingFluid.getHeight}: a full block under fluid of its own kind, else its own height. */
    private float fluidHeight(BlockPos p, int s, byte kind) throws Colliders.Unknown {
        return states.fluidKind(state(p.atY(p.y() + 1))) == kind ? 1.0F : ownHeight(s);
    }

    /** {@code FluidState.getOwnHeight}: {@code amount / 9}, 0 for no fluid. */
    private float ownHeight(int s) {
        return states.fluidAmount(s) / 9.0F;
    }

    /** {@code Direction.Plane.HORIZONTAL}: north, east, south, west, as x and z steps. */
    private static final int[][] HORIZONTAL = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    /**
     * {@code FlowingFluid.getFlow}: toward lower neighbours (through a passable neighbour to the fluid of its kind below
     * it), and for a falling fluid next to a sturdy face (or one above it) mostly down.
     */
    private Vec3d flow(BlockPos pos, int s, byte kind) throws Colliders.Unknown {
        float own = ownHeight(s);
        double fx = 0.0;
        double fz = 0.0;
        for (int[] d : HORIZONTAL) {
            BlockPos n = new BlockPos(pos.x() + d[0], pos.y(), pos.z() + d[1]);
            int ns = state(n);
            if (!affectsFlow(ns, kind)) {
                continue;
            }
            float f = ownHeight(ns);
            float g = 0.0F;
            if (f == 0.0F) {
                if (!states.has(ns, BlockStates.BLOCKS_MOTION)) {
                    int below = state(n.atY(n.y() - 1));
                    if (affectsFlow(below, kind)) {
                        f = ownHeight(below);
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
                if (solidFace(n, d, kind) || solidFace(n.atY(n.y() + 1), d, kind)) {
                    flow = flow.normalize().add(0.0, -6.0, 0.0);
                    break;
                }
            }
        }
        return flow.normalize();
    }

    /** {@code FlowingFluid.affectsFlow}: no fluid, or the same kind ({@code isSame}). */
    private boolean affectsFlow(int s, byte kind) {
        byte k = states.fluidKind(s);
        return k == BlockStates.FLUID_NONE || k == kind;
    }

    /** {@code FlowingFluid.isSolidFace} for a horizontal side: not the same fluid, not ice, and the face sturdy. */
    private boolean solidFace(BlockPos n, int[] d, byte kind) throws Colliders.Unknown {
        int s = state(n);
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

    /** {@code Entity.updateFluidOnEyes}: the eye is in water when the water's top in its cell is above it. */
    private void updateFluidOnEyes() throws Colliders.Unknown {
        double eyeY = y + pose.eyeHeight;
        BlockPos p = BlockPos.containing(x, eyeY, z);
        int s = state(p);
        eyeInWater = states.fluidKind(s) == BlockStates.FLUID_WATER && p.y() + fluidHeight(p, s, BlockStates.FLUID_WATER) > eyeY;
    }

    /** {@code Entity.updateSwimming} for the local player ({@code isUnderWater}: {@code wasUnderwater}). */
    private void updateSwimming() throws Colliders.Unknown {
        if (swimming) {
            swimming = sprinting && wasTouchingWater;
        } else {
            swimming = sprinting && wasUnderwater && states.fluidKind(state(BlockPos.containing(x, y, z))) == BlockStates.FLUID_WATER;
        }
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
        Colliders.blocks(world, context(), area, colliders); // level.getCollisions: no solid entity in scope
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

    /**
     * {@code CollisionContext.of(player)} made now: the feet's height and the shift key as they are at this point of the
     * tick (before the input is polled, the last tick's keys).
     */
    private Colliders.Context context() {
        return new Colliders.Context(y, keys.shift());
    }

    /** The most cells a move's ray is followed through ({@link #mayResetTheFall}); beyond, it is not followed. */
    private static final int MAX_RAY_CELLS = 512;

    /**
     * {@code Entity.move}: a move of a block or more while falling casts a ray from the feet along it, at most 8 long
     * ({@code Level.clip} with {@code ClipContext.Block.FALLDAMAGE_RESETTING}, {@code ClipContext.Fluid.WATER}), and the
     * fall resets where it meets a block of {@link #FALL_DAMAGE_RESETTING} (its shape a full block) or water. Where it
     * meets them is not modelled: false only if no cell the move's ray can pass through ({@code traverseBlocks}: within
     * the move's bounds) holds either.
     */
    private boolean mayResetTheFall(Vec3d move) throws Colliders.Unknown {
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
                    int s = state(new BlockPos(bx, by, bz));
                    if (FALL_DAMAGE_RESETTING.contains(states.block(s)) || states.fluidKind(s) == BlockStates.FLUID_WATER) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** {@code blockState.getCollisionShape(level, pos, CollisionContext.of(player))}, unmoved. */
    private Voxels collisionShape(BlockPos pos) throws Colliders.Unknown {
        return Voxels.of(Colliders.playerShape(states, state(pos), context(), pos.x(), pos.y(), pos.z()));
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
        Vec3d collided = Collide.collide(world, context(), box, movement, onGround, STEP_HEIGHT, List.of());
        double length = collided.lengthSqr();
        if (length > 1.0E-7 || movement.lengthSqr() - length < 1.0E-7) {
            if (fallDistance != 0.0 && length >= 1.0 && mayResetTheFall(collided)) {
                throw new Colliders.Unknown("a fall reset by a ray (not modelled)");
            }
            Vec3d from = new Vec3d(x, y, z);
            x += collided.x();
            y += collided.y();
            z += collided.z();
            movements.add(new Movement(from, new Vec3d(x, y, z), movement)); // addMovementThisTick
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
        // checkFallDamage: LivingEntity's looks for water again when not in it (and pushes), then Entity's counts the fall
        if (!wasTouchingWater) {
            updateInWaterStateAndDoWaterCurrentPushing();
        }
        if (!wasTouchingWater && collided.y() < 0.0) {
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
        return Colliders.noCollision(world, context(), Box.of(box.minX() + 1.0E-7 + dx, box.minY() - height - 1.0E-7, box.minZ() + 1.0E-7 + dz,
                box.maxX() - 1.0E-7 + dx, box.minY(), box.maxZ() - 1.0E-7 + dz));
    }

    /** {@code Entity.checkSupportingBlock(onGround, movement)}, after the position moved. */
    private void checkSupportingBlock(Vec3d movement) throws Colliders.Unknown {
        if (onGround) {
            Box box = pose.box(x, y, z);
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
     * {@code LocalPlayer.isHorizontalCollisionMinor}: the collision is within 8 degrees of the walking direction. The
     * client's {@code Math.acos} (HotSpot's is {@code StrictMath.acos}) may be another JVM's, within an ulp of the exact
     * value as this one is: an angle within two ulps of the threshold may compare either way and is not followed.
     */
    private boolean isHorizontalCollisionMinor(Vec3d movement) throws Colliders.Unknown {
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
            if (Math.abs(angle - 0.13962634F) <= 2.0 * Math.ulp(angle)) {
                throw new Colliders.Unknown("a minor collision at its threshold, as another JVM's acos may compare");
            }
            return angle < 0.13962634F;
        }
        return false;
    }

    /** {@code Player.getBlockSpeedFactor}: 1 while gliding, else {@code Entity}'s (no movement-efficiency attribute, not flying). */
    private float blockSpeedFactor() throws Colliders.Unknown {
        if (fallFlying) {
            return 1.0F;
        }
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
     * changes the motion), then the blocks the box passed through ({@link #checkInsideBlocks}) along the tick's moves, or
     * from where the tick started when it made none.
     */
    private void applyEffectsFromBlocks() throws Colliders.Unknown {
        java.util.ArrayList<Movement> moves = new java.util.ArrayList<>(movements); // finalMovementsThisTick
        movements.clear();
        Vec3d position = new Vec3d(x, y, z);
        if (moves.isEmpty()) {
            moves.add(new Movement(oldPosition, position, null));
        } else if (moves.getLast().to().distanceToSqr(position) > 9.9999994E-11F) {
            moves.add(new Movement(moves.getLast().to(), position, null));
        }
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
        checkInsideBlocks(moves);
    }

    /**
     * {@code Entity.checkInsideBlocks(List<Movement>)}: each move swept in at most 16 steps, a move asked for split per
     * axis ({@code Direction.axisStepOrder}: y, then x and z, the larger last) and, the steps spent, its end once more;
     * a block acts once a tick, in the order the sweeps meet it.
     */
    private void checkInsideBlocks(List<Movement> moves) throws Colliders.Unknown {
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
                        budget -= checkInsideBlocks(from, next, budget, visited);
                        from = next;
                    }
                }
            } else {
                budget -= checkInsideBlocks(m.from(), m.to(), 16, visited);
            }
            if (budget <= 0) {
                checkInsideBlocks(m.to(), m.to(), 1, visited);
            }
        }
    }

    /**
     * One sweep of {@code checkInsideBlocks}: the box at {@code to}, deflated by {@code 1.0E-5F}, through the cells from
     * {@code from} ({@link InsideBlocks}), stopping at the step budget; the steps it took. Every block that acts is
     * met wherever its cell is (its inside shape is the full block).
     */
    private int checkInsideBlocks(Vec3d from, Vec3d to, int budget, java.util.Set<BlockPos> visited) throws Colliders.Unknown {
        Box box = pose.box(to.x(), to.y(), to.z()).inflate(-1.0E-5F, -1.0E-5F, -1.0E-5F);
        boolean far = from.distanceToSqr(to) > 0.9999900000002526 * 0.9999900000002526;
        int[] last = {0};
        InsideBlocks.forEachBlockIntersectedBetween(from, to, box, (bx, by, bz, step) -> {
            if (step >= budget) {
                return false;
            }
            last[0] = step;
            BlockPos pos = new BlockPos(bx, by, bz);
            int s = state(pos);
            if (!states.has(s, BlockStates.AIR) && MOVING_INSIDE.contains(states.block(s)) && visited.add(pos)) {
                entityInside(s, pos, far || box.intersects(bx, by, bz, bx + 1, by + 1, bz + 1));
            }
            return true;
        });
        return last[0] + 1;
    }

    /**
     * The {@code entityInside} of the blocks that move the player: a cobweb or a berry bush holds the next move back
     * ({@code makeStuckInBlock}, the fall forgotten), honey slows a fall down its side ({@code HoneyBlock.doSlideMovement}),
     * a bubble column the box meets lifts or drags it (more where its top is open). No other block's changes the
     * client's motion.
     */
    private void entityInside(int s, BlockPos pos, boolean intersects) throws Colliders.Unknown {
        switch (states.block(s)) {
            case "minecraft:cobweb" -> {
                fallDistance = 0.0;
                stuck = conditions.effects().weaving() ? new Vec3d(0.5, 0.25, 0.5) : new Vec3d(0.25, 0.05F, 0.25);
            }
            case "minecraft:sweet_berry_bush" -> {
                fallDistance = 0.0;
                stuck = new Vec3d(0.8F, 0.75, 0.8F);
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
                    boolean drag = property(s, "drag").equals("true");
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
            default -> throw new Colliders.Unknown(states.block(s) + " inside the box at " + pos + " (not modelled)");
        }
    }

    /** {@code HoneyBlock.isSlidingDown}: falling in the air, below the top, against a side of the block. */
    private boolean isSlidingDown(BlockPos pos) {
        if (onGround || y > pos.y() + 0.9375 - 1.0E-7 || delta.y() / 0.98F + 0.08 >= -0.08) {
            return false;
        }
        double dx = Math.abs(pos.x() + 0.5 - x);
        double dz = Math.abs(pos.z() + 0.5 - z);
        double edge = 0.4375 + pose.width / 2.0F;
        return dx + 1.0E-7 > edge || dz + 1.0E-7 > edge;
    }

    /** {@code Player.updatePlayerPose}, with the current shift key. */
    private void updatePlayerPose() throws Colliders.Unknown {
        if (fits(PlayerState.Pose.SWIMMING)) {
            PlayerState.Pose desired = swimming ? PlayerState.Pose.SWIMMING : fallFlying ? PlayerState.Pose.FALL_FLYING
                    : keys.shift() ? PlayerState.Pose.CROUCHING : PlayerState.Pose.STANDING; // getDesiredPose
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
        return Colliders.noCollision(world, context(), candidate.box(x, y, z).inflate(-1.0E-7, -1.0E-7, -1.0E-7));
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

    /** No block in the cells the two boxes span holds a fluid but water and lava, nor is powder snow. */
    private void requireInScope(Box a, Box b, String when) throws Colliders.Unknown {
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
                    byte fluid = states.fluidKind(s);
                    if (fluid == BlockStates.FLUID_OTHER || states.block(s).equals(POWDER_SNOW)) {
                        throw new Colliders.Unknown(states.block(s) + " inside the box " + when + " (not modelled)");
                    }
                }
            }
        }
    }

    /**
     * {@code LivingEntity.onClimbable} for a player neither flying nor a spectator: the block at the feet
     * ({@code getInBlockState}) is climbable (scaffolding too), or an open trapdoor over a ladder facing the same way
     * ({@code trapdoorUsableAsLadder}).
     */
    private boolean onClimbable() throws Colliders.Unknown {
        BlockPos feet = BlockPos.containing(x, y, z);
        int s = state(feet);
        String block = states.block(s);
        if (fallFlying && CAN_GLIDE_THROUGH.contains(block)) {
            return false;
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

    /**
     * {@code handleOnClimbable}: on a climbable block the motion is held to 0.15 a tick, and shift holds the player still,
     * but in scaffolding (which shift lets the player sink through).
     */
    private Vec3d handleOnClimbable(Vec3d d) throws Colliders.Unknown {
        if (!onClimbable()) {
            return d;
        }
        fallDistance = 0.0; // resetFallDistance
        double cx = VanillaMath.clamp(d.x(), -0.15F, 0.15F);
        double cz = VanillaMath.clamp(d.z(), -0.15F, 0.15F);
        double cy = Math.max(d.y(), -0.15F);
        if (cy < 0.0 && !states.block(state(BlockPos.containing(x, y, z))).equals(Colliders.SCAFFOLDING) && keys.shift()) { // isSuppressingSlidingDownLadder
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
