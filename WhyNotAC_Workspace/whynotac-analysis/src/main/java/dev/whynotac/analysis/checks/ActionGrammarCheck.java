package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.Map;
import java.util.TreeMap;

/**
 * Action grammar (P-M): the vanilla 1.21.11 client acts (attacks, uses items, digs, drops, swaps hands, picks a
 * hotbar slot) early in its tick, before its movement, and swings right after an attack.
 *
 * <p>Soundness (decompiled 1.21.11 client):
 * <ul>
 *   <li>Each of these packets has one sender, and each sender runs only in {@code Minecraft.handleKeybinds} (the
 *       keys' queued clicks and the held use key) or, for the hotbar slot, also at the very start of the tick
 *       ({@code MultiPlayerGameMode.tick}): {@code ServerboundInteractPacket} (ATTACK from {@code attack}, INTERACT and
 *       INTERACT_AT from {@code interact}/{@code interactAt}), {@code ServerboundUseItemOnPacket} and
 *       {@code ServerboundUseItemPacket} ({@code useItemOn}, {@code useItem}; all four called only from
 *       {@code Minecraft.startAttack} and {@code startUseItem}), {@code ServerboundPlayerActionPacket} (digging from
 *       {@code startDestroyBlock}, {@code continueDestroyBlock} and {@code stopDestroyBlock}, called from
 *       {@code startAttack} and {@code continueAttack}; RELEASE_USE_ITEM from {@code releaseUsingItem}; STAB from
 *       {@code piercingAttack}; the drops from {@code LocalPlayer.drop}; SWAP_ITEM_WITH_OFFHAND from the swap key) and
 *       {@code ServerboundSetCarriedItemPacket} ({@code ensureHasSentCarriedItem}).</li>
 *   <li>{@code Minecraft.tick} runs {@code handleKeybinds} before {@code level.tickEntities()}, where
 *       {@code LocalPlayer.tick} (riding too) sends the tick's movement packet, and ends with
 *       {@code ServerboundClientTickEndPacket}. Packet handlers (teleport, rotation and vehicle-teleport replies)
 *       run before the frame's ticks.</li>
 *   <li>After the ATTACK, {@code startAttack} swings the main hand unless {@code player.isSpectator()}; after the
 *       STAB always. {@code LocalPlayer.swing} sends {@code ServerboundSwingPacket}. In between only
 *       {@code Player.attack} runs, which sends nothing. The client's network thread answers keep-alives and chunk
 *       batches itself, so those can land between two packets the main thread queued; nothing else can.</li>
 * </ul>
 * So:
 * <ol>
 *   <li>after a STAB, or an ATTACK while the player cannot be a spectator ({@code ClientSelf#mayBeSpectator}),
 *       the next packet other than {@code KeepAlive} and {@code ChunkBatchReceived} is the main hand's swing;</li>
 *   <li>none of these actions follows, in the same client tick, a movement packet that can only be the tick's own:
 *       not the reply to an accepted teleport, nor a packet a rotation or entity teleport the client may have
 *       handled could explain ({@link MoveReplies}, the tick-framing allowances).</li>
 * </ol>
 * Each rule is judged as packets arrive. The second needs the tick's start, so it waits for a first
 * ClientTickEnd. A modified client is proven, not a cheat: a mod may reorder these packets too.
 *
 * <p>At most one finding per {@link #REPORT_INTERVAL_NANOS} per session; the next one counts what broke the
 * grammar in between.
 */
public final class ActionGrammarCheck implements TimelineListener {

    public static final String ID = "action-grammar";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final String NO_SWING = "attacksWithoutSwing";
    static final String AFTER_MOVEMENT = "actionsAfterMovement";

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public ActionGrammarCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final MoveReplies replies = new MoveReplies();
        /** The last ClientTickEnd: the current tick started after it (none seen: unknown). */
        long tickStart = Long.MIN_VALUE;
        long tickStartNanos;
        /** The tick's own movement packet, once one is certain in this tick. */
        long ownMove = Long.MIN_VALUE;
        /** The attack or stab whose swing is the next main-thread packet. */
        long swingDue = Long.MIN_VALUE;
        long swingDueNanos;
        String swingFor;
        long lastReportNanos = Long.MIN_VALUE;
        long withoutSwing;
        long afterMovement;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out -> state.replies.onOutbound(out);
            case TimelineEvent.Acknowledged ack -> state.replies.onAcknowledged(ack);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        ServerboundPacket packet = in.packet();
        if (packet instanceof ServerboundPacket.KeepAlive || packet instanceof ServerboundPacket.ChunkBatchReceived) {
            return; // the network thread's own answers: anywhere
        }
        if (state.swingDue != Long.MIN_VALUE) {
            long due = state.swingDue;
            state.swingDue = Long.MIN_VALUE;
            if (!(packet instanceof ServerboundPacket.Swing swing && swing.hand() == Hand.MAIN_HAND)) {
                state.withoutSwing++;
                report(session, state, due, state.swingDueNanos, in,
                        state.swingFor + " not followed by the main hand's swing (next: " + packet.getClass().getSimpleName() + ")");
            }
        }
        switch (packet) {
            case ServerboundPacket.AcceptTeleportation accept -> state.replies.onAccept(accept);
            case ServerboundPacket.MovePlayer move -> {
                boolean reply = state.replies.takeTeleportReply();
                if (!reply && state.ownMove == Long.MIN_VALUE && state.tickStart != Long.MIN_VALUE && !mayBeReply(state.replies, move)) {
                    state.ownMove = in.sequence();
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> {
                state.replies.closeInterval();
                state.tickStart = in.sequence();
                state.tickStartNanos = in.nanoTime();
                state.ownMove = Long.MIN_VALUE;
            }
            default -> {
                String action = action(packet);
                if (action == null) {
                    return;
                }
                if (state.ownMove != Long.MIN_VALUE) {
                    state.afterMovement++;
                    report(session, state, state.tickStart, state.tickStartNanos, in,
                            action + " after the tick's own movement packet (" + state.ownMove + ")");
                }
                boolean attack = packet instanceof ServerboundPacket.InteractEntity interact
                        && interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK;
                boolean stab = packet instanceof ServerboundPacket.PlayerAction a && a.action() == ServerboundPacket.PlayerAction.Action.STAB;
                if (stab || attack && !replicas.self().self(session).mayBeSpectator()) {
                    state.swingDue = in.sequence();
                    state.swingDueNanos = in.nanoTime();
                    state.swingFor = action;
                }
            }
        }
    }

    /** What the client did, if this packet is one only {@code handleKeybinds} (or the tick's start) sends; else null. */
    static String action(ServerboundPacket packet) {
        return switch (packet) {
            case ServerboundPacket.InteractEntity interact -> switch (interact.kind()) {
                case ATTACK -> "attack on entity " + interact.entityId();
                case INTERACT, INTERACT_AT -> "interaction with entity " + interact.entityId();
            };
            case ServerboundPacket.UseItemOn ignored -> "use of the item on a block";
            case ServerboundPacket.UseItem ignored -> "use of the item";
            case ServerboundPacket.SetCarriedItem ignored -> "hotbar selection";
            case ServerboundPacket.PlayerAction action -> switch (action.action()) {
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> "digging (" + action.action() + ")";
                case RELEASE_USE_ITEM -> "release of the item in use";
                case DROP_ITEM, DROP_ALL_ITEMS -> "item drop";
                case SWAP_ITEM_WITH_OFFHAND -> "hand swap";
                case STAB -> "stab";
                case UNKNOWN -> null;
            };
            default -> null;
        };
    }

    /** A rotation-only or position-and-rotation packet that a rotation or entity teleport the client may have handled explains. */
    private static boolean mayBeReply(MoveReplies replies, ServerboundPacket.MovePlayer move) {
        if (replies.saturated()) {
            return true;
        }
        if (move.hasRotation() && move.hasPosition()) {
            return replies.vehicleTokens() > 0;
        }
        return move.hasRotation() && replies.rotationTokens() > 0;
    }

    private void report(PlayerSession session, State state, long from, long fromNanos, TimelineEvent.Inbound at, String summary) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        state.lastReportNanos = at.nanoTime();
        Admissibility admissibility = state.admissibility.evaluate(from);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put(NO_SWING, Long.toString(state.withoutSwing));
        details.put(AFTER_MOVEMENT, Long.toString(state.afterMovement));
        state.withoutSwing = 0;
        state.afterMovement = 0;
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, from, at.sequence(), fromNanos, at.nanoTime(), summary,
                details, admissibility));
    }
}
