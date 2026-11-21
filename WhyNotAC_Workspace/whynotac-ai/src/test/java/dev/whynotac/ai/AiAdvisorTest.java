package dev.whynotac.ai;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.verdict.DependencyGroup;
import dev.whynotac.verdict.EvidenceCertificate;
import dev.whynotac.verdict.MitigationKind;
import dev.whynotac.verdict.VerdictCase;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AiAdvisorTest {

    @Test
    void extractsSessionFeaturesAssessesShiftPrioritizesCaseAndRespectsForget() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 4096, (s, e, err) -> {
            throw new AssertionError("Listener failed", err);
        });
        AiAdvisor advisor = new AiAdvisor();
        registry.addListener(advisor);

        UUID playerId = UUID.randomUUID();

        // Two baseline sessions with steady ~5-tick clicks and moderate 3-deg aim steps
        for (int s = 0; s < 2; s++) {
            PlayerSession base = registry.open("127.0.0.1:100" + s);
            base.bind(playerId, "TestPlayer", 1, 100L);
            feedCombatSession(base, 5, 3.0f, 0.20, 40);
            advisor.mergeCleanSession(playerId, base);
            base.disconnect(10_000_000_000L);
        }

        // Third session switches suddenly to 1-tick auto-clicker & 22-deg snap aim + probe wealth
        PlayerSession suspect = registry.open("127.0.0.1:2000");
        suspect.bind(playerId, "TestPlayer", 1, 100L);
        feedCombatSession(suspect, 1, 22.0f, 0.35, 30);

        for (int i = 0; i < 4; i++) {
            advisor.probeScheduler().recordOutcome(playerId, 1.0, 0.05);
        }

        BehavioralFeatureExtractor.Snapshot snap = advisor.extractor().snapshot(suspect);
        assertTrue(snap.features().get(BehavioralFeatureExtractor.CLICK_INTERVAL_TICKS).count() > 0);
        assertTrue(snap.features().get(BehavioralFeatureExtractor.AIM_ANGULAR_STEP_DEG).count() > 0);
        assertTrue(snap.features().get(BehavioralFeatureExtractor.TICK_DELTA_MS).count() > 0);
        assertTrue(snap.features().get(BehavioralFeatureExtractor.COMBAT_AIM_STEP_DEG).count() > 0);
        assertTrue(snap.features().get(BehavioralFeatureExtractor.COMBAT_AIM_JERK_DEG).count() > 0);
        assertTrue(snap.features().get(BehavioralFeatureExtractor.ATTACK_SNAP_RATIO).count() > 0);
        assertTrue(snap.combatCovariance().count() > 0);

        AiAdvisor.IntrinsicRiskAssessment intrinsic = advisor.assessIntrinsicRisk(snap);
        assertTrue(intrinsic.score0To100() > 20.0, "Expected intrinsic cold-start risk > 20, got " + intrinsic.score0To100());
        assertFalse(intrinsic.dominantSignal().equals("none"));

        Evidence ev = new Evidence(
                "click-pattern", 1, EvidenceClass.NOMINAL_STATISTIC,
                1L, 100L, 1_000_000L, 50_000_000L,
                "low click variance", Map.of(),
                new Admissibility(true, List.of(), Admissibility.IdentityTier.AUTHENTICATED));
        VerdictCase vCase = new VerdictCase(
                "case-ai-1", 1000L, suspect, ev, Lifecycle.ARMED, DependencyGroup.STATISTICAL_SIGNALS,
                EnumSet.of(Action.ALERT, Action.REVIEW), Action.REVIEW, MitigationKind.NONE,
                0.0, 0.9, 1, false,
                EvidenceCertificate.issue("cert-ai-1", 1000L, suspect.connectionId(),
                        ev.check(), ev.checkVersion(), ev.evidenceClass(), Lifecycle.ARMED,
                        Admissibility.IdentityTier.AUTHENTICATED, ev.fingerprint(),
                        List.of(), List.of(DependencyGroup.STATISTICAL_SIGNALS.id()),
                        EvidenceCertificate.VerificationStatus.NOT_NEEDED, "", Action.REVIEW, MitigationKind.NONE));

        advisor.opened(vCase);
        AiAdvisor.CasePriority priority = advisor.priorityOf("case-ai-1");
        assertNotNull(priority);
        assertFalse(priority.shift().coldStart());
        assertTrue(priority.score0To100() > 30.0 && priority.score0To100() <= 100.0,
                "Expected elevated priority score in (30, 100], got " + priority.score0To100());
        assertTrue(priority.eValueWealth() > 100.0);
        // Advisory layer must keep MitigationKind.NONE intact on VerdictCase
        assertEquals(MitigationKind.NONE, vCase.mitigation());

        // GDPR erasure cleans baseline, probe state, and case priority entry
        advisor.forget(playerId);
        assertNull(advisor.priorityOf("case-ai-1"));
        assertNull(advisor.probeScheduler().processFor(playerId));
        assertTrue(advisor.baselineModel().baselineSnapshot(playerId).isEmpty());

        suspect.disconnect(10_000_000_000L);
    }

    @Test
    void detectsMidSessionToggleAndAdaptiveMartingaleBetting() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 4096, (s, e, err) -> {
            throw new AssertionError("Listener failed", err);
        });
        AiAdvisor advisor = new AiAdvisor();
        registry.addListener(advisor);

        PlayerSession toggleSession = registry.open("127.0.0.1:3000");
        toggleSession.bind(UUID.randomUUID(), "TogglePlayer", 1, 100L);

        // First half: 30 human attacks with varied intervals (4..7 ticks) and moderate aim
        long nano = 1_000_000_000L;
        float yaw = 0.0f;
        int tick = 0;
        for (int a = 0; a < 30; a++) {
            int gap = 4 + (a % 4);
            for (int i = 0; i < gap; i++) {
                yaw = (yaw + 2.0f + (i % 3) * 0.7f) % 360.0f;
                toggleSession.onInbound(new ServerboundPacket.MovePlayer(0.0, 64.0, 0.0, yaw, 5.0f, true, true, true, false), nano);
                if (i == gap - 1) {
                    toggleSession.onInbound(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK,
                            Hand.MAIN_HAND, Vec3d.ZERO, false), nano + 100_000L);
                }
                toggleSession.onInbound(new ServerboundPacket.ClientTickEnd(), nano + 50_000_000L);
                nano += 50_000_000L;
                tick++;
            }
        }

        // Second half: player toggles 1-tick constant-interval auto-clicker & 20-deg snap aim for 32 ticks
        for (int a = 0; a < 32; a++) {
            yaw = (yaw + 20.0f) % 360.0f;
            toggleSession.onInbound(new ServerboundPacket.MovePlayer(0.0, 64.0, 0.0, yaw, 5.0f, true, true, true, false), nano);
            toggleSession.onInbound(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK,
                    Hand.MAIN_HAND, Vec3d.ZERO, false), nano + 100_000L);
            toggleSession.onInbound(new ServerboundPacket.ClientTickEnd(), nano + 50_000_000L);
            nano += 50_000_000L;
            tick++;
        }

        BehavioralFeatureExtractor.Snapshot snap = advisor.extractor().snapshot(toggleSession);
        assertTrue(snap.intraSessionToggleZ() >= 5.0,
                "Expected high intraSessionToggleZ after mid-session toggle, got " + snap.intraSessionToggleZ());
        AiAdvisor.IntrinsicRiskAssessment risk = advisor.assessIntrinsicRisk(snap);
        assertTrue(risk.score0To100() >= 25.0, "Expected intrinsic risk >= 25 after toggle, got " + risk.score0To100());

        EValueProcess adaptive = new EValueProcess(0.001);
        double w1 = adaptive.observeAdaptive(1.0, 0.05);
        double lambda1 = adaptive.lastLambda();
        double w2 = adaptive.observeAdaptive(1.0, 0.05);
        double lambda2 = adaptive.lastLambda();
        assertTrue(w2 > w1 && lambda2 > lambda1, "Predictable Kelly bet should increase after prior hit");

        toggleSession.disconnect(nano + 1_000_000_000L);
    }

    private static void feedCombatSession(
            PlayerSession session, int clickEveryNTicks, float yawStepDeg, double moveStep, int attackCount) {
        long nano = 1_000_000_000L;
        int totalTicks = attackCount * clickEveryNTicks + 2;
        float yaw = 0.0f;
        double x = 0.0;
        for (int t = 0; t < totalTicks; t++) {
            yaw = (yaw + yawStepDeg) % 360.0f;
            x += moveStep;
            session.onInbound(new ServerboundPacket.MovePlayer(x, 64.0, 0.0, yaw, 10.0f, true, true, true, false), nano);
            if (t % clickEveryNTicks == 0) {
                session.onInbound(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK,
                        Hand.MAIN_HAND, Vec3d.ZERO, false), nano + 100_000L);
            }
            session.onInbound(new ServerboundPacket.ClientTickEnd(), nano + 50_000_000L);
            nano += 50_000_000L;
        }
    }
}
