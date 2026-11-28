package dev.whynotac.verdict;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void recoversUnexpiredUncancelledEntriesFromJournalAcrossRestart(@TempDir Path dir) throws Exception {
        Path journal = dir.resolve("ban-wave-queue.jsonl");
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach"))
                .autoBanEnabled(true)
                .banWaveDelayMillis(60_000L) // long delay so entries stay queued before crash/restart
                .build();
        SessionRegistry reg = new SessionRegistry(Runnable::run, 64, (s, e, err) -> { });

        UUID playerKeep = UUID.randomUUID();
        UUID playerCancel = UUID.randomUUID();
        PlayerSession sKeep = reg.open("127.0.0.1:10");
        sKeep.bind(playerKeep, "RecoverMe", 10, 100L);
        PlayerSession sCancel = reg.open("127.0.0.1:11");
        sCancel.bind(playerCancel, "CancelMe", 11, 100L);

        VerdictDecision keepDecision = makeDecision(sKeep, System.currentTimeMillis());
        VerdictDecision cancelDecision = makeDecision(sCancel, System.currentTimeMillis());

        // First process: enqueue two waves, cancel one, then close (simulating server restart) before wave fires
        try (BanWaveQueue first = new BanWaveQueue(cfg, new CircuitBreaker(5), new FalseBanBudget(5, TimeUnit.DAYS.toMillis(1)))) {
            first.attachJournal(journal);
            first.enqueue(keepDecision, d -> { throw new AssertionError("should not fire before shutdown"); });
            first.enqueue(cancelDecision, d -> { throw new AssertionError("should not fire before shutdown"); });
            first.cancelSubject(playerCancel, System.currentTimeMillis() + 1_000L);
        }

        // Append an expired (>24h old) entry and an already-EXECUTED entry to the journal
        long now = System.currentTimeMillis();
        String hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String extra = "{\"timestampMs\":" + (now - BanWaveQueue.MAX_RECOVERY_AGE_MILLIS - 10_000L)
                + ",\"state\":\"QUEUED\",\"caseId\":\"expired-1\",\"playerId\":\"" + UUID.randomUUID()
                + "\",\"check\":\"attack-reach\",\"delayMs\":100,\"certificateSha256\":\"" + hex64 + "\"}\n"
                + "{\"timestampMs\":" + (now - 5_000L)
                + ",\"state\":\"QUEUED\",\"caseId\":\"done-1\",\"playerId\":\"" + UUID.randomUUID()
                + "\",\"check\":\"attack-reach\",\"delayMs\":100,\"certificateSha256\":\"" + hex64 + "\"}\n"
                + "{\"timestampMs\":" + (now - 1_000L)
                + ",\"state\":\"EXECUTED\",\"caseId\":\"done-1\",\"playerId\":\"" + UUID.randomUUID()
                + "\",\"check\":\"attack-reach\",\"delayMs\":0,\"certificateSha256\":\"" + hex64 + "\"}\n";
        Files.writeString(journal, Files.readString(journal) + extra);

        // Fresh process after restart: simulate time past delay so recovered entry fires immediately (~50ms)
        VerdictConfig cfgAfterRestart = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach"))
                .autoBanEnabled(true)
                .banWaveDelayMillis(60_000L)
                .build();
        FalseBanBudget fbbAfter = new FalseBanBudget(5, TimeUnit.DAYS.toMillis(1));
        CopyOnWriteArrayList<BanWaveQueue.RecoveredEntry> executed = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        try (BanWaveQueue second = new BanWaveQueue(cfgAfterRestart, new CircuitBreaker(5), fbbAfter)) {
            second.attachJournal(journal);
            int recovered = second.recoverFromJournal(journal, now + 120_000L, entry -> {
                executed.add(entry);
                latch.countDown();
            });
            assertEquals(1, recovered, "Exactly 1 unexpired, uncancelled, unexecuted entry must be recovered");
            assertTrue(latch.await(3, TimeUnit.SECONDS), "Recovered ban wave must fire");
        }
        assertEquals(1, executed.size());
        assertEquals(keepDecision.verdictCase().id(), executed.get(0).caseId());
        assertEquals(playerKeep, executed.get(0).playerId());
        assertEquals(1, fbbAfter.snapshot(System.currentTimeMillis()).totalAutoBansIssued());

        // Third restart after EXECUTED was written must recover 0 entries (idempotent!)
        try (BanWaveQueue third = new BanWaveQueue(cfgAfterRestart, new CircuitBreaker(5), fbbAfter)) {
            int recoveredAgain = third.recoverFromJournal(journal, now + 180_000L, entry -> {
                throw new AssertionError("Already-executed entry must not replay");
            });
            assertEquals(0, recoveredAgain, "Executed entry must not be recovered twice");
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
