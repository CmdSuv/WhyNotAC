package dev.whynotac.paper.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.latency.TransactionTracker;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The handler inside a real Netty pipeline ({@link EmbeddedChannel}) with real 1.21.11 packet
 * objects, several of them decoded from wire bytes through the game's own codecs.
 */
class WhyNotChannelHandlerTest {

    private static final int SELF = 77;

    private EmbeddedChannel channel;
    private PlayerSession session;
    private final List<TimelineEvent> events = new ArrayList<>();
    private final List<Object> forwardedInbound = new ArrayList<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1024, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener((s, e) -> events.add(e));
        session = registry.open("embedded");
        channel = new EmbeddedChannel();
        channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                forwardedInbound.add(msg);
            }
        });
        WhyNotChannelHandler handler = new WhyNotChannelHandler(session, new PacketTranslator(), Logger.getAnonymousLogger());
        handler.selfEntityId(SELF);
        channel.pipeline().addBefore("packet_handler", PacketInterceptor.HANDLER_NAME, handler);
    }

    private List<Object> drainOutbound() {
        List<Object> out = new ArrayList<>();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            out.add(msg);
        }
        return out;
    }

    private static <T> T decode(net.minecraft.network.codec.StreamCodec<? super FriendlyByteBuf, T> codec, java.util.function.Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        writer.accept(buf);
        return codec.decode(buf);
    }

    @Test
    void inboundPacketsAreObservedAndForwardedUnchanged() {
        Packet<?> move = new ServerboundMovePlayerPacket.PosRot(1.25, 64.0, -3.5, 1234.5678F, -12.25F, true, false);
        channel.writeInbound(move);
        assertSame(move, forwardedInbound.getFirst(), "the game must receive the identical object");
        ServerboundPacket.MovePlayer observed = assertInstanceOf(ServerboundPacket.MovePlayer.class,
                ((TimelineEvent.Inbound) events.getFirst()).packet());
        assertEquals(new ServerboundPacket.MovePlayer(1.25, 64.0, -3.5, 1234.5678F, -12.25F, true, true, true, false), observed);
    }

    @Test
    void absentMovementComponentsBecomeNaN() {
        channel.writeInbound(new ServerboundMovePlayerPacket.Rot(90.0F, 45.0F, false, true));
        ServerboundPacket.MovePlayer rot = (ServerboundPacket.MovePlayer) ((TimelineEvent.Inbound) events.getFirst()).packet();
        assertEquals(ServerboundPacket.MovePlayer.Shape.ROT, rot.shape());
        assertTrue(Double.isNaN(rot.x()));
        assertTrue(rot.horizontalCollision());
    }

    @Test
    void keyboardStateAttackAndSpearStabAreTranslated() {
        channel.writeInbound(new ServerboundPlayerInputPacket(new Input(true, false, true, false, false, true, true)));
        // Attack decoded from wire bytes: entity id, action type ATTACK (1), secondary action flag.
        ServerboundInteractPacket attack = decode(ServerboundInteractPacket.STREAM_CODEC, buf -> {
            buf.writeVarInt(1234);
            buf.writeVarInt(1);
            buf.writeBoolean(true);
        });
        channel.writeInbound(attack);
        channel.writeInbound(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STAB, BlockPos.ZERO, Direction.DOWN, 9));

        assertEquals(new ServerboundPacket.PlayerInput(true, false, true, false, false, true, true), ((TimelineEvent.Inbound) events.get(0)).packet());
        assertEquals(new ServerboundPacket.InteractEntity(1234, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, true),
                ((TimelineEvent.Inbound) events.get(1)).packet());
        ServerboundPacket.PlayerAction stab = (ServerboundPacket.PlayerAction) ((TimelineEvent.Inbound) events.get(2)).packet();
        assertEquals(ServerboundPacket.PlayerAction.Action.STAB, stab.action());
        assertEquals(9, stab.sequence());
    }

    @Test
    void useItemOnKeepsTheExactHitLocation() {
        BlockHitResult hit = new BlockHitResult(new Vec3(10.5, 64.0, -2.25), Direction.UP, new BlockPos(10, 63, -3), false);
        channel.writeInbound(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, 4));
        ServerboundPacket.UseItemOn use = (ServerboundPacket.UseItemOn) ((TimelineEvent.Inbound) events.getFirst()).packet();
        assertEquals(Hand.OFF_HAND, use.hand());
        assertEquals(new Vec3d(10.5, 64.0, -2.25), use.location());
        assertEquals(dev.whynotac.protocol.packet.BlockFace.UP, use.face());
    }

    @Test
    void clientTickFramingFollowsTickEndPackets() {
        channel.writeInbound(new ServerboundMovePlayerPacket.StatusOnly(true, false));
        channel.writeInbound(ServerboundClientTickEndPacket.INSTANCE);
        channel.writeInbound(new ServerboundMovePlayerPacket.StatusOnly(true, false));
        assertEquals(1, ((TimelineEvent.Inbound) events.get(2)).clientTick());
        assertEquals(1, session.clientTicks());
    }

    @Test
    void selfKnockbackIsSandwichedBetweenTransactions() {
        ClientboundSetEntityMotionPacket knockback = new ClientboundSetEntityMotionPacket(SELF, new Vec3(0.312345, 0.36, -0.221));
        channel.writeOutbound(knockback);
        List<Object> wire = drainOutbound();
        assertEquals(3, wire.size());
        int before = assertInstanceOf(ClientboundPingPacket.class, wire.get(0)).getId();
        assertSame(knockback, wire.get(1));
        int after = assertInstanceOf(ClientboundPingPacket.class, wire.get(2)).getId();
        assertTrue(TransactionTracker.isWhyNotAcId(before) && TransactionTracker.isWhyNotAcId(after) && before != after);

        // Timeline order matches wire order, and the motion carries the exact client-side quantisation.
        assertEquals(new ClientboundPacket.Ping(before), ((TimelineEvent.Outbound) events.get(0)).packet());
        ClientboundPacket.SetEntityMotion motion = (ClientboundPacket.SetEntityMotion) ((TimelineEvent.Outbound) events.get(1)).packet();
        assertEquals(LpVec3.quantize(new Vec3d(0.312345, 0.36, -0.221)), motion.clientMotion());
        assertEquals(new ClientboundPacket.Ping(after), ((TimelineEvent.Outbound) events.get(2)).packet());

        channel.writeInbound(new ServerboundPongPacket(before));
        channel.writeInbound(new ServerboundPongPacket(after));
        long acks = events.stream().filter(e -> e instanceof TimelineEvent.Acknowledged a && a.skipped() == 0).count();
        assertEquals(2, acks);
    }

    @Test
    void otherEntitiesKnockbackIsNotSandwiched() {
        channel.writeOutbound(new ClientboundSetEntityMotionPacket(SELF + 1, new Vec3(0.1, 0.2, 0.3)));
        assertEquals(1, drainOutbound().size());
    }

    @Test
    void bundlesAreFlattenedAndSandwichedAsAWhole() {
        ClientboundExplodePacket explosion = new ClientboundExplodePacket(new Vec3(5, 65, 5), 3.0F, 0,
                java.util.Optional.of(new Vec3(0.75, 0.5, -0.25)), net.minecraft.core.particles.ParticleTypes.EXPLOSION,
                net.minecraft.sounds.SoundEvents.WIND_CHARGE_BURST, net.minecraft.util.random.WeightedList.of());
        ClientboundPlayerPositionPacket teleport = ClientboundPlayerPositionPacket.of(5,
                new PositionMoveRotation(new Vec3(1, 2, 3), Vec3.ZERO, 10.0F, 20.0F), Set.of(Relative.Y_ROT));
        List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> parts = List.of(explosion, teleport);
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(parts);
        channel.writeOutbound(bundle);
        List<Object> wire = drainOutbound();
        assertEquals(3, wire.size());
        assertSame(bundle, wire.get(1));

        // Timeline in wire order: transaction, both bundle parts, transaction.
        assertInstanceOf(ClientboundPacket.Ping.class, ((TimelineEvent.Outbound) events.get(0)).packet());
        ClientboundPacket.Explosion modeledExplosion = (ClientboundPacket.Explosion) ((TimelineEvent.Outbound) events.get(1)).packet();
        assertEquals(new Vec3d(0.75, 0.5, -0.25), modeledExplosion.playerKnockback());
        ClientboundPacket.PlayerPosition modeledTeleport = (ClientboundPacket.PlayerPosition) ((TimelineEvent.Outbound) events.get(2)).packet();
        assertEquals(5, modeledTeleport.teleportId());
        assertEquals(Set.of(dev.whynotac.protocol.packet.RelativeFlag.Y_ROT), modeledTeleport.relatives());
        assertInstanceOf(ClientboundPacket.Ping.class, ((TimelineEvent.Outbound) events.get(3)).packet());
    }

    @Test
    void translationFailureFailsOpen() {
        // A packet that blows up inside WhyNotAC must still reach the game untouched.
        Packet<net.minecraft.network.protocol.game.ServerGamePacketListener> hostile = new Packet<>() {
            @Override
            public net.minecraft.network.protocol.PacketType<? extends Packet<net.minecraft.network.protocol.game.ServerGamePacketListener>> type() {
                throw new IllegalStateException("simulated translator failure");
            }

            @Override
            public void handle(net.minecraft.network.protocol.game.ServerGamePacketListener listener) {
            }
        };
        channel.writeInbound(hostile);
        assertSame(hostile, forwardedInbound.getFirst());
        assertTrue(events.isEmpty(), "nothing half-translated may reach the timeline");
        channel.writeInbound(ServerboundClientTickEndPacket.INSTANCE);
        assertEquals(1, events.size(), "the handler keeps working after a failure");
    }

    @Test
    void closingTheChannelEndsTheSession() {
        channel.writeInbound(ServerboundClientTickEndPacket.INSTANCE);
        channel.close();
        assertInstanceOf(TimelineEvent.Disconnected.class, events.getLast());
        assertEquals(1, events.stream().filter(e -> e instanceof TimelineEvent.Disconnected).count(), "exactly one disconnect");
        assertNull(channel.pipeline().get(PacketInterceptor.HANDLER_NAME));
    }
}
