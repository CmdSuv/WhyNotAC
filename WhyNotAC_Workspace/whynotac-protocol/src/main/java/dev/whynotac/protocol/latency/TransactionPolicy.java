package dev.whynotac.protocol.latency;

import dev.whynotac.protocol.packet.ClientboundPacket;

/**
 * Which outbound packets change the receiving player's own physics state and must therefore be
 * bracketed by transactions ("sandwiched"), so the physics layer learns the exact client tick window
 * in which the change took effect.
 */
public final class TransactionPolicy {

    private TransactionPolicy() {}

    /**
     * @param selfEntityId the receiving player's entity id, or {@code -1} if not known yet (then
     *                     entity-targeted packets are conservatively sandwiched)
     */
    public static boolean requiresSandwich(ClientboundPacket packet, int selfEntityId) {
        return switch (packet) {
            // Most frequent first: a pattern switch tests its labels in order.
            case ClientboundPacket.Unmodeled ignored -> false;
            case ClientboundPacket.Ping ignored -> false;
            // Other entities, as the client sees them: bounded by the per-tick transactions, like the world.
            case ClientboundPacket.MoveEntity ignored -> false;
            case ClientboundPacket.EntityPositionSync ignored -> false;
            case ClientboundPacket.SetEntityData ignored -> false;
            case ClientboundPacket.RotateHead ignored -> false;
            case ClientboundPacket.SetEntityMotion motion -> targetsSelf(motion.entityId(), selfEntityId);
            case ClientboundPacket.UpdateAttributes attributes -> targetsSelf(attributes.entityId(), selfEntityId);
            case ClientboundPacket.MobEffect effect -> targetsSelf(effect.entityId(), selfEntityId);
            case ClientboundPacket.RemoveMobEffect effect -> targetsSelf(effect.entityId(), selfEntityId);
            case ClientboundPacket.Explosion explosion -> explosion.playerKnockback() != null;
            case ClientboundPacket.GameEvent event -> event.type() == ClientboundPacket.GameEvent.CHANGE_GAME_MODE;
            case ClientboundPacket.PlayerPosition ignored -> true;
            case ClientboundPacket.PlayerRotation ignored -> true;
            // Moves the player only as its former vehicle; positions are not modelled yet (physics backlog).
            case ClientboundPacket.TeleportEntity ignored -> false;
            case ClientboundPacket.PlayerAbilities ignored -> true;
            case ClientboundPacket.Respawn ignored -> true;
            case ClientboundPacket.KeepAlive ignored -> false;
            case ClientboundPacket.Login ignored -> false;
            // The client's world: bounded by the per-tick transactions (one tick of uncertainty), not bracketed
            // one by one; a burst of chunks would otherwise double the packet rate. The physics layer
            // treats a block as uncertain until a later transaction is acknowledged.
            case ClientboundPacket.LevelChunk ignored -> false;
            case ClientboundPacket.BlockUpdate ignored -> false;
            case ClientboundPacket.SectionBlocksUpdate ignored -> false;
            case ClientboundPacket.ForgetLevelChunk ignored -> false;
            case ClientboundPacket.BlockChangedAck ignored -> false;
            case ClientboundPacket.ChunkCacheCenter ignored -> false;
            case ClientboundPacket.ChunkCacheRadius ignored -> false;
            // Entity ticking only (never faster than 20 Hz for the client, players never frozen): placed by the prefix bounds.
            case ClientboundPacket.TickingState ignored -> false;
            case ClientboundPacket.TickingStep ignored -> false;
            case ClientboundPacket.PlayerInfoUpdate ignored -> false; // other players; the player's own game mode comes with game_event
            case ClientboundPacket.PlayerInfoRemove ignored -> false;
            case ClientboundPacket.AddEntity ignored -> false;
            case ClientboundPacket.RemoveEntities ignored -> false;
            // The player's inventory and menus: placed by the flush brackets, like the entities.
            case ClientboundPacket.ContainerSetContent ignored -> false;
            case ClientboundPacket.ContainerSetSlot ignored -> false;
            case ClientboundPacket.SetCursorItem ignored -> false;
            case ClientboundPacket.SetPlayerInventory ignored -> false;
            case ClientboundPacket.SetHeldSlot ignored -> false;
            case ClientboundPacket.OpenScreen ignored -> false;
            case ClientboundPacket.MountScreenOpen ignored -> false;
            case ClientboundPacket.ContainerClose ignored -> false;
            // The player's own: finishing an item use stops using it (the slowdown ends).
            case ClientboundPacket.EntityEvent event -> targetsSelf(event.entityId(), selfEntityId);
            // Another entity's camera stops the player's own movement input (LocalPlayer.isControlledCamera).
            case ClientboundPacket.SetCamera ignored -> true;
            // Food decides whether the player can sprint: frequent in combat, placed by the flush brackets.
            case ClientboundPacket.SetHealth ignored -> false;
            // Teams decide whether entities push the player; rare.
            case ClientboundPacket.SetPlayerTeam ignored -> true;
            // The world border collides with the player; rare.
            case ClientboundPacket.InitializeBorder ignored -> true;
            case ClientboundPacket.SetBorderCenter ignored -> true;
            case ClientboundPacket.SetBorderLerpSize ignored -> true;
            case ClientboundPacket.SetBorderSize ignored -> true;
            // Mounting and dismounting change the player's own physics (which side is a passenger needs state: always).
            case ClientboundPacket.SetPassengers ignored -> true;
            case ClientboundPacket.MoveVehicle ignored -> true; // moves the vehicle the player controls
            // Tags decide what the player climbs, stands on and swims in, and how fast it mines. Mostly in the configuration
            // phase, where no transaction is sent; in play (a reload) the flush brackets place them.
            case ClientboundPacket.UpdateTags ignored -> false;
            case ClientboundPacket.Untranslatable ignored -> true; // unknown effect: bracket it like a physics change
        };
    }

    private static boolean targetsSelf(int entityId, int selfEntityId) {
        return selfEntityId < 0 || entityId == selfEntityId;
    }
}
