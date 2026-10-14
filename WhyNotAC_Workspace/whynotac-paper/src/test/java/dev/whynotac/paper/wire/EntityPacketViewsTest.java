package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ClientboundPacket.SetEntityData.DataEntry;
import dev.whynotac.protocol.packet.ClientboundPacket.SetEntityData.Kind;
import dev.whynotac.protocol.packet.RelativeFlag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.Rotations;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every entity packet WhyNotAC models translates to the same record from the game's object and from the
 * bytes the client receives (the network edge decodes them with the game's own codec), apart from the
 * server-only values the model marks as such. And the record holds what the client derives: angles as
 * the wire bytes, velocity as the client decodes the lossy {@code LpVec3}, synched data as the client reads it.
 */
class EntityPacketViewsTest {

    private static ProtocolInfo<ClientGamePacketListener> clientbound;
    private static RegistryAccess registries;
    private final PacketTranslator translator = new PacketTranslator();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
    }

    /** The packet as the client decodes it from its frame. */
    private static Packet<?> overTheWire(Packet<? super ClientGamePacketListener> packet) {
        ByteBuf frame = Unpooled.buffer();
        try {
            clientbound.codec().encode(frame, packet);
            Packet<?> decoded = clientbound.codec().decode(frame);
            assertEquals(0, frame.readableBytes(), "the codec read the whole frame");
            return decoded;
        } finally {
            frame.release();
        }
    }

    /** Game view and edge view of one packet; returns the edge's (the client's) record. */
    private ClientboundPacket bothViews(Packet<? super ClientGamePacketListener> packet) {
        return bothViews(packet, overTheWire(packet));
    }

    private ClientboundPacket bothViews(Packet<? super ClientGamePacketListener> packet, Packet<?> received) {
        ClientboundPacket game = translator.outbound(packet, false);
        ClientboundPacket wire = translator.outbound(received, true);
        assertEquals(WireObserver.canonical(game), WireObserver.canonical(wire), "views agree on " + packet);
        return wire;
    }

    @Test
    void addEntityKeepsTheWireAnglesAndTheClientsVelocity() {
        Vec3 motion = new Vec3(0.123456789, -0.5, 1.75);
        UUID uuid = UUID.fromString("0f3b6a2e-1111-4c4c-9e9e-123456789abc");
        ClientboundAddEntityPacket packet = new ClientboundAddEntityPacket(42, uuid, 10.5, 64.0, -3.25, 33.3F, -170.0F, EntityType.ZOMBIE, 7,
                motion, 91.0);
        ClientboundPacket.AddEntity game = (ClientboundPacket.AddEntity) translator.outbound(packet, false);
        ClientboundPacket.AddEntity wire = (ClientboundPacket.AddEntity) bothViews(packet);
        assertEquals("minecraft:zombie", wire.type());
        assertEquals(uuid, wire.uuid());
        assertEquals(net.minecraft.util.Mth.packDegrees(33.3F), wire.xRot());
        assertEquals(net.minecraft.util.Mth.packDegrees(-170.0F), wire.yRot());
        assertEquals(net.minecraft.util.Mth.packDegrees(91.0F), wire.yHeadRot());
        assertEquals(7, wire.data());
        assertNull(wire.serverMotion());
        assertEquals(new Vec3d(motion.x, motion.y, motion.z), game.serverMotion());
        // The client's velocity is the one quantization of the server's.
        assertEquals(LpVec3.quantize(new Vec3d(motion.x, motion.y, motion.z)), wire.clientMotion());
        Vec3 decoded = ((ClientboundAddEntityPacket) overTheWire(packet)).getMovement();
        assertEquals(new Vec3d(decoded.x, decoded.y, decoded.z), wire.clientMotion());
    }

    @Test
    void movementShapesKeepTheirRawFields() {
        ClientboundPacket.MoveEntity pos = (ClientboundPacket.MoveEntity) bothViews(new ClientboundMoveEntityPacket.Pos(5, (short) -4096, (short) 1, (short) 32767, true));
        assertEquals(new ClientboundPacket.MoveEntity(5, (short) -4096, (short) 1, (short) 32767, (byte) 0, (byte) 0, true, true, false), pos);
        ClientboundPacket.MoveEntity posRot = (ClientboundPacket.MoveEntity) bothViews(
                new ClientboundMoveEntityPacket.PosRot(6, (short) 12, (short) -13, (short) 14, (byte) -128, (byte) 64, false));
        assertEquals(new ClientboundPacket.MoveEntity(6, (short) 12, (short) -13, (short) 14, (byte) -128, (byte) 64, false, true, true), posRot);
        ClientboundPacket.MoveEntity rot = (ClientboundPacket.MoveEntity) bothViews(new ClientboundMoveEntityPacket.Rot(7, (byte) 1, (byte) -1, true));
        assertEquals(new ClientboundPacket.MoveEntity(7, (short) 0, (short) 0, (short) 0, (byte) 1, (byte) -1, true, false, true), rot);
    }

    @Test
    void absolutePositionsAreExact() {
        PositionMoveRotation values = new PositionMoveRotation(new Vec3(1.0 / 3, 70.000001, -9e6), new Vec3(0.01, -0.0784, 0), 359.9F, -90F);
        ClientboundPacket.EntityPositionSync sync = (ClientboundPacket.EntityPositionSync) bothViews(new ClientboundEntityPositionSyncPacket(8, values, true));
        assertEquals(new ClientboundPacket.EntityPositionSync(8, new Vec3d(1.0 / 3, 70.000001, -9e6), new Vec3d(0.01, -0.0784, 0), 359.9F, -90F, true),
                sync);
        ClientboundPacket.TeleportEntity teleport = (ClientboundPacket.TeleportEntity) bothViews(
                new ClientboundTeleportEntityPacket(9, values, Set.of(Relative.X, Relative.Y_ROT), false));
        assertEquals(new ClientboundPacket.TeleportEntity(9, new Vec3d(1.0 / 3, 70.000001, -9e6), new Vec3d(0.01, -0.0784, 0), 359.9F, -90F,
                Set.of(RelativeFlag.X, RelativeFlag.Y_ROT), false), teleport);
        ClientboundPacket.MoveVehicle vehicle = (ClientboundPacket.MoveVehicle) bothViews(new ClientboundMoveVehiclePacket(new Vec3(0.5, 63.4375, 0.5), 12.5F, 0F));
        assertEquals(new ClientboundPacket.MoveVehicle(new Vec3d(0.5, 63.4375, 0.5), 12.5F, 0F), vehicle);
    }

    @Test
    void listsAndHeadRotation() {
        assertArrayEquals(new int[] {3, 1, 2}, ((ClientboundPacket.RemoveEntities) bothViews(new ClientboundRemoveEntitiesPacket(3, 1, 2))).entityIds());
        // These two are built by the game from live entities; here from the bytes the client receives.
        FriendlyByteBuf passengers = new FriendlyByteBuf(Unpooled.buffer());
        passengers.writeVarInt(10);
        passengers.writeVarIntArray(new int[] {11, 12});
        ClientboundSetPassengersPacket setPassengers = ClientboundSetPassengersPacket.STREAM_CODEC.decode(passengers);
        assertEquals(new ClientboundPacket.SetPassengers(10, new int[] {11, 12}), bothViews(setPassengers));
        FriendlyByteBuf head = new FriendlyByteBuf(Unpooled.buffer());
        head.writeVarInt(13);
        head.writeByte(-77);
        ClientboundRotateHeadPacket rotateHead = ClientboundRotateHeadPacket.STREAM_CODEC.decode(head);
        assertEquals(new ClientboundPacket.RotateHead(13, (byte) -77), bothViews(rotateHead));
    }

    @Test
    void synchedDataKeepsScalarsExactlyAndCountsTheRest() {
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(14, List.of(
                new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 0b1000_0010),
                new SynchedEntityData.DataValue<>(6, EntityDataSerializers.POSE, Pose.CROUCHING),
                new SynchedEntityData.DataValue<>(9, EntityDataSerializers.FLOAT, -0.0F),
                new SynchedEntityData.DataValue<>(16, EntityDataSerializers.BOOLEAN, true),
                new SynchedEntityData.DataValue<>(17, EntityDataSerializers.INT, -5),
                new SynchedEntityData.DataValue<>(18, EntityDataSerializers.LONG, Long.MIN_VALUE),
                new SynchedEntityData.DataValue<>(19, EntityDataSerializers.DIRECTION, Direction.WEST),
                new SynchedEntityData.DataValue<>(20, EntityDataSerializers.OPTIONAL_UNSIGNED_INT, OptionalInt.empty()),
                new SynchedEntityData.DataValue<>(21, EntityDataSerializers.OPTIONAL_UNSIGNED_INT, OptionalInt.of(3)),
                new SynchedEntityData.DataValue<>(22, EntityDataSerializers.BLOCK_STATE, Blocks.STONE.defaultBlockState()),
                new SynchedEntityData.DataValue<>(23, EntityDataSerializers.OPTIONAL_BLOCK_STATE, Optional.empty()),
                // Written as id 0, which the client reads back as empty: the record holds what the client sees.
                new SynchedEntityData.DataValue<>(24, EntityDataSerializers.OPTIONAL_BLOCK_STATE, Optional.of(Blocks.AIR.defaultBlockState())),
                new SynchedEntityData.DataValue<>(25, EntityDataSerializers.OPTIONAL_BLOCK_STATE, Optional.of(Blocks.STONE.defaultBlockState())),
                new SynchedEntityData.DataValue<>(26, EntityDataSerializers.SNIFFER_STATE, net.minecraft.world.entity.animal.sniffer.Sniffer.State.DIGGING),
                new SynchedEntityData.DataValue<>(8, EntityDataSerializers.ROTATIONS, new Rotations(1F, 2F, 3F)),
                new SynchedEntityData.DataValue<>(2, EntityDataSerializers.OPTIONAL_COMPONENT, Optional.of(Component.literal("name")))));
        // Paper's encoder needs its global configuration (it sanitizes item values; scalars pass unchanged): the
        // test writes the values itself, as the encoder does, and the client's codec reads them back.
        RegistryFriendlyByteBuf bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        bytes.writeVarInt(packet.id());
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            value.write(bytes);
        }
        bytes.writeByte(ClientboundSetEntityDataPacket.EOF_MARKER);
        ClientboundSetEntityDataPacket received = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(bytes);
        assertEquals(0, bytes.readableBytes());
        ClientboundPacket.SetEntityData data = (ClientboundPacket.SetEntityData) bothViews(packet, received);
        assertEquals(new ClientboundPacket.SetEntityData(14, List.of(
                new DataEntry(0, Kind.BYTE, (byte) 0b1000_0010),
                new DataEntry(6, Kind.POSE, ClientboundPacket.EntityPose.CROUCHING.ordinal()),
                new DataEntry(9, Kind.FLOAT, Float.floatToRawIntBits(-0.0F)),
                new DataEntry(16, Kind.BOOLEAN, 1),
                new DataEntry(17, Kind.INT, -5),
                new DataEntry(18, Kind.LONG, Long.MIN_VALUE),
                new DataEntry(19, Kind.DIRECTION, Direction.WEST.ordinal()),
                new DataEntry(20, Kind.OPTIONAL_UNSIGNED_INT, -1),
                new DataEntry(21, Kind.OPTIONAL_UNSIGNED_INT, 3),
                new DataEntry(22, Kind.BLOCK_STATE, stone),
                new DataEntry(23, Kind.OPTIONAL_BLOCK_STATE, -1),
                new DataEntry(24, Kind.OPTIONAL_BLOCK_STATE, -1),
                new DataEntry(25, Kind.OPTIONAL_BLOCK_STATE, stone),
                new DataEntry(26, Kind.SNIFFER_STATE, 5)), 2), data);
    }

    @Test
    void tickingStateAndStepKeepTheirValues() {
        assertEquals(new ClientboundPacket.TickingState(12.5F, true), bothViews(new ClientboundTickingStatePacket(12.5F, true)));
        assertEquals(new ClientboundPacket.TickingState(20.0F, false), bothViews(new ClientboundTickingStatePacket(20.0F, false)));
        assertEquals(new ClientboundPacket.TickingStep(37), bothViews(new ClientboundTickingStepPacket(37)));
    }

    @Test
    void playerInfoKeepsWhoTheClientKnowsAndGameModes() {
        UUID a = UUID.fromString("00000000-0000-4000-8000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-4000-8000-00000000000b");
        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(a, "Alice");
        ClientboundPlayerInfoUpdatePacket.Entry alice = new ClientboundPlayerInfoUpdatePacket.Entry(a, profile, true, 12,
                net.minecraft.world.level.GameType.SPECTATOR, null, true, 0, null);
        ClientboundPlayerInfoUpdatePacket add = new ClientboundPlayerInfoUpdatePacket(java.util.EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), List.of(alice));
        assertEquals(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(a, 3))), bothViews(add));
        ClientboundPlayerInfoUpdatePacket latency = new ClientboundPlayerInfoUpdatePacket(
                java.util.EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), List.of(alice));
        assertEquals(new ClientboundPacket.PlayerInfoUpdate(false, false, List.of()), bothViews(latency));
        assertEquals(new ClientboundPacket.PlayerInfoRemove(List.of(a, b)), bothViews(new ClientboundPlayerInfoRemovePacket(List.of(a, b))));
    }
}
