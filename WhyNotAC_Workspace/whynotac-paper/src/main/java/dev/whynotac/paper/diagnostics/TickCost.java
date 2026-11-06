package dev.whynotac.paper.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Main-thread cost of one recurring task (the per-tick transaction loop): what WhyNotAC itself costs
 * the server tick. Both wall time and the thread's own CPU time are kept: on a busy machine the wall
 * time also contains the moments the thread was descheduled, which are not WhyNotAC's cost.
 *
 * <p>Written by the task (main thread), read by commands; the volatile fields only make a read from
 * another thread safe (each value individually).
 */
public final class TickCost {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME = THREADS.isCurrentThreadCpuTimeSupported();

    private volatile long runs;
    private volatile long wallNanos;
    private volatile long cpuNanos;
    private volatile long maxWallNanos;

    /** A run that is about to start: returns the token to pass to {@link #end}. */
    public long[] begin() {
        return new long[] {System.nanoTime(), CPU_TIME ? THREADS.getCurrentThreadCpuTime() : -1};
    }

    public void end(long[] token) {
        long wall = System.nanoTime() - token[0];
        runs++;
        wallNanos += wall;
        if (token[1] >= 0) {
            cpuNanos += THREADS.getCurrentThreadCpuTime() - token[1];
        }
        if (wall > maxWallNanos) {
            maxWallNanos = wall;
        }
    }

    public long runs() {
        return runs;
    }

    public double meanWallMicros() {
        long n = runs;
        return n == 0 ? 0 : wallNanos / 1e3 / n;
    }

    /** Mean CPU time per run, or NaN where the JVM cannot measure thread CPU time. */
    public double meanCpuMicros() {
        long n = runs;
        return !CPU_TIME ? Double.NaN : n == 0 ? 0 : cpuNanos / 1e3 / n;
    }

    public double maxWallMicros() {
        return maxWallNanos / 1e3;
    }
}
