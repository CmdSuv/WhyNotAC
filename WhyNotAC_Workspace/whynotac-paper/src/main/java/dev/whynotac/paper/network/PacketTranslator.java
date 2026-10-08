package dev.whynotac.paper.network;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Translates live 1.21.11 (Mojang-mapped) packet objects into WhyNotAC's immutable model.
 *
 * <p>Runs on Netty threads for every packet, so it only reads fields and allocates the result
 * record. Values are copied verbatim; in particular absent movement components become {@code NaN}
 * instead of the {@code 0.0} placeholder the game object stores.
 */
public final class PacketTranslator {

    private static final VarHandle GAME_EVENT_TYPE_ID;

    static {
        try {
            GAME_EVENT_TYPE_ID = MethodHandles.privateLookupIn(ClientboundGameEventPacket.Type.class, MethodHandles.lookup())
                    .findVarHandle(ClientboundGameEventPacket.Type.class, "id", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Maps game enum constants onto WhyNotAC's by name. A constant a newer game version adds becomes
     * {@code UNKNOWN} instead of failing the whole packet (the packet's other fields stay usable);
     * {@code ProtocolSurfaceTest} turns such additions into a failing build so they get modelled.
     */
    static final class EnumMapping<G extends Enum<G>, W extends Enum<W>> {
        private final Map<G, W> table;
        private final W unknown;

        EnumMapping(Class<G> game, Class<W> ours, W unknown) {
            this.unknown = unknown;
            Map<G, W> map = new java.util.EnumMap<>(game);
            for (G constant : game.getEnumConstants()) {
                for (W candidate : ours.getEnumConstants()) {
                    if (candidate.name().equals(constant.name())) {
                        map.put(constant, candidate);
                    }
                }
            }
            this.table = map;
        }

        W map(G constant) {
            return table.getOrDefault(constant, unknown);
        }
    }

    static final EnumMapping<ServerboundPlayerCommandPacket.Action, ServerboundPacket.PlayerCommand.Action> PLAYER_COMMAND =
            new EnumMapping<>(ServerboundPlayerCommandPacket.Action.class, ServerboundPacket.PlayerCommand.Action.class, ServerboundPacket.PlayerCommand.Action.UNKNOWN);
    static final EnumMapping<ServerboundPlayerActionPacket.Action, ServerboundPacket.PlayerAction.Action> PLAYER_ACTION =
            new EnumMapping<>(ServerboundPlayerActionPacket.Action.class, ServerboundPacket.PlayerAction.Action.class, ServerboundPacket.PlayerAction.Action.UNKNOWN);
    static final EnumMapping<AttributeModifier.Operation, ClientboundPacket.UpdateAttributes.Operation> OPERATION =
            new EnumMapping<>(AttributeModifier.Operation.class, ClientboundPacket.UpdateAttributes.Operation.class, ClientboundPacket.UpdateAttributes.Operation.UNKNOWN);
    static final EnumMapping<Relative, RelativeFlag> RELATIVE = new EnumMapping<>(Relative.class, RelativeFlag.class, RelativeFlag.UNKNOWN);
    static final EnumMapping<ClientIntent, ServerboundPacket.Handshake.Intent> INTENT =
            new EnumMapping<>(ClientIntent.class, ServerboundPacket.Handshake.Intent.class, ServerboundPacket.Handshake.Intent.UNKNOWN);

    /**
     * Packet type paths (any protocol state) that {@link #inbound} turns into a dedicated model record.
     * The edge view decodes exactly these; everything else is recorded by type only.
     * {@code ProtocolSurfaceTest} keeps this list in step with the protocol.
     */
    public static final Set<String> SERVERBOUND_MODELLED = Set.of(
            "intention", "client_information", "custom_payload",
            "accept_teleportation", "chunk_batch_received", "client_tick_end", "container_click", "container_close", "interact",
            "keep_alive", "move_player_pos", "move_player_pos_rot", "move_player_rot", "move_player_status_only", "move_vehicle",
            "paddle_boat", "player_abilities", "player_action", "player_command", "player_input", "player_loaded", "pong",
            "set_carried_item", "swing", "use_item", "use_item_on");

    /** Packet type paths that {@link #outbound} turns into a dedicated model record (see {@link #SERVERBOUND_MODELLED}). */
    public static final Set<String> CLIENTBOUND_MODELLED = Set.of(
            "explode", "game_event", "keep_alive", "login", "ping", "player_abilities", "player_position", "player_rotation",
            "remove_mob_effect", "respawn", "set_entity_motion", "update_attributes", "update_mob_effect");

    /** Best-effort type id of a packet whose translation failed (the failure may be in {@code type()} itself). */
    /** Packet class -> type id. A packet class always has the same type; built once per class, never per packet. */
    private static final Map<Class<?>, String> TYPE_NAMES = new ConcurrentHashMap<>();

    public static String typeName(Packet<?> packet) {
        String name = TYPE_NAMES.get(packet.getClass());
        if (name != null) {
            return name;
        }
        try {
            name = packet.type().id().toString();
        } catch (RuntimeException | LinkageError e) {
            return packet.getClass().getName();
        }
        TYPE_NAMES.put(packet.getClass(), name);
        return name;
    }

    private final Map<Class<?>, ServerboundPacket.Unmodeled> unmodeledInbound = new ConcurrentHashMap<>();
    private final Map<Class<?>, ClientboundPacket.Unmodeled> unmodeledOutbound = new ConcurrentHashMap<>();

    // --- serverbound -------------------------------------------------------------------------------

    public ServerboundPacket inbound(Packet<?> packet) {
        return switch (packet) {
            case ServerboundMovePlayerPacket move -> new ServerboundPacket.MovePlayer(
                    move.getX(Double.NaN), move.getY(Double.NaN), move.getZ(Double.NaN),
                    move.getYRot(Float.NaN), move.getXRot(Float.NaN),
                    move.hasPosition(), move.hasRotation(), move.isOnGround(), move.horizontalCollision());
            case ServerboundPlayerInputPacket(Input input) -> new ServerboundPacket.PlayerInput(
                    input.forward(), input.backward(), input.left(), input.right(), input.jump(), input.shift(), input.sprint());
            case ServerboundClientTickEndPacket ignored -> new ServerboundPacket.ClientTickEnd();
            case ServerboundPongPacket pong -> new ServerboundPacket.Pong(pong.getId());
            case ServerboundPlayerCommandPacket command -> new ServerboundPacket.PlayerCommand(
                    command.getId(), PLAYER_COMMAND.map(command.getAction()), command.getData());
            case ServerboundPlayerActionPacket action -> new ServerboundPacket.PlayerAction(
                    PLAYER_ACTION.map(action.getAction()),
                    position(action.getPos()), face(action.getDirection()), action.getSequence());
            case ServerboundInteractPacket interact -> interact(interact);
            case ServerboundSwingPacket swing -> new ServerboundPacket.Swing(hand(swing.getHand()));
            case ServerboundUseItemPacket use -> new ServerboundPacket.UseItem(hand(use.getHand()), use.getSequence(), use.getYRot(), use.getXRot());
            case ServerboundUseItemOnPacket useOn -> useItemOn(useOn);
            case ServerboundSetCarriedItemPacket carried -> new ServerboundPacket.SetCarriedItem(carried.getSlot());
            case ServerboundKeepAlivePacket keepAlive -> new ServerboundPacket.KeepAlive(keepAlive.getId());
            case ServerboundAcceptTeleportationPacket accept -> new ServerboundPacket.AcceptTeleportation(accept.getId());
            case ServerboundPlayerAbilitiesPacket abilities -> new ServerboundPacket.PlayerAbilities(abilities.isFlying());
            case ServerboundMoveVehiclePacket vehicle -> new ServerboundPacket.MoveVehicle(
                    vec(vehicle.position()), vehicle.yRot(), vehicle.xRot(), vehicle.onGround());
            case ServerboundPaddleBoatPacket paddle -> new ServerboundPacket.PaddleBoat(paddle.getLeft(), paddle.getRight());
            case ServerboundContainerClickPacket click -> new ServerboundPacket.ContainerClick(
                    click.containerId(), click.stateId(), click.slotNum(), click.buttonNum(), click.clickType().name());
            case ServerboundContainerClosePacket close -> new ServerboundPacket.ContainerClose(close.getContainerId());
            case ServerboundPlayerLoadedPacket ignored -> new ServerboundPacket.PlayerLoaded();
            case ServerboundChunkBatchReceivedPacket(float desired) -> new ServerboundPacket.ChunkBatchReceived(desired);
            case ClientIntentionPacket intention -> new ServerboundPacket.Handshake(intention.protocolVersion(), INTENT.map(intention.intention()));
            case ServerboundClientInformationPacket info -> new ServerboundPacket.ClientInformation(
                    info.information().viewDistance(), info.information().mainHand().name());
            case ServerboundCustomPayloadPacket payload -> customPayload(payload.payload());
            default -> unmodeledInbound.computeIfAbsent(packet.getClass(), c -> new ServerboundPacket.Unmodeled(packet.type().id().toString()));
        };
    }

    private static ServerboundPacket interact(ServerboundInteractPacket packet) {
        final class Capture implements ServerboundInteractPacket.Handler {
            ServerboundPacket.InteractEntity.Kind kind = ServerboundPacket.InteractEntity.Kind.ATTACK;
            Hand hand;
            Vec3d location;

            @Override
            public void onInteraction(InteractionHand interactionHand) {
                kind = ServerboundPacket.InteractEntity.Kind.INTERACT;
                hand = hand(interactionHand);
            }

            @Override
            public void onInteraction(InteractionHand interactionHand, Vec3 interactionLocation) {
                kind = ServerboundPacket.InteractEntity.Kind.INTERACT_AT;
                hand = hand(interactionHand);
                location = vec(interactionLocation);
            }

            @Override
            public void onAttack() {
                kind = ServerboundPacket.InteractEntity.Kind.ATTACK;
            }
        }
        Capture capture = new Capture();
        packet.dispatch(capture);
        return new ServerboundPacket.InteractEntity(packet.getEntityId(), capture.kind, capture.hand, capture.location, packet.isUsingSecondaryAction());
    }

    private static ServerboundPacket useItemOn(ServerboundUseItemOnPacket packet) {
        BlockHitResult hit = packet.getHitResult();
        return new ServerboundPacket.UseItemOn(hand(packet.getHand()), position(hit.getBlockPos()), face(hit.getDirection()),
                vec(hit.getLocation()), hit.isInside(), hit.isWorldBorderHit(), packet.getSequence());
    }

    /** Longest brand or channel name kept: a client may send up to 32 KiB of anything. */
    static final int MAX_CLAIMED_TEXT = 128;
    /** Most channels kept from one registration. */
    static final int MAX_CHANNELS = 64;

    private static ServerboundPacket customPayload(CustomPacketPayload payload) {
        if (payload instanceof BrandPayload brand) {
            return new ServerboundPacket.ClientBrand(clip(brand.brand()));
        }
        String channel = payload.type().id().toString();
        if (payload instanceof DiscardedPayload discarded) {
            if (channel.equals("minecraft:brand")) {
                // Paper keeps every serverbound payload as raw data (for plugin messaging), the brand too.
                try {
                    return new ServerboundPacket.ClientBrand(clip(new net.minecraft.network.FriendlyByteBuf(
                            io.netty.buffer.Unpooled.wrappedBuffer(discarded.data())).readUtf(32767)));
                } catch (RuntimeException malformed) {
                    return new ServerboundPacket.CustomPayload(channel, discarded.data().length);
                }
            }
            boolean register = channel.equals("minecraft:register");
            if (register || channel.equals("minecraft:unregister")) {
                List<String> channels = new ArrayList<>();
                for (String name : new String(discarded.data(), java.nio.charset.StandardCharsets.UTF_8).split("\u0000")) {
                    if (!name.isEmpty() && channels.size() < MAX_CHANNELS) {
                        channels.add(clip(name));
                    }
                }
                return new ServerboundPacket.ChannelRegistration(register, channels);
            }
            return new ServerboundPacket.CustomPayload(clip(channel), discarded.data().length);
        }
        return new ServerboundPacket.CustomPayload(clip(channel), -1);
    }

    private static String clip(String text) {
        return text.length() <= MAX_CLAIMED_TEXT ? text : text.substring(0, MAX_CLAIMED_TEXT);
    }

    // --- clientbound -------------------------------------------------------------------------------

    public ClientboundPacket outbound(Packet<?> packet) {
        return outbound(packet, false);
    }

    /**
     * {@code fromWire}: the packet object was decoded from the bytes the client received (network edge),
     * so lossy fields already hold the client's values; otherwise it is the game's object before encoding.
     */
    public ClientboundPacket outbound(Packet<?> packet, boolean fromWire) {
        return switch (packet) {
            case ClientboundPingPacket ping -> new ClientboundPacket.Ping(ping.getId());
            case ClientboundKeepAlivePacket keepAlive -> new ClientboundPacket.KeepAlive(keepAlive.getId());
            case ClientboundSetEntityMotionPacket motion -> fromWire
                    ? ClientboundPacket.SetEntityMotion.received(motion.getId(), vec(motion.getMovement()))
                    : ClientboundPacket.SetEntityMotion.sent(motion.getId(), vec(motion.getMovement()));
            case ClientboundExplodePacket explode -> new ClientboundPacket.Explosion(
                    vec(explode.center()), explode.radius(), explode.playerKnockback().map(PacketTranslator::vec).orElse(null));
            case ClientboundPlayerPositionPacket position -> new ClientboundPacket.PlayerPosition(
                    position.id(), vec(position.change().position()), vec(position.change().deltaMovement()),
                    position.change().yRot(), position.change().xRot(), relatives(position.relatives()));
            case ClientboundPlayerRotationPacket rotation -> new ClientboundPacket.PlayerRotation(
                    rotation.yRot(), rotation.relativeY(), rotation.xRot(), rotation.relativeX());
            case ClientboundUpdateAttributesPacket attributes -> attributes(attributes);
            case ClientboundUpdateMobEffectPacket effect -> new ClientboundPacket.MobEffect(
                    effect.getEntityId(), key(effect.getEffect()), effect.getEffectAmplifier(), effect.getEffectDurationTicks(),
                    (effect.isEffectAmbient() ? 1 : 0) | (effect.isEffectVisible() ? 2 : 0) | (effect.effectShowsIcon() ? 4 : 0) | (effect.shouldBlend() ? 8 : 0));
            case ClientboundRemoveMobEffectPacket remove -> new ClientboundPacket.RemoveMobEffect(remove.entityId(), key(remove.effect()));
            case ClientboundPlayerAbilitiesPacket abilities -> new ClientboundPacket.PlayerAbilities(
                    abilities.isInvulnerable(), abilities.isFlying(), abilities.canFly(), abilities.canInstabuild(),
                    abilities.getFlyingSpeed(), abilities.getWalkingSpeed());
            case ClientboundGameEventPacket event -> new ClientboundPacket.GameEvent((int) GAME_EVENT_TYPE_ID.get(event.getEvent()), event.getParam());
            case ClientboundLoginPacket login -> new ClientboundPacket.Login(login.playerId());
            case ClientboundRespawnPacket ignored -> new ClientboundPacket.Respawn();
            default -> unmodeledOutbound.computeIfAbsent(packet.getClass(), c -> new ClientboundPacket.Unmodeled(packet.type().id().toString()));
        };
    }

    private static ClientboundPacket attributes(ClientboundUpdateAttributesPacket packet) {
        List<ClientboundPacket.UpdateAttributes.Attribute> out = new ArrayList<>(packet.getValues().size());
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : packet.getValues()) {
            List<ClientboundPacket.UpdateAttributes.Modifier> modifiers = new ArrayList<>(snapshot.modifiers().size());
            for (AttributeModifier modifier : snapshot.modifiers()) {
                modifiers.add(new ClientboundPacket.UpdateAttributes.Modifier(modifier.id().toString(), modifier.amount(),
                        OPERATION.map(modifier.operation())));
            }
            out.add(new ClientboundPacket.UpdateAttributes.Attribute(key(snapshot.attribute()), snapshot.base(), modifiers));
        }
        return new ClientboundPacket.UpdateAttributes(packet.getEntityId(), out);
    }

    // --- helpers -------------------------------------------------------------------------------

    static Vec3d vec(Vec3 v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    static Hand hand(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? Hand.MAIN_HAND : Hand.OFF_HAND;
    }

    static BlockFace face(Direction direction) {
        return BlockFace.values()[direction.get3DDataValue()];
    }

    static BlockPosition position(BlockPos pos) {
        return new BlockPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    static Set<RelativeFlag> relatives(Set<Relative> relatives) {
        EnumSet<RelativeFlag> out = EnumSet.noneOf(RelativeFlag.class);
        for (Relative relative : relatives) {
            out.add(RELATIVE.map(relative));
        }
        return out;
    }

    private static String key(Holder<?> holder) {
        return holder.unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
    }
}
