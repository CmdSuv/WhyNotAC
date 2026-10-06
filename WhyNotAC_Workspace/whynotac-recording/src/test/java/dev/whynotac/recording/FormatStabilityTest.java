package dev.whynotac.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.EventCodec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins format version 1. If this test fails, a change altered the bytes on disk: either revert it
 * or bump {@code RecordingFormat.VERSION} and keep a reader for version 1 (old recordings are
 * evidence and must stay readable).
 */
class FormatStabilityTest {

    @Test
    void encodingOfAFixedTimelineIsFrozen() {
        List<TimelineEvent> events = List.of(
                new TimelineEvent.Bound(0, 1_000, new UUID(0x1234, 0x5678), "anon-00001234", 42),
                new TimelineEvent.Inbound(1, 1_050, 0, new ServerboundPacket.MovePlayer(1.5, 64.0, -2.25, 90.0F, -10.5F, true, true, true, false)),
                new TimelineEvent.Inbound(2, 1_060, 0, new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 91.0F, -10.5F, false, true, true, false)),
                new TimelineEvent.Inbound(3, 1_070, 0, new ServerboundPacket.PlayerInput(true, false, false, true, false, false, true)),
                new TimelineEvent.Inbound(4, 1_080, 0, new ServerboundPacket.ClientTickEnd()),
                new TimelineEvent.Outbound(5, 1_090, new ClientboundPacket.Ping(0x574E0001)),
                new TimelineEvent.Outbound(6, 1_091, new ClientboundPacket.SetEntityMotion(42, new Vec3d(0.25, 0.4, -0.125))),
                new TimelineEvent.Outbound(7, 1_092, new ClientboundPacket.PlayerPosition(3, Vec3d.ZERO, Vec3d.Y_AXIS, 0F, 0F, Set.of(RelativeFlag.X, RelativeFlag.ROTATE_DELTA))),
                new TimelineEvent.Inbound(8, 2_000, 1, new ServerboundPacket.Pong(0x574E0001)),
                new TimelineEvent.Acknowledged(9, 2_000, 0x574E0001, 1_090, 0),
                new TimelineEvent.Inbound(10, 2_100, 1, new ServerboundPacket.InteractEntity(7, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)),
                new TimelineEvent.Inbound(11, 2_110, 1, new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.STAB,
                        new dev.whynotac.protocol.packet.BlockPosition(0, 0, 0), BlockFace.DOWN, 5)),
                new TimelineEvent.Inbound(12, 2_120, 1, new ServerboundPacket.Swing(Hand.MAIN_HAND)),
                new TimelineEvent.Inbound(13, 2_130, 1, new ServerboundPacket.Unmodeled("minecraft:chat")),
                new TimelineEvent.Inbound(14, 2_131, 1, new ServerboundPacket.Unmodeled("minecraft:chat")),
                new TimelineEvent.Anomaly(15, 2_200, TimelineEvent.Anomaly.Kind.SKIPPED_TRANSACTION, "1"),
                new TimelineEvent.Gap(17, 2_300, 1),
                new TimelineEvent.Disconnected(18, 3_000));
        EventCodec.Encoder encoder = new EventCodec.Encoder();
        encoder.reset(0, 1_000, 0);
        ByteSink sink = new ByteSink(512);
        events.forEach(e -> encoder.encode(e, sink));
        assertEquals(GOLDEN, HexFormat.of().formatHex(sink.toByteArray()));
    }

    /** Format version 1 bytes of the timeline above, generated once from the reference implementation and committed. */
    private static final String GOLDEN = ""
            + "0422010000000000000012340000000000005678000d616e6f6e2d303030303132333454012501640001073ff8000000"
            + "0000004050000000000000c00200000000000042b40000c1280000010d011400010642b60000c1280000010501140002"
            + "4901040114000a0207011401574e0001021c010205543fd00000000000003fd999999999999abfc0000000000000023e"
            + "0102070600000000000000000000000000000000000000000000000000000000000000003ff000000000000000000000"
            + "0000000000000000000000008102010901980e020b574e000103090100574e00019c0e00010801c80100050e0100010a"
            + "0114000407000000000a01050114000600011401140015000e6d696e6563726166743a63686174010501020015020607"
            + "018a0101000131050402c80102070301f80a";

    @Test
    void enumOrdinalsUsedOnDiskAreFrozen() {
        assertEquals(List.of("UNKNOWN_TRANSACTION", "SKIPPED_TRANSACTION", "NON_FINITE_MOVEMENT", "DUPLICATE_TRANSACTION", "TRANSACTIONS_WITHHELD"),
                names(TimelineEvent.Anomaly.Kind.values()));
        assertEquals(List.of("STOP_SLEEPING", "START_SPRINTING", "STOP_SPRINTING", "START_RIDING_JUMP", "STOP_RIDING_JUMP", "OPEN_INVENTORY", "START_FALL_FLYING", "UNKNOWN"),
                names(ServerboundPacket.PlayerCommand.Action.values()));
        assertEquals(List.of("START_DESTROY_BLOCK", "ABORT_DESTROY_BLOCK", "STOP_DESTROY_BLOCK", "DROP_ALL_ITEMS", "DROP_ITEM", "RELEASE_USE_ITEM",
                "SWAP_ITEM_WITH_OFFHAND", "STAB", "UNKNOWN"), names(ServerboundPacket.PlayerAction.Action.values()));
        assertEquals(List.of("INTERACT", "ATTACK", "INTERACT_AT"), names(ServerboundPacket.InteractEntity.Kind.values()));
        assertEquals(List.of("STATUS", "LOGIN", "TRANSFER", "UNKNOWN"), names(ServerboundPacket.Handshake.Intent.values()));
        assertEquals(List.of("MAIN_HAND", "OFF_HAND"), names(Hand.values()));
        assertEquals(List.of("DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"), names(BlockFace.values()));
        assertEquals(List.of("X", "Y", "Z", "Y_ROT", "X_ROT", "DELTA_X", "DELTA_Y", "DELTA_Z", "ROTATE_DELTA", "UNKNOWN"), names(RelativeFlag.values()));
        assertEquals(List.of("ADD_VALUE", "ADD_MULTIPLIED_BASE", "ADD_MULTIPLIED_TOTAL", "UNKNOWN"), names(ClientboundPacket.UpdateAttributes.Operation.values()));
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
