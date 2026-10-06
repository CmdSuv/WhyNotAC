package dev.whynotac.protocol.packet;

import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.math.vector.Vec3d;
import java.util.List;
import java.util.Set;

/**
 * Server-to-client packets that change the client's physics state or serve latency measurement.
 * Everything else is reduced to {@link Unmodeled} and, outbound, not queued for analysis at all
 * (chunk and entity traffic would flood the timeline without adding evidence).
 */
public sealed interface ClientboundPacket {

    /** {@code ClientboundPingPacket}; WhyNotAC's transactions are pings in a reserved id range. */
    record Ping(int id) implements ClientboundPacket {}

    /** {@code ClientboundKeepAlivePacket}. */
    record KeepAlive(long id) implements ClientboundPacket {}

    /** {@code ClientboundLoginPacket}: tells the client its own entity id. */
    record Login(int entityId) implements ClientboundPacket {}

    /** {@code ClientboundRespawnPacket}: dimension change or respawn; the client resets movement state. */
    record Respawn() implements ClientboundPacket {}

    /**
     * {@code ClientboundSetEntityMotionPacket}. {@code motion} is the server-side value; the client
     * <em>replaces</em> its velocity with {@link #clientMotion()}, the lossy {@code LpVec3} decoding.
     */
    record SetEntityMotion(int entityId, Vec3d motion) implements ClientboundPacket {
        public Vec3d clientMotion() {
            return LpVec3.quantize(motion);
        }
    }

    /**
     * {@code ClientboundExplodePacket} (TNT, wind charges, mace wind burst...). The client
     * <em>adds</em> {@code playerKnockback} to its velocity at full double precision. Null when the
     * explosion does not push this player.
     */
    record Explosion(Vec3d center, float radius, Vec3d playerKnockback) implements ClientboundPacket {}

    /** {@code ClientboundPlayerPositionPacket}: a teleport or server-side correction. */
    record PlayerPosition(int teleportId, Vec3d position, Vec3d deltaMovement, float yaw, float pitch, Set<RelativeFlag> relatives)
            implements ClientboundPacket {}

    /** {@code ClientboundPlayerRotationPacket}. */
    record PlayerRotation(float yaw, boolean relativeYaw, float pitch, boolean relativePitch) implements ClientboundPacket {}

    /** {@code ClientboundUpdateAttributesPacket} (movement speed, gravity, step height, jump strength, scale, ...). */
    record UpdateAttributes(int entityId, List<Attribute> attributes) implements ClientboundPacket {
        public UpdateAttributes {
            attributes = List.copyOf(attributes);
        }

        public record Attribute(String key, double base, List<Modifier> modifiers) {
            public Attribute {
                modifiers = List.copyOf(modifiers);
            }
        }

        public record Modifier(String id, double amount, Operation operation) {}

        public enum Operation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL }
    }

    /** {@code ClientboundUpdateMobEffectPacket}. {@code amplifier} is zero-based. */
    record MobEffect(int entityId, String effectKey, int amplifier, int durationTicks, int flags) implements ClientboundPacket {}

    /** {@code ClientboundRemoveMobEffectPacket}. */
    record RemoveMobEffect(int entityId, String effectKey) implements ClientboundPacket {}

    /** {@code ClientboundPlayerAbilitiesPacket}. */
    record PlayerAbilities(boolean invulnerable, boolean flying, boolean mayFly, boolean instabuild, float flyingSpeed, float walkingSpeed)
            implements ClientboundPacket {}

    /** {@code ClientboundGameEventPacket} (game mode changes, among others); {@code type} is the protocol id. */
    record GameEvent(int type, float param) implements ClientboundPacket {
        public static final int CHANGE_GAME_MODE = 3;
    }

    /** Any other clientbound packet (counted, never queued). */
    record Unmodeled(String type) implements ClientboundPacket {}
}
