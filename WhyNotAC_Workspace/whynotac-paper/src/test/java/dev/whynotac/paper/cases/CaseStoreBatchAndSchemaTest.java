package dev.whynotac.paper.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CaseStoreBatchAndSchemaTest {

    @Test
    void commits1000ConcurrentCaseWritesInWalBatchesWithZeroDrops(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("cases.db");
        Path dossiers = dir.resolve("cases");
        int totalCases = 1_000;
        int threads = 8;
        int perThread = totalCases / threads;
        UUID player = UUID.randomUUID();

        try (CaseStore store = new CaseStore(dbFile, dossiers, s -> java.util.Optional.empty(),
                c -> java.util.Optional.empty(), "test-23", Logger.getLogger("batch-test"), "", "", "")) {
            assertTrue(store.isSqlite());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            long baseTs = System.currentTimeMillis();
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            String id = String.format("batch-%02d-%04d", tid, i);
                            CaseStore.StoredCase row = new CaseStore.StoredCase(
                                    id, baseTs + tid * 1000L + i, "movement-simulation", 1, "P-A", "SHADOW",
                                    "Player", player.toString(), "AUTHENTICATED", i, i + 1, "summary-" + i,
                                    "fp-" + id, null, dossiers.resolve(id + ".json").toString());
                            store.add(row, "{\"id\":\"" + id + "\",\"playerId\":\"" + player + "\"}");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            // Read-your-own-writes flush guarantees every queued write is committed before returning
            CompletableFuture<List<CaseStore.StoredCase>> recent = new CompletableFuture<>();
            store.recent(totalCases + 10, recent::complete);
            List<CaseStore.StoredCase> rows = recent.get(30, TimeUnit.SECONDS);

            assertEquals(totalCases, rows.size(), "All 1,000 concurrent cases must be persisted");
            assertEquals(totalCases, store.storedCount());
            assertEquals(0L, store.droppedCount());
            assertEquals(totalCases, store.casesBatched());
            assertTrue(store.batchesCommitted() > 0 && store.batchesCommitted() < totalCases,
                    "Writes must be grouped into transactions (batchesCommitted=" + store.batchesCommitted() + ")");
        }

        // Verify SQLite journal mode is WAL and schema indexes exist
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase());
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cases")) {
                assertTrue(rs.next());
                assertEquals(totalCases, rs.getInt(1));
            }
        }
    }

    @Test
    void duplicateKeyInsideBatchFallsBackWithoutDroppingValidSiblings(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("dup.db");
        Path dossiers = dir.resolve("cases-dup");
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        try (CaseStore store = new CaseStore(dbFile, dossiers, s -> java.util.Optional.empty(), "test",
                Logger.getLogger("dup-test"))) {
            CaseStore.StoredCase dup = new CaseStore.StoredCase(
                    "same-id", now, "attack-reach", 1, "P-A", "SHADOW",
                    "P", player.toString(), "AUTHENTICATED", 1, 2, "s", "fp", null,
                    dossiers.resolve("same-id.json").toString());
            CaseStore.StoredCase ok = new CaseStore.StoredCase(
                    "valid-sibling", now + 1, "attack-reach", 1, "P-A", "SHADOW",
                    "P", player.toString(), "AUTHENTICATED", 2, 3, "s", "fp2", null,
                    dossiers.resolve("valid-sibling.json").toString());
            store.add(dup, "{\"id\":\"same-id\"}");
            store.add(dup, "{\"id\":\"same-id\"}"); // duplicate PK in same batch
            store.add(ok, "{\"id\":\"valid-sibling\"}");

            CompletableFuture<List<CaseStore.StoredCase>> recent = new CompletableFuture<>();
            store.recent(10, recent::complete);
            List<CaseStore.StoredCase> rows = recent.get(10, TimeUnit.SECONDS);
            assertEquals(2, rows.size(), "Valid sibling must survive duplicate-key batch fallback");
        }
    }
}
