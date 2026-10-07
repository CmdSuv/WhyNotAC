package dev.whynotac.protocol.session;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import java.util.UUID;

/**
 * One entry of a player's ordered timeline. Inbound and outbound traffic of a connection are
 * handled on the same network thread, so {@link #sequence()} is a total order over everything the
 * player sent and received, and {@link #nanoTime()} is the {@link System#nanoTime()} at which the
 * network thread saw it (the earliest server-side observation point, before any game-thread delay).
 */
public sealed interface TimelineEvent {

    long sequence();

    long nanoTime();

    /** A packet from the client. {@code clientTick} counts {@code ClientTickEnd} packets received before this one. */
    record Inbound(long sequence, long nanoTime, long clientTick, ServerboundPacket packet) implements TimelineEvent {}

    /** A modelled packet to the client, observed as it entered the network pipeline. */
    record Outbound(long sequence, long nanoTime, ClientboundPacket packet) implements TimelineEvent {}

    /**
     * The client answered WhyNotAC transaction {@code transactionId}: it has now processed everything
     * sent before that ping. {@code skipped} counts older transactions it never answered (always 0
     * for a vanilla client: TCP is ordered and the client answers every ping in order).
     */
    record Acknowledged(long sequence, long nanoTime, int transactionId, long sentNanoTime, int skipped) implements TimelineEvent {
        public long roundTripNanos() {
            return nanoTime - sentNanoTime;
        }
    }

    /** The session was linked to an authenticated player. */
    record Bound(long sequence, long nanoTime, UUID playerId, String playerName, int entityId) implements TimelineEvent {}

    /**
     * Analysis fell behind and {@code droppedEvents} events were discarded before this point. State
     * that spans a gap is unknown, so evidence must never be built across it.
     */
    record Gap(long sequence, long nanoTime, long droppedEvents) implements TimelineEvent {}

    /** A protocol-level impossibility (not an accusation: a fact to be weighed by the verdict layer). */
    record Anomaly(long sequence, long nanoTime, Kind kind, String detail) implements TimelineEvent {
        /** Constants are stored by ordinal in recordings: only ever append. */
        public enum Kind {
            /** Legacy (format v1 recordings only): a pong with a WhyNotAC-prefixed id that was never sent. Ids are random now. */
            UNKNOWN_TRANSACTION,
            /** Pong answered a newer transaction while older ones are still pending. */
            SKIPPED_TRANSACTION,
            /** Movement or rotation contained NaN or infinity. */
            NON_FINITE_MOVEMENT,
            /** A transaction answered a second time, or after a newer one. */
            DUPLICATE_TRANSACTION,
            /** The client keeps the connection alive but has stopped answering transactions. */
            TRANSACTIONS_WITHHELD
        }
    }

    /**
     * Where this session's {@link Inbound} and {@link Outbound} events come from, from this event on.
     * {@code clientProtocol} is the protocol version the client really speaks (read from its handshake
     * bytes before any translation layer), or -1 if unknown.
     */
    record Observation(long sequence, long nanoTime, Source inbound, Source outbound, int clientProtocol, String detail) implements TimelineEvent {
        /** Constants are stored by ordinal in recordings: only ever append. */
        public enum Source {
            /**
             * Decoded from the bytes at the network edge, below every other plugin: exactly what the
             * client sent, or exactly what it received.
             */
            WIRE,
            /**
             * The packet objects next to the game's own handler: what the game processed or sent. Used
             * when the edge cannot be decoded (the client speaks another protocol version through a
             * translation layer such as ViaVersion, or WhyNotAC attached to a connection mid-session).
             */
            GAME
        }
    }

    /**
     * The client's packet stream and the game's differ: a plugin between the network edge and the
     * game added, removed or rewrote a packet. A fact about observation, not about the player.
     *
     * <ul>
     *   <li>{@code WIRE_ONLY}, inbound: the client sent it, the game never processed it.
     *   <li>{@code GAME_ONLY}, inbound: the game processed it, the client never sent it.
     *   <li>{@code WIRE_ONLY}, outbound: the client received it, the game never sent it (written below
     *       the game's send path, e.g. by PacketEvents).
     *   <li>{@code GAME_ONLY}, outbound: the game sent it, the client never received it.
     * </ul>
     * A rewritten packet shows up as a {@code GAME_ONLY} original plus a {@code WIRE_ONLY} replacement.
     */
    enum DivergenceKind { WIRE_ONLY, GAME_ONLY }

    /** See {@link DivergenceKind}. */
    record InboundDivergence(long sequence, long nanoTime, DivergenceKind kind, ServerboundPacket packet) implements TimelineEvent {}

    /** See {@link DivergenceKind}. */
    record OutboundDivergence(long sequence, long nanoTime, DivergenceKind kind, ClientboundPacket packet) implements TimelineEvent {}

    /**
     * Something the server learned about the client, with how far it can be trusted. The rule every
     * analysis layer must follow: a {@link Trust#CLAIMED} fact may add hypotheses that are granted to
     * every player, but it can never disable a deterministic check or excuse evidence (a cheat can
     * claim anything). Only {@link Trust#AUTHENTIC} and {@link Trust#FORMAT_BOUND} facts may select a
     * physics model.
     */
    record ClientFact(long sequence, long nanoTime, Fact fact, String value, Trust trust, String source) implements TimelineEvent {
        /** Constants are stored by ordinal in recordings: only ever append. */
        public enum Fact {
            /** How the player's identity was established: see {@link Identity}. */
            IDENTITY,
            /** How the connection reaches the server: DIRECT, VELOCITY_MODERN, BUNGEE_LEGACY, GEYSER_LOCAL. */
            TOPOLOGY,
            /** The protocol version the client really speaks. */
            NATIVE_PROTOCOL,
            /** A plugin translates between the client's protocol and the server's: none, or which. */
            TRANSLATOR,
            /** The player is on Bedrock Edition (through Geyser): true or false. */
            BEDROCK,
            /** Who answers transactions: CLIENT, or TRANSLATOR (then they say nothing about the client). */
            TRANSACTION_ORIGIN
        }

        /** Constants are stored by ordinal in recordings: only ever append. */
        public enum Trust {
            /** Cryptographically or structurally established (online mode, Floodgate key, in-process channel). */
            AUTHENTIC,
            /** Forced by the bytes the client must speak to be served at all (its real protocol version). */
            FORMAT_BOUND,
            /** The client's own word: brand, channels, settings, a forwarded identity nobody verified. */
            CLAIMED
        }

        /**
         * Values of {@link Fact#IDENTITY}. Long-lived state (profiles, bans) may only ever be keyed by an
         * {@code AUTHENTICATED_*} identity; anything else is session-scoped and never acted on automatically.
         */
        public enum Identity { AUTHENTICATED_MOJANG, AUTHENTICATED_BY_PROXY, CLAIMED_BY_PROXY, UNAUTHENTICATED }
    }

    /** The connection closed. This is always the last event of a session. */
    record Disconnected(long sequence, long nanoTime) implements TimelineEvent {}
}
