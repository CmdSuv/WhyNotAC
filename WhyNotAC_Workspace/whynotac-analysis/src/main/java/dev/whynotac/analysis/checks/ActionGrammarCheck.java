package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Action grammar (P-M): the vanilla 1.21.11 client acts (attacks, uses items, digs, drops, swaps hands, picks a
 * hotbar slot) early in its tick, before its movement, swings right after an attack, and neither acts nor walks while a
 * menu covers its screen.
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
 *       handled could explain ({@link MoveReplies}, the tick-framing allowances);</li>
 *   <li>while a menu the server opened is certainly on the client's screen ({@link MenuScreen}), none of these actions
 *       (the hotbar slot aside: the tick's start sends it under a screen too) and no movement key (forward, backward,
 *       left, right; jump, sneak and sprint aside, which auto-jump and the toggle options may hold).</li>
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
    static final String IN_MENU = "actionsInMenu";

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

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
        final MenuScreen menu = new MenuScreen();
        long withoutSwing;
        long afterMovement;
        long inMenu;
    }

    /** It reads no entity motion: the entity replica follows the entities. */
    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out -> {
                state.replies.onOutbound(out);
                state.menu.onOutbound(out);
            }
            case TimelineEvent.Acknowledged ack -> {
                state.replies.onAcknowledged(ack);
                state.menu.onAcknowledged(ack);
            }
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
            case ServerboundPacket.ContainerClose ignored -> state.menu.forget(); // the client closed its screen
            case ServerboundPacket.PlayerInput input when state.menu.open() && (input.forward() || input.backward() || input.left() || input.right()) -> {
                state.inMenu++;
                report(session, state, state.menu.since(), state.menu.sinceNanos(), in, "movement keys pressed while a menu was open");
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
                if (state.menu.open() && !(packet instanceof ServerboundPacket.SetCarriedItem)) { // the tick's start sends it under a screen too
                    state.inMenu++;
                    report(session, state, state.menu.since(), state.menu.sinceNanos(), in, action + " while a menu was open");
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
        details.put(IN_MENU, Long.toString(state.inMenu));
        state.withoutSwing = 0;
        state.afterMovement = 0;
        state.inMenu = 0;
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, from, at.sequence(), fromNanos, at.nanoTime(), summary,
                details, admissibility));
    }

    /**
     * Whether a menu the server opened is certainly on the client's screen: {@code handleOpenScreen} always shows a vanilla
     * menu type's screen, and the screen stays until the client closes it itself (every way it does sends
     * {@code ContainerClose}: escape or the inventory key, the debug options key, a portal, death under the screen) or the
     * server sends something that closes or covers it. So from the client's answer to a transaction sent after the
     * {@code OpenScreen} until either, the client runs no {@code handleKeybinds} and its keys stay released
     * ({@code setScreen} releases them all; with a screen open, key and button presses reach the screen, not the key
     * mappings).
     */
    static final class MenuScreen {
        /** Unmodelled server packets whose handlers close or cover the screen (by their id's path). */
        static final Set<String> COVERING = Set.of("open_sign_editor", "open_book", "player_combat_kill", "show_dialog", "clear_dialog",
                "resource_pack_push", "start_configuration", "transfer", "disconnect");
        /** {@code ClientboundGameEventPacket}'s WIN_GAME (credits) and DEMO_EVENT (the demo screen). */
        static final int WIN_GAME = 4;
        static final int DEMO_EVENT = 5;

        private final Map<Integer, Long> pings = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
                return size() > MoveReplies.MAX_TRACKED_PINGS;
            }
        };
        /** The OpenScreen, while nothing that may close or cover its screen was sent since. */
        private long sent = Long.MIN_VALUE;
        private long sentNanos;
        private boolean open;

        void onOutbound(TimelineEvent.Outbound out) {
            switch (out.packet()) {
                case ClientboundPacket.OpenScreen screen -> {
                    forget();
                    if (screen.menuType() != null) {
                        sent = out.sequence();
                        sentNanos = out.nanoTime();
                    }
                }
                case ClientboundPacket.Ping ping -> pings.put(ping.id(), out.sequence());
                case ClientboundPacket.MountScreenOpen ignored -> forget();
                case ClientboundPacket.ContainerClose ignored -> forget();
                case ClientboundPacket.Respawn ignored -> forget();
                case ClientboundPacket.Login ignored -> forget();
                case ClientboundPacket.GameEvent event when event.type() == WIN_GAME || event.type() == DEMO_EVENT -> forget();
                case ClientboundPacket.Unmodeled other when COVERING.contains(other.type().substring(other.type().indexOf(':') + 1)) -> forget();
                default -> { }
            }
        }

        void onAcknowledged(TimelineEvent.Acknowledged ack) {
            Long ping = pings.remove(ack.transactionId());
            if (ping != null && sent != Long.MIN_VALUE && ping > sent) {
                open = true; // the client handled the OpenScreen before this transaction
            }
        }

        void forget() {
            sent = Long.MIN_VALUE;
            open = false;
        }

        boolean open() {
            return open;
        }

        long since() {
            return sent;
        }

        long sinceNanos() {
            return sentNanos;
        }
    }
}
