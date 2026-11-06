package dev.whynotac.protocol.latency;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.OptionalInt;

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

    /**
     * One transaction per server tick for 60 s. A client that keeps the connection alive but has not
     * answered this many transactions that really left the server is withholding them (an anomaly to
     * weigh, not proof: a client whose main thread froze answers keep-alives but not pings).
     */
    public static final int MAX_PENDING = 1_200;
    /**
     * An allocated id whose ping never reached the network within this time was lost on the server
     * side (cancelled by a plugin, dropped after a disconnect, written after a protocol switch). It is
     * counted as {@code lostBeforeWire} and never held against the client.
     */
    public static final long UNSENT_EXPIRY_NANOS = 5_000_000_000L;
    /** Answered (and skipped) ids remembered, to recognise a second or late answer. */
    private static final int REMEMBERED = 512;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Ids drawn from {@link #RANDOM} per call: its lock, which every network thread takes, once per this many ids. */
    private static final int DRAWN = 64;

    /** Outcome of a pong. */
    public sealed interface PongResult {
        /** Answered; {@code skipped} older transactions were never answered (0 for vanilla). */
        record Matched(int id, long sentNanoTime, int skipped) implements PongResult {}

        /** One of ours, answered a second time or after a newer one (impossible for vanilla). */
        record Duplicate(int id) implements PongResult {}

        /** Not one of ours (another plugin's ping, or garbage): ignored. */
        record Foreign(int id) implements PongResult {}
    }

    // Plain arrays, no boxing: every ping passes through allocate, onSent and onPong on a network thread.
    /** Sent and unanswered, oldest first: a ring of ids and send times. */
    private int[] pendingIds = new int[16];
    private long[] pendingSent = new long[16];
    private int pendingHead;
    private int pendingSize;
    /** Pending id -> send time. */
    private final IdTable pendingById = new IdTable();
    /** Allocated id -> allocation time, for pings not yet seen leaving the server. */
    private final IdTable allocatedUnsent = new IdTable();
    /** The last {@link #REMEMBERED} answered or skipped ids, a ring (once full, the oldest is at {@code rememberedNext}). */
    private final int[] rememberedOrder = new int[REMEMBERED];
    private int rememberedNext;
    private int rememberedCount;
    private final IdTable remembered = new IdTable();
    private final LatencyEstimator estimator = new LatencyEstimator();
    /** Random ids drawn ahead; never sent before {@link #allocate} hands them out, so no less unpredictable. */
    private final int[] drawn = new int[DRAWN];
    private int nextDrawn = DRAWN;
    private long sent;
    private long acknowledged;
    private long skipped;
    private long duplicates;
    private long lostBeforeWire;
    private boolean withheld;

    /** True for ids in the range left to other plugins. */
    public static boolean isReservedForOthers(int id) {
        return id >= Short.MIN_VALUE && id <= Short.MAX_VALUE;
    }

    /**
     * Reserves an id for a ping about to be sent, or empty if {@link #MAX_PENDING} sent transactions
     * are unanswered (see {@link #isWithheld()}) or {@link #MAX_PENDING} allocations are still waiting
     * to leave the server (a server-side backlog, not the client's doing).
     */
    public OptionalInt allocate(long nanoTime) {
        expireUnsent(nanoTime);
        if (pendingSize >= MAX_PENDING) {
            withheld = true;
            return OptionalInt.empty();
        }
        if (allocatedUnsent.size() >= MAX_PENDING) {
            return OptionalInt.empty();
        }
        int id;
        do {
            id = nextRandom();
        } while (isReservedForOthers(id) || pendingById.contains(id) || allocatedUnsent.contains(id) || remembered.contains(id));
        allocatedUnsent.put(id, nanoTime);
        return OptionalInt.of(id);
    }

    private int nextRandom() {
        if (nextDrawn == DRAWN) {
            byte[] bytes = new byte[DRAWN * Integer.BYTES];
            RANDOM.nextBytes(bytes);
            ByteBuffer.wrap(bytes).asIntBuffer().get(drawn);
            nextDrawn = 0;
        }
        return drawn[nextDrawn++];
    }

    private void expireUnsent(long nanoTime) {
        if (!allocatedUnsent.isEmpty()) {
            lostBeforeWire += allocatedUnsent.removeOlderThan(nanoTime, UNSENT_EXPIRY_NANOS);
        }
    }

    /**
     * Records that a ping left through the network pipeline at {@code nanoTime}. Returns false if the
     * id was not allocated here (another plugin's ping), which is then not tracked.
     */
    public boolean onSent(int id, long nanoTime) {
        if (!allocatedUnsent.remove(id)) {
            return false;
        }
        if (pendingSize == pendingIds.length) {
            int[] ids = new int[pendingSize * 2];
            long[] times = new long[pendingSize * 2];
            for (int i = 0; i < pendingSize; i++) {
                ids[i] = pendingIds[(pendingHead + i) % pendingSize];
                times[i] = pendingSent[(pendingHead + i) % pendingSize];
            }
            pendingIds = ids;
            pendingSent = times;
            pendingHead = 0;
        }
        int tail = (pendingHead + pendingSize) % pendingIds.length;
        pendingIds[tail] = id;
        pendingSent[tail] = nanoTime;
        pendingSize++;
        pendingById.put(id, nanoTime);
        sent++;
        return true;
    }

    public PongResult onPong(int id, long nanoTime) {
        if (!pendingById.contains(id)) {
            if (remembered.contains(id)) {
                duplicates++;
                return new PongResult.Duplicate(id);
            }
            return new PongResult.Foreign(id);
        }
        int skippedNow = 0;
        long sentNanoTime;
        while (true) {
            int head = pendingIds[pendingHead];
            long headSent = pendingSent[pendingHead];
            pendingHead = (pendingHead + 1) % pendingIds.length;
            pendingSize--;
            pendingById.remove(head);
            remember(head);
            if (head == id) {
                sentNanoTime = headSent;
                break;
            }
            skippedNow++;
        }
        acknowledged++;
        skipped += skippedNow;
        estimator.addSample(nanoTime - sentNanoTime);
        if (pendingSize < MAX_PENDING) {
            withheld = false;
        }
        return new PongResult.Matched(id, sentNanoTime, skippedNow);
    }

    private void remember(int id) {
        if (remembered.contains(id)) {
            return;
        }
        if (rememberedCount == REMEMBERED) {
            remembered.remove(rememberedOrder[rememberedNext]);
        } else {
            rememberedCount++;
        }
        rememberedOrder[rememberedNext] = id;
        rememberedNext = (rememberedNext + 1) % REMEMBERED;
        remembered.put(id, 0);
    }

    /** True while the pending limit is reached: the client stopped answering transactions. */
    public boolean isWithheld() {
        return withheld;
    }

    /** Age of the oldest unanswered transaction, or 0 if none is pending. */
    public long oldestPendingAgeNanos(long nowNanoTime) {
        return pendingSize == 0 ? 0 : nowNanoTime - pendingSent[pendingHead];
    }

    public LatencySnapshot snapshot(long nowNanoTime) {
        return new LatencySnapshot(
                estimator.lastMillis(), estimator.smoothedMillis(), estimator.jitterMillis(),
                estimator.minMillis(), estimator.maxMillis(),
                pendingSize, oldestPendingAgeNanos(nowNanoTime) / 1.0e6,
                sent, acknowledged, skipped, duplicates, lostBeforeWire, withheld);
    }
}
