package dev.whynotac.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atomic, restart-safe persistent profile store indexed by player UUID (STO-03), supporting GDPR access and
 * erasure requests ({@link #find(UUID)}, {@link #forget(UUID)}).
 */
public final class SubjectProfileStore {

    public record FeatureSummary(long count, double mean, double sampleVariance) {}

    public record SubjectProfile(
            UUID playerId,
            long lastSeenEpochMillis,
            long totalCases,
            long autoBansIssued,
            long overturnedCount,
            Map<String, Long> casesByCheck,
            List<Long> falseBanIncidentTimestamps,
            Map<String, FeatureSummary> baselineFeatures) {

        public SubjectProfile(
                UUID playerId,
                long lastSeenEpochMillis,
                long totalCases,
                long autoBansIssued,
                long overturnedCount,
                Map<String, Long> casesByCheck,
                List<Long> falseBanIncidentTimestamps) {
            this(playerId, lastSeenEpochMillis, totalCases, autoBansIssued, overturnedCount,
                    casesByCheck, falseBanIncidentTimestamps, Map.of());
        }

        public SubjectProfile {
            Objects.requireNonNull(playerId, "playerId");
            casesByCheck = Collections.unmodifiableMap(new TreeMap<>(casesByCheck));
            falseBanIncidentTimestamps = List.copyOf(falseBanIncidentTimestamps);
            baselineFeatures = Collections.unmodifiableMap(new TreeMap<>(baselineFeatures));
        }
    }

    private final Path directory;

    public SubjectProfileStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public synchronized SubjectProfile recordCase(UUID playerId, String checkId, long epochMillis) throws IOException {
        SubjectProfile current = find(playerId).orElseGet(() ->
                new SubjectProfile(playerId, epochMillis, 0L, 0L, 0L, Map.of(), List.of(), Map.of()));
        Map<String, Long> byCheck = new TreeMap<>(current.casesByCheck());
        byCheck.merge(checkId, 1L, Long::sum);
        SubjectProfile updated = new SubjectProfile(
                playerId,
                Math.max(current.lastSeenEpochMillis(), epochMillis),
                current.totalCases() + 1L,
                current.autoBansIssued(),
                current.overturnedCount(),
                byCheck,
                current.falseBanIncidentTimestamps(),
                current.baselineFeatures());
        write(updated);
        return updated;
    }

    public synchronized SubjectProfile recordAutoBan(UUID playerId, long epochMillis) throws IOException {
        SubjectProfile current = find(playerId).orElseGet(() ->
                new SubjectProfile(playerId, epochMillis, 0L, 0L, 0L, Map.of(), List.of(), Map.of()));
        SubjectProfile updated = new SubjectProfile(
                playerId,
                Math.max(current.lastSeenEpochMillis(), epochMillis),
                current.totalCases(),
                current.autoBansIssued() + 1L,
                current.overturnedCount(),
                current.casesByCheck(),
                current.falseBanIncidentTimestamps(),
                current.baselineFeatures());
        write(updated);
        return updated;
    }

    public synchronized SubjectProfile recordFalseBanIncident(UUID playerId, long epochMillis) throws IOException {
        SubjectProfile current = find(playerId).orElseGet(() ->
                new SubjectProfile(playerId, epochMillis, 0L, 0L, 0L, Map.of(), List.of(), Map.of()));
        List<Long> incidents = new ArrayList<>(current.falseBanIncidentTimestamps());
        incidents.add(epochMillis);
        SubjectProfile updated = new SubjectProfile(
                playerId,
                Math.max(current.lastSeenEpochMillis(), epochMillis),
                current.totalCases(),
                current.autoBansIssued(),
                current.overturnedCount() + 1L,
                current.casesByCheck(),
                incidents,
                current.baselineFeatures());
        write(updated);
        return updated;
    }

    /** Atomically updates persisted per-feature baseline moments for an authenticated account (AI-02 / STO-03). */
    public synchronized SubjectProfile saveBaselineFeatures(
            UUID playerId, Map<String, FeatureSummary> baselineFeatures, long epochMillis) throws IOException {
        SubjectProfile current = find(playerId).orElseGet(() ->
                new SubjectProfile(playerId, epochMillis, 0L, 0L, 0L, Map.of(), List.of(), Map.of()));
        SubjectProfile updated = new SubjectProfile(
                playerId,
                Math.max(current.lastSeenEpochMillis(), epochMillis),
                current.totalCases(),
                current.autoBansIssued(),
                current.overturnedCount(),
                current.casesByCheck(),
                current.falseBanIncidentTimestamps(),
                baselineFeatures);
        write(updated);
        return updated;
    }

    public synchronized Optional<SubjectProfile> find(UUID playerId) throws IOException {
        Path file = profileFile(playerId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return Optional.of(fromJson(playerId, json));
    }

    /** Returns every recent false-ban incident timestamp across all stored subjects within {@code sinceEpochMillis}. */
    public synchronized List<Long> loadRecentIncidents(long sinceEpochMillis) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Long> all = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                String stem = p.getFileName().toString().replaceFirst("\\.json$", "");
                UUID id;
                try {
                    id = UUID.fromString(stem);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                SubjectProfile profile = fromJson(id, Files.readString(p, StandardCharsets.UTF_8));
                for (long ts : profile.falseBanIncidentTimestamps()) {
                    if (ts >= sinceEpochMillis) {
                        all.add(ts);
                    }
                }
            }
        }
        all.sort(Long::compareTo);
        return List.copyOf(all);
    }

    /** Erases a player's persisted profile (GDPR / privacy forget). Returns true if a file existed and was deleted. */
    public synchronized boolean forget(UUID playerId) throws IOException {
        return Files.deleteIfExists(profileFile(playerId));
    }

    private Path profileFile(UUID playerId) {
        return directory.resolve(playerId + ".json");
    }

    private void write(SubjectProfile p) throws IOException {
        Files.createDirectories(directory);
        Path target = profileFile(p.playerId());
        Path tmp = Files.createTempFile(directory, p.playerId().toString(), ".tmp");
        try {
            Files.writeString(tmp, toJson(p), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String toJson(SubjectProfile p) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"playerId\": \"").append(p.playerId()).append("\",\n");
        sb.append("  \"lastSeenEpochMillis\": ").append(p.lastSeenEpochMillis()).append(",\n");
        sb.append("  \"totalCases\": ").append(p.totalCases()).append(",\n");
        sb.append("  \"autoBansIssued\": ").append(p.autoBansIssued()).append(",\n");
        sb.append("  \"overturnedCount\": ").append(p.overturnedCount()).append(",\n");
        sb.append("  \"casesByCheck\": {");
        boolean first = true;
        for (Map.Entry<String, Long> e : p.casesByCheck().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append('"').append(escape(e.getKey())).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("},\n");
        sb.append("  \"falseBanIncidents\": [");
        List<Long> inc = p.falseBanIncidentTimestamps();
        for (int i = 0; i < inc.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(inc.get(i));
        }
        sb.append("],\n  \"baselineFeatures\": {");
        boolean firstFeat = true;
        for (Map.Entry<String, FeatureSummary> fe : p.baselineFeatures().entrySet()) {
            if (fe.getValue().count() <= 0 || !Double.isFinite(fe.getValue().mean())) {
                continue;
            }
            if (!firstFeat) {
                sb.append(", ");
            }
            double var = Double.isFinite(fe.getValue().sampleVariance()) ? fe.getValue().sampleVariance() : 0.0;
            sb.append(String.format(Locale.ROOT, "\"%s\": {\"count\": %d, \"mean\": %.12g, \"var\": %.12g}",
                    escape(fe.getKey()), fe.getValue().count(), fe.getValue().mean(), var));
            firstFeat = false;
        }
        sb.append("}\n}\n");
        return sb.toString();
    }

    private static SubjectProfile fromJson(UUID expectedId, String json) {
        long lastSeen = parseLong(json, "lastSeenEpochMillis");
        long totalCases = parseLong(json, "totalCases");
        long autoBans = parseLong(json, "autoBansIssued");
        long overturned = parseLong(json, "overturnedCount");
        Map<String, Long> byCheck = new LinkedHashMap<>();
        Matcher checksBlock = Pattern.compile("\"casesByCheck\"\\s*:\\s*\\{([^}]*)}").matcher(json);
        if (checksBlock.find()) {
            Matcher entry = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(\\d+)").matcher(checksBlock.group(1));
            while (entry.find()) {
                byCheck.put(entry.group(1), Long.parseLong(entry.group(2)));
            }
        }
        List<Long> incidents = new ArrayList<>();
        Matcher incBlock = Pattern.compile("\"falseBanIncidents\"\\s*:\\s*\\[([^\\]]*)]").matcher(json);
        if (incBlock.find()) {
            Matcher num = Pattern.compile("\\d+").matcher(incBlock.group(1));
            while (num.find()) {
                incidents.add(Long.parseLong(num.group()));
            }
        }
        Map<String, FeatureSummary> baseline = new TreeMap<>();
        int bfStart = json.indexOf("\"baselineFeatures\"");
        if (bfStart >= 0) {
            Matcher feat = Pattern.compile(
                    "\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\\{\\s*\"count\"\\s*:\\s*(\\d+)\\s*,\\s*\"mean\"\\s*:\\s*([^,\\s}]+)\\s*,\\s*\"var\"\\s*:\\s*([^}\\s]+)\\s*}")
                    .matcher(json.substring(bfStart));
            while (feat.find()) {
                try {
                    long count = Long.parseLong(feat.group(2));
                    double mean = Double.parseDouble(feat.group(3));
                    double var = Double.parseDouble(feat.group(4));
                    if (count > 0 && Double.isFinite(mean) && Double.isFinite(var) && var >= 0.0) {
                        baseline.put(feat.group(1), new FeatureSummary(count, mean, var));
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        return new SubjectProfile(expectedId, lastSeen, totalCases, autoBans, overturned, byCheck, incidents, baseline);
    }

    private static long parseLong(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
