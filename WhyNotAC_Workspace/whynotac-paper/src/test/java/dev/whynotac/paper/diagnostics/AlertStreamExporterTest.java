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
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    void postsJsonAlertToLoopbackHttpWebhook(@TempDir Path dir) throws Exception {
        CopyOnWriteArrayList<String> receivedBodies = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/alerts", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            receivedBodies.add(new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
            latch.countDown();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/alerts";
            SessionRegistry registry = new SessionRegistry(Runnable::run, 16, (s, e, err) -> { throw new AssertionError(err); });
            PlayerSession session = registry.open("webhook-test");
            session.bind(new UUID(2, 3), "WebhookTarget", 1, 100L);
            Evidence ev = new Evidence(
                    "velocity-response", 1, EvidenceClass.ADVANTAGE_PROOF,
                    10L, 12L, 1_000_000L, 2_000_000L,
                    "ignored kb", Map.of(),
                    new Admissibility(true, List.of(), Admissibility.IdentityTier.AUTHENTICATED));
            EvidenceCertificate cert = EvidenceCertificate.issue(
                    "cert-2", 1000L, session.connectionId(),
                    ev.check(), ev.checkVersion(), ev.evidenceClass(),
                    ActionPolicy.Lifecycle.ARMED, Admissibility.IdentityTier.AUTHENTICATED,
                    ev.fingerprint(), List.of(), List.of(DependencyGroup.MOVEMENT_PHYSICS.id()),
                    EvidenceCertificate.VerificationStatus.NOT_NEEDED, "",
                    ActionPolicy.Action.NEUTRALIZE, MitigationKind.SETBACK_POSITION);
            VerdictCase vCase = new VerdictCase(
                    "case-webhook-1", 1000L, session, ev,
                    ActionPolicy.Lifecycle.ARMED, DependencyGroup.MOVEMENT_PHYSICS,
                    EnumSet.of(ActionPolicy.Action.ALERT, ActionPolicy.Action.NEUTRALIZE),
                    ActionPolicy.Action.NEUTRALIZE, MitigationKind.SETBACK_POSITION,
                    1.0, 0.9, 1, false, cert);
            AiAdvisor advisor = new AiAdvisor();
            advisor.opened(vCase);
            try (AlertStreamExporter exporter = new AlertStreamExporter(
                    dir.resolve("alerts.jsonl"), advisor, Logger.getLogger("webhook-test"), url)) {
                exporter.opened(vCase);
                assertTrue(latch.await(5, TimeUnit.SECONDS), "Webhook HTTP POST must arrive within 5s");
            }
            assertEquals(1, receivedBodies.size());
            assertTrue(receivedBodies.get(0).contains("\"caseId\":\"case-webhook-1\""));
            assertTrue(receivedBodies.get(0).contains("\"check\":\"velocity-response\""));
        } finally {
            server.stop(0);
        }
    }
}
