package dev.whynotac.paper.wire;

import dev.whynotac.math.vanilla.LpVec3;
import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.latency.TransactionPolicy;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent.DivergenceKind;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.SharedConstants;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.configuration.ConfigurationPacketTypes;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.handshake.HandshakePacketTypes;
import net.minecraft.network.protocol.login.LoginPacketTypes;

/**
 * One connection's view from the network edge, and its reconciliation with the game's view.
 *
 * <h2>Why the edge</h2>
 * Other plugins sit between the network and the game's packet handler (ViaVersion, PacketEvents,
 * ProtocolLib and everything built on them). They can rewrite, cancel or inject packets, and
 * PacketEvents can write pre-encoded bytes that never pass the game's send path. A knockback the
 * client received but WhyNotAC never saw would make an honest player's movement look impossible. So
 * the timeline is decoded from the bytes themselves, right next to (de)compression: inbound before any
 * other plugin's decoder, outbound after every other plugin's encoder. That is exactly what the
 * client sent and exactly what it received.
 *
 * <h2>Reconciliation</h2>
 * The packet objects next to the game's handler ({@code WhyNotChannelHandler}) are compared with the
 * edge stream, in order. Every difference becomes an explicit divergence event: a packet only one side
 * has. Outbound, the game's write and the edge are on the same call stack (the encoder and every
 * byte-level handler run synchronously inside the game's {@code write}), so whatever the game wrote and
 * the edge has not seen by the time the write returns was cancelled below WhyNotAC.
 *
 * <h2>When the edge cannot be decoded</h2>
 * The edge is decoded with the server's own 1.21.11 codecs, which only fit if the client speaks
 * protocol 774. A client on another version (translated by ViaVersion) gets the game's view instead,
 * declared in an {@code Observation} event so analysis knows which view it has.
 *
 * <p>Confined to the connection's event loop, except the {@code volatile} status fields.
 */
public final class WireObserver {

    /** Type of the marker recorded when the edge itself failed on a frame; it reconciles with any packet. */
    static final String EDGE_FAILURE = "whynotac:edge-failure";
    /**
     * How long a packet may be held back by another plugin before it counts as cancelled, once later
     * packets have passed it (see {@link Reconciler}).
     */
    static final long DEFAULT_GRACE_NANOS = 2_000_000_000L;
    private static final int MAX_REPORTED_FAILURES = 5;

    /** What the inbound tap should do after forwarding a frame. */
    enum InboundAction { CONTINUE, DETACH }

    /** Result of decoding an outbound frame, committed after the frame was forwarded. */
    record Outgoing(String type, ClientboundPacket packet, boolean wireOnly, boolean sandwich) {
        static final Outgoing STRUCTURAL = new Outgoing(null, null, false, false);
    }

    private final PlayerSession session;
    private final PacketTranslator translator;
    private final WireProtocols protocols;
    private final Logger logger;
    private final int serverProtocol;

    private WireProtocols.Table inboundTable;
    private WireProtocols.Table outboundTable;
    /** Reference: the edge (what the client sent); matched by the game. Missing = cancelled before the game. */
    private final Reconciler<ServerboundPacket> inbound;
    /** Reference: the game (what it sent); matched by the edge. Missing = cancelled below the game. */
    private final Reconciler<ClientboundPacket> outbound;
    private int failures;

    private volatile Source source;   // null until the handshake decides
    private volatile int clientProtocol = -1;
    private volatile int selfEntityId = -1;
    private volatile long inboundWireOnly;
    private volatile long inboundGameOnly;
    private volatile long outboundWireOnly;
    private volatile long outboundGameOnly;
    private volatile long edgeTransactions;
    private volatile long placementRepairs;

    public WireObserver(PlayerSession session, PacketTranslator translator, WireProtocols protocols, Logger logger) {
        this(session, translator, protocols, logger, SharedConstants.getProtocolVersion(), DEFAULT_GRACE_NANOS);
    }

    WireObserver(PlayerSession session, PacketTranslator translator, WireProtocols protocols, Logger logger, int serverProtocol,
            long graceNanos) {
        this.session = session;
        this.translator = translator;
        this.protocols = protocols;
        this.logger = logger;
        this.serverProtocol = serverProtocol;
        this.inboundTable = protocols.handshake;
        this.inbound = new Reconciler<>(WireObserver::sameInbound, (packet, nanoTime) -> {
            inboundWireOnly++;
            session.onInboundDivergence(DivergenceKind.WIRE_ONLY, packet, nanoTime);
        }, graceNanos);
        this.outbound = new Reconciler<>(WireObserver::sameOutbound, (packet, nanoTime) -> {
            outboundGameOnly++;
            session.onOutboundDivergence(DivergenceKind.GAME_ONLY, packet, nanoTime);
        }, graceNanos);
    }

    /** For a connection WhyNotAC attached to mid-session: the edge state is unknown, use the game's view. */
    public void useGameView(String reason, long nanoTime) {
        source = Source.GAME;
        session.onObservation(Source.GAME, Source.GAME, -1, reason, nanoTime);
    }

    /** Test hook: start directly in the play state with the edge view, as after a 774 login. */
    void startInPlay(long nanoTime) {
        source = Source.WIRE;
        clientProtocol = serverProtocol;
        inboundTable = protocols.playServerbound();
        outboundTable = protocols.playClientbound();
        session.onObservation(Source.WIRE, Source.WIRE, serverProtocol, "edge decoding (test)", nanoTime);
    }

    // --- edge side (taps) ------------------------------------------------------------------------

    /** One inbound frame (packet id + body, decompressed), before any other plugin's decoder. */
    InboundAction onWireInbound(ByteBuf frame, long nanoTime) {
        if (source == Source.GAME) {
            return InboundAction.DETACH;
        }
        WireProtocols.Table table = inboundTable;
        ByteBuf view = frame.duplicate();
        int id = VarInt.read(view);
        PacketType<?> type = table.type(id);
        String typeName = type == null ? "whynotac:unknown-id/" + table + "/" + id : type.id().toString();
        ServerboundPacket translated;
        if (type == null) {
            translated = new ServerboundPacket.Untranslatable(typeName);
        } else if (table.decodes(id)) {
            translated = decodeServerbound(table, frame, typeName);
        } else {
            translated = table.unmodeledServerbound(id);
        }

        if (HandshakePacketTypes.CLIENT_INTENTION.equals(type)) {
            return onHandshake(translated, nanoTime);
        }
        if (source == null) {
            // The first frame of a connection is always the handshake; anything else is not ours to decode.
            source = Source.GAME;
            session.onObservation(Source.GAME, Source.GAME, -1, "first frame is not a handshake: " + typeName, nanoTime);
            return InboundAction.DETACH;
        }
        if (LoginPacketTypes.SERVERBOUND_LOGIN_ACKNOWLEDGED.equals(type)
                || GamePacketTypes.SERVERBOUND_CONFIGURATION_ACKNOWLEDGED.equals(type)) {
            inboundTable = protocols.configurationServerbound;
        } else if (ConfigurationPacketTypes.SERVERBOUND_FINISH_CONFIGURATION.equals(type)) {
            inboundTable = protocols.playServerbound();
        }
        session.onInbound(translated, nanoTime);
        inbound.expect(typeName, translated, nanoTime);
        return InboundAction.CONTINUE;
    }

    private InboundAction onHandshake(ServerboundPacket translated, long nanoTime) {
        if (!(translated instanceof ServerboundPacket.Handshake handshake)) {
            source = Source.GAME;
            session.onObservation(Source.GAME, Source.GAME, -1, "handshake not decodable", nanoTime);
            return InboundAction.DETACH;
        }
        clientProtocol = handshake.protocolVersion();
        if (handshake.intent() == ServerboundPacket.Handshake.Intent.STATUS) {
            // A server-list ping never becomes a player; the game-side handler records and detaches.
            source = Source.GAME;
            return InboundAction.DETACH;
        }
        if (handshake.protocolVersion() != serverProtocol) {
            source = Source.GAME;
            session.onObservation(Source.GAME, Source.GAME, handshake.protocolVersion(),
                    "client speaks protocol " + handshake.protocolVersion() + " (translated by another plugin); edge decoding needs "
                            + serverProtocol, nanoTime);
            return InboundAction.DETACH;
        }
        source = Source.WIRE;
        inboundTable = protocols.loginServerbound;
        outboundTable = protocols.loginClientbound;
        session.onObservation(Source.WIRE, Source.WIRE, handshake.protocolVersion(), "edge decoding", nanoTime);
        session.onInbound(translated, nanoTime);
        inbound.expect(HandshakePacketTypes.CLIENT_INTENTION.id().toString(), translated, nanoTime);
        return InboundAction.CONTINUE;
    }

    /**
     * One outbound frame, after every other plugin's encoder. Decodes and reconciles it; the caller
     * forwards the frame and then calls {@link #commitOutbound}. Packets the game wrote earlier that
     * this frame skipped over are reported here (they never reached the client).
     */
    Outgoing beforeWireOutbound(ByteBuf frame, long nanoTime) {
        WireProtocols.Table table = outboundTable;
        if (source != Source.WIRE || table == null) {
            return Outgoing.STRUCTURAL;
        }
        ByteBuf view = frame.duplicate();
        int id = VarInt.read(view);
        PacketType<?> type = table.type(id);
        if (GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER.equals(type)) {
            return Outgoing.STRUCTURAL; // bundles are flattened, as in the game's view
        }
        String typeName = type == null ? "whynotac:unknown-id/" + table + "/" + id : type.id().toString();
        ClientboundPacket translated;
        if (type == null) {
            translated = new ClientboundPacket.Untranslatable(typeName);
        } else if (table.decodes(id)) {
            translated = decodeClientbound(table, frame, typeName);
        } else {
            translated = table.unmodeledClientbound(id);
        }
        if (LoginPacketTypes.CLIENTBOUND_LOGIN_FINISHED.equals(type) || GamePacketTypes.CLIENTBOUND_START_CONFIGURATION.equals(type)) {
            outboundTable = protocols.configurationClientbound;
        } else if (ConfigurationPacketTypes.CLIENTBOUND_FINISH_CONFIGURATION.equals(type)) {
            outboundTable = protocols.playClientbound();
        }
        if (translated instanceof ClientboundPacket.Login login) {
            selfEntityId = login.entityId();
        }
        return correlateOutbound(table, typeName, translated, nanoTime);
    }

    /**
     * The edge failed on an outbound frame (a bug, or a malformed frame from another plugin): a marker
     * takes its place, so the timeline never has a silent hole.
     */
    Outgoing outboundFailure(long nanoTime) {
        WireProtocols.Table table = outboundTable;
        if (source != Source.WIRE || table == null) {
            return Outgoing.STRUCTURAL;
        }
        return correlateOutbound(table, EDGE_FAILURE, new ClientboundPacket.Untranslatable(EDGE_FAILURE), nanoTime);
    }

    /** The edge failed on an inbound frame: a marker takes its place (see {@link #outboundFailure}). */
    void inboundFailure(long nanoTime) {
        if (source != Source.WIRE) {
            return;
        }
        ServerboundPacket marker = new ServerboundPacket.Untranslatable(EDGE_FAILURE);
        session.onInbound(marker, nanoTime);
        inbound.expect(EDGE_FAILURE, marker, nanoTime);
    }

    private Outgoing correlateOutbound(WireProtocols.Table table, String typeName, ClientboundPacket translated, long nanoTime) {
        boolean expected = outbound.match(typeName, translated, nanoTime);
        boolean sandwich = !expected && table.state == net.minecraft.network.ConnectionProtocol.PLAY
                && TransactionPolicy.requiresSandwich(translated, selfEntityId);
        return new Outgoing(typeName, translated, !expected, sandwich);
    }

    /** Records an outbound frame after it was forwarded towards the client. */
    void commitOutbound(Outgoing outgoing, long nanoTime) {
        if (outgoing.packet() == null) {
            return;
        }
        session.onOutbound(outgoing.packet(), nanoTime);
        if (outgoing.wireOnly()) {
            outboundWireOnly++;
            session.onOutboundDivergence(DivergenceKind.WIRE_ONLY, outgoing.packet(), nanoTime);
        }
    }

    /**
     * Writes a transaction ping directly at the edge, bracketing a physics-changing packet another
     * plugin wrote below the game's send path. Encoded with the client's own (774) codec.
     */
    void writeEdgeTransaction(ChannelHandlerContext ctx, long nanoTime) {
        OptionalInt id = session.allocateTransaction();
        if (id.isEmpty()) {
            return;
        }
        ByteBuf buf = ctx.alloc().buffer(8);
        try {
            outboundTable.encode(buf, new ClientboundPingPacket(id.getAsInt()));
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
        edgeTransactions++;
        session.onOutbound(new ClientboundPacket.Ping(id.getAsInt()), nanoTime);
        ctx.write(buf, ctx.newPromise());
    }

    // --- game side (WhyNotChannelHandler) ---------------------------------------------------------

    /** True once the handshake chose the edge view: the game-side handler then only reconciles. */
    public boolean edgeView() {
        return source == Source.WIRE;
    }

    /** A packet the game decoded and is about to process. */
    public void onGameInbound(String type, ServerboundPacket packet, long nanoTime) {
        if (!inbound.match(type, packet, nanoTime)) {
            inboundGameOnly++;
            session.onInboundDivergence(DivergenceKind.GAME_ONLY, packet, nanoTime);
        }
    }

    /** The game is about to write these packets (a bundle is several); the edge will match them as they pass. */
    public void beginGameWrite(List<String> types, List<ClientboundPacket> packets, long nanoTime) {
        for (int i = 0; i < packets.size(); i++) {
            outbound.expect(types.get(i), canonical(packets.get(i)), nanoTime);
        }
    }

    public void selfEntityId(int entityId) {
        this.selfEntityId = entityId;
    }

    void placementRepaired() {
        placementRepairs++;
    }

    /** Snapshot for diagnostics (any thread). */
    public Status status() {
        return new Status(source, clientProtocol, inboundWireOnly, inboundGameOnly, outboundWireOnly, outboundGameOnly,
                edgeTransactions, placementRepairs);
    }

    /** Diagnostics snapshot; {@code source} is null until the handshake. */
    public record Status(Source source, int clientProtocol, long inboundWireOnly, long inboundGameOnly,
            long outboundWireOnly, long outboundGameOnly, long edgeTransactions, long placementRepairs) {}

    // --- internals ---------------------------------------------------------------------------------

    private ServerboundPacket decodeServerbound(WireProtocols.Table table, ByteBuf frame, String typeName) {
        try {
            ByteBuf view = frame.duplicate();
            Packet<?> packet = table.decode(view);
            if (view.isReadable()) {
                return new ServerboundPacket.Untranslatable(typeName); // the game rejects trailing bytes too
            }
            return translator.inbound(packet);
        } catch (RuntimeException | LinkageError e) {
            report("inbound " + typeName, e);
            return new ServerboundPacket.Untranslatable(typeName);
        }
    }

    private ClientboundPacket decodeClientbound(WireProtocols.Table table, ByteBuf frame, String typeName) {
        try {
            ByteBuf view = frame.duplicate();
            Packet<?> packet = table.decode(view);
            if (view.isReadable()) {
                return new ClientboundPacket.Untranslatable(typeName);
            }
            return translator.outbound(packet);
        } catch (RuntimeException | LinkageError e) {
            report("outbound " + typeName, e);
            return new ClientboundPacket.Untranslatable(typeName);
        }
    }

    /** The game's packet in the form the client receives it (velocity is quantized on the wire). */
    static ClientboundPacket canonical(ClientboundPacket packet) {
        if (packet instanceof ClientboundPacket.SetEntityMotion motion) {
            return new ClientboundPacket.SetEntityMotion(motion.entityId(), LpVec3.quantize(motion.motion()));
        }
        return packet;
    }

    /** Same packet in both views: same type and content, or one side could not be translated. */
    private static boolean sameInbound(String typeA, ServerboundPacket a, String typeB, ServerboundPacket b) {
        if (typeA.equals(EDGE_FAILURE) || typeB.equals(EDGE_FAILURE)) {
            return true;
        }
        return typeA.equals(typeB) && (a.equals(b) || a instanceof ServerboundPacket.Untranslatable || b instanceof ServerboundPacket.Untranslatable);
    }

    private static boolean sameOutbound(String typeA, ClientboundPacket a, String typeB, ClientboundPacket b) {
        if (typeA.equals(EDGE_FAILURE) || typeB.equals(EDGE_FAILURE)) {
            return true;
        }
        return typeA.equals(typeB) && (a.equals(b) || a instanceof ClientboundPacket.Untranslatable || b instanceof ClientboundPacket.Untranslatable);
    }

    private void report(String what, Throwable error) {
        failures++;
        if (failures <= MAX_REPORTED_FAILURES || Integer.bitCount(failures) == 1) {
            logger.log(Level.WARNING, "WhyNotAC could not decode " + what + " at the network edge of " + session + " (failure #"
                    + failures + "); recorded as untranslatable, the packet was forwarded unchanged", error);
        }
    }
}
