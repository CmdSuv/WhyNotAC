package dev.whynotac.protocol.packet;

import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.math.vector.Vec3d;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Server-to-client packets that change the client's physics state, its world, or serve latency
 * measurement. Everything else is reduced to {@link Unmodeled} and, outbound, not queued for analysis
 * at all.
 */
public sealed interface ClientboundPacket {

    /** {@code ClientboundPingPacket}; WhyNotAC's transactions are pings in a reserved id range. */
    record Ping(int id) implements ClientboundPacket {}

    /** {@code ClientboundKeepAlivePacket}. */
    record KeepAlive(long id) implements ClientboundPacket {}

    /**
     * The dimension a {@link Login} or {@link Respawn} puts the client in: its key and the vertical
     * extent of its chunks ({@code DimensionType.minY()} and {@code height()}, a multiple of 16).
     */
    record Dimension(String key, int minY, int height) {
        /** Chunk sections per chunk column. */
        public int sections() {
            return height >> 4;
        }
    }

    /**
     * {@code ClientboundLoginPacket}: the client's own entity id, the server's chunk radius (the client
     * sizes its chunk storage from it) and its dimension. A login always gives the client a new, empty
     * world. {@code chunkRadius} is -1 and {@code dimension} null in recordings older than these fields.
     */
    record Login(int entityId, int chunkRadius, Dimension dimension) implements ClientboundPacket {}

    /**
     * {@code ClientboundRespawnPacket}: dimension change or respawn; the client resets movement state.
     * It only replaces its world when the dimension key changes. {@code dimension} is null in older
     * recordings.
     */
    record Respawn(Dimension dimension) implements ClientboundPacket {}

    /**
     * {@code ClientboundLevelChunkWithLightPacket}: one chunk column. {@code body} is the packet exactly as
     * the client received it after the two coordinates (heightmaps, section data, block entities, light),
     * so nothing is lost and decoding can happen off the network thread. It is null in the game's view
     * when the network edge supplies the bytes (that view only reconciles).
     */
    record LevelChunk(int x, int z, byte[] body) implements ClientboundPacket {
        @Override
        public boolean equals(Object other) {
            return other instanceof LevelChunk c && c.x == x && c.z == z && Arrays.equals(c.body, body);
        }

        @Override
        public int hashCode() {
            return (31 * x + z) * 31 + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "LevelChunk[x=" + x + ", z=" + z + ", body=" + (body == null ? "null" : body.length + " bytes") + "]";
        }

        /** This packet without its bytes: what both views agree on cheaply. */
        public LevelChunk withoutBody() {
            return body == null ? this : new LevelChunk(x, z, null);
        }
    }

    /** {@code ClientboundForgetLevelChunkPacket}: the client drops this chunk column. */
    record ForgetLevelChunk(int x, int z) implements ClientboundPacket {}

    /** {@code ClientboundBlockUpdatePacket}: one block; {@code state} is the global block state id. */
    record BlockUpdate(BlockPosition position, int state) implements ClientboundPacket {}

    /**
     * {@code ClientboundSectionBlocksUpdatePacket}: several blocks of one 16x16x16 section. Each entry is
     * encoded as on the wire: {@code state << 12 | x << 8 | z << 4 | y} with section-local coordinates.
     */
    record SectionBlocksUpdate(int sectionX, int sectionY, int sectionZ, long[] entries) implements ClientboundPacket {
        public SectionBlocksUpdate {
            entries = entries.clone();
        }

        public long[] entries() {
            return entries.clone();
        }

        public int size() {
            return entries.length;
        }

        public long entry(int index) {
            return entries[index];
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SectionBlocksUpdate u && u.sectionX == sectionX && u.sectionY == sectionY && u.sectionZ == sectionZ
                    && Arrays.equals(u.entries, entries);
        }

        @Override
        public int hashCode() {
            return ((31 * sectionX + sectionY) * 31 + sectionZ) * 31 + Arrays.hashCode(entries);
        }

        @Override
        public String toString() {
            return "SectionBlocksUpdate[" + sectionX + ", " + sectionY + ", " + sectionZ + ", " + entries.length + " blocks]";
        }
    }

    /**
     * {@code ClientboundBlockChangedAckPacket}: the server has processed the client's block actions up to
     * {@code sequence}; the client now replaces its own predictions with what the server sent.
     */
    record BlockChangedAck(int sequence) implements ClientboundPacket {}

    /** {@code ClientboundSetChunkCacheCenterPacket}: the centre of the client's chunk storage. */
    record ChunkCacheCenter(int x, int z) implements ClientboundPacket {}

    /** {@code ClientboundSetChunkCacheRadiusPacket}: the server's view distance; the client resizes its chunk storage. */
    record ChunkCacheRadius(int radius) implements ClientboundPacket {}

    /**
     * {@code ClientboundSetEntityMotionPacket}. The client <em>replaces</em> its velocity with
     * {@code clientMotion}: exactly what it decodes from the lossy {@code LpVec3} encoding. Physics must
     * use this value and never quantize it again ({@code LpVec3} is not idempotent: re-quantizing
     * changes about 1 vector in 36 000).
     *
     * <p>{@code serverMotion} is the value the server encoded. Only the game's view knows it; it is
     * null when the packet was decoded at the network edge, where only the client's value exists.
     */
    record SetEntityMotion(int entityId, Vec3d clientMotion, Vec3d serverMotion) implements ClientboundPacket {
        /** As decoded at the network edge: what the client received. */
        public static SetEntityMotion received(int entityId, Vec3d clientMotion) {
            return new SetEntityMotion(entityId, clientMotion, null);
        }

        /** As the game sent it: the client will apply the single quantization of it. */
        public static SetEntityMotion sent(int entityId, Vec3d serverMotion) {
            return new SetEntityMotion(entityId, LpVec3.quantize(serverMotion), serverMotion);
        }

        /** This packet as the client sees it (without the server-side value), for comparing views. */
        public SetEntityMotion asReceived() {
            return serverMotion == null ? this : received(entityId, clientMotion);
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

    /**
     * {@code ClientboundTeleportEntityPacket}, entity id only (the position is not modelled yet). It
     * matters to the player itself in one case: a teleport of its former vehicle moves the player, and
     * the client answers it with a movement packet outside its tick.
     */
    record TeleportEntity(int entityId) implements ClientboundPacket {}

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

        /** {@code UNKNOWN}: a constant this WhyNotAC build does not know. Always last. */
        public enum Operation { ADD_VALUE, ADD_MULTIPLIED_BASE, ADD_MULTIPLIED_TOTAL, UNKNOWN }
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

    /**
     * A packet WhyNotAC failed to translate. Unlike {@link Unmodeled} it is always queued and always
     * sandwiched: it may have changed the client's state in a way WhyNotAC cannot see, so everything
     * built on client state must treat it as an unknown change.
     */
    record Untranslatable(String type) implements ClientboundPacket {}
}
