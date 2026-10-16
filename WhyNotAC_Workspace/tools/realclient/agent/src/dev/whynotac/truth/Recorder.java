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
 * and is reported once.
 */
public final class Recorder {

    private static Mappings mappings;
    private static Path output;
    private static BufferedWriter out;
    private static boolean failed;
    private static long calls;
    private static Object ticking;   // the player whose tick() is running (set by pre, read by post)

    // resolved on first use (the classes are loaded by then)
    private static Field position, deltaMovement, yRot, xRot, onGround, horizontalCollision, verticalCollision,
            verticalCollisionBelow, minorHorizontalCollision, fallDistance, tickCount, wasTouchingWater, stuckSpeedMultiplier;
    private static Field xxa, yya, zza, jumping;
    private static Field xLast, yLast, zLast, yRotLast, xRotLast, positionReminder, wasSprinting, lastOnGround, lastHorizontalCollision, input;
    private static Field keyPresses, connection, clientLoaded;
    private static Field keyForward, keyBackward, keyLeft, keyRight, keyJump, keyShift, keySprint;
    private static Field vecX, vecY, vecZ;
    // hidden state the movement step reads: supporting block, jump delay, speed, sneak state, the input's vector
    private static Field mainSupportingBlockPos, onGroundNoBlocks, noJumpDelay, speed, crouching, sprintTriggerTime, autoJumpTime, moveVector;
    private static Field posX, posY, posZ, vec2X, vec2Y;
    private static Method isSprinting, isShiftKeyDown, getPose, isPassenger;

    private Recorder() {}

    static void configure(Mappings m, Path file) {
        mappings = m;
        output = file;
    }

    public static void pre(Object player) {
        ticking = player;
        write("pre", player);
    }

    /** Takes no argument: the client is ProGuard-processed, and its frames mark {@code this} dead before the return. */
    public static void post() {
        write("post", ticking);
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
        moveVector = field(loader, clientInput, "moveVector");
        posX = field(loader, "net.minecraft.core.Vec3i", "x");
        posY = field(loader, "net.minecraft.core.Vec3i", "y");
        posZ = field(loader, "net.minecraft.core.Vec3i", "z");
        vec2X = field(loader, "net.minecraft.world.phys.Vec2", "x");
        vec2Y = field(loader, "net.minecraft.world.phys.Vec2", "y");
        isSprinting = method(loader, entity, "isSprinting()");
        isShiftKeyDown = method(loader, entity, "isShiftKeyDown()");
        getPose = method(loader, entity, "getPose()");
        isPassenger = method(loader, entity, "isPassenger()");
    }

    private static Field field(ClassLoader loader, String mojangClass, String name) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(mappings.get(mojangClass).obf(), false, loader);
        Field f = owner.getDeclaredField(mappings.field(mojangClass, name));
        f.setAccessible(true);
        return f;
    }

    private static Method method(ClassLoader loader, String mojangClass, String signature) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(mappings.get(mojangClass).obf(), false, loader);
        Method m = owner.getDeclaredMethod(mappings.method(mojangClass, signature));
        m.setAccessible(true);
        return m;
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
        try {
            if (out != null) {
                out.flush();
                out.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
