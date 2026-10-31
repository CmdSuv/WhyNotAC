package dev.whynotac.analysis.evidence;

import java.util.ArrayDeque;

/**
 * The server's own clock assumption ({@code VAL-3}): checks measure time with the server's monotonic clock
 * ({@code System.nanoTime}), and {@code client-clock} allows the client's clock 1% against it. This compares that clock's
 * rate with the wall clock's ({@code System.currentTimeMillis}, which time synchronisation keeps to real time) over the
 * last ten minutes. Beyond {@link #TOLERANCE}, half the client's budget, a claim that rests on the server's clock is not
 * made. Two clocks of one machine that are wrong together are not seen.
 *
 * <p>Fed once a second by the plugin ({@link #SERVER}); unfed (a replay, a test) it knows nothing and holds no claim back.
 * Thread-safe.
 */
public final class ServerClock {

    /** The server's clock, fed by the plugin. */
    public static final ServerClock SERVER = new ServerClock();

    /** Relative rate difference beyond which the server's clock is not trusted: half of {@code client-clock}'s 1%. */
    public static final double TOLERANCE = 0.005;
    static final long WINDOW_NANOS = 600_000_000_000L;
    /** The shortest span a rate is judged over: the wall clock's millisecond steps stay below 0.004% of it. */
    static final long MIN_SPAN_NANOS = 60_000_000_000L;
    /** One interval in which the two clocks moved apart by more than this: the wall clock was set, start again. */
    static final long STEP_MILLIS = 1_000;

    private final ArrayDeque<long[]> samples = new ArrayDeque<>(); // {nanoTime, wallMillis}

    public synchronized void sample(long nanoTime, long wallMillis) {
        long[] last = samples.peekLast();
        if (last != null && Math.abs((nanoTime - last[0]) / 1_000_000 - (wallMillis - last[1])) > STEP_MILLIS) {
            samples.clear();
        }
        samples.addLast(new long[] {nanoTime, wallMillis});
        while (nanoTime - samples.peekFirst()[0] > WINDOW_NANOS) {
            samples.pollFirst();
        }
    }

    /** The monotonic clock's rate relative to the wall clock's, minus 1; NaN until a long enough span. */
    public synchronized double divergence() {
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        if (first == null || last[0] - first[0] < MIN_SPAN_NANOS || last[1] == first[1]) {
            return Double.NaN;
        }
        return (last[0] - first[0]) / 1e6 / (last[1] - first[1]) - 1.0;
    }

    /** Whether a claim may rest on the server's clock: within {@link #TOLERANCE}, or not known to be otherwise. */
    public boolean trusted() {
        double d = divergence();
        return Double.isNaN(d) || Math.abs(d) <= TOLERANCE;
    }
}
