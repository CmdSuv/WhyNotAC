package dev.whynotac.ai;

import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Randomized behind-FOV probe geometry within vanilla 3.0-block attack reach so 360-degree / multi-target
     * KillAuras consider the decoy in range while remaining outside honest camera FOV.
     */
    public record ProbePlan(
            ProbeType type,
            long scheduledAtTick,
            double expectedNullHitRate,
            double distanceBlocks,
            float yawOffsetDeg,
            double yOffsetBlocks) {

        public ProbePlan(ProbeType type, long scheduledAtTick, double expectedNullHitRate) {
            this(type, scheduledAtTick, expectedNullHitRate, 2.45, 145.0f, 0.15);
        }

        public long effectiveTtlTicks(long configuredTtlTicks) {
            long base = Math.max(4L, configuredTtlTicks);
            return type == ProbeType.TARGET_SWAP_CADENCE ? Math.max(4L, (base * 2) / 3) : base;
        }
    }

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
        ProbeType chosen;
        double distance;
        float yawOffset;
        double yOffset;
        double nullRate;
        synchronized (rng) {
            chosen = types[rng.nextInt(types.length)];
            switch (chosen) {
                case DECOY_LOS_CHECK -> {
                    distance = 2.20 + rng.nextDouble() * 0.50; // 2.20..2.70 blocks (< 3.0 vanilla reach)
                    yawOffset = (float) (130.0 + rng.nextDouble() * 35.0);
                    yOffset = 0.15 + rng.nextDouble() * 0.20;
                    nullRate = 0.05;
                }
                case RANDOMIZED_HITBOX_OFFSET -> {
                    distance = 2.10 + rng.nextDouble() * 0.55; // 2.10..2.65 blocks
                    yawOffset = (float) (-130.0 - rng.nextDouble() * 35.0);
                    yOffset = -0.15 + rng.nextDouble() * 0.55;
                    nullRate = 0.05;
                }
                case TARGET_SWAP_CADENCE -> {
                    distance = 2.05 + rng.nextDouble() * 0.40; // 2.05..2.45 blocks directly behind
                    yawOffset = (float) (168.0 + rng.nextDouble() * 24.0);
                    yOffset = 0.05 + rng.nextDouble() * 0.25;
                    nullRate = 0.03;
                }
                default -> {
                    distance = 2.45;
                    yawOffset = 145.0f;
                    yOffset = 0.15;
                    nullRate = 0.05;
                }
            }
        }
        return new ProbePlan(chosen, currentTick, nullRate, distance, yawOffset, yOffset);
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
