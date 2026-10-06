package dev.whynotac.protocol.latency;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Transaction bookkeeping for one connection.
 *
 * <p>A transaction is a {@code ClientboundPingPacket}. The client answers every ping with a pong on
 * its game thread, in the order the pings arrived and in order with every other packet. So when the
 * pong for transaction {@code t} arrives, an honest client has applied every packet the server sent
 * before {@code t}. Bracketing a velocity or teleport packet with two transactions ("sandwich") tells
 * the physics layer exactly which client ticks may already see the change and which certainly do.
 *
 * <h2>Ids are unpredictable</h2>
 * Ids come from {@link SecureRandom}. With sequential or patterned ids a cheat can recognise the
 * anti-cheat's pings and, worse, answer a transaction before it arrives (pretending a knockback was
 * applied early). A random 32-bit id cannot be answered before it is seen. The small-integer range
 * {@code [-32768, 32767]} is left to other plugins, which conventionally use short ids.
 *
 * <p>Not thread-safe: owned by {@link dev.whynotac.protocol.session.PlayerSession}, which serialises access.
 */
public final class TransactionTracker {

    /** One transaction per server tick for 60 s. A client that answers keep-alives but not pings for that long is withholding them. */
    public static final int MAX_PENDING = 1_200;
    /** Answered (and skipped) ids remembered, to recognise a second or late answer. */
    private static final int REMEMBERED = 512;

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Pending(int id, long sentNanoTime) {}

    /** Outcome of a pong. */
    public sealed interface PongResult {
        /** Answered; {@code skipped} older transactions were never answered (0 for vanilla). */
        record Matched(int id, long sentNanoTime, int skipped) implements PongResult {}

        /** One of ours, answered a second time or after a newer one (impossible for vanilla). */
        record Duplicate(int id) implements PongResult {}

        /** Not one of ours (another plugin's ping, or garbage): ignored. */
        record Foreign(int id) implements PongResult {}
    }

    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Map<Integer, Pending> pendingById = new HashMap<>();
    private final Set<Integer> allocatedUnsent = new HashSet<>();
    private final ArrayDeque<Integer> rememberedOrder = new ArrayDeque<>();
    private final Set<Integer> remembered = new HashSet<>();
    private final LatencyEstimator estimator = new LatencyEstimator();
    private long sent;
    private long acknowledged;
    private long skipped;
    private long duplicates;
    private boolean withheld;

    /** True for ids in the range left to other plugins. */
    public static boolean isReservedForOthers(int id) {
        return id >= Short.MIN_VALUE && id <= Short.MAX_VALUE;
    }

    /**
     * Reserves an id for a ping about to be sent, or empty if {@link #MAX_PENDING} transactions are
     * outstanding (see {@link #isWithheld()}).
     */
    public OptionalInt allocate() {
        if (pending.size() + allocatedUnsent.size() >= MAX_PENDING) {
            withheld = true;
            return OptionalInt.empty();
        }
        int id;
        do {
            id = RANDOM.nextInt();
        } while (isReservedForOthers(id) || pendingById.containsKey(id) || allocatedUnsent.contains(id) || remembered.contains(id));
        allocatedUnsent.add(id);
        return OptionalInt.of(id);
    }

    /**
     * Records that a ping left through the network pipeline at {@code nanoTime}. Returns false if the
     * id was not allocated here (another plugin's ping), which is then not tracked.
     */
    public boolean onSent(int id, long nanoTime) {
        if (!allocatedUnsent.remove(id)) {
            return false;
        }
        Pending entry = new Pending(id, nanoTime);
        pending.addLast(entry);
        pendingById.put(id, entry);
        sent++;
        return true;
    }

    public PongResult onPong(int id, long nanoTime) {
        Pending match = pendingById.get(id);
        if (match == null) {
            if (remembered.contains(id)) {
                duplicates++;
                return new PongResult.Duplicate(id);
            }
            return new PongResult.Foreign(id);
        }
        int skippedNow = 0;
        while (true) {
            Pending head = pending.removeFirst();
            pendingById.remove(head.id);
            remember(head.id);
            if (head == match) {
                break;
            }
            skippedNow++;
        }
        acknowledged++;
        skipped += skippedNow;
        estimator.addSample(nanoTime - match.sentNanoTime);
        if (pending.size() + allocatedUnsent.size() < MAX_PENDING) {
            withheld = false;
        }
        return new PongResult.Matched(id, match.sentNanoTime, skippedNow);
    }

    private void remember(int id) {
        if (remembered.add(id)) {
            rememberedOrder.addLast(id);
            if (rememberedOrder.size() > REMEMBERED) {
                remembered.remove(rememberedOrder.removeFirst());
            }
        }
    }

    /** True while the pending limit is reached: the client stopped answering transactions. */
    public boolean isWithheld() {
        return withheld;
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
                sent, acknowledged, skipped, duplicates, withheld);
    }
}
