package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ClientboundPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Health, food, the level's time and the world border translate from the bytes the client receives, in the 774 layout, to
 * the same records as from the game's objects.
 */
class PlayerStatePacketViewsTest {

    private final PacketTranslator translator = new PacketTranslator();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static FriendlyByteBuf bytes() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void healthKeepsFoodAndSaturation() {
        FriendlyByteBuf buf = bytes();
        buf.writeFloat(13.5F);
        buf.writeVarInt(6);
        buf.writeFloat(0.25F);
        ClientboundSetHealthPacket packet = ClientboundSetHealthPacket.STREAM_CODEC.decode(buf);
        assertEquals(new ClientboundPacket.SetHealth(13.5F, 6, 0.25F), translator.outbound(packet, true));
        assertEquals(translator.outbound(new ClientboundSetHealthPacket(13.5F, 6, 0.25F), true), translator.outbound(packet, true));
    }

    @Test
    void timeKeepsTheGameTimeTheDayTimeAndWhetherTheDayRuns() {
        FriendlyByteBuf buf = bytes();
        buf.writeLong(123_456_789_012L);
        buf.writeLong(-6000L);
        buf.writeBoolean(false);
        ClientboundSetTimePacket packet = ClientboundSetTimePacket.STREAM_CODEC.decode(buf);
        assertEquals(new ClientboundPacket.SetTime(123_456_789_012L, -6000L, false), translator.outbound(packet, true));
        assertEquals(translator.outbound(new ClientboundSetTimePacket(123_456_789_012L, -6000L, false), true), translator.outbound(packet, true));
    }

    @Test
    void theBorderKeepsItsCentreSizeAndLerp() {
        FriendlyByteBuf buf = bytes();
        buf.writeDouble(-12.5);
        buf.writeDouble(300.25);
        buf.writeDouble(64.0);
        buf.writeDouble(16.0);
        buf.writeVarLong(1200L);
        buf.writeVarInt(29_999_984);
        buf.writeVarInt(5);  // warning blocks: drawn only
        buf.writeVarInt(15); // warning time: drawn only
        assertEquals(new ClientboundPacket.InitializeBorder(-12.5, 300.25, 64.0, 16.0, 1200L, 29_999_984),
                translator.outbound(ClientboundInitializeBorderPacket.STREAM_CODEC.decode(buf), true));

        buf = bytes();
        buf.writeDouble(7.0);
        buf.writeDouble(-8.0);
        assertEquals(new ClientboundPacket.SetBorderCenter(7.0, -8.0), translator.outbound(ClientboundSetBorderCenterPacket.STREAM_CODEC.decode(buf), true));

        buf = bytes();
        buf.writeDouble(100.0);
        buf.writeDouble(50.0);
        buf.writeVarLong(400L);
        assertEquals(new ClientboundPacket.SetBorderLerpSize(100.0, 50.0, 400L),
                translator.outbound(ClientboundSetBorderLerpSizePacket.STREAM_CODEC.decode(buf), true));

        buf = bytes();
        buf.writeDouble(32.0);
        assertEquals(new ClientboundPacket.SetBorderSize(32.0), translator.outbound(ClientboundSetBorderSizePacket.STREAM_CODEC.decode(buf), true));
    }
}
