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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tick framing (P-M): the vanilla 1.21.11 client sends at most one movement packet per client tick.
 *
 * <p>Soundness (decompiled 1.21.11 client): every {@code ServerboundMovePlayerPacket} comes from
 * <ul>
 *   <li>{@code LocalPlayer.sendPosition()} or, while riding, {@code LocalPlayer.tick()}: once per tick,
 *       and each tick ends with {@code ServerboundClientTickEndPacket} ({@code Minecraft.tick});
 *   <li>or a reply to a server packet, sent while the client handles it at frame start:
 *       {@code handleMovePlayer} (a teleport: AcceptTeleportation + PosRot), {@code handleRotatePlayer}
 *       (Rot), {@code handleTeleportEntity} for the player's removed vehicle (PosRot).
 * </ul>
 * So between two ClientTickEnd, at most one movement packet plus one reply per such server packet
 * the client may have handled in that interval. A server packet allows a reply ("token") from the
 * moment it is sent until the end of the interval in which the client acknowledged a transaction
 * sent after it: the reply precedes that pong on the wire, in the same frame. Tokens are spent only
 * on intervals that need them, so a tick's own packet can never use up a reply's allowance.
 *
 * <p>Only admissible windows yield evidence (native 774 at the edge, no lost events, ...). A modified
 * client is proven, not a cheat: legitimate mods that send extra movement packets produce this too.
 * Movement exploits that fake fall distance or teleport by sending several positions per tick
 * (mace damage hacks, click teleports) do.
 */
public final class TickFramingCheck implements TimelineListener {

    public static final String ID = "tick-framing";
    public static final int VERSION = 1;
    static final int MAX_TOKENS = 256;
    static final int MAX_TRACKED_PINGS = 4_096;

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
        final ArrayDeque<Token> tokens = new ArrayDeque<>();
        final Map<Integer, Long> pingSequence = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
                return size() > MAX_TRACKED_PINGS;
            }
        };
        boolean saturated;
        long intervalStart = Long.MIN_VALUE;
        long intervalStartNanos;
        final List<Long> moves = new ArrayList<>();
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
            case ClientboundPacket.PlayerPosition ignored -> addToken(state, out.sequence());
            case ClientboundPacket.PlayerRotation ignored -> addToken(state, out.sequence());
            case ClientboundPacket.TeleportEntity ignored -> addToken(state, out.sequence()); // conservative: any entity
            case ClientboundPacket.Ping ping -> state.pingSequence.put(ping.id(), out.sequence());
            default -> { }
        }
    }

    private static void addToken(State state, long sequence) {
        if (state.tokens.size() >= MAX_TOKENS) {
            state.saturated = true; // a flood of teleports: stay silent until it has drained
            return;
        }
        state.tokens.addLast(new Token(sequence));
    }

    private static void onAcknowledged(State state, TimelineEvent.Acknowledged ack) {
        Long pingSequence = state.pingSequence.remove(ack.transactionId());
        if (pingSequence == null) {
            return;
        }
        for (Token token : state.tokens) {
            if (token.sequence < pingSequence) {
                token.expiring = true; // the client handled it before this ping: its reply is in this interval or earlier
            }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.MovePlayer ignored -> {
                if (state.intervalStart != Long.MIN_VALUE) {
                    state.moves.add(in.sequence());
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> closeInterval(session, state, in);
            default -> { }
        }
    }

    private void closeInterval(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        if (state.intervalStart != Long.MIN_VALUE) {
            int excess = state.moves.size() - 1;
            int usedTokens = 0;
            while (excess > 0 && !state.tokens.isEmpty()) {
                state.tokens.removeFirst(); // a reply: the oldest server packet that allowed one
                excess--;
                usedTokens++;
            }
            if (excess > 0 && !state.saturated) {
                Admissibility admissibility = state.admissibility.evaluate(state.intervalStart);
                if (admissibility.admissible()) {
                    sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, state.intervalStart,
                            tickEnd.sequence(), state.intervalStartNanos, tickEnd.nanoTime(),
                            state.moves.size() + " movement packets in one client tick (at most " + (1 + usedTokens) + " possible)",
                            Map.of("movePackets", Integer.toString(state.moves.size()), "replyAllowance", Integer.toString(usedTokens),
                                    "moveSequences", state.moves.toString()),
                            admissibility));
                } else {
                    sink.rejected(session, ID, admissibility);
                }
            }
        }
        // Tokens whose transaction came back in this interval cannot cover a later one.
        for (Iterator<Token> it = state.tokens.iterator(); it.hasNext(); ) {
            if (it.next().expiring) {
                it.remove();
            }
        }
        if (state.tokens.isEmpty()) {
            state.saturated = false;
        }
        state.moves.clear();
        state.intervalStart = tickEnd.sequence();
        state.intervalStartNanos = tickEnd.nanoTime();
    }
}
