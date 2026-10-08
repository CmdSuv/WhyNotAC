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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick framing (P-M): the vanilla 1.21.11 client sends at most one movement packet per client tick,
 * plus exactly-shaped replies to a few server packets.
 *
 * <p>Soundness (decompiled 1.21.11 client): every {@code ServerboundMovePlayerPacket} comes from
 * <ul>
 *   <li>{@code LocalPlayer.sendPosition()} or, while riding, {@code LocalPlayer.tick()}: once per tick,
 *       and each tick ends with {@code ServerboundClientTickEndPacket} ({@code Minecraft.tick});</li>
 *   <li>{@code handleMovePlayer} (a teleport): {@code AcceptTeleportation(id)} and, sent right after it from
 *       the same thread, {@code PosRot(..., onGround=false, horizontalCollision=false)};</li>
 *   <li>{@code handleRotatePlayer}: {@code Rot(..., false, false)};</li>
 *   <li>{@code handleTeleportEntity} for the player's removed vehicle: {@code PosRot(..., false, false)}.</li>
 * </ul>
 * So between two ClientTickEnd: one movement packet of any shape, plus
 * <ul>
 *   <li>the first movement packet after the accept of a teleport the server sent (paired by id, so a
 *       client-provoked setback buys exactly its own reply and nothing else; packets between them do not
 *       matter: {@code handleKeepAlive} and {@code handleChunkBatchFinished} answer from the network
 *       thread, which can write between the two packets the main thread queued);</li>
 *   <li>one rotation-only packet per rotation packet and one position-and-rotation packet per entity
 *       teleport (conservatively any entity) the client may have handled in the interval. Such an allowance
 *       ("token") lasts from the moment the server sent the packet until the end of the interval in which
 *       the client acknowledged a transaction sent after it.</li>
 * </ul>
 * Replies are matched by kind and shape, not by their flags: vanilla sends {@code onGround=false,
 * horizontalCollision=false}, but requiring that adds nothing to soundness (vanilla is never flagged
 * either way) and would flag harmless client variants.
 * Excess is judged as each packet arrives: a client that stops sending ClientTickEnd does not escape.
 * A client that sends extra ClientTickEnd instead is caught by {@link ClientClockCheck}.
 *
 * <p>Only admissible windows yield evidence (native 774 at the edge, no lost events, ...). A modified
 * client is proven, not a cheat: legitimate mods that send extra movement packets produce this too.
 * Movement exploits that fake fall distance or teleport by sending several positions per tick
 * (mace damage hacks, click teleports) do.
 */
public final class TickFramingCheck implements TimelineListener {

    public static final String ID = "tick-framing";
    public static final int VERSION = 2;
    static final int MAX_TOKENS = 256;
    static final int MAX_TRACKED_PINGS = 4_096;
    static final int MAX_PENDING_TELEPORTS = 1_024;

    private static final Object STATE = new Object();

    private final EvidenceSink sink;

    public TickFramingCheck(EvidenceSink sink) {
        this.sink = sink;
    }

    private static final class Token {
        final long sequence;
        boolean expiring; // acknowledged-after in the current interval: usable until it closes

        Token(long sequence) {
            this.sequence = sequence;
        }
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final ArrayDeque<Token> rotationTokens = new ArrayDeque<>();
        final ArrayDeque<Token> vehicleTokens = new ArrayDeque<>();
        final Map<Integer, Long> pingSequence = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
                return size() > MAX_TRACKED_PINGS;
            }
        };
        /** Teleport ids the server sent and the client has not accepted yet. */
        final java.util.Set<Integer> pendingTeleports = new java.util.LinkedHashSet<>();
        boolean saturated;
        /**
         * A teleport the server sent was accepted and no movement packet has followed yet. Not cleared by
         * other packets: the client's network thread sends keep-alive and chunk-batch answers itself, so one
         * can land between the accept and its reply (both queued by the main thread).
         */
        boolean afterAccept;
        long intervalStart = Long.MIN_VALUE;
        long intervalStartNanos;
        final List<Long> moves = new ArrayList<>();
        int rotationShaped;  // moves shaped like a rotation reply: rotation only
        int posRotShaped;    // moves shaped like a vehicle-teleport reply: position and rotation
        int teleportReplies;
        boolean judged;      // this interval already produced evidence or a rejection
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(state, out);
            case TimelineEvent.Acknowledged ack -> onAcknowledged(state, ack);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private static void onOutbound(State state, TimelineEvent.Outbound out) {
        switch (out.packet()) {
            case ClientboundPacket.PlayerPosition teleport -> {
                if (state.pendingTeleports.size() < MAX_PENDING_TELEPORTS) {
                    state.pendingTeleports.add(teleport.teleportId());
                }
            }
            case ClientboundPacket.PlayerRotation ignored -> addToken(state, state.rotationTokens, out.sequence());
            case ClientboundPacket.TeleportEntity ignored -> addToken(state, state.vehicleTokens, out.sequence()); // conservative: any entity
            case ClientboundPacket.Ping ping -> state.pingSequence.put(ping.id(), out.sequence());
            default -> { }
        }
    }

    private static void addToken(State state, ArrayDeque<Token> tokens, long sequence) {
        if (tokens.size() >= MAX_TOKENS) {
            state.saturated = true; // a flood of server packets: stay silent until it has drained
            return;
        }
        tokens.addLast(new Token(sequence));
    }

    private static void onAcknowledged(State state, TimelineEvent.Acknowledged ack) {
        Long pingSequence = state.pingSequence.remove(ack.transactionId());
        if (pingSequence == null) {
            return;
        }
        for (ArrayDeque<Token> tokens : List.of(state.rotationTokens, state.vehicleTokens)) {
            for (Token token : tokens) {
                if (token.sequence < pingSequence) {
                    token.expiring = true; // the client handled it before this ping: its reply is in this interval or earlier
                }
            }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.AcceptTeleportation accept -> {
                if (state.pendingTeleports.remove(accept.teleportId())) {
                    state.afterAccept = true;
                }
            }
            case ServerboundPacket.MovePlayer move -> {
                boolean reply = state.afterAccept;
                state.afterAccept = false;
                if (state.intervalStart == Long.MIN_VALUE) {
                    return;
                }
                if (reply) {
                    state.teleportReplies++; // the teleport's own reply, paired with its accept
                    return;
                }
                state.moves.add(in.sequence());
                if (move.hasRotation() && move.hasPosition()) {
                    state.posRotShaped++;
                } else if (move.hasRotation()) {
                    state.rotationShaped++;
                }
                judge(session, state, in);
            }
            case ServerboundPacket.ClientTickEnd ignored -> closeInterval(session, state, in);
            default -> { }
        }
    }

    /** Evidence (or a rejection) once per interval, as soon as the packets exceed what vanilla allows. */
    private void judge(PlayerSession session, State state, TimelineEvent.Inbound at) {
        if (state.judged || state.saturated) {
            return;
        }
        int rotationAllowance = Math.min(state.rotationShaped, state.rotationTokens.size());
        int vehicleAllowance = Math.min(state.posRotShaped, state.vehicleTokens.size());
        int unexplained = state.moves.size() - rotationAllowance - vehicleAllowance;
        if (unexplained <= 1) {
            return; // one of them is the tick's own packet
        }
        state.judged = true;
        Admissibility admissibility = state.admissibility.evaluate(state.intervalStart);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        int possible = 1 + rotationAllowance + vehicleAllowance;
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, state.intervalStart, at.sequence(),
                state.intervalStartNanos, at.nanoTime(),
                state.moves.size() + " movement packets in one client tick (at most " + possible + " possible)",
                Map.of("movePackets", Integer.toString(state.moves.size()), "rotationAllowance", Integer.toString(rotationAllowance),
                        "vehicleAllowance", Integer.toString(vehicleAllowance), "teleportReplies", Integer.toString(state.teleportReplies),
                        "moveSequences", state.moves.toString()),
                admissibility));
    }

    private void closeInterval(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        if (state.intervalStart != Long.MIN_VALUE) {
            judge(session, state, tickEnd);
        }
        // Tokens whose transaction came back in this interval cannot cover a later one. Tokens are not
        // spent: which packet was the tick's own is unknown, and keeping an allowance only errs towards the client.
        for (ArrayDeque<Token> tokens : List.of(state.rotationTokens, state.vehicleTokens)) {
            tokens.removeIf(token -> token.expiring);
        }
        if (state.rotationTokens.isEmpty() && state.vehicleTokens.isEmpty()) {
            state.saturated = false;
        }
        state.moves.clear();
        state.rotationShaped = 0;
        state.posRotShaped = 0;
        state.teleportReplies = 0;
        state.judged = false;
        state.intervalStart = tickEnd.sequence();
        state.intervalStartNanos = tickEnd.nanoTime();
    }
}
