package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.whynotac.world.ClientEntities;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The firework rocket's data whynotac-world reads ({@code ClientEntities.rocketsBoosting}) is the game's own: the type's
 * key, and {@code DATA_ATTACHED_TO_TARGET}'s index and serializer (an {@code OptionalInt}, which the translator writes
 * as {@code -1} when empty).
 */
class RocketDataTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theRocketsTargetIsTheGamesOwnIndex() throws ReflectiveOperationException {
        assertEquals(ClientEntities.FIREWORK_ROCKET, BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.FIREWORK_ROCKET).toString());
        EntityDataAccessor<?> attached = GameEntities.accessor(FireworkRocketEntity.class, "DATA_ATTACHED_TO_TARGET");
        assertEquals(ClientEntities.DATA_ROCKET_ATTACHED, attached.id());
        assertSame(EntityDataSerializers.OPTIONAL_UNSIGNED_INT, attached.serializer());
    }
}
