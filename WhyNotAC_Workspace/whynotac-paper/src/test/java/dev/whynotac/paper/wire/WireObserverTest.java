package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.DivergenceKind;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.network.ConnectionEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Logger;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The edge taps and the reconciliation inside a real Netty pipeline built like the server's: the
 * game's own {@code PacketDecoder}/{@code PacketEncoder}/{@code PacketBundleUnpacker} with the 1.21.11
 * play codecs, WhyNotAC's game-side handler, and stand-ins for the plugins that sit in between.
 *
 * <pre>
 *   splitter · wire_in · [between-in] · decoder · prepender · wire_out · [between-out] · encoder · unbundler · interceptor · packet_handler
 * </pre>
 */
class WireObserverTest {

    private static final int SELF = 77;
    private static RegistryAccess registries;
    private static ProtocolInfo<ServerGamePacketListener> serverbound;
    private static ProtocolInfo<ClientGamePacketListener> clientbound;

    private final List<TimelineEvent> events = new ArrayList<>();
    private final List<Object> processedByGame = new ArrayList<>();
    private EmbeddedChannel channel;
    private WireObserver observer;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        serverbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), () -> true);
        clientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
    }

    /** A pass-through placeholder for a game handler the test does not need (framing, compression). */
    @ChannelHandler.Sharable
    private static final class Placeholder extends ChannelDuplexHandler {}

    @BeforeEach
    void setUp() {
        // No grace period: a packet counts as cancelled as soon as a later one passed it (production waits 2 s).
        build(0);
    }

    /** (Re)builds the pipeline; tests about entries that are still pending use the production grace period. */
    private void build(long graceNanos) {
        events.clear();
        processedByGame.clear();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1 << 16, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener((s, e) -> events.add(e));
        PlayerSession session = registry.open("embedded");
        observer = new WireObserver(session, new PacketTranslator(), new WireProtocols(() -> registries), Logger.getAnonymousLogger(),
                SharedConstants.getProtocolVersion(), graceNanos);
        channel = new EmbeddedChannel();
        channel.pipeline()
                .addLast("splitter", new Placeholder())
                .addLast("decoder", new PacketDecoder<>(serverbound))
                .addLast("prepender", new Placeholder())
                .addLast("encoder", new PacketEncoder<>(clientbound))
                .addLast("unbundler", new PacketBundleUnpacker(clientbound.bundlerInfo()))
                .addLast("packet_handler", new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        processedByGame.add(msg);
                    }
                });
        GameSide.install(channel, session, observer);
        WireTaps.install(channel.pipeline(), observer, Logger.getAnonymousLogger());
        observer.selfEntityId(SELF);
        observer.startInPlay(0);
        events.clear();
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static ByteBuf serverboundFrame(Packet<? super ServerGamePacketListener> packet) {
        ByteBuf buf = Unpooled.buffer();
        serverbound.codec().encode(buf, packet);
        return buf;
    }

    private static ByteBuf clientboundFrame(Packet<? super ClientGamePacketListener> packet) {
        ByteBuf buf = Unpooled.buffer();
        clientbound.codec().encode(buf, packet);
        return buf;
    }

    /** What the client receives: every outbound frame, decoded with the client's codec. */
    private List<Packet<?>> clientReceived() {
        List<Packet<?>> out = new ArrayList<>();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            ByteBuf frame = (ByteBuf) msg;
            out.add(clientbound.codec().decode(frame));
            frame.release();
        }
        return out;
    }

    private static int packetId(ByteBuf frame) {
        return VarInt.read(frame.duplicate());
    }

    private static int clientboundId(Packet<? super ClientGamePacketListener> sample) {
        return packetId(clientboundFrame(sample));
    }

    private static int serverboundId(Packet<? super ServerGamePacketListener> sample) {
        return packetId(serverboundFrame(sample));
    }

    private List<TimelineEvent> eventsOf(Class<?> type) {
        return events.stream().filter(type::isInstance).toList();
    }

    private static ClientboundSetEntityMotionPacket knockback(double x, double y, double z) {
        return new ClientboundSetEntityMotionPacket(SELF, new Vec3(x, y, z));
    }

    /** Drops matching frames between the edge and the game, like a plugin cancelling a packet. */
    private static ChannelHandler dropInbound(Predicate<ByteBuf> drop) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof ByteBuf frame && drop.test(frame)) {
                    frame.release();
                    return;
                }
                ctx.fireChannelRead(msg);
            }
        };
    }

    private static ChannelHandler dropOutbound(Predicate<ByteBuf> drop) {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf frame && drop.test(frame)) {
                    frame.release();
                    promise.setSuccess();
                    return;
                }
                ctx.write(msg, promise);
            }
        };
    }

    // --- inbound ------------------------------------------------------------------------------------

    @Test
    void inboundIsRecordedAtTheEdgeAndReconciledWithTheGame() {
        channel.writeInbound(serverboundFrame(new ServerboundMovePlayerPacket.PosRot(1.25, 64.0, -3.5, 1234.5678F, -12.25F, true, false)));
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));

        assertEquals(2, processedByGame.size(), "the game processes both, unchanged");
        List<TimelineEvent> inbound = eventsOf(TimelineEvent.Inbound.class);
        assertEquals(new ServerboundPacket.MovePlayer(1.25, 64.0, -3.5, 1234.5678F, -12.25F, true, true, true, false),
                ((TimelineEvent.Inbound) inbound.get(0)).packet());
        assertInstanceOf(ServerboundPacket.ClientTickEnd.class, ((TimelineEvent.Inbound) inbound.get(1)).packet());
        assertTrue(eventsOf(TimelineEvent.InboundDivergence.class).isEmpty(), events.toString());
    }

    @Test
    void aPacketCancelledBeforeTheGameIsStillOnTheTimelineAndFlagged() {
        int swing = serverboundId(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        channel.pipeline().addAfter(WireTaps.INBOUND, "between-in", dropInbound(frame -> packetId(frame) == swing));

        channel.writeInbound(serverboundFrame(new ServerboundSwingPacket(InteractionHand.MAIN_HAND)));
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));

        assertEquals(1, processedByGame.size(), "the game never saw the swing");
        assertEquals(List.of(new ServerboundPacket.Swing(Hand.MAIN_HAND), new ServerboundPacket.ClientTickEnd()),
                eventsOf(TimelineEvent.Inbound.class).stream().map(e -> ((TimelineEvent.Inbound) e).packet()).toList(),
                "the client did swing: the timeline keeps what it sent");
        TimelineEvent.InboundDivergence divergence = assertInstanceOf(TimelineEvent.InboundDivergence.class,
                eventsOf(TimelineEvent.InboundDivergence.class).getFirst());
        assertEquals(DivergenceKind.WIRE_ONLY, divergence.kind());
        assertEquals(new ServerboundPacket.Swing(Hand.MAIN_HAND), divergence.packet());
        assertEquals(1, observer.status().inboundWireOnly());
    }

    @Test
    void aPacketThePlayerNeverSentIsFlagged() {
        channel.pipeline().addAfter("decoder", "between-in", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ctx.fireChannelRead(new ServerboundSwingPacket(InteractionHand.OFF_HAND)); // synthesised by a plugin
                ctx.fireChannelRead(msg);
            }
        });
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));

        assertEquals(List.of(new ServerboundPacket.ClientTickEnd()),
                eventsOf(TimelineEvent.Inbound.class).stream().map(e -> ((TimelineEvent.Inbound) e).packet()).toList());
        TimelineEvent.InboundDivergence divergence = (TimelineEvent.InboundDivergence) eventsOf(TimelineEvent.InboundDivergence.class).getFirst();
        assertEquals(DivergenceKind.GAME_ONLY, divergence.kind());
        assertEquals(new ServerboundPacket.Swing(Hand.OFF_HAND), divergence.packet());
    }

    // --- outbound -----------------------------------------------------------------------------------

    @Test
    void outboundIsRecordedAsTheClientReceivesItQuantizedAndSandwiched() {
        channel.writeOutbound(knockback(0.1, 0.42, -0.3));

        List<Packet<?>> received = clientReceived();
        assertEquals(3, received.size(), received.toString());
        assertInstanceOf(ClientboundPingPacket.class, received.get(0));
        assertInstanceOf(ClientboundSetEntityMotionPacket.class, received.get(1));
        assertInstanceOf(ClientboundPingPacket.class, received.get(2));

        List<TimelineEvent> outbound = eventsOf(TimelineEvent.Outbound.class);
        assertEquals(3, outbound.size(), events.toString());
        ClientboundPacket.SetEntityMotion motion = (ClientboundPacket.SetEntityMotion) ((TimelineEvent.Outbound) outbound.get(1)).packet();
        assertEquals(LpVec3.quantize(new Vec3d(0.1, 0.42, -0.3)), motion.clientMotion(), "the velocity the client really applies");
        assertNull(motion.serverMotion(), "the edge only knows what the client received");
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), events.toString());
    }

    @Test
    void edgeVelocityIsQuantizedExactlyOnce() {
        // LpVec3 is not idempotent: re-quantizing this vector changes it (the z scale drops from 8 to 7).
        Vec3d v = new Vec3d(1.884547125526538, 4.484370533432598, 7.00008200728259);
        Vec3d once = LpVec3.quantize(v);
        assertTrue(!once.equals(LpVec3.quantize(once)), "precondition: the vector is a non-idempotent case");

        channel.writeOutbound(knockback(v.x(), v.y(), v.z()));

        ClientboundPacket.SetEntityMotion recorded = eventsOf(TimelineEvent.Outbound.class).stream()
                .map(e -> ((TimelineEvent.Outbound) e).packet()).filter(p -> p instanceof ClientboundPacket.SetEntityMotion)
                .map(p -> (ClientboundPacket.SetEntityMotion) p).findFirst().orElseThrow();
        assertEquals(once, recorded.clientMotion(), "what the client decodes from the bytes, bit for bit");
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), events.toString());
    }

    @Test
    void knockbackInjectedBelowTheGameIsSeenAndBracketedAtTheEdge() {
        // PacketEvents' sendPacketSilently: pre-encoded bytes written below the game's send path.
        channel.pipeline().addAfter(WireTaps.OUTBOUND, "pe-encoder", new Placeholder());
        channel.pipeline().context("pe-encoder").writeAndFlush(clientboundFrame(knockback(0.0, 0.5, 0.0)));

        List<Packet<?>> received = clientReceived();
        assertEquals(3, received.size(), received.toString());
        assertInstanceOf(ClientboundPingPacket.class, received.get(0));
        assertInstanceOf(ClientboundSetEntityMotionPacket.class, received.get(1));
        assertInstanceOf(ClientboundPingPacket.class, received.get(2));

        List<TimelineEvent> outbound = eventsOf(TimelineEvent.Outbound.class);
        assertEquals(3, outbound.size(), events.toString());
        assertEquals(((ClientboundPingPacket) received.get(0)).getId(), ((ClientboundPacket.Ping) ((TimelineEvent.Outbound) outbound.get(0)).packet()).id());
        TimelineEvent.OutboundDivergence divergence = (TimelineEvent.OutboundDivergence) eventsOf(TimelineEvent.OutboundDivergence.class).getFirst();
        assertEquals(DivergenceKind.WIRE_ONLY, divergence.kind());
        assertEquals(ClientboundPacket.SetEntityMotion.received(SELF, LpVec3.quantize(new Vec3d(0.0, 0.5, 0.0))), divergence.packet());
        assertEquals(2, observer.status().edgeTransactions());
    }

    @Test
    void preEncodedBytesWrittenThroughTheWholePipelineAreAlsoCaught() {
        // PacketEvents' sendPacket: a ByteBuf written from the tail passes the game-side handler as bytes.
        channel.writeAndFlush(clientboundFrame(knockback(0.25, 0.0, 0.0)));

        TimelineEvent.OutboundDivergence divergence = (TimelineEvent.OutboundDivergence) eventsOf(TimelineEvent.OutboundDivergence.class).getFirst();
        assertEquals(DivergenceKind.WIRE_ONLY, divergence.kind());
        assertEquals(3, clientReceived().size(), "bracketed at the edge");
    }

    @Test
    void knockbackCancelledBelowTheGameNeverReachesTheTimeline() {
        int motion = clientboundId(knockback(0, 0, 0));
        channel.pipeline().addAfter(WireTaps.OUTBOUND, "pe-encoder", dropOutbound(frame -> packetId(frame) == motion));

        channel.writeOutbound(knockback(0.1, 0.42, -0.3));

        List<Packet<?>> received = clientReceived();
        assertEquals(2, received.size(), "only the two pings arrive: " + received);
        assertTrue(eventsOf(TimelineEvent.Outbound.class).stream()
                        .noneMatch(e -> ((TimelineEvent.Outbound) e).packet() instanceof ClientboundPacket.SetEntityMotion),
                "the client never got the knockback, so the timeline must not claim it did");
        TimelineEvent.OutboundDivergence divergence = (TimelineEvent.OutboundDivergence) eventsOf(TimelineEvent.OutboundDivergence.class).getFirst();
        assertEquals(DivergenceKind.GAME_ONLY, divergence.kind());
        assertEquals(ClientboundPacket.SetEntityMotion.sent(SELF, new Vec3d(0.1, 0.42, -0.3)), divergence.packet(), "reported as the game sent it");
    }

    @Test
    void aRewrittenKnockbackIsRecordedAsTheClientGotIt() {
        int motion = clientboundId(knockback(0, 0, 0));
        channel.pipeline().addAfter(WireTaps.OUTBOUND, "pe-encoder", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf frame && packetId(frame) == motion) {
                    frame.release();
                    ctx.write(clientboundFrame(knockback(0.0, 0.9, 0.0)), promise); // a knockback plugin doubling it
                    return;
                }
                ctx.write(msg, promise);
            }
        });

        channel.writeOutbound(knockback(0.0, 0.45, 0.0));

        List<ClientboundPacket> motions = eventsOf(TimelineEvent.Outbound.class).stream().map(e -> ((TimelineEvent.Outbound) e).packet())
                .filter(p -> p instanceof ClientboundPacket.SetEntityMotion).toList();
        assertEquals(List.of(ClientboundPacket.SetEntityMotion.received(SELF, LpVec3.quantize(new Vec3d(0.0, 0.9, 0.0)))), motions);
        List<DivergenceKind> kinds = eventsOf(TimelineEvent.OutboundDivergence.class).stream()
                .map(e -> ((TimelineEvent.OutboundDivergence) e).kind()).toList();
        assertEquals(List.of(DivergenceKind.WIRE_ONLY, DivergenceKind.GAME_ONLY), kinds);
    }

    @Test
    void packetsAnotherPluginHoldsBackAndReleasesLaterAreNotDivergences() {
        // Like ViaVersion/PacketEvents around the start of play: frames are queued, then flushed in order.
        List<Object> held = new ArrayList<>();
        List<ChannelPromise> promises = new ArrayList<>();
        channel.pipeline().addAfter(WireTaps.OUTBOUND, "holder", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                held.add(msg);
                promises.add(promise);
            }
        });
        channel.writeOutbound(knockback(0.1, 0.42, -0.3));
        channel.writeOutbound(new ClientboundPingPacket(9));
        assertTrue(eventsOf(TimelineEvent.Outbound.class).isEmpty(), "nothing reached the client yet");

        ChannelHandlerContext holder = channel.pipeline().context("holder");
        channel.pipeline().remove("holder");
        for (int i = 0; i < held.size(); i++) {
            holder.write(held.get(i), promises.get(i));
        }
        channel.flushOutbound();
        assertEquals(4, eventsOf(TimelineEvent.Outbound.class).size(), "sandwiched knockback + a plain ping: " + events);
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), "a delay is not a divergence: " + events);
    }

    @Test
    void bundlesAreFlattenedInBothViews() {
        channel.writeOutbound(new ClientboundBundlePacket(List.of(knockback(0.1, 0, 0), new ClientboundPingPacket(5))));

        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), events.toString());
        List<ClientboundPacket> outbound = eventsOf(TimelineEvent.Outbound.class).stream().map(e -> ((TimelineEvent.Outbound) e).packet()).toList();
        assertEquals(4, outbound.size(), "transaction, knockback, foreign ping, transaction: " + outbound);
        assertEquals(new ClientboundPacket.Ping(5), outbound.get(2));
    }

    @Test
    void entityMovesAreReadAtTheEdgeAndReconciledByType() {
        channel.writeOutbound(new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Pos(77, (short) 409, (short) -3, (short) 0, true),
                new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot(77, (byte) 5, (byte) -5, false));
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), events.toString());
        List<ClientboundPacket> outbound = eventsOf(TimelineEvent.Outbound.class).stream().map(e -> ((TimelineEvent.Outbound) e).packet()).toList();
        assertEquals(List.of(new ClientboundPacket.MoveEntity(77, (short) 409, (short) -3, (short) 0, (byte) 0, (byte) 0, true, true, false),
                new ClientboundPacket.MoveEntity(77, (short) 0, (short) 0, (short) 0, (byte) 5, (byte) -5, false, false, true)), outbound,
                "the client's values, from the bytes");
    }

    // --- flush brackets ------------------------------------------------------------------------------

    /** A packet about another entity: no physics bracket of its own. */
    private static ClientboundRemoveEntitiesPacket other(int id) {
        return new ClientboundRemoveEntitiesPacket(id);
    }

    private List<Class<?>> receivedTypes() {
        return clientReceived().stream().<Class<?>>map(Object::getClass).toList();
    }

    @Test
    void everyFlushAfterTheLoginIsBracketedWithTransactions() {
        observer.loggedIn();
        channel.writeOutbound(other(5), other(6));
        assertEquals(List.of(ClientboundPingPacket.class, ClientboundRemoveEntitiesPacket.class, ClientboundRemoveEntitiesPacket.class,
                ClientboundPingPacket.class), receivedTypes());
        List<ClientboundPacket> outbound = eventsOf(TimelineEvent.Outbound.class).stream().map(e -> ((TimelineEvent.Outbound) e).packet()).toList();
        assertEquals(4, outbound.size(), outbound.toString());
        assertInstanceOf(ClientboundPacket.Ping.class, outbound.getFirst());
        assertInstanceOf(ClientboundPacket.Ping.class, outbound.getLast());
        channel.writeOutbound(other(7)); // the next flush has its own
        assertEquals(List.of(ClientboundPingPacket.class, ClientboundRemoveEntitiesPacket.class, ClientboundPingPacket.class), receivedTypes());
    }

    @Test
    void aTransactionAlreadyAtTheEdgeOfAFlushServesAsItsBracket() {
        observer.loggedIn();
        channel.writeOutbound(new ClientboundPingPacket(41), other(5), new ClientboundPingPacket(42));
        assertEquals(List.of(ClientboundPingPacket.class, ClientboundRemoveEntitiesPacket.class, ClientboundPingPacket.class), receivedTypes());
        channel.writeOutbound(knockback(0.1, 0.2, 0.3)); // bracketed for physics already
        assertEquals(List.of(ClientboundPingPacket.class, ClientboundSetEntityMotionPacket.class, ClientboundPingPacket.class), receivedTypes());
    }

    @Test
    void bracketsStayOutsideBundles() {
        observer.loggedIn();
        channel.writeOutbound(new ClientboundBundlePacket(List.of(other(5), other(6))));
        List<Class<?>> types = receivedTypes();
        assertEquals(6, types.size(), types.toString());
        assertEquals(ClientboundPingPacket.class, types.getFirst());
        assertEquals(ClientboundPingPacket.class, types.getLast());
        assertEquals(List.of(ClientboundRemoveEntitiesPacket.class, ClientboundRemoveEntitiesPacket.class), types.subList(2, 4));
    }

    @Test
    void noBracketsBeforeTheLoginOrWhenDisabled() {
        channel.writeOutbound(other(5));
        assertEquals(List.of(ClientboundRemoveEntitiesPacket.class), receivedTypes(), "not logged in yet");
        observer.loggedIn();
        observer.bracketFlushes(false);
        channel.writeOutbound(other(6));
        assertEquals(List.of(ClientboundRemoveEntitiesPacket.class), receivedTypes(), "observation.flush-transactions: false");
    }

    // --- handshake and placement ---------------------------------------------------------------------

    private static ByteBuf handshake(int protocol, int intent) {
        ByteBuf buf = Unpooled.buffer();
        VarInt.write(buf, 0); // intention
        VarInt.write(buf, protocol);
        byte[] host = "localhost".getBytes(StandardCharsets.UTF_8);
        VarInt.write(buf, host.length);
        buf.writeBytes(host);
        buf.writeShort(25565);
        VarInt.write(buf, intent);
        return buf;
    }

    /** A pipeline at connection start: the handshake decoder, the taps and the game-side handler. */
    private EmbeddedChannel freshConnection(List<TimelineEvent> sink) {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1024, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener((s, e) -> sink.add(e));
        PlayerSession session = registry.open("fresh");
        WireObserver fresh = new WireObserver(session, new PacketTranslator(), new WireProtocols(() -> registries), Logger.getAnonymousLogger(),
                SharedConstants.getProtocolVersion(), 0);
        EmbeddedChannel connection = new EmbeddedChannel();
        connection.pipeline()
                .addLast("splitter", new Placeholder())
                .addLast("decoder", new PacketDecoder<>(HandshakeProtocols.SERVERBOUND))
                .addLast("prepender", new Placeholder())
                .addLast("packet_handler", new ChannelInboundHandlerAdapter());
        GameSide.install(connection, session, fresh);
        WireTaps.install(connection.pipeline(), fresh, Logger.getAnonymousLogger());
        return connection;
    }

    @Test
    void aMatchingClientIsObservedAtTheEdgeFromItsHandshake() {
        List<TimelineEvent> sink = new ArrayList<>();
        EmbeddedChannel connection = freshConnection(sink);
        connection.writeInbound(handshake(SharedConstants.getProtocolVersion(), 2));

        TimelineEvent.Observation observation = assertInstanceOf(TimelineEvent.Observation.class, sink.getFirst());
        assertEquals(Source.WIRE, observation.inbound());
        assertEquals(SharedConstants.getProtocolVersion(), observation.clientProtocol());
        assertEquals(new ServerboundPacket.Handshake(SharedConstants.getProtocolVersion(), ServerboundPacket.Handshake.Intent.LOGIN),
                ((TimelineEvent.Inbound) sink.get(1)).packet());
        assertTrue(sink.stream().noneMatch(e -> e instanceof TimelineEvent.InboundDivergence), sink.toString());
        assertTrue(connection.pipeline().get(WireTaps.INBOUND) != null && connection.pipeline().get(WireTaps.OUTBOUND) != null);
    }

    @Test
    void aClientOnAnotherProtocolFallsBackToTheGameViewAndTheTapsLeave() {
        List<TimelineEvent> sink = new ArrayList<>();
        EmbeddedChannel connection = freshConnection(sink);
        connection.writeInbound(handshake(47, 2)); // a 1.8 client behind ViaVersion

        TimelineEvent.Observation observation = assertInstanceOf(TimelineEvent.Observation.class, sink.getFirst());
        assertEquals(Source.GAME, observation.inbound());
        assertEquals(47, observation.clientProtocol());
        assertInstanceOf(ServerboundPacket.Handshake.class, ((TimelineEvent.Inbound) sink.get(1)).packet(), "recorded by the game view");
        assertNull(connection.pipeline().get(WireTaps.INBOUND));
        assertNull(connection.pipeline().get(WireTaps.OUTBOUND));
    }

    @Test
    void aServerListPingDetachesTheTaps() {
        EmbeddedChannel connection = freshConnection(new ArrayList<>());
        connection.writeInbound(handshake(SharedConstants.getProtocolVersion(), 1));
        assertNull(connection.pipeline().get(WireTaps.INBOUND));
        assertNull(connection.pipeline().get(WireTaps.OUTBOUND));
    }

    @Test
    void unknownPacketIdsBecomeMarkersNotHoles() {
        ByteBuf frame = Unpooled.buffer();
        VarInt.write(frame, 0x7F);
        assertEquals(WireObserver.InboundAction.CONTINUE, observer.onWireInbound(frame, 1));
        ServerboundPacket packet = ((TimelineEvent.Inbound) events.getLast()).packet();
        assertTrue(assertInstanceOf(ServerboundPacket.Untranslatable.class, packet).type().startsWith("whynotac:unknown-id/"));
    }

    /** Behaves like ViaVersion: moves itself next to (de)compression when Paper announces compression. */
    @ChannelHandler.Sharable
    private static final class ViaLike extends ChannelDuplexHandler {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt == ConnectionEvent.COMPRESSION_THRESHOLD_SET) {
                var pipeline = ctx.pipeline();
                pipeline.addAfter("compress", "via-encoder", pipeline.remove("via-encoder"));
                pipeline.addAfter("decompress", "via-decoder", pipeline.remove("via-decoder"));
            }
            super.userEventTriggered(ctx, evt);
        }
    }

    @Test
    void tapsReturnToTheOuterEdgeAfterCompressionIsEnabled() {
        var pipeline = channel.pipeline();
        pipeline.addBefore("decoder", "via-decoder", new ViaLike());
        pipeline.addBefore("encoder", "via-encoder", new ViaLike());
        // What Connection.setupCompression does, then the event Paper fires.
        pipeline.addAfter("splitter", "decompress", new Placeholder());
        pipeline.addAfter("prepender", "compress", new Placeholder());
        pipeline.fireUserEventTriggered(ConnectionEvent.COMPRESSION_THRESHOLD_SET);
        List<String> afterVia = pipeline.names();
        assertEquals(afterVia.indexOf("decompress") + 1, afterVia.indexOf("via-decoder"), "ViaVersion moved in front of the tap: " + afterVia);

        channel.runPendingTasks();
        List<String> names = pipeline.names();
        assertEquals(names.indexOf("decompress") + 1, names.indexOf(WireTaps.INBOUND), names.toString());
        assertEquals(names.indexOf(WireTaps.INBOUND) + 1, names.indexOf("via-decoder"), names.toString());
        assertEquals(names.indexOf("compress") + 1, names.indexOf(WireTaps.OUTBOUND), names.toString());
        assertEquals(names.indexOf(WireTaps.OUTBOUND) + 1, names.indexOf("via-encoder"), names.toString());
        assertEquals(1, observer.status().placementRepairs());

        // Still decoding correctly at the new position.
        channel.writeOutbound(knockback(0.0, 0.42, 0.0));
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), events.toString());
    }

    @Test
    void aTapRemovedByAnotherPluginNeverLeavesASilentHole() {
        channel.pipeline().remove(WireTaps.INBOUND); // e.g. a plugin "cleaning up" unknown handlers
        TimelineEvent.Observation observation = (TimelineEvent.Observation) eventsOf(TimelineEvent.Observation.class).getLast();
        assertEquals(Source.GAME, observation.inbound());
        assertTrue(observation.detail().contains("removed by another plugin"), observation.detail());

        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        channel.writeOutbound(knockback(0.0, 0.42, 0.0));
        assertEquals(List.of(new ServerboundPacket.ClientTickEnd()),
                eventsOf(TimelineEvent.Inbound.class).stream().map(e -> ((TimelineEvent.Inbound) e).packet()).toList(),
                "the game's view records what the edge no longer can");
        assertTrue(eventsOf(TimelineEvent.Outbound.class).stream()
                .anyMatch(e -> ((TimelineEvent.Outbound) e).packet() instanceof ClientboundPacket.SetEntityMotion));
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty() && eventsOf(TimelineEvent.InboundDivergence.class).isEmpty());
    }

    @Test
    void aPacketCancelledBeforeTheEdgeWasLostNeverSwallowsALaterIdenticalOne() {
        // Regression (review): production grace keeps the cancelled swing pending; after the tap was removed
        // the game-side view matched the NEW swing against it and never recorded it.
        build(WireObserver.DEFAULT_GRACE_NANOS);
        int swing = serverboundId(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        boolean[] cancelOnce = {true};
        channel.pipeline().addAfter(WireTaps.INBOUND, "swing-filter", dropInbound(frame -> {
            if (cancelOnce[0] && packetId(frame) == swing) {
                cancelOnce[0] = false;
                return true;
            }
            return false;
        }));
        channel.writeInbound(serverboundFrame(new ServerboundSwingPacket(InteractionHand.MAIN_HAND))); // cancelled before the game
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        channel.pipeline().remove(WireTaps.INBOUND); // another plugin removes the tap
        events.clear();
        channel.writeInbound(serverboundFrame(new ServerboundSwingPacket(InteractionHand.MAIN_HAND))); // the client swings again
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        assertEquals(List.of(new ServerboundPacket.Swing(dev.whynotac.protocol.packet.Hand.MAIN_HAND), new ServerboundPacket.ClientTickEnd()),
                eventsOf(TimelineEvent.Inbound.class).stream().map(e -> ((TimelineEvent.Inbound) e).packet()).toList());
    }

    @Test
    void aPacketInFlightWhenTheEdgeIsLostIsRecordedOnce() {
        build(WireObserver.DEFAULT_GRACE_NANOS);
        // A plugin between the taps holds inbound packets; the tap is removed while one is held.
        List<Object> held = new ArrayList<>();
        boolean[] holding = {true};
        channel.pipeline().addAfter(WireTaps.INBOUND, "holder", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (holding[0]) {
                    held.add(msg);
                } else {
                    ctx.fireChannelRead(msg);
                }
            }
        });
        channel.writeInbound(serverboundFrame(new ServerboundSwingPacket(InteractionHand.MAIN_HAND)));
        channel.pipeline().remove(WireTaps.INBOUND);
        holding[0] = false;
        ChannelHandlerContext holder = channel.pipeline().context("holder");
        held.forEach(holder::fireChannelRead); // released after the edge was lost: the edge recorded it already
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        assertEquals(List.of(new ServerboundPacket.Swing(dev.whynotac.protocol.packet.Hand.MAIN_HAND), new ServerboundPacket.ClientTickEnd()),
                eventsOf(TimelineEvent.Inbound.class).stream().map(e -> ((TimelineEvent.Inbound) e).packet()).toList(),
                "recorded exactly once each");
        assertTrue(eventsOf(TimelineEvent.InboundDivergence.class).isEmpty());
    }

    @Test
    void aFloodOfCancelledMotionPacketsNeverHidesThePlayersOwnKnockback() {
        // Regression (review): an entity-hiding plugin below WhyNotAC cancels other entities' motion packets.
        // With the 2 s grace period they stay pending; the own knockback was not found among them and was
        // flagged as injected, with a second edge sandwich (5 frames instead of 3).
        build(WireObserver.DEFAULT_GRACE_NANOS);
        int motion = clientboundId(knockback(0, 0, 0));
        channel.pipeline().addAfter(WireTaps.OUTBOUND, "entity-hider", dropOutbound(frame -> {
            if (packetId(frame) != motion) {
                return false;
            }
            ByteBuf view = frame.duplicate();
            VarInt.read(view);
            return VarInt.read(view) != SELF;
        }));
        for (int i = 0; i < 1_000; i++) {
            channel.writeOutbound(new ClientboundSetEntityMotionPacket(1_000 + i, new Vec3(0.01 * (i % 100), 0, 0)));
        }
        clientReceived();
        events.clear();
        channel.writeOutbound(knockback(0.0, 0.42, 0.0));
        List<Packet<?>> received = clientReceived();
        assertEquals(3, received.size(), "ping, knockback, ping: " + received);
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), eventsOf(TimelineEvent.OutboundDivergence.class).toString());
    }

    @Test
    void theGameSideHandlerRemovedByAnotherPluginLeavesTheEdgeRecording() {
        channel.pipeline().remove(dev.whynotac.paper.network.PacketInterceptor.HANDLER_NAME);
        assertTrue(eventsOf(TimelineEvent.Disconnected.class).isEmpty(), "the player is still connected");
        TimelineEvent.Observation observation = (TimelineEvent.Observation) eventsOf(TimelineEvent.Observation.class).getLast();
        assertTrue(observation.detail().contains("game-side handler removed"), observation.detail());

        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        channel.writeOutbound(knockback(0.0, 0.42, 0.0)); // no game-side sandwich any more...
        List<Packet<?>> received = clientReceived();
        assertEquals(3, received.size(), "...so the edge brackets it: " + received);
        assertInstanceOf(ClientboundPingPacket.class, received.get(0));
        assertInstanceOf(ClientboundPingPacket.class, received.get(2));
        assertTrue(eventsOf(TimelineEvent.OutboundDivergence.class).isEmpty(), "nothing to reconcile, nothing reported");
        assertTrue(eventsOf(TimelineEvent.Inbound.class).stream().anyMatch(e -> ((TimelineEvent.Inbound) e).packet() instanceof ServerboundPacket.ClientTickEnd));

        channel.close();
        assertEquals(1, eventsOf(TimelineEvent.Disconnected.class).size(), "the edge ends the session at disconnect");
    }

    @Test
    void emptyFramesAreNothingOnBothSides() {
        channel.writeInbound(Unpooled.EMPTY_BUFFER.retainedDuplicate()); // a client sending [len=1][dataLength=0]
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        assertEquals(List.of(new ServerboundPacket.ClientTickEnd()),
                eventsOf(TimelineEvent.Inbound.class).stream().map(e -> ((TimelineEvent.Inbound) e).packet()).toList(),
                "no marker for a frame nobody processes");
        assertTrue(eventsOf(TimelineEvent.InboundDivergence.class).isEmpty(), events.toString());

        // A plugin encoder that "cancels" by writing nothing: the client gets no packet, so neither does the timeline.
        int motion = clientboundId(knockback(0, 0, 0));
        channel.pipeline().addAfter(WireTaps.OUTBOUND, "pe-encoder", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (msg instanceof ByteBuf frame && packetId(frame) == motion) {
                    frame.release();
                    ctx.write(Unpooled.EMPTY_BUFFER, promise);
                    return;
                }
                ctx.write(msg, promise);
            }
        });
        channel.writeOutbound(knockback(0.0, 0.42, 0.0));
        assertTrue(eventsOf(TimelineEvent.Outbound.class).stream()
                .noneMatch(e -> ((TimelineEvent.Outbound) e).packet() instanceof ClientboundPacket.SetEntityMotion));
        assertEquals(DivergenceKind.GAME_ONLY, ((TimelineEvent.OutboundDivergence) eventsOf(TimelineEvent.OutboundDivergence.class).getFirst()).kind());
    }

    @Test
    void aFloodOfPacketsTheGameMaySkipNeverBreaksReconciliation() {
        // set_creative_mode_slot is dropped by vanilla for survival players: recorded, never reconciled.
        ByteBuf slotFrame = Unpooled.buffer();
        serverbound.codec().encode(slotFrame, new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36,
                net.minecraft.world.item.ItemStack.EMPTY));
        for (int i = 0; i < 300; i++) {
            channel.writeInbound(slotFrame.retainedDuplicate());
        }
        for (int i = 0; i < 50; i++) {
            channel.writeInbound(serverboundFrame(new ServerboundMovePlayerPacket.Pos(i, 64.0, 0.5, true, false)));
        }
        assertEquals(350, eventsOf(TimelineEvent.Inbound.class).size(), "everything the client sent is on the timeline");
        assertTrue(eventsOf(TimelineEvent.InboundDivergence.class).isEmpty(), "and nothing is blamed on a plugin: " + events);
    }

    @Test
    void behindAProxyTheClientsProtocolIsNeverClaimed() {
        List<TimelineEvent> sink = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1024, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener((s, e) -> sink.add(e));
        PlayerSession session = registry.open("proxied");
        WireObserver proxied = new WireObserver(session, new PacketTranslator(), new WireProtocols(() -> registries), Logger.getAnonymousLogger(),
                SharedConstants.getProtocolVersion(), 0, "the VELOCITY_MODERN proxy");
        assertEquals(WireObserver.InboundAction.CONTINUE, proxied.onWireInbound(handshake(SharedConstants.getProtocolVersion(), 2), 1));
        TimelineEvent.Observation observation = assertInstanceOf(TimelineEvent.Observation.class, sink.getFirst());
        assertEquals(Source.WIRE, observation.inbound(), "the proxy's 774 stream is still decoded exactly");
        assertEquals(-1, observation.clientProtocol(), "but the client behind the proxy may speak anything");
        assertTrue(observation.detail().contains("unknown"), observation.detail());
    }

    @Test
    void aReplacedFramingHandlerEndsTheEdgeExplicitly() {
        channel.pipeline().replace("splitter", "custom_splitter", new Placeholder());
        WireTaps.ensurePlacement(channel.pipeline());
        assertNull(channel.pipeline().get(WireTaps.INBOUND));
        TimelineEvent.Observation observation = (TimelineEvent.Observation) eventsOf(TimelineEvent.Observation.class).getLast();
        assertEquals(Source.GAME, observation.inbound());
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        assertEquals(1, eventsOf(TimelineEvent.Inbound.class).size(), "the game's view records instead of a silent hole");
    }

    @Test
    void handlersOfADisabledInstanceLeaveOnTheirNextPacket() {
        boolean[] active = {true};
        observer.activeWhile(() -> active[0]);
        active[0] = false; // WhyNotAC disabled while this connection was still being accepted
        channel.writeInbound(serverboundFrame(ServerboundClientTickEndPacket.INSTANCE));
        assertEquals(1, processedByGame.size(), "the packet still reaches the game");
        assertNull(channel.pipeline().get(WireTaps.INBOUND));
        assertNull(channel.pipeline().get(WireTaps.OUTBOUND));
        assertNull(channel.pipeline().get(dev.whynotac.paper.network.PacketInterceptor.HANDLER_NAME));
        assertEquals(1, eventsOf(TimelineEvent.Disconnected.class).size(), "the session ends cleanly");
    }

    @Test
    void theAuditRepairsAPluginThatSqueezedInOutsideTheTaps() {
        channel.pipeline().addAfter("splitter", "late-plugin", new Placeholder());
        assertTrue(WireTaps.ensurePlacement(channel.pipeline()));
        assertTrue(eventsOf(TimelineEvent.Observation.class).isEmpty(), "WhyNotAC's own move is not a lost tap: " + events);
        List<String> names = channel.pipeline().names();
        assertEquals(names.indexOf("splitter") + 1, names.indexOf(WireTaps.INBOUND), names.toString());
        assertFalse(WireTaps.ensurePlacement(channel.pipeline()), "already in place");
    }

    // --- game-side wiring -----------------------------------------------------------------------------

    /** Installs WhyNotAC's game-side handler (package-private in another package) the way the interceptor does. */
    private static final class GameSide {
        static void install(EmbeddedChannel channel, PlayerSession session, WireObserver observer) {
            channel.pipeline().addBefore("packet_handler", dev.whynotac.paper.network.PacketInterceptor.HANDLER_NAME,
                    dev.whynotac.paper.network.TestAccess.handler(session, observer, SELF));
        }
    }
}
