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
     * {@code ClientboundTickingStatePacket}: the server's tick rate and whether it is frozen. A frozen
     * client stops ticking every entity but players and their vehicles; a rate below 20 also slows the
     * client's own ticks (never above 20: the client waits {@code max(50 ms, 1000 / tickRate)}).
     */
    record TickingState(float tickRate, boolean frozen) implements ClientboundPacket {}

    /** {@code ClientboundTickingStepPacket}: a frozen game runs this many more ticks. */
    record TickingStep(int steps) implements ClientboundPacket {}

    /**
     * {@code ClientboundPlayerInfoUpdatePacket}, the parts that decide which players the client has and
     * how it treats them: the client only creates a player entity whose profile it was given
     * ({@code addPlayers}), and a player in spectator mode is neither pickable nor visible. Entries are
     * kept only when one of the two actions is present; {@code gameMode} is the {@code GameType} id, or
     * -1 without {@code updateGameMode}.
     */
    record PlayerInfoUpdate(boolean addPlayers, boolean updateGameMode, List<Entry> entries) implements ClientboundPacket {
        public PlayerInfoUpdate {
            entries = List.copyOf(entries);
        }

        public record Entry(java.util.UUID profileId, int gameMode) {}

        public static final int SPECTATOR = 3;
    }

    /** {@code ClientboundPlayerInfoRemovePacket}: the client forgets these profiles (entities already created stay). */
    record PlayerInfoRemove(List<java.util.UUID> profileIds) implements ClientboundPacket {
        public PlayerInfoRemove {
            profileIds = List.copyOf(profileIds);
        }
    }

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
     * {@code ClientboundTeleportEntityPacket}: an entity's position, velocity and rotation, each absolute
     * or relative ({@code relatives}). It also matters to the player itself: a teleport of its former
     * vehicle moves the player, and the client answers it with a movement packet outside its tick.
     * {@code position} is null in recordings older than these fields (entity id only).
     */
    record TeleportEntity(int entityId, Vec3d position, Vec3d deltaMovement, float yRot, float xRot, Set<RelativeFlag> relatives,
                          boolean onGround) implements ClientboundPacket {
        public TeleportEntity {
            relatives = relatives == null ? null : Set.copyOf(relatives);
        }

        /** As read from a recording that kept the entity id only. */
        public static TeleportEntity idOnly(int entityId) {
            return new TeleportEntity(entityId, null, null, 0F, 0F, null, false);
        }
    }

    /**
     * {@code ClientboundAddEntityPacket}: a new entity in the client's world. Angles are the wire bytes
     * (the client unpacks them with {@code Mth.unpackDegrees}); {@code type} is the entity type key;
     * {@code data} is the type-specific int (a falling block's state, a projectile's owner, a hanging
     * entity's direction). Velocity as for {@link SetEntityMotion}: the client's value from the lossy
     * {@code LpVec3}, and the server's own value in the game's view only.
     */
    record AddEntity(int entityId, java.util.UUID uuid, String type, double x, double y, double z, Vec3d clientMotion, Vec3d serverMotion,
                     byte xRot, byte yRot, byte yHeadRot, int data) implements ClientboundPacket {
        /** As decoded at the network edge: what the client received. */
        public static AddEntity received(int entityId, java.util.UUID uuid, String type, double x, double y, double z, Vec3d clientMotion,
                byte xRot, byte yRot, byte yHeadRot, int data) {
            return new AddEntity(entityId, uuid, type, x, y, z, clientMotion, null, xRot, yRot, yHeadRot, data);
        }

        /** As the game sent it: the client will apply the single quantization of the velocity. */
        public static AddEntity sent(int entityId, java.util.UUID uuid, String type, double x, double y, double z, Vec3d serverMotion,
                byte xRot, byte yRot, byte yHeadRot, int data) {
            return new AddEntity(entityId, uuid, type, x, y, z, LpVec3.quantize(serverMotion), serverMotion, xRot, yRot, yHeadRot, data);
        }

        /** This packet as the client sees it (without the server-side value), for comparing views. */
        public AddEntity asReceived() {
            return serverMotion == null ? this : received(entityId, uuid, type, x, y, z, clientMotion, xRot, yRot, yHeadRot, data);
        }
    }

    /** {@code ClientboundRemoveEntitiesPacket}. */
    record RemoveEntities(int[] entityIds) implements ClientboundPacket {
        @Override
        public boolean equals(Object other) {
            return other instanceof RemoveEntities r && Arrays.equals(r.entityIds, entityIds);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(entityIds);
        }

        @Override
        public String toString() {
            return "RemoveEntities" + Arrays.toString(entityIds);
        }
    }

    /**
     * {@code ClientboundMoveEntityPacket} ({@code Pos}, {@code PosRot}, {@code Rot}): position deltas in
     * 1/4096 block relative to the client's codec base for the entity ({@code VecDeltaCodec}), angles as
     * wire bytes. Fields a shape does not carry are zero.
     */
    record MoveEntity(int entityId, short xa, short ya, short za, byte yRot, byte xRot, boolean onGround, boolean hasPosition,
                      boolean hasRotation) implements ClientboundPacket {}

    /** {@code ClientboundEntityPositionSyncPacket}: an entity's absolute position, velocity and rotation. */
    record EntityPositionSync(int entityId, Vec3d position, Vec3d deltaMovement, float yRot, float xRot, boolean onGround)
            implements ClientboundPacket {}

    /** {@code ClientboundRotateHeadPacket}; the head yaw as the wire byte. */
    record RotateHead(int entityId, byte yHeadRot) implements ClientboundPacket {}

    /** {@code ClientboundSetPassengersPacket}: the complete passenger list of a vehicle. */
    record SetPassengers(int vehicleId, int[] passengerIds) implements ClientboundPacket {
        @Override
        public boolean equals(Object other) {
            return other instanceof SetPassengers p && p.vehicleId == vehicleId && Arrays.equals(p.passengerIds, passengerIds);
        }

        @Override
        public int hashCode() {
            return 31 * vehicleId + Arrays.hashCode(passengerIds);
        }

        @Override
        public String toString() {
            return "SetPassengers[vehicleId=" + vehicleId + ", passengerIds=" + Arrays.toString(passengerIds) + "]";
        }
    }

    /** {@code ClientboundMoveVehiclePacket}: the server moves the vehicle the player controls. */
    record MoveVehicle(Vec3d position, float yRot, float xRot) implements ClientboundPacket {}

    /**
     * {@code ClientboundSetEntityDataPacket}: synched data values of an entity. Scalar values are kept
     * exactly ({@link DataEntry}): flags, pose, sizes, ages, booleans. Values of other serializers (items,
     * text, NBT, particles, vectors, ...) are only counted in {@code dropped}: never misread, and none of
     * them changes where an entity is or how big it is.
     */
    record SetEntityData(int entityId, List<DataEntry> entries, int dropped) implements ClientboundPacket {
        public SetEntityData {
            entries = List.copyOf(entries);
        }

        /**
         * One value: the accessor's index (its meaning depends on the entity type) and the value's bits
         * by {@code kind}: a float as {@code Float.floatToRawIntBits}, a boolean as 0/1, a pose or direction
         * as the ordinal of {@link EntityPose} or {@link BlockFace}, an optional int or block state as
         * -1 when empty, a sniffer state by its id ({@code Sniffer.State.id()}).
         */
        public record DataEntry(int index, Kind kind, long value) {}

        /** The scalar serializers kept. Recorded by ordinal: new constants go last. */
        public enum Kind { BYTE, INT, LONG, FLOAT, BOOLEAN, POSE, DIRECTION, OPTIONAL_UNSIGNED_INT, BLOCK_STATE, OPTIONAL_BLOCK_STATE, SNIFFER_STATE }
    }

    /** {@code net.minecraft.world.entity.Pose}, by name. {@code UNKNOWN}: a constant this build does not know. Always last. */
    enum EntityPose {
        STANDING, FALL_FLYING, SLEEPING, SWIMMING, SPIN_ATTACK, CROUCHING, LONG_JUMPING, DYING, CROAKING, USING_TONGUE, SITTING, ROARING,
        SNIFFING, EMERGING, DIGGING, SLIDING, SHOOTING, INHALING, UNKNOWN
    }

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
    /**
     * {@code ClientboundContainerSetContentPacket}: every slot of a menu, in menu order, and the stack the
     * cursor carries. Container 0 is the player's own inventory menu.
     */
    record ContainerSetContent(int containerId, int stateId, List<Stack> items, Stack carried) implements ClientboundPacket {
        public ContainerSetContent {
            items = List.copyOf(items);
        }
    }

    /** {@code ClientboundContainerSetSlotPacket}: one slot of a menu, by menu index. */
    record ContainerSetSlot(int containerId, int stateId, int slot, Stack item) implements ClientboundPacket {}

    /** {@code ClientboundSetCursorItemPacket}: the stack the cursor carries. */
    record SetCursorItem(Stack item) implements ClientboundPacket {}

    /** {@code ClientboundSetPlayerInventoryPacket}: one slot of the player's inventory, by inventory index (0-8 hotbar, 40 off hand). */
    record SetPlayerInventory(int slot, Stack item) implements ClientboundPacket {}

    /** {@code ClientboundSetHeldSlotPacket}: the selected hotbar slot. */
    record SetHeldSlot(int slot) implements ClientboundPacket {}

    /** {@code ClientboundOpenScreenPacket}: a menu opens with this container id; {@code menuType} by key. */
    record OpenScreen(int containerId, String menuType) implements ClientboundPacket {}

    /** {@code ClientboundMountScreenOpenPacket}: a mount's inventory menu opens. */
    record MountScreenOpen(int containerId, int inventoryColumns, int entityId) implements ClientboundPacket {}

    /** {@code ClientboundContainerClosePacket}: the client goes back to its inventory menu. */
    record ContainerClose(int containerId) implements ClientboundPacket {}

    record Unmodeled(String type) implements ClientboundPacket {}

    /**
     * A packet WhyNotAC failed to translate. Unlike {@link Unmodeled} it is always queued and always
     * sandwiched: it may have changed the client's state in a way WhyNotAC cannot see, so everything
     * built on client state must treat it as an unknown change.
     */
    record Untranslatable(String type) implements ClientboundPacket {}
}
