package dev.whynotac.protocol.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PingLedgerTest {

    @Test
    void tracksZeroNegativeAndSkippedPingsInOrder() {
        PingLedger ledger = new PingLedger();
        assertEquals(Long.MIN_VALUE, ledger.certain());
        assertEquals(Long.MAX_VALUE, ledger.firstPending());

        ledger.onPing(0, 10L);
        ledger.onPing(-42, 20L);
        ledger.onPing(100, 30L);
        assertEquals(10L, ledger.firstPending());

        // Unknown id leaves state untouched
        assertEquals(Long.MIN_VALUE, ledger.onAcknowledged(999));
        assertEquals(Long.MIN_VALUE, ledger.lastMatched());
        assertEquals(10L, ledger.firstPending());

        // Answering -42 also clears skipped 0
        assertEquals(20L, ledger.onAcknowledged(-42));
        assertEquals(20L, ledger.lastMatched());
        assertEquals(20L, ledger.certain());
        assertEquals(30L, ledger.firstPending());

        // Skipped 0 can no longer match
        assertEquals(Long.MIN_VALUE, ledger.onAcknowledged(0));

        assertEquals(30L, ledger.onAcknowledged(100));
        assertEquals(30L, ledger.certain());
        assertEquals(Long.MAX_VALUE, ledger.firstPending());
    }

    @Test
    void evictsOldestWhenCapacityExceeded() {
        PingLedger ledger = new PingLedger();
        for (int i = 0; i <= PingLedger.CAPACITY; i++) {
            ledger.onPing(i, i * 10L);
        }
        assertEquals(10L, ledger.firstPending());
        assertEquals(Long.MIN_VALUE, ledger.onAcknowledged(0));
        assertEquals(10L, ledger.onAcknowledged(1));
        assertEquals(20L, ledger.firstPending());
    }
}
