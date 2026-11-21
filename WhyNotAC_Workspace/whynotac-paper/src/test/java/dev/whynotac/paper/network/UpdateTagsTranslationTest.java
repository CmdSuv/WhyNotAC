package dev.whynotac.paper.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.analysis.movement.EngineTags;
import dev.whynotac.protocol.packet.ClientboundPacket;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The tags packet as the client decodes it, translated; and the registry ids the movement engine's tags name. */
class UpdateTagsTranslationTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theClientsTagsAreTranslatedRegistryByRegistryInNameOrder() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(2);
        buf.writeIdentifier(Identifier.parse("minecraft:fluid"));
        buf.writeVarInt(1);
        buf.writeIdentifier(Identifier.parse("minecraft:water"));
        buf.writeIntIdList(IntList.of(2, 1));
        buf.writeIdentifier(Identifier.parse("minecraft:block"));
        buf.writeVarInt(2);
        buf.writeIdentifier(Identifier.parse("minecraft:fences"));
        buf.writeIntIdList(IntList.of(9));
        buf.writeIdentifier(Identifier.parse("minecraft:climbable"));
        buf.writeIntIdList(IntList.of());
        ClientboundUpdateTagsPacket packet = ClientboundUpdateTagsPacket.STREAM_CODEC.decode(buf);
        ClientboundPacket translated = new PacketTranslator().outbound(packet);
        assertEquals(new ClientboundPacket.UpdateTags(List.of(
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:block", List.of(
                        new ClientboundPacket.UpdateTags.Tag("minecraft:climbable", new int[0]),
                        new ClientboundPacket.UpdateTags.Tag("minecraft:fences", new int[] {9}))),
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:fluid", List.of(
                        new ClientboundPacket.UpdateTags.Tag("minecraft:water", new int[] {2, 1}))))), translated);
    }

    @Test
    void theMovementEnginesRegistryIdsAreTheGames() {
        assertEquals(BuiltInRegistries.FLUID.getId(Fluids.FLOWING_WATER), EngineTags.FLOWING_WATER);
        assertEquals(BuiltInRegistries.FLUID.getId(Fluids.WATER), EngineTags.WATER);
        assertEquals(BuiltInRegistries.FLUID.getId(Fluids.FLOWING_LAVA), EngineTags.FLOWING_LAVA);
        assertEquals(BuiltInRegistries.FLUID.getId(Fluids.LAVA), EngineTags.LAVA);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.getId(EntityType.PLAYER), EngineTags.PLAYER);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.getId(EntityType.CAMEL), EngineTags.CAMEL);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.getId(EntityType.CAMEL_HUSK), EngineTags.CAMEL_HUSK);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.getId(EntityType.DONKEY), EngineTags.DONKEY);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.getId(EntityType.HORSE), EngineTags.HORSE);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.getId(EntityType.MULE), EngineTags.MULE);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.getId(EntityType.ZOMBIE_HORSE), EngineTags.ZOMBIE_HORSE);
    }
}
