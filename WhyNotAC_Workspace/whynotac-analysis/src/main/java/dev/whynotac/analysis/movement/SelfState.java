package dev.whynotac.analysis.movement;

import dev.whynotac.physics.OnFoot;
import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The client's own player beyond its physics, as far as the on-foot step's scope needs it, in the order the
 * client processed what it was sent (decompiled 1.21.11 {@code ClientPacketListener}). Immutable: every
 * change makes a copy, so hypotheses can share it.
 *
 * @param ticking         {@code connection.hasClientLoaded()}: {@code LocalPlayer.tick} runs (from the tick in
 *                        which the client sent {@code PlayerLoaded} until the next login or respawn)
 * @param positionKnown   a teleport placed the player since it was created
 * @param firstTick       a new player whose first {@code sendPosition} has not run: auto-jump is enabled whatever the option
 * @param food            {@code FoodData.foodLevel}: a new player's is 20
 * @param gameMode        the local game mode id, -1 before the login
 * @param effects         the active effects the on-foot step reads, at the potency the client holds
 * @param speed           the movement speed attribute, its modifiers in the client's own order; null when that is
 *                        not followed (a table past its size, an unknown operation)
 * @param otherAttributes the other attributes on-foot movement reads whose value is not the default
 * @param vehicle         the entity the player rides ({@code -1}: none)
 * @param using           the item the client is using, or null ({@code LocalPlayer.isUsingItem}: the client's own, which
 *                        the server's living-entity flags start or stop when they differ)
 * @param border          the world border of the client's level ({@link ClientBorder})
 * @param rainLevel       the rain level of the client's level ({@code ClientLevel.setRainLevel}, from the weather's game
 *                        events: 0 a new level's, 0 at a start of rain, 1 at its end, then each change), NaN when not
 *                        known; {@code isRaining} is above 0.2
 */
public record SelfState(boolean ticking, boolean positionKnown, boolean firstTick, int food, float health, int gameMode, boolean flying, boolean mayFly,
        OnFoot.Effects effects, SpeedAttribute speed, Set<String> otherAttributes, boolean camera, int vehicle, boolean noGravity,
        Using using, ClientBorder border, float rainLevel) {

    static final String MOVEMENT_SPEED = "minecraft:movement_speed";
    /** Attributes the on-foot step reads, with the client's default value (decompiled {@code Attributes}). */
    static final java.util.Map<String, Double> OTHER_ATTRIBUTES = java.util.Map.of(
            "minecraft:jump_strength", (double) 0.42F,
            "minecraft:step_height", 0.6,
            "minecraft:gravity", 0.08,
            "minecraft:scale", 1.0,
            "minecraft:movement_efficiency", 0.0,
            "minecraft:sneaking_speed", 0.3,
            "minecraft:water_movement_efficiency", 0.0);
    static final int SURVIVAL = 0;
    static final int ADVENTURE = 2;

    public SelfState {
        otherAttributes = Set.copyOf(otherAttributes);
    }

    /** A new player's own state before any packet about it: the client's defaults. */
    static SelfState fresh(int gameMode, SpeedAttribute speed, Set<String> otherAttributes, ClientBorder border, float rainLevel) {
        return new SelfState(false, false, true, 20, 20.0F, gameMode, false, false, OnFoot.Effects.NONE, speed, otherAttributes, true, -1, false, null, border,
                rainLevel);
    }

    /** Before the login: nothing known about the session yet. */
    static final SelfState INITIAL = fresh(-1, SpeedAttribute.DEFAULT, Set.of(), ClientBorder.fresh(null), Float.NaN);

    /** Why the on-foot step cannot run now, or null. */
    String outOfScope() {
        if (gameMode != SURVIVAL && gameMode != ADVENTURE) {
            return "game mode " + gameMode;
        }
        if (flying || mayFly) {
            return "may fly";
        }
        if (speed == null) {
            return "movement speed modifiers not followed";
        }
        if (!otherAttributes.isEmpty()) {
            return "attribute " + new TreeSet<>(otherAttributes).first();
        }
        if (!camera) {
            return "another entity's camera";
        }
        if (vehicle != -1) {
            return "riding";
        }
        if (noGravity) {
            return "no gravity";
        }
        if (!(health > 0.0F)) {
            return "dead";
        }
        if (!positionKnown) {
            return "position not known";
        }
        return null;
    }

    boolean foodAllowsSprint() {
        return food > 6 || mayFly;
    }

    SelfState withTicking(boolean value) {
        return new SelfState(value, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera, vehicle,
                noGravity, using, border, rainLevel);
    }

    SelfState withPositionKnown() {
        return new SelfState(ticking, true, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera, vehicle,
                noGravity, using, border, rainLevel);
    }

    SelfState afterFirstTick() {
        return firstTick ? new SelfState(ticking, positionKnown, false, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera,
                vehicle, noGravity, using, border, rainLevel) : this;
    }

    SelfState withHealth(float newHealth, int newFood) {
        return new SelfState(ticking, positionKnown, firstTick, newFood, newHealth, gameMode, flying, mayFly, effects, speed, otherAttributes, camera,
                vehicle, noGravity, using, border, rainLevel);
    }

    SelfState withAbilities(boolean newFlying, boolean newMayFly) {
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, newFlying, newMayFly, effects, speed, otherAttributes, camera,
                vehicle, noGravity, using, border, rainLevel);
    }

    /** {@code MultiPlayerGameMode.setLocalMode}: the mode's abilities ({@code GameType.updatePlayerAbilities}). */
    SelfState withGameMode(int mode) {
        boolean creativeOrSpectator = mode == 1 || mode == 3;
        return new SelfState(ticking, positionKnown, firstTick, food, health, mode, mode == 3, creativeOrSpectator, effects, speed, otherAttributes, camera,
                vehicle, noGravity, using, border, rainLevel);
    }

    /**
     * {@code handleUpdateMobEffect} ({@code forceAddEffect}: the instance replaced, its amplifier clamped to 0..255)
     * or {@code handleRemoveMobEffect}. The client counts a duration down but never ends an effect itself: it lasts
     * until the server removes it. Effects the on-foot step does not read change nothing (speed and slowness act
     * through the attribute the server sends).
     */
    SelfState withEffect(String key, int amplifier, boolean active) {
        int level = active ? Math.clamp(amplifier, 0, 255) + 1 : 0;
        OnFoot.Effects e = effects;
        OnFoot.Effects next = switch (key) {
            case "minecraft:jump_boost" -> new OnFoot.Effects(level, e.levitation(), e.slowFalling(), e.blindness(), e.dolphinsGrace(), e.weaving());
            case "minecraft:levitation" -> new OnFoot.Effects(e.jumpBoost(), level, e.slowFalling(), e.blindness(), e.dolphinsGrace(), e.weaving());
            case "minecraft:slow_falling" -> new OnFoot.Effects(e.jumpBoost(), e.levitation(), active, e.blindness(), e.dolphinsGrace(), e.weaving());
            case "minecraft:blindness" -> new OnFoot.Effects(e.jumpBoost(), e.levitation(), e.slowFalling(), active, e.dolphinsGrace(), e.weaving());
            case "minecraft:dolphins_grace" -> new OnFoot.Effects(e.jumpBoost(), e.levitation(), e.slowFalling(), e.blindness(), active, e.weaving());
            case "minecraft:weaving" -> new OnFoot.Effects(e.jumpBoost(), e.levitation(), e.slowFalling(), e.blindness(), e.dolphinsGrace(), active);
            default -> e;
        };
        if (next.equals(e)) {
            return this;
        }
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, next, speed, otherAttributes, camera, vehicle,
                noGravity, using, border, rainLevel);
    }

    /** {@code handleUpdateAttributes}: each attribute's base and modifiers are replaced. */
    SelfState withAttributes(List<ClientboundPacket.UpdateAttributes.Attribute> attributes) {
        SpeedAttribute newSpeed = speed;
        Set<String> other = new TreeSet<>(otherAttributes);
        for (ClientboundPacket.UpdateAttributes.Attribute a : attributes) {
            if (a.key().equals(MOVEMENT_SPEED)) {
                newSpeed = SpeedAttribute.of(a);
            } else if (OTHER_ATTRIBUTES.containsKey(a.key())) {
                double[] range = dev.whynotac.world.AttributeValues.range(a, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                double expected = OTHER_ATTRIBUTES.get(a.key());
                if (range[0] == expected && range[1] == expected) {
                    other.remove(a.key());
                } else {
                    other.add(a.key());
                }
            }
        }
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, newSpeed, other, camera, vehicle,
                noGravity, using, border, rainLevel);
    }

    SelfState withSpeed(SpeedAttribute newSpeed) {
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, newSpeed, otherAttributes, camera,
                vehicle, noGravity, using, border, rainLevel);
    }

    SelfState withCamera(boolean self) {
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, self, vehicle,
                noGravity, using, border, rainLevel);
    }

    SelfState withVehicle(int value) {
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera, value,
                noGravity, using, border, rainLevel);
    }

    SelfState withNoGravity(boolean value) {
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera, vehicle,
                value, using, border, rainLevel);
    }

    /** The item in use: {@code startUsingItem}, {@code stopUsingItem}, or the use going on with the hand's stack. */
    SelfState withUsing(Using newUsing) {
        return java.util.Objects.equals(newUsing, using) ? this
                : new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera,
                        vehicle, noGravity, newUsing, border, rainLevel);
    }

    SelfState withBorder(ClientBorder newBorder) {
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera, vehicle,
                noGravity, using, newBorder, rainLevel);
    }

    /** {@code ClientLevel.setRainLevel}: the value clamped to 0..1 (NaN: not known). */
    SelfState withRainLevel(float value) {
        float level = Float.isNaN(value) ? value : Math.max(0.0F, Math.min(1.0F, value));
        return new SelfState(ticking, positionKnown, firstTick, food, health, gameMode, flying, mayFly, effects, speed, otherAttributes, camera, vehicle,
                noGravity, using, border, level);
    }

    /** {@code Level.isRaining}: the rain level above 0.2 (true), not (false), or null when not known. */
    Boolean raining() {
        return Float.isNaN(rainLevel) ? null : rainLevel > 0.2F;
    }
}
