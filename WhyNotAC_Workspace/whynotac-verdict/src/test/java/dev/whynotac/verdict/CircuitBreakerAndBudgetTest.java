package dev.whynotac.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakerAndBudgetTest {

    private static Evidence reachEv(long nano) {
        return new Evidence("attack-reach", Checks.version("attack-reach"), EvidenceClass.ADVANTAGE_PROOF, 1, 2, nano, nano,
                "reach", Map.of("k", "v"), new Admissibility(true, List.of(), IdentityTier.AUTHENTICATED));
    }

    private static Evidence moveEv(long nano) {
        return new Evidence("movement", Checks.version("movement"), EvidenceClass.ADVANTAGE_PROOF, 3, 4, nano, nano,
                "movement", Map.of("k", "v"), new Admissibility(true, List.of(), IdentityTier.AUTHENTICATED));
    }

    @Test
    void killSwitchAndSpikeCircuitBreakerSuppressActiveEnforcementSafely() {
        AtomicLong clock = new AtomicLong(100_000L);
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach"))
                .mitigationsEnabled(true)
                .maxUniquePenalizedPlayersPerMinute(2)
                .build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none(), clock::get)) {
            List<VerdictCase> cases = new ArrayList<>();
            List<VerdictDecision> mitigations = new ArrayList<>();
            List<CircuitBreaker.Status> trips = new ArrayList<>();
            engine.addCaseListener(cases::add);
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override public void mitigate(VerdictDecision decision) { mitigations.add(decision); }
                @Override public void circuitBreakerTripped(CircuitBreaker.Status status) { trips.add(status); }
            });
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});

            // Kill switch suppresses even the first player
            engine.circuitBreaker().setKillSwitch(true, "operator test");
            PlayerSession s1 = registry.openReplay("p1");
            engine.accept(s1, reachEv(1_000L));
            assertEquals(0, mitigations.size());
            assertTrue(cases.getLast().suppressedBySafetyGate());
            assertEquals(Lifecycle.SHADOW, cases.getLast().lifecycle());

            // Disable kill switch: 2 unique players allowed, 3rd triggers automatic spike breaker
            engine.circuitBreaker().setKillSwitch(false, "");
            PlayerSession s2 = registry.openReplay("p2");
            PlayerSession s3 = registry.openReplay("p3");
            PlayerSession s4 = registry.openReplay("p4");

            engine.accept(s2, reachEv(2_000L));
            engine.accept(s3, reachEv(3_000L));
            assertEquals(2, mitigations.size());
            assertEquals(0, trips.size());

            // 3rd unique player exceeds limit=2 -> trips breaker AND suppresses that enforcement
            engine.accept(s4, reachEv(4_000L));
            assertEquals(2, mitigations.size());
            assertEquals(1, trips.size());
            assertTrue(engine.circuitBreaker().isSuppressed());
            assertEquals(Action.ALERT, cases.getLast().authorizedAction());
            assertTrue(cases.getLast().suppressedBySafetyGate());

            // Reset restores enforcement
            engine.circuitBreaker().resetAutomaticTrip();
            assertFalse(engine.circuitBreaker().isSuppressed());
            PlayerSession s5 = registry.openReplay("p5");
            engine.accept(s5, reachEv(5_000L));
            assertEquals(3, mitigations.size());
        }
    }

    @Test
    void falseBanBudgetLocksOutFutureAutoTempBansAfterOverturn() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach", "movement"))
                .autoBanEnabled(true)
                .secondVerifierEnabled(false)
                .requireCorroborationForTempBan(true)
                .maxFalseBanBudgetPerWindow(1)
                .build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none(), clock::get)) {
            List<VerdictDecision> bans = new ArrayList<>();
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override public void tempBan(VerdictDecision decision) { bans.add(decision); }
            });
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});

            // One overturned ban exhausts the budget (limit=1)
            assertTrue(engine.falseBanBudget().recordIncident(UUID.randomUUID().toString(), "manual overturn", clock.get()));
            assertTrue(engine.falseBanBudget().isLocked());

            PlayerSession s = registry.openReplay("budget-locked");
            engine.accept(s, reachEv(1_000L));
            engine.accept(s, moveEv(2_000L));
            assertTrue(bans.isEmpty(), "locked FalseBanBudget must block auto-bans even for 2-group P-A");

            engine.falseBanBudget().reset();
            assertFalse(engine.falseBanBudget().isLocked());
            PlayerSession s2 = registry.openReplay("budget-reset");
            engine.accept(s2, reachEv(10_000L));
            engine.accept(s2, moveEv(11_000L));
            assertEquals(1, bans.size());
        }
    }
}
