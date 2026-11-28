package dev.whynotac.storage;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
        try {
            List<Path> segments = new ArrayList<>();
            try (Stream<Path> s = Files.list(storageDir)) {
                s.filter(p -> Files.isRegularFile(p)
                                && p.getFileName().toString().startsWith("audit-ledger-")
                                && p.getFileName().toString().endsWith(".jsonl"))
                        .sorted()
                        .forEach(segments::add);
            }
            segments.add(ledgerFile);
            long totalEntries = 0L;
            long lastSeq = -1L;
            String head = AuditLedger.GENESIS_HASH;
            for (Path seg : segments) {
                try (AuditLedger ledger = new AuditLedger(seg, secretFile, "offline-verify")) {
                    AuditLedger.ChainVerification v = ledger.verify();
                    if (!v.valid()) {
                        out.println("{\"valid\":false,\"segment\":\"" + escape(seg.getFileName().toString())
                                + "\",\"entries\":" + totalEntries
                                + ",\"failure\":\"" + escape(v.failureReason()) + "\"}");
                        return 2;
                    }
                    totalEntries += v.entryCount();
                    if (v.lastSequence() >= 0) {
                        lastSeq = v.lastSequence();
                    }
                    head = v.headHash();
                }
            }
            out.println("{\"valid\":true,\"segments\":" + segments.size()
                    + ",\"entries\":" + totalEntries
                    + ",\"lastSequence\":" + lastSeq
                    + ",\"head\":\"" + head + "\"}");
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("cannot verify audit ledger at " + storageDir + ": " + e.getMessage());
            return 1;
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
