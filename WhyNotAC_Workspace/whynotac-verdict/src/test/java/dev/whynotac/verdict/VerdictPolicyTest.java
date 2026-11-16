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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VerdictPolicyTest {

    private static Evidence ev(String check, EvidenceClass cls, IdentityTier tier, long nano) {
        return new Evidence(check, Math.max(1, Checks.version(check)), cls, 1, 2, nano, nano, check, Map.of("n", Long.toString(nano)),
                new Admissibility(true, List.of(), tier));
    }

    @Test
    void shadowDefaultsNeverTriggerAnyEnforcementAction() {
        try (VerdictEngine engine = new VerdictEngine(VerdictConfig.shadowDefaults(), SecondVerifier.ReplaySource.none())) {
            List<VerdictCase> cases = new ArrayList<>();
            AtomicInteger enforcements = new AtomicInteger();
            engine.addCaseListener(cases::add);
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override public void mitigate(VerdictDecision decision) { enforcements.incrementAndGet(); }
                @Override public void kick(VerdictDecision decision) { enforcements.incrementAndGet(); }
                @Override public void tempBan(VerdictDecision decision) { enforcements.incrementAndGet(); }
            });
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});
            PlayerSession session = registry.openReplay("shadow");

            engine.accept(session, ev("attack-reach", EvidenceClass.ADVANTAGE_PROOF, IdentityTier.AUTHENTICATED, 1_000L));
            engine.accept(session, ev("movement", EvidenceClass.ADVANTAGE_PROOF, IdentityTier.AUTHENTICATED, 2_000L));

            assertEquals(0, enforcements.get());
            for (VerdictCase c : cases) {
                assertEquals(Lifecycle.SHADOW, c.lifecycle());
                assertEquals(Set.of(Action.ALERT, Action.REVIEW), c.permittedActions());
                assertEquals(Action.ALERT, c.authorizedAction());
                assertEquals(MitigationKind.NONE, c.mitigation());
                assertTrue(c.certificate().verifyIntegrity());
            }
        }
    }

    @Test
    void statisticalAndDesignAndContextClassesNeverPunishEvenWhenArmedAndOptedIn() {
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("click-pattern", "aim-pattern", "design-probe", "context-note"))
                .mitigationsEnabled(true)
                .autoKickEnabled(true)
                .autoBanEnabled(true)
                .requireCorroborationForTempBan(false)
                .minScoreForKick(0.1)
                .build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none())) {
            List<VerdictCase> cases = new ArrayList<>();
            AtomicInteger enforcements = new AtomicInteger();
            engine.addCaseListener(cases::add);
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override public void mitigate(VerdictDecision decision) { enforcements.incrementAndGet(); }
                @Override public void kick(VerdictDecision decision) { enforcements.incrementAndGet(); }
                @Override public void tempBan(VerdictDecision decision) { enforcements.incrementAndGet(); }
            });
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});
            PlayerSession session = registry.openReplay("s-never-ban");

            for (int i = 0; i < 20; i++) {
                engine.accept(session, ev("click-pattern", EvidenceClass.NOMINAL_STATISTIC, IdentityTier.AUTHENTICATED, i * 1_000L + 1));
                engine.accept(session, ev("aim-pattern", EvidenceClass.NOMINAL_STATISTIC, IdentityTier.AUTHENTICATED, i * 1_000L + 2));
                engine.accept(session, ev("design-probe", EvidenceClass.DESIGN_STATISTIC, IdentityTier.AUTHENTICATED, i * 1_000L + 3));
                engine.accept(session, ev("context-note", EvidenceClass.CONTEXT, IdentityTier.AUTHENTICATED, i * 1_000L + 4));
            }
            assertEquals(0, enforcements.get());
            for (VerdictCase c : cases) {
                assertFalse(c.authorizedAction() == Action.NEUTRALIZE
                        || c.authorizedAction() == Action.KICK
                        || c.authorizedAction() == Action.TEMP_BAN
                        || c.authorizedAction() == Action.PERMANENT_BAN);
            }
        }
    }

    @Test
    void modificationProofAndUnauthenticatedIdentitiesNeverAutoTempBan() {
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Checks.ids())
                .mitigationsEnabled(true)
                .autoKickEnabled(true)
                .autoBanEnabled(true)
                .requireCorroborationForTempBan(false)
                .build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none())) {
            List<VerdictDecision> bans = new ArrayList<>();
            List<VerdictDecision> mitigations = new ArrayList<>();
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override public void mitigate(VerdictDecision decision) { mitigations.add(decision); }
                @Override public void tempBan(VerdictDecision decision) { bans.add(decision); }
            });
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});

            PlayerSession pmSession = registry.openReplay("pm-auth");
            engine.accept(pmSession, ev("client-clock", EvidenceClass.MODIFICATION_PROOF, IdentityTier.AUTHENTICATED, 1_000L));
            engine.accept(pmSession, ev("tick-framing", EvidenceClass.MODIFICATION_PROOF, IdentityTier.AUTHENTICATED, 2_000L));
            engine.accept(pmSession, ev("break-time", EvidenceClass.MODIFICATION_PROOF, IdentityTier.AUTHENTICATED, 3_000L));
            engine.accept(pmSession, ev("attack-aim", EvidenceClass.MODIFICATION_PROOF, IdentityTier.AUTHENTICATED, 4_000L));
            assertTrue(bans.isEmpty(), "P-M must never auto-temp-ban");

            PlayerSession unauthSession = registry.openReplay("pa-unauth");
            engine.accept(unauthSession, ev("attack-reach", EvidenceClass.ADVANTAGE_PROOF, IdentityTier.UNAUTHENTICATED, 10_000L));
            engine.accept(unauthSession, ev("movement", EvidenceClass.ADVANTAGE_PROOF, IdentityTier.UNAUTHENTICATED, 11_000L));
            assertTrue(bans.isEmpty(), "UNAUTHENTICATED must never auto-temp-ban");
            long unauthMitigations = mitigations.stream().filter(d -> d.session() == unauthSession).count();
            assertEquals(2, unauthMitigations, "soft NEUTRALIZE mitigations still apply to UNAUTHENTICATED ARMED P-A");
        }
    }

    @Test
    void twoIndependentArmedAdvantageProofGroupsTriggerAutoTempBanForAuthenticatedAndAuthPluginConfirmed() {
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach", "movement"))
                .mitigationsEnabled(true)
                .autoBanEnabled(true)
                .secondVerifierEnabled(false)
                .requireCorroborationForTempBan(true)
                .build();
        try (VerdictEngine engine = new VerdictEngine(cfg, SecondVerifier.ReplaySource.none())) {
            List<VerdictDecision> bans = new ArrayList<>();
            List<VerdictDecision> mitigations = new ArrayList<>();
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override public void mitigate(VerdictDecision decision) { mitigations.add(decision); }
                @Override public void tempBan(VerdictDecision decision) { bans.add(decision); }
            });
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});

            for (IdentityTier tier : List.of(IdentityTier.AUTHENTICATED, IdentityTier.AUTH_PLUGIN_CONFIRMED)) {
                PlayerSession s = registry.openReplay("ban-" + tier);
                // First P-A group: only mitigates, does NOT ban yet (single group without 2nd verifier)
                engine.accept(s, ev("attack-reach", EvidenceClass.ADVANTAGE_PROOF, tier, 1_000L));
                assertEquals(bans.size(), tier == IdentityTier.AUTHENTICATED ? 0 : 1);

                // Second independent P-A group: corroborates -> TEMP_BAN!
                engine.accept(s, ev("movement", EvidenceClass.ADVANTAGE_PROOF, tier, 2_000L));
            }
            assertEquals(2, bans.size());
            assertEquals(2, mitigations.size());
            assertTrue(bans.stream().allMatch(b -> b.certificate().verifyIntegrity()));
        }
    }
}
