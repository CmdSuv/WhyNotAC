package dev.whynotac.ai;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Randomized active-probe scheduler and e-value accumulator (AI-04, AI-05).
 *
 * <p>Schedules unpredictable combat/aim probe opportunities strictly as read-only design recommendations;
 * outputs only {@code DESIGN_STATISTIC (D)} review annotations when the martingale rejects H0 and never
 * authorizes punishment actions directly.
 */
public final class ProbeScheduler {

    public enum ProbeType {
        DECOY_LOS_CHECK,
        RANDOMIZED_HITBOX_OFFSET,
        TARGET_SWAP_CADENCE
    }

    public record ProbePlan(ProbeType type, long scheduledAtTick, double expectedNullHitRate) {}

    private final long minCooldownTicks;
    private final long maxJitterTicks;
    private final double alpha;
    private final SplittableRandom rng;
    private final Map<UUID, Long> nextEligibleTick = new ConcurrentHashMap<>();
    private final Map<UUID, EValueProcess> martingales = new ConcurrentHashMap<>();

    public ProbeScheduler() {
        this(200L, 400L, 0.001, 0x5A17_C0DEL);
    }

    public ProbeScheduler(long minCooldownTicks, long maxJitterTicks, double alpha, long seed) {
        if (minCooldownTicks < 1 || maxJitterTicks < 0) {
            throw new IllegalArgumentException("Invalid cooldown/jitter bounds");
        }
        this.minCooldownTicks = minCooldownTicks;
        this.maxJitterTicks = maxJitterTicks;
        this.alpha = alpha;
        this.rng = new SplittableRandom(seed);
    }

    /** Returns an unpredictable randomized probe plan when cooldown elapsed, else null. */
    public ProbePlan maybeSchedule(UUID subject, long currentTick, boolean inCombat) {
        if (!inCombat || subject == null) {
            return null;
        }
        long eligibleAt = nextEligibleTick.computeIfAbsent(subject,
                id -> currentTick + minCooldownTicks + nextJitter());
        if (currentTick < eligibleAt) {
            return null;
        }
        nextEligibleTick.put(subject, currentTick + minCooldownTicks + nextJitter());
        ProbeType[] types = ProbeType.values();
        int idx;
        synchronized (rng) {
            idx = rng.nextInt(types.length);
        }
        return new ProbePlan(types[idx], currentTick, 0.05);
    }

    /** Feeds an experimental outcome (1.0 = unexpected hit on probe, 0.0 = ignored/clean) to the session's e-process. */
    public EValueProcess recordOutcome(UUID subject, double outcome01, double nullHitRate) {
        EValueProcess ep = martingales.computeIfAbsent(subject, id -> new EValueProcess(alpha));
        // Kelly-conservative martingale bet toward high-signal cheat deviations while strictly preserving E_H0[factor] = 1
        double bet = Math.min(8.0, 0.40 / Math.max(1e-6, nullHitRate));
        ep.observe(outcome01, nullHitRate, bet);
        return ep;
    }

    public EValueProcess processFor(UUID subject) {
        return martingales.get(subject);
    }

    public void forget(UUID subject) {
        nextEligibleTick.remove(subject);
        martingales.remove(subject);
    }

    private long nextJitter() {
        if (maxJitterTicks == 0) {
            return 0L;
        }
        synchronized (rng) {
            return rng.nextLong(maxJitterTicks + 1);
        }
    }
}
