package dev.whynotac.world;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The player's own hotbar, off hand, selected slot and open menu as the client holds them, in client
 * order. A server packet takes effect when the client processes it, which the flush brackets place
 * (ops before the last answered ping are processed, ops from the first unanswered one on are not, and the
 * ones between are processed as a prefix); the client's own clicks, selections, creative edits and item
 * uses take effect where it sent them, on whatever prefix it had processed. Each candidate is one state
 * the client may hold; a slot is null when it is not known.
 *
 * <p>The handlers are {@code ClientPacketListener}'s: container 0 is always the player's
 * {@code InventoryMenu} and any other id only the open menu's; a menu type's slots are the game's own
 * ({@link MenuLayouts}); a respawn gives the client a new, empty player; a mount screen for an entity the
 * client does not have as a horse or a nautilus changes nothing. The client's own changes are the ones it
 * reports: a click lists every menu slot it changed, a creative edit is sent slot by slot while it has
 * infinite materials. Two it does not report are followed as unknown: the off hand a SWAP with button 40
 * changes in a menu without an off-hand slot, and the creative screen's edits while the client is no
 * longer creative (they are not sent).
 *
 * <p>What it follows is what the client's attack reach depends on: the stacks in its hands and their
 * {@code minecraft:attack_range}. An item use changes the hand in ways the packets do not show (a stack
 * used up, a bucket filled, a remainder); {@link UseEffects} says which of them the replica keeps
 * (its reach is not affected) and which make the hand unknown.
 *
 * <p>Confined to the session's analysis thread.
 */
public final class ClientInventory {

    public static final int HOTBAR = 9;
    /** {@code Inventory}: the off hand at 40. */
    static final int INVENTORY_OFFHAND = 40;
    /** {@code ServerboundContainerClickPacket} SWAP with this button swaps the slot with the off hand. */
    static final int SWAP_OFFHAND_BUTTON = 40;
    /** Beyond this many candidates the replica gives up and knows nothing until the server resends. */
    static final int MAX_CANDIDATES = 64;
    static final String ALLAY = "minecraft:allay";
    static final int MAX_ALLAYS = 4096;
    /** Attack ranges remembered by hash; past this many, new ones stay unresolved (unknown). */
    static final int MAX_RANGES = 4096;
    private static final int MAX_TRACKED_PINGS = 4096;
    private static final int UNKNOWN = -1;
    /** The off hand's index among the hand slots (hotbar 0..8, then the off hand). */
    static final int OFFHAND = HOTBAR;
    private static final int ALL_SLOTS = (1 << HOTBAR) - 1;
    /** {@code GameType} creative id. */
    private static final int CREATIVE = 1;
    private static final byte NO = 0;
    private static final byte YES = 1;
    private static final byte MAYBE = 2;

    /** What a stack does to the attack reach: no {@code attack_range}, one with known values, or not known. */
    public record Reach(Stack.AttackRange range, boolean known) {
        public static final Reach NONE = new Reach(null, true);
        public static final Reach UNKNOWN = new Reach(null, false);
    }

    /** How an item use may change the stack in the used hand, as far as its reach goes. */
    public interface UseEffects {
        /** What using {@code stack} (non-empty) through {@code use} may leave in the hand. */
        Outcome outcome(Stack stack, Use use);
    }

    /**
     * What a use may leave in the hand: the stack itself ({@code KEEP}); the stack, or none or a stack
     * without {@code attack_range} instead ({@code KEEP_OR_NONE}: used up, dropped, turned into a filled
     * bucket or a bowl); or anything ({@code UNKNOWN}: a swap with a slot not followed, a remainder from
     * the stack's own patch). {@code KEEP_OR_NONE_ADD} is {@code KEEP_OR_NONE} for the hand while a stack
     * not followed (the armor a stacked equippable replaces) goes into the inventory: into the first
     * empty slot unless it merges with a stack of its own kind.
     */
    public enum Outcome { KEEP, KEEP_OR_NONE, KEEP_OR_NONE_ADD, UNKNOWN }

    /** The kind of client action that used the stack. */
    public enum Use { ITEM, ITEM_ON_BLOCK, ENTITY }

    private final ItemPrototypes prototypes;
    private final MenuLayouts layouts;
    private final UseEffects uses;

    /** Pings not answered yet, in order: {id, sequence}. */
    private final ArrayDeque<long[]> pings = new ArrayDeque<>();
    private final List<Pending> pending = new ArrayList<>();
    /** Every attack range a full stack showed, by the client's hash of it: what a hashed stack's range is. */
    private final java.util.Map<Integer, Stack.AttackRange> rangesByHash = new java.util.HashMap<>();
    private List<State> candidates;
    /** Entity ids the server announced as allays (ids are never reused); {@code allaysUnknown}: some may be missing. */
    private final Set<Integer> allays = new java.util.HashSet<>();
    private boolean allaysUnknown;
    private boolean loggedIn;
    /** The client's own entity id (from the login), -1 before it. */
    private int selfId = -1;
    private long overflows;

    public ClientInventory(ItemPrototypes prototypes, MenuLayouts layouts, UseEffects uses) {
        this.prototypes = prototypes;
        this.layouts = layouts;
        this.uses = uses;
        candidates = new ArrayList<>(List.of(State.unknown()));
    }

    // --- timeline ---------------------------------------------------------------------------------------

    /** Applies one timeline event, in sequence order. */
    public void onEvent(TimelineEvent event) {
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(out.sequence(), out.packet());
            case TimelineEvent.Inbound in -> onInbound(in.packet());
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.transactionId());
            case TimelineEvent.Gap gap -> {
                lose();
                allaysUnknown = true;
            }
            case TimelineEvent.Disconnected ignored -> {
                lose();
                pings.clear();
            }
            default -> { }
        }
    }

    private void onOutbound(long seq, ClientboundPacket packet) {
        Op op = switch (packet) {
            case ClientboundPacket.Ping ping -> {
                pings.add(new long[] {ping.id(), seq});
                if (pings.size() > MAX_TRACKED_PINGS) {
                    pings.pollFirst(); // a later window end: wider, still sound
                }
                yield null;
            }
            case ClientboundPacket.ContainerSetContent content -> new Op.Content(content.containerId(), content.items());
            case ClientboundPacket.ContainerSetSlot slot -> new Op.Slot(slot.containerId(), slot.slot(), slot.item());
            case ClientboundPacket.SetPlayerInventory inventory -> new Op.InventorySlot(inventory.slot(), inventory.item());
            case ClientboundPacket.SetHeldSlot held -> new Op.Held(held.slot());
            case ClientboundPacket.OpenScreen open -> new Op.Open(open.containerId(), open.menuType() == null ? null : layouts.get(open.menuType()));
            case ClientboundPacket.MountScreenOpen mount -> new Op.Mount(mount.containerId(), mount.inventoryColumns());
            case ClientboundPacket.ContainerClose ignored -> Op.CLOSE;
            case ClientboundPacket.Login login -> {
                // The first login of a connection always makes a new LocalPlayer (there is none); a later
                // one reuses it unless the client went through configuration (handleLogin).
                Op o = new Op.Login(login.gameMode(), !loggedIn);
                loggedIn = true;
                selfId = login.entityId();
                yield o;
            }
            case ClientboundPacket.Respawn respawn -> new Op.NewPlayer(respawn.gameMode());
            case ClientboundPacket.PlayerAbilities abilities -> new Op.Creative(abilities.instabuild() ? YES : NO);
            case ClientboundPacket.GameEvent event when event.type() == ClientboundPacket.GameEvent.CHANGE_GAME_MODE ->
                    new Op.Creative(Math.round(event.param()) == CREATIVE ? YES : NO);
            case ClientboundPacket.EntityEvent event when event.event() == ClientboundPacket.EntityEvent.FINISH_USING_ITEM
                    && (selfId < 0 || event.entityId() == selfId) -> Op.FINISH_USE;
            case ClientboundPacket.AddEntity add -> {
                if (ALLAY.equals(add.type())) {
                    if (allays.size() < MAX_ALLAYS) {
                        allays.add(add.entityId());
                    } else {
                        allaysUnknown = true;
                    }
                }
                yield null;
            }
            case ClientboundPacket.Untranslatable untranslatable -> switch (untranslatable.type()) {
                case "minecraft:add_entity" -> {
                    allaysUnknown = true;
                    yield null;
                }
                case "minecraft:entity_event" -> Op.FINISH_USE; // maybe the player's own event 9
                case "minecraft:container_set_content", "minecraft:container_set_slot", "minecraft:set_player_inventory",
                        "minecraft:set_held_slot", "minecraft:open_screen", "minecraft:mount_screen_open", "minecraft:container_close",
                        "minecraft:login", "minecraft:respawn" -> Op.FORGET;
                case "minecraft:player_abilities", "minecraft:game_event" -> new Op.Creative(MAYBE);
                default -> null;
            };
            default -> null;
        };
        if (op != null) {
            pending.add(new Pending(seq, op));
            switch (op) {
                case Op.Content content -> content.items().forEach(this::learn);
                case Op.Slot slot -> learn(slot.item());
                case Op.InventorySlot slot -> learn(slot.item());
                default -> { }
            }
        }
    }

    /** Remembers a full stack's attack range under the client's hash of it. */
    private void learn(Stack stack) {
        if (stack != null && !stack.hashed() && stack.attackRange() != null && stack.rangeHash() != null && rangesByHash.size() < MAX_RANGES) {
            rangesByHash.putIfAbsent(stack.rangeHash(), stack.attackRange());
        }
    }

    private void onInbound(ServerboundPacket packet) {
        Event event = switch (packet) {
            case ServerboundPacket.ContainerClick click -> click.carried() == null ? Event.FORGET : new Event.Click(click);
            case ServerboundPacket.SetCarriedItem carried -> new Event.Select(carried.slot());
            case ServerboundPacket.SetCreativeModeSlot creative -> {
                learn(creative.item());
                yield new Event.CreativeSlot(creative.slot(), creative.item());
            }
            case ServerboundPacket.UseItem use -> new Event.Used(use.hand(), Use.ITEM, false);
            case ServerboundPacket.UseItemOn use -> new Event.Used(use.hand(), Use.ITEM_ON_BLOCK, false);
            case ServerboundPacket.InteractEntity interact when interact.kind() != ServerboundPacket.InteractEntity.Kind.ATTACK && interact.hand() != null ->
                    new Event.Used(interact.hand(), Use.ENTITY, interact.hand() == Hand.MAIN_HAND && (allaysUnknown || allays.contains(interact.entityId())));
            case ServerboundPacket.ContainerClose ignored -> Event.CLOSE;
            case ServerboundPacket.ClientTickEnd ignored -> Event.TICK_END;
            case ServerboundPacket.PlayerAction action when action.action() == ServerboundPacket.PlayerAction.Action.DROP_ITEM
                    || action.action() == ServerboundPacket.PlayerAction.Action.DROP_ALL_ITEMS -> Event.DROP;
            // releaseUsingItem: Item.releaseUsing, then a remainder if that emptied the stack (either hand may be the used one)
            case ServerboundPacket.PlayerAction action when action.action() == ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM -> Event.RELEASE;
            case ServerboundPacket.Untranslatable untranslatable -> switch (untranslatable.type()) {
                case "minecraft:container_click", "minecraft:set_carried_item", "minecraft:set_creative_mode_slot", "minecraft:use_item",
                        "minecraft:use_item_on", "minecraft:interact", "minecraft:container_close" -> Event.FORGET;
                default -> null;
            };
            default -> null;
        };
        if (event == null) {
            return;
        }
        int applicable = applicable(windowEnd());
        Set<State> next = new LinkedHashSet<>();
        for (State state : candidates) {
            for (State s : prefixes(state, applicable)) {
                State after = apply(event, s);
                if (after != null) {
                    next.add(after);
                }
            }
        }
        if (next.isEmpty()) {
            // No state the client may hold explains what it did: it holds one this replica does not know.
            next.add(State.unknown().at(applicable));
        }
        candidates = bound(new ArrayList<>(next));
    }

    private void onAcknowledged(int id) {
        long seq = Long.MIN_VALUE;
        for (long[] ping : pings) {
            if (ping[0] == id) {
                seq = ping[1];
                break;
            }
        }
        if (seq == Long.MIN_VALUE) {
            return; // not ours, or forgotten
        }
        while (!pings.isEmpty() && pings.peekFirst()[1] <= seq) {
            pings.pollFirst(); // skipped ones too: the client processed them before this one
        }
        int done = 0;
        while (done < pending.size() && pending.get(done).seq() < seq) {
            done++;
        }
        if (done == 0) {
            return;
        }
        Set<State> next = new LinkedHashSet<>();
        for (State state : candidates) {
            for (State s : applyUpTo(state, done)) {
                next.add(s.at(s.applied - done));
            }
        }
        pending.subList(0, done).clear();
        candidates = bound(new ArrayList<>(next));
    }

    private void lose() {
        pending.clear();
        candidates = new ArrayList<>(List.of(State.unknown()));
    }

    private long windowEnd() {
        long[] first = pings.peekFirst();
        return first == null ? Long.MAX_VALUE : first[1];
    }

    /** How many pending ops the client may have processed: those before the first unanswered ping. */
    private int applicable(long end) {
        int n = 0;
        while (n < pending.size() && pending.get(n).seq() < end) {
            n++;
        }
        return n;
    }

    /** Every state {@code state} becomes after the pending ops [state.applied, upTo). */
    private List<State> applyUpTo(State state, int upTo) {
        List<State> current = List.of(state);
        for (int k = state.applied; k < upTo; k++) {
            List<State> next = new ArrayList<>(current.size());
            for (State s : current) {
                apply(pending.get(k).op(), s, next);
            }
            current = next;
        }
        return current;
    }

    /** {@code state} and every state after each further prefix of the pending ops, up to {@code applicable}. */
    private List<State> prefixes(State state, int applicable) {
        List<State> all = new ArrayList<>();
        List<State> current = List.of(state);
        all.addAll(current);
        for (int k = state.applied; k < applicable; k++) {
            List<State> next = new ArrayList<>(current.size());
            for (State s : current) {
                apply(pending.get(k).op(), s, next);
            }
            current = next;
            all.addAll(current);
        }
        return all;
    }

    private List<State> bound(List<State> states) {
        if (states.size() <= MAX_CANDIDATES) {
            return states;
        }
        overflows++;
        int applied = Integer.MAX_VALUE;
        for (State s : states) {
            applied = Math.min(applied, s.applied);
        }
        return new ArrayList<>(List.of(State.unknown().at(applied)));
    }

    // --- one candidate --------------------------------------------------------------------------------------

    /** A server packet the client processes: every state it may leave the client in, added to {@code out}. */
    private void apply(Op op, State s, List<State> out) {
        State n = s.copy();
        n.applied = s.applied + 1;
        switch (op) {
            case Op.Content content -> {
                if (content.containerId() == 0) {
                    // inventoryMenu.initializeContents, whatever menu is open; slots past the list keep their stack
                    write(n, layouts.inventory(), content.items());
                } else if (n.menuId == UNKNOWN) {
                    n.forgetHands(); // it may be the open menu's, laid out any way
                } else if (content.containerId() == n.menuId) {
                    if (n.layout == null) {
                        n.forgetHands();
                    } else {
                        write(n, n.layout, content.items());
                    }
                }
            }
            case Op.Slot slot -> {
                if (slot.containerId() == 0) {
                    write(n, layouts.inventory(), slot.slot(), slot.item());
                } else if (n.menuId == UNKNOWN) {
                    n.forgetHands();
                } else if (slot.containerId() == n.menuId) {
                    if (n.layout == null) {
                        n.forgetHands();
                    } else {
                        write(n, n.layout, slot.slot(), slot.item());
                    }
                }
            }
            case Op.InventorySlot slot -> {
                if (slot.slot() >= 0 && slot.slot() < HOTBAR) {
                    n.set(slot.slot(), slot.item());
                } else if (slot.slot() == INVENTORY_OFFHAND) {
                    n.set(OFFHAND, slot.item());
                }
            }
            case Op.Held held -> {
                if (held.slot() >= 0 && held.slot() < HOTBAR) {
                    n.select(held.slot());
                }
            }
            case Op.Open open -> n.open(open.containerId(), open.layout());
            case Op.Mount mount -> {
                // handleMountScreenOpen: a horse's menu, a nautilus's, or nothing for an entity it does not have as either
                out.add(n.copy());
                State horse = n.copy();
                horse.open(mount.containerId(), layouts.horse(mount.inventoryColumns()));
                out.add(horse);
                n.open(mount.containerId(), layouts.nautilus());
            }
            case Op.Simple simple -> {
                switch (simple.kind()) {
                    case Op.KIND_CLOSE -> n.open(0, layouts.inventory());
                    case Op.KIND_FORGET -> {
                        State u = State.unknown();
                        u.applied = n.applied;
                        out.add(u);
                        return;
                    }
                    case Op.KIND_FINISH_USE -> usedHandsAfter(n, Use.ITEM); // completeUsingItem: consumed, maybe its remainder
                    default -> throw new IllegalStateException();
                }
            }
            case Op.Login login -> {
                if (!login.newPlayer()) {
                    State kept = n.copy();
                    kept.creative(login.gameMode() < 0 ? MAYBE : login.gameMode() == CREATIVE ? YES : NO);
                    out.add(kept);
                }
                n.newPlayer(login.gameMode(), layouts.inventory());
            }
            case Op.NewPlayer player -> n.newPlayer(player.gameMode(), layouts.inventory());
            case Op.Creative creative -> n.creative(creative.value());
        }
        out.add(n);
    }

    /** Something the client did itself; null when no state it may hold explains it. */
    private State apply(Event event, State s) {
        State n = s.copy();
        switch (event) {
            case Event.Click click -> {
                ServerboundPacket.ContainerClick c = click.click();
                if (n.menuId == UNKNOWN) {
                    // the click names the open menu, not its layout
                    n.forgetHands();
                    n.menuId = c.containerId();
                    n.layout = c.containerId() == 0 ? layouts.inventory() : null;
                    n.picker = c.containerId() == 0;
                    return n;
                }
                if (c.containerId() != n.menuId) {
                    return null; // handleInventoryMouseClick sends nothing for another menu
                }
                if (n.menuId == 0 && n.mayBePicker()) {
                    // The creative screen's menu (id 0 too) never sends clicks, but after a switch out of creative
                    // with it open the inventory screen's clicks act on it, slot numbers and all.
                    n.forgetHands();
                    return n;
                }
                if (n.layout == null) {
                    n.forgetHands();
                    return n;
                }
                for (ServerboundPacket.ContainerClick.ChangedSlot changed : c.changed()) {
                    write(n, n.layout, changed.slot(), changed.item());
                }
                if ("SWAP".equals(c.clickType()) && c.button() == SWAP_OFFHAND_BUTTON && n.layout.offhand() < 0) {
                    n.set(OFFHAND, null); // swapped with the off hand, which this menu has no slot for: not in the click
                }
            }
            case Event.Select select -> {
                if (select.slot() >= 0 && select.slot() < HOTBAR) {
                    n.select(select.slot());
                }
            }
            case Event.CreativeSlot creative -> write(n, layouts.inventory(), creative.slot(), creative.item());
            case Event.Used used -> {
                if (used.mayGive() && n.mainHandMayBeEmpty()) {
                    // Allay.mobInteract: with an empty main hand the player takes the allay's item (any stack) with
                    // Inventory.add, into a stack of its own kind or the first empty slot; no slot is reported.
                    n.emptySlotsUnknown();
                }
                if (used.hand() == Hand.OFF_HAND) {
                    afterUse(n, OFFHAND, used.use());
                } else if (n.selected == UNKNOWN) {
                    for (int k = 0; k < HOTBAR; k++) {
                        afterUse(n, k, used.use());
                    }
                } else {
                    afterUse(n, n.selected, used.use());
                }
            }
            case Event.Simple simple -> {
                switch (simple.kind()) {
                    case Event.KIND_CLOSE -> n.open(0, layouts.inventory());
                    case Event.KIND_FORGET -> n.forgetHands();
                    case Event.KIND_TICK_END -> n.tickSelected = n.selected == UNKNOWN ? ALL_SLOTS : 1 << n.selected;
                    case Event.KIND_RELEASE -> usedHandsAfter(n, Use.ITEM);
                    case Event.KIND_DROP -> {
                        // LocalPlayer.drop: the selected stack loses one or all, and nothing says which slot that is:
                        // the drop key is handled after the hotbar keys of the same tick, whose selection is only sent
                        // at the next tick (ensureHasSentCarriedItem is not called on this path).
                        for (int k = 0; k < HOTBAR; k++) {
                            n.mayBeNone[k] |= n.hotbar[k] != null;
                        }
                    }
                    default -> throw new IllegalStateException();
                }
            }
        }
        return n;
    }

    /** After the item in use finished: the hand using it is the main hand or the off hand ({@code usingItemHand}, not sent). */
    private void usedHandsAfter(State n, Use use) {
        afterUse(n, OFFHAND, use);
        for (int k = 0; k < HOTBAR; k++) {
            if (n.selected == UNKNOWN || n.selected == k) {
                afterUse(n, k, use);
            }
        }
    }

    /** The hand slot after a use of its stack. */
    private void afterUse(State n, int slot, Use use) {
        Stack stack = n.get(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        switch (uses.outcome(stack, use)) {
            case KEEP -> { }
            case KEEP_OR_NONE -> n.mayBeNone[slot] = true;
            case KEEP_OR_NONE_ADD -> {
                n.mayBeNone[slot] = true;
                n.emptySlotsUnknown();
            }
            case UNKNOWN -> n.set(slot, null);
        }
    }

    private static void write(State n, MenuLayouts.Layout layout, List<Stack> items) {
        for (int k = 0; k < HOTBAR; k++) {
            int slot = layout.hotbar().get(k);
            if (slot >= 0 && slot < items.size()) {
                n.set(k, items.get(slot));
            }
        }
        if (layout.offhand() >= 0 && layout.offhand() < items.size()) {
            n.set(OFFHAND, items.get(layout.offhand()));
        }
    }

    private static void write(State n, MenuLayouts.Layout layout, int slot, Stack item) {
        int player = layout.playerSlot(slot);
        if (player == -2) {
            n.set(OFFHAND, item);
        } else if (player >= 0) {
            n.set(player, item);
        }
    }

    // --- queries ------------------------------------------------------------------------------------------

    /**
     * The reach of one stack: empty, a patch that sets or clears {@code attack_range}, else the item's
     * prototype. A hashed stack's range is the one a full stack showed with the same hash (the client
     * hashes its own stack: the same value hashes the same), else unknown.
     */
    public Reach reach(Stack stack) {
        if (stack != null && stack.hashed() && stack.added().contains(Stack.ATTACK_RANGE) && !stack.removed().contains(Stack.ATTACK_RANGE)) {
            Stack.AttackRange range = stack.rangeHash() == null ? null : rangesByHash.get(stack.rangeHash());
            return range == null ? Reach.UNKNOWN : new Reach(range, true);
        }
        return reach(prototypes, stack);
    }

    /** {@link #reach(Stack)} with these prototypes. */
    public static Reach reach(ItemPrototypes prototypes, Stack stack) {
        if (stack == null) {
            return Reach.UNKNOWN;
        }
        if (stack.isEmpty() || stack.removed().contains(Stack.ATTACK_RANGE)) {
            return Reach.NONE;
        }
        if (stack.added().contains(Stack.ATTACK_RANGE)) {
            return stack.attackRange() != null ? new Reach(stack.attackRange(), true) : Reach.UNKNOWN;
        }
        ItemPrototypes.Prototype prototype = prototypes.get(stack.item());
        if (prototype == null) {
            return Reach.UNKNOWN;
        }
        return prototype.attackRange() == null ? Reach.NONE : new Reach(prototype.attackRange(), true);
    }

    /**
     * Every reach the client's main hand may have now: the selected hotbar slot's (every hotbar slot's if
     * the selection is not known), and none as well where its stack may have been used up.
     */
    public Set<Reach> mainHandReach() {
        Set<Reach> out = new LinkedHashSet<>();
        for (State s : possible()) {
            for (int k = 0; k < HOTBAR; k++) {
                if (s.selected == UNKNOWN || s.selected == k) {
                    s.reaches(k, this, out);
                }
            }
        }
        return out;
    }

    /**
     * Every reach the main hand may have had at this client tick's pick ({@code GameRenderer.pick} runs at
     * the start of the tick, before the hotbar keys of that tick): every hotbar slot selected at the last
     * tick end or since.
     */
    public Set<Reach> pickReach() {
        Set<Reach> out = new LinkedHashSet<>();
        for (State s : possible()) {
            for (int k = 0; k < HOTBAR; k++) {
                if ((s.tickSelected & 1 << k) != 0) {
                    s.reaches(k, this, out);
                }
            }
        }
        return out;
    }

    /** Every reach the off hand may have now. */
    public Set<Reach> offhandReach() {
        Set<Reach> out = new LinkedHashSet<>();
        for (State s : possible()) {
            s.reaches(OFFHAND, this, out);
        }
        return out;
    }

    /** Every state the client may hold now. */
    public List<View> views() {
        List<View> out = new ArrayList<>();
        Set<State> seen = new LinkedHashSet<>();
        for (State s : possible()) {
            State key = s.at(0);
            if (seen.add(key)) {
                out.add(new View(Arrays.asList(s.hotbar.clone()), s.offhand, s.selected, s.menuId, s.mayBeNone.clone()));
            }
        }
        return out;
    }

    private List<State> possible() {
        int applicable = applicable(windowEnd());
        List<State> all = new ArrayList<>();
        for (State state : candidates) {
            all.addAll(prefixes(state, applicable));
        }
        return all;
    }

    /**
     * One state: the hotbar (null: not known), the off hand, the selected slot (-1: not known), the open
     * menu (-1: not known), and per hotbar slot (0..8) and the off hand (9) whether its stack may have been
     * used up or turned into one without {@code attack_range}.
     */
    public record View(List<Stack> hotbar, Stack offhand, int selected, int menuId, boolean[] mayBeNone) {
        public View {
            hotbar = Collections.unmodifiableList(new ArrayList<>(hotbar));
            mayBeNone = mayBeNone.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof View v && hotbar.equals(v.hotbar) && Objects.equals(offhand, v.offhand) && selected == v.selected
                    && menuId == v.menuId && Arrays.equals(mayBeNone, v.mayBeNone);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hotbar, offhand, selected, menuId, Arrays.hashCode(mayBeNone));
        }

        @Override
        public String toString() {
            return "View[hotbar=" + hotbar + ", offhand=" + offhand + ", selected=" + selected + ", menuId=" + menuId + ", mayBeNone="
                    + Arrays.toString(mayBeNone) + "]";
        }
    }

    public int candidates() {
        return candidates.size();
    }

    public int pendingOps() {
        return pending.size();
    }

    public long overflows() {
        return overflows;
    }

    // --- model --------------------------------------------------------------------------------------------

    private record Pending(long seq, Op op) {}

    private sealed interface Op {
        int KIND_CLOSE = 0;
        int KIND_FORGET = 1;
        int KIND_FINISH_USE = 2;
        Simple CLOSE = new Simple(KIND_CLOSE);
        Simple FORGET = new Simple(KIND_FORGET);
        /** The player's own entity event 9: the item in use is finished on the client. */
        Simple FINISH_USE = new Simple(KIND_FINISH_USE);

        record Content(int containerId, List<Stack> items) implements Op {}

        record Slot(int containerId, int slot, Stack item) implements Op {}

        record InventorySlot(int slot, Stack item) implements Op {}

        record Held(int slot) implements Op {}

        /** A menu of a known layout, or null for a menu type this table does not know. */
        record Open(int containerId, MenuLayouts.Layout layout) implements Op {}

        record Mount(int containerId, int inventoryColumns) implements Op {}

        /** {@code newPlayer}: certainly a new LocalPlayer; otherwise maybe the old one, kept. */
        record Login(int gameMode, boolean newPlayer) implements Op {}

        record NewPlayer(int gameMode) implements Op {}

        /** The client's {@code abilities.instabuild} (what {@code hasInfiniteMaterials} reads): NO, YES or MAYBE. */
        record Creative(byte value) implements Op {}

        record Simple(int kind) implements Op {}
    }

    private sealed interface Event {
        int KIND_CLOSE = 0;
        int KIND_FORGET = 1;
        int KIND_TICK_END = 2;
        int KIND_DROP = 3;
        int KIND_RELEASE = 4;
        Simple CLOSE = new Simple(KIND_CLOSE);
        /** The client did something to its inventory this replica cannot read. */
        Simple FORGET = new Simple(KIND_FORGET);
        Simple TICK_END = new Simple(KIND_TICK_END);
        /** The drop key: the selected stack loses one or all of its items. */
        Simple DROP = new Simple(KIND_DROP);
        /** The use key released: the item in use is released. */
        Simple RELEASE = new Simple(KIND_RELEASE);

        record Click(ServerboundPacket.ContainerClick click) implements Event {}

        record Select(int slot) implements Event {}

        record CreativeSlot(int slot, Stack item) implements Event {}

        /** {@code mayGive}: an entity interaction with the main hand, maybe with an allay. */
        record Used(Hand hand, Use use, boolean mayGive) implements Event {}

        record Simple(int kind) implements Event {}
    }

    /** One possible client-side state. Equal states are one candidate. */
    private static final class State {
        final Stack[] hotbar = new Stack[HOTBAR];
        Stack offhand;
        /** Per hand slot (hotbar 0..8, the off hand): its stack may have been used up or turned into one without attack_range. */
        final boolean[] mayBeNone = new boolean[HOTBAR + 1];
        int selected = UNKNOWN;
        /** The hotbar slots selected at the last client tick end or since (bit k: slot k). */
        int tickSelected = ALL_SLOTS;
        int menuId = UNKNOWN;
        /** The open menu's layout; null if not known. */
        MenuLayouts.Layout layout;
        /** The client's instabuild: NO, YES or MAYBE. */
        byte creative = MAYBE;
        /** The open menu may be the creative screen's (it opens without a packet while creative). */
        boolean picker = true;
        /** Pending ops this candidate has processed. */
        int applied;

        static State unknown() {
            return new State();
        }

        State copy() {
            State c = new State();
            System.arraycopy(hotbar, 0, c.hotbar, 0, HOTBAR);
            c.offhand = offhand;
            System.arraycopy(mayBeNone, 0, c.mayBeNone, 0, HOTBAR + 1);
            c.selected = selected;
            c.tickSelected = tickSelected;
            c.menuId = menuId;
            c.layout = layout;
            c.creative = creative;
            c.picker = picker;
            c.applied = applied;
            return c;
        }

        State at(int applied) {
            State c = copy();
            c.applied = applied;
            return c;
        }

        Stack get(int slot) {
            return slot == OFFHAND ? offhand : hotbar[slot];
        }

        /** A stack the client was given or reported: exactly this one. */
        void set(int slot, Stack stack) {
            if (slot == OFFHAND) {
                offhand = stack;
            } else {
                hotbar[slot] = stack;
            }
            mayBeNone[slot] = false;
        }

        /** Inventory.add of a stack not followed: a stack of its own kind grows (same reach), else the first empty slot takes it. */
        void emptySlotsUnknown() {
            for (int k = 0; k < HOTBAR; k++) {
                if (hotbar[k] != null && (hotbar[k].isEmpty() || mayBeNone[k])) {
                    set(k, null);
                }
            }
        }

        boolean mainHandMayBeEmpty() {
            for (int k = 0; k < HOTBAR; k++) {
                if ((selected == UNKNOWN || selected == k) && (hotbar[k] == null || hotbar[k].isEmpty() || mayBeNone[k])) {
                    return true;
                }
            }
            return false;
        }

        void select(int slot) {
            selected = slot;
            tickSelected |= 1 << slot;
        }

        void reaches(int slot, ClientInventory inventory, Set<Reach> out) {
            out.add(inventory.reach(get(slot)));
            if (mayBeNone[slot]) {
                out.add(Reach.NONE);
            }
        }

        void forgetHands() {
            Arrays.fill(hotbar, null);
            offhand = null;
            Arrays.fill(mayBeNone, false);
        }

        /** {@code player.containerMenu} becomes a menu built for this packet (or the inventory menu). */
        void open(int id, MenuLayouts.Layout menu) {
            menuId = id;
            layout = menu;
            picker = false;
        }

        boolean mayBePicker() {
            return picker || creative != NO;
        }

        void creative(byte value) {
            if (creative != NO && value != YES) {
                // Leaving creative with the creative screen maybe open: its edits until the screen swaps are not sent.
                forgetHands();
                picker = true; // its menu stays the open one until a close
            }
            creative = value;
        }

        /** A new LocalPlayer: an empty inventory, slot 0, its own inventory menu. */
        void newPlayer(int gameMode, MenuLayouts.Layout inventory) {
            Arrays.fill(hotbar, Stack.EMPTY);
            offhand = Stack.EMPTY;
            Arrays.fill(mayBeNone, false);
            selected = 0;
            tickSelected |= 1;
            open(0, inventory);
            creative = gameMode < 0 ? MAYBE : gameMode == CREATIVE ? YES : NO;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof State s && applied == s.applied && selected == s.selected && tickSelected == s.tickSelected && menuId == s.menuId
                    && creative == s.creative && picker == s.picker && sameLayout(layout, s.layout) && Objects.equals(offhand, s.offhand)
                    && Arrays.equals(hotbar, s.hotbar) && Arrays.equals(mayBeNone, s.mayBeNone);
        }

        @Override
        public int hashCode() {
            return Objects.hash(applied, selected, tickSelected, menuId, creative, picker, layout == null ? null : layout.hotbar(), offhand,
                    Arrays.hashCode(hotbar), Arrays.hashCode(mayBeNone));
        }

        private static boolean sameLayout(MenuLayouts.Layout a, MenuLayouts.Layout b) {
            return a == null ? b == null : b != null && a.size() == b.size() && a.offhand() == b.offhand() && a.hotbar().equals(b.hotbar());
        }
    }
}
