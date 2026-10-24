package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ClientboundPacket;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A team's collision rule and entries translate from the bytes the client receives, in the 774 layout, to the
 * same record as from the game's objects, for each of the packet's five methods.
 */
class TeamPacketViewsTest {

    private static final String ENTITY = "6f1d2c3b-4a59-4e68-9d7c-8b1a2f3e4d5c";

    private final PacketTranslator translator = new PacketTranslator();
    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private RegistryFriendlyByteBuf header(String team, int method) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        buf.writeUtf(team);
        buf.writeByte(method);
        return buf;
    }

    /** The parameters: display name, options, name tag visibility (id), collision rule (id), colour, prefix, suffix. */
    private static void parameters(RegistryFriendlyByteBuf buf, int collisionRule) {
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, Component.literal("Red"));
        buf.writeByte(3);
        buf.writeVarInt(0); // ALWAYS
        buf.writeVarInt(collisionRule);
        buf.writeVarInt(12); // ChatFormatting.RED
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, Component.empty());
        ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, Component.empty());
    }

    private ClientboundPacket edge(RegistryFriendlyByteBuf buf) {
        return translator.outbound(ClientboundSetPlayerTeamPacket.STREAM_CODEC.decode(buf), true);
    }

    @Test
    void everyMethodKeepsTheRuleAndTheEntries() {
        PlayerTeam team = new PlayerTeam(new Scoreboard(), "red");
        team.setCollisionRule(Team.CollisionRule.PUSH_OTHER_TEAMS);
        team.getPlayers().add(ENTITY);

        RegistryFriendlyByteBuf buf = header("red", 0);
        parameters(buf, 2);
        buf.writeCollection(List.of(ENTITY), FriendlyByteBuf::writeUtf);
        ClientboundPacket add = new ClientboundPacket.SetPlayerTeam("red", ClientboundPacket.SetPlayerTeam.ADD, "PUSH_OTHER_TEAMS", List.of(ENTITY));
        assertEquals(add, edge(buf));
        assertEquals(add, translator.outbound(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true), false));

        buf = header("red", 2);
        parameters(buf, 1);
        team.setCollisionRule(Team.CollisionRule.NEVER);
        ClientboundPacket change = new ClientboundPacket.SetPlayerTeam("red", ClientboundPacket.SetPlayerTeam.CHANGE, "NEVER", List.of());
        assertEquals(change, edge(buf));
        assertEquals(change, translator.outbound(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false), false));

        buf = header("red", 3);
        buf.writeCollection(List.of("Alex"), FriendlyByteBuf::writeUtf);
        ClientboundPacket join = new ClientboundPacket.SetPlayerTeam("red", ClientboundPacket.SetPlayerTeam.JOIN, null, List.of("Alex"));
        assertEquals(join, edge(buf));
        assertEquals(join, translator.outbound(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, "Alex", ClientboundSetPlayerTeamPacket.Action.ADD), false));

        buf = header("red", 4);
        buf.writeCollection(List.of(ENTITY), FriendlyByteBuf::writeUtf);
        ClientboundPacket leave = new ClientboundPacket.SetPlayerTeam("red", ClientboundPacket.SetPlayerTeam.LEAVE, null, List.of(ENTITY));
        assertEquals(leave, edge(buf));
        assertEquals(leave, translator.outbound(ClientboundSetPlayerTeamPacket.createPlayerPacket(team, ENTITY, ClientboundSetPlayerTeamPacket.Action.REMOVE), false));

        ClientboundPacket remove = new ClientboundPacket.SetPlayerTeam("red", ClientboundPacket.SetPlayerTeam.REMOVE, null, List.of());
        assertEquals(remove, edge(header("red", 1)));
        assertEquals(remove, translator.outbound(ClientboundSetPlayerTeamPacket.createRemovePacket(team), false));
    }
}
