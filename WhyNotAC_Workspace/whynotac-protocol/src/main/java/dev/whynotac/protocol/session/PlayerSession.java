package dev.whynotac.protocol.session;

import dev.whynotac.protocol.latency.LatencySnapshot;
import dev.whynotac.protocol.latency.TransactionTracker;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Everything WhyNotAC knows about one network connection, from channel creation to disconnect.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@code on*} / {@code bind} / {@code disconnect} are called by the platform (mostly the
 *       connection's network thread) and are serialised on this object. They only stamp, count and
 *       enqueue, so the network thread is never held up by analysis.
 *   <li>Analysis happens later, on a pool thread, through {@link TimelineListener}s that receive
 *       this session's events strictly in order. {@link #attachment} state belongs to that side.
 * </ul>
 */
public final class PlayerSession {

    /** Authenticated identity, known from the moment the player joins the game. */
    public record Identity(UUID playerId, String name, int entityId) {}

    private final long connectionId;
    private final String remoteAddress;
    private final SerialMailbox mailbox;
    private final TransactionTracker transactions = new TransactionTracker();

    // guarded by this
    private long sequence;
    private long clientTick;
    private long droppedSinceGap;
    private long droppedTotal;
    private long inboundCount;
    private long outboundModeled;
    private long outboundUnmodeled;
    private boolean closed;

    private volatile Identity identity;
    private volatile long clientTicksPublished;

    // analysis-thread confined
    private final Map<Object, Object> attachments = new HashMap<>();

    PlayerSession(long connectionId, String remoteAddress, SessionRegistry registry, int mailboxCapacity) {
        this.connectionId = connectionId;
        this.remoteAddress = remoteAddress;
        this.mailbox = new SerialMailbox(registry.executor(), mailboxCapacity, event -> registry.dispatch(this, event));
    }

    // --- platform side ---------------------------------------------------------------------------

    /** A decoded packet from the client, observed on the network thread at {@code nanoTime}. */
    public synchronized void onInbound(ServerboundPacket packet, long nanoTime) {
        if (closed) {
            return;
        }
        inboundCount++;
        long tick = clientTick;
        emit(nanoTime, seq -> new TimelineEvent.Inbound(seq, nanoTime, tick, packet));
        switch (packet) {
            case ServerboundPacket.Pong pong when TransactionTracker.isWhyNotAcId(pong.id()) -> onTransactionPong(pong.id(), nanoTime);
            case ServerboundPacket.MovePlayer move when move.hasNonFiniteValue() ->
                    emit(nanoTime, seq -> new TimelineEvent.Anomaly(seq, nanoTime, TimelineEvent.Anomaly.Kind.NON_FINITE_MOVEMENT, move.toString()));
            case ServerboundPacket.ClientTickEnd ignored -> clientTicksPublished = ++clientTick;
            default -> { }
        }
    }

    /**
     * A packet to the client, observed as it enters the network pipeline. Unmodelled packets are
     * counted but not queued.
     */
    public synchronized void onOutbound(ClientboundPacket packet, long nanoTime) {
        if (closed) {
            return;
        }
        if (packet instanceof ClientboundPacket.Unmodeled) {
            outboundUnmodeled++;
            return;
        }
        outboundModeled++;
        if (packet instanceof ClientboundPacket.Ping ping && TransactionTracker.isWhyNotAcId(ping.id())) {
            transactions.onSent(ping.id(), nanoTime);
        }
        emit(nanoTime, seq -> new TimelineEvent.Outbound(seq, nanoTime, packet));
    }

    /** Reserves a transaction id for a ping the platform is about to send. */
    public synchronized OptionalInt allocateTransaction() {
        return closed ? OptionalInt.empty() : transactions.allocate();
    }

    /** Links this connection to the player who logged in on it. */
    public synchronized void bind(UUID playerId, String name, int entityId, long nanoTime) {
        if (closed) {
            return;
        }
        Identity bound = new Identity(playerId, name, entityId);
        identity = bound;
        emit(nanoTime, seq -> new TimelineEvent.Bound(seq, nanoTime, bound.playerId(), bound.name(), bound.entityId()));
    }

    /** The connection closed. Idempotent; the {@code Disconnected} event is delivered even if the mailbox is full. */
    public synchronized void disconnect(long nanoTime) {
        if (closed) {
            return;
        }
        closed = true;
        flushGap(nanoTime, true);
        mailbox.enqueue(new TimelineEvent.Disconnected(sequence++, nanoTime));
    }

    private void onTransactionPong(int id, long nanoTime) {
        switch (transactions.onPong(id, nanoTime)) {
            case TransactionTracker.PongResult.Matched matched -> {
                emit(nanoTime, seq -> new TimelineEvent.Acknowledged(seq, nanoTime, matched.id(), matched.sentNanoTime(), matched.skipped()));
                if (matched.skipped() > 0) {
                    emit(nanoTime, seq -> new TimelineEvent.Anomaly(seq, nanoTime, TimelineEvent.Anomaly.Kind.SKIPPED_TRANSACTION,
                            matched.skipped() + " transaction(s) before " + Integer.toHexString(id) + " were never answered"));
                }
            }
            case TransactionTracker.PongResult.Unknown unknown -> emit(nanoTime, seq -> new TimelineEvent.Anomaly(seq, nanoTime,
                    TimelineEvent.Anomaly.Kind.UNKNOWN_TRANSACTION, "pong " + Integer.toHexString(unknown.id())));
        }
    }

    /** Assigns the next sequence number and enqueues, converting overflow into an explicit gap. */
    private void emit(long nanoTime, LongFunction<TimelineEvent> factory) {
        boolean gapPending = droppedSinceGap > 0;
        if (!mailbox.hasRoomFor(gapPending ? 2 : 1)) {
            sequence++; // dropped events still consume a sequence number, so the gap is visible in the numbering too
            droppedSinceGap++;
            droppedTotal++;
            return;
        }
        if (gapPending) {
            flushGap(nanoTime, false);
        }
        mailbox.enqueue(factory.apply(sequence++));
    }

    private void flushGap(long nanoTime, boolean force) {
        if (droppedSinceGap > 0 && (force || mailbox.hasRoomFor(1))) {
            mailbox.enqueue(new TimelineEvent.Gap(sequence++, nanoTime, droppedSinceGap));
            droppedSinceGap = 0;
        }
    }

    // --- observation (any thread) -------------------------------------------------------------------

    public long connectionId() {
        return connectionId;
    }

    public String remoteAddress() {
        return remoteAddress;
    }

    /** Null until {@link #bind}. */
    public Identity identity() {
        return identity;
    }

    /** {@code ClientTickEnd} packets received so far. */
    public long clientTicks() {
        return clientTicksPublished;
    }

    public synchronized LatencySnapshot latency(long nowNanoTime) {
        return transactions.snapshot(nowNanoTime);
    }

    public synchronized Counters counters() {
        return new Counters(inboundCount, outboundModeled, outboundUnmodeled, droppedTotal, mailbox.size());
    }

    /** Traffic counters; {@code dropped} counts events lost to back-pressure (each one became part of a gap). */
    public record Counters(long inbound, long outboundModeled, long outboundUnmodeled, long dropped, int queued) {}

    public synchronized boolean isClosed() {
        return closed;
    }

    // --- analysis side ---------------------------------------------------------------------------

    /**
     * Per-session state owned by a listener. Only call this from {@link TimelineListener#onEvent}:
     * it is unsynchronised by design (calls for a session never overlap).
     */
    @SuppressWarnings("unchecked")
    public <T> T attachment(Object key, Supplier<T> factory) {
        return (T) attachments.computeIfAbsent(key, k -> factory.get());
    }

    @Override
    public String toString() {
        Identity id = identity;
        return "PlayerSession[" + connectionId + (id == null ? ", unbound" : ", " + id.name()) + ", " + remoteAddress + "]";
    }
}
