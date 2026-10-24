package dev.whynotac.paper.cases;

import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.verdict.ShadowVerdict;
import dev.whynotac.protocol.session.PlayerSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Cases: one row per finding in an embedded SQLite database ({@code plugins/WhyNotAC/cases.db}) and
 * one dossier per case ({@code plugins/WhyNotAC/cases/<id>.json}) that an independent tool can
 * re-verify against the session's recording ({@code java -jar WhyNotAC.jar verify <dossier>}).
 *
 * <p>Everything happens on one writer thread with a bounded queue: the analysis thread hands a case
 * over and returns at once; a full queue drops the case (counted, logged), never blocks.
 *
 * <p>A case names its player (name and UUID): staff review it. {@link #subject} finds and deletes one
 * player's cases for a data-subject request, {@link #retain} deletes old ones.
 */
public final class CaseStore implements ShadowVerdict.CaseListener, AutoCloseable {

    /** A case as stored. */
    public record StoredCase(String id, long createdEpochMillis, String check, int checkVersion, String evidenceClass, String lifecycle,
            String playerName, String playerId, String identity, long fromSequence, long toSequence, String summary,
            String fingerprint, String recording, String dossier) {}

    /** One player's cases: the case ids and dossier files found, and what was deleted. */
    public record Subject(List<String> cases, List<String> dossiers, long deletedRows, long deletedDossiers, List<String> failures) {}

    static final int MAX_QUEUED = 10_000;

    private final Path database;
    private final Path dossiers;
    private final Function<PlayerSession, Optional<Path>> recordingOf;
    private final String whynotacVersion;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WhyNotAC Cases");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger queued = new AtomicInteger();
    int retentionBatch = 10_000; // rows per retention round; tests use a small one
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong stored = new AtomicLong();
    private Connection connection; // writer thread only
    private boolean databaseFailed; // writer thread only

    public CaseStore(Path database, Path dossiers, Function<PlayerSession, Optional<Path>> recordingOf, String whynotacVersion, Logger logger) {
        this.database = database;
        this.dossiers = dossiers;
        this.recordingOf = recordingOf;
        this.whynotacVersion = whynotacVersion;
        this.logger = logger;
    }

    @Override
    public void opened(ShadowVerdict.Case c) {
        // Read everything that belongs to the live session now, on its analysis thread.
        PlayerSession.Identity player = c.session().identity();
        Optional<Path> recording = recordingOf.apply(c.session());
        Evidence e = c.evidence();
        StoredCase row = new StoredCase(c.id(), c.createdEpochMillis(), e.check(), e.checkVersion(), e.evidenceClass().code(),
                c.lifecycle().name(), player == null ? null : player.name(), player == null ? null : player.playerId().toString(),
                e.admissibility().identity().name(), e.fromSequence(), e.toSequence(), e.summary(), e.fingerprint(),
                recording.map(r -> r.toAbsolutePath().normalize().toString()).orElse(null), dossiers.resolve(c.id() + ".json").toString());
        String dossier = dossierJson(row, c, e);
        if (queued.incrementAndGet() > MAX_QUEUED) {
            queued.decrementAndGet();
            long n = dropped.incrementAndGet();
            if (n <= 10 || Long.bitCount(n) == 1) {
                logger.warning("WhyNotAC case queue full; case " + c.id() + " was not stored (dropped #" + n + ")");
            }
            return;
        }
        writer.execute(() -> {
            try {
                write(row, dossier);
            } finally {
                queued.decrementAndGet();
            }
        });
    }

    /** Stores a case row and its dossier as {@link #opened} does (tests). */
    void add(StoredCase row, String dossier) {
        writer.execute(() -> write(row, dossier));
    }

    private void write(StoredCase row, String dossier) {
        try {
            Files.createDirectories(dossiers);
            Files.writeString(Path.of(row.dossier()), dossier, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "WhyNotAC could not write the dossier of case " + row.id(), e);
        }
        Connection db = connection();
        if (db == null) {
            return;
        }
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO cases (id, created_at, check_id, check_version, evidence_class, "
                + "lifecycle, player_name, player_id, identity, from_seq, to_seq, summary, fingerprint, recording, dossier) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, row.id());
            insert.setLong(2, row.createdEpochMillis());
            insert.setString(3, row.check());
            insert.setInt(4, row.checkVersion());
            insert.setString(5, row.evidenceClass());
            insert.setString(6, row.lifecycle());
            insert.setString(7, row.playerName());
            insert.setString(8, row.playerId());
            insert.setString(9, row.identity());
            insert.setLong(10, row.fromSequence());
            insert.setLong(11, row.toSequence());
            insert.setString(12, row.summary());
            insert.setString(13, row.fingerprint());
            insert.setString(14, row.recording());
            insert.setString(15, row.dossier());
            insert.executeUpdate();
            stored.incrementAndGet();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "WhyNotAC could not store case " + row.id() + " (the dossier file was written)", e);
        }
    }

    /** The most recent cases, newest first; {@code callback} runs on the writer thread. */
    public void recent(int limit, Consumer<List<StoredCase>> callback) {
        writer.execute(() -> callback.accept(query("SELECT * FROM cases ORDER BY created_at DESC LIMIT ?", limit, null)));
    }

    /** The case whose id starts with {@code prefix} (at least 4 characters), if exactly one does. */
    public void find(String prefix, Consumer<Optional<StoredCase>> callback) {
        writer.execute(() -> {
            List<StoredCase> found = prefix.length() < 4 ? List.of() : query("SELECT * FROM cases WHERE id LIKE ? LIMIT 2", 2, prefix + "%");
            callback.accept(found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty());
        });
    }

    /**
     * Deletes cases (rows and dossiers) older than {@code days}: privacy retention. Rows go in batches until
     * none is left; dossier files older than the cutoff go too, also those without a row (written while
     * the database was unavailable).
     */
    public void retain(int days) {
        writer.execute(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
            Connection db = connection();
            if (db != null) {
                List<StoredCase> old;
                do {
                    old = query("SELECT * FROM cases WHERE created_at < ? ORDER BY created_at LIMIT " + retentionBatch, -1, Long.toString(cutoff));
                    for (StoredCase c : old) {
                        deleteDossier(Path.of(c.dossier()), null);
                    }
                } while (deleteRows(db, old) && old.size() == retentionBatch);
            }
            for (Path file : dossierFiles()) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        deleteDossier(file, null);
                    }
                } catch (IOException e) {
                    logger.log(Level.WARNING, "WhyNotAC could not check dossier " + file, e);
                }
            }
        });
    }

    /**
     * A data-subject request: the cases of the player {@code playerId}, found by their rows and by the
     * {@code playerId} of every dossier file (a dossier is written even when its row is not); with
     * {@code delete}, rows and dossiers are deleted. {@code callback} runs on the writer thread.
     */
    public void subject(UUID playerId, boolean delete, Consumer<Subject> callback) {
        writer.execute(() -> {
            String id = playerId.toString();
            java.util.Set<String> ids = new java.util.TreeSet<>();
            java.util.Set<Path> files = new java.util.TreeSet<>();
            List<String> failures = new ArrayList<>();
            Connection db = connection();
            List<StoredCase> rows = db == null ? List.of() : query("SELECT * FROM cases WHERE player_id = ?", -1, id);
            for (StoredCase c : rows) {
                ids.add(c.id());
                if (Files.exists(Path.of(c.dossier()))) {
                    files.add(Path.of(c.dossier()));
                }
            }
            String field = "\"playerId\": \"" + id + "\"";
            for (Path file : dossierFiles()) {
                try {
                    if (Files.readString(file, StandardCharsets.UTF_8).contains(field)) {
                        files.add(file);
                        String name = file.getFileName().toString();
                        ids.add(name.substring(0, name.length() - ".json".length()));
                    }
                } catch (IOException e) {
                    failures.add(file.toString());
                }
            }
            long deletedRows = 0;
            long deletedDossiers = 0;
            if (delete) {
                for (Path file : files) {
                    if (deleteDossier(file, failures)) {
                        deletedDossiers++;
                    }
                }
                if (db != null) {
                    try (PreparedStatement statement = db.prepareStatement("DELETE FROM cases WHERE player_id = ?")) {
                        statement.setString(1, id);
                        deletedRows = statement.executeUpdate();
                    } catch (SQLException e) {
                        logger.log(Level.WARNING, "WhyNotAC could not delete the cases of a player", e);
                        failures.add(database.toString());
                    }
                }
            }
            if (db == null && Files.exists(database)) {
                failures.add(database.toString());
            }
            callback.accept(new Subject(List.copyOf(ids), files.stream().map(Path::toString).toList(), deletedRows, deletedDossiers,
                    List.copyOf(failures)));
        });
    }

    private boolean deleteRows(Connection db, List<StoredCase> rows) {
        if (rows.isEmpty()) {
            return false;
        }
        try (PreparedStatement delete = db.prepareStatement("DELETE FROM cases WHERE id = ?")) {
            for (StoredCase c : rows) {
                delete.setString(1, c.id());
                delete.addBatch();
            }
            delete.executeBatch();
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "WhyNotAC case retention failed", e);
            return false;
        }
    }

    private boolean deleteDossier(Path file, List<String> failures) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.log(Level.WARNING, "WhyNotAC could not delete dossier " + file, e);
            if (failures != null) {
                failures.add(file.toString());
            }
            return false;
        }
    }

    private List<Path> dossierFiles() {
        if (!Files.isDirectory(dossiers)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> list = Files.list(dossiers)) {
            return list.filter(f -> f.getFileName().toString().endsWith(".json") && Files.isRegularFile(f)).sorted().toList();
        } catch (IOException e) {
            logger.log(Level.WARNING, "WhyNotAC could not list the dossiers in " + dossiers, e);
            return List.of();
        }
    }

    public long storedCount() {
        return stored.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    private List<StoredCase> query(String sql, int limit, String parameter) {
        Connection db = connection();
        List<StoredCase> out = new ArrayList<>();
        if (db == null) {
            return out;
        }
        try (PreparedStatement select = db.prepareStatement(sql)) {
            if (parameter != null && sql.contains("created_at <")) {
                select.setLong(1, Long.parseLong(parameter));
            } else if (parameter != null) {
                select.setString(1, parameter);
            } else {
                select.setInt(1, limit);
            }
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredCase(rs.getString("id"), rs.getLong("created_at"), rs.getString("check_id"), rs.getInt("check_version"),
                            rs.getString("evidence_class"), rs.getString("lifecycle"), rs.getString("player_name"), rs.getString("player_id"),
                            rs.getString("identity"), rs.getLong("from_seq"), rs.getLong("to_seq"), rs.getString("summary"),
                            rs.getString("fingerprint"), rs.getString("recording"), rs.getString("dossier")));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "WhyNotAC case query failed", e);
        }
        return out;
    }

    /** Opens the database on first use (writer thread). A failure leaves dossier files as the only record. */
    private Connection connection() {
        if (connection != null || databaseFailed) {
            return connection;
        }
        try {
            Class.forName("org.sqlite.JDBC"); // bundled with Paper
            Files.createDirectories(database.getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            try (Statement s = connection.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS cases (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, check_id TEXT NOT NULL, "
                        + "check_version INTEGER NOT NULL, evidence_class TEXT NOT NULL, lifecycle TEXT NOT NULL, player_name TEXT, player_id TEXT, "
                        + "identity TEXT NOT NULL, from_seq INTEGER NOT NULL, to_seq INTEGER NOT NULL, summary TEXT NOT NULL, "
                        + "fingerprint TEXT NOT NULL, recording TEXT, dossier TEXT NOT NULL)");
                s.executeUpdate("CREATE INDEX IF NOT EXISTS cases_created ON cases (created_at)");
            }
        } catch (ClassNotFoundException | SQLException | IOException e) {
            databaseFailed = true;
            logger.log(Level.WARNING, "WhyNotAC case database unavailable (" + database + "); cases are kept as dossier files only", e);
        }
        return connection;
    }

    private String dossierJson(StoredCase row, ShadowVerdict.Case c, Evidence e) {
        StringBuilder json = new StringBuilder("{\n");
        field(json, "id", row.id());
        field(json, "created", Instant.ofEpochMilli(row.createdEpochMillis()).toString());
        field(json, "check", row.check());
        json.append("  \"checkVersion\": ").append(row.checkVersion()).append(",\n");
        field(json, "evidenceClass", row.evidenceClass());
        field(json, "lifecycle", row.lifecycle());
        json.append("  \"permittedActions\": [").append(c.permittedActions().stream().map(Action::name).map(a -> "\"" + a + "\"")
                .collect(Collectors.joining(", "))).append("],\n");
        field(json, "playerName", row.playerName());
        field(json, "playerId", row.playerId());
        field(json, "identity", row.identity());
        json.append("  \"fromSequence\": ").append(row.fromSequence()).append(",\n");
        json.append("  \"toSequence\": ").append(row.toSequence()).append(",\n");
        field(json, "summary", row.summary());
        json.append("  \"details\": {");
        boolean first = true;
        for (Map.Entry<String, String> d : e.details().entrySet()) {
            json.append(first ? "" : ", ").append('"').append(escape(d.getKey())).append("\": \"").append(escape(d.getValue())).append('"');
            first = false;
        }
        json.append("},\n");
        json.append("  \"admissible\": ").append(e.admissibility().admissible()).append(",\n");
        field(json, "fingerprint", row.fingerprint());
        // Relative to the dossier, so a copied plugin folder still verifies; the absolute path as a fallback.
        field(json, "recording", row.recording() == null ? null
                : dossiers.toAbsolutePath().normalize().relativize(Path.of(row.recording())).toString());
        field(json, "recordingAbsolute", row.recording());
        json.append("  \"whynotacVersion\": \"").append(escape(whynotacVersion)).append("\"\n}\n");
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append("  \"").append(name).append("\": ").append(value == null ? "null" : "\"" + escape(value) + "\"").append(",\n");
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : String.valueOf(ch));
            }
        }
        return out.toString();
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warning("WhyNotAC case writer did not finish within 5 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.log(Level.FINE, "closing the case database", e);
        }
    }
}
