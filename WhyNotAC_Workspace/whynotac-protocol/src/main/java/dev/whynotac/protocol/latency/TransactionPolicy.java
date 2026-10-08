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
            case ClientboundPacket.Untranslatable ignored -> true; // unknown effect: bracket it like a physics change
        };
    }

    private static boolean targetsSelf(int entityId, int selfEntityId) {
        return selfEntityId < 0 || entityId == selfEntityId;
    }
}
