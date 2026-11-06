package dev.whynotac.paper.wire;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import io.netty.buffer.ByteBuf;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;

/**
 * The game's own 1.21.11 codecs, per protocol state and direction, for decoding the bytes seen at the
 * network edge. Using the vanilla {@code StreamCodec}s (instead of a hand-written parser) means the
 * edge view reads every byte exactly as the client and the server do.
 *
 * <p>Immutable and shared by all connections. The play tables need the server's registries (dimension
 * types, effects, attributes are encoded as registry ids) and are bound on first use.
 */
public final class WireProtocols {

    /** One direction of one protocol state: the vanilla codec plus an id table. */
    static final class Table {
        final ConnectionProtocol state;
        final PacketFlow flow;
        private final StreamCodec<ByteBuf, Packet<?>> codec;
        private final PacketType<?>[] types;
        private final boolean[] decode;
        private final Object[] unmodeled;
        private final String[] names;

        @SuppressWarnings("unchecked")
        Table(ProtocolInfo<?> info, ProtocolInfo.DetailsProvider template, Set<String> modelledPaths) {
            this.state = info.id();
            this.flow = info.flow();
            // Safe: a protocol's codec reads and writes exactly the packets of that protocol.
            this.codec = (StreamCodec<ByteBuf, Packet<?>>) (StreamCodec<?, ?>) info.codec();
            int[] size = {0};
            template.details().listPackets((type, id) -> size[0] = Math.max(size[0], id + 1));
            this.types = new PacketType<?>[size[0]];
            this.decode = new boolean[size[0]];
            this.unmodeled = new Object[size[0]];
            this.names = new String[size[0]];
            template.details().listPackets((type, id) -> {
                types[id] = type;
                decode[id] = modelledPaths.contains(type.id().getPath());
                String name = type.id().toString();
                names[id] = name;
                unmodeled[id] = flow == PacketFlow.SERVERBOUND ? new ServerboundPacket.Unmodeled(name) : new ClientboundPacket.Unmodeled(name);
            });
        }

        /** The packet type with this id, or null if the id does not exist in this state. */
        PacketType<?> type(int id) {
            return id >= 0 && id < types.length ? types[id] : null;
        }

        /** The type id of packet id {@code id} (built once), or null if the id does not exist in this state. */
        String name(int id) {
            return id >= 0 && id < names.length ? names[id] : null;
        }

        /** True if WhyNotAC models this packet and therefore decodes its body; others are recorded by type only. */
        boolean decodes(int id) {
            return decode[id];
        }

        ServerboundPacket.Unmodeled unmodeledServerbound(int id) {
            return (ServerboundPacket.Unmodeled) unmodeled[id];
        }

        ClientboundPacket.Unmodeled unmodeledClientbound(int id) {
            return (ClientboundPacket.Unmodeled) unmodeled[id];
        }

        /** Decodes one complete frame (packet id + body), consuming it from {@code frame}. */
        Packet<?> decode(ByteBuf frame) {
            return codec.decode(frame);
        }

        /** Encodes one packet of this protocol (packet id + body) into {@code out}. */
        void encode(ByteBuf out, Packet<?> packet) {
            codec.encode(out, packet);
        }

        @Override
        public String toString() {
            return state.id() + "/" + flow.id();
        }
    }

    private final Supplier<RegistryAccess> registries;
    final Table handshake;
    final Table loginServerbound;
    final Table loginClientbound;
    final Table configurationServerbound;
    final Table configurationClientbound;
    private volatile Table playServerbound;
    private volatile Table playClientbound;

    /** {@code registries} is called once, on the first play-state packet (the server's frozen registries). */
    public WireProtocols(Supplier<RegistryAccess> registries) {
        this.registries = registries;
        Set<String> serverbound = PacketTranslator.SERVERBOUND_MODELLED;
        Set<String> clientbound = PacketTranslator.CLIENTBOUND_MODELLED;
        handshake = new Table(HandshakeProtocols.SERVERBOUND, HandshakeProtocols.SERVERBOUND_TEMPLATE, serverbound);
        loginServerbound = new Table(LoginProtocols.SERVERBOUND, LoginProtocols.SERVERBOUND_TEMPLATE, serverbound);
        loginClientbound = new Table(LoginProtocols.CLIENTBOUND, LoginProtocols.CLIENTBOUND_TEMPLATE, clientbound);
        configurationServerbound = new Table(ConfigurationProtocols.SERVERBOUND, ConfigurationProtocols.SERVERBOUND_TEMPLATE, serverbound);
        configurationClientbound = new Table(ConfigurationProtocols.CLIENTBOUND, ConfigurationProtocols.CLIENTBOUND_TEMPLATE, clientbound);
    }

    Table playServerbound() {
        Table table = playServerbound;
        if (table == null) {
            bindPlay();
            table = playServerbound;
        }
        return table;
    }

    Table playClientbound() {
        Table table = playClientbound;
        if (table == null) {
            bindPlay();
            table = playClientbound;
        }
        return table;
    }

    private synchronized void bindPlay() {
        if (playServerbound != null) {
            return;
        }
        RegistryAccess access = registries.get();
        // Creative-mode slot validation is the only context-dependent codec; the edge view never rejects.
        GameProtocols.Context lenient = () -> true;
        playClientbound = new Table(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access)),
                GameProtocols.CLIENTBOUND_TEMPLATE, PacketTranslator.CLIENTBOUND_MODELLED);
        playServerbound = new Table(GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(access), lenient),
                GameProtocols.SERVERBOUND_TEMPLATE, PacketTranslator.SERVERBOUND_MODELLED);
    }
}
