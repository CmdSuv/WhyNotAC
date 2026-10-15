package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientInventoryTest {

    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final Stack SWORD = plain("minecraft:diamond_sword");
    private static final Stack SPEAR = plain("minecraft:iron_spear");
    private static final Stack SHIELD = plain("minecraft:shield");
    private static final Stack APPLE = plain("minecraft:golden_apple");
    private static final Stack.AttackRange LONG = new Stack.AttackRange(0F, 4.5F, 0F, 6.5F, 0.25F, 1F);
    private static final int LONG_HASH = 0x5eed1234;
    private static final Stack LONG_STICK = new Stack("minecraft:stick", 1, List.of(Stack.ATTACK_RANGE), List.of(), LONG, false, LONG_HASH);

    private ClientInventory inventory;
    private ClientInventory.Outcome outcome;
    private long sequence;
    private int pingId;

    @BeforeEach
    void setUp() {
        outcome = ClientInventory.Outcome.KEEP;
        inventory = new ClientInventory(ItemPrototypes.vanilla(), MenuLayouts.vanilla(), (stack, use) -> outcome);
        sequence = 0;
        out(new ClientboundPacket.Login(1, 8, OVERWORLD, 0));
        out(new ClientboundPacket.PlayerAbilities(false, false, false, false, 0.05F, 0.1F));
        out(new ClientboundPacket.SetHeldSlot(2));
        out(content(0, inventoryMenu(SHIELD, SWORD, SPEAR, APPLE), Stack.EMPTY));
        sync();
    }

    @Test
    void theLoginStateIsTheServersOnceProcessed() {
        ClientInventory.View view = only();
        assertEquals(SWORD, view.hotbar().get(0));
        assertEquals(SPEAR, view.hotbar().get(1));
        assertEquals(APPLE, view.hotbar().get(2));
        assertEquals(Stack.EMPTY, view.hotbar().get(3));
        assertEquals(SHIELD, view.offhand());
        assertEquals(2, view.selected());
        assertEquals(0, view.menuId());
    }

    @Test
    void anUnansweredSlotIsEitherSideOfItsProcessing() {
        ping();
        out(new ClientboundPacket.ContainerSetSlot(0, 2, 36 + 3, SWORD));
        Set<Stack> slot3 = new HashSet<>();
        for (ClientInventory.View view : inventory.views()) {
            slot3.add(view.hotbar().get(3));
        }
        // the ping before it is unanswered: the client may not have processed the slot
        assertEquals(Set.of(Stack.EMPTY), slot3);
        sync();
        assertEquals(SWORD, only().hotbar().get(3));
    }

    @Test
    void opsBetweenTheLastAnsweredAndTheFirstUnansweredPingAreAPrefix() {
        int first = ping();
        out(new ClientboundPacket.ContainerSetSlot(0, 2, 36 + 3, SWORD));
        out(new ClientboundPacket.ContainerSetSlot(0, 3, 36 + 4, SPEAR));
        int second = ping();
        ack(first);
        Set<List<Stack>> seen = new HashSet<>();
        for (ClientInventory.View view : inventory.views()) {
            seen.add(view.hotbar().subList(3, 5));
        }
        assertEquals(Set.of(List.of(Stack.EMPTY, Stack.EMPTY), List.of(SWORD, Stack.EMPTY), List.of(SWORD, SPEAR)), seen);
        ack(second);
        assertEquals(List.of(SWORD, SPEAR), only().hotbar().subList(3, 5));
    }

    @Test
    void aChestsHotbarSlotsAreItsLastNine() {
        out(new ClientboundPacket.OpenScreen(5, "minecraft:generic_9x3"));
        List<Stack> items = new ArrayList<>(Collections.nCopies(63, Stack.EMPTY));
        items.set(54, SPEAR);
        items.set(62, SWORD);
        out(content(5, items, Stack.EMPTY));
        out(new ClientboundPacket.ContainerSetSlot(5, 2, 55, APPLE));
        sync();
        ClientInventory.View view = only();
        assertEquals(5, view.menuId());
        assertEquals(SPEAR, view.hotbar().get(0));
        assertEquals(APPLE, view.hotbar().get(1));
        assertEquals(SWORD, view.hotbar().get(8));
        assertEquals(SHIELD, view.offhand()); // a chest has no off-hand slot
    }

    @Test
    void aCraftersResultSlotComesAfterTheHotbar() {
        out(new ClientboundPacket.OpenScreen(5, "minecraft:crafter_3x3"));
        out(new ClientboundPacket.ContainerSetSlot(5, 2, 36, SPEAR));
        out(new ClientboundPacket.ContainerSetSlot(5, 3, 45, SWORD)); // the result slot, not hotbar 8
        sync();
        ClientInventory.View view = only();
        assertEquals(SPEAR, view.hotbar().get(0));
        assertEquals(Stack.EMPTY, view.hotbar().get(8));
    }

    @Test
    void aShortContentListKeepsTheSlotsPastIt() {
        out(content(0, List.of(Stack.EMPTY, Stack.EMPTY), Stack.EMPTY));
        sync();
        assertEquals(SWORD, only().hotbar().get(0));
        assertEquals(SHIELD, only().offhand());
    }

    @Test
    void anotherMenusSlotsAreIgnored() {
        out(new ClientboundPacket.ContainerSetSlot(7, 2, 36, SPEAR)); // menu 7 is not open
        sync();
        assertEquals(SWORD, only().hotbar().get(0));
    }

    @Test
    void containerZeroIsTheInventoryMenuWhateverIsOpen() {
        out(new ClientboundPacket.OpenScreen(5, "minecraft:generic_9x1"));
        out(new ClientboundPacket.ContainerSetSlot(0, 2, 45, SPEAR));
        sync();
        assertEquals(SPEAR, only().offhand());
        assertEquals(5, only().menuId());
    }

    @Test
    void aClickWritesTheSlotsItChanged() {
        click(0, 36, "PICKUP", changed(36, Stack.EMPTY, 40, SWORD));
        ClientInventory.View view = only();
        assertEquals(Stack.EMPTY, view.hotbar().get(0));
        assertEquals(SWORD, view.hotbar().get(4));
    }

    @Test
    void aClickTellsWhichMenuIsOpen() {
        int first = ping();
        out(new ClientboundPacket.OpenScreen(5, "minecraft:generic_9x1"));
        ping();
        ack(first);
        assertEquals(Set.of(0, 5), menus());
        click(5, 36, "PICKUP", changed(36, Stack.EMPTY));
        assertEquals(Set.of(5), menus());
        assertEquals(Stack.EMPTY, inventory.views().getFirst().hotbar().get(0));
    }

    @Test
    void anOffhandSwapInAMenuWithoutTheOffhandIsNotKnown() {
        out(new ClientboundPacket.OpenScreen(5, "minecraft:generic_9x1"));
        sync();
        click(5, 0, "SWAP", 40, changed(0, SHIELD));
        assertNull(only().offhand());
        assertEquals(SWORD, only().hotbar().get(0));
    }

    @Test
    void anOffhandSwapInTheInventoryIsInTheClick() {
        click(0, 36, "SWAP", 40, changed(36, SHIELD, 45, SWORD));
        assertEquals(SWORD, only().offhand());
        assertEquals(SHIELD, only().hotbar().get(0));
    }

    @Test
    void aMountScreenOpensOnlyForAHorseOrANautilus() {
        out(new ClientboundPacket.MountScreenOpen(3, 5, 99));
        sync();
        // a donkey's (5 columns), a nautilus's, or none (the client has no such mount)
        assertEquals(Set.of(0, 3), menus());
        assertEquals(3, inventory.views().size());
        List<Stack> items = new ArrayList<>(Collections.nCopies(2 + 15 + 36, Stack.EMPTY));
        items.set(2 + 15 + 27, SPEAR);
        out(content(3, items, Stack.EMPTY));
        click(3, 2 + 15 + 27 + 1, "PICKUP", changed(2 + 15 + 27 + 1, APPLE));
        sync();
        // the click names menu 3; the donkey's layout (hotbar at 44) and the nautilus's (hotbar at 29) differ
        Set<Stack> slot0 = new HashSet<>();
        Set<Stack> slot1 = new HashSet<>();
        for (ClientInventory.View view : inventory.views()) {
            assertEquals(3, view.menuId());
            slot0.add(view.hotbar().get(0));
            slot1.add(view.hotbar().get(1));
        }
        assertTrue(slot0.contains(SPEAR));
        assertTrue(slot1.contains(APPLE));
    }

    @Test
    void aRespawnIsANewEmptyPlayer() {
        out(new ClientboundPacket.OpenScreen(5, "minecraft:generic_9x1"));
        out(new ClientboundPacket.Respawn(OVERWORLD, 0));
        sync();
        ClientInventory.View view = only();
        assertEquals(Collections.nCopies(9, Stack.EMPTY), view.hotbar());
        assertEquals(Stack.EMPTY, view.offhand());
        assertEquals(0, view.selected());
        assertEquals(0, view.menuId());
    }

    @Test
    void aSecondLoginMayKeepThePlayer() {
        out(new ClientboundPacket.Login(1, 8, OVERWORLD, 0));
        sync();
        Set<Stack> slot0 = new HashSet<>();
        for (ClientInventory.View view : inventory.views()) {
            slot0.add(view.hotbar().get(0));
        }
        assertEquals(Set.of(SWORD, Stack.EMPTY), slot0);
    }

    @Test
    void creativeEditsAreTheInventoryMenusSlots() {
        out(new ClientboundPacket.PlayerAbilities(true, false, true, true, 0.05F, 0.1F));
        sync();
        in(new ServerboundPacket.SetCreativeModeSlot(36 + 5, SPEAR));
        in(new ServerboundPacket.SetCreativeModeSlot(45, APPLE));
        assertEquals(SPEAR, only().hotbar().get(5));
        assertEquals(APPLE, only().offhand());
    }

    @Test
    void leavingCreativeForgetsTheHandsUntilTheServerResends() {
        out(new ClientboundPacket.PlayerAbilities(true, false, true, true, 0.05F, 0.1F));
        out(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, 0F));
        sync();
        assertNull(only().hotbar().get(0));
        // the creative screen's menu (also id 0) may still be open: its clicks are not read
        click(0, 45, "PICKUP", changed(45, SPEAR));
        assertNull(only().hotbar().get(0));
        in(new ServerboundPacket.ContainerClose(0));
        click(0, 36, "PICKUP", changed(36, SPEAR));
        assertEquals(SPEAR, only().hotbar().get(0));
        out(content(0, inventoryMenu(SHIELD, SWORD), Stack.EMPTY));
        sync();
        assertEquals(SWORD, only().hotbar().get(0));
        assertEquals(SHIELD, only().offhand());
    }

    @Test
    void theServerSetsInventoryIndices() {
        out(new ClientboundPacket.SetPlayerInventory(5, SPEAR));
        out(new ClientboundPacket.SetPlayerInventory(40, APPLE));
        out(new ClientboundPacket.SetPlayerInventory(20, SWORD));
        sync();
        assertEquals(SPEAR, only().hotbar().get(5));
        assertEquals(APPLE, only().offhand());
    }

    @Test
    void theClientSelects() {
        in(new ServerboundPacket.SetCarriedItem(7));
        assertEquals(7, only().selected());
        in(new ServerboundPacket.SetCarriedItem(9)); // not a hotbar slot: ignored
        assertEquals(7, only().selected());
    }

    @Test
    void reachFromThePrototypeOrThePatch() {
        assertEquals(ClientInventory.Reach.NONE, inventory.reach(SWORD));
        assertEquals(ClientInventory.Reach.NONE, inventory.reach(Stack.EMPTY));
        assertTrue(inventory.reach(SPEAR).known());
        assertEquals(new Stack.AttackRange(2F, 4.5F, 2F, 6.5F, 0.125F, 0.5F), inventory.reach(SPEAR).range());
        assertEquals(LONG, inventory.reach(LONG_STICK).range());
        Stack cleared = new Stack("minecraft:iron_spear", 1, List.of(), List.of(Stack.ATTACK_RANGE), null, false);
        assertEquals(ClientInventory.Reach.NONE, inventory.reach(cleared));
        Stack hashed = new Stack("minecraft:stick", 1, List.of(Stack.ATTACK_RANGE), List.of(), null, true);
        assertEquals(ClientInventory.Reach.UNKNOWN, inventory.reach(hashed));
        assertEquals(ClientInventory.Reach.UNKNOWN, inventory.reach(null));
        assertEquals(ClientInventory.Reach.UNKNOWN, inventory.reach(plain("othermod:thing")));
    }

    @Test
    void mainHandReachIsTheSelectedSlots() {
        out(new ClientboundPacket.SetPlayerInventory(40, LONG_STICK));
        out(new ClientboundPacket.SetPlayerInventory(3, LONG_STICK));
        sync();
        assertEquals(Set.of(ClientInventory.Reach.NONE), inventory.mainHandReach());
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true)), inventory.offhandReach());
        in(new ServerboundPacket.SetCarriedItem(3));
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true)), inventory.mainHandReach());
        inventory.onEvent(new TimelineEvent.Gap(sequence++, sequence, 1));
        assertEquals(Set.of(ClientInventory.Reach.UNKNOWN), inventory.mainHandReach());
    }

    @Test
    void thePickMayHaveSeenEverySlotSelectedThisTick() {
        out(new ClientboundPacket.SetPlayerInventory(3, LONG_STICK));
        sync();
        in(new ServerboundPacket.ClientTickEnd());
        assertEquals(Set.of(ClientInventory.Reach.NONE), inventory.pickReach());
        in(new ServerboundPacket.SetCarriedItem(3));
        // the pick ran at the tick's start, maybe before the hotbar key: either slot
        assertEquals(Set.of(ClientInventory.Reach.NONE, new ClientInventory.Reach(LONG, true)), inventory.pickReach());
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true)), inventory.mainHandReach());
        in(new ServerboundPacket.ClientTickEnd());
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true)), inventory.pickReach());
    }

    @Test
    void aStackThatMayBeUsedUpMayAlsoBeNone() {
        out(new ClientboundPacket.SetPlayerInventory(2, LONG_STICK));
        sync();
        outcome = ClientInventory.Outcome.KEEP_OR_NONE;
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 1, 0F, 0F));
        assertEquals(LONG_STICK, only().hotbar().get(2));
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true), ClientInventory.Reach.NONE), inventory.mainHandReach());
        out(new ClientboundPacket.SetPlayerInventory(2, LONG_STICK));
        sync();
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true)), inventory.mainHandReach());
    }

    @Test
    void aDropMayEmptyAnyHotbarSlot() {
        out(new ClientboundPacket.SetPlayerInventory(5, LONG_STICK));
        sync();
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.DROP_ITEM, null, null, 0));
        in(new ServerboundPacket.SetCarriedItem(5)); // the hotbar key of the drop's tick, sent a tick later
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true), ClientInventory.Reach.NONE), inventory.mainHandReach());
    }

    @Test
    void aUseThatMayChangeTheReachMakesTheHandUnknown() {
        outcome = ClientInventory.Outcome.UNKNOWN;
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 1, 0F, 0F));
        assertNull(only().hotbar().get(2));
        in(new ServerboundPacket.UseItem(Hand.OFF_HAND, 2, 0F, 0F));
        assertNull(only().offhand());
        assertEquals(SWORD, only().hotbar().get(0));
        outcome = ClientInventory.Outcome.KEEP;
        out(content(0, inventoryMenu(SHIELD, SWORD, SPEAR, APPLE), Stack.EMPTY));
        sync();
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 3, 0F, 0F));
        assertEquals(APPLE, only().hotbar().get(2));
    }

    @Test
    void aHashedStacksRangeIsTheOneAFullStackShowedWithItsHash() {
        out(new ClientboundPacket.SetPlayerInventory(3, LONG_STICK));
        sync();
        // the client moves it: the click carries only the hash
        Stack moved = new Stack("minecraft:stick", 1, List.of(Stack.ATTACK_RANGE), List.of(), null, true, LONG_HASH);
        click(0, 39, "PICKUP", changed(39, Stack.EMPTY));
        click(0, 41, "PICKUP", changed(41, moved));
        in(new ServerboundPacket.SetCarriedItem(5));
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true)), inventory.mainHandReach());
        Stack other = new Stack("minecraft:stick", 1, List.of(Stack.ATTACK_RANGE), List.of(), null, true, LONG_HASH + 1);
        click(0, 41, "PICKUP", changed(41, other));
        assertEquals(Set.of(ClientInventory.Reach.UNKNOWN), inventory.mainHandReach());
    }

    @Test
    void anAllayMayGiveItsItemToAnEmptyMainHand() {
        in(new ServerboundPacket.SetCarriedItem(3)); // an empty slot
        out(ClientboundPacket.AddEntity.received(50, new java.util.UUID(0, 50), "minecraft:allay", 0, 0, 0, dev.whynotac.math.vector.Vec3d.ZERO,
                (byte) 0, (byte) 0, (byte) 0, 0));
        out(ClientboundPacket.AddEntity.received(51, new java.util.UUID(0, 51), "minecraft:villager", 0, 0, 0, dev.whynotac.math.vector.Vec3d.ZERO,
                (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        in(new ServerboundPacket.InteractEntity(51, ServerboundPacket.InteractEntity.Kind.INTERACT, Hand.MAIN_HAND, null, false));
        assertEquals(Stack.EMPTY, only().hotbar().get(3));
        in(new ServerboundPacket.InteractEntity(50, ServerboundPacket.InteractEntity.Kind.INTERACT, Hand.MAIN_HAND, null, false));
        // the allay's item lands in the first empty slot (or merges): every empty slot is unknown
        assertNull(only().hotbar().get(3));
        assertEquals(SWORD, only().hotbar().get(0));
        assertEquals(Set.of(ClientInventory.Reach.UNKNOWN), inventory.mainHandReach());
    }

    @Test
    void finishingTheItemInUseMayLeaveNoneInEitherHand() {
        out(new ClientboundPacket.SetPlayerInventory(2, LONG_STICK));
        out(new ClientboundPacket.SetPlayerInventory(40, LONG_STICK));
        sync();
        outcome = ClientInventory.Outcome.KEEP_OR_NONE;
        out(new ClientboundPacket.EntityEvent(99, ClientboundPacket.EntityEvent.FINISH_USING_ITEM)); // another entity's
        sync();
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true)), inventory.mainHandReach());
        out(new ClientboundPacket.EntityEvent(1, ClientboundPacket.EntityEvent.FINISH_USING_ITEM)); // the player's own
        sync();
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true), ClientInventory.Reach.NONE), inventory.mainHandReach());
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true), ClientInventory.Reach.NONE), inventory.offhandReach());
    }

    @Test
    void releasingTheItemInUseMayLeaveNone() {
        out(new ClientboundPacket.SetPlayerInventory(2, LONG_STICK));
        sync();
        outcome = ClientInventory.Outcome.KEEP_OR_NONE;
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM, null, null, 0));
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true), ClientInventory.Reach.NONE), inventory.mainHandReach());
    }

    @Test
    void selectingATradeMovesStacksWithoutAClick() {
        out(new ClientboundPacket.OpenScreen(5, "minecraft:merchant"));
        out(new ClientboundPacket.SetPlayerInventory(2, LONG_STICK));
        sync();
        in(new ServerboundPacket.Unmodeled("minecraft:select_trade"));
        ClientInventory.View view = only();
        assertNull(view.hotbar().get(3)); // an empty slot may take back a payment stack
        assertEquals(SWORD, view.hotbar().get(0));
        in(new ServerboundPacket.SetCarriedItem(2));
        assertEquals(Set.of(new ClientInventory.Reach(LONG, true), ClientInventory.Reach.NONE), inventory.mainHandReach());
    }

    @Test
    void aGapForgetsEverything() {
        inventory.onEvent(new TimelineEvent.Gap(sequence++, sequence, 3));
        ClientInventory.View view = only();
        assertNull(view.hotbar().get(0));
        assertEquals(-1, view.selected());
        assertEquals(Set.of(ClientInventory.Reach.UNKNOWN), inventory.mainHandReach());
    }

    @Test
    void untranslatableInventoryPacketsForget() {
        out(new ClientboundPacket.Untranslatable("minecraft:container_set_slot"));
        sync();
        assertNull(only().hotbar().get(0));
    }

    // --- helpers ---

    private ClientInventory.View only() {
        List<ClientInventory.View> views = inventory.views();
        assertEquals(1, views.size(), views.toString());
        return views.getFirst();
    }

    private Set<Integer> menus() {
        Set<Integer> out = new HashSet<>();
        for (ClientInventory.View view : inventory.views()) {
            out.add(view.menuId());
        }
        return out;
    }

    private void out(ClientboundPacket packet) {
        inventory.onEvent(new TimelineEvent.Outbound(sequence++, sequence, packet));
    }

    private void in(ServerboundPacket packet) {
        inventory.onEvent(new TimelineEvent.Inbound(sequence++, sequence, 0, packet));
    }

    private int ping() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        return id;
    }

    private void ack(int id) {
        inventory.onEvent(new TimelineEvent.Acknowledged(sequence++, sequence, id, 0, 0));
    }

    private void sync() {
        ack(ping());
    }

    private void click(int container, int slot, String type, List<ServerboundPacket.ContainerClick.ChangedSlot> changed) {
        click(container, slot, type, 0, changed);
    }

    private void click(int container, int slot, String type, int button, List<ServerboundPacket.ContainerClick.ChangedSlot> changed) {
        in(new ServerboundPacket.ContainerClick(container, 1, slot, button, type, changed, Stack.EMPTY));
    }

    private static List<ServerboundPacket.ContainerClick.ChangedSlot> changed(Object... slotsAndItems) {
        List<ServerboundPacket.ContainerClick.ChangedSlot> out = new ArrayList<>();
        for (int i = 0; i < slotsAndItems.length; i += 2) {
            out.add(new ServerboundPacket.ContainerClick.ChangedSlot((Integer) slotsAndItems[i], (Stack) slotsAndItems[i + 1]));
        }
        return out;
    }

    private static ClientboundPacket.ContainerSetContent content(int container, List<Stack> items, Stack carried) {
        return new ClientboundPacket.ContainerSetContent(container, 1, items, carried);
    }

    /** InventoryMenu contents: this off hand (menu slot 45) and these hotbar stacks from slot 0 (menu 36). */
    private static List<Stack> inventoryMenu(Stack offhand, Stack... hotbar) {
        List<Stack> items = new ArrayList<>(Collections.nCopies(46, Stack.EMPTY));
        for (int k = 0; k < hotbar.length; k++) {
            items.set(36 + k, hotbar[k]);
        }
        items.set(45, offhand);
        return items;
    }

    private static Stack plain(String item) {
        return new Stack(item, 1, List.of(), List.of(), null, false);
    }
}
