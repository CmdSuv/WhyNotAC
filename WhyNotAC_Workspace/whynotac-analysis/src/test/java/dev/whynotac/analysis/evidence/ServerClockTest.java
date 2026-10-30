package dev.whynotac.analysis.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ServerClockTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void knowsNothingBeforeAMinuteAndHoldsNothingBack() {
        ServerClock clock = new ServerClock();
        assertTrue(Double.isNaN(clock.divergence()));
        for (long s = 0; s < 60; s++) {
            clock.sample(s * SECOND, 5_000 + s * 900); // far off, but not for a minute yet
        }
        assertTrue(Double.isNaN(clock.divergence()));
        assertTrue(clock.trusted());
    }

    @Test
    void measuresTheRateOverTheWindow() {
        ServerClock clock = new ServerClock();
        for (long s = 0; s <= 900; s++) {
            clock.sample(7 + s * SECOND, 1_000 + s * 1_000 + (s < 100 ? 0 : (s - 100) * 2)); // 0.2% slow after 100 s
        }
        assertEquals(-0.002 / 1.002, clock.divergence(), 1e-6); // the last ten minutes only
        assertTrue(clock.trusted());
        for (long s = 901; s <= 1_600; s++) {
            clock.sample(7 + s * SECOND, 1_000 + 900_000 + 1_600 + (s - 900) * 1_010); // 1% slow
        }
        assertFalse(clock.trusted());
    }

    @Test
    void aWallClockStepStartsAgain() {
        ServerClock clock = new ServerClock();
        for (long s = 0; s <= 120; s++) {
            clock.sample(s * SECOND, s * 1_100); // 10% apart
        }
        assertFalse(clock.trusted());
        clock.sample(121 * SECOND, 121 * 1_100 + 3_600_000); // the wall clock was set an hour ahead
        assertTrue(Double.isNaN(clock.divergence()));
        assertTrue(clock.trusted());
    }
}
