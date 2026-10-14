package dev.whynotac.protocol.packet;

import dev.whynotac.math.vector.Vec3d;
import java.util.List;

/**
 * Client-to-server packets (1.21.11 play state). Names follow Mojang's mappings without the
 * {@code Serverbound...Packet} affixes.
 */
public sealed interface ServerboundPacket {

    /**
     * {@code ServerboundMovePlayerPacket} in all four shapes. Components the client did not send are
     * {@code NaN}, so "absent" can never be confused with "zero".
     */
    record MovePlayer(
            double x, double y, double z,
            float yaw, float pitch,
            boolean hasPosition, boolean hasRotation,
            boolean onGround, boolean horizontalCollision) implements ServerboundPacket {

        public enum Shape { POS_ROT, POS, ROT, STATUS_ONLY }

        public Shape shape() {
            if (hasPosition) {
                return hasRotation ? Shape.POS_ROT : Shape.POS;
            }
            return hasRotation ? Shape.ROT : Shape.STATUS_ONLY;
        }

        public Vec3d position() {
            return new Vec3d(x, y, z);
        }

        /** True when a sent component is NaN or infinite (the vanilla server disconnects for this). */
        public boolean hasNonFiniteValue() {
            return hasPosition && !(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))
                    || hasRotation && !(Float.isFinite(yaw) && Float.isFinite(pitch));
        }

        // The record's own equals and hashCode, written out: the generated ones go through method handles,
        // which cost several times more on the network thread (every move is reconciled by value).
        @Override
        public boolean equals(Object other) {
            return other instanceof MovePlayer m
                    && Double.doubleToLongBits(x) == Double.doubleToLongBits(m.x)
                    && Double.doubleToLongBits(y) == Double.doubleToLongBits(m.y)
                    && Double.doubleToLongBits(z) == Double.doubleToLongBits(m.z)
                    && Float.floatToIntBits(yaw) == Float.floatToIntBits(m.yaw)
                    && Float.floatToIntBits(pitch) == Float.floatToIntBits(m.pitch)
                    && hasPosition == m.hasPosition && hasRotation == m.hasRotation
                    && onGround == m.onGround && horizontalCollision == m.horizontalCollision;
        }

        @Override
        public int hashCode() {
            int h = Double.hashCode(x);
            h = 31 * h + Double.hashCode(y);
            h = 31 * h + Double.hashCode(z);
            h = 31 * h + Float.hashCode(yaw);
            h = 31 * h + Float.hashCode(pitch);
            return 31 * h + (hasPosition ? 1 : 0) + (hasRotation ? 2 : 0) + (onGround ? 4 : 0) + (horizontalCollision ? 8 : 0);
        }
    }

    /**
     * {@code ServerboundPlayerInputPacket}: the client's movement key state, sent whenever it changes
     * (since 1.21.2). This is what the player is <em>pressing</em>, independent of where they end up.
     */
    record PlayerInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint)
            implements ServerboundPacket {

        public static final PlayerInput NONE = new PlayerInput(false, false, false, false, false, false, false);
    }

    /** {@code ServerboundPlayerCommandPacket}. */
    record PlayerCommand(int entityId, Action action, int data) implements ServerboundPacket {
        /** {@code UNKNOWN}: a constant this WhyNotAC build does not know (newer game version). Always last. */
        public enum Action { STOP_SLEEPING, START_SPRINTING, STOP_SPRINTING, START_RIDING_JUMP, STOP_RIDING_JUMP, OPEN_INVENTORY, START_FALL_FLYING, UNKNOWN }
    }

    /** {@code ServerboundPlayerActionPacket}; {@code STAB} is the 1.21.11 spear jab. */
    record PlayerAction(Action action, BlockPosition position, BlockFace face, int sequence) implements ServerboundPacket {
        /** {@code UNKNOWN}: a constant this WhyNotAC build does not know (newer game version). Always last. */
        public enum Action {
            START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK, DROP_ALL_ITEMS, DROP_ITEM,
            RELEASE_USE_ITEM, SWAP_ITEM_WITH_OFFHAND, STAB, UNKNOWN
        }
    }

    /**
     * {@code ServerboundInteractPacket}. {@code hand} is null for attacks; {@code location}
     * (relative to the target's position, as sent) is only present for {@code INTERACT_AT}.
     */
    record InteractEntity(int entityId, Kind kind, Hand hand, Vec3d location, boolean secondaryAction) implements ServerboundPacket {
        public enum Kind { INTERACT, ATTACK, INTERACT_AT }
    }

    /** {@code ServerboundSwingPacket}. */
    record Swing(Hand hand) implements ServerboundPacket {}

    /** {@code ServerboundUseItemPacket}: carries the rotation the client used for the action. */
    record UseItem(Hand hand, int sequence, float yaw, float pitch) implements ServerboundPacket {}

    /**
     * {@code ServerboundUseItemOnPacket}. {@code location} is absolute: block coordinate plus the
     * float cursor offset the client sent, widened exactly as the game does.
     */
    record UseItemOn(Hand hand, BlockPosition position, BlockFace face, Vec3d location, boolean inside, boolean worldBorderHit, int sequence)
            implements ServerboundPacket {}

    /** {@code ServerboundSetCarriedItemPacket}. */
    record SetCarriedItem(int slot) implements ServerboundPacket {}

    /** {@code ServerboundClientTickEndPacket}: the client finished one tick (since 1.21.2). */
    record ClientTickEnd() implements ServerboundPacket {
        @Override
        public boolean equals(Object other) {
            return other instanceof ClientTickEnd; // written out, as in MovePlayer
        }

        @Override
        public int hashCode() {
            return 0x7e1c;
        }
    }

    /** {@code ServerboundPongPacket}: answer to a {@code ClientboundPingPacket}, processed in order. */
    record Pong(int id) implements ServerboundPacket {
        @Override
        public boolean equals(Object other) {
            return other instanceof Pong pong && pong.id == id; // written out, as in MovePlayer
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    /** {@code ServerboundKeepAlivePacket}. */
    record KeepAlive(long id) implements ServerboundPacket {}

    /** {@code ServerboundAcceptTeleportationPacket}. */
    record AcceptTeleportation(int teleportId) implements ServerboundPacket {}

    /** {@code ServerboundPlayerAbilitiesPacket}. */
    record PlayerAbilities(boolean flying) implements ServerboundPacket {}

    /** {@code ServerboundMoveVehiclePacket}. */
    record MoveVehicle(Vec3d position, float yaw, float pitch, boolean onGround) implements ServerboundPacket {}

    /** {@code ServerboundPaddleBoatPacket}. */
    record PaddleBoat(boolean left, boolean right) implements ServerboundPacket {}

    /**
     * {@code ServerboundContainerClickPacket}. The client applies the click itself and sends what it
     * predicts: every slot it changed ({@code changed}, by menu index) and the stack the cursor carries
     * then ({@code carried}), as hashed stacks. Recordings older than these fields hold none and a null
     * {@code carried}.
     */
    record ContainerClick(int containerId, int stateId, int slot, int button, String clickType, List<ChangedSlot> changed, Stack carried)
            implements ServerboundPacket {
        public ContainerClick {
            changed = List.copyOf(changed);
        }

        /** Timing only: no predicted contents. */
        public ContainerClick(int containerId, int stateId, int slot, int button, String clickType) {
            this(containerId, stateId, slot, button, clickType, List.of(), null);
        }

        public record ChangedSlot(int slot, Stack item) {}
    }

    /** {@code ServerboundSetCreativeModeSlotPacket}: the creative client sets an inventory menu slot itself (-1: drops the stack). */
    record SetCreativeModeSlot(int slot, Stack item) implements ServerboundPacket {}

    /** {@code ServerboundContainerClosePacket}. */
    record ContainerClose(int containerId) implements ServerboundPacket {}

    /** {@code ServerboundPlayerLoadedPacket}: the client finished loading the world after join/respawn. */
    record PlayerLoaded() implements ServerboundPacket {}

    /** {@code ServerboundChunkBatchReceivedPacket}. */
    record ChunkBatchReceived(float desiredChunksPerTick) implements ServerboundPacket {}

    /**
     * {@code ClientIntentionPacket}, the first packet of every connection. The host name the client
     * typed is deliberately not kept. {@code protocolVersion} is what reached the server: a protocol
     * translator such as ViaVersion may already have rewritten it.
     */
    record Handshake(int protocolVersion, Intent intent) implements ServerboundPacket {
        /** {@code UNKNOWN}: a constant this WhyNotAC build does not know. Always last. */
        public enum Intent { STATUS, LOGIN, TRANSFER, UNKNOWN }
    }

    /** Any other serverbound packet: kept for timing and ordering analysis. */
    record Unmodeled(String type) implements ServerboundPacket {}

    /**
     * {@code ServerboundClientInformationPacket}, reduced to what analysis needs (data minimisation:
     * language, chat and skin settings are not kept). {@code viewDistance} bounds which entities the
     * client tracks; {@code mainHand} is "LEFT" or "RIGHT". Self-reported (claimed).
     */
    record ClientInformation(int viewDistance, String mainHand) implements ServerboundPacket {}

    /** The client's self-reported brand ({@code minecraft:brand}): claimed, any string is possible. */
    record ClientBrand(String brand) implements ServerboundPacket {}

    /** {@code minecraft:register}/{@code unregister}: plugin-message channels the client announces (claimed). */
    record ChannelRegistration(boolean register, List<String> channels) implements ServerboundPacket {
        public ChannelRegistration {
            channels = List.copyOf(channels);
        }
    }

    /** Any other plugin message: its channel and size only, never its content. */
    record CustomPayload(String channel, int size) implements ServerboundPacket {}

    /**
     * A packet WhyNotAC failed to translate (a bug, or a game update it does not know yet). It still
     * occupies its place in the timeline, so analysis knows something unknown happened here instead
     * of seeing a silent hole.
     */
    record Untranslatable(String type) implements ServerboundPacket {}
}
