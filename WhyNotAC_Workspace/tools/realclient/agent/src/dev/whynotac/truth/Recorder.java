package dev.whynotac.truth;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Called by the instrumented {@code LocalPlayer.tick()} at its start ({@link #pre}) and before every
 * return ({@link #post}), and by {@code AbstractBoat.tick()} likewise ({@link #boatPre}, {@link #boatPost}: only
 * for a boat the local player steers). Writes one JSON line per call with the exact state: doubles and
 * floats as their raw bits (hex), so nothing is rounded on the way.
 *
 * <p>Runs on the client's main thread only. Never throws into the game: a failure disables recording
 * and is reported once. When the JVM shuts down (the run ends the client with SIGTERM), the game thread
 * keeps ticking until the JVM halts: the shutdown hook waits for a tick in progress to write its end, and
 * no tick starts after it (the game thread waits in {@link #pre} until the halt), so every movement packet
 * the client sent has its tick on record.
 */
public final class Recorder {

    private static Mappings mappings;
    private static Path output;
    private static BufferedWriter out;
    private static boolean failed;
    private static long calls;
    private static Object ticking;   // the player whose tick() is running (set by pre, read by post)
    private static final Object LOCK = new Object();
    private static boolean inTick;   // between pre and post
    private static boolean closing;  // the shutdown hook has run: no tick may start

    // resolved on first use (the classes are loaded by then)
    private static Field position, deltaMovement, yRot, xRot, onGround, horizontalCollision, verticalCollision,
            verticalCollisionBelow, minorHorizontalCollision, fallDistance, tickCount, wasTouchingWater, stuckSpeedMultiplier;
    private static Field xxa, yya, zza, jumping;
    private static Field xLast, yLast, zLast, yRotLast, xRotLast, positionReminder, wasSprinting, lastOnGround, lastHorizontalCollision, input;
    private static Field keyPresses, connection, clientLoaded;
    private static Field keyForward, keyBackward, keyLeft, keyRight, keyJump, keyShift, keySprint;
    private static Field vecX, vecY, vecZ;
    // hidden state the movement step reads: supporting block, jump delay, speed, sneak state, the input's vector
    private static Field mainSupportingBlockPos, onGroundNoBlocks, noJumpDelay, speed, crouching, sprintTriggerTime, autoJumpTime, autoJumpEnabled, moveVector;
    private static Field posX, posY, posZ, vec2X, vec2Y;
    // the active effects (the client keeps each until the server removes it) and their amplifiers
    private static Field activeEffects, amplifier;
    // the item in use (LocalPlayer.startedUsingItem, LivingEntity.useItem)
    private static Field startedUsingItem, useItem;
    // water: the tags on the eye (fluidOnEyes), the fluid heights, Player.wasUnderwater
    private static Field fluidOnEyes, fluidHeight, wasUnderwater;
    private static Method isSprinting, isShiftKeyDown, getPose, isPassenger, isSwimming;
    // gliding: the shared flag, and whether an equipped item can glide (the item part of LivingEntity.canGlide)
    private static Method isFallFlying, getItemBySlot, canGlideUsing;
    private static Method level, getWorldBorder, borderMinX, borderMinZ, borderMaxX, borderMaxZ;
    private static Field isInPowderSnow;
    // a riptide's spin attack: the client's own count (startAutoSpinAttack) and the server's living flag 4 (its pose)
    private static Field autoSpinAttackTicks;
    private static Method isAutoSpinAttack;
    // a boat the player steers: AbstractBoat's own state, the entity's id, whether it is interpolating
    private static Field boatDeltaRotation, boatStatus, boatOldStatus, boatWaterLevel, boatLandFriction, boatLastYd, boatInputLeft, boatInputRight,
            boatInputUp, boatInputDown, entityId, firstTick;
    private static Method getControllingPassenger, isNoGravity, getInterpolation, hasActiveInterpolation, getType, getPassengers;
    private static Method interpolationPosition, interpolationYRot, interpolationXRot;
    // a horse the player rides: AbstractHorse's jump and rearing state, the attributes its tick reads, its rider's input
    private static Field horseJumpPendingScale, horseAllowStandSliding, horseStandCounter, livingXxa, livingZza, livingJumping;
    private static Method horseIsStanding, maxUpStep, getEffectiveGravity, getAttributeValue, entityControllingPassenger, entityInterpolation;
    private static Object movementSpeed, jumpStrength;
    private static Object tickingHorse; // the horse whose tick() is running, if it is recorded
    private static Object tickingCamel; // the camel whose tick() is running, if it is recorded (its AbstractHorse.tick() records nothing)
    private static Object tickingPig; // the pig whose Mob.tick() is running, if it is recorded
    // a pig the player steers: its ItemBasedSteering (the boost it counts itself)
    private static Field pigSteering, steeringBoosting, steeringBoostTime;
    private static Method steeringBoostTimeTotal;
    private static Class<?> pigClass;
    // a strider the player steers: its steering likewise, its suffocation, and whether its client runs its AI
    private static Field striderSteering;
    private static Method striderIsSuffocating, mobIsNoAi;
    private static Class<?> striderClass;
    // a happy ghast the player steers: whether it stays still, its flying speed
    private static Method ghastStaysStill;
    private static Object flyingSpeed;
    private static Class<?> ghastClass;
    // a nautilus the player rides: the dash its rider's jump left pending, its cooldown, its dashing flag
    private static Field nautilusJumpPendingScale, nautilusDashCooldown;
    private static Method nautilusIsDashing;
    private static Class<?> nautilusClass;
    // a camel the player rides: its dash's cooldown and flag; any rider's sprint
    private static Field camelDashCooldown;
    private static Method camelIsDashing, entityIsSprinting;
    private static Class<?> camelClass;
    private static Class<?> localPlayerClass;
    private static Object tickingBoat; // the boat whose tick() is running, if it is recorded (set by boatPre, read by boatPost)
    private static Object feetSlot;
    private static Object[] equipmentSlots;

    private Recorder() {}

    static void configure(Mappings m, Path file) {
        mappings = m;
        output = file;
    }

    public static void pre(Object player) {
        synchronized (LOCK) {
            while (closing) {
                try {
                    LOCK.wait(); // until the JVM halts: a tick run now would send its packet with nothing on record
                } catch (InterruptedException e) {
                    // keep waiting (the wait cleared the flag)
                }
            }
            inTick = true;
            ticking = player;
            write("pre", player);
        }
    }

    /** Takes no argument: the client is ProGuard-processed, and its frames mark {@code this} dead before the return. */
    public static void post() {
        synchronized (LOCK) {
            write("post", ticking);
            inTick = false;
            LOCK.notifyAll();
        }
    }

    /** At the start of {@code AbstractBoat.tick()}: recorded when the local player steers the boat (its controlling passenger). */
    public static void boatPre(Object boat) {
        synchronized (LOCK) {
            tickingBoat = null;
            if (closing || failed) {
                return; // the player's tick would not run: no packet of this tick reaches the server
            }
            try {
                if (out == null) {
                    return; // the player has not ticked yet
                }
                if (localPlayerClass.isInstance(getControllingPassenger.invoke(boat))) {
                    tickingBoat = boat;
                    writeBoat("boatPre", boat);
                }
            } catch (Throwable e) {
                failed = true;
                System.err.println("[whynotac-truth] recording stopped: " + e);
                e.printStackTrace();
            }
        }
    }

    /** At the start of {@code AbstractHorse.tick()}: recorded when the local player rides and controls it. */
    public static void horsePre(Object horse) {
        synchronized (LOCK) {
            tickingHorse = null;
            if (closing || failed || out == null || camelClass.isInstance(horse)) {
                return; // a camel's: inside its Camel.tick(), which records it
            }
            try {
                Object rider = entityControllingPassenger.invoke(horse);
                if (localPlayerClass.isInstance(rider)) {
                    tickingHorse = horse;
                    writeHorse("horsePre", horse, rider);
                }
            } catch (Throwable e) {
                failed = true;
                System.err.println("[whynotac-truth] recording stopped: " + e);
                e.printStackTrace();
            }
        }
    }

    public static void horsePost() {
        synchronized (LOCK) {
            if (tickingHorse != null && !failed) {
                try {
                    writeHorse("horsePost", tickingHorse, entityControllingPassenger.invoke(tickingHorse));
                } catch (Throwable e) {
                    failed = true;
                    System.err.println("[whynotac-truth] recording stopped: " + e);
                    e.printStackTrace();
                }
            }
            tickingHorse = null;
        }
    }

    /** At the start of {@code Camel.tick()}: recorded as a horse is, its own tick around {@code AbstractHorse.tick()}. */
    public static void camelPre(Object camel) {
        synchronized (LOCK) {
            tickingCamel = null;
            if (closing || failed || out == null) {
                return;
            }
            try {
                Object rider = entityControllingPassenger.invoke(camel);
                if (localPlayerClass.isInstance(rider)) {
                    tickingCamel = camel;
                    writeHorse("horsePre", camel, rider);
                }
            } catch (Throwable e) {
                failed = true;
                System.err.println("[whynotac-truth] recording stopped: " + e);
                e.printStackTrace();
            }
        }
    }

    public static void camelPost() {
        synchronized (LOCK) {
            if (tickingCamel != null && !failed) {
                try {
                    writeHorse("horsePost", tickingCamel, entityControllingPassenger.invoke(tickingCamel));
                } catch (Throwable e) {
                    failed = true;
                    System.err.println("[whynotac-truth] recording stopped: " + e);
                    e.printStackTrace();
                }
            }
            tickingCamel = null;
        }
    }

    /** At the start of {@code Mob.tick()}: a pig the local player rides and controls is recorded as a horse is. */
    public static void mobPre(Object mob) {
        synchronized (LOCK) {
            if (closing || failed || out == null || !pigClass.isInstance(mob)) {
                return; // a horse's own tick runs this too, inside its AbstractHorse.tick()
            }
            tickingPig = null;
            try {
                Object rider = entityControllingPassenger.invoke(mob);
                if (localPlayerClass.isInstance(rider)) {
                    tickingPig = mob;
                    writeHorse("horsePre", mob, rider);
                }
            } catch (Throwable e) {
                failed = true;
                System.err.println("[whynotac-truth] recording stopped: " + e);
                e.printStackTrace();
            }
        }
    }

    public static void mobPost() {
        synchronized (LOCK) {
            if (tickingPig != null && !failed) {
                try {
                    writeHorse("horsePost", tickingPig, entityControllingPassenger.invoke(tickingPig));
                } catch (Throwable e) {
                    failed = true;
                    System.err.println("[whynotac-truth] recording stopped: " + e);
                    e.printStackTrace();
                }
            }
            tickingPig = null;
        }
    }

    private static void writeHorse(String hook, Object horse, Object rider) throws Throwable {
        StringBuilder line = new StringBuilder(640).append("{\"hook\":\"").append(hook).append("\",\"n\":").append(calls++)
                .append(",\"nanoTime\":").append(System.nanoTime())
                .append(",\"tickCount\":").append(tickCount.getInt(horse))
                .append(",\"id\":").append(entityId.getInt(horse));
        line.append(",\"type\":\"").append(String.valueOf(getType.invoke(horse)).replaceAll("[^A-Za-z0-9_.:-]", "_")).append('"');
        vec(line, "pos", position.get(horse));
        vec(line, "deltaMovement", deltaMovement.get(horse));
        vec(line, "stuckSpeedMultiplier", stuckSpeedMultiplier.get(horse));
        f32(line, "yRot", yRot.getFloat(horse));
        f32(line, "xRot", xRot.getFloat(horse));
        f64(line, "fallDistance", fallDistance.getDouble(horse));
        bool(line, "onGround", onGround.getBoolean(horse));
        bool(line, "horizontalCollision", horizontalCollision.getBoolean(horse));
        bool(line, "verticalCollision", verticalCollision.getBoolean(horse));
        bool(line, "verticalCollisionBelow", verticalCollisionBelow.getBoolean(horse));
        java.util.Optional<?> support = (java.util.Optional<?>) mainSupportingBlockPos.get(horse);
        line.append(",\"support\":").append(support.isEmpty() ? "null" : "[" + posX.getInt(support.get()) + "," + posY.getInt(support.get()) + ","
                + posZ.getInt(support.get()) + "]");
        bool(line, "onGroundNoBlocks", onGroundNoBlocks.getBoolean(horse));
        bool(line, "wasTouchingWater", wasTouchingWater.getBoolean(horse));
        bool(line, "inPowderSnow", isInPowderSnow.getBoolean(horse));
        bool(line, "noGravity", (Boolean) isNoGravity.invoke(horse));
        bool(line, "firstTick", firstTick.getBoolean(horse));
        Object interpolation = entityInterpolation.invoke(horse);
        boolean interpolating = interpolation != null && (Boolean) hasActiveInterpolation.invoke(interpolation);
        bool(line, "interpolating", interpolating);
        if (interpolating) { // ServerboundMoveVehiclePacket.fromEntity sends these while the entity interpolates
            vec(line, "interpolationPos", interpolationPosition.invoke(interpolation));
            f32(line, "interpolationYRot", (Float) interpolationYRot.invoke(interpolation));
            f32(line, "interpolationXRot", (Float) interpolationXRot.invoke(interpolation));
        }
        line.append(",\"noJumpDelay\":").append(noJumpDelay.getInt(horse));
        bool(line, "jumping", livingJumping.getBoolean(horse));
        double lava = 0.0;
        for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) fluidHeight.get(horse)).entrySet()) {
            if (String.valueOf(e.getKey()).contains(" / minecraft:lava]")) {
                lava = ((Number) e.getValue()).doubleValue();
            }
        }
        f64(line, "lavaHeight", lava);
        boolean strider = striderClass.isInstance(horse);
        boolean ghast = ghastClass.isInstance(horse);
        boolean nautilus = nautilusClass.isInstance(horse);
        boolean pig = pigClass.isInstance(horse) || strider;
        if (strider) {
            bool(line, "suffocating", (Boolean) striderIsSuffocating.invoke(horse));
            bool(line, "noAi", (Boolean) mobIsNoAi.invoke(horse));
        }
        if (nautilus) { // no rearing; the dash pending, its cooldown and flag
            f32(line, "jumpPendingScale", nautilusJumpPendingScale.getFloat(horse));
            bool(line, "allowStandSliding", false);
            bool(line, "standing", false);
            line.append(",\"standCounter\":0");
            line.append(",\"dashCooldown\":").append(nautilusDashCooldown.getInt(horse));
            bool(line, "dashing", (Boolean) nautilusIsDashing.invoke(horse));
        } else if (ghast) { // no jump nor rearing; whether it stays still, its flying speed
            f32(line, "jumpPendingScale", 0.0F);
            bool(line, "allowStandSliding", false);
            bool(line, "standing", false);
            line.append(",\"standCounter\":0");
            bool(line, "staysStill", (Boolean) ghastStaysStill.invoke(horse));
            f64(line, "flyingSpeed", (Double) getAttributeValue.invoke(horse, flyingSpeed));
        } else if (pig) { // no jump nor rearing; the boost its steering counts, and the boost time the client holds
            f32(line, "jumpPendingScale", 0.0F);
            bool(line, "allowStandSliding", false);
            bool(line, "standing", false);
            line.append(",\"standCounter\":0");
            Object steering = (strider ? striderSteering : pigSteering).get(horse);
            bool(line, "boosting", steeringBoosting.getBoolean(steering));
            line.append(",\"boostTime\":").append(steeringBoostTime.getInt(steering));
            line.append(",\"boostTimeTotal\":").append((Integer) steeringBoostTimeTotal.invoke(steering));
        } else {
            f32(line, "jumpPendingScale", horseJumpPendingScale.getFloat(horse));
            bool(line, "allowStandSliding", horseAllowStandSliding.getBoolean(horse));
            bool(line, "standing", (Boolean) horseIsStanding.invoke(horse));
            line.append(",\"standCounter\":").append(horseStandCounter.getInt(horse));
            if (camelClass.isInstance(horse)) {
                line.append(",\"dashCooldown\":").append(camelDashCooldown.getInt(horse));
                bool(line, "dashing", (Boolean) camelIsDashing.invoke(horse));
            }
        }
        f32(line, "speed", speed.getFloat(horse));
        f64(line, "movementSpeed", (Double) getAttributeValue.invoke(horse, movementSpeed));
        f64(line, "jumpStrength", pig || ghast || nautilus ? 0.0 : (Double) getAttributeValue.invoke(horse, jumpStrength));
        f32(line, "maxUpStep", (Float) maxUpStep.invoke(horse));
        f64(line, "gravity", (Double) getEffectiveGravity.invoke(horse));
        effects(line, (java.util.Map<?, ?>) activeEffects.get(horse));
        line.append(",\"passengers\":").append(((java.util.List<?>) getPassengers.invoke(horse)).size());
        if (rider != null) { // what its tick reads of its rider (LivingEntity.travelRidden, AbstractHorse.tickRidden)
            f32(line, "riderXxa", livingXxa.getFloat(rider));
            f32(line, "riderZza", livingZza.getFloat(rider));
            f32(line, "riderYRot", yRot.getFloat(rider));
            f32(line, "riderXRot", xRot.getFloat(rider));
            bool(line, "riderJumping", livingJumping.getBoolean(rider));
            bool(line, "riderSprinting", (Boolean) entityIsSprinting.invoke(rider));
        }
        out.append(line).append("}\n");
    }

    public static void boatPost() {
        synchronized (LOCK) {
            if (tickingBoat != null && !failed) {
                writeBoat("boatPost", tickingBoat);
            }
            tickingBoat = null;
        }
    }

    private static void writeBoat(String hook, Object boat) {
        try {
            StringBuilder line = new StringBuilder(512).append("{\"hook\":\"").append(hook).append("\",\"n\":").append(calls++)
                    .append(",\"nanoTime\":").append(System.nanoTime())
                    .append(",\"tickCount\":").append(tickCount.getInt(boat))
                    .append(",\"id\":").append(entityId.getInt(boat));
            line.append(",\"type\":\"").append(String.valueOf(getType.invoke(boat)).replaceAll("[^A-Za-z0-9_.:-]", "_")).append('"');
            vec(line, "pos", position.get(boat));
            vec(line, "deltaMovement", deltaMovement.get(boat));
            vec(line, "stuckSpeedMultiplier", stuckSpeedMultiplier.get(boat));
            f32(line, "yRot", yRot.getFloat(boat));
            f32(line, "xRot", xRot.getFloat(boat));
            f32(line, "deltaRotation", boatDeltaRotation.getFloat(boat));
            // AbstractBoat.Status by its ordinal (its constants' names are obfuscated): IN_WATER, UNDER_WATER, UNDER_FLOWING_WATER, ON_LAND, IN_AIR
            Object status = boatStatus.get(boat);
            Object oldStatus = boatOldStatus.get(boat);
            line.append(",\"status\":").append(status == null ? -1 : ((Enum<?>) status).ordinal());
            line.append(",\"oldStatus\":").append(oldStatus == null ? -1 : ((Enum<?>) oldStatus).ordinal());
            f64(line, "waterLevel", boatWaterLevel.getDouble(boat));
            f32(line, "landFriction", boatLandFriction.getFloat(boat));
            f64(line, "lastYd", boatLastYd.getDouble(boat));
            f64(line, "fallDistance", fallDistance.getDouble(boat));
            bool(line, "onGround", onGround.getBoolean(boat));
            bool(line, "horizontalCollision", horizontalCollision.getBoolean(boat));
            bool(line, "verticalCollision", verticalCollision.getBoolean(boat));
            bool(line, "verticalCollisionBelow", verticalCollisionBelow.getBoolean(boat));
            java.util.Optional<?> support = (java.util.Optional<?>) mainSupportingBlockPos.get(boat);
            line.append(",\"support\":").append(support.isEmpty() ? "null" : "[" + posX.getInt(support.get()) + "," + posY.getInt(support.get()) + ","
                    + posZ.getInt(support.get()) + "]");
            bool(line, "onGroundNoBlocks", onGroundNoBlocks.getBoolean(boat));
            line.append(",\"input\":\"").append(boatInputLeft.getBoolean(boat) ? 'L' : '-').append(boatInputRight.getBoolean(boat) ? 'R' : '-')
                    .append(boatInputUp.getBoolean(boat) ? 'U' : '-').append(boatInputDown.getBoolean(boat) ? 'D' : '-').append('"');
            bool(line, "noGravity", (Boolean) isNoGravity.invoke(boat));
            Object interpolation = getInterpolation.invoke(boat);
            boolean interpolating = (Boolean) hasActiveInterpolation.invoke(interpolation);
            bool(line, "interpolating", interpolating);
            if (interpolating) { // ServerboundMoveVehiclePacket.fromEntity sends these while the entity interpolates
                vec(line, "interpolationPos", interpolationPosition.invoke(interpolation));
                f32(line, "interpolationYRot", (Float) interpolationYRot.invoke(interpolation));
                f32(line, "interpolationXRot", (Float) interpolationXRot.invoke(interpolation));
            }
            bool(line, "firstTick", firstTick.getBoolean(boat));
            bool(line, "wasTouchingWater", wasTouchingWater.getBoolean(boat));
            bool(line, "inPowderSnow", isInPowderSnow.getBoolean(boat));
            line.append(",\"passengers\":").append(((java.util.List<?>) getPassengers.invoke(boat)).size());
            out.append(line).append("}\n");
        } catch (Throwable e) { // never into the game
            failed = true;
            System.err.println("[whynotac-truth] recording stopped: " + e);
            e.printStackTrace();
        }
    }

    private static void write(String hook, Object player) {
        if (failed) {
            return;
        }
        try {
            if (out == null) {
                resolve(player.getClass().getClassLoader());
                out = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
                Runtime.getRuntime().addShutdownHook(new Thread(Recorder::close, "whynotac-truth-close"));
            }
            StringBuilder line = new StringBuilder(512).append("{\"hook\":\"").append(hook).append("\",\"n\":").append(calls++)
                    .append(",\"nanoTime\":").append(System.nanoTime())
                    .append(",\"tickCount\":").append(tickCount.getInt(player));
            // tick() returns at once while the connection has not loaded the player; a passenger sends a Rot packet
            // every tick instead of calling sendPosition()
            bool(line, "loaded", clientLoaded.getBoolean(connection.get(player)));
            bool(line, "passenger", (Boolean) isPassenger.invoke(player));
            vec(line, "pos", position.get(player));
            vec(line, "deltaMovement", deltaMovement.get(player));
            vec(line, "stuckSpeedMultiplier", stuckSpeedMultiplier.get(player));
            f32(line, "yRot", yRot.getFloat(player));
            f32(line, "xRot", xRot.getFloat(player));
            bool(line, "onGround", onGround.getBoolean(player));
            bool(line, "horizontalCollision", horizontalCollision.getBoolean(player));
            bool(line, "verticalCollision", verticalCollision.getBoolean(player));
            bool(line, "verticalCollisionBelow", verticalCollisionBelow.getBoolean(player));
            bool(line, "minorHorizontalCollision", minorHorizontalCollision.getBoolean(player));
            f64(line, "fallDistance", fallDistance.getDouble(player));
            bool(line, "wasTouchingWater", wasTouchingWater.getBoolean(player));
            bool(line, "swimming", (Boolean) isSwimming.invoke(player));
            bool(line, "fallFlying", (Boolean) isFallFlying.invoke(player)); // the shared flag 7
            bool(line, "glider", glider(player));
            bool(line, "inPowderSnow", isInPowderSnow.getBoolean(player));
            line.append(",\"autoSpinAttackTicks\":").append(autoSpinAttackTicks.getInt(player));
            bool(line, "autoSpinAttack", (Boolean) isAutoSpinAttack.invoke(player));
            // canEntityWalkOnPowderSnow: ItemStack.toString is "count item", Item.toString its registered name
            bool(line, "leatherBoots", String.valueOf(getItemBySlot.invoke(player, feetSlot)).endsWith(" minecraft:leather_boots"));
            // the level's world border as the collision reads it (it steps after the entities: at "pre", the move's)
            Object worldBorder = getWorldBorder.invoke(level.invoke(player));
            line.append(",\"border\":[");
            Method[] bounds = {borderMinX, borderMinZ, borderMaxX, borderMaxZ};
            for (int i = 0; i < bounds.length; i++) {
                line.append(i == 0 ? "\"" : ",\"").append(Long.toHexString(Double.doubleToRawLongBits((Double) bounds[i].invoke(worldBorder)))).append('"');
            }
            line.append(']');
            // TagKey.toString: "TagKey[minecraft:fluid / minecraft:water]"
            bool(line, "eyeInWater", String.valueOf(fluidOnEyes.get(player)).contains(" / minecraft:water]"));
            bool(line, "wasUnderwater", wasUnderwater.getBoolean(player));
            double water = 0.0;
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) fluidHeight.get(player)).entrySet()) {
                if (String.valueOf(e.getKey()).contains(" / minecraft:water]")) {
                    water = ((Number) e.getValue()).doubleValue();
                }
            }
            f64(line, "waterHeight", water);
            f32(line, "xxa", xxa.getFloat(player));
            f32(line, "yya", yya.getFloat(player));
            f32(line, "zza", zza.getFloat(player));
            bool(line, "jumping", jumping.getBoolean(player));
            bool(line, "sprinting", (Boolean) isSprinting.invoke(player));
            bool(line, "shiftKeyDown", (Boolean) isShiftKeyDown.invoke(player));
            line.append(",\"pose\":\"").append(getPose.invoke(player)).append('"');
            java.util.Optional<?> support = (java.util.Optional<?>) mainSupportingBlockPos.get(player);
            line.append(",\"support\":").append(support.isEmpty() ? "null" : "[" + posX.getInt(support.get()) + "," + posY.getInt(support.get()) + ","
                    + posZ.getInt(support.get()) + "]");
            bool(line, "onGroundNoBlocks", onGroundNoBlocks.getBoolean(player));
            line.append(",\"noJumpDelay\":").append(noJumpDelay.getInt(player));
            f32(line, "speed", speed.getFloat(player));
            bool(line, "crouching", crouching.getBoolean(player));
            line.append(",\"sprintTriggerTime\":").append(sprintTriggerTime.getInt(player));
            line.append(",\"autoJumpTime\":").append(autoJumpTime.getInt(player));
            bool(line, "autoJumpEnabled", autoJumpEnabled.getBoolean(player));
            effects(line, (java.util.Map<?, ?>) activeEffects.get(player));
            bool(line, "usingItem", startedUsingItem.getBoolean(player));
            // ItemStack.toString: "<count> <item key>"
            line.append(",\"useItem\":\"").append(String.valueOf(useItem.get(player)).replaceAll("[^A-Za-z0-9_.: -]", "_")).append('"');
            Object move = moveVector.get(input.get(player));
            line.append(",\"moveVector\":[\"").append(Integer.toHexString(Float.floatToRawIntBits(vec2X.getFloat(move)))).append("\",\"")
                    .append(Integer.toHexString(Float.floatToRawIntBits(vec2Y.getFloat(move)))).append("\"]");
            f64(line, "xLast", xLast.getDouble(player));
            f64(line, "yLast", yLast.getDouble(player));
            f64(line, "zLast", zLast.getDouble(player));
            f32(line, "yRotLast", yRotLast.getFloat(player));
            f32(line, "xRotLast", xRotLast.getFloat(player));
            line.append(",\"positionReminder\":").append(positionReminder.getInt(player));
            bool(line, "wasSprinting", wasSprinting.getBoolean(player));
            bool(line, "lastOnGround", lastOnGround.getBoolean(player));
            bool(line, "lastHorizontalCollision", lastHorizontalCollision.getBoolean(player));
            Object keys = keyPresses.get(input.get(player));
            line.append(",\"keys\":\"")
                    .append(keyForward.getBoolean(keys) ? 'W' : '-').append(keyBackward.getBoolean(keys) ? 'S' : '-')
                    .append(keyLeft.getBoolean(keys) ? 'A' : '-').append(keyRight.getBoolean(keys) ? 'D' : '-')
                    .append(keyJump.getBoolean(keys) ? 'J' : '-').append(keyShift.getBoolean(keys) ? 'H' : '-')
                    .append(keySprint.getBoolean(keys) ? 'R' : '-').append('"');
            out.append(line).append("}\n");
            if ((calls & 63) == 0) {
                out.flush();
            }
        } catch (Throwable e) { // never into the game
            failed = true;
            System.err.println("[whynotac-truth] recording stopped: " + e);
            e.printStackTrace();
        }
    }

    private static void resolve(ClassLoader loader) throws ReflectiveOperationException {
        String entity = "net.minecraft.world.entity.Entity";
        String living = "net.minecraft.world.entity.LivingEntity";
        String local = TruthAgent.LOCAL_PLAYER;
        String vec3 = "net.minecraft.world.phys.Vec3";
        String clientInput = "net.minecraft.client.player.ClientInput";
        String inputRecord = "net.minecraft.world.entity.player.Input";
        position = field(loader, entity, "position");
        deltaMovement = field(loader, entity, "deltaMovement");
        stuckSpeedMultiplier = field(loader, entity, "stuckSpeedMultiplier");
        yRot = field(loader, entity, "yRot");
        xRot = field(loader, entity, "xRot");
        onGround = field(loader, entity, "onGround");
        horizontalCollision = field(loader, entity, "horizontalCollision");
        verticalCollision = field(loader, entity, "verticalCollision");
        verticalCollisionBelow = field(loader, entity, "verticalCollisionBelow");
        minorHorizontalCollision = field(loader, entity, "minorHorizontalCollision");
        fallDistance = field(loader, entity, "fallDistance");
        tickCount = field(loader, entity, "tickCount");
        wasTouchingWater = field(loader, entity, "wasTouchingWater");
        xxa = field(loader, living, "xxa");
        yya = field(loader, living, "yya");
        zza = field(loader, living, "zza");
        jumping = field(loader, living, "jumping");
        xLast = field(loader, local, "xLast");
        yLast = field(loader, local, "yLast");
        zLast = field(loader, local, "zLast");
        yRotLast = field(loader, local, "yRotLast");
        xRotLast = field(loader, local, "xRotLast");
        positionReminder = field(loader, local, "positionReminder");
        wasSprinting = field(loader, local, "wasSprinting");
        lastOnGround = field(loader, local, "lastOnGround");
        lastHorizontalCollision = field(loader, local, "lastHorizontalCollision");
        input = field(loader, local, "input");
        connection = field(loader, local, "connection");
        clientLoaded = field(loader, "net.minecraft.client.multiplayer.ClientPacketListener", "clientLoaded");
        keyPresses = field(loader, clientInput, "keyPresses");
        keyForward = field(loader, inputRecord, "forward");
        keyBackward = field(loader, inputRecord, "backward");
        keyLeft = field(loader, inputRecord, "left");
        keyRight = field(loader, inputRecord, "right");
        keyJump = field(loader, inputRecord, "jump");
        keyShift = field(loader, inputRecord, "shift");
        keySprint = field(loader, inputRecord, "sprint");
        vecX = field(loader, vec3, "x");
        vecY = field(loader, vec3, "y");
        vecZ = field(loader, vec3, "z");
        mainSupportingBlockPos = field(loader, entity, "mainSupportingBlockPos");
        onGroundNoBlocks = field(loader, entity, "onGroundNoBlocks");
        noJumpDelay = field(loader, living, "noJumpDelay");
        autoSpinAttackTicks = field(loader, living, "autoSpinAttackTicks");
        isAutoSpinAttack = method(loader, living, "isAutoSpinAttack()");
        speed = field(loader, living, "speed");
        crouching = field(loader, local, "crouching");
        sprintTriggerTime = field(loader, local, "sprintTriggerTime");
        autoJumpTime = field(loader, local, "autoJumpTime");
        autoJumpEnabled = field(loader, local, "autoJumpEnabled");
        moveVector = field(loader, clientInput, "moveVector");
        posX = field(loader, "net.minecraft.core.Vec3i", "x");
        posY = field(loader, "net.minecraft.core.Vec3i", "y");
        posZ = field(loader, "net.minecraft.core.Vec3i", "z");
        vec2X = field(loader, "net.minecraft.world.phys.Vec2", "x");
        vec2Y = field(loader, "net.minecraft.world.phys.Vec2", "y");
        activeEffects = field(loader, living, "activeEffects");
        startedUsingItem = field(loader, local, "startedUsingItem");
        useItem = field(loader, living, "useItem");
        amplifier = field(loader, "net.minecraft.world.effect.MobEffectInstance", "amplifier");
        isSprinting = method(loader, entity, "isSprinting()");
        isShiftKeyDown = method(loader, entity, "isShiftKeyDown()");
        getPose = method(loader, entity, "getPose()");
        isPassenger = method(loader, entity, "isPassenger()");
        isSwimming = method(loader, entity, "isSwimming()");
        isFallFlying = method(loader, living, "isFallFlying()");
        Class<?> slot = Class.forName(mappings.get("net.minecraft.world.entity.EquipmentSlot").obf(), false, loader);
        Class<?> stack = Class.forName(mappings.get("net.minecraft.world.item.ItemStack").obf(), false, loader);
        equipmentSlots = slot.getEnumConstants();
        getItemBySlot = method(loader, living, "getItemBySlot(net.minecraft.world.entity.EquipmentSlot)", slot);
        canGlideUsing = method(loader, living, "canGlideUsing(net.minecraft.world.item.ItemStack,net.minecraft.world.entity.EquipmentSlot)", stack, slot);
        isInPowderSnow = field(loader, entity, "isInPowderSnow");
        Field feet = slot.getDeclaredField(mappings.field("net.minecraft.world.entity.EquipmentSlot", "FEET"));
        feet.setAccessible(true);
        feetSlot = feet.get(null);
        level = method(loader, entity, "level()");
        getWorldBorder = method(loader, "net.minecraft.client.multiplayer.ClientLevel", "getWorldBorder()"); // declared there, not in Level
        String border = "net.minecraft.world.level.border.WorldBorder";
        borderMinX = method(loader, border, "getMinX()");
        borderMinZ = method(loader, border, "getMinZ()");
        borderMaxX = method(loader, border, "getMaxX()");
        borderMaxZ = method(loader, border, "getMaxZ()");
        fluidOnEyes = field(loader, entity, "fluidOnEyes");
        fluidHeight = field(loader, entity, "fluidHeight");
        wasUnderwater = field(loader, "net.minecraft.world.entity.player.Player", "wasUnderwater");
        String boat = TruthAgent.ABSTRACT_BOAT;
        boatDeltaRotation = field(loader, boat, "deltaRotation");
        boatStatus = field(loader, boat, "status");
        boatOldStatus = field(loader, boat, "oldStatus");
        boatWaterLevel = field(loader, boat, "waterLevel");
        boatLandFriction = field(loader, boat, "landFriction");
        boatLastYd = field(loader, boat, "lastYd");
        boatInputLeft = field(loader, boat, "inputLeft");
        boatInputRight = field(loader, boat, "inputRight");
        boatInputUp = field(loader, boat, "inputUp");
        boatInputDown = field(loader, boat, "inputDown");
        entityId = field(loader, entity, "id");
        firstTick = field(loader, entity, "firstTick");
        getControllingPassenger = method(loader, boat, "getControllingPassenger()");
        isNoGravity = method(loader, entity, "isNoGravity()");
        getInterpolation = method(loader, boat, "getInterpolation()");
        hasActiveInterpolation = method(loader, "net.minecraft.world.entity.InterpolationHandler", "hasActiveInterpolation()");
        interpolationPosition = method(loader, "net.minecraft.world.entity.InterpolationHandler", "position()");
        interpolationYRot = method(loader, "net.minecraft.world.entity.InterpolationHandler", "yRot()");
        interpolationXRot = method(loader, "net.minecraft.world.entity.InterpolationHandler", "xRot()");
        String horse = "net.minecraft.world.entity.animal.equine.AbstractHorse";
        horseJumpPendingScale = field(loader, horse, "playerJumpPendingScale");
        horseAllowStandSliding = field(loader, horse, "allowStandSliding");
        horseStandCounter = field(loader, horse, "standCounter");
        horseIsStanding = method(loader, horse, "isStanding()");
        pigClass = Class.forName(mappings.get(TruthAgent.PIG).obf(), false, loader);
        pigSteering = field(loader, TruthAgent.PIG, "steering");
        striderClass = Class.forName(mappings.get(TruthAgent.STRIDER).obf(), false, loader);
        striderSteering = field(loader, TruthAgent.STRIDER, "steering");
        striderIsSuffocating = method(loader, TruthAgent.STRIDER, "isSuffocating()");
        mobIsNoAi = method(loader, TruthAgent.MOB, "isNoAi()");
        ghastClass = Class.forName(mappings.get(TruthAgent.HAPPY_GHAST).obf(), false, loader);
        ghastStaysStill = method(loader, TruthAgent.HAPPY_GHAST, "staysStill()");
        nautilusClass = Class.forName(mappings.get(TruthAgent.NAUTILUS).obf(), false, loader);
        nautilusJumpPendingScale = field(loader, TruthAgent.NAUTILUS, "playerJumpPendingScale");
        nautilusDashCooldown = field(loader, TruthAgent.NAUTILUS, "dashCooldown");
        nautilusIsDashing = method(loader, TruthAgent.NAUTILUS, "isDashing()");
        camelClass = Class.forName(mappings.get(TruthAgent.CAMEL).obf(), false, loader);
        camelDashCooldown = field(loader, TruthAgent.CAMEL, "dashCooldown");
        camelIsDashing = method(loader, TruthAgent.CAMEL, "isDashing()");
        entityIsSprinting = method(loader, "net.minecraft.world.entity.Entity", "isSprinting()");
        String steering = "net.minecraft.world.entity.ItemBasedSteering";
        steeringBoosting = field(loader, steering, "boosting");
        steeringBoostTime = field(loader, steering, "boostTime");
        steeringBoostTimeTotal = method(loader, steering, "boostTimeTotal()");
        entityControllingPassenger = method(loader, "net.minecraft.world.entity.Entity", "getControllingPassenger()"); // virtual: any vehicle
        entityInterpolation = method(loader, "net.minecraft.world.entity.Entity", "getInterpolation()");
        livingXxa = field(loader, living, "xxa");
        livingZza = field(loader, living, "zza");
        livingJumping = field(loader, living, "jumping");
        maxUpStep = method(loader, living, "maxUpStep()");
        getEffectiveGravity = method(loader, living, "getEffectiveGravity()");
        Class<?> holder = Class.forName(mappings.get("net.minecraft.core.Holder").obf(), false, loader);
        getAttributeValue = method(loader, living, "getAttributeValue(net.minecraft.core.Holder)", holder);
        movementSpeed = staticField(loader, "net.minecraft.world.entity.ai.attributes.Attributes", "MOVEMENT_SPEED");
        jumpStrength = staticField(loader, "net.minecraft.world.entity.ai.attributes.Attributes", "JUMP_STRENGTH");
        flyingSpeed = staticField(loader, "net.minecraft.world.entity.ai.attributes.Attributes", "FLYING_SPEED");
        getType = method(loader, entity, "getType()");
        getPassengers = method(loader, entity, "getPassengers()");
        localPlayerClass = Class.forName(mappings.get(local).obf(), false, loader);
    }

    private static Object staticField(ClassLoader loader, String mojangClass, String name) throws ReflectiveOperationException {
        return field(loader, mojangClass, name).get(null);
    }

    private static Field field(ClassLoader loader, String mojangClass, String name) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(mappings.get(mojangClass).obf(), false, loader);
        Field f = owner.getDeclaredField(mappings.field(mojangClass, name));
        f.setAccessible(true);
        return f;
    }

    private static Method method(ClassLoader loader, String mojangClass, String signature, Class<?>... parameters) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(mappings.get(mojangClass).obf(), false, loader);
        Method m = owner.getDeclaredMethod(mappings.method(mojangClass, signature), parameters);
        m.setAccessible(true);
        return m;
    }

    /** {@code LivingEntity.canGlide}'s item part: an equipped item that can glide in its slot (an elytra not about to break). */
    private static boolean glider(Object player) throws ReflectiveOperationException {
        for (Object slot : equipmentSlots) {
            if ((Boolean) canGlideUsing.invoke(null, getItemBySlot.invoke(player, slot), slot)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code "effects":{"minecraft:jump_boost":1,...}}: each active effect's registry name and amplifier. The key
     * is a {@code Holder.Reference}, whose {@code toString} names its {@code ResourceKey}:
     * {@code Reference{ResourceKey[minecraft:mob_effect / minecraft:jump_boost]=...}}.
     */
    private static void effects(StringBuilder line, java.util.Map<?, ?> active) throws IllegalAccessException {
        line.append(",\"effects\":{");
        boolean first = true;
        for (java.util.Map.Entry<?, ?> e : active.entrySet()) {
            String holder = String.valueOf(e.getKey());
            int from = holder.indexOf(" / ");
            int to = from < 0 ? -1 : holder.indexOf(']', from);
            String name = to < 0 ? "unparsed:" + holder.replaceAll("[^A-Za-z0-9_.:/-]", "_") : holder.substring(from + 3, to);
            line.append(first ? "" : ",").append('"').append(name).append("\":").append(amplifier.getInt(e.getValue()));
            first = false;
        }
        line.append('}');
    }

    private static void vec(StringBuilder line, String name, Object vec) throws IllegalAccessException {
        line.append(",\"").append(name).append("\":[");
        line.append('"').append(Long.toHexString(Double.doubleToRawLongBits(vecX.getDouble(vec)))).append("\",");
        line.append('"').append(Long.toHexString(Double.doubleToRawLongBits(vecY.getDouble(vec)))).append("\",");
        line.append('"').append(Long.toHexString(Double.doubleToRawLongBits(vecZ.getDouble(vec)))).append("\"]");
    }

    private static void f64(StringBuilder line, String name, double value) {
        line.append(",\"").append(name).append("\":\"").append(Long.toHexString(Double.doubleToRawLongBits(value))).append('"');
    }

    private static void f32(StringBuilder line, String name, float value) {
        line.append(",\"").append(name).append("\":\"").append(Integer.toHexString(Float.floatToRawIntBits(value))).append('"');
    }

    private static void bool(StringBuilder line, String name, boolean value) {
        line.append(",\"").append(name).append("\":").append(value);
    }

    private static void close() {
        synchronized (LOCK) {
            closing = true;
            long deadline = System.nanoTime() + 2_000_000_000L; // a tick that threw never reaches post
            try {
                for (long left = deadline - System.nanoTime(); inTick && left > 0; left = deadline - System.nanoTime()) {
                    LOCK.wait(Math.max(1, left / 1_000_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                if (out != null) {
                    out.flush();
                    out.close();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        try {
            Thread.sleep(200); // the connection's event loop writes the last tick's packet before the JVM halts
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
