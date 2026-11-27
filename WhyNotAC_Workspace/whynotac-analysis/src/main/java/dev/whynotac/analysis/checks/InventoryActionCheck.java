package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Inventory protocol check ({@code inventory-protocol}, P-M, SHADOW): verifies decompiled 1.21.11
 * {@code AbstractContainerScreen} / {@code InventoryScreen} + {@code Minecraft.tick()} invariants.
 *
 * <p>When the client clicks a slot in any container ({@code ServerboundContainerClickPacket}, including
 * {@code containerId == 0} for the player's own inventory), {@code this.screen} is non-null during the
 * screen event phase of {@code Minecraft.tick()}. Unless the screen was closed in that same tick via
 * {@code ServerboundContainerClosePacket} (or covered by a server screen packet), {@code this.screen != null}
 * holds when {@code Minecraft.tick()} reaches {@code if (this.screen == null) { this.handleKeybinds(); }},
 * and {@code KeyMapping.releaseAll()} keeps movement keys released. Therefore:
 * <ul>
 *   <li>{@code action-during-container-click}: no keybind action ({@code InteractEntity}, {@code UseItem},
 *       {@code UseItemOn}, {@code START_DESTROY_BLOCK}, {@code STOP_DESTROY_BLOCK}, {@code STAB}) or
 *       {@code START_SPRINTING} command may follow a {@code ContainerClick} in the same tick without an
 *       intervening {@code ContainerClose};</li>
 *   <li>{@code walk-during-container-click}: no {@code PlayerInput} with active horizontal walking keys
 *       ({@code forward}, {@code backward}, {@code left}, {@code right}) may be sent in the same tick after
 *       a {@code ContainerClick} without an intervening {@code ContainerClose}.</li>
 * </ul>
 */
public final class InventoryActionCheck implements TimelineListener {

    public static final String ID = "inventory-protocol";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final Set<String> SCREEN_CHANGING_UNMODELED = Set.of(
            "minecraft:start_configuration",
            "minecraft:open_book",
            "minecraft:open_sign_editor",
            "minecraft:resource_pack_push",
            "minecraft:show_dialog",
            "minecraft:transfer",
            "minecraft:disconnect");

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public InventoryActionCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final PingLedger ledger;
        final boolean sharedLedger;
        long lastReportNanos = Long.MIN_VALUE;
        int selfId = -1;
        boolean clickedThisTick;
        int clickedContainerId;
        long clickSeq;
        long clickNano;
        boolean sawSessionStart;
        boolean containerHistoryGap;
        final Set<Integer> openContainers = new HashSet<>();
        final Set<Integer> preRespawnContainers = new HashSet<>();
        long pendingRespawnSeq = Long.MIN_VALUE;
        long pendingServerCloseSeq = Long.MIN_VALUE;
        long pendingSprintEchoSeq = Long.MIN_VALUE;
        int sprintEchoGraceTicks;

        State(PingLedger shared) {
            this.sharedLedger = shared != null;
            this.ledger = shared != null ? shared : new PingLedger();
        }

        static State fromSession(PlayerSession session) {
            return new State(session.pingLedger());
        }

        void clearTick() {
            clickedThisTick = false;
        }

        boolean isServerClosePending() {
            return pendingServerCloseSeq != Long.MIN_VALUE;
        }

        boolean isSprintEchoSuppressed() {
            return pendingSprintEchoSeq != Long.MIN_VALUE || sprintEchoGraceTicks > 0;
        }

        void resetContainers(boolean gap) {
            clearTick();
            openContainers.clear();
            preRespawnContainers.clear();
            pendingRespawnSeq = Long.MIN_VALUE;
            pendingServerCloseSeq = Long.MIN_VALUE;
            pendingSprintEchoSeq = Long.MIN_VALUE;
            sprintEchoGraceTicks = 0;
            if (gap) {
                containerHistoryGap = true;
            } else {
                sawSessionStart = true;
                containerHistoryGap = false;
            }
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
            case TimelineEvent.Gap ignored -> state.resetContainers(true);
            case TimelineEvent.Bound bound -> state.selfId = bound.entityId();
            case TimelineEvent.Acknowledged ack -> {
                long pingSeq = state.sharedLedger ? state.ledger.lastMatched() : state.ledger.onAcknowledged(ack.transactionId());
                if (pingSeq != Long.MIN_VALUE) {
                    if (state.pendingServerCloseSeq != Long.MIN_VALUE && pingSeq > state.pendingServerCloseSeq) {
                        state.pendingServerCloseSeq = Long.MIN_VALUE;
                    }
                    if (state.pendingRespawnSeq != Long.MIN_VALUE && pingSeq > state.pendingRespawnSeq) {
                        state.preRespawnContainers.clear();
                        state.pendingRespawnSeq = Long.MIN_VALUE;
                    }
                    if (state.pendingSprintEchoSeq != Long.MIN_VALUE && pingSeq > state.pendingSprintEchoSeq) {
                        state.pendingSprintEchoSeq = Long.MIN_VALUE;
                        state.sprintEchoGraceTicks = 2;
                    }
                }
            }
            case TimelineEvent.Outbound out -> {
                switch (out.packet()) {
                    case ClientboundPacket.Login login -> {
                        state.selfId = login.entityId();
                        state.resetContainers(false);
                    }
                    case ClientboundPacket.Unmodeled u when "minecraft:start_configuration".equals(u.type()) ->
                            state.resetContainers(false);
                    case ClientboundPacket.Unmodeled u when SCREEN_CHANGING_UNMODELED.contains(u.type()) -> {
                        state.clearTick();
                        state.pendingServerCloseSeq = out.sequence();
                    }
                    case ClientboundPacket.Respawn ignored -> {
                        state.clearTick();
                        state.preRespawnContainers.addAll(state.openContainers);
                        state.openContainers.clear();
                        state.sawSessionStart = true;
                        state.containerHistoryGap = false;
                        state.pendingRespawnSeq = out.sequence();
                        state.pendingServerCloseSeq = out.sequence();
                    }
                    case ClientboundPacket.Ping ping -> {
                        if (!state.sharedLedger) {
                            state.ledger.onPing(ping.id(), out.sequence());
                        }
                    }
                    case ClientboundPacket.OpenScreen open -> {
                        state.clearTick();
                        if (open.containerId() > 0) {
                            state.openContainers.add(open.containerId());
                        }
                    }
                    case ClientboundPacket.MountScreenOpen mount -> {
                        state.clearTick();
                        if (mount.containerId() > 0) {
                            state.openContainers.add(mount.containerId());
                        }
                    }
                    case ClientboundPacket.ContainerClose ignored -> {
                        // ClientPacketListener.handleContainerClose calls clientSideCloseContainer() without
                        // sending ServerboundContainerClosePacket; suppress same-tick click rules until ack.
                        state.clearTick();
                        state.pendingServerCloseSeq = out.sequence();
                    }
                    case ClientboundPacket.SetHealth h when !(h.health() > 0.0F) -> {
                        state.clearTick();
                        state.pendingServerCloseSeq = out.sequence();
                    }
                    case ClientboundPacket.SetEntityData data when state.selfId >= 0 && data.entityId() == state.selfId -> {
                        for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                            if (e.index() == 0 && (e.value() & 8L) != 0L) {
                                state.pendingSprintEchoSeq = out.sequence();
                            }
                        }
                    }
                    default -> { }
                }
            }
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.ContainerClick click -> {
                if (click.containerId() > 0 && state.sawSessionStart && !state.containerHistoryGap
                        && !state.openContainers.contains(click.containerId())
                        && !state.preRespawnContainers.contains(click.containerId())) {
                    state.clickSeq = in.sequence();
                    state.clickNano = in.nanoTime();
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "unopened-container-click");
                    details.put("containerId", Integer.toString(click.containerId()));
                    report(session, state, in,
                            "ContainerClick targeting container " + click.containerId() + " with no open screen",
                            details);
                    return;
                }
                if (!state.isServerClosePending()) {
                    state.clickedThisTick = true;
                    state.clickedContainerId = click.containerId();
                    state.clickSeq = in.sequence();
                    state.clickNano = in.nanoTime();
                }
            }
            case ServerboundPacket.ContainerClose close -> {
                state.clearTick();
                if (close.containerId() > 0) {
                    state.openContainers.remove(close.containerId());
                    state.preRespawnContainers.remove(close.containerId());
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> {
                state.clearTick();
                if (state.sprintEchoGraceTicks > 0) {
                    state.sprintEchoGraceTicks--;
                }
            }
            case ServerboundPacket.PlayerInput input -> {
                if (state.clickedThisTick && !state.isServerClosePending()
                        && (input.forward() || input.backward() || input.left() || input.right())) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "walk-during-container-click");
                    details.put("containerId", Integer.toString(state.clickedContainerId));
                    report(session, state, in,
                            "pressed walking keys in the same tick as ContainerClick (container " + state.clickedContainerId + ")",
                            details);
                    state.clearTick();
                }
            }
            case ServerboundPacket.PlayerCommand cmd
                    when cmd.action() == ServerboundPacket.PlayerCommand.Action.START_SPRINTING -> {
                if (state.clickedThisTick && !state.isServerClosePending()) {
                    if (state.isSprintEchoSuppressed()) {
                        state.clearTick();
                    } else {
                        Map<String, String> details = new TreeMap<>();
                        details.put("reason", "action-during-container-click");
                        details.put("action", "START_SPRINTING");
                        details.put("containerId", Integer.toString(state.clickedContainerId));
                        report(session, state, in,
                                "sent START_SPRINTING in the same tick after ContainerClick (container " + state.clickedContainerId + ")",
                                details);
                        state.clearTick();
                    }
                }
            }
            default -> {
                if (state.clickedThisTick && !state.isServerClosePending() && isKeybindWorldOrCombatAction(in.packet())) {
                    String act = ActionGrammarCheck.action(in.packet());
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "action-during-container-click");
                    details.put("action", act == null ? in.packet().getClass().getSimpleName() : act);
                    details.put("containerId", Integer.toString(state.clickedContainerId));
                    report(session, state, in,
                            "performed keybind action (" + details.get("action")
                                    + ") in the same tick after ContainerClick (container " + state.clickedContainerId + ")",
                            details);
                    state.clearTick();
                }
            }
        }
    }

    private static boolean isKeybindWorldOrCombatAction(ServerboundPacket packet) {
        return switch (packet) {
            case ServerboundPacket.InteractEntity ignored -> true;
            case ServerboundPacket.UseItem ignored -> true;
            case ServerboundPacket.UseItemOn ignored -> true;
            case ServerboundPacket.PlayerAction a -> switch (a.action()) {
                case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, STAB -> true;
                default -> false;
            };
            default -> false;
        };
    }

    private void report(PlayerSession session, State state, TimelineEvent.Inbound at, String summary, Map<String, String> details) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = state.admissibility.evaluate(state.clickSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, state.clickSeq, at.sequence(),
                state.clickNano, at.nanoTime(), summary, details, admissibility));
        state.lastReportNanos = at.nanoTime();
    }
}
