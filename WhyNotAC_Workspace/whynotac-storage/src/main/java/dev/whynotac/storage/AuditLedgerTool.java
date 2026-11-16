package dev.whynotac.storage;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline integrity verification CLI for {@link AuditLedger} invoked via {@code java -jar WhyNotAC.jar audit <storage-dir>}.
 */
public final class AuditLedgerTool {

    private AuditLedgerTool() {}

    public static int verifyLedger(Path storageDir, PrintStream out, PrintStream err) {
        Path ledgerFile = storageDir.resolve("audit-ledger.jsonl");
        Path secretFile = storageDir.resolve("audit-secret.key");
        if (!Files.exists(ledgerFile) || !Files.exists(secretFile)) {
            err.println("missing audit-ledger.jsonl or audit-secret.key at " + storageDir);
            return 1;
        }
        try (AuditLedger ledger = new AuditLedger(ledgerFile, secretFile, "offline-verify")) {
            AuditLedger.ChainVerification v = ledger.verify();
            out.println("{\"valid\":" + v.valid()
                    + ",\"entries\":" + v.entryCount()
                    + ",\"lastSequence\":" + v.lastSequence()
                    + ",\"head\":\"" + v.headHash() + "\""
                    + (v.valid() ? "" : ",\"failure\":\"" + escape(v.failureReason()) + "\"")
                    + "}");
            return v.valid() ? 0 : 2;
        } catch (IOException | RuntimeException e) {
            err.println("cannot verify audit ledger at " + storageDir + ": " + e.getMessage());
            return 1;
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
