package dev.whynotac.recording.format;

import static dev.whynotac.recording.format.RecordingFormat.*;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Exact binary codec for timeline events. Within one block, sequence numbers, timestamps and client
 * ticks are delta-encoded and strings are interned; {@link Encoder#reset}/{@link Decoder#reset}
 * restart that state at every block boundary.
 *
 * <p>Exactness rules: doubles and floats are stored as raw IEEE-754 bits; every record component is
 * stored, so {@code decode(encode(e)).equals(e)} holds for every event, including non-canonical
 * values (enforced by property tests over all record types). The {@code switch}es over the sealed
 * packet hierarchies have no {@code default}: a new packet type does not compile until it is
 * recordable.
 */
public final class EventCodec {

    private EventCodec() {}

    private static final long CANONICAL_NAN_D = Double.doubleToRawLongBits(Double.NaN);
    private static final int CANONICAL_NAN_F = Float.floatToRawIntBits(Float.NaN);

    private static final int MOVE_HAS_POS = 1;
    private static final int MOVE_HAS_ROT = 2;
    private static final int MOVE_ON_GROUND = 4;
    private static final int MOVE_H_COLLISION = 8;
    private static final int MOVE_EXPLICIT_ABSENT = 16;

    // =============================================================================================
    public static final class Encoder {

        private long previousSequence;
        private long previousNanoTime;
        private long previousClientTick;
        private final Map<String, Integer> strings = new HashMap<>();
        private final ByteSink body = new ByteSink(512);

        /** Restarts delta and string state for a new block. */
        public void reset(long baseSequence, long baseNanoTime, long baseClientTick) {
            previousSequence = baseSequence - 1;
            previousNanoTime = baseNanoTime;
            previousClientTick = baseClientTick;
            strings.clear();
        }

        /** Appends one framed record ({@code tag, length, body}) to {@code out}. */
        public void encode(TimelineEvent event, ByteSink out) {
            body.reset();
            long deltaSequence = event.sequence() - previousSequence;
            if (deltaSequence < 1) {
                throw new IllegalArgumentException("sequence " + event.sequence() + " does not follow " + previousSequence);
            }
            body.writeVarLong(deltaSequence);
            body.writeZigZag(event.nanoTime() - previousNanoTime);
            previousSequence = event.sequence();
            previousNanoTime = event.nanoTime();
            int tag = switch (event) {
                case TimelineEvent.Inbound inbound -> {
                    body.writeZigZag(inbound.clientTick() - previousClientTick);
                    previousClientTick = inbound.clientTick();
                    writeServerbound(inbound.packet());
                    yield TAG_INBOUND;
                }
                case TimelineEvent.Outbound outbound -> {
                    writeClientbound(outbound.packet());
                    yield TAG_OUTBOUND;
                }
                case TimelineEvent.Acknowledged ack -> {
                    body.writeInt(ack.transactionId());
                    body.writeZigZag(ack.nanoTime() - ack.sentNanoTime());
                    body.writeZigZag(ack.skipped());
                    yield TAG_ACKNOWLEDGED;
                }
                case TimelineEvent.Bound bound -> {
                    writeUuid(bound.playerId());
                    string(bound.playerName());
                    body.writeZigZag(bound.entityId());
                    yield TAG_BOUND;
                }
                case TimelineEvent.Gap gap -> {
                    body.writeZigZag(gap.droppedEvents());
                    yield TAG_GAP;
                }
                case TimelineEvent.Anomaly anomaly -> {
                    body.writeVarInt(anomaly.kind().ordinal());
                    string(anomaly.detail());
                    yield TAG_ANOMALY;
                }
                case TimelineEvent.Disconnected ignored -> TAG_DISCONNECTED;
                case TimelineEvent.Observation observation -> {
                    body.writeVarInt(observation.inbound().ordinal());
                    body.writeVarInt(observation.outbound().ordinal());
                    body.writeZigZag(observation.clientProtocol());
                    string(observation.detail());
                    yield TAG_OBSERVATION;
                }
                case TimelineEvent.InboundDivergence divergence -> {
                    body.writeVarInt(divergence.kind().ordinal());
                    writeServerbound(divergence.packet());
                    yield TAG_INBOUND_DIVERGENCE;
                }
                case TimelineEvent.OutboundDivergence divergence -> {
                    body.writeVarInt(divergence.kind().ordinal());
                    writeClientbound(divergence.packet());
                    yield TAG_OUTBOUND_DIVERGENCE;
                }
                case TimelineEvent.ClientFact fact -> {
                    body.writeVarInt(fact.fact().ordinal());
                    string(fact.value());
                    body.writeVarInt(fact.trust().ordinal());
                    string(fact.source());
                    yield TAG_CLIENT_FACT;
                }
            };
            out.writeVarInt(tag);
            out.writeVarInt(body.size());
            out.writeBytes(body.array(), 0, body.size());
        }

        /** Appends the end-of-recording metadata record. */
        public static void encodeEnd(long totalEvents, long lostEvents, ByteSink out) {
            ByteSink end = new ByteSink(24);
            end.writeVarLong(totalEvents);
            end.writeVarLong(lostEvents);
            out.writeVarInt(TAG_END);
            out.writeVarInt(end.size());
            out.writeBytes(end.toByteArray());
        }

        private void string(String value) {
            Integer index = strings.get(value);
            if (index != null) {
                body.writeVarInt(index + 1);
            } else {
                int defined = strings.size();
                body.writeVarInt(0);
                body.writeVarInt(defined);
                body.writeUtf8(value);
                strings.put(value, defined);
            }
        }

        private void writeUuid(UUID id) {
            body.writeLong(id.getMostSignificantBits());
            body.writeLong(id.getLeastSignificantBits());
        }

        private void vec(Vec3d v) {
            body.writeDouble(v.x());
            body.writeDouble(v.y());
            body.writeDouble(v.z());
        }

        private void position(BlockPosition p) {
            body.writeZigZag(p.x());
            body.writeZigZag(p.y());
            body.writeZigZag(p.z());
        }

        private void writeServerbound(ServerboundPacket packet) {
            switch (packet) {
                case ServerboundPacket.MovePlayer m -> {
                    body.writeVarInt(SB_MOVE_PLAYER);
                    boolean canonical = (m.hasPosition() || isCanonicalNaN(m.x()) && isCanonicalNaN(m.y()) && isCanonicalNaN(m.z()))
                            && (m.hasRotation() || isCanonicalNaN(m.yaw()) && isCanonicalNaN(m.pitch()));
                    int flags = (m.hasPosition() ? MOVE_HAS_POS : 0) | (m.hasRotation() ? MOVE_HAS_ROT : 0)
                            | (m.onGround() ? MOVE_ON_GROUND : 0) | (m.horizontalCollision() ? MOVE_H_COLLISION : 0)
                            | (canonical ? 0 : MOVE_EXPLICIT_ABSENT);
                    body.writeByte(flags);
                    if (m.hasPosition() || !canonical) {
                        body.writeDouble(m.x());
                        body.writeDouble(m.y());
                        body.writeDouble(m.z());
                    }
                    if (m.hasRotation() || !canonical) {
                        body.writeFloat(m.yaw());
                        body.writeFloat(m.pitch());
                    }
                }
                case ServerboundPacket.PlayerInput i -> {
                    body.writeVarInt(SB_PLAYER_INPUT);
                    body.writeByte((i.forward() ? 1 : 0) | (i.backward() ? 2 : 0) | (i.left() ? 4 : 0) | (i.right() ? 8 : 0)
                            | (i.jump() ? 16 : 0) | (i.shift() ? 32 : 0) | (i.sprint() ? 64 : 0));
                }
                case ServerboundPacket.PlayerCommand c -> {
                    body.writeVarInt(SB_PLAYER_COMMAND);
                    body.writeZigZag(c.entityId());
                    body.writeVarInt(c.action().ordinal());
                    body.writeZigZag(c.data());
                }
                case ServerboundPacket.PlayerAction a -> {
                    body.writeVarInt(SB_PLAYER_ACTION);
                    body.writeVarInt(a.action().ordinal());
                    position(a.position());
                    body.writeVarInt(a.face().ordinal());
                    body.writeZigZag(a.sequence());
                }
                case ServerboundPacket.InteractEntity e -> {
                    body.writeVarInt(SB_INTERACT_ENTITY);
                    body.writeZigZag(e.entityId());
                    body.writeVarInt(e.kind().ordinal());
                    body.writeByte((e.hand() != null ? 1 : 0) | (e.location() != null ? 2 : 0) | (e.secondaryAction() ? 4 : 0));
                    if (e.hand() != null) {
                        body.writeVarInt(e.hand().ordinal());
                    }
                    if (e.location() != null) {
                        vec(e.location());
                    }
                }
                case ServerboundPacket.Swing s -> {
                    body.writeVarInt(SB_SWING);
                    body.writeVarInt(s.hand().ordinal());
                }
                case ServerboundPacket.UseItem u -> {
                    body.writeVarInt(SB_USE_ITEM);
                    body.writeVarInt(u.hand().ordinal());
                    body.writeZigZag(u.sequence());
                    body.writeFloat(u.yaw());
                    body.writeFloat(u.pitch());
                }
                case ServerboundPacket.UseItemOn u -> {
                    body.writeVarInt(SB_USE_ITEM_ON);
                    body.writeVarInt(u.hand().ordinal());
                    position(u.position());
                    body.writeVarInt(u.face().ordinal());
                    vec(u.location());
                    body.writeByte((u.inside() ? 1 : 0) | (u.worldBorderHit() ? 2 : 0));
                    body.writeZigZag(u.sequence());
                }
                case ServerboundPacket.SetCarriedItem s -> {
                    body.writeVarInt(SB_SET_CARRIED_ITEM);
                    body.writeZigZag(s.slot());
                }
                case ServerboundPacket.ClientTickEnd ignored -> body.writeVarInt(SB_CLIENT_TICK_END);
                case ServerboundPacket.Pong p -> {
                    body.writeVarInt(SB_PONG);
                    body.writeInt(p.id());
                }
                case ServerboundPacket.KeepAlive k -> {
                    body.writeVarInt(SB_KEEP_ALIVE);
                    body.writeLong(k.id());
                }
                case ServerboundPacket.AcceptTeleportation a -> {
                    body.writeVarInt(SB_ACCEPT_TELEPORTATION);
                    body.writeZigZag(a.teleportId());
                }
                case ServerboundPacket.PlayerAbilities a -> {
                    body.writeVarInt(SB_PLAYER_ABILITIES);
                    body.writeByte(a.flying() ? 1 : 0);
                }
                case ServerboundPacket.MoveVehicle v -> {
                    body.writeVarInt(SB_MOVE_VEHICLE);
                    vec(v.position());
                    body.writeFloat(v.yaw());
                    body.writeFloat(v.pitch());
                    body.writeByte(v.onGround() ? 1 : 0);
                }
                case ServerboundPacket.PaddleBoat p -> {
                    body.writeVarInt(SB_PADDLE_BOAT);
                    body.writeByte((p.left() ? 1 : 0) | (p.right() ? 2 : 0));
                }
                case ServerboundPacket.ContainerClick c -> {
                    body.writeVarInt(SB_CONTAINER_CLICK);
                    body.writeZigZag(c.containerId());
                    body.writeZigZag(c.stateId());
                    body.writeZigZag(c.slot());
                    body.writeZigZag(c.button());
                    string(c.clickType());
                }
                case ServerboundPacket.ContainerClose c -> {
                    body.writeVarInt(SB_CONTAINER_CLOSE);
                    body.writeZigZag(c.containerId());
                }
                case ServerboundPacket.PlayerLoaded ignored -> body.writeVarInt(SB_PLAYER_LOADED);
                case ServerboundPacket.ChunkBatchReceived c -> {
                    body.writeVarInt(SB_CHUNK_BATCH_RECEIVED);
                    body.writeFloat(c.desiredChunksPerTick());
                }
                case ServerboundPacket.Unmodeled u -> {
                    body.writeVarInt(SB_UNMODELED);
                    string(u.type());
                }
                case ServerboundPacket.Untranslatable u -> {
                    body.writeVarInt(SB_UNTRANSLATABLE);
                    string(u.type());
                }
                case ServerboundPacket.Handshake h -> {
                    body.writeVarInt(SB_HANDSHAKE);
                    body.writeZigZag(h.protocolVersion());
                    body.writeVarInt(h.intent().ordinal());
                }
                case ServerboundPacket.ClientInformation c -> {
                    body.writeVarInt(SB_CLIENT_INFORMATION);
                    body.writeZigZag(c.viewDistance());
                    string(c.mainHand());
                }
                case ServerboundPacket.ClientBrand b -> {
                    body.writeVarInt(SB_CLIENT_BRAND);
                    string(b.brand());
                }
                case ServerboundPacket.ChannelRegistration r -> {
                    body.writeVarInt(SB_CHANNEL_REGISTRATION);
                    body.writeByte(r.register() ? 1 : 0);
                    body.writeVarInt(r.channels().size());
                    r.channels().forEach(this::string);
                }
                case ServerboundPacket.CustomPayload p -> {
                    body.writeVarInt(SB_CUSTOM_PAYLOAD);
                    string(p.channel());
                    body.writeZigZag(p.size());
                }
            }
        }

        private void writeClientbound(ClientboundPacket packet) {
            switch (packet) {
                case ClientboundPacket.Ping p -> {
                    body.writeVarInt(CB_PING);
                    body.writeInt(p.id());
                }
                case ClientboundPacket.KeepAlive k -> {
                    body.writeVarInt(CB_KEEP_ALIVE);
                    body.writeLong(k.id());
                }
                case ClientboundPacket.Login l -> {
                    body.writeVarInt(CB_LOGIN);
                    body.writeZigZag(l.entityId());
                }
                case ClientboundPacket.Respawn ignored -> body.writeVarInt(CB_RESPAWN);
                case ClientboundPacket.SetEntityMotion m -> {
                    body.writeVarInt(CB_SET_ENTITY_MOTION_EXACT);
                    body.writeZigZag(m.entityId());
                    vec(m.clientMotion());
                    body.writeByte(m.serverMotion() == null ? 0 : 1);
                    if (m.serverMotion() != null) {
                        vec(m.serverMotion());
                    }
                }
                case ClientboundPacket.Explosion e -> {
                    body.writeVarInt(CB_EXPLOSION);
                    vec(e.center());
                    body.writeFloat(e.radius());
                    body.writeByte(e.playerKnockback() != null ? 1 : 0);
                    if (e.playerKnockback() != null) {
                        vec(e.playerKnockback());
                    }
                }
                case ClientboundPacket.PlayerPosition p -> {
                    body.writeVarInt(CB_PLAYER_POSITION);
                    body.writeZigZag(p.teleportId());
                    vec(p.position());
                    vec(p.deltaMovement());
                    body.writeFloat(p.yaw());
                    body.writeFloat(p.pitch());
                    int mask = 0;
                    for (RelativeFlag flag : p.relatives()) {
                        mask |= 1 << flag.ordinal();
                    }
                    body.writeVarInt(mask);
                }
                case ClientboundPacket.PlayerRotation r -> {
                    body.writeVarInt(CB_PLAYER_ROTATION);
                    body.writeFloat(r.yaw());
                    body.writeFloat(r.pitch());
                    body.writeByte((r.relativeYaw() ? 1 : 0) | (r.relativePitch() ? 2 : 0));
                }
                case ClientboundPacket.UpdateAttributes a -> {
                    body.writeVarInt(CB_UPDATE_ATTRIBUTES);
                    body.writeZigZag(a.entityId());
                    body.writeVarInt(a.attributes().size());
                    for (ClientboundPacket.UpdateAttributes.Attribute attribute : a.attributes()) {
                        string(attribute.key());
                        body.writeDouble(attribute.base());
                        body.writeVarInt(attribute.modifiers().size());
                        for (ClientboundPacket.UpdateAttributes.Modifier modifier : attribute.modifiers()) {
                            string(modifier.id());
                            body.writeDouble(modifier.amount());
                            body.writeVarInt(modifier.operation().ordinal());
                        }
                    }
                }
                case ClientboundPacket.MobEffect e -> {
                    body.writeVarInt(CB_MOB_EFFECT);
                    body.writeZigZag(e.entityId());
                    string(e.effectKey());
                    body.writeZigZag(e.amplifier());
                    body.writeZigZag(e.durationTicks());
                    body.writeZigZag(e.flags());
                }
                case ClientboundPacket.RemoveMobEffect e -> {
                    body.writeVarInt(CB_REMOVE_MOB_EFFECT);
                    body.writeZigZag(e.entityId());
                    string(e.effectKey());
                }
                case ClientboundPacket.PlayerAbilities a -> {
                    body.writeVarInt(CB_PLAYER_ABILITIES);
                    body.writeByte((a.invulnerable() ? 1 : 0) | (a.flying() ? 2 : 0) | (a.mayFly() ? 4 : 0) | (a.instabuild() ? 8 : 0));
                    body.writeFloat(a.flyingSpeed());
                    body.writeFloat(a.walkingSpeed());
                }
                case ClientboundPacket.GameEvent g -> {
                    body.writeVarInt(CB_GAME_EVENT);
                    body.writeZigZag(g.type());
                    body.writeFloat(g.param());
                }
                case ClientboundPacket.Unmodeled u -> {
                    body.writeVarInt(CB_UNMODELED);
                    string(u.type());
                }
                case ClientboundPacket.Untranslatable u -> {
                    body.writeVarInt(CB_UNTRANSLATABLE);
                    string(u.type());
                }
            }
        }
    }

    private static boolean isCanonicalNaN(double value) {
        return Double.doubleToRawLongBits(value) == CANONICAL_NAN_D;
    }

    private static boolean isCanonicalNaN(float value) {
        return Float.floatToRawIntBits(value) == CANONICAL_NAN_F;
    }

    // =============================================================================================

    /** What a decoded record turned out to be. */
    public sealed interface Decoded {
        record Event(TimelineEvent event) implements Decoded {}

        /** An event kind this reader does not know; its sequence number was consumed. */
        record UnknownEvent(int tag, long sequence, long nanoTime) implements Decoded {}

        record End(long totalEvents, long lostEvents) implements Decoded {}

        record UnknownMetadata(int tag) implements Decoded {}
    }

    public static final class Decoder {

        private final int version;
        private long previousSequence;
        private long previousNanoTime;
        private long previousClientTick;
        private final List<String> strings = new ArrayList<>();

        /** A decoder for the current format version. */
        public Decoder() {
            this(RecordingFormat.VERSION);
        }

        /** A decoder for recordings of format {@code version}. */
        public Decoder(int version) {
            if (version < RecordingFormat.MIN_READABLE_VERSION || version > RecordingFormat.VERSION) {
                throw new IllegalArgumentException("unsupported recording format version " + version);
            }
            this.version = version;
        }

        public void reset(long baseSequence, long baseNanoTime, long baseClientTick) {
            previousSequence = baseSequence - 1;
            previousNanoTime = baseNanoTime;
            previousClientTick = baseClientTick;
            strings.clear();
        }

        public Decoded decode(ByteSource in) {
            int tag = in.readVarInt();
            int length = in.readVarInt();
            ByteSource body = in.slice(length);
            if (tag == TAG_END) {
                return new Decoded.End(body.readVarLong(), body.readVarLong());
            }
            if (tag < 1 || tag > MAX_EVENT_TAG) {
                return new Decoded.UnknownMetadata(tag);
            }
            long deltaSequence = body.readVarLong();
            if (deltaSequence < 1) {
                throw new RecordingFormatException("non-increasing sequence");
            }
            long sequence = previousSequence + deltaSequence;
            long nanoTime = previousNanoTime + body.readZigZag();
            previousSequence = sequence;
            previousNanoTime = nanoTime;
            TimelineEvent event = switch (tag) {
                case TAG_INBOUND -> {
                    long clientTick = previousClientTick + body.readZigZag();
                    previousClientTick = clientTick;
                    yield new TimelineEvent.Inbound(sequence, nanoTime, clientTick, readServerbound(body));
                }
                case TAG_OUTBOUND -> new TimelineEvent.Outbound(sequence, nanoTime, readClientbound(body));
                case TAG_ACKNOWLEDGED -> {
                    int id = body.readInt();
                    long roundTrip = body.readZigZag();
                    yield new TimelineEvent.Acknowledged(sequence, nanoTime, id, nanoTime - roundTrip, (int) body.readZigZag());
                }
                case TAG_BOUND -> new TimelineEvent.Bound(sequence, nanoTime, readUuid(body), string(body), (int) body.readZigZag());
                case TAG_GAP -> new TimelineEvent.Gap(sequence, nanoTime, body.readZigZag());
                case TAG_ANOMALY -> new TimelineEvent.Anomaly(sequence, nanoTime, enumAt(TimelineEvent.Anomaly.Kind.values(), body.readVarInt()), string(body));
                case TAG_DISCONNECTED -> new TimelineEvent.Disconnected(sequence, nanoTime);
                case TAG_OBSERVATION -> new TimelineEvent.Observation(sequence, nanoTime,
                        enumAt(TimelineEvent.Observation.Source.values(), body.readVarInt()),
                        enumAt(TimelineEvent.Observation.Source.values(), body.readVarInt()), (int) body.readZigZag(), string(body));
                case TAG_INBOUND_DIVERGENCE -> new TimelineEvent.InboundDivergence(sequence, nanoTime,
                        enumAt(TimelineEvent.DivergenceKind.values(), body.readVarInt()), readServerbound(body));
                case TAG_OUTBOUND_DIVERGENCE -> new TimelineEvent.OutboundDivergence(sequence, nanoTime,
                        enumAt(TimelineEvent.DivergenceKind.values(), body.readVarInt()), readClientbound(body));
                case TAG_CLIENT_FACT -> {
                    TimelineEvent.ClientFact.Fact fact = enumAt(TimelineEvent.ClientFact.Fact.values(), body.readVarInt());
                    String value = string(body);
                    TimelineEvent.ClientFact.Trust trust = enumAt(TimelineEvent.ClientFact.Trust.values(), body.readVarInt());
                    yield new TimelineEvent.ClientFact(sequence, nanoTime, fact, value, trust, string(body));
                }
                default -> null;
            };
            if (event == null) {
                return new Decoded.UnknownEvent(tag, sequence, nanoTime);
            }
            if (body.remaining() != 0) {
                throw new RecordingFormatException("trailing bytes in record " + tag);
            }
            return new Decoded.Event(event);
        }

        private String string(ByteSource in) {
            int reference = in.readVarInt();
            if (reference == 0) {
                int index = version >= 2 ? in.readVarInt() : strings.size();
                String value = in.readUtf8();
                if (index < 0 || index > strings.size() + 4096) {
                    throw new RecordingFormatException("string definition index " + index + " after " + strings.size());
                }
                while (strings.size() <= index) {
                    strings.add(null); // defined in a record this reader skipped
                }
                strings.set(index, value);
                return value;
            }
            String value = reference <= strings.size() ? strings.get(reference - 1) : null;
            if (value == null) {
                if (version < 2) {
                    throw new RecordingFormatException("string reference " + reference + " of " + strings.size());
                }
                // Defined inside a record written by a newer WhyNotAC that this reader skipped: say so, never guess.
                return RecordingFormat.UNKNOWN_STRING_PREFIX + (reference - 1);
            }
            return value;
        }

        private static UUID readUuid(ByteSource in) {
            return new UUID(in.readLong(), in.readLong());
        }

        private static Vec3d vec(ByteSource in) {
            return new Vec3d(in.readDouble(), in.readDouble(), in.readDouble());
        }

        private static BlockPosition position(ByteSource in) {
            return new BlockPosition((int) in.readZigZag(), (int) in.readZigZag(), (int) in.readZigZag());
        }

        private static int zint(ByteSource in) {
            return (int) in.readZigZag();
        }

        private ServerboundPacket readServerbound(ByteSource in) {
            int tag = in.readVarInt();
            return switch (tag) {
                case SB_MOVE_PLAYER -> {
                    int flags = in.readUnsignedByte();
                    boolean hasPos = (flags & MOVE_HAS_POS) != 0;
                    boolean hasRot = (flags & MOVE_HAS_ROT) != 0;
                    boolean explicit = (flags & MOVE_EXPLICIT_ABSENT) != 0;
                    double x = Double.NaN;
                    double y = Double.NaN;
                    double z = Double.NaN;
                    float yaw = Float.NaN;
                    float pitch = Float.NaN;
                    if (hasPos || explicit) {
                        x = in.readDouble();
                        y = in.readDouble();
                        z = in.readDouble();
                    }
                    if (hasRot || explicit) {
                        yaw = in.readFloat();
                        pitch = in.readFloat();
                    }
                    yield new ServerboundPacket.MovePlayer(x, y, z, yaw, pitch, hasPos, hasRot,
                            (flags & MOVE_ON_GROUND) != 0, (flags & MOVE_H_COLLISION) != 0);
                }
                case SB_PLAYER_INPUT -> {
                    int b = in.readUnsignedByte();
                    yield new ServerboundPacket.PlayerInput((b & 1) != 0, (b & 2) != 0, (b & 4) != 0, (b & 8) != 0,
                            (b & 16) != 0, (b & 32) != 0, (b & 64) != 0);
                }
                case SB_PLAYER_COMMAND -> new ServerboundPacket.PlayerCommand(zint(in),
                        enumAt(ServerboundPacket.PlayerCommand.Action.values(), in.readVarInt()), zint(in));
                case SB_PLAYER_ACTION -> new ServerboundPacket.PlayerAction(enumAt(ServerboundPacket.PlayerAction.Action.values(), in.readVarInt()),
                        position(in), enumAt(BlockFace.values(), in.readVarInt()), zint(in));
                case SB_INTERACT_ENTITY -> {
                    int entityId = zint(in);
                    ServerboundPacket.InteractEntity.Kind kind = enumAt(ServerboundPacket.InteractEntity.Kind.values(), in.readVarInt());
                    int flags = in.readUnsignedByte();
                    Hand hand = (flags & 1) != 0 ? enumAt(Hand.values(), in.readVarInt()) : null;
                    Vec3d location = (flags & 2) != 0 ? vec(in) : null;
                    yield new ServerboundPacket.InteractEntity(entityId, kind, hand, location, (flags & 4) != 0);
                }
                case SB_SWING -> new ServerboundPacket.Swing(enumAt(Hand.values(), in.readVarInt()));
                case SB_USE_ITEM -> new ServerboundPacket.UseItem(enumAt(Hand.values(), in.readVarInt()), zint(in), in.readFloat(), in.readFloat());
                case SB_USE_ITEM_ON -> {
                    Hand hand = enumAt(Hand.values(), in.readVarInt());
                    BlockPosition position = position(in);
                    BlockFace face = enumAt(BlockFace.values(), in.readVarInt());
                    Vec3d location = vec(in);
                    int flags = in.readUnsignedByte();
                    yield new ServerboundPacket.UseItemOn(hand, position, face, location, (flags & 1) != 0, (flags & 2) != 0, zint(in));
                }
                case SB_SET_CARRIED_ITEM -> new ServerboundPacket.SetCarriedItem(zint(in));
                case SB_CLIENT_TICK_END -> new ServerboundPacket.ClientTickEnd();
                case SB_PONG -> new ServerboundPacket.Pong(in.readInt());
                case SB_KEEP_ALIVE -> new ServerboundPacket.KeepAlive(in.readLong());
                case SB_ACCEPT_TELEPORTATION -> new ServerboundPacket.AcceptTeleportation(zint(in));
                case SB_PLAYER_ABILITIES -> new ServerboundPacket.PlayerAbilities(in.readUnsignedByte() != 0);
                case SB_MOVE_VEHICLE -> new ServerboundPacket.MoveVehicle(vec(in), in.readFloat(), in.readFloat(), in.readUnsignedByte() != 0);
                case SB_PADDLE_BOAT -> {
                    int b = in.readUnsignedByte();
                    yield new ServerboundPacket.PaddleBoat((b & 1) != 0, (b & 2) != 0);
                }
                case SB_CONTAINER_CLICK -> new ServerboundPacket.ContainerClick(zint(in), zint(in), zint(in), zint(in), string(in));
                case SB_CONTAINER_CLOSE -> new ServerboundPacket.ContainerClose(zint(in));
                case SB_PLAYER_LOADED -> new ServerboundPacket.PlayerLoaded();
                case SB_CHUNK_BATCH_RECEIVED -> new ServerboundPacket.ChunkBatchReceived(in.readFloat());
                case SB_UNMODELED -> new ServerboundPacket.Unmodeled(string(in));
                case SB_UNTRANSLATABLE -> new ServerboundPacket.Untranslatable(string(in));
                case SB_HANDSHAKE -> new ServerboundPacket.Handshake(zint(in), enumAt(ServerboundPacket.Handshake.Intent.values(), in.readVarInt()));
                case SB_CLIENT_INFORMATION -> new ServerboundPacket.ClientInformation(zint(in), string(in));
                case SB_CLIENT_BRAND -> new ServerboundPacket.ClientBrand(string(in));
                case SB_CHANNEL_REGISTRATION -> {
                    boolean register = in.readUnsignedByte() != 0;
                    int count = in.readVarInt();
                    if (count < 0 || count > in.remaining()) {
                        throw new RecordingFormatException("channel count " + count);
                    }
                    List<String> channels = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        channels.add(string(in));
                    }
                    yield new ServerboundPacket.ChannelRegistration(register, channels);
                }
                case SB_CUSTOM_PAYLOAD -> new ServerboundPacket.CustomPayload(string(in), zint(in));
                default -> {
                    in.skip(in.remaining()); // the packet is the record's last field
                    yield new ServerboundPacket.Unmodeled(UNKNOWN_PACKET_PREFIX + tag);
                }
            };
        }

        private ClientboundPacket readClientbound(ByteSource in) {
            int tag = in.readVarInt();
            return switch (tag) {
                case CB_PING -> new ClientboundPacket.Ping(in.readInt());
                case CB_KEEP_ALIVE -> new ClientboundPacket.KeepAlive(in.readLong());
                case CB_LOGIN -> new ClientboundPacket.Login(zint(in));
                case CB_RESPAWN -> new ClientboundPacket.Respawn();
                // Format 1 recorded the game's view only: the server value, which the client quantizes once.
                case CB_SET_ENTITY_MOTION -> ClientboundPacket.SetEntityMotion.sent(zint(in), vec(in));
                case CB_SET_ENTITY_MOTION_EXACT -> {
                    int entityId = zint(in);
                    Vec3d client = vec(in);
                    yield new ClientboundPacket.SetEntityMotion(entityId, client, in.readUnsignedByte() != 0 ? vec(in) : null);
                }
                case CB_EXPLOSION -> {
                    Vec3d center = vec(in);
                    float radius = in.readFloat();
                    Vec3d knockback = in.readUnsignedByte() != 0 ? vec(in) : null;
                    yield new ClientboundPacket.Explosion(center, radius, knockback);
                }
                case CB_PLAYER_POSITION -> {
                    int teleportId = zint(in);
                    Vec3d position = vec(in);
                    Vec3d delta = vec(in);
                    float yaw = in.readFloat();
                    float pitch = in.readFloat();
                    int mask = in.readVarInt();
                    Set<RelativeFlag> relatives = EnumSet.noneOf(RelativeFlag.class);
                    for (RelativeFlag flag : RelativeFlag.values()) {
                        if ((mask & 1 << flag.ordinal()) != 0) {
                            relatives.add(flag);
                        }
                    }
                    if (mask >>> RelativeFlag.values().length != 0) {
                        throw new RecordingFormatException("unknown relative flags " + Integer.toBinaryString(mask));
                    }
                    yield new ClientboundPacket.PlayerPosition(teleportId, position, delta, yaw, pitch, relatives);
                }
                case CB_PLAYER_ROTATION -> {
                    float yaw = in.readFloat();
                    float pitch = in.readFloat();
                    int flags = in.readUnsignedByte();
                    yield new ClientboundPacket.PlayerRotation(yaw, (flags & 1) != 0, pitch, (flags & 2) != 0);
                }
                case CB_UPDATE_ATTRIBUTES -> {
                    int entityId = zint(in);
                    int count = boundedCount(in);
                    List<ClientboundPacket.UpdateAttributes.Attribute> attributes = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        String key = string(in);
                        double base = in.readDouble();
                        int modifierCount = boundedCount(in);
                        List<ClientboundPacket.UpdateAttributes.Modifier> modifiers = new ArrayList<>(modifierCount);
                        for (int j = 0; j < modifierCount; j++) {
                            modifiers.add(new ClientboundPacket.UpdateAttributes.Modifier(string(in), in.readDouble(),
                                    enumAt(ClientboundPacket.UpdateAttributes.Operation.values(), in.readVarInt())));
                        }
                        attributes.add(new ClientboundPacket.UpdateAttributes.Attribute(key, base, modifiers));
                    }
                    yield new ClientboundPacket.UpdateAttributes(entityId, attributes);
                }
                case CB_MOB_EFFECT -> new ClientboundPacket.MobEffect(zint(in), string(in), zint(in), zint(in), zint(in));
                case CB_REMOVE_MOB_EFFECT -> new ClientboundPacket.RemoveMobEffect(zint(in), string(in));
                case CB_PLAYER_ABILITIES -> {
                    int flags = in.readUnsignedByte();
                    yield new ClientboundPacket.PlayerAbilities((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
                            in.readFloat(), in.readFloat());
                }
                case CB_GAME_EVENT -> new ClientboundPacket.GameEvent(zint(in), in.readFloat());
                case CB_UNMODELED -> new ClientboundPacket.Unmodeled(string(in));
                case CB_UNTRANSLATABLE -> new ClientboundPacket.Untranslatable(string(in));
                default -> {
                    in.skip(in.remaining());
                    yield new ClientboundPacket.Unmodeled(UNKNOWN_PACKET_PREFIX + tag);
                }
            };
        }

        private static int boundedCount(ByteSource in) {
            int count = in.readVarInt();
            if (count < 0 || count > in.remaining()) {
                throw new RecordingFormatException("implausible element count " + count);
            }
            return count;
        }
    }

    private static <E extends Enum<E>> E enumAt(E[] values, int ordinal) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new RecordingFormatException("enum ordinal " + ordinal + " out of range for " + values.getClass().getComponentType().getSimpleName());
        }
        return values[ordinal];
    }
}
