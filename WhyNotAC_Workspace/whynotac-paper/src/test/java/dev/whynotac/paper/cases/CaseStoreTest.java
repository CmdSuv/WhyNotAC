package dev.whynotac.paper.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaseStoreTest {

    @TempDir
    Path dir;

    private CaseStore.StoredCase row(String id, long created, UUID player, String name) {
        return new CaseStore.StoredCase(id, created, "attack-reach", 1, "P-A", "SHADOW", name, player.toString(), "VERIFIED", 1, 2, "summary",
                "fp", null, dir.resolve("cases").resolve(id + ".json").toString());
    }

    private static String dossier(String id, UUID player, String name) {
        return "{\n  \"id\": \"" + id + "\",\n  \"playerName\": \"" + name + "\",\n  \"playerId\": \"" + player + "\",\n}\n";
    }

    private static CaseStore.Subject subject(CaseStore store, UUID player, boolean delete) throws Exception {
        CompletableFuture<CaseStore.Subject> result = new CompletableFuture<>();
        store.subject(player, delete, result::complete);
        return result.get(10, TimeUnit.SECONDS);
    }

    private static List<CaseStore.StoredCase> recent(CaseStore store) throws Exception {
        CompletableFuture<List<CaseStore.StoredCase>> result = new CompletableFuture<>();
        store.recent(100, result::complete);
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aSubjectRequestFindsAndDeletesExactlyOnePlayersCasesAndDossiers() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (CaseStore store = new CaseStore(dir.resolve("cases.db"), dir.resolve("cases"), s -> java.util.Optional.empty(), "test",
                Logger.getLogger("test"))) {
            store.add(row("a1", now, alice, "alice"), dossier("a1", alice, "alice"));
            store.add(row("a2", now, alice, "alice"), dossier("a2", alice, "alice"));
            store.add(row("b1", now, bob, "bob"), dossier("b1", bob, "bob"));
            // a dossier whose row was never stored (the database was unavailable then)
            Files.createDirectories(dir.resolve("cases"));
            Files.writeString(dir.resolve("cases").resolve("a3.json"), dossier("a3", alice, "alice"));

            CaseStore.Subject found = subject(store, alice, false);
            assertEquals(List.of("a1", "a2", "a3"), found.cases());
            assertEquals(3, found.dossiers().size());
            assertEquals(0, found.deletedRows() + found.deletedDossiers(), "a dry run deletes nothing");
            assertEquals(4, Files.list(dir.resolve("cases")).count());

            CaseStore.Subject forgotten = subject(store, alice, true);
            assertEquals(2, forgotten.deletedRows());
            assertEquals(3, forgotten.deletedDossiers());
            assertTrue(forgotten.failures().isEmpty(), forgotten.failures().toString());
            assertEquals(List.of("b1"), recent(store).stream().map(CaseStore.StoredCase::id).toList());
            assertEquals(List.of(dir.resolve("cases").resolve("b1.json")), Files.list(dir.resolve("cases")).toList());
            assertTrue(subject(store, alice, false).cases().isEmpty());
            assertEquals(List.of("b1"), subject(store, bob, false).cases());
        }
    }

    @Test
    void retentionDeletesEveryOldCaseInBatchesAndOldDossiersWithoutARow() throws Exception {
        UUID player = UUID.randomUUID();
        long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10);
        int many = 3 * 4 + 1;
        try (CaseStore store = new CaseStore(dir.resolve("cases.db"), dir.resolve("cases"), s -> java.util.Optional.empty(), "test",
                Logger.getLogger("test"))) {
            store.retentionBatch = 4;
            for (int i = 0; i < many; i++) {
                store.add(row("old" + i, old, player, "p"), dossier("old" + i, player, "p"));
            }
            store.add(row("new", System.currentTimeMillis(), player, "p"), dossier("new", player, "p"));
            assertEquals(many + 1, subject(store, player, false).cases().size());
            Files.createDirectories(dir.resolve("cases"));
            Path orphan = Files.writeString(dir.resolve("cases").resolve("orphan.json"), dossier("orphan", player, "p"));
            Files.setLastModifiedTime(orphan, FileTime.fromMillis(old));
            for (int i = 0; i < many; i++) {
                Files.setLastModifiedTime(dir.resolve("cases").resolve("old" + i + ".json"), FileTime.fromMillis(old));
            }
            store.retain(7);
            CaseStore.Subject left = subject(store, player, false);
            assertEquals(List.of("new"), left.cases());
            assertFalse(Files.exists(orphan));
            assertEquals(List.of(dir.resolve("cases").resolve("new.json")), Files.list(dir.resolve("cases")).toList());
        }
    }
}
