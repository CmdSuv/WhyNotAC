package dev.whynotac.verdict;

import dev.whynotac.analysis.policy.ActionPolicy;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Delayed/jittered temporary-ban wave queue (VER-12).
 *
 * <p>When configured with {@code banWaveDelayMillis > 0}, holds automatic temporary bans until a jittered
 * wave delay elapses, then re-verifies every safety invariant at execution time ({@link EvidenceCertificate}
 * integrity, {@link CircuitBreaker}, {@link FalseBanBudget}, {@link ActionPolicy}, and cancellation state)
 * before issuing the ban.
 */
public final class BanWaveQueue implements AutoCloseable {

    private final VerdictConfig config;
    private final CircuitBreaker circuitBreaker;
    private final FalseBanBudget falseBanBudget;
    private final ScheduledExecutorService scheduler;
    private final SplittableRandom rng;
    private final Map<UUID, Long> cancelledBeforeMillis = new ConcurrentHashMap<>();
    private final Set<ScheduledFuture<?>> pendingFutures = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingCount = new AtomicInteger();

    public BanWaveQueue(VerdictConfig config, CircuitBreaker circuitBreaker, FalseBanBudget falseBanBudget) {
        this(config, circuitBreaker, falseBanBudget, 0xB417_4E12L);
    }

    BanWaveQueue(VerdictConfig config, CircuitBreaker circuitBreaker, FalseBanBudget falseBanBudget, long seed) {
        this.config = Objects.requireNonNull(config, "config");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        this.falseBanBudget = Objects.requireNonNull(falseBanBudget, "falseBanBudget");
        this.rng = new SplittableRandom(seed);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WhyNotAC BanWaveQueue");
            t.setDaemon(true);
            return t;
        });
    }

    /** Enqueues a verified temporary ban (executes immediately when {@code banWaveDelayMillis == 0}). */
    public void enqueue(VerdictDecision decision, Consumer<VerdictDecision> executor) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(executor, "executor");
        long baseDelay = config.banWaveDelayMillis();
        if (baseDelay <= 0L) {
            executeIfSafe(decision, executor);
            return;
        }
        long delayMillis = computeJitteredDelay(baseDelay);
        pendingCount.incrementAndGet();
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                executeIfSafe(decision, executor);
            } finally {
                pendingCount.decrementAndGet();
                if (holder[0] != null) {
                    pendingFutures.remove(holder[0]);
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
        holder[0] = future;
        pendingFutures.add(future);
    }

    /** Prevents any queued ban opened at or before {@code cancelledAtMillis} from firing (e.g. overturn/GDPR). */
    public void cancelSubject(UUID playerId, long cancelledAtMillis) {
        if (playerId != null) {
            cancelledBeforeMillis.merge(playerId, cancelledAtMillis, Math::max);
        }
    }

    public int pendingCount() {
        return Math.max(0, pendingCount.get());
    }

    private long computeJitteredDelay(long baseDelay) {
        long min = Math.max(1L, baseDelay / 2L);
        long max = Math.max(min + 1L, baseDelay + baseDelay / 4L);
        synchronized (rng) {
            return min + rng.nextLong(max - min + 1L);
        }
    }

    private void executeIfSafe(VerdictDecision decision, Consumer<VerdictDecision> executor) {
        if (!config.autoBanEnabled() || !decision.certificate().verifyIntegrity()) {
            return;
        }
        if (circuitBreaker.isSuppressed() || falseBanBudget.isLocked()) {
            return;
        }
        UUID subjectId = decision.session() != null && decision.session().identity() != null
                ? decision.session().identity().playerId()
                : null;
        if (subjectId != null) {
            Long cutoff = cancelledBeforeMillis.get(subjectId);
            if (cutoff != null && decision.verdictCase().openedEpochMillis() <= cutoff) {
                return;
            }
        }
        boolean allowed = config.armedChecks().contains(decision.evidence().check())
                && ActionPolicy.enabled(decision.evidence().evidenceClass(), Action.TEMP_BAN,
                        decision.evidence().admissibility().identity(), Lifecycle.ARMED, true);
        if (!allowed) {
            return;
        }
        falseBanBudget.recordAutoBanIssued();
        executor.accept(decision);
    }

    @Override
    public void close() {
        for (ScheduledFuture<?> future : pendingFutures) {
            future.cancel(false);
        }
        scheduler.shutdownNow();
    }
}
