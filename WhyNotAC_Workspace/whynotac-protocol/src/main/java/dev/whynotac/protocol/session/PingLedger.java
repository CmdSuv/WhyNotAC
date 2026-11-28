package dev.whynotac.protocol.session;

import java.util.Arrays;

/**
 * Per-session ledger of pending outbound {@code Ping} transactions (client order), shared across all
 * {@link TimelineListener}s on the session's analysis thread. Replaces a dozen redundant per-listener
 * {@code HashMap}/{@code ArrayDeque<long[]>} copies and eliminates per-ping boxing/array allocations.
 *
 * <p>Holds up to {@link #CAPACITY} pending pings in a primitive ring buffer indexed by an open-addressed
 * primitive hash table (supporting any 32-bit {@code int} id, including {@code 0}). Not thread-safe:
 * confined to the session's analysis thread.
 */
public final class PingLedger {

    public static final int CAPACITY = 4_096;
    private static final int MASK = CAPACITY - 1;
    private static final int EMPTY = 0;

    private final int[] ringIds = new int[CAPACITY];
    private final long[] ringSeqs = new long[CAPACITY];
    private int head;
    private int count;

    // Open-addressed table mapping non-zero ping id -> timeline sequence (id 0 stored separately).
    private int[] keys = new int[32];
    private long[] seqs = new long[32];
    private int tableSize;
    private boolean hasZero;
    private long zeroSeq;

    private long certain = Long.MIN_VALUE;
    private long firstPending = Long.MAX_VALUE;
    private long lastMatched = Long.MIN_VALUE;

    /** Records an outbound {@code Ping(id)} sent at {@code sequence}. */
    public void onPing(int id, long sequence) {
        if (count == CAPACITY) {
            removeFromTable(ringIds[head]);
            head = (head + 1) & MASK;
            count--;
        }
        int tail = (head + count) & MASK;
        ringIds[tail] = id;
        ringSeqs[tail] = sequence;
        count++;
        putInTable(id, sequence);
        firstPending = ringSeqs[head];
    }

    /**
     * Records an acknowledged transaction {@code id}. Returns the sequence of the matching {@code Ping},
     * or {@link Long#MIN_VALUE} if unknown/already cleared, and updates {@link #lastMatched()},
     * {@link #certain()} and {@link #firstPending()}.
     */
    public long onAcknowledged(int id) {
        long seq = getFromTable(id);
        if (seq == Long.MIN_VALUE) {
            lastMatched = Long.MIN_VALUE;
            return Long.MIN_VALUE;
        }
        while (count > 0 && ringSeqs[head] <= seq) {
            removeFromTable(ringIds[head]);
            head = (head + 1) & MASK;
            count--;
        }
        if (seq > certain) {
            certain = seq;
        }
        firstPending = count == 0 ? Long.MAX_VALUE : ringSeqs[head];
        lastMatched = seq;
        return seq;
    }

    /** Clears pending transactions (e.g. on disconnect). */
    public void clear() {
        head = 0;
        count = 0;
        Arrays.fill(keys, EMPTY);
        tableSize = 0;
        hasZero = false;
        firstPending = Long.MAX_VALUE;
        lastMatched = Long.MIN_VALUE;
    }

    /** Sequence of the last {@code Ping} the client answered ({@link Long#MIN_VALUE} before the first). */
    public long certain() {
        return certain;
    }

    /** Sequence of the oldest unanswered {@code Ping} still pending ({@link Long#MAX_VALUE} when none). */
    public long firstPending() {
        return firstPending;
    }

    /** Sequence matched by the current {@code Acknowledged} event, or {@link Long#MIN_VALUE} if unmatched. */
    public long lastMatched() {
        return lastMatched;
    }

    private static int home(int key, int mask) {
        int h = key * 0x9E37_79B9;
        return (h ^ (h >>> 16)) & mask;
    }

    private long getFromTable(int id) {
        if (id == EMPTY) {
            return hasZero ? zeroSeq : Long.MIN_VALUE;
        }
        int mask = keys.length - 1;
        for (int i = home(id, mask); ; i = (i + 1) & mask) {
            int k = keys[i];
            if (k == id) {
                return seqs[i];
            }
            if (k == EMPTY) {
                return Long.MIN_VALUE;
            }
        }
    }

    private void putInTable(int id, long seq) {
        if (id == EMPTY) {
            hasZero = true;
            zeroSeq = seq;
            return;
        }
        int mask = keys.length - 1;
        for (int i = home(id, mask); ; i = (i + 1) & mask) {
            int k = keys[i];
            if (k == id) {
                seqs[i] = seq;
                return;
            }
            if (k == EMPTY) {
                break;
            }
        }
        if (2 * (tableSize + 1) > keys.length) {
            growTable();
        }
        insertInTable(id, seq);
        tableSize++;
    }

    private void insertInTable(int id, long seq) {
        int mask = keys.length - 1;
        int i = home(id, mask);
        while (keys[i] != EMPTY) {
            i = (i + 1) & mask;
        }
        keys[i] = id;
        seqs[i] = seq;
    }

    private void growTable() {
        int[] oldKeys = keys;
        long[] oldSeqs = seqs;
        keys = new int[oldKeys.length * 2];
        seqs = new long[oldKeys.length * 2];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                insertInTable(oldKeys[i], oldSeqs[i]);
            }
        }
    }

    private void removeFromTable(int id) {
        if (id == EMPTY) {
            hasZero = false;
            return;
        }
        int mask = keys.length - 1;
        int gap = -1;
        for (int i = home(id, mask); ; i = (i + 1) & mask) {
            int k = keys[i];
            if (k == id) {
                gap = i;
                break;
            }
            if (k == EMPTY) {
                return;
            }
        }
        for (int j = (gap + 1) & mask; keys[j] != EMPTY; j = (j + 1) & mask) {
            if (((j - home(keys[j], mask)) & mask) >= ((j - gap) & mask)) {
                keys[gap] = keys[j];
                seqs[gap] = seqs[j];
                gap = j;
            }
        }
        keys[gap] = EMPTY;
        seqs[gap] = 0L;
        tableSize--;
    }
}
