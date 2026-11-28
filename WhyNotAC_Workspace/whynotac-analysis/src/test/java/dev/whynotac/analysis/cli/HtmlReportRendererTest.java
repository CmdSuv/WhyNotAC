package dev.whynotac.analysis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HtmlReportRendererTest {

    @Test
    void rendersSingleDossierAndMultiCaseDirectoryOfflineWithStrictXssEscaping(@TempDir Path dir) throws Exception {
        Path casesDir = dir.resolve("cases");
        Files.createDirectories(casesDir);

        String xssDossier = """
                {
                  "id": "case-xss-001",
                  "created": "2026-11-24T05:00:00Z",
                  "check": "movement-simulation",
                  "checkVersion": 1,
                  "evidenceClass": "P-A",
                  "lifecycle": "ARMED",
                  "playerName": "<script>alert('xss')</script>",
                  "playerId": "11111111-1111-1111-1111-111111111111",
                  "identity": "AUTHENTICATED",
                  "fromSequence": 100,
                  "toSequence": 140,
                  "summary": "hover </script><img src=x onerror=alert(1)> & \\"quotes\\"",
                  "details": {
                    "predicted": "1.0,64.0,2.0",
                    "payload": "</script><!--comment-->"
                  },
                  "admissible": true,
                  "fingerprint": "fp-xss-001",
                  "recording": "../recordings/demo.wnrec",
                  "dependencyGroup": "movement-physics",
                  "authorizedAction": "TEMP_BAN",
                  "mitigation": "SETBACK_POSITION",
                  "verificationStatus": "VERIFIED",
                  "certificateId": "cert-001",
                  "certificateSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "aiPriorityScore": 91.50,
                  "aiIntrinsicRisk": 84.20,
                  "aiDominantSignal": "aim_snap",
                  "aiBaselineRmsZ": 3.850,
                  "aiTopFeature": "mahalanobis_4d",
                  "aiProbeWealth": 240.0000,
                  "whynotacVersion": "0.1.0-SNAPSHOT"
                }
                """;
        String secondDossier = """
                {
                  "id": "case-stat-002",
                  "created": "2026-11-24T05:05:00Z",
                  "check": "reach-distribution",
                  "checkVersion": 1,
                  "evidenceClass": "S",
                  "lifecycle": "SHADOW",
                  "playerName": "SuspectTwo",
                  "playerId": "22222222-2222-2222-2222-222222222222",
                  "identity": "AUTHENTICATED",
                  "fromSequence": 200,
                  "toSequence": 260,
                  "summary": "median reach 2.978 across 44 hits",
                  "details": { "medianReach": "2.978" },
                  "admissible": true,
                  "fingerprint": "fp-stat-002",
                  "dependencyGroup": "statistical-signals",
                  "authorizedAction": "ALERT",
                  "mitigation": "NONE",
                  "verificationStatus": "NOT_NEEDED",
                  "whynotacVersion": "0.1.0-SNAPSHOT"
                }
                """;
        Path file1 = casesDir.resolve("case-xss-001.json");
        Path file2 = casesDir.resolve("case-stat-002.json");
        Files.writeString(file1, xssDossier, StandardCharsets.UTF_8);
        Files.writeString(file2, secondDossier, StandardCharsets.UTF_8);

        // 1. Directory render
        Path dirReport = dir.resolve("all-cases.html");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = HtmlReportRenderer.runCli(casesDir, dirReport, new PrintStream(out), new PrintStream(err));
        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        String html = Files.readString(dirReport, StandardCharsets.UTF_8);

        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("Content-Security-Policy"));
        assertTrue(html.contains("<svg "));
        assertTrue(html.contains("movement-simulation"));
        assertTrue(html.contains("reach-distribution"));
        assertTrue(html.contains("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertTrue(html.contains("4x4 Mahalanobis"));
        // Strict offline guarantee: zero external HTTP(S) URLs
        assertFalse(html.contains("http://"), "Offline report must not reference http://");
        assertFalse(html.contains("https://"), "Offline report must not reference https://");
        // Strict XSS protection: raw injected script/img tags must never appear unescaped anywhere!
        assertFalse(html.contains("<script>alert("), "Raw <script> payload must be HTML-escaped");
        assertFalse(html.contains("<img src=x"), "Raw <img> payload must be HTML-escaped");
        assertTrue(html.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"));
        assertTrue(html.contains("\\u003c/script\\u003e"), "Embedded script JSON must escape </script>");
        // Exactly one closing </script> tag (the report's own script block)
        assertEquals(html.indexOf("</script>"), html.lastIndexOf("</script>"),
                "Injected </script> must not close the report script tag");

        // 2. Single-dossier render
        Path singleReport = dir.resolve("single.html");
        assertEquals(0, HtmlReportRenderer.runCli(file1, singleReport, new PrintStream(out), new PrintStream(err)));
        assertTrue(Files.readString(singleReport, StandardCharsets.UTF_8).contains("case-xss-001"));
    }
}
