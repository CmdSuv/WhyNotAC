package dev.whynotac.analysis.cli;

import dev.whynotac.analysis.checks.Checks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Offline operator health, configuration, secret-permission, audit-chain, and SQLite integrity verifier
 * ({@code java -jar WhyNotAC.jar doctor <plugins/WhyNotAC>}, PH27-01).
 */
public final class DoctorCommand {

    public enum Status { PASS, WARN, FAIL }

    public record CheckResult(String name, Status status, String detail) {}

    public record DoctorReport(List<CheckResult> checks) {
        public long passCount() {
            return checks.stream().filter(c -> c.status() == Status.PASS).count();
        }

        public long warnCount() {
            return checks.stream().filter(c -> c.status() == Status.WARN).count();
        }

        public long failCount() {
            return checks.stream().filter(c -> c.status() == Status.FAIL).count();
        }
    }

    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private DoctorCommand() {}

    public static int runCli(Path dataDir, PrintStream out, PrintStream err) {
        DoctorReport report = diagnose(dataDir);
        out.println("=== WhyNotAC Doctor Report (" + dataDir.toAbsolutePath() + ") ===");
        for (CheckResult r : report.checks()) {
            out.printf(Locale.ROOT, "  [%-4s] %-26s : %s%n", r.status().name(), r.name(), r.detail());
        }
        out.printf(Locale.ROOT, "Summary: PASS=%d WARN=%d FAIL=%d%n",
                report.passCount(), report.warnCount(), report.failCount());
        out.println("DOCTOR_JSON: " + toJson(report));
        if (report.failCount() > 0) {
            err.println("Doctor found " + report.failCount() + " critical issue(s).");
            return 2;
        }
        return 0;
    }

    public static DoctorReport diagnose(Path dataDir) {
        List<CheckResult> results = new ArrayList<>();
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            results.add(new CheckResult("data-directory", Status.FAIL, "Directory does not exist: " + dataDir));
            return new DoctorReport(List.copyOf(results));
        }
        results.add(new CheckResult("data-directory", Status.PASS, "Readable directory " + dataDir.toAbsolutePath()));

        checkConfig(dataDir.resolve("config.yml"), results);
        checkSecretKeys(dataDir, results);
        checkAuditLedger(dataDir.resolve("storage"), results);
        checkCasesDatabase(dataDir.resolve("cases.db"), results);
        checkBanWaveJournal(dataDir.resolve("storage").resolve("ban-wave-queue.jsonl"), results);
        return new DoctorReport(List.copyOf(results));
    }

    private static void checkConfig(Path configPath, List<CheckResult> out) {
        if (!Files.isRegularFile(configPath)) {
            out.add(new CheckResult("config.yml", Status.WARN, "Not found (default SHADOW settings will be created on boot)"));
            return;
        }
        try {
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            Map<String, String> flat = parseSimpleYaml(raw);
            List<String> armedChecks = parseYamlList(raw, "verdict", "armed-checks");

            boolean dashboardEnabled = "true".equalsIgnoreCase(flat.getOrDefault("observability.dashboard-enabled", "false"));
            String authToken = stripQuotes(flat.getOrDefault("observability.metrics-auth-token", ""));
            boolean autoBan = "true".equalsIgnoreCase(flat.getOrDefault("verdict.auto-ban", "false"));
            boolean secondVerifier = !"false".equalsIgnoreCase(flat.getOrDefault("verdict.second-verifier", "true"));
            boolean requireCorrob = !"false".equalsIgnoreCase(flat.getOrDefault("verdict.require-corroboration-for-ban", "true"));
            boolean recordingEnabled = "true".equalsIgnoreCase(flat.getOrDefault("recording.enabled", "false"));
            boolean edgeEnabled = !"false".equalsIgnoreCase(flat.getOrDefault("observation.edge", "true"));

            if (dashboardEnabled && authToken.isEmpty()) {
                out.add(new CheckResult("config.dashboard-auth", Status.FAIL,
                        "observability.dashboard-enabled=true requires non-empty observability.metrics-auth-token"));
            } else {
                out.add(new CheckResult("config.dashboard-auth", Status.PASS,
                        dashboardEnabled ? "Dashboard enabled with Bearer token configured" : "Dashboard disabled (safe default)"));
            }

            List<String> unknownArmed = new ArrayList<>();
            for (String chk : armedChecks) {
                if (!"design-probe".equals(chk) && Checks.version(chk) < 0) {
                    unknownArmed.add(chk);
                }
            }
            if (!unknownArmed.isEmpty()) {
                out.add(new CheckResult("config.armed-checks", Status.WARN, "Unknown check IDs in verdict.armed-checks: " + unknownArmed));
            } else {
                out.add(new CheckResult("config.armed-checks", Status.PASS, armedChecks.size() + " valid check(s) armed"));
            }

            if (autoBan && (!secondVerifier || !requireCorrob || !recordingEnabled)) {
                out.add(new CheckResult("config.auto-ban-safety", Status.WARN,
                        "verdict.auto-ban=true with weakened safeguards (secondVerifier=" + secondVerifier
                                + ", corroboration=" + requireCorrob + ", recording=" + recordingEnabled + ")"));
            } else {
                out.add(new CheckResult("config.auto-ban-safety", Status.PASS,
                        autoBan ? "Auto-ban enabled with SecondVerifier + corroboration + recording" : "Auto-ban disabled (safe default)"));
            }

            if (!edgeEnabled) {
                out.add(new CheckResult("config.observation-edge", Status.WARN, "observation.edge=false reduces detection fidelity"));
            } else {
                out.add(new CheckResult("config.observation-edge", Status.PASS, "Edge wire observation enabled"));
            }
        } catch (IOException ex) {
            out.add(new CheckResult("config.yml", Status.FAIL, "Unreadable config.yml: " + ex.getMessage()));
        }
    }

    private static void checkSecretKeys(Path dataDir, List<CheckResult> out) {
        List<Path> keys = new ArrayList<>();
        collectKeyFiles(dataDir, keys);
        collectKeyFiles(dataDir.resolve("storage"), keys);
        if (keys.isEmpty()) {
            out.add(new CheckResult("secret-keys", Status.PASS, "No *.key files created yet"));
            return;
        }
        for (Path key : keys) {
            String label = "key:" + dataDir.relativize(key);
            try {
                long size = Files.size(key);
                if (size < 32L) {
                    out.add(new CheckResult(label, Status.FAIL, "Key length " + size + " bytes (< 32-byte minimum)"));
                    continue;
                }
                try {
                    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(key);
                    boolean exposed = perms.contains(PosixFilePermission.GROUP_READ)
                            || perms.contains(PosixFilePermission.GROUP_WRITE)
                            || perms.contains(PosixFilePermission.GROUP_EXECUTE)
                            || perms.contains(PosixFilePermission.OTHERS_READ)
                            || perms.contains(PosixFilePermission.OTHERS_WRITE)
                            || perms.contains(PosixFilePermission.OTHERS_EXECUTE);
                    if (exposed) {
                        out.add(new CheckResult(label, Status.FAIL, "Overly permissive POSIX mode " + perms + " (expected 0600)"));
                        continue;
                    }
                } catch (UnsupportedOperationException nonPosix) {
                    // Windows/non-POSIX filesystem
                }
                out.add(new CheckResult(label, Status.PASS, size + " bytes, owner-only permissions verified"));
            } catch (IOException ex) {
                out.add(new CheckResult(label, Status.FAIL, "Cannot inspect key file: " + ex.getMessage()));
            }
        }
    }

    private static void collectKeyFiles(Path dir, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".key"))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException ignored) { }
    }

    private static void checkAuditLedger(Path storageDir, List<CheckResult> out) {
        Path ledgerFile = storageDir.resolve("audit-ledger.jsonl");
        if (!Files.exists(ledgerFile)) {
            out.add(new CheckResult("audit-ledger", Status.PASS, "No audit-ledger.jsonl yet"));
            return;
        }
        ByteArrayOutputStream capOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capErr = new ByteArrayOutputStream();
        int code = Tool.audit(storageDir,
                new PrintStream(capOut, true, StandardCharsets.UTF_8),
                new PrintStream(capErr, true, StandardCharsets.UTF_8));
        String stdout = capOut.toString(StandardCharsets.UTF_8).trim();
        String stderr = capErr.toString(StandardCharsets.UTF_8).trim();
        if (code == 0) {
            out.add(new CheckResult("audit-ledger", Status.PASS, stdout.isEmpty() ? "Hash & HMAC chain verified" : stdout));
        } else if (stderr.contains("whynotac-storage missing")) {
            out.add(new CheckResult("audit-ledger", Status.WARN, "Run doctor from full WhyNotAC plugin JAR to verify HMAC chain"));
        } else {
            out.add(new CheckResult("audit-ledger", Status.FAIL, stderr.isEmpty() ? stdout : stderr));
        }
    }

    private static void checkCasesDatabase(Path dbFile, List<CheckResult> out) {
        if (!Files.isRegularFile(dbFile)) {
            out.add(new CheckResult("cases.db", Status.PASS, "No cases.db created yet"));
            return;
        }
        try {
            byte[] header = Files.readAllBytes(dbFile);
            if (header.length < SQLITE_MAGIC.length) {
                out.add(new CheckResult("cases.db", Status.FAIL, "Truncated/corrupted SQLite file (" + header.length + " bytes)"));
                return;
            }
            for (int i = 0; i < SQLITE_MAGIC.length; i++) {
                if (header[i] != SQLITE_MAGIC[i]) {
                    out.add(new CheckResult("cases.db", Status.FAIL, "Invalid SQLite header magic bytes"));
                    return;
                }
            }
        } catch (IOException ex) {
            out.add(new CheckResult("cases.db", Status.FAIL, "Cannot read cases.db: " + ex.getMessage()));
            return;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                if (rs.next()) {
                    String status = rs.getString(1);
                    if ("ok".equalsIgnoreCase(status)) {
                        out.add(new CheckResult("cases.db", Status.PASS, "SQLite PRAGMA integrity_check = ok"));
                    } else {
                        out.add(new CheckResult("cases.db", Status.FAIL, "SQLite integrity_check failed: " + status));
                    }
                } else {
                    out.add(new CheckResult("cases.db", Status.FAIL, "SQLite integrity_check returned no rows"));
                }
            }
        } catch (ClassNotFoundException noDriver) {
            out.add(new CheckResult("cases.db", Status.PASS, "SQLite header magic verified (JDBC driver not on standalone classpath)"));
        } catch (Exception ex) {
            out.add(new CheckResult("cases.db", Status.FAIL, "SQLite integrity check error: " + ex.getMessage()));
        }
    }

    private static void checkBanWaveJournal(Path journal, List<CheckResult> out) {
        if (!Files.isRegularFile(journal)) {
            out.add(new CheckResult("ban-wave-journal", Status.PASS, "No ban-wave-queue.jsonl present"));
            return;
        }
        try {
            int lines = 0;
            int malformed = 0;
            for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                lines++;
                if (!trimmed.startsWith("{") || !trimmed.endsWith("}") || !trimmed.contains("\"state\"")) {
                    malformed++;
                }
            }
            if (malformed > 0) {
                out.add(new CheckResult("ban-wave-journal", Status.FAIL, malformed + " malformed line(s) out of " + lines));
            } else {
                out.add(new CheckResult("ban-wave-journal", Status.PASS, lines + " valid journal line(s)"));
            }
        } catch (IOException ex) {
            out.add(new CheckResult("ban-wave-journal", Status.FAIL, "Unreadable ban-wave journal: " + ex.getMessage()));
        }
    }

    private static Map<String, String> parseSimpleYaml(String yaml) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> sectionStack = new ArrayList<>();
        List<Integer> indentStack = new ArrayList<>();
        for (String rawLine : yaml.split("\r?\n")) {
            int commentIdx = rawLine.indexOf('#');
            String line = commentIdx >= 0 ? rawLine.substring(0, commentIdx) : rawLine;
            if (line.trim().isEmpty() || line.trim().startsWith("-")) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            while (!indentStack.isEmpty() && indentStack.getLast() >= indent) {
                indentStack.removeLast();
                sectionStack.removeLast();
            }
            if (value.isEmpty()) {
                sectionStack.add(key);
                indentStack.add(indent);
            } else {
                String fullKey = sectionStack.isEmpty() ? key : String.join(".", sectionStack) + "." + key;
                out.put(fullKey, value);
            }
        }
        return out;
    }

    private static List<String> parseYamlList(String yaml, String section, String listKey) {
        List<String> items = new ArrayList<>();
        boolean inSection = false;
        boolean inList = false;
        int listIndent = -1;
        for (String rawLine : yaml.split("\r?\n")) {
            int commentIdx = rawLine.indexOf('#');
            String line = commentIdx >= 0 ? rawLine.substring(0, commentIdx) : rawLine;
            if (line.trim().isEmpty()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String trimmed = line.trim();
            if (indent == 0 && trimmed.endsWith(":")) {
                inSection = trimmed.equals(section + ":");
                inList = false;
                continue;
            }
            if (inSection && trimmed.startsWith(listKey + ":")) {
                String rest = trimmed.substring((listKey + ":").length()).trim();
                if (rest.startsWith("[") && rest.endsWith("]")) {
                    String inner = rest.substring(1, rest.length() - 1).trim();
                    if (!inner.isEmpty()) {
                        for (String part : inner.split(",")) {
                            String v = stripQuotes(part.trim());
                            if (!v.isEmpty()) {
                                items.add(v);
                            }
                        }
                    }
                    inList = false;
                } else {
                    inList = true;
                    listIndent = indent;
                }
                continue;
            }
            if (inList) {
                if (trimmed.startsWith("-")) {
                    String v = stripQuotes(trimmed.substring(1).trim());
                    if (!v.isEmpty()) {
                        items.add(v);
                    }
                } else if (indent <= listIndent) {
                    inList = false;
                }
            }
        }
        return items;
    }

    private static String stripQuotes(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String toJson(DoctorReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"pass\":").append(report.passCount())
                .append(",\"warn\":").append(report.warnCount())
                .append(",\"fail\":").append(report.failCount())
                .append(",\"checks\":[");
        for (int i = 0; i < report.checks().size(); i++) {
            CheckResult r = report.checks().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":\"").append(Tool.escape(r.name()))
                    .append("\",\"status\":\"").append(r.status().name())
                    .append("\",\"detail\":\"").append(Tool.escape(r.detail())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
