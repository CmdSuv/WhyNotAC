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

    /** The connection closed. This is always the last event of a session. */
    record Disconnected(long sequence, long nanoTime) implements TimelineEvent {}
}
