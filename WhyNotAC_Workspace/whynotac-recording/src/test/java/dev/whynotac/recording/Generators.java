package dev.whynotac.recording;

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
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/** Adversarial random instances of every event and packet type. */
public final class Generators {

    private final SplittableRandom random;
    private long sequence;
    private long nanoTime;
    private long clientTick;

    public Generators(long seed) {
        this.random = new SplittableRandom(seed);
        this.nanoTime = random.nextLong(Long.MIN_VALUE / 4, Long.MAX_VALUE / 4);
    }

    public double anyDouble() {
        return switch (random.nextInt(12)) {
            case 0 -> Double.NaN;
            case 1 -> Double.longBitsToDouble(0x7FF0_0000_0000_0001L + random.nextLong(1L << 50)); // NaN with payload
            case 2 -> -0.0;
            case 3 -> Double.POSITIVE_INFINITY;
            case 4 -> Double.MIN_VALUE;
            case 5 -> -Double.MAX_VALUE;
            case 6 -> Double.longBitsToDouble(random.nextLong());
            case 7 -> random.nextDouble(-3.0e7, 3.0e7);
            default -> random.nextDouble(-2, 2);
        };
    }

    public float anyFloat() {
        return switch (random.nextInt(10)) {
            case 0 -> Float.NaN;
            case 1 -> Float.intBitsToFloat(0x7F80_0001 + random.nextInt(1 << 20));
            case 2 -> -0.0F;
            case 3 -> Float.NEGATIVE_INFINITY;
            case 4 -> Float.intBitsToFloat(random.nextInt());
            default -> (float) random.nextDouble(-1.0e6, 1.0e6);
        };
    }

    public int anyInt() {
        return switch (random.nextInt(6)) {
            case 0 -> Integer.MIN_VALUE;
            case 1 -> Integer.MAX_VALUE;
            case 2 -> 0;
            case 3 -> random.nextInt();
            default -> random.nextInt(-300, 300);
        };
    }

    public long anyLong() {
        return random.nextInt(4) == 0 ? (random.nextBoolean() ? Long.MIN_VALUE : Long.MAX_VALUE) : random.nextLong();
    }

    public String anyString() {
        return switch (random.nextInt(6)) {
            case 0 -> "";
            case 1 -> "minecraft:level_chunk_with_light";
            case 2 -> "çğıöşü ÇĞİÖŞÜ 🗡️ \"quoted\" \\ \n";
            case 3 -> "x".repeat(random.nextInt(1, 3000));
            default -> "minecraft:" + Integer.toHexString(random.nextInt(64)); // repeats exercise the string table
        };
    }

    public Vec3d vec() {
        return new Vec3d(anyDouble(), anyDouble(), anyDouble());
    }

    public <E extends Enum<E>> E pick(E[] values) {
        return values[random.nextInt(values.length)];
    }

    private BlockPosition position() {
        return new BlockPosition(anyInt(), anyInt(), anyInt());
    }

    public static final List<Class<?>> SERVERBOUND = List.of(ServerboundPacket.class.getPermittedSubclasses());
    public static final List<Class<?>> CLIENTBOUND = List.of(ClientboundPacket.class.getPermittedSubclasses());
    public static final List<Class<?>> EVENTS = List.of(TimelineEvent.class.getPermittedSubclasses());

    public ServerboundPacket serverbound(Class<?> type) {
        String name = type.getSimpleName();
        return switch (name) {
            case "MovePlayer" -> {
                boolean pos = random.nextBoolean();
                boolean rot = random.nextBoolean();
                boolean canonical = random.nextInt(4) != 0;
                yield new ServerboundPacket.MovePlayer(
                        pos || !canonical ? anyDouble() : Double.NaN, pos || !canonical ? anyDouble() : Double.NaN, pos || !canonical ? anyDouble() : Double.NaN,
                        rot || !canonical ? anyFloat() : Float.NaN, rot || !canonical ? anyFloat() : Float.NaN,
                        pos, rot, random.nextBoolean(), random.nextBoolean());
            }
            case "PlayerInput" -> new ServerboundPacket.PlayerInput(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean());
            case "PlayerCommand" -> new ServerboundPacket.PlayerCommand(anyInt(), pick(ServerboundPacket.PlayerCommand.Action.values()), anyInt());
            case "PlayerAction" -> new ServerboundPacket.PlayerAction(pick(ServerboundPacket.PlayerAction.Action.values()), position(), pick(BlockFace.values()), anyInt());
            case "InteractEntity" -> new ServerboundPacket.InteractEntity(anyInt(), pick(ServerboundPacket.InteractEntity.Kind.values()),
                    random.nextBoolean() ? pick(Hand.values()) : null, random.nextBoolean() ? vec() : null, random.nextBoolean());
            case "Swing" -> new ServerboundPacket.Swing(pick(Hand.values()));
            case "UseItem" -> new ServerboundPacket.UseItem(pick(Hand.values()), anyInt(), anyFloat(), anyFloat());
            case "UseItemOn" -> new ServerboundPacket.UseItemOn(pick(Hand.values()), position(), pick(BlockFace.values()), vec(),
                    random.nextBoolean(), random.nextBoolean(), anyInt());
            case "SetCarriedItem" -> new ServerboundPacket.SetCarriedItem(anyInt());
            case "ClientTickEnd" -> new ServerboundPacket.ClientTickEnd();
            case "Pong" -> new ServerboundPacket.Pong(anyInt());
            case "KeepAlive" -> new ServerboundPacket.KeepAlive(anyLong());
            case "AcceptTeleportation" -> new ServerboundPacket.AcceptTeleportation(anyInt());
            case "PlayerAbilities" -> new ServerboundPacket.PlayerAbilities(random.nextBoolean());
            case "MoveVehicle" -> new ServerboundPacket.MoveVehicle(vec(), anyFloat(), anyFloat(), random.nextBoolean());
            case "PaddleBoat" -> new ServerboundPacket.PaddleBoat(random.nextBoolean(), random.nextBoolean());
            case "ContainerClick" -> new ServerboundPacket.ContainerClick(anyInt(), anyInt(), anyInt(), anyInt(), anyString());
            case "ContainerClose" -> new ServerboundPacket.ContainerClose(anyInt());
            case "PlayerLoaded" -> new ServerboundPacket.PlayerLoaded();
            case "ChunkBatchReceived" -> new ServerboundPacket.ChunkBatchReceived(anyFloat());
            case "ClientInformation" -> new ServerboundPacket.ClientInformation(anyInt(), random.nextBoolean() ? "LEFT" : anyString());
            case "ClientBrand" -> new ServerboundPacket.ClientBrand(anyString());
            case "ChannelRegistration" -> new ServerboundPacket.ChannelRegistration(random.nextBoolean(),
                    java.util.stream.IntStream.range(0, random.nextInt(4)).mapToObj(n -> anyString()).toList());
            case "CustomPayload" -> new ServerboundPacket.CustomPayload(anyString(), anyInt());
            case "Unmodeled" -> new ServerboundPacket.Unmodeled(anyString());
            case "Untranslatable" -> new ServerboundPacket.Untranslatable(anyString());
            case "Handshake" -> new ServerboundPacket.Handshake(anyInt(), pick(ServerboundPacket.Handshake.Intent.values()));
            default -> throw new AssertionError("no generator for serverbound " + name + ": add one (and a codec case)");
        };
    }

    public ClientboundPacket clientbound(Class<?> type) {
        String name = type.getSimpleName();
        return switch (name) {
            case "Ping" -> new ClientboundPacket.Ping(anyInt());
            case "KeepAlive" -> new ClientboundPacket.KeepAlive(anyLong());
            case "Login" -> new ClientboundPacket.Login(anyInt());
            case "Respawn" -> new ClientboundPacket.Respawn();
            case "SetEntityMotion" -> new ClientboundPacket.SetEntityMotion(anyInt(), vec(), random.nextBoolean() ? vec() : null);
            case "Explosion" -> new ClientboundPacket.Explosion(vec(), anyFloat(), random.nextBoolean() ? vec() : null);
            case "PlayerPosition" -> {
                EnumSet<RelativeFlag> flags = EnumSet.noneOf(RelativeFlag.class);
                for (RelativeFlag flag : RelativeFlag.values()) {
                    if (random.nextBoolean()) {
                        flags.add(flag);
                    }
                }
                yield new ClientboundPacket.PlayerPosition(anyInt(), vec(), vec(), anyFloat(), anyFloat(), flags);
            }
            case "PlayerRotation" -> new ClientboundPacket.PlayerRotation(anyFloat(), random.nextBoolean(), anyFloat(), random.nextBoolean());
            case "TeleportEntity" -> new ClientboundPacket.TeleportEntity(anyInt());
            case "UpdateAttributes" -> {
                List<ClientboundPacket.UpdateAttributes.Attribute> attributes = new ArrayList<>();
                for (int i = random.nextInt(4); i > 0; i--) {
                    List<ClientboundPacket.UpdateAttributes.Modifier> modifiers = new ArrayList<>();
                    for (int j = random.nextInt(3); j > 0; j--) {
                        modifiers.add(new ClientboundPacket.UpdateAttributes.Modifier(anyString(), anyDouble(),
                                pick(ClientboundPacket.UpdateAttributes.Operation.values())));
                    }
                    attributes.add(new ClientboundPacket.UpdateAttributes.Attribute(anyString(), anyDouble(), modifiers));
                }
                yield new ClientboundPacket.UpdateAttributes(anyInt(), attributes);
            }
            case "MobEffect" -> new ClientboundPacket.MobEffect(anyInt(), anyString(), anyInt(), anyInt(), anyInt());
            case "RemoveMobEffect" -> new ClientboundPacket.RemoveMobEffect(anyInt(), anyString());
            case "PlayerAbilities" -> new ClientboundPacket.PlayerAbilities(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
                    random.nextBoolean(), anyFloat(), anyFloat());
            case "GameEvent" -> new ClientboundPacket.GameEvent(anyInt(), anyFloat());
            case "Unmodeled" -> new ClientboundPacket.Unmodeled(anyString());
            case "Untranslatable" -> new ClientboundPacket.Untranslatable(anyString());
            default -> throw new AssertionError("no generator for clientbound " + name + ": add one (and a codec case)");
        };
    }

    /** Next event with a strictly increasing sequence and arbitrary (also backwards) time steps. */
    public TimelineEvent event(Class<?> type) {
        sequence += random.nextInt(8) == 0 ? random.nextLong(2, 1000) : 1;
        nanoTime += random.nextInt(10) == 0 ? random.nextLong(-1_000_000_000L, 1_000_000_000L) : random.nextLong(0, 60_000_000L);
        String name = type.getSimpleName();
        return switch (name) {
            case "Inbound" -> {
                if (random.nextInt(5) == 0) {
                    clientTick += random.nextInt(3);
                }
                yield new TimelineEvent.Inbound(sequence, nanoTime, clientTick, serverbound(SERVERBOUND.get(random.nextInt(SERVERBOUND.size()))));
            }
            case "Outbound" -> new TimelineEvent.Outbound(sequence, nanoTime, clientbound(CLIENTBOUND.get(random.nextInt(CLIENTBOUND.size()))));
            case "Acknowledged" -> new TimelineEvent.Acknowledged(sequence, nanoTime, anyInt(), nanoTime - random.nextLong(0, 5_000_000_000L), random.nextInt(0, 50));
            case "Bound" -> new TimelineEvent.Bound(sequence, nanoTime, new UUID(random.nextLong(), random.nextLong()), anyString(), anyInt());
            case "Gap" -> new TimelineEvent.Gap(sequence, nanoTime, random.nextLong(0, Long.MAX_VALUE));
            case "Anomaly" -> new TimelineEvent.Anomaly(sequence, nanoTime, pick(TimelineEvent.Anomaly.Kind.values()), anyString());
            case "Disconnected" -> new TimelineEvent.Disconnected(sequence, nanoTime);
            case "Observation" -> new TimelineEvent.Observation(sequence, nanoTime, pick(TimelineEvent.Observation.Source.values()),
                    pick(TimelineEvent.Observation.Source.values()), anyInt(), anyString());
            case "InboundDivergence" -> new TimelineEvent.InboundDivergence(sequence, nanoTime, pick(TimelineEvent.DivergenceKind.values()),
                    serverbound(SERVERBOUND.get(random.nextInt(SERVERBOUND.size()))));
            case "ClientFact" -> new TimelineEvent.ClientFact(sequence, nanoTime, pick(TimelineEvent.ClientFact.Fact.values()), anyString(),
                    pick(TimelineEvent.ClientFact.Trust.values()), anyString());
            case "OutboundDivergence" -> new TimelineEvent.OutboundDivergence(sequence, nanoTime, pick(TimelineEvent.DivergenceKind.values()),
                    clientbound(CLIENTBOUND.get(random.nextInt(CLIENTBOUND.size()))));
            default -> throw new AssertionError("no generator for event " + name);
        };
    }

    /** A random stream of events, never containing Disconnected except possibly last. */
    public List<TimelineEvent> stream(int count) {
        List<TimelineEvent> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Class<?> type;
            do {
                type = EVENTS.get(random.nextInt(EVENTS.size()));
            } while (type == TimelineEvent.Disconnected.class);
            out.add(event(type));
        }
        return out;
    }
}
