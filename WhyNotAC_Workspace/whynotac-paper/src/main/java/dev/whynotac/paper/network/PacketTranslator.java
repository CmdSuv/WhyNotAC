package dev.whynotac.paper.network;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
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
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
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

    private static final VarHandle ADD_ENTITY_X_ROT;
    private static final VarHandle ADD_ENTITY_Y_ROT;
    private static final VarHandle ADD_ENTITY_Y_HEAD_ROT;
    private static final VarHandle MOVE_ENTITY_ID;
    private static final VarHandle MOVE_ENTITY_Y_ROT;
    private static final VarHandle MOVE_ENTITY_X_ROT;
    private static final VarHandle ROTATE_HEAD_ID;
    private static final VarHandle ENTITY_EVENT_ID;
    private static final VarHandle SET_CAMERA_ID;
    private static final VarHandle ROTATE_HEAD_Y_HEAD_ROT;

    private static final VarHandle SECTION_POS;
    private static final VarHandle SECTION_POSITIONS;
    private static final VarHandle SECTION_STATES;
    /** {@code TagNetworkSerialization.NetworkPayload.tags}: package-private, no getter. */
    private static final VarHandle TAG_PAYLOAD_TAGS;

    static {
        try {
            GAME_EVENT_TYPE_ID = MethodHandles.privateLookupIn(ClientboundGameEventPacket.Type.class, MethodHandles.lookup())
                    .findVarHandle(ClientboundGameEventPacket.Type.class, "id", int.class);
            MethodHandles.Lookup section = MethodHandles.privateLookupIn(ClientboundSectionBlocksUpdatePacket.class, MethodHandles.lookup());
            SECTION_POS = section.findVarHandle(ClientboundSectionBlocksUpdatePacket.class, "sectionPos", SectionPos.class);
            SECTION_POSITIONS = section.findVarHandle(ClientboundSectionBlocksUpdatePacket.class, "positions", short[].class);
            SECTION_STATES = section.findVarHandle(ClientboundSectionBlocksUpdatePacket.class, "states", BlockState[].class);
            // The client unpacks angles from these bytes; the getters return floats it never sees.
            MethodHandles.Lookup add = MethodHandles.privateLookupIn(ClientboundAddEntityPacket.class, MethodHandles.lookup());
            ADD_ENTITY_X_ROT = add.findVarHandle(ClientboundAddEntityPacket.class, "xRot", byte.class);
            ADD_ENTITY_Y_ROT = add.findVarHandle(ClientboundAddEntityPacket.class, "yRot", byte.class);
            ADD_ENTITY_Y_HEAD_ROT = add.findVarHandle(ClientboundAddEntityPacket.class, "yHeadRot", byte.class);
            MethodHandles.Lookup move = MethodHandles.privateLookupIn(ClientboundMoveEntityPacket.class, MethodHandles.lookup());
            MOVE_ENTITY_ID = move.findVarHandle(ClientboundMoveEntityPacket.class, "entityId", int.class);
            MOVE_ENTITY_Y_ROT = move.findVarHandle(ClientboundMoveEntityPacket.class, "yRot", byte.class);
            MOVE_ENTITY_X_ROT = move.findVarHandle(ClientboundMoveEntityPacket.class, "xRot", byte.class);
            MethodHandles.Lookup head = MethodHandles.privateLookupIn(ClientboundRotateHeadPacket.class, MethodHandles.lookup());
            ROTATE_HEAD_ID = head.findVarHandle(ClientboundRotateHeadPacket.class, "entityId", int.class);
            ENTITY_EVENT_ID = MethodHandles.privateLookupIn(ClientboundEntityEventPacket.class, MethodHandles.lookup())
                    .findVarHandle(ClientboundEntityEventPacket.class, "entityId", int.class);
            SET_CAMERA_ID = MethodHandles.privateLookupIn(ClientboundSetCameraPacket.class, MethodHandles.lookup())
                    .findVarHandle(ClientboundSetCameraPacket.class, "cameraId", int.class);
            ROTATE_HEAD_Y_HEAD_ROT = head.findVarHandle(ClientboundRotateHeadPacket.class, "yHeadRot", byte.class);
            TAG_PAYLOAD_TAGS = MethodHandles.privateLookupIn(net.minecraft.tags.TagNetworkSerialization.NetworkPayload.class, MethodHandles.lookup())
                    .findVarHandle(net.minecraft.tags.TagNetworkSerialization.NetworkPayload.class, "tags", Map.class);
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
    static final EnumMapping<Pose, ClientboundPacket.EntityPose> POSE =
            new EnumMapping<>(Pose.class, ClientboundPacket.EntityPose.class, ClientboundPacket.EntityPose.UNKNOWN);
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
            "set_carried_item", "swing", "use_item", "use_item_on", "set_creative_mode_slot");

    /** Packet type paths that {@link #outbound} turns into a dedicated model record (see {@link #SERVERBOUND_MODELLED}). */
    public static final Set<String> CLIENTBOUND_MODELLED = Set.of(
            "explode", "game_event", "keep_alive", "login", "ping", "player_abilities", "player_position", "player_rotation",
            "remove_mob_effect", "respawn", "set_entity_motion", "teleport_entity", "update_attributes", "update_mob_effect",
            "level_chunk_with_light", "forget_level_chunk", "block_update", "section_blocks_update", "block_changed_ack",
            "set_chunk_cache_center", "set_chunk_cache_radius", "ticking_state", "ticking_step", "player_info_update",
            "player_info_remove",
            "add_entity", "remove_entities", "move_entity_pos", "move_entity_pos_rot", "move_entity_rot", "entity_position_sync",
            "rotate_head", "set_passengers", "move_vehicle", "set_entity_data",
            "container_set_content", "container_set_slot", "set_cursor_item", "set_player_inventory", "set_held_slot", "open_screen",
            "mount_screen_open", "container_close", "entity_event", "set_camera", "set_health", "initialize_border", "set_border_center",
            "set_border_lerp_size", "set_border_size", "set_player_team", "update_tags");

    /**
     * Packets about other entities that come by the hundred per tick: the edge decodes them (the client's
     * view is what the replicas read), while the game's view only notes their type, and the two views are
     * reconciled by type and order. None of them ever needs a physics bracket ({@code TransactionPolicy}).
     */
    public static final Set<String> RECONCILED_BY_TYPE = Set.of("minecraft:move_entity_pos", "minecraft:move_entity_pos_rot",
            "minecraft:move_entity_rot", "minecraft:rotate_head", "minecraft:set_entity_data", "minecraft:entity_position_sync");

    private static final Map<String, ClientboundPacket.Unmodeled> TYPE_MARKERS = new ConcurrentHashMap<>();

    /** The game's view of a {@link #RECONCILED_BY_TYPE} packet: its type alone, one shared marker per type. */
    public static ClientboundPacket.Unmodeled typeMarker(String type) {
        return TYPE_MARKERS.computeIfAbsent(type, ClientboundPacket.Unmodeled::new);
    }

    /** The packet classes {@link #outbound} has a case for (keep in step with its switch). */
    private static final List<Class<?>> OUTBOUND_CLASSES = List.of(
            ClientboundPingPacket.class, ClientboundKeepAlivePacket.class, ClientboundSetEntityMotionPacket.class, ClientboundExplodePacket.class,
            ClientboundPlayerPositionPacket.class, ClientboundTeleportEntityPacket.class, ClientboundPlayerRotationPacket.class,
            ClientboundUpdateAttributesPacket.class, ClientboundUpdateMobEffectPacket.class, ClientboundRemoveMobEffectPacket.class,
            ClientboundPlayerAbilitiesPacket.class, ClientboundGameEventPacket.class, ClientboundLoginPacket.class, ClientboundRespawnPacket.class,
            ClientboundLevelChunkWithLightPacket.class, ClientboundForgetLevelChunkPacket.class, ClientboundBlockUpdatePacket.class,
            ClientboundSectionBlocksUpdatePacket.class, ClientboundBlockChangedAckPacket.class, ClientboundSetChunkCacheCenterPacket.class,
            ClientboundSetChunkCacheRadiusPacket.class, ClientboundAddEntityPacket.class, ClientboundRemoveEntitiesPacket.class,
            ClientboundMoveEntityPacket.class, ClientboundEntityPositionSyncPacket.class, ClientboundRotateHeadPacket.class,
            ClientboundSetPassengersPacket.class, ClientboundMoveVehiclePacket.class, ClientboundSetEntityDataPacket.class,
            ClientboundTickingStatePacket.class, ClientboundTickingStepPacket.class, ClientboundPlayerInfoUpdatePacket.class,
            ClientboundPlayerInfoRemovePacket.class, ClientboundContainerSetContentPacket.class,
            ClientboundContainerSetSlotPacket.class, ClientboundSetCursorItemPacket.class,
            ClientboundSetPlayerInventoryPacket.class, ClientboundSetHeldSlotPacket.class,
            ClientboundOpenScreenPacket.class, ClientboundMountScreenOpenPacket.class,
            ClientboundUpdateTagsPacket.class,
            ClientboundContainerClosePacket.class, ClientboundEntityEventPacket.class, ClientboundSetCameraPacket.class,
            ClientboundSetHealthPacket.class, ClientboundInitializeBorderPacket.class, ClientboundSetBorderCenterPacket.class,
            ClientboundSetBorderLerpSizePacket.class, ClientboundSetBorderSizePacket.class, ClientboundSetPlayerTeamPacket.class);

    /** Per packet class, computed once: is it (a subclass of) one {@link #outbound} models? */
    static final ClassValue<Boolean> OUTBOUND_MODELLED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> modelled : OUTBOUND_CLASSES) {
                if (modelled.isAssignableFrom(type)) {
                    return true;
                }
            }
            return false;
        }
    };

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
            case ServerboundContainerClickPacket click -> containerClick(click);
            case ServerboundSetCreativeModeSlotPacket creative -> new ServerboundPacket.SetCreativeModeSlot(
                    creative.slotNum(), stack(creative.itemStack()));
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

    /** The click with the slots the client changed itself and its carried stack, as it hashed them, in slot order. */
    private static ServerboundPacket.ContainerClick containerClick(ServerboundContainerClickPacket click) {
        int[] slots = click.changedSlots().keySet().toIntArray();
        java.util.Arrays.sort(slots);
        List<ServerboundPacket.ContainerClick.ChangedSlot> changed = new ArrayList<>(slots.length);
        for (int slot : slots) {
            changed.add(new ServerboundPacket.ContainerClick.ChangedSlot(slot, stack(click.changedSlots().get(slot))));
        }
        return new ServerboundPacket.ContainerClick(click.containerId(), click.stateId(), click.slotNum(), click.buttonNum(), click.clickType().name(),
                changed, stack(click.carriedItem()));
    }

    /**
     * An item stack as the client receives it: item, count, and its component patch (types sorted by key, so
     * the game's object and the decoded bytes give the same list); the attack range when the patch sets one.
     */
    public static Stack stack(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return Stack.EMPTY;
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        Stack.AttackRange range = null;
        Integer hash = null;
        for (Map.Entry<net.minecraft.core.component.DataComponentType<?>, java.util.Optional<?>> entry : stack.getComponentsPatch().entrySet()) {
            String type = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey()).toString();
            if (entry.getValue().isEmpty()) {
                removed.add(type);
                continue;
            }
            added.add(type);
            if (entry.getValue().get() instanceof net.minecraft.world.item.component.AttackRange r) {
                range = new Stack.AttackRange(r.minRange(), r.maxRange(), r.minCreativeRange(), r.maxCreativeRange(), r.hitboxMargin(), r.mobFactor());
                hash = rangeHash(r);
            }
        }
        added.sort(null);
        removed.sort(null);
        return new Stack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), added, removed,
                range, false, hash);
    }

    /**
     * The client's hash of an {@code attack_range} value, as {@code HashedPatchMap} computes it for a click
     * ({@code ClientPacketListener.decoratedHashOpsGenerator}: CRC32C over the component's encoding). The
     * value holds no registry element, so the registry context the client adds changes nothing.
     */
    public static int rangeHash(net.minecraft.world.item.component.AttackRange value) {
        return net.minecraft.core.component.DataComponents.ATTACK_RANGE.codecOrThrow()
                .encodeStart(net.minecraft.util.HashOps.CRC32C_INSTANCE, value).getOrThrow().asInt();
    }

    /** A stack the client hashed: item, count and the patch's types; no component values. */
    static Stack stack(net.minecraft.network.HashedStack hashed) {
        if (!(hashed instanceof net.minecraft.network.HashedStack.ActualItem item)) {
            return Stack.EMPTY;
        }
        List<String> added = new ArrayList<>();
        for (net.minecraft.core.component.DataComponentType<?> type : item.components().addedComponents().keySet()) {
            added.add(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString());
        }
        List<String> removed = new ArrayList<>();
        for (net.minecraft.core.component.DataComponentType<?> type : item.components().removedComponents()) {
            removed.add(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString());
        }
        added.sort(null);
        removed.sort(null);
        return new Stack(key(item.item()), item.count(), added, removed, null, true,
                item.components().addedComponents().get(net.minecraft.core.component.DataComponents.ATTACK_RANGE));
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

    /**
     * Every registry's tags as the payload holds them, registries and tags in name order (the packet's maps have none:
     * both views then translate it alike), each tag's ids as sent.
     */
    @SuppressWarnings("unchecked")
    static ClientboundPacket.UpdateTags updateTags(ClientboundUpdateTagsPacket packet) {
        List<ClientboundPacket.UpdateTags.RegistryTags> registries = new ArrayList<>(packet.getTags().size());
        for (Map.Entry<net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<?>>, net.minecraft.tags.TagNetworkSerialization.NetworkPayload> entry
                : packet.getTags().entrySet()) {
            Map<net.minecraft.resources.Identifier, it.unimi.dsi.fastutil.ints.IntList> payload =
                    (Map<net.minecraft.resources.Identifier, it.unimi.dsi.fastutil.ints.IntList>) TAG_PAYLOAD_TAGS.get(entry.getValue());
            List<ClientboundPacket.UpdateTags.Tag> tags = new ArrayList<>(payload.size());
            for (Map.Entry<net.minecraft.resources.Identifier, it.unimi.dsi.fastutil.ints.IntList> tag : payload.entrySet()) {
                tags.add(new ClientboundPacket.UpdateTags.Tag(tag.getKey().toString(), tag.getValue().toIntArray()));
            }
            tags.sort(java.util.Comparator.comparing(ClientboundPacket.UpdateTags.Tag::name));
            registries.add(new ClientboundPacket.UpdateTags.RegistryTags(entry.getKey().identifier().toString(), tags));
        }
        registries.sort(java.util.Comparator.comparing(ClientboundPacket.UpdateTags.RegistryTags::registry));
        return new ClientboundPacket.UpdateTags(registries);
    }

    public ClientboundPacket outbound(Packet<?> packet) {
        return outbound(packet, false);
    }

    /**
     * {@code fromWire}: the packet object was decoded from the bytes the client received (network edge),
     * so lossy fields already hold the client's values; otherwise it is the game's object before encoding.
     */
    public ClientboundPacket outbound(Packet<?> packet, boolean fromWire) {
        if (!OUTBOUND_MODELLED.get(packet.getClass())) {
            // Most outbound traffic: skip the type switch, which tests every case in turn (JDK 21 typeSwitch).
            return unmodeledOutbound.computeIfAbsent(packet.getClass(), c -> new ClientboundPacket.Unmodeled(packet.type().id().toString()));
        }
        // The type switch tests its cases in turn (JDK 21 typeSwitch): the most frequent come first.
        return switch (packet) {
            case ClientboundMoveEntityPacket move -> new ClientboundPacket.MoveEntity((int) MOVE_ENTITY_ID.get(move),
                    move.getXa(), move.getYa(), move.getZa(), (byte) MOVE_ENTITY_Y_ROT.get(move), (byte) MOVE_ENTITY_X_ROT.get(move),
                    move.isOnGround(), move.hasPosition(), move.hasRotation());
            case ClientboundRotateHeadPacket head -> new ClientboundPacket.RotateHead((int) ROTATE_HEAD_ID.get(head),
                    (byte) ROTATE_HEAD_Y_HEAD_ROT.get(head));
            case ClientboundSetEntityDataPacket data -> entityData(data);
            case ClientboundEntityPositionSyncPacket sync -> new ClientboundPacket.EntityPositionSync(sync.id(),
                    vec(sync.values().position()), vec(sync.values().deltaMovement()), sync.values().yRot(), sync.values().xRot(), sync.onGround());
            case ClientboundSetEntityMotionPacket motion -> fromWire
                    ? ClientboundPacket.SetEntityMotion.received(motion.getId(), vec(motion.getMovement()))
                    : ClientboundPacket.SetEntityMotion.sent(motion.getId(), vec(motion.getMovement()));
            case ClientboundPingPacket ping -> new ClientboundPacket.Ping(ping.getId());
            case ClientboundKeepAlivePacket keepAlive -> new ClientboundPacket.KeepAlive(keepAlive.getId());
            case ClientboundTeleportEntityPacket teleport -> {
                PositionMoveRotation change = teleport.change();
                yield new ClientboundPacket.TeleportEntity(teleport.id(), vec(change.position()), vec(change.deltaMovement()), change.yRot(),
                        change.xRot(), relatives(teleport.relatives()), teleport.onGround());
            }
            case ClientboundAddEntityPacket add -> addEntity(add, fromWire);
            case ClientboundRemoveEntitiesPacket remove -> new ClientboundPacket.RemoveEntities(remove.getEntityIds().toIntArray());
            case ClientboundUpdateAttributesPacket attributes -> attributes(attributes);
            case ClientboundBlockUpdatePacket update -> new ClientboundPacket.BlockUpdate(position(update.getPos()), Block.getId(update.getBlockState()));
            case ClientboundSectionBlocksUpdatePacket update -> sectionUpdate(update);
            // The game's view has no bytes yet; see levelChunk(ByteBuf) for the edge and withBody for the game-only view.
            case ClientboundLevelChunkWithLightPacket chunk -> new ClientboundPacket.LevelChunk(chunk.getX(), chunk.getZ(), null);
            case ClientboundForgetLevelChunkPacket forget -> new ClientboundPacket.ForgetLevelChunk(forget.pos().x, forget.pos().z);
            case ClientboundSetChunkCacheCenterPacket center -> new ClientboundPacket.ChunkCacheCenter(center.getX(), center.getZ());
            case ClientboundBlockChangedAckPacket ack -> new ClientboundPacket.BlockChangedAck(ack.sequence());
            case ClientboundSetPassengersPacket passengers -> new ClientboundPacket.SetPassengers(passengers.getVehicle(),
                    passengers.getPassengers().clone());
            case ClientboundMoveVehiclePacket vehicle -> new ClientboundPacket.MoveVehicle(vec(vehicle.position()), vehicle.yRot(), vehicle.xRot());
            case ClientboundPlayerInfoUpdatePacket info -> playerInfo(info);
            case ClientboundPlayerInfoRemovePacket remove -> new ClientboundPacket.PlayerInfoRemove(remove.profileIds());
            case ClientboundUpdateMobEffectPacket effect -> new ClientboundPacket.MobEffect(
                    effect.getEntityId(), key(effect.getEffect()), effect.getEffectAmplifier(), effect.getEffectDurationTicks(),
                    (effect.isEffectAmbient() ? 1 : 0) | (effect.isEffectVisible() ? 2 : 0) | (effect.effectShowsIcon() ? 4 : 0) | (effect.shouldBlend() ? 8 : 0));
            case ClientboundRemoveMobEffectPacket remove -> new ClientboundPacket.RemoveMobEffect(remove.entityId(), key(remove.effect()));
            case ClientboundExplodePacket explode -> new ClientboundPacket.Explosion(
                    vec(explode.center()), explode.radius(), explode.playerKnockback().map(PacketTranslator::vec).orElse(null));
            case ClientboundPlayerPositionPacket position -> new ClientboundPacket.PlayerPosition(
                    position.id(), vec(position.change().position()), vec(position.change().deltaMovement()),
                    position.change().yRot(), position.change().xRot(), relatives(position.relatives()));
            case ClientboundPlayerRotationPacket rotation -> new ClientboundPacket.PlayerRotation(
                    rotation.yRot(), rotation.relativeY(), rotation.xRot(), rotation.relativeX());
            case ClientboundPlayerAbilitiesPacket abilities -> new ClientboundPacket.PlayerAbilities(
                    abilities.isInvulnerable(), abilities.isFlying(), abilities.canFly(), abilities.canInstabuild(),
                    abilities.getFlyingSpeed(), abilities.getWalkingSpeed());
            case ClientboundGameEventPacket event -> new ClientboundPacket.GameEvent((int) GAME_EVENT_TYPE_ID.get(event.getEvent()), event.getParam());
            case ClientboundLoginPacket login -> new ClientboundPacket.Login(
                    login.playerId(), login.chunkRadius(), dimension(login.commonPlayerSpawnInfo()), login.commonPlayerSpawnInfo().gameType().getId());
            case ClientboundRespawnPacket respawn -> new ClientboundPacket.Respawn(
                    dimension(respawn.commonPlayerSpawnInfo()), respawn.commonPlayerSpawnInfo().gameType().getId(),
                    (respawn.shouldKeep(ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS) ? 1 : 0)
                            | (respawn.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA) ? 2 : 0));
            case ClientboundSetChunkCacheRadiusPacket radius -> new ClientboundPacket.ChunkCacheRadius(radius.getRadius());
            case ClientboundTickingStatePacket state -> new ClientboundPacket.TickingState(state.tickRate(), state.isFrozen());
            case ClientboundTickingStepPacket step -> new ClientboundPacket.TickingStep(step.tickSteps());
            case ClientboundContainerSetSlotPacket slot -> new ClientboundPacket.ContainerSetSlot(
                    slot.getContainerId(), slot.getStateId(), slot.getSlot(), stack(slot.getItem()));
            case ClientboundContainerSetContentPacket content -> {
                List<Stack> items = new ArrayList<>(content.items().size());
                for (net.minecraft.world.item.ItemStack item : content.items()) {
                    items.add(stack(item));
                }
                yield new ClientboundPacket.ContainerSetContent(content.containerId(), content.stateId(), items, stack(content.carriedItem()));
            }
            case ClientboundSetCursorItemPacket cursor -> new ClientboundPacket.SetCursorItem(stack(cursor.contents()));
            case ClientboundSetPlayerInventoryPacket inventory -> new ClientboundPacket.SetPlayerInventory(
                    inventory.slot(), stack(inventory.contents()));
            case ClientboundSetHeldSlotPacket held -> new ClientboundPacket.SetHeldSlot(held.slot());
            case ClientboundEntityEventPacket event -> new ClientboundPacket.EntityEvent((int) ENTITY_EVENT_ID.get(event), event.getEventId());
            case ClientboundSetCameraPacket camera -> new ClientboundPacket.SetCamera((int) SET_CAMERA_ID.get(camera));
            case ClientboundSetHealthPacket health -> new ClientboundPacket.SetHealth(health.getHealth(), health.getFood(), health.getSaturation());
            case ClientboundInitializeBorderPacket border -> new ClientboundPacket.InitializeBorder(border.getNewCenterX(), border.getNewCenterZ(),
                    border.getOldSize(), border.getNewSize(), border.getLerpTime(), border.getNewAbsoluteMaxSize());
            case ClientboundSetBorderCenterPacket border -> new ClientboundPacket.SetBorderCenter(border.getNewCenterX(), border.getNewCenterZ());
            case ClientboundSetBorderLerpSizePacket border -> new ClientboundPacket.SetBorderLerpSize(border.getOldSize(), border.getNewSize(),
                    border.getLerpTime());
            case ClientboundSetBorderSizePacket border -> new ClientboundPacket.SetBorderSize(border.getSize());
            case ClientboundOpenScreenPacket open -> new ClientboundPacket.OpenScreen(open.getContainerId(),
                    String.valueOf(net.minecraft.core.registries.BuiltInRegistries.MENU.getKey(open.getType())));
            case ClientboundMountScreenOpenPacket mount -> new ClientboundPacket.MountScreenOpen(
                    mount.getContainerId(), mount.getInventoryColumns(), mount.getEntityId());
            case ClientboundContainerClosePacket close -> new ClientboundPacket.ContainerClose(close.getContainerId());
            case ClientboundSetPlayerTeamPacket team -> playerTeam(team, fromWire);
            case ClientboundUpdateTagsPacket tags -> updateTags(tags);
            default -> unmodeledOutbound.computeIfAbsent(packet.getClass(), c -> new ClientboundPacket.Unmodeled(packet.type().id().toString()));
        };
    }

    /**
     * A chunk packet exactly as the client receives it, from its frame body (after the packet id): the
     * two coordinates, then the remaining bytes copied unparsed. Nothing is decoded on the network thread.
     */
    public static ClientboundPacket.LevelChunk levelChunk(ByteBuf body) {
        int x = body.readInt();
        int z = body.readInt();
        byte[] rest = new byte[body.readableBytes()];
        body.readBytes(rest);
        return new ClientboundPacket.LevelChunk(x, z, rest);
    }

    /**
     * The game's chunk packet with its bytes, for the game-only view (no network edge): encoded with
     * the game's own codec, exactly as the encoder will. Null bytes if no server registry is available.
     */
    public static ClientboundPacket.LevelChunk withBody(ClientboundLevelChunkWithLightPacket packet) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return new ClientboundPacket.LevelChunk(packet.getX(), packet.getZ(), null);
        }
        ByteBuf buffer = Unpooled.buffer();
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(new RegistryFriendlyByteBuf(buffer, server.registryAccess()), packet);
            return levelChunk(buffer);
        } finally {
            buffer.release();
        }
    }

    private static ClientboundPacket.AddEntity addEntity(ClientboundAddEntityPacket add, boolean fromWire) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(add.getType()).toString();
        byte xRot = (byte) ADD_ENTITY_X_ROT.get(add);
        byte yRot = (byte) ADD_ENTITY_Y_ROT.get(add);
        byte yHeadRot = (byte) ADD_ENTITY_Y_HEAD_ROT.get(add);
        return fromWire
                ? ClientboundPacket.AddEntity.received(add.getId(), add.getUUID(), type, add.getX(), add.getY(), add.getZ(),
                        vec(add.getMovement()), xRot, yRot, yHeadRot, add.getData())
                : ClientboundPacket.AddEntity.sent(add.getId(), add.getUUID(), type, add.getX(), add.getY(), add.getZ(),
                        vec(add.getMovement()), xRot, yRot, yHeadRot, add.getData());
    }

    /** Which players the client learns about, and game modes; nothing else of the tab list (see the record). */
    static ClientboundPacket.PlayerInfoUpdate playerInfo(ClientboundPlayerInfoUpdatePacket packet) {
        boolean add = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
        boolean gameMode = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
        if (!add && !gameMode) {
            return new ClientboundPacket.PlayerInfoUpdate(false, false, List.of()); // latency, display names, ...
        }
        List<ClientboundPacket.PlayerInfoUpdate.Entry> entries = new ArrayList<>(packet.entries().size());
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            entries.add(new ClientboundPacket.PlayerInfoUpdate.Entry(entry.profileId(), gameMode ? entry.gameMode().getId() : -1));
        }
        return new ClientboundPacket.PlayerInfoUpdate(add, gameMode, entries);
    }

    /** The scalar values of a synched-data packet, exactly as the client decodes them; the others counted. */
    /**
     * The method from the packet's actions ({@code getTeamAction}: 0 ADD, 1 REMOVE; {@code getPlayerAction}: 3 ADD,
     * 4 REMOVE; neither: 2, a change of the parameters), the entries only for the methods that carry them.
     */
    static ClientboundPacket.SetPlayerTeam playerTeam(ClientboundSetPlayerTeamPacket packet, boolean fromWire) {
        ClientboundSetPlayerTeamPacket.Action team = packet.getTeamAction();
        ClientboundSetPlayerTeamPacket.Action player = packet.getPlayerAction();
        int method = team == ClientboundSetPlayerTeamPacket.Action.ADD ? ClientboundPacket.SetPlayerTeam.ADD
                : team == ClientboundSetPlayerTeamPacket.Action.REMOVE ? ClientboundPacket.SetPlayerTeam.REMOVE
                : player == ClientboundSetPlayerTeamPacket.Action.ADD ? ClientboundPacket.SetPlayerTeam.JOIN
                : player == ClientboundSetPlayerTeamPacket.Action.REMOVE ? ClientboundPacket.SetPlayerTeam.LEAVE
                : ClientboundPacket.SetPlayerTeam.CHANGE;
        String rule = packet.getParameters().map(p -> collisionRule(p.getCollisionRule(), fromWire)).orElse(null);
        boolean entries = method == ClientboundPacket.SetPlayerTeam.ADD || method == ClientboundPacket.SetPlayerTeam.JOIN
                || method == ClientboundPacket.SetPlayerTeam.LEAVE;
        return new ClientboundPacket.SetPlayerTeam(packet.getName(), method, rule, entries ? List.copyOf(packet.getPlayers()) : List.of());
    }

    /**
     * The rule the client receives. Paper writes {@code NEVER} for every team while player collisions are off
     * ({@code collisions.enable-player-collisions}); the game's object still holds the team's own.
     */
    private static String collisionRule(net.minecraft.world.scores.Team.CollisionRule rule, boolean fromWire) {
        if (!fromWire) {
            io.papermc.paper.configuration.GlobalConfiguration config = io.papermc.paper.configuration.GlobalConfiguration.get();
            if (config != null && !config.collisions.enablePlayerCollisions) {
                return "NEVER";
            }
        }
        return rule == null ? "UNKNOWN" : rule.name();
    }

    static ClientboundPacket.SetEntityData entityData(ClientboundSetEntityDataPacket packet) {
        List<ClientboundPacket.SetEntityData.DataEntry> entries = new ArrayList<>(packet.packedItems().size());
        int dropped = 0;
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            ClientboundPacket.SetEntityData.DataEntry entry = dataEntry(value);
            if (entry == null) {
                dropped++;
            } else {
                entries.add(entry);
            }
        }
        return new ClientboundPacket.SetEntityData(packet.id(), entries, dropped);
    }

    private static ClientboundPacket.SetEntityData.DataEntry dataEntry(SynchedEntityData.DataValue<?> data) {
        EntityDataSerializer<?> serializer = data.serializer();
        Object value = data.value();
        int index = data.id();
        if (serializer == EntityDataSerializers.BYTE) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.BYTE, (Byte) value);
        } else if (serializer == EntityDataSerializers.INT) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.INT, (Integer) value);
        } else if (serializer == EntityDataSerializers.LONG) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.LONG, (Long) value);
        } else if (serializer == EntityDataSerializers.FLOAT) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.FLOAT, Float.floatToRawIntBits((Float) value));
        } else if (serializer == EntityDataSerializers.BOOLEAN) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.BOOLEAN, (Boolean) value ? 1 : 0);
        } else if (serializer == EntityDataSerializers.POSE) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.POSE, POSE.map((Pose) value).ordinal());
        } else if (serializer == EntityDataSerializers.DIRECTION) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.DIRECTION, ((Direction) value).ordinal());
        } else if (serializer == EntityDataSerializers.OPTIONAL_UNSIGNED_INT) {
            java.util.OptionalInt optional = (java.util.OptionalInt) value;
            return entry(index, ClientboundPacket.SetEntityData.Kind.OPTIONAL_UNSIGNED_INT, optional.isPresent() ? optional.getAsInt() : -1);
        } else if (serializer == EntityDataSerializers.BLOCK_STATE) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.BLOCK_STATE, Block.getId((BlockState) value));
        } else if (serializer == EntityDataSerializers.OPTIONAL_BLOCK_STATE) {
            // The wire writes an empty optional as id 0 (air), so the client reads Optional.of(air) as empty too.
            @SuppressWarnings("unchecked")
            java.util.Optional<BlockState> optional = (java.util.Optional<BlockState>) value;
            int id = optional.map(Block::getId).orElse(0);
            return entry(index, ClientboundPacket.SetEntityData.Kind.OPTIONAL_BLOCK_STATE, id == 0 ? -1 : id);
        } else if (serializer == EntityDataSerializers.SNIFFER_STATE) {
            return entry(index, ClientboundPacket.SetEntityData.Kind.SNIFFER_STATE, ((net.minecraft.world.entity.animal.sniffer.Sniffer.State) value).id());
        }
        return null;
    }

    private static ClientboundPacket.SetEntityData.DataEntry entry(int index, ClientboundPacket.SetEntityData.Kind kind, long value) {
        return new ClientboundPacket.SetEntityData.DataEntry(index, kind, value);
    }

    private static ClientboundPacket.SectionBlocksUpdate sectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        SectionPos section = (SectionPos) SECTION_POS.get(packet);
        short[] positions = (short[]) SECTION_POSITIONS.get(packet);
        BlockState[] states = (BlockState[]) SECTION_STATES.get(packet);
        long[] entries = new long[positions.length];
        for (int i = 0; i < positions.length; i++) {
            entries[i] = (long) Block.getId(states[i]) << 12 | positions[i] & 0xFFF; // the wire encoding
        }
        return new ClientboundPacket.SectionBlocksUpdate(section.x(), section.y(), section.z(), entries);
    }

    private static ClientboundPacket.Dimension dimension(CommonPlayerSpawnInfo spawn) {
        DimensionType type = spawn.dimensionType().value();
        return new ClientboundPacket.Dimension(spawn.dimension().identifier().toString(), type.minY(), type.height(), fastLava(type));
    }

    /**
     * {@code gameplay/fast_lava} as the client reads it for the dimension ({@code EnvironmentAttributeSystem.getDimensionValue}):
     * the attribute's default through the type's own attributes (biome layers are positional, so not read); not known
     * when one of the type's timelines tracks it. The client builds the same from the synced type: the attribute syncs.
     */
    private static Boolean fastLava(DimensionType type) {
        for (Holder<net.minecraft.world.timeline.Timeline> timeline : type.timelines()) {
            if (timeline.value().attributes().contains(net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA)) {
                return null;
            }
        }
        net.minecraft.world.attribute.EnvironmentAttribute<Boolean> attribute = net.minecraft.world.attribute.EnvironmentAttributes.FAST_LAVA;
        return attribute.sanitizeValue(type.attributes().applyModifier(attribute, attribute.defaultValue()));
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
