package dev.whynotac.paper.wire;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.latency.TransactionPolicy;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent.DivergenceKind;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayDeque;
import java.util.List;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.SharedConstants;
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

    /** Type of the marker recorded when the edge itself failed on a frame whose type it cannot even read. */
    static final String EDGE_FAILURE = "whynotac:edge-failure";
    /**
     * Inbound types recorded but never reconciled: the game itself may drop them (vanilla skips
     * {@code set_creative_mode_slot} with {@code SkipPacketDecoderException} for players without infinite
     * materials), so a missing game-side packet would wrongly be blamed on a plugin.
     */
    static final java.util.Set<String> NOT_RECONCILED_INBOUND = java.util.Set.of("minecraft:set_creative_mode_slot");
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
    /** After the edge view ended: inbound packets the edge recorded that had not reached the game yet. */
    private ArrayDeque<Reconciler.InFlight<ServerboundPacket>> inFlight = new ArrayDeque<>();
    /** Reference: the game (what it sent); matched by the edge. Missing = cancelled below the game. */
    private final Reconciler<ClientboundPacket> outbound;
    private int failures;
    /** WhyNotAC itself is moving or removing the taps: their handlerRemoved is not a loss. */
    private boolean moving;
    /** Another plugin removed WhyNotAC's game-side handler: nothing to reconcile with any more. */
    private boolean gameViewLost;

    private volatile Source source;   // null until the handshake decides
    /** Non-null if the connection comes through a translating hop (a proxy, Geyser): its handshake is not the client's. */
    private final String upstreamTranslation;
    /** False once the WhyNotAC instance that created this observer is disabled: its handlers then remove themselves. */
    private volatile java.util.function.BooleanSupplier active = () -> true;
    private volatile int clientProtocol = -1;
    private volatile int selfEntityId = -1;
    private volatile long inboundWireOnly;
    private volatile long inboundGameOnly;
    private volatile long outboundWireOnly;
    private volatile long outboundGameOnly;
    private volatile long edgeTransactions;
    private volatile long placementRepairs;

    public WireObserver(PlayerSession session, PacketTranslator translator, WireProtocols protocols, Logger logger) {
        this(session, translator, protocols, logger, null);
    }

    /**
     * {@code upstreamTranslation}: why the bytes this server receives are not the client's own (the
     * connection comes through Velocity/BungeeCord, which may run ViaVersion, or through Geyser), or
     * null for a direct connection. The edge still records the stream exactly; it just never claims to
     * know the client's protocol.
     */
    public WireObserver(PlayerSession session, PacketTranslator translator, WireProtocols protocols, Logger logger, String upstreamTranslation) {
        this(session, translator, protocols, logger, SharedConstants.getProtocolVersion(), DEFAULT_GRACE_NANOS, upstreamTranslation);
    }

    WireObserver(PlayerSession session, PacketTranslator translator, WireProtocols protocols, Logger logger, int serverProtocol,
            long graceNanos) {
        this(session, translator, protocols, logger, serverProtocol, graceNanos, null);
    }

    WireObserver(PlayerSession session, PacketTranslator translator, WireProtocols protocols, Logger logger, int serverProtocol,
            long graceNanos, String upstreamTranslation) {
        this.upstreamTranslation = upstreamTranslation;
        this.session = session;
        this.translator = translator;
        this.protocols = protocols;
        this.logger = logger;
        this.serverProtocol = serverProtocol;
        this.inboundTable = protocols.handshake;
        this.inbound = new Reconciler<>(WireObserver::inboundKey, (packet, nanoTime) -> {
            inboundWireOnly++;
            session.onInboundDivergence(DivergenceKind.WIRE_ONLY, packet, nanoTime);
        }, graceNanos);
        this.outbound = new Reconciler<>(WireObserver::outboundKey, (packet, nanoTime) -> {
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
        if (!frame.isReadable()) {
            return InboundAction.CONTINUE; // an empty frame: the game's decoder ignores it, so does the edge
        }
        WireProtocols.Table table = inboundTable;
        int id = packetId(frame);
        PacketType<?> type = table.type(id);
        String typeName = typeName(table, type, id);
        ServerboundPacket translated;
        if (type == null) {
            translated = new ServerboundPacket.Untranslatable(typeName);
        } else if (table.decodes(id)) {
            translated = decodeServerbound(table, frame, typeName);
        } else {
            translated = table.unmodeledServerbound(id);
        }

        if (type == HandshakePacketTypes.CLIENT_INTENTION) {
            return onHandshake(translated, nanoTime);
        }
        if (source == null) {
            // The first frame of a connection is always the handshake; anything else is not ours to decode.
            source = Source.GAME;
            session.onObservation(Source.GAME, Source.GAME, -1, "first frame is not a handshake: " + typeName, nanoTime);
            return InboundAction.DETACH;
        }
        if (type == LoginPacketTypes.SERVERBOUND_LOGIN_ACKNOWLEDGED
                || type == GamePacketTypes.SERVERBOUND_CONFIGURATION_ACKNOWLEDGED) {
            inboundTable = protocols.configurationServerbound;
        } else if (type == ConfigurationPacketTypes.SERVERBOUND_FINISH_CONFIGURATION) {
            inboundTable = protocols.playServerbound();
        }
        session.onInbound(translated, nanoTime);
        if (!gameViewLost && !NOT_RECONCILED_INBOUND.contains(typeName)) {
            inbound.expect(typeName, translated, nanoTime);
        }
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
        if (upstreamTranslation != null) {
            clientProtocol = -1; // the handshake is the proxy's or Geyser's, not the client's
            session.onObservation(Source.WIRE, Source.WIRE, -1, "edge decoding of the " + handshake.protocolVersion()
                    + " stream from " + upstreamTranslation + "; the client's own protocol is unknown", nanoTime);
        } else {
            session.onObservation(Source.WIRE, Source.WIRE, handshake.protocolVersion(), "edge decoding", nanoTime);
        }
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
        if (!frame.isReadable()) {
            return Outgoing.STRUCTURAL; // an empty frame (a plugin encoder that wrote nothing): the client ignores it
        }
        int id = packetId(frame);
        PacketType<?> type = table.type(id);
        if (type == GamePacketTypes.CLIENTBOUND_BUNDLE_DELIMITER) {
            return Outgoing.STRUCTURAL; // bundles are flattened, as in the game's view
        }
        String typeName = typeName(table, type, id);
        ClientboundPacket translated;
        if (type == null) {
            translated = new ClientboundPacket.Untranslatable(typeName);
        } else if (type == GamePacketTypes.CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT) {
            translated = rawChunk(frame, typeName);
        } else if (table.decodes(id)) {
            translated = decodeClientbound(table, frame, typeName);
        } else {
            translated = table.unmodeledClientbound(id);
        }
        if (type == LoginPacketTypes.CLIENTBOUND_LOGIN_FINISHED || type == GamePacketTypes.CLIENTBOUND_START_CONFIGURATION) {
            outboundTable = protocols.configurationClientbound;
        } else if (type == ConfigurationPacketTypes.CLIENTBOUND_FINISH_CONFIGURATION) {
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
    Outgoing outboundFailure(ByteBuf frame, long nanoTime) {
        WireProtocols.Table table = outboundTable;
        if (source != Source.WIRE || table == null || !frame.isReadable()) {
            return Outgoing.STRUCTURAL;
        }
        String typeName = failedTypeName(table, frame);
        return correlateOutbound(table, typeName, new ClientboundPacket.Untranslatable(typeName), nanoTime);
    }

    /** The edge failed on an inbound frame: a marker takes its place (see {@link #outboundFailure}). */
    void inboundFailure(ByteBuf frame, long nanoTime) {
        if (source != Source.WIRE || !frame.isReadable()) {
            return;
        }
        String typeName = failedTypeName(inboundTable, frame);
        ServerboundPacket marker = new ServerboundPacket.Untranslatable(typeName);
        session.onInbound(marker, nanoTime);
        inbound.expect(typeName, marker, nanoTime);
    }

    private Outgoing correlateOutbound(WireProtocols.Table table, String typeName, ClientboundPacket translated, long nanoTime) {
        boolean physics = table.state == net.minecraft.network.ConnectionProtocol.PLAY && TransactionPolicy.requiresSandwich(translated, selfEntityId);
        if (gameViewLost) {
            return new Outgoing(typeName, translated, false, physics); // nothing to reconcile; the edge brackets it
        }
        boolean expected = outbound.match(typeName, translated, nanoTime);
        if (expected && !physics && translated instanceof ClientboundPacket.Unmodeled unmodeled) {
            Outgoing shared = unmodeledOutgoing.get(unmodeled); // most frames: no allocation
            if (shared == null) {
                shared = new Outgoing(typeName, unmodeled, false, false);
                unmodeledOutgoing.put(unmodeled, shared);
            }
            return shared;
        }
        return new Outgoing(typeName, translated, !expected, !expected && physics);
    }

    /** One immutable Outgoing per unmodelled packet type (those packets are themselves shared per type). */
    private final java.util.IdentityHashMap<ClientboundPacket.Unmodeled, Outgoing> unmodeledOutgoing = new java.util.IdentityHashMap<>();

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

    /** Ties this connection's handlers to the lifetime of the WhyNotAC instance that installed them. */
    public void activeWhile(java.util.function.BooleanSupplier active) {
        this.active = active;
    }

    /** False after the installing WhyNotAC instance was disabled (reload): the handlers must leave. */
    public boolean active() {
        return active.getAsBoolean();
    }

    /** WhyNotAC moves (placement repair) or removes (uninstall) the taps itself. */
    void moving(boolean moving) {
        this.moving = moving;
    }

    /** Uninstall: the taps and the handler are about to be removed on purpose. */
    public void detaching() {
        moving = true;
    }

    /**
     * A tap was removed, and not by WhyNotAC: from now on the game's view records the timeline, so it
     * never silently stops. Declared with an {@code Observation}; evidence spanning it is invalid.
     */
    void tapRemoved(String name, long nanoTime) {
        if (moving || source != Source.WIRE) {
            return;
        }
        source = Source.GAME;
        session.onObservation(Source.GAME, Source.GAME, clientProtocol,
                "edge tap " + name + " removed by another plugin; the game's view records from here", nanoTime);
        keepInFlight(nanoTime);
    }

    /** The edge cannot be kept (e.g. its anchors are gone): switch to the game's view, declared. */
    void edgeLost(String reason, long nanoTime) {
        if (source != Source.WIRE) {
            return;
        }
        source = Source.GAME;
        session.onObservation(Source.GAME, Source.GAME, clientProtocol, reason + "; the game's view records from here", nanoTime);
        keepInFlight(nanoTime);
    }

    /**
     * The edge view ends: inbound packets the edge recorded that the game has not seen yet are in flight
     * between the two and will reach the game in order (unless a plugin cancels them). They are kept so
     * the game view does not record them twice; anything the stream had already moved past was cancelled
     * between the two views and is reported now, so it can never absorb a later, identical packet.
     */
    private void keepInFlight(long nanoTime) {
        inFlight = new ArrayDeque<>(inbound.drainInFlight(nanoTime));
    }

    /** The connection closed (seen by a tap; the game-side handler may be gone). Idempotent. */
    void channelInactive(long nanoTime) {
        session.disconnect(nanoTime);
    }

    /**
     * Another plugin removed WhyNotAC's game-side handler while the connection is open. The edge keeps
     * recording; there is no game view to reconcile with any more, and the edge brackets every
     * physics-changing packet itself (the game-side sandwich is gone).
     */
    public void gameViewLost(long nanoTime) {
        if (gameViewLost) {
            return;
        }
        gameViewLost = true;
        Source current = source == null ? Source.GAME : source;
        session.onObservation(current, current, clientProtocol,
                "game-side handler removed by another plugin: no reconciliation; the edge brackets physics packets", nanoTime);
    }

    /**
     * After the edge was lost: true if this packet the game decoded was already recorded at the edge
     * (it was between the two when the tap went away), so the game view must not record it again.
     */
    public boolean recordedByEdge(String type, ServerboundPacket packet, long nanoTime) {
        if (inFlight.isEmpty()) {
            return false;
        }
        // In flight packets reach the game in order: the first one that is this packet was recorded by
        // the edge; the ones before it were cancelled in between. If none is, this packet is new and
        // every packet still in flight was cancelled (a later packet has passed them all).
        Object key = inboundKey(type, packet);
        int position = 0;
        for (Reconciler.InFlight<ServerboundPacket> candidate : inFlight) {
            if (candidate.type().equals(type) && (key == null || candidate.key() == null || key.equals(candidate.key()))) {
                for (int i = 0; i < position; i++) {
                    reportCancelledInFlight(inFlight.removeFirst().packet(), nanoTime);
                }
                inFlight.removeFirst();
                return true;
            }
            position++;
        }
        while (!inFlight.isEmpty()) {
            reportCancelledInFlight(inFlight.removeFirst().packet(), nanoTime);
        }
        return false;
    }

    private void reportCancelledInFlight(ServerboundPacket packet, long nanoTime) {
        inboundWireOnly++;
        session.onInboundDivergence(DivergenceKind.WIRE_ONLY, packet, nanoTime);
    }

    // --- game side (WhyNotChannelHandler) ---------------------------------------------------------

    /** True once the handshake chose the edge view: the game-side handler then only reconciles. */
    public boolean edgeView() {
        return source == Source.WIRE;
    }

    /** A packet the game decoded and is about to process. */
    public void onGameInbound(String type, ServerboundPacket packet, long nanoTime) {
        if (NOT_RECONCILED_INBOUND.contains(type)) {
            return;
        }
        if (!inbound.match(type, packet, nanoTime)) {
            inboundGameOnly++;
            session.onInboundDivergence(DivergenceKind.GAME_ONLY, packet, nanoTime);
        }
    }

    /** The game is about to write these packets (a bundle is several); the edge will match them as they pass. */
    public void beginGameWrite(List<String> types, List<ClientboundPacket> packets, long nanoTime) {
        for (int i = 0; i < packets.size(); i++) {
            outbound.expect(types.get(i), packets.get(i), nanoTime); // reported as the game sent it
        }
    }

    /** {@link #beginGameWrite(List, List, long)} for one packet (the common case: no bundle). */
    public void beginGameWrite(String type, ClientboundPacket packet, long nanoTime) {
        outbound.expect(type, packet, nanoTime);
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

    /** The frame's packet id, or -1 if it is not a well-formed VarInt (never throws, never moves the reader index). */
    static int packetId(ByteBuf frame) {
        int value = 0;
        int index = frame.readerIndex();
        for (int i = 0; i < 5; i++) {
            if (index + i >= frame.writerIndex()) {
                return -1;
            }
            byte b = frame.getByte(index + i);
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return -1;
    }

    private static String typeName(WireProtocols.Table table, PacketType<?> type, int id) {
        if (type != null) {
            return table.name(id);
        }
        return id < 0 ? "whynotac:malformed-frame/" + table : "whynotac:unknown-id/" + table + "/" + id;
    }

    private static String failedTypeName(WireProtocols.Table table, ByteBuf frame) {
        if (table == null) {
            return EDGE_FAILURE;
        }
        int id = packetId(frame);
        PacketType<?> type = table.type(id);
        return type != null ? table.name(id) : EDGE_FAILURE;
    }

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

    /**
     * A chunk is kept as the bytes the client received: no decoding on the network thread (the world
     * replica decodes it on an analysis thread), one copy of the frame.
     */
    private ClientboundPacket rawChunk(ByteBuf frame, String typeName) {
        try {
            ByteBuf view = frame.duplicate();
            skipVarInt(view);
            return PacketTranslator.levelChunk(view);
        } catch (RuntimeException e) {
            report("outbound " + typeName, e);
            return new ClientboundPacket.Untranslatable(typeName);
        }
    }

    private static void skipVarInt(ByteBuf buf) {
        for (int i = 0; i < 5; i++) {
            if ((buf.readByte() & 0x80) == 0) {
                return;
            }
        }
        throw new IllegalArgumentException("VarInt too long");
    }

    private ClientboundPacket decodeClientbound(WireProtocols.Table table, ByteBuf frame, String typeName) {
        try {
            ByteBuf view = frame.duplicate();
            Packet<?> packet = table.decode(view);
            if (view.isReadable()) {
                return new ClientboundPacket.Untranslatable(typeName);
            }
            return translator.outbound(packet, true);
        } catch (RuntimeException | LinkageError e) {
            report("outbound " + typeName, e);
            return new ClientboundPacket.Untranslatable(typeName);
        }
    }

    /**
     * A packet in the form both views can compare: as the client receives it (the game's view also
     * knows the server-side velocity), and without bulk bytes only the edge has.
     */
    static ClientboundPacket canonical(ClientboundPacket packet) {
        return switch (packet) {
            case ClientboundPacket.SetEntityMotion motion -> motion.asReceived();
            // The game's view carries no chunk bytes (the edge has them): compare coordinates only.
            case ClientboundPacket.LevelChunk chunk -> chunk.withoutBody();
            default -> packet;
        };
    }

    /**
     * Content keys for reconciliation: the same packet in both views has the same type and an equal key.
     * A packet one side could not translate has no key and matches by type alone (a marker of unknown
     * type matches nothing, so it can never shift other pairs).
     */
    static Object inboundKey(String type, ServerboundPacket packet) {
        return packet instanceof ServerboundPacket.Untranslatable ? null : packet;
    }

    static Object outboundKey(String type, ClientboundPacket packet) {
        return packet instanceof ClientboundPacket.Untranslatable ? null : canonical(packet);
    }

    private void report(String what, Throwable error) {
        failures++;
        if (failures <= MAX_REPORTED_FAILURES || Integer.bitCount(failures) == 1) {
            logger.log(Level.WARNING, "WhyNotAC could not decode " + what + " at the network edge of " + session + " (failure #"
                    + failures + "); recorded as untranslatable, the packet was forwarded unchanged", error);
        }
    }
}
