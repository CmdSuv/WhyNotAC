package dev.whynotac.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuditLedgerToolTest {

    @Test
    void verifiesLedgerOfflineAndReportsChainMetadata(@TempDir Path dir) throws IOException {
        try (StorageManager sm = new StorageManager(dir, "test-build", Logger.getLogger("AuditLedgerToolTest"))) {
            sm.ledger().append(AuditLedger.EntryKind.CASE_OPENED, 1000L, "case-1", "attack-reach", UUID.randomUUID(), "test finding");
            sm.ledger().append(AuditLedger.EntryKind.APPEAL_RESOLVED, 2000L, "case-1", "attack-reach", UUID.randomUUID(), "upheld");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = AuditLedgerTool.verifyLedger(dir, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"valid\":true"));
        assertTrue(json.contains("\"entries\":2"));
    }
}
