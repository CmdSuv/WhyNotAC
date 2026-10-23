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
 * return ({@link #post}). Writes one JSON line per call with the player's exact state: doubles and
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
