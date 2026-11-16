package dev.whynotac.analysis.checks;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which movement packets of a client tick may be replies to the server rather than the tick's own
 * ({@link TickFramingCheck} has the client's rules): the reply to a teleport, paired with its accept by id,
 * and allowances ("tokens") for rotation-only and position-and-rotation packets, one per rotation packet
 * and per vehicle teleport (an entity the player may have ridden since login, narrowed from {@code SetPassengers}
 * and {@code Login}) the client may have handled in the interval. A token lasts from the moment the server
 * sent the packet until the end of the interval in which the client acknowledged a transaction sent after it.
 *
 * <p>Fed every event of one session, in order; {@link #closeInterval} at each ClientTickEnd.
 */
final class MoveReplies {

    static final int MAX_TOKENS = 256;
    static final int MAX_TRACKED_PINGS = 4_096;
    static final int MAX_PENDING_TELEPORTS = 1_024;
    static final int MAX_VEHICLES = 256;

    private static final class Token {
        final long sequence;
        boolean expiring; // acknowledged-after in the current interval: usable until it closes

        Token(long sequence) {
            this.sequence = sequence;
        }
    }

    private final ArrayDeque<Token> rotationTokens = new ArrayDeque<>();
    private final ArrayDeque<Token> vehicleTokens = new ArrayDeque<>();
    private final PingLedger ledger;
    private final boolean sharedLedger;
    /** Teleport ids the server sent and the client has not accepted yet. */
    private final Set<Integer> pendingTeleports = new java.util.LinkedHashSet<>();
    private int selfId = -1;
    /** Passenger -> vehicle edges (server order) for transitive root-vehicle tracking. */
    private final Map<Integer, Integer> parentOf = new HashMap<>();
    /** Entities the player may have ridden since login (or saturated if too many). */
    private final Set<Integer> riddenVehicles = new HashSet<>();
    private boolean vehiclesSaturated;
    private boolean saturated;
    /**
     * A teleport the server sent was accepted and no movement packet has followed yet. Not cleared by
     * other packets: the client's network thread sends keep-alive and chunk-batch answers itself, so one
     * can land between the accept and its reply (both queued by the main thread).
     */
    private boolean afterAccept;

    MoveReplies() {
        this(null);
    }

    MoveReplies(PingLedger sharedLedger) {
        this.sharedLedger = sharedLedger != null;
        this.ledger = sharedLedger != null ? sharedLedger : new PingLedger();
    }

    void onBound(int entityId) {
        if (entityId > 0) {
            selfId = entityId;
        }
    }

    void onOutbound(TimelineEvent.Outbound out) {
        switch (out.packet()) {
            case ClientboundPacket.Login login -> {
                selfId = login.entityId();
                parentOf.clear();
                riddenVehicles.clear();
                vehiclesSaturated = false;
            }
            case ClientboundPacket.PlayerPosition teleport -> {
                if (pendingTeleports.size() < MAX_PENDING_TELEPORTS) {
                    pendingTeleports.add(teleport.teleportId());
                }
            }
            case ClientboundPacket.PlayerRotation ignored -> addToken(rotationTokens, out.sequence());
            case ClientboundPacket.SetPassengers passengers -> onSetPassengers(passengers);
            case ClientboundPacket.TeleportEntity teleport -> {
                if (selfId < 0 || vehiclesSaturated || riddenVehicles.contains(teleport.entityId())) {
                    addToken(vehicleTokens, out.sequence());
                }
            }
            case ClientboundPacket.Ping ping -> {
                if (!sharedLedger) {
                    ledger.onPing(ping.id(), out.sequence());
                }
            }
            default -> { }
        }
    }

    private void onSetPassengers(ClientboundPacket.SetPassengers passengers) {
        int vehicleId = passengers.vehicleId();
        parentOf.values().removeIf(v -> v == vehicleId);
        for (int p : passengers.passengerIds()) {
            if (parentOf.size() < MAX_TRACKED_PINGS) {
                parentOf.put(p, vehicleId);
            }
        }
        if (selfId < 0) {
            return;
        }
        // Walk upward from selfId to collect any vehicle or stacked root vehicle it may now ride.
        int cursor = selfId;
        for (int depth = 0; depth < 8; depth++) {
            Integer parent = parentOf.get(cursor);
            if (parent == null) {
                break;
            }
            if (riddenVehicles.size() >= MAX_VEHICLES) {
                vehiclesSaturated = true;
                break;
            }
            riddenVehicles.add(parent);
            cursor = parent;
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
        long ping = sharedLedger ? ledger.lastMatched() : ledger.onAcknowledged(ack.transactionId());
        if (ping == Long.MIN_VALUE) {
            return;
        }
        markExpiring(rotationTokens, ping);
        markExpiring(vehicleTokens, ping);
    }

    private static void markExpiring(ArrayDeque<Token> tokens, long ping) {
        for (Token token : tokens) {
            if (token.sequence < ping) {
                token.expiring = true; // the client handled it before this ping: its reply is in this interval or earlier
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
