package dev.whynotac.analysis.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ToolTest {

    @Test
    void explainsDossierHumanReadableAndRejectsMissing(@TempDir Path dir) throws IOException {
        Path dossier = dir.resolve("case.json");
        Files.writeString(dossier, """
                {
                  "id": "case-123",
                  "playerName": "PlayerOne",
                  "identity": "AUTHENTICATED_MOJANG",
                  "check": "attack-reach",
                  "evidenceClass": "P-A",
                  "lifecycle": "SHADOW",
                  "summary": "attacked sheep from 6.5 blocks",
                  "fingerprint": "attack-reach@1 P-A [1..2] {}"
                }
                """, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Tool.explain(dossier, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("PlayerOne (AUTHENTICATED_MOJANG)"));
        assertTrue(printed.contains("Advantage proof"));
        assertTrue(printed.contains("java -jar WhyNotAC.jar verify"));

        assertEquals(1, Tool.explain(dir.resolve("missing.json"),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)));
    }
}
