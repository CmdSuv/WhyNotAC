package dev.whynotac.paper.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.ai.AiAdvisor;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.verdict.DependencyGroup;
import dev.whynotac.verdict.EvidenceCertificate;
import dev.whynotac.verdict.MitigationKind;
import dev.whynotac.verdict.VerdictCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AlertStreamExporterTest {

    @Test
    void appendsSingleLineJsonWithEscapedFields(@TempDir Path dir) throws Exception {
        Path outFile = dir.resolve("alerts-stream.jsonl");
        SessionRegistry registry = new SessionRegistry(Runnable::run, 16, (s, e, err) -> { throw new AssertionError(err); });
        PlayerSession session = registry.open("stream-test");
        UUID playerId = new UUID(1, 2);
        session.bind(playerId, "Cheater\"One", 1, 100L);

        Evidence ev = new Evidence(
                "velocity-response", 1, EvidenceClass.ADVANTAGE_PROOF,
                10L, 12L, 1_000_000L, 2_000_000L,
                "ignored vertical kb\nline2", Map.of(),
                new Admissibility(true, List.of(), Admissibility.IdentityTier.AUTHENTICATED));
        EvidenceCertificate cert = EvidenceCertificate.issue(
                "cert-1", 1000L, session.connectionId(),
                ev.check(), ev.checkVersion(), ev.evidenceClass(),
                ActionPolicy.Lifecycle.ARMED, Admissibility.IdentityTier.AUTHENTICATED,
                ev.fingerprint(), List.of(), List.of(DependencyGroup.MOVEMENT_PHYSICS.id()),
                EvidenceCertificate.VerificationStatus.NOT_NEEDED, "",
                ActionPolicy.Action.NEUTRALIZE, MitigationKind.SETBACK_POSITION);
        VerdictCase vCase = new VerdictCase(
                "case-stream-1", 1000L, session, ev,
                ActionPolicy.Lifecycle.ARMED, DependencyGroup.MOVEMENT_PHYSICS,
                EnumSet.of(ActionPolicy.Action.ALERT, ActionPolicy.Action.NEUTRALIZE),
                ActionPolicy.Action.NEUTRALIZE, MitigationKind.SETBACK_POSITION,
                1.0, 0.9, 1, false, cert);

        AiAdvisor advisor = new AiAdvisor();
        advisor.opened(vCase);
        try (AlertStreamExporter exporter = new AlertStreamExporter(outFile, advisor, Logger.getLogger("test"))) {
            exporter.opened(vCase);
        }

        List<String> lines = Files.readAllLines(outFile);
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("{") && line.endsWith("}"));
        assertTrue(line.contains("\"caseId\":\"case-stream-1\""));
        assertTrue(line.contains("\"check\":\"velocity-response\""));
        assertTrue(line.contains("\"playerName\":\"Cheater\\\"One\""));
        assertTrue(line.contains("\"mitigation\":\"SETBACK_POSITION\""));
    }
}
