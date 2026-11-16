package dev.whynotac.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DependencyGroupTest {

    private static Admissibility verified() {
        return new Admissibility(true, List.of(), IdentityTier.AUTHENTICATED);
    }

    private static Evidence ev(String check, EvidenceClass cls, long nano) {
        return new Evidence(check, Math.max(1, Checks.version(check)), cls, 1, 2, nano, nano, check + " finding",
                Map.of("k", "v" + nano), verified());
    }

    @Test
    void everyRegisteredCheckHasAnExplicitDependencyGroupMapping() {
        for (String id : Checks.ids()) {
            assertTrue(DependencyGroup.mappings().containsKey(id),
                    "Check '" + id + "' is missing from DependencyGroup.mappings()");
        }
    }

    @Test
    void correlatedFindingsInOneGroupSaturateWhileIndependentGroupsAdd() {
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Checks.ids())
                .mitigationsEnabled(true)
                .autoKickEnabled(true)
                .sameGroupCapMultiplier(1.5)
                .minScoreForKick(3.0)
                .build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none())) {
            List<VerdictCase> cases = new ArrayList<>();
            engine.addCaseListener(cases::add);
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});
            PlayerSession session = registry.openReplay("test");

            // 10 findings inside the same group (ATTACK_REACH) must saturate at 1.5x (below 3.0 kick threshold)
            for (int i = 0; i < 10; i++) {
                engine.accept(session, ev("attack-reach", EvidenceClass.ADVANTAGE_PROOF, 1_000_000L * (i + 1)));
            }
            VerdictCase after10Reach = cases.getLast();
            assertEquals(1.5, after10Reach.correlatedEnforcementScore(), 1e-6);
            assertEquals(1, after10Reach.independentAdvantageGroups());

            // Findings in distinct independent groups add cleanly
            engine.accept(session, ev("movement", EvidenceClass.ADVANTAGE_PROOF, 20_000_000L));
            engine.accept(session, ev("client-clock", EvidenceClass.MODIFICATION_PROOF, 30_000_000L));
            engine.accept(session, ev("break-time", EvidenceClass.MODIFICATION_PROOF, 40_000_000L));
            VerdictCase combined = cases.getLast();
            assertTrue(combined.correlatedEnforcementScore() >= 3.0,
                    "Expected combined score across 4 groups >= 3.0, got " + combined.correlatedEnforcementScore());
            assertEquals(2, combined.independentAdvantageGroups());
        }
    }

    @Test
    void statisticalAndDesignFindingsNeverAddEnforcementScore() {
        VerdictConfig cfg = VerdictConfig.builder().armedChecks(Checks.ids()).build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none())) {
            List<VerdictCase> cases = new ArrayList<>();
            engine.addCaseListener(cases::add);
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});
            PlayerSession session = registry.openReplay("stats");

            for (int i = 0; i < 50; i++) {
                engine.accept(session, ev("click-pattern", EvidenceClass.NOMINAL_STATISTIC, (i + 1) * 1_000L));
                engine.accept(session, ev("aim-pattern", EvidenceClass.NOMINAL_STATISTIC, (i + 1) * 1_000L + 500));
                engine.accept(session, ev("probe", EvidenceClass.DESIGN_STATISTIC, (i + 1) * 1_000L + 700));
            }
            VerdictCase last = cases.getLast();
            assertEquals(0.0, last.correlatedEnforcementScore(), 1e-12);
            assertTrue(last.reviewPriorityScore() > 0.0);
        }
    }

    @Test
    void halfLifeDecayReducesOldViolationScoresOverTime() {
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach"))
                .correlationHalfLifeNanos(10_000_000_000L) // 10 s
                .build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none())) {
            List<VerdictCase> cases = new ArrayList<>();
            engine.addCaseListener(cases::add);
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});
            PlayerSession session = registry.openReplay("decay");

            engine.accept(session, ev("attack-reach", EvidenceClass.ADVANTAGE_PROOF, 1_000_000_000L));
            assertEquals(1.0, cases.getLast().correlatedEnforcementScore(), 1e-6);

            // After 10 seconds (1 half-life), old 1.0 decays to 0.5; + 1.0 capped at 1.5 -> wait test with different group:
            engine.accept(session, ev("movement", EvidenceClass.ADVANTAGE_PROOF, 11_000_000_000L));
            assertEquals(1.5, cases.getLast().correlatedEnforcementScore(), 1e-6);
        }
    }
}
