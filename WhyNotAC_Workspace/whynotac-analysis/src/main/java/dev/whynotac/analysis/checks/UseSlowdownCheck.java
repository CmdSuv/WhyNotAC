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
 * Sound item-use &amp; AutoBlock / NoSlow intra-tick packet ordering check ({@code use-item-state}, P-M, SHADOW).
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
 * <p>Three deterministic intra-tick invariants follow for every unmodified client:
 * <ul>
 *   <li><b>{@code release-before-attack-same-tick}</b>: {@code RELEASE_USE_ITEM} cannot precede
 *       {@code InteractEntity(ATTACK)}, {@code START_DESTROY_BLOCK}, or {@code STAB} in the same tick
 *       (catches {@code AutoBlock} / {@code SwordBlock} unblocking to attack in the same tick).</li>
 *   <li><b>{@code release-before-use-same-tick}</b>: {@code RELEASE_USE_ITEM} cannot precede
 *       {@code UseItem} or {@code UseItemOn} in the same tick (catches {@code AutoBlock} / {@code FastUse}
 *       release-and-reuse cycles).</li>
 *   <li><b>{@code action-after-sprint-command-same-tick}</b>: {@code START_SPRINTING} / {@code STOP_SPRINTING}
 *       is emitted in {@code sendPosition()} after {@code handleKeybinds()}, so neither
 *       {@code RELEASE_USE_ITEM} nor {@code UseItem}/{@code UseItemOn}/{@code InteractEntity} can follow
 *       a sprint command in the same tick (catches {@code NoSlow} pre-motion sprint/use packet hooks).</li>
 * </ul>
 */
public final class UseSlowdownCheck implements TimelineListener {

    public static final String ID = "use-item-state";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public UseSlowdownCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        long lastReportNanos = Long.MIN_VALUE;

        boolean releasedInCurrentTick;
        long releaseSeq = -1L;
        long releaseNano;

        boolean sprintCommandInCurrentTick;
        long sprintCmdSeq = -1L;
        long sprintCmdNano;
        String sprintCmdName;

        void resetTick() {
            releasedInCurrentTick = false;
            releaseSeq = -1L;
            sprintCommandInCurrentTick = false;
            sprintCmdSeq = -1L;
            sprintCmdName = null;
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
            case TimelineEvent.Gap ignored -> state.resetTick();
            case TimelineEvent.Outbound out -> {
                if (out.packet() instanceof ClientboundPacket.Respawn
                        || out.packet() instanceof ClientboundPacket.Login) {
                    state.resetTick();
                }
            }
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.MovePlayer ignored -> state.resetTick();
            case ServerboundPacket.ClientTickEnd ignored -> state.resetTick();
            case ServerboundPacket.AcceptTeleportation ignored -> state.resetTick();
            case ServerboundPacket.PlayerAction action -> {
                if (action.action() == ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM) {
                    if (state.sprintCommandInCurrentTick) {
                        long cmdSeq = state.sprintCmdSeq;
                        long cmdNano = state.sprintCmdNano;
                        String cmdName = state.sprintCmdName;
                        state.resetTick();
                        Map<String, String> details = new TreeMap<>();
                        details.put("reason", "action-after-sprint-command-same-tick");
                        details.put("sprintCommand", cmdName);
                        details.put("action", "RELEASE_USE_ITEM");
                        report(session, state, cmdSeq, cmdNano, in,
                                "sent RELEASE_USE_ITEM after " + cmdName + " in the same tick (NoSlow packet hook)",
                                details);
                        return;
                    }
                    state.releasedInCurrentTick = true;
                    state.releaseSeq = in.sequence();
                    state.releaseNano = in.nanoTime();
                } else if (action.action() == ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK
                        || action.action() == ServerboundPacket.PlayerAction.Action.STAB) {
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
                    }
                }
            }
            case ServerboundPacket.InteractEntity interact -> {
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
                }
            }
            case ServerboundPacket.UseItem ignored -> onUsePacket(session, state, in, "UseItem");
            case ServerboundPacket.UseItemOn ignored -> onUsePacket(session, state, in, "UseItemOn");
            case ServerboundPacket.PlayerCommand cmd -> {
                if (cmd.action() == ServerboundPacket.PlayerCommand.Action.START_SPRINTING
                        || cmd.action() == ServerboundPacket.PlayerCommand.Action.STOP_SPRINTING) {
                    state.sprintCommandInCurrentTick = true;
                    state.sprintCmdSeq = in.sequence();
                    state.sprintCmdNano = in.nanoTime();
                    state.sprintCmdName = cmd.action().name();
                }
            }
            default -> { }
        }
    }

    private void onUsePacket(PlayerSession session, State state, TimelineEvent.Inbound in, String packetName) {
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
        }
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
