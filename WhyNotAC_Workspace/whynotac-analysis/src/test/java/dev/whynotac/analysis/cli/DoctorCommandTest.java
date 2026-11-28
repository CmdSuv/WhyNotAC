package dev.whynotac.analysis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DoctorCommandTest {

    @Test
    void passesOnHealthyConfigurationAndKeyPermissions(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("config.yml"), """
                recording:
                  enabled: true
                observation:
                  edge: true
                verdict:
                  armed-checks:
                    - "attack-reach"
                    - "velocity-response"
                  auto-ban: false
                  second-verifier: true
                  require-corroboration-for-ban: true
                observability:
                  dashboard-enabled: true
                  metrics-auth-token: "strong-token-27"
                """, StandardCharsets.UTF_8);
        Path key = dataDir.resolve("network-secret.key");
        Files.write(key, new byte[32]);
        try {
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) { }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = DoctorCommand.runCli(dataDir, new PrintStream(out), new PrintStream(err));
        assertEquals(0, exit, out.toString(StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("DOCTOR_JSON:"));
        assertTrue(text.contains("\"fail\":0"));
    }

    @Test
    void flagsConfigContradictionsExposedKeysAndCorruptSqliteDatabase(@TempDir Path dataDir) throws Exception {
        Files.writeString(dataDir.resolve("config.yml"), """
                recording:
                  enabled: false
                observation:
                  edge: false
                verdict:
                  armed-checks:
                    - "nonexistent-check-xyz"
                  auto-ban: true
                  second-verifier: false
                observability:
                  dashboard-enabled: true
                  metrics-auth-token: ""
                """, StandardCharsets.UTF_8);
        Path weakKey = dataDir.resolve("recording-secret.key");
        Files.write(weakKey, new byte[12]); // too short (<32)
        Path exposedKey = dataDir.resolve("network-secret.key");
        Files.write(exposedKey, new byte[32]);
        try {
            Files.setPosixFilePermissions(exposedKey, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ignored) { }
        Files.writeString(dataDir.resolve("cases.db"), "not-a-sqlite-file", StandardCharsets.UTF_8);

        DoctorCommand.DoctorReport report = DoctorCommand.diagnose(dataDir);
        assertTrue(report.failCount() >= 3, "Expected >= 3 FAILs (dashboard-auth, weak key, corrupt cases.db): " + report.checks());
        assertTrue(report.warnCount() >= 2, "Expected >= 2 WARNs (unknown check, auto-ban safety, edge=false): " + report.checks());
        assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("config.dashboard-auth") && c.status() == DoctorCommand.Status.FAIL));
        assertTrue(report.checks().stream().anyMatch(c -> c.name().equals("cases.db") && c.status() == DoctorCommand.Status.FAIL));
    }
}
