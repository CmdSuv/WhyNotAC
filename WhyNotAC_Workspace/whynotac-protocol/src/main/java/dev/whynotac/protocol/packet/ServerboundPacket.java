package dev.whynotac.protocol.packet;

import dev.whynotac.math.vector.Vec3d;

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
        public enum Action { STOP_SLEEPING, START_SPRINTING, STOP_SPRINTING, START_RIDING_JUMP, STOP_RIDING_JUMP, OPEN_INVENTORY, START_FALL_FLYING }
    }

    /** {@code ServerboundPlayerActionPacket}; {@code STAB} is the 1.21.11 spear jab. */
    record PlayerAction(Action action, BlockPosition position, BlockFace face, int sequence) implements ServerboundPacket {
        public enum Action {
            START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK, DROP_ALL_ITEMS, DROP_ITEM,
            RELEASE_USE_ITEM, SWAP_ITEM_WITH_OFFHAND, STAB
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
    record ClientTickEnd() implements ServerboundPacket {}

    /** {@code ServerboundPongPacket}: answer to a {@code ClientboundPingPacket}, processed in order. */
    record Pong(int id) implements ServerboundPacket {}

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

    /** {@code ServerboundContainerClickPacket} (inventory timing; item payload not modelled). */
    record ContainerClick(int containerId, int stateId, int slot, int button, String clickType) implements ServerboundPacket {}

    /** {@code ServerboundContainerClosePacket}. */
    record ContainerClose(int containerId) implements ServerboundPacket {}

    /** {@code ServerboundPlayerLoadedPacket}: the client finished loading the world after join/respawn. */
    record PlayerLoaded() implements ServerboundPacket {}

    /** {@code ServerboundChunkBatchReceivedPacket}. */
    record ChunkBatchReceived(float desiredChunksPerTick) implements ServerboundPacket {}

    /** Any other serverbound packet: kept for timing and ordering analysis. */
    record Unmodeled(String type) implements ServerboundPacket {}
}
