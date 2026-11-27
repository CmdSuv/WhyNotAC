package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.Replicas;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Sound item-use &amp; AutoBlock / NoSlow intra-tick and cross-tick packet ordering check ({@code use-item-state}, P-M, SHADOW).
 *
 * <p>In vanilla 1.21.11 {@code Minecraft.tick()}, packet emission within a single client tick
 * follows a strict total order across {@code handleKeybinds()} and {@code LocalPlayer.sendPosition()}:
 * <ol>
 *   <li>{@code keyAttack.consumeClick() -> startAttack()} ({@code InteractEntity(ATTACK)},
 *       {@code START_DESTROY_BLOCK}, {@code STAB}).</li>
 *   <li>{@code keyUse.consumeClick() -> startUseItem()} ({@code InteractEntity(INTERACT/INTERACT_AT)},
 *       {@code UseItemOn}, {@code UseItem}).</li>
 *   <li>{@code if (!keyUse.isDown()) releaseUsingItem()} ({@code PlayerAction(RELEASE_USE_ITEM)}).</li>
 *   <li>{@code LocalPlayer.tick() -> sendPosition() -> sendIsSprintingIfNeeded()}
 *       ({@code PlayerCommand(START_SPRINTING / STOP_SPRINTING)}).</li>
 * </ol>
 *
 * <p>Furthermore, while {@code player.isUsingItem()} is continuously held active for a non-self-ending hold item
 * (such as {@code minecraft:shield} or {@code minecraft:spyglass} verified via {@link ClientInventory}) without
 * an intervening release, swap, drop, container open, or server stop/cooldown, {@code handleKeybinds()} returns
 * early after checking {@code releaseUsingItem()} and {@code LocalPlayer.canStartSprinting()} forbids starting sprint:
 * <ul>
 *   <li><b>{@code release-before-attack-same-tick}</b>: {@code RELEASE_USE_ITEM} cannot precede
 *       {@code InteractEntity(ATTACK)}, {@code START_DESTROY_BLOCK}, or {@code STAB} in the same tick.</li>
 *   <li><b>{@code release-before-use-same-tick}</b>: {@code RELEASE_USE_ITEM} cannot precede
 *       {@code UseItem} or {@code UseItemOn} in the same tick.</li>
 *   <li><b>{@code action-after-sprint-command-same-tick}</b>: sprint commands are emitted after {@code handleKeybinds()},
 *       so no {@code RELEASE_USE_ITEM}, {@code UseItem}, {@code UseItemOn}, or {@code InteractEntity} may follow them.</li>
 *   <li><b>{@code attack-while-using}</b>, <b>{@code use-while-using}</b>, and <b>{@code sprint-while-using}</b>:
 *       no attack, block dig, item use, or {@code START_SPRINTING} may occur while confirmed continuous shield/spyglass
 *       use remains active across ticks.</li>
 * </ul>
 */
public final class UseSlowdownCheck implements TimelineListener {

    public static final String ID = "use-item-state";
    public static final int VERSION = 4;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final int MAX_CONSUMABLE_HOLD_TICKS = 24;
    private static final Set<String> CONTINUOUS_HOLD_ITEMS = Set.of("minecraft:shield", "minecraft:spyglass");
    private static final Set<String> ALWAYS_CONSUMABLE_HOLD_ITEMS = Set.of(
            "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple",
            "minecraft:potion",
            "minecraft:milk_bucket",
            "minecraft:honey_bottle",
            "minecraft:ominous_bottle");
    private static final Set<String> HUNGER_CONSUMABLE_HOLD_ITEMS = Set.of(
            "minecraft:cooked_beef",
            "minecraft:golden_carrot",
            "minecraft:cooked_porkchop",
            "minecraft:bread",
            "minecraft:cooked_mutton",
            "minecraft:cooked_chicken");
    private static final String CONSUMABLE = "minecraft:consumable";
    private static final String FOOD = "minecraft:food";
    private static final String USE_COOLDOWN = "minecraft:use_cooldown";
    private static final String USE_EFFECTS = "minecraft:use_effects";
    private static final String BLOCKS_ATTACKS = "minecraft:blocks_attacks";

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public UseSlowdownCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final PingLedger ledger;
        final boolean sharedLedger;
        long lastReportNanos = Long.MIN_VALUE;
        int selfId = -1;

        boolean confirmedHungry;
        long pendingHungrySeq = Long.MIN_VALUE;

        // Pending outbound self SetEntityData (index 0, FLAG_SPRINTING bit 3 = 0x08) echo tracking:
        // suppressed until a subsequent ping is acknowledged plus one additional client tick.
        long pendingSprintEchoSeq = Long.MIN_VALUE;
        int sprintEchoGraceTicks;
        // Pending outbound server packet that stops client item use without RELEASE_USE_ITEM (OpenScreen,
        // PlayerPosition, SetEntityData index 8=0 / index 6, Cooldown, EntityEvent, GameEvent): do not arm
        // cross-tick hold claims until a subsequent ping confirms the client processed it.
        long pendingStopUseSeq = Long.MIN_VALUE;

        boolean releasedInCurrentTick;
        long releaseSeq = -1L;
        long releaseNano;

        boolean sprintCommandInCurrentTick;
        long sprintCmdSeq = -1L;
        long sprintCmdNano;
        String sprintCmdName;

        boolean sawUseOnThisTick;
        boolean sawInteractEntityThisTick;

        boolean activeHoldUse;
        String holdItem;
        long holdStartSeq = -1L;
        long holdStartNano;
        int holdTicksRemaining;
        int shieldCooldownTicks;
        int spyglassCooldownTicks;
        int anyCooldownTicks;

        State(PingLedger shared) {
            this.sharedLedger = shared != null;
            this.ledger = shared != null ? shared : new PingLedger();
        }

        static State fromSession(PlayerSession session) {
            return new State(session.pingLedger());
        }

        void resetHunger() {
            confirmedHungry = false;
            pendingHungrySeq = Long.MIN_VALUE;
        }

        void resetSprintEcho() {
            pendingSprintEchoSeq = Long.MIN_VALUE;
            sprintEchoGraceTicks = 0;
            pendingStopUseSeq = Long.MIN_VALUE;
        }

        boolean isSprintEchoSuppressed() {
            return pendingSprintEchoSeq != Long.MIN_VALUE || sprintEchoGraceTicks > 0;
        }

        void resetTick() {
            releasedInCurrentTick = false;
            releaseSeq = -1L;
            sprintCommandInCurrentTick = false;
            sprintCmdSeq = -1L;
            sprintCmdName = null;
            sawUseOnThisTick = false;
            sawInteractEntityThisTick = false;
        }

        void clearHold() {
            activeHoldUse = false;
            holdItem = null;
            holdStartSeq = -1L;
            holdTicksRemaining = 0;
        }
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::fromSession);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> {
                state.resetTick();
                state.clearHold();
                state.resetHunger();
                state.resetSprintEcho();
                state.shieldCooldownTicks = 0;
                state.spyglassCooldownTicks = 0;
                state.anyCooldownTicks = 0;
            }
            case TimelineEvent.Bound bound -> state.selfId = bound.entityId();
            case TimelineEvent.Outbound out -> onOutbound(state, out);
            case TimelineEvent.Acknowledged ack -> {
                long pingSeq = state.sharedLedger ? state.ledger.lastMatched() : state.ledger.onAcknowledged(ack.transactionId());
                if (pingSeq != Long.MIN_VALUE && state.pendingHungrySeq != Long.MIN_VALUE && pingSeq > state.pendingHungrySeq) {
                    state.confirmedHungry = true;
                    state.pendingHungrySeq = Long.MIN_VALUE;
                }
                if (pingSeq != Long.MIN_VALUE && state.pendingSprintEchoSeq != Long.MIN_VALUE && pingSeq > state.pendingSprintEchoSeq) {
                    state.pendingSprintEchoSeq = Long.MIN_VALUE;
                    state.sprintEchoGraceTicks = 2;
                }
                if (pingSeq != Long.MIN_VALUE && state.pendingStopUseSeq != Long.MIN_VALUE && pingSeq > state.pendingStopUseSeq) {
                    state.pendingStopUseSeq = Long.MIN_VALUE;
                }
            }
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onOutbound(State state, TimelineEvent.Outbound out) {
        ClientboundPacket packet = out.packet();
        switch (packet) {
            case ClientboundPacket.Login login -> {
                state.selfId = login.entityId();
                state.resetTick();
                state.clearHold();
                state.resetHunger();
                state.resetSprintEcho();
                state.shieldCooldownTicks = 0;
                state.spyglassCooldownTicks = 0;
                state.anyCooldownTicks = 0;
            }
            case ClientboundPacket.Respawn ignored -> {
                state.resetTick();
                state.clearHold();
                state.resetHunger();
                state.resetSprintEcho();
                state.shieldCooldownTicks = 0;
                state.spyglassCooldownTicks = 0;
                state.anyCooldownTicks = 0;
            }
            case ClientboundPacket.Unmodeled u when "minecraft:start_configuration".equals(u.type()) -> {
                state.resetTick();
                state.clearHold();
                state.resetHunger();
                state.resetSprintEcho();
                state.shieldCooldownTicks = 0;
                state.spyglassCooldownTicks = 0;
                state.anyCooldownTicks = 0;
            }
            case ClientboundPacket.Ping ping -> {
                if (!state.sharedLedger) {
                    state.ledger.onPing(ping.id(), out.sequence());
                }
            }
            case ClientboundPacket.Cooldown cd -> {
                state.clearHold();
                state.pendingStopUseSeq = out.sequence();
                if (cd.duration() > 0) {
                    state.anyCooldownTicks = Math.max(state.anyCooldownTicks, cd.duration());
                    if (cd.group().endsWith(":shield") || cd.group().equals("shield")) {
                        state.shieldCooldownTicks = Math.max(state.shieldCooldownTicks, cd.duration());
                    } else if (cd.group().endsWith(":spyglass") || cd.group().equals("spyglass")) {
                        state.spyglassCooldownTicks = Math.max(state.spyglassCooldownTicks, cd.duration());
                    }
                }
            }
            case ClientboundPacket.PlayerPosition ignored -> {
                state.clearHold();
                state.pendingStopUseSeq = out.sequence();
            }
            case ClientboundPacket.OpenScreen ignored -> {
                state.clearHold();
                state.pendingStopUseSeq = out.sequence();
            }
            case ClientboundPacket.MountScreenOpen ignored -> {
                state.clearHold();
                state.pendingStopUseSeq = out.sequence();
            }
            case ClientboundPacket.ContainerClose ignored -> {
                state.clearHold();
                state.pendingStopUseSeq = out.sequence();
            }
            case ClientboundPacket.ContainerSetContent ignored -> state.clearHold();
            case ClientboundPacket.ContainerSetSlot ignored -> state.clearHold();
            case ClientboundPacket.SetPlayerInventory ignored -> state.clearHold();
            case ClientboundPacket.SetCursorItem ignored -> state.clearHold();
            case ClientboundPacket.SetHealth h -> {
                state.clearHold();
                if (!(h.health() > 0.0F)) {
                    state.pendingStopUseSeq = out.sequence();
                }
                if (h.food() >= 20 || !(h.health() > 0.0F)) {
                    state.resetHunger();
                } else if (h.food() >= 0 && !state.confirmedHungry) {
                    state.pendingHungrySeq = out.sequence();
                }
            }
            case ClientboundPacket.GameEvent ignored -> {
                state.clearHold();
                state.pendingStopUseSeq = out.sequence();
            }
            case ClientboundPacket.EntityEvent ev when state.selfId >= 0 && ev.entityId() == state.selfId -> {
                state.clearHold();
                state.pendingStopUseSeq = out.sequence();
            }
            case ClientboundPacket.SetEntityData data when state.selfId >= 0 && data.entityId() == state.selfId -> {
                for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                    if (e.index() == 8 && (e.value() & 1L) == 0L || e.index() == 6) {
                        state.clearHold();
                        state.pendingStopUseSeq = out.sequence();
                    }
                    // Entity.DATA_SHARED_FLAGS_ID (index 0) bit 3 (Entity.FLAG_SPRINTING = 0x08):
                    // a stale server sprint echo overwrites client isSprinting() even while holding a shield
                    // (LocalPlayer.shouldStopRunSprinting does not check isUsingItem), causing
                    // LocalPlayer.sendIsSprintingIfNeeded() to emit START_SPRINTING on vanilla clients.
                    if (e.index() == 0 && (e.value() & 8L) != 0L) {
                        state.pendingSprintEchoSeq = out.sequence();
                    }
                }
            }
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.MovePlayer ignored -> state.resetTick();
            case ServerboundPacket.AcceptTeleportation ignored -> state.resetTick();
            case ServerboundPacket.ClientTickEnd ignored -> {
                state.resetTick();
                if (state.shieldCooldownTicks > 0) {
                    state.shieldCooldownTicks--;
                }
                if (state.spyglassCooldownTicks > 0) {
                    state.spyglassCooldownTicks--;
                }
                if (state.anyCooldownTicks > 0) {
                    state.anyCooldownTicks--;
                }
                if (state.activeHoldUse && state.holdTicksRemaining < Integer.MAX_VALUE) {
                    state.holdTicksRemaining--;
                    if (state.holdTicksRemaining <= 0) {
                        state.clearHold();
                    }
                }
                if (state.sprintEchoGraceTicks > 0) {
                    state.sprintEchoGraceTicks--;
                }
            }
            case ServerboundPacket.SetCarriedItem ignored -> state.clearHold();
            case ServerboundPacket.ContainerClick ignored -> state.clearHold();
            case ServerboundPacket.ContainerClose ignored -> state.clearHold();
            case ServerboundPacket.SetCreativeModeSlot ignored -> state.clearHold();
            case ServerboundPacket.PlayerAction action -> {
                switch (action.action()) {
                    case RELEASE_USE_ITEM -> {
                        if (state.sprintCommandInCurrentTick) {
                            long cmdSeq = state.sprintCmdSeq;
                            long cmdNano = state.sprintCmdNano;
                            String cmdName = state.sprintCmdName;
                            state.resetTick();
                            state.clearHold();
                            Map<String, String> details = new TreeMap<>();
                            details.put("reason", "action-after-sprint-command-same-tick");
                            details.put("sprintCommand", cmdName);
                            details.put("action", "RELEASE_USE_ITEM");
                            report(session, state, cmdSeq, cmdNano, in,
                                    "sent RELEASE_USE_ITEM after " + cmdName + " in the same tick (NoSlow packet hook)",
                                    details);
                            return;
                        }
                        state.clearHold();
                        state.releasedInCurrentTick = true;
                        state.releaseSeq = in.sequence();
                        state.releaseNano = in.nanoTime();
                    }
                    case SWAP_ITEM_WITH_OFFHAND, DROP_ITEM, DROP_ALL_ITEMS -> state.clearHold();
                    case START_DESTROY_BLOCK, STAB -> {
                        if (state.releasedInCurrentTick) {
                            long relSeq = state.releaseSeq;
                            long relNano = state.releaseNano;
                            state.resetTick();
                            Map<String, String> details = new TreeMap<>();
                            details.put("reason", "release-before-attack-same-tick");
                            details.put("action", action.action().name());
                            report(session, state, relSeq, relNano, in,
                                    "sent " + action.action().name() + " after RELEASE_USE_ITEM in the same tick",
                                    details);
                        } else if (state.activeHoldUse) {
                            long startSeq = state.holdStartSeq;
                            long startNano = state.holdStartNano;
                            String item = state.holdItem;
                            state.clearHold();
                            Map<String, String> details = new TreeMap<>();
                            details.put("reason", "attack-while-using");
                            details.put("action", action.action().name());
                            details.put("item", item);
                            report(session, state, startSeq, startNano, in,
                                    "sent " + action.action().name() + " while continuously using " + item,
                                    details);
                        }
                    }
                    default -> { }
                }
            }
            case ServerboundPacket.InteractEntity interact -> {
                state.sawInteractEntityThisTick = true;
                if (state.releasedInCurrentTick) {
                    long relSeq = state.releaseSeq;
                    long relNano = state.releaseNano;
                    state.resetTick();
                    String reason = interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK
                            ? "release-before-attack-same-tick"
                            : "release-before-use-same-tick";
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", reason);
                    details.put("targetEntityId", Integer.toString(interact.entityId()));
                    report(session, state, relSeq, relNano, in,
                            "sent InteractEntity(" + interact.kind() + ") after RELEASE_USE_ITEM in the same tick (AutoBlock packet cycle)",
                            details);
                } else if (state.sprintCommandInCurrentTick) {
                    long cmdSeq = state.sprintCmdSeq;
                    long cmdNano = state.sprintCmdNano;
                    String cmdName = state.sprintCmdName;
                    state.resetTick();
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "action-after-sprint-command-same-tick");
                    details.put("sprintCommand", cmdName);
                    details.put("action", "InteractEntity:" + interact.kind());
                    report(session, state, cmdSeq, cmdNano, in,
                            "sent InteractEntity(" + interact.kind() + ") after " + cmdName + " in the same tick",
                            details);
                } else if (state.activeHoldUse && interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK) {
                    long startSeq = state.holdStartSeq;
                    long startNano = state.holdStartNano;
                    String item = state.holdItem;
                    state.clearHold();
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "attack-while-using");
                    details.put("action", "InteractEntity:ATTACK");
                    details.put("targetEntityId", Integer.toString(interact.entityId()));
                    details.put("item", item);
                    report(session, state, startSeq, startNano, in,
                            "attacked entity " + interact.entityId() + " while continuously using " + item + " (AutoBlock)",
                            details);
                }
            }
            case ServerboundPacket.UseItem use -> {
                if (onUsePacket(session, state, in, "UseItem")) {
                    return;
                }
                armContinuousHold(session, state, in, use.hand());
            }
            case ServerboundPacket.UseItemOn ignored -> {
                state.sawUseOnThisTick = true;
                onUsePacket(session, state, in, "UseItemOn");
            }
            case ServerboundPacket.PlayerCommand cmd -> {
                if (cmd.action() == ServerboundPacket.PlayerCommand.Action.START_SPRINTING) {
                    if (state.activeHoldUse) {
                        if (state.isSprintEchoSuppressed()) {
                            state.clearHold();
                        } else {
                            long startSeq = state.holdStartSeq;
                            long startNano = state.holdStartNano;
                            String item = state.holdItem;
                            state.clearHold();
                            Map<String, String> details = new TreeMap<>();
                            details.put("reason", "sprint-while-using");
                            details.put("item", item);
                            report(session, state, startSeq, startNano, in,
                                    "sent START_SPRINTING while continuously using " + item + " (NoSlow)",
                                    details);
                        }
                    }
                    state.sprintCommandInCurrentTick = true;
                    state.sprintCmdSeq = in.sequence();
                    state.sprintCmdNano = in.nanoTime();
                    state.sprintCmdName = cmd.action().name();
                } else if (cmd.action() == ServerboundPacket.PlayerCommand.Action.STOP_SPRINTING) {
                    state.sprintCommandInCurrentTick = true;
                    state.sprintCmdSeq = in.sequence();
                    state.sprintCmdNano = in.nanoTime();
                    state.sprintCmdName = cmd.action().name();
                }
            }
            default -> { }
        }
    }

    private void armContinuousHold(PlayerSession session, State state, TimelineEvent.Inbound in, Hand hand) {
        if (state.sawUseOnThisTick || state.sawInteractEntityThisTick || state.pendingStopUseSeq != Long.MIN_VALUE) {
            return;
        }
        ClientSelf self = replicas.self().self(session);
        if (self.selfId() < 0 || self.mayBeSpectator()) {
            return;
        }
        ClientInventory inventory = replicas.inventory().inventory(session);
        List<ClientInventory.View> views = inventory.views();
        if (views.isEmpty()) {
            return;
        }
        String agreed = null;
        for (ClientInventory.View v : views) {
            int slot = hand == Hand.MAIN_HAND ? v.selected() : ClientInventory.HOTBAR;
            if (slot < 0) {
                return;
            }
            Stack stack = hand == Hand.MAIN_HAND ? v.hotbar().get(slot) : v.offhand();
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (v.mayBeNone()[slot] && (v.itemUses(slot) < 1 || v.itemUses(slot) > stack.count())) {
                return;
            }
            boolean isContinuous = CONTINUOUS_HOLD_ITEMS.contains(stack.item());
            boolean isAlwaysConsumable = ALWAYS_CONSUMABLE_HOLD_ITEMS.contains(stack.item());
            boolean isHungryFood = state.confirmedHungry && !self.mayBeCreativeOrSpectator()
                    && HUNGER_CONSUMABLE_HOLD_ITEMS.contains(stack.item());
            if ((!isContinuous && !isAlwaysConsumable && !isHungryFood)
                    || stack.added().contains(CONSUMABLE) || stack.removed().contains(CONSUMABLE)
                    || stack.added().contains(FOOD) || stack.removed().contains(FOOD)
                    || stack.added().contains(USE_COOLDOWN)
                    || stack.added().contains(USE_EFFECTS) || stack.removed().contains(USE_EFFECTS)
                    || stack.added().contains(BLOCKS_ATTACKS) || stack.removed().contains(BLOCKS_ATTACKS)) {
                return;
            }
            if (agreed == null) {
                agreed = stack.item();
            } else if (!agreed.equals(stack.item())) {
                return;
            }
        }
        if ("minecraft:shield".equals(agreed) && state.shieldCooldownTicks > 0
                || "minecraft:spyglass".equals(agreed) && state.spyglassCooldownTicks > 0
                || (ALWAYS_CONSUMABLE_HOLD_ITEMS.contains(agreed) || HUNGER_CONSUMABLE_HOLD_ITEMS.contains(agreed))
                        && state.anyCooldownTicks > 0) {
            return;
        }
        state.activeHoldUse = true;
        state.holdItem = agreed;
        state.holdStartSeq = in.sequence();
        state.holdStartNano = in.nanoTime();
        state.holdTicksRemaining = CONTINUOUS_HOLD_ITEMS.contains(agreed)
                ? Integer.MAX_VALUE
                : MAX_CONSUMABLE_HOLD_TICKS;
    }

    private boolean onUsePacket(PlayerSession session, State state, TimelineEvent.Inbound in, String packetName) {
        if (state.releasedInCurrentTick) {
            long relSeq = state.releaseSeq;
            long relNano = state.releaseNano;
            state.resetTick();
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "release-before-use-same-tick");
            details.put("packet", packetName);
            report(session, state, relSeq, relNano, in,
                    "sent " + packetName + " after RELEASE_USE_ITEM in the same tick",
                    details);
            return true;
        } else if (state.sprintCommandInCurrentTick) {
            long cmdSeq = state.sprintCmdSeq;
            long cmdNano = state.sprintCmdNano;
            String cmdName = state.sprintCmdName;
            state.resetTick();
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "action-after-sprint-command-same-tick");
            details.put("sprintCommand", cmdName);
            details.put("action", packetName);
            report(session, state, cmdSeq, cmdNano, in,
                    "sent " + packetName + " after " + cmdName + " in the same tick (NoSlow packet hook)",
                    details);
            return true;
        } else if (state.activeHoldUse) {
            long startSeq = state.holdStartSeq;
            long startNano = state.holdStartNano;
            String item = state.holdItem;
            state.clearHold();
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "use-while-using");
            details.put("packet", packetName);
            details.put("item", item);
            report(session, state, startSeq, startNano, in,
                    "sent " + packetName + " while continuously using " + item,
                    details);
            return true;
        }
        return false;
    }

    private void report(
            PlayerSession session,
            State state,
            long fromSeq,
            long fromNano,
            TimelineEvent.Inbound at,
            String summary,
            Map<String, String> details) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = state.admissibility.evaluate(fromSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, fromSeq, at.sequence(),
                fromNano, at.nanoTime(), summary, details, admissibility));
        state.lastReportNanos = at.nanoTime();
    }
}
