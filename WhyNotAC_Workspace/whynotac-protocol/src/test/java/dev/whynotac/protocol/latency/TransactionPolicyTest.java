package dev.whynotac.protocol.latency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TransactionPolicyTest {

    private static final int SELF = 10;

    @Test
    void ownPhysicsChangesAreSandwiched() {
        assertTrue(TransactionPolicy.requiresSandwich(ClientboundPacket.SetEntityMotion.received(SELF, Vec3d.Y_AXIS), SELF));
        assertTrue(TransactionPolicy.requiresSandwich(new ClientboundPacket.UpdateAttributes(SELF, List.of()), SELF));
        assertTrue(TransactionPolicy.requiresSandwich(new ClientboundPacket.MobEffect(SELF, "minecraft:speed", 1, 100, 0), SELF));
        assertTrue(TransactionPolicy.requiresSandwich(new ClientboundPacket.Explosion(Vec3d.ZERO, 1.2F, new Vec3d(0.5, 0.6, 0)), SELF));
        assertTrue(TransactionPolicy.requiresSandwich(new ClientboundPacket.PlayerPosition(1, Vec3d.ZERO, Vec3d.ZERO, 0, 0, Set.of()), SELF));
        assertTrue(TransactionPolicy.requiresSandwich(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, 1), SELF));
    }

    @Test
    void otherEntitiesAndNeutralPacketsAreNot() {
        assertFalse(TransactionPolicy.requiresSandwich(ClientboundPacket.SetEntityMotion.received(SELF + 1, Vec3d.Y_AXIS), SELF));
        assertFalse(TransactionPolicy.requiresSandwich(new ClientboundPacket.Explosion(Vec3d.ZERO, 1.2F, null), SELF));
        assertFalse(TransactionPolicy.requiresSandwich(new ClientboundPacket.GameEvent(1, 0), SELF)); // rain
        assertFalse(TransactionPolicy.requiresSandwich(new ClientboundPacket.Ping(5), SELF));
        assertFalse(TransactionPolicy.requiresSandwich(new ClientboundPacket.Unmodeled("minecraft:level_chunk_with_light"), SELF));
    }

    @Test
    void unknownSelfIdIsConservative() {
        assertTrue(TransactionPolicy.requiresSandwich(ClientboundPacket.SetEntityMotion.received(12345, Vec3d.Y_AXIS), -1));
    }
}
