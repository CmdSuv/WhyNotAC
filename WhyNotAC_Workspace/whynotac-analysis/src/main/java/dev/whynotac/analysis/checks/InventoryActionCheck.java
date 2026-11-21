package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.Map;
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

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public InventoryActionCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        long lastReportNanos = Long.MIN_VALUE;
        boolean clickedThisTick;
        int clickedContainerId;
        long clickSeq;
        long clickNano;
        boolean sawSessionStart;
        boolean containerHistoryGap;
        final java.util.Set<Integer> openContainers = new java.util.HashSet<>();

        void clearTick() {
            clickedThisTick = false;
        }

        void resetContainers(boolean gap) {
            clearTick();
            openContainers.clear();
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
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> state.resetContainers(true);
            case TimelineEvent.Outbound out -> {
                switch (out.packet()) {
                    case ClientboundPacket.Login ignored -> state.resetContainers(false);
                    case ClientboundPacket.Respawn ignored -> state.resetContainers(false);
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
                    case ClientboundPacket.ContainerClose ignored -> state.clearTick();
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
                        && !state.openContainers.contains(click.containerId())) {
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
                state.clickedThisTick = true;
                state.clickedContainerId = click.containerId();
                state.clickSeq = in.sequence();
                state.clickNano = in.nanoTime();
            }
            case ServerboundPacket.ContainerClose close -> {
                state.clearTick();
                if (close.containerId() > 0) {
                    state.openContainers.remove(close.containerId());
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> state.clearTick();
            case ServerboundPacket.PlayerInput input -> {
                if (state.clickedThisTick && (input.forward() || input.backward() || input.left() || input.right())) {
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
                if (state.clickedThisTick) {
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
            default -> {
                if (state.clickedThisTick && isKeybindWorldOrCombatAction(in.packet())) {
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
