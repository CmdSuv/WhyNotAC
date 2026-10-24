package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The inventory packets (and an entity's equipment) translate to the same records from the game's objects and from
 * the bytes the client receives: item, count, the component patch (types sorted) and a patched attack range; a click's
 * hashed stacks keep the item, count and patch types, never values.
 */
class InventoryPacketViewsTest {

    private static RegistryAccess registries;
    private final PacketTranslator translator = new PacketTranslator();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    /**
     * Paper's item encoder needs a running server (it sanitizes components): the test writes the stacks
     * itself, as the vanilla codec does (count, item, component patch), and the client's codec reads them.
     */
    private static RegistryFriendlyByteBuf bytes() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
    }

    private static void writeStack(RegistryFriendlyByteBuf buf, ItemStack stack) {
        buf.writeVarInt(stack.getCount());
        if (stack.isEmpty()) {
            return;
        }
        Item.STREAM_CODEC.encode(buf, stack.getItemHolder());
        // DataComponentPatch.STREAM_CODEC: added count, removed count, then each added type with its value, each removed type.
        DataComponentPatch patch = stack.getComponentsPatch();
        List<java.util.Map.Entry<DataComponentType<?>, java.util.Optional<?>>> entries = List.copyOf(patch.entrySet());
        buf.writeVarInt((int) entries.stream().filter(e -> e.getValue().isPresent()).count());
        buf.writeVarInt((int) entries.stream().filter(e -> e.getValue().isEmpty()).count());
        for (java.util.Map.Entry<DataComponentType<?>, java.util.Optional<?>> entry : entries) {
            if (entry.getValue().isPresent()) {
                DataComponentType.STREAM_CODEC.encode(buf, entry.getKey());
                writeValue(buf, entry.getKey(), entry.getValue().get());
            }
        }
        for (java.util.Map.Entry<DataComponentType<?>, java.util.Optional<?>> entry : entries) {
            if (entry.getValue().isEmpty()) {
                DataComponentType.STREAM_CODEC.encode(buf, entry.getKey());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void writeValue(RegistryFriendlyByteBuf buf, DataComponentType<T> type, Object value) {
        type.streamCodec().encode(buf, (T) value);
    }

    private static <P> P decoded(RegistryFriendlyByteBuf buf, net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, P> codec) {
        P packet = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "the codec read every byte");
        return packet;
    }

    private ClientboundPacket bothViews(Packet<? super ClientGamePacketListener> packet, Packet<?> received) {
        ClientboundPacket game = translator.outbound(packet, false);
        ClientboundPacket wire = translator.outbound(received, true);
        assertEquals(WireObserver.canonical(game), WireObserver.canonical(wire), "views agree on " + packet);
        return wire;
    }

    private ClientboundPacket setSlot(int container, int state, int slot, ItemStack stack) {
        RegistryFriendlyByteBuf buf = bytes();
        buf.writeContainerId(container);
        buf.writeVarInt(state);
        buf.writeShort(slot);
        writeStack(buf, stack);
        return bothViews(new ClientboundContainerSetSlotPacket(container, state, slot, stack), decoded(buf, ClientboundContainerSetSlotPacket.STREAM_CODEC));
    }

    private ClientboundPacket setContent(int container, int state, List<ItemStack> items, ItemStack carried) {
        RegistryFriendlyByteBuf buf = bytes();
        buf.writeContainerId(container);
        buf.writeVarInt(state);
        buf.writeVarInt(items.size());
        items.forEach(item -> writeStack(buf, item));
        writeStack(buf, carried);
        return bothViews(new ClientboundContainerSetContentPacket(container, state, items, carried),
                decoded(buf, ClientboundContainerSetContentPacket.STREAM_CODEC));
    }

    private ClientboundPacket cursor(ItemStack stack) {
        RegistryFriendlyByteBuf buf = bytes();
        writeStack(buf, stack);
        return bothViews(new ClientboundSetCursorItemPacket(stack), decoded(buf, ClientboundSetCursorItemPacket.STREAM_CODEC));
    }

    private ClientboundPacket playerInventory(int slot, ItemStack stack) {
        RegistryFriendlyByteBuf buf = bytes();
        buf.writeVarInt(slot);
        writeStack(buf, stack);
        return bothViews(new ClientboundSetPlayerInventoryPacket(slot, stack), decoded(buf, ClientboundSetPlayerInventoryPacket.STREAM_CODEC));
    }

    /** A stick that reaches like a weapon and cannot be stacked: two patched types, one cleared. */
    private static ItemStack reachStick() {
        ItemStack stick = new ItemStack(Items.STICK, 1);
        stick.set(DataComponents.ATTACK_RANGE, new AttackRange(0.5F, 4.25F, 0.0F, 6.0F, 0.125F, 0.75F));
        stick.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("reach"));
        stick.remove(DataComponents.MAX_STACK_SIZE);
        return stick;
    }

    /** The model of {@link #reachStick()} (its hash needs the bootstrapped game). */
    private static Stack reachStickModel() {
        return new Stack("minecraft:stick", 1, List.of("minecraft:attack_range", "minecraft:custom_name"),
            List.of("minecraft:max_stack_size"), new Stack.AttackRange(0.5F, 4.25F, 0.0F, 6.0F, 0.125F, 0.75F), false,
            PacketTranslator.rangeHash(new AttackRange(0.5F, 4.25F, 0.0F, 6.0F, 0.125F, 0.75F)));
    }

    @Test
    void stacksKeepTheirItemCountAndPatch() {
        assertEquals(new ClientboundPacket.ContainerSetSlot(0, 7, 36, reachStickModel()), setSlot(0, 7, 36, reachStick()));
        Stack dirt = new Stack("minecraft:dirt", 12, List.of(), List.of(), null, false);
        assertEquals(new ClientboundPacket.ContainerSetContent(3, 9, List.of(Stack.EMPTY, dirt, reachStickModel()), Stack.EMPTY),
                setContent(3, 9, List.of(ItemStack.EMPTY, new ItemStack(Items.DIRT, 12), reachStick()), ItemStack.EMPTY));
        assertEquals(new ClientboundPacket.SetCursorItem(dirt), cursor(new ItemStack(Items.DIRT, 12)));
        assertEquals(new ClientboundPacket.SetPlayerInventory(40, reachStickModel()), playerInventory(40, reachStick()));
        assertEquals(new ClientboundPacket.SetHeldSlot(4), translator.outbound(new ClientboundSetHeldSlotPacket(4), true));
    }

    /** {@code ClientboundSetEquipmentPacket}: the entity, then each slot's ordinal (the high bit: more follow) and its stack. */
    @Test
    void equipmentKeepsTheEntityTheSlotsAndTheirStacks() {
        List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> slots = List.of(
                com.mojang.datafixers.util.Pair.of(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS)),
                com.mojang.datafixers.util.Pair.of(EquipmentSlot.MAINHAND, reachStick()), com.mojang.datafixers.util.Pair.of(EquipmentSlot.HEAD, ItemStack.EMPTY));
        RegistryFriendlyByteBuf buf = bytes();
        buf.writeVarInt(42);
        for (int i = 0; i < slots.size(); i++) {
            int ordinal = slots.get(i).getFirst().ordinal();
            buf.writeByte(i != slots.size() - 1 ? ordinal | -128 : ordinal);
            writeStack(buf, slots.get(i).getSecond());
        }
        ClientboundPacket wire = bothViews(new ClientboundSetEquipmentPacket(42, slots), decoded(buf, ClientboundSetEquipmentPacket.STREAM_CODEC));
        assertEquals(new ClientboundPacket.SetEquipment(42, List.of(
                new ClientboundPacket.SetEquipment.Slot("feet", new Stack("minecraft:leather_boots", 1, List.of(), List.of(), null, false)),
                new ClientboundPacket.SetEquipment.Slot("mainhand", reachStickModel()), new ClientboundPacket.SetEquipment.Slot("head", Stack.EMPTY))), wire);
    }

    @Test
    void aClickKeepsWhatTheClientPredictedWithoutValues() {
        Int2ObjectOpenHashMap<HashedStack> changed = new Int2ObjectOpenHashMap<>();
        changed.put(38, HashedStack.create(reachStick(), component -> 17));
        changed.put(12, HashedStack.EMPTY);
        ServerboundContainerClickPacket click = new ServerboundContainerClickPacket(0, 5, (short) 12, (byte) 2, ClickType.SWAP, changed,
                HashedStack.create(new ItemStack(Items.DIRT, 3), component -> 0));
        Stack hashedStick = new Stack("minecraft:stick", 1, List.of("minecraft:attack_range", "minecraft:custom_name"),
                List.of("minecraft:max_stack_size"), null, true, 17);
        assertEquals(new ServerboundPacket.ContainerClick(0, 5, 12, 2, "SWAP",
                        List.of(new ServerboundPacket.ContainerClick.ChangedSlot(12, Stack.EMPTY), new ServerboundPacket.ContainerClick.ChangedSlot(38, hashedStick)),
                        new Stack("minecraft:dirt", 3, List.of(), List.of(), null, true)),
                translator.inbound(click));
    }

    /**
     * The client hashes each component of a clicked stack with {@code ClientPacketListener.decoratedHashOpsGenerator}
     * (CRC32C over the registry-aware encoding); the translator's hash of a full stack's attack range is that
     * same number, so a hashed stack resolves to the range a full stack showed.
     */
    @Test
    void theRangeHashIsTheClientsHashOfTheComponent() {
        net.minecraft.resources.RegistryOps<com.google.common.hash.HashCode> ops = registries.createSerializationContext(net.minecraft.util.HashOps.CRC32C_INSTANCE);
        net.minecraft.network.HashedPatchMap.HashGenerator client = typed -> typed.encodeValue(ops).getOrThrow().asInt();
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 200; i++) {
            AttackRange range = i == 0 ? new AttackRange(0F, 3F, 0F, 5F, 0.3F, 1F)
                    : new AttackRange(random.nextFloat() * 64F, random.nextFloat() * 64F, random.nextFloat() * 64F, random.nextFloat() * 64F,
                            random.nextFloat(), random.nextFloat() * 2F);
            ItemStack stick = new ItemStack(Items.STICK);
            stick.set(DataComponents.ATTACK_RANGE, range);
            Stack full = PacketTranslator.stack(stick);
            Int2ObjectOpenHashMap<HashedStack> changed = new Int2ObjectOpenHashMap<>();
            changed.put(36, HashedStack.create(stick, client));
            ServerboundPacket.ContainerClick click = (ServerboundPacket.ContainerClick) translator.inbound(
                    new ServerboundContainerClickPacket(0, 1, (short) 36, (byte) 0, ClickType.PICKUP, changed, HashedStack.EMPTY));
            Stack hashed = click.changed().getFirst().item();
            assertEquals(full.rangeHash(), hashed.rangeHash(), range.toString());
            assertEquals(client.apply(new net.minecraft.core.component.TypedDataComponent<>(DataComponents.ATTACK_RANGE, range)),
                    PacketTranslator.rangeHash(range));
        }
    }

    /** The player's own entity event 9 finishes the item in use: the client's decoded packet gives the id and the event. */
    @Test
    void anEntityEventKeepsItsEntityAndEvent() {
        RegistryFriendlyByteBuf buf = bytes();
        buf.writeInt(1234);
        buf.writeByte(9);
        net.minecraft.network.protocol.game.ClientboundEntityEventPacket packet =
                net.minecraft.network.protocol.game.ClientboundEntityEventPacket.STREAM_CODEC.decode(buf);
        assertEquals(new ClientboundPacket.EntityEvent(1234, ClientboundPacket.EntityEvent.FINISH_USING_ITEM), translator.outbound(packet, true));
    }

    /** The camera packet's entity id, read from the client's decoded packet. */
    @Test
    void aCameraKeepsItsEntity() {
        RegistryFriendlyByteBuf buf = bytes();
        buf.writeVarInt(4321);
        net.minecraft.network.protocol.game.ClientboundSetCameraPacket packet =
                net.minecraft.network.protocol.game.ClientboundSetCameraPacket.STREAM_CODEC.decode(buf);
        assertEquals(new ClientboundPacket.SetCamera(4321), translator.outbound(packet, true));
    }
}
