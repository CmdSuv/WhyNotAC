package dev.whynotac.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLedgerTest {

    @TempDir
    Path tempDir;

    @Test
    void hashChainedEntriesReloadAcrossRestartsAndDetectAnyTampering() throws Exception {
        Path ledgerFile = tempDir.resolve("audit-ledger.jsonl");
        Path keyFile = tempDir.resolve("audit-secret.key");
        UUID player = UUID.randomUUID();

        try (AuditLedger ledger = new AuditLedger(ledgerFile, keyFile, "0.1.0-p774")) {
            AuditLedger.LedgerEntry e0 = ledger.append(AuditLedger.EntryKind.CASE_OPENED, 1000L, "case-1", "cert-a", player, "reach");
            AuditLedger.LedgerEntry e1 = ledger.append(AuditLedger.EntryKind.TEMP_BAN_ISSUED, 2000L, "case-1", "cert-a", player, "verified");
            assertEquals(0L, e0.sequence());
            assertEquals(AuditLedger.GENESIS_HASH, e0.prevHash());
            assertEquals(1L, e1.sequence());
            assertEquals(e0.entryHash(), e1.prevHash());
            assertNotEquals(player.toString(), e0.subjectToken());
            assertFalse(Files.readString(ledgerFile, StandardCharsets.UTF_8).contains(player.toString()));
        }

        // Re-open with same secret key -> verifies existing chain cleanly and appends seq=2
        try (AuditLedger reopened = new AuditLedger(ledgerFile, keyFile, "0.1.0-p774")) {
            AuditLedger.ChainVerification ok = reopened.verify();
            assertTrue(ok.valid());
            assertEquals(2L, ok.entryCount());
            AuditLedger.LedgerEntry e2 = reopened.append(AuditLedger.EntryKind.SUBJECT_FORGOTTEN, 3000L, "", "", null, "gdpr");
            assertEquals(2L, e2.sequence());
            assertTrue(reopened.verify().valid());
        }

        // Tamper a single character inside line 1 summary -> verify() AND startup initialization fail!
        List<String> lines = Files.readAllLines(ledgerFile, StandardCharsets.UTF_8);
        lines.set(0, lines.get(0).replace("\"summary\":\"reach\"", "\"summary\":\"other\""));
        Files.write(ledgerFile, lines, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new AuditLedger(ledgerFile, keyFile, "0.1.0-p774"));
    }

    @Test
    void storageManagerRestoresFalseBanBudgetAndErasesSubjectPrivacyCleanly() throws Exception {
        UUID player = UUID.randomUUID();
        try (StorageManager sm = new StorageManager(tempDir.resolve("store"), "0.1.0-p774", Logger.getLogger("test"))) {
            sm.profiles().recordCase(player, "attack-reach", 5000L);
            sm.profiles().recordFalseBanIncident(player, 6000L);
            assertTrue(sm.profiles().find(player).isPresent());
            assertEquals(1, sm.profiles().loadRecentIncidents(4000L).size());

            assertTrue(sm.forgetSubject(player, 7000L));
            assertTrue(sm.profiles().find(player).isEmpty());
            assertTrue(sm.ledger().verify().valid());
        }
    }
}
