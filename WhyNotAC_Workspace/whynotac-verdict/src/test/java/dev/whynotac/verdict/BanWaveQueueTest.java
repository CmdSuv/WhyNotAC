package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BanWaveQueueTest {

    @Test
    void delaysExecutionAndReverifiesSafetyGatesAtDispatchTime() throws InterruptedException {
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach"))
                .autoBanEnabled(true)
                .banWaveDelayMillis(80L)
                .build();
        CircuitBreaker cb = new CircuitBreaker(5);
        FalseBanBudget fbb = new FalseBanBudget(1, TimeUnit.DAYS.toMillis(1));

        try (BanWaveQueue queue = new BanWaveQueue(cfg, cb, fbb)) {
            SessionRegistry reg = new SessionRegistry(Runnable::run, 64, (s, e, err) -> { });
            UUID playerA = UUID.randomUUID();
            PlayerSession sA = reg.open("127.0.0.1:1");
            sA.bind(playerA, "CheaterA", 1, 100L);

            AtomicInteger fired = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(1);
            queue.enqueue(makeDecision(sA, 1000L), d -> {
                fired.incrementAndGet();
                latch.countDown();
            });
            // Must not fire synchronously when banWaveDelayMillis > 0
            assertEquals(0, fired.get());
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(1, fired.get());
            assertEquals(1, fbb.snapshot(System.currentTimeMillis()).totalAutoBansIssued());

            // Now enqueue B, then trip killswitch before timer fires -> must cancel at execution time!
            UUID playerB = UUID.randomUUID();
            PlayerSession sB = reg.open("127.0.0.1:2");
            sB.bind(playerB, "CheaterB", 2, 100L);
            queue.enqueue(makeDecision(sB, 2000L), d -> fired.incrementAndGet());
            assertTrue(queue.pendingCount() >= 1);
            cb.setKillSwitch(true, "emergency halt");
            Thread.sleep(180);
            assertEquals(1, fired.get());
            assertEquals(1, fbb.snapshot(System.currentTimeMillis()).totalAutoBansIssued());

            // Reset killswitch, enqueue C, cancel C before wave executes -> suppressed!
            cb.setKillSwitch(false, "");
            UUID playerC = UUID.randomUUID();
            PlayerSession sC = reg.open("127.0.0.1:3");
            sC.bind(playerC, "AppealedC", 3, 100L);
            queue.enqueue(makeDecision(sC, 3000L), d -> fired.incrementAndGet());
            queue.cancelSubject(playerC, 3000L);
            Thread.sleep(180);
            assertEquals(1, fired.get());

            // Future incident on C opened AFTER cutoff is still eligible once delay runs out
            CountDownLatch futureLatch = new CountDownLatch(1);
            queue.enqueue(makeDecision(sC, 4000L), d -> {
                fired.incrementAndGet();
                futureLatch.countDown();
            });
            assertTrue(futureLatch.await(2, TimeUnit.SECONDS));
            assertEquals(2, fired.get());

            // Hot-reloading config to disable autoBan immediately suppresses pending waves before dispatch
            UUID playerD = UUID.randomUUID();
            PlayerSession sD = reg.open("127.0.0.1:4");
            sD.bind(playerD, "ReloadedD", 4, 100L);
            queue.enqueue(makeDecision(sD, 5000L), d -> fired.incrementAndGet());
            queue.updateConfig(VerdictConfig.shadowDefaults());
            Thread.sleep(180);
            assertEquals(2, fired.get(), "hot-reloading autoBan=false must suppress already-queued ban waves");
        }
    }

    private static VerdictDecision makeDecision(PlayerSession session, long openedMillis) {
        Evidence ev = new Evidence("attack-reach", 1, EvidenceClass.ADVANTAGE_PROOF,
                10L, 20L, 1000L, 2000L, "reach > 3", Map.of(),
                new Admissibility(true, List.of(), IdentityTier.AUTHENTICATED));
        EvidenceCertificate cert = EvidenceCertificate.issue(
                UUID.randomUUID().toString(), openedMillis, session.connectionId(),
                ev.check(), ev.checkVersion(), ev.evidenceClass(), Lifecycle.ARMED,
                IdentityTier.AUTHENTICATED, ev.fingerprint(), List.of(ev.fingerprint()),
                List.of(DependencyGroup.ATTACK_REACH.id()), EvidenceCertificate.VerificationStatus.VERIFIED,
                "verified", Action.TEMP_BAN, MitigationKind.NONE);
        VerdictCase vCase = new VerdictCase(
                UUID.randomUUID().toString(), openedMillis, session, ev, Lifecycle.ARMED,
                DependencyGroup.ATTACK_REACH, EnumSet.of(Action.ALERT, Action.REVIEW, Action.TEMP_BAN),
                Action.TEMP_BAN, MitigationKind.NONE, 1.0, 0.0, 1, false, cert);
        return new VerdictDecision(vCase, session, ev, Action.TEMP_BAN, MitigationKind.NONE,
                TimeUnit.HOURS.toMillis(24), cert, "verified");
    }
}
