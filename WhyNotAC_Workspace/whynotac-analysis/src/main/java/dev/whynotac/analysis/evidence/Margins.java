package dev.whynotac.analysis.evidence;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The margins ({@link EvidenceSink#margin}) per check: how many observations, and the smallest slack any of them left.
 * Safe to feed from several analysis threads.
 */
public final class Margins {

    /** One check's margins: {@code smallest} is {@code +Infinity} until an observation. */
    public record Margin(long observed, double smallest) {}

    private final Map<String, double[]> byCheck = new ConcurrentHashMap<>(); // {observed, smallest}, guarded by itself

    public void add(String check, double slack) {
        if (Double.isNaN(slack)) {
            return;
        }
        double[] m = byCheck.computeIfAbsent(check, c -> new double[] {0, Double.POSITIVE_INFINITY});
        synchronized (m) {
            m[0]++;
            m[1] = Math.min(m[1], slack);
        }
    }

    /** By check, in name order. */
    public Map<String, Margin> snapshot() {
        Map<String, Margin> out = new TreeMap<>();
        byCheck.forEach((check, m) -> {
            synchronized (m) {
                out.put(check, new Margin((long) m[0], m[1]));
            }
        });
        return out;
    }
}
