package dev.whynotac.analysis.checks;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which movement packets of a client tick may be replies to the server rather than the tick's own
 * ({@link TickFramingCheck} has the client's rules): the reply to a teleport, paired with its accept by id,
 * and allowances ("tokens") for rotation-only and position-and-rotation packets, one per rotation packet
 * and per entity teleport (conservatively any entity) the client may have handled in the interval. A token
 * lasts from the moment the server sent the packet until the end of the interval in which the client
 * acknowledged a transaction sent after it.
 *
 * <p>Fed every event of one session, in order; {@link #closeInterval} at each ClientTickEnd.
 */
final class MoveReplies {

    static final int MAX_TOKENS = 256;
    static final int MAX_TRACKED_PINGS = 4_096;
    static final int MAX_PENDING_TELEPORTS = 1_024;

    private static final class Token {
        final long sequence;
        boolean expiring; // acknowledged-after in the current interval: usable until it closes

        Token(long sequence) {
            this.sequence = sequence;
        }
    }

    private final ArrayDeque<Token> rotationTokens = new ArrayDeque<>();
    private final ArrayDeque<Token> vehicleTokens = new ArrayDeque<>();
    private final Map<Integer, Long> pingSequence = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
            return size() > MAX_TRACKED_PINGS;
        }
    };
    /** Teleport ids the server sent and the client has not accepted yet. */
    private final java.util.Set<Integer> pendingTeleports = new java.util.LinkedHashSet<>();
    private boolean saturated;
    /**
     * A teleport the server sent was accepted and no movement packet has followed yet. Not cleared by
     * other packets: the client's network thread sends keep-alive and chunk-batch answers itself, so one
     * can land between the accept and its reply (both queued by the main thread).
     */
    private boolean afterAccept;

    void onOutbound(TimelineEvent.Outbound out) {
        switch (out.packet()) {
            case ClientboundPacket.PlayerPosition teleport -> {
                if (pendingTeleports.size() < MAX_PENDING_TELEPORTS) {
                    pendingTeleports.add(teleport.teleportId());
                }
            }
            case ClientboundPacket.PlayerRotation ignored -> addToken(rotationTokens, out.sequence());
            case ClientboundPacket.TeleportEntity ignored -> addToken(vehicleTokens, out.sequence()); // conservative: any entity
            case ClientboundPacket.Ping ping -> pingSequence.put(ping.id(), out.sequence());
            default -> { }
        }
    }

    private void addToken(ArrayDeque<Token> tokens, long sequence) {
        if (tokens.size() >= MAX_TOKENS) {
            saturated = true; // a flood of server packets: stay silent until it has drained
            return;
        }
        tokens.addLast(new Token(sequence));
    }

    void onAcknowledged(TimelineEvent.Acknowledged ack) {
        Long ping = pingSequence.remove(ack.transactionId());
        if (ping == null) {
            return;
        }
        for (ArrayDeque<Token> tokens : List.of(rotationTokens, vehicleTokens)) {
            for (Token token : tokens) {
                if (token.sequence < ping) {
                    token.expiring = true; // the client handled it before this ping: its reply is in this interval or earlier
                }
            }
        }
    }

    void onAccept(ServerboundPacket.AcceptTeleportation accept) {
        if (pendingTeleports.remove(accept.teleportId())) {
            afterAccept = true;
        }
    }

    /** A movement packet arrived: whether it is the reply to the teleport accepted last (paired with that accept only). */
    boolean takeTeleportReply() {
        boolean reply = afterAccept;
        afterAccept = false;
        return reply;
    }

    /** How many rotation-only packets of this interval may be replies. */
    int rotationTokens() {
        return rotationTokens.size();
    }

    /** How many position-and-rotation packets of this interval may be replies to an entity teleport. */
    int vehicleTokens() {
        return vehicleTokens.size();
    }

    /** Too many tokens to follow: which packet is a reply is not known until they drain. */
    boolean saturated() {
        return saturated;
    }

    /**
     * Tokens whose transaction came back in this interval cannot cover a later one. Tokens are not spent: which
     * packet was the tick's own is unknown, and keeping an allowance only errs towards the client.
     */
    void closeInterval() {
        for (ArrayDeque<Token> tokens : List.of(rotationTokens, vehicleTokens)) {
            tokens.removeIf(token -> token.expiring);
        }
        if (rotationTokens.isEmpty() && vehicleTokens.isEmpty()) {
            saturated = false;
        }
    }
}
