package dev.whynotac.protocol.latency;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.OptionalInt;

/**
 * Transaction bookkeeping for one connection.
 *
 * <p>A transaction is a {@code ClientboundPingPacket} with an id from WhyNotAC's reserved range. The
 * client answers every ping with a pong on its game thread, in the order the pings arrived and in
 * order with every other packet. So when the pong for transaction {@code t} arrives, the client has
 * provably applied every packet the server sent before {@code t}. Bracketing a velocity or teleport
 * packet with two transactions ("sandwich") tells the physics layer exactly which client ticks may
 * already see the change and which certainly do: that is how lag is compensated without guessing.
 *
 * <p>Not thread-safe: owned by {@link dev.whynotac.protocol.session.PlayerSession}, which serialises access.
 */
public final class TransactionTracker {

    /** Upper 16 bits of every WhyNotAC transaction id ("WN"). */
    public static final int ID_PREFIX = 0x574E_0000;
    private static final int PREFIX_MASK = 0xFFFF_0000;
    private static final int COUNTER_MASK = 0x0000_FFFF;
    /** Never have more than this many outstanding, so the 16-bit counter cannot wrap onto a live id. */
    public static final int MAX_PENDING = 30_000;

    private record Pending(int id, long sentNanoTime) {}

    /** Outcome of a pong carrying a WhyNotAC id. */
    public sealed interface PongResult {
        /** Answered in order; {@code skipped} older transactions were never answered (0 for vanilla). */
        record Matched(int id, long sentNanoTime, int skipped) implements PongResult {}

        /** The id was never sent, or already answered. */
        record Unknown(int id) implements PongResult {}
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final LatencyEstimator estimator = new LatencyEstimator();
    private int counter;
    private long sent;
    private long acknowledged;
    private long skipped;
    private long unknown;

    public static boolean isWhyNotAcId(int id) {
        return (id & PREFIX_MASK) == ID_PREFIX;
    }

    /** Reserves the next id, or empty if too many transactions are outstanding (client stopped answering). */
    public OptionalInt allocate() {
        if (pending.size() >= MAX_PENDING) {
            return OptionalInt.empty();
        }
        int id = ID_PREFIX | (counter++ & COUNTER_MASK);
        return OptionalInt.of(id);
    }

    /** Records that the ping actually left through the network pipeline at {@code nanoTime}. */
    public void onSent(int id, long nanoTime) {
        pending.addLast(new Pending(id, nanoTime));
        sent++;
    }

    /** Processes a pong with a WhyNotAC id; callers must check {@link #isWhyNotAcId} first. */
    public PongResult onPong(int id, long nanoTime) {
        int position = 0;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); position++) {
            if (it.next().id == id) {
                break;
            }
        }
        if (position == pending.size()) {
            unknown++;
            return new PongResult.Unknown(id);
        }
        for (int i = 0; i < position; i++) {
            pending.removeFirst();
        }
        Pending match = pending.removeFirst();
        acknowledged++;
        skipped += position;
        estimator.addSample(nanoTime - match.sentNanoTime);
        return new PongResult.Matched(id, match.sentNanoTime, position);
    }

    /** Age of the oldest unanswered transaction, or 0 if none is pending. */
    public long oldestPendingAgeNanos(long nowNanoTime) {
        Pending oldest = pending.peekFirst();
        return oldest == null ? 0 : nowNanoTime - oldest.sentNanoTime;
    }

    public LatencySnapshot snapshot(long nowNanoTime) {
        return new LatencySnapshot(
                estimator.lastMillis(), estimator.smoothedMillis(), estimator.jitterMillis(),
                estimator.minMillis(), estimator.maxMillis(),
                pending.size(), oldestPendingAgeNanos(nowNanoTime) / 1.0e6,
                sent, acknowledged, skipped, unknown);
    }
}
