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

/**
 * Atomic, restart-safe persistent profile store indexed by player UUID (STO-03), supporting GDPR access and
 * erasure requests ({@link #find(UUID)}, {@link #forget(UUID)}).
 */
public final class SubjectProfileStore {

    public record FeatureSummary(long count, double mean, double sampleVariance) {}

    public record CovarianceSummary(int dim, long count, double[] mean, double[] flatCoMoments) {
        public CovarianceSummary {
            mean = mean == null ? new double[0] : mean.clone();
            flatCoMoments = flatCoMoments == null ? new double[0] : flatCoMoments.clone();
        }

        @Override
        public double[] mean() {
            return mean.clone();
        }

        @Override
        public double[] flatCoMoments() {
            return flatCoMoments.clone();
        }
    }

    public record SubjectProfile(
            UUID playerId,
            long lastSeenEpochMillis,
            long totalCases,
            long autoBansIssued,
            long overturnedCount,
            Map<String, Long> casesByCheck,
            List<Long> falseBanIncidentTimestamps,
            Map<String, FeatureSummary> baselineFeatures,
            CovarianceSummary combatCovariance) {

        public SubjectProfile(
                UUID playerId,
                long lastSeenEpochMillis,
                long totalCases,
                long autoBansIssued,
                long overturnedCount,
                Map<String, Long> casesByCheck,
                List<Long> falseBanIncidentTimestamps) {
            this(playerId, lastSeenEpochMillis, totalCases, autoBansIssued, overturnedCount,
                    casesByCheck, falseBanIncidentTimestamps, Map.of(), null);
        }

        public SubjectProfile(
                UUID playerId,
                long lastSeenEpochMillis,
                long totalCases,
                long autoBansIssued,
                long overturnedCount,
                Map<String, Long> casesByCheck,
                List<Long> falseBanIncidentTimestamps,
                Map<String, FeatureSummary> baselineFeatures) {
            this(playerId, lastSeenEpochMillis, totalCases, autoBansIssued, overturnedCount,
                    casesByCheck, falseBanIncidentTimestamps, baselineFeatures, null);
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
                current.baselineFeatures(),
                current.combatCovariance());
        write(updated);
        return updated;
    }

    public synchronized SubjectProfile recordAutoBan(UUID playerId, long epochMillis) throws IOException {
        SubjectProfile current = find(playerId).orElseGet(() ->
                new SubjectProfile(playerId, epochMillis, 0L, 0L, 0L, Map.of(), List.of(), Map.of(), null));
        SubjectProfile updated = new SubjectProfile(
                playerId,
                Math.max(current.lastSeenEpochMillis(), epochMillis),
                current.totalCases(),
                current.autoBansIssued() + 1L,
                current.overturnedCount(),
                current.casesByCheck(),
                current.falseBanIncidentTimestamps(),
                current.baselineFeatures(),
                current.combatCovariance());
        write(updated);
        return updated;
    }

    public synchronized SubjectProfile recordFalseBanIncident(UUID playerId, long epochMillis) throws IOException {
        SubjectProfile current = find(playerId).orElseGet(() ->
                new SubjectProfile(playerId, epochMillis, 0L, 0L, 0L, Map.of(), List.of(), Map.of(), null));
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
                current.baselineFeatures(),
                current.combatCovariance());
        write(updated);
        return updated;
    }

    /** Atomically updates persisted per-feature baseline moments for an authenticated account (AI-02 / STO-03). */
    public synchronized SubjectProfile saveBaselineFeatures(
            UUID playerId, Map<String, FeatureSummary> baselineFeatures, long epochMillis) throws IOException {
        return saveBaselineFeatures(playerId, baselineFeatures, null, epochMillis);
    }

    /** Atomically updates persisted per-feature moments and optional multivariate combat covariance (AI-02 / STO-03). */
    public synchronized SubjectProfile saveBaselineFeatures(
            UUID playerId, Map<String, FeatureSummary> baselineFeatures, CovarianceSummary combatCovariance, long epochMillis)
            throws IOException {
        SubjectProfile current = find(playerId).orElseGet(() ->
                new SubjectProfile(playerId, epochMillis, 0L, 0L, 0L, Map.of(), List.of(), Map.of(), null));
        SubjectProfile updated = new SubjectProfile(
                playerId,
                Math.max(current.lastSeenEpochMillis(), epochMillis),
                current.totalCases(),
                current.autoBansIssued(),
                current.overturnedCount(),
                current.casesByCheck(),
                current.falseBanIncidentTimestamps(),
                baselineFeatures,
                combatCovariance != null ? combatCovariance : current.combatCovariance());
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
        sb.append("}");
        CovarianceSummary cov = p.combatCovariance();
        if (cov != null && cov.dim() > 0 && cov.count() > 0
                && cov.mean().length == cov.dim()
                && cov.flatCoMoments().length == cov.dim() * cov.dim()) {
            sb.append(",\n  \"combatCovariance\": {\"dim\": ").append(cov.dim())
                    .append(", \"count\": ").append(cov.count())
                    .append(", \"mean\": [");
            double[] mean = cov.mean();
            for (int i = 0; i < mean.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(String.format(Locale.ROOT, "%.12g", Double.isFinite(mean[i]) ? mean[i] : 0.0));
            }
            sb.append("], \"coMoments\": [");
            double[] cm = cov.flatCoMoments();
            for (int i = 0; i < cm.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(String.format(Locale.ROOT, "%.12g", Double.isFinite(cm[i]) ? cm[i] : 0.0));
            }
            sb.append("]}");
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    private static SubjectProfile fromJson(UUID expectedId, String json) {
        Map<String, Object> root = MinimalJson.parseObject(json);
        long lastSeen = MinimalJson.getLong(root, "lastSeenEpochMillis", 0L);
        long totalCases = MinimalJson.getLong(root, "totalCases", 0L);
        long autoBans = MinimalJson.getLong(root, "autoBansIssued", 0L);
        long overturned = MinimalJson.getLong(root, "overturnedCount", 0L);
        Map<String, Long> byCheck = new LinkedHashMap<>();
        Map<String, Object> checksObj = MinimalJson.getObject(root, "casesByCheck");
        if (checksObj != null) {
            for (Map.Entry<String, Object> e : checksObj.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    byCheck.put(e.getKey(), n.longValue());
                }
            }
        }
        List<Long> incidents = new ArrayList<>();
        List<Object> incArr = MinimalJson.getArray(root, "falseBanIncidents");
        if (incArr != null) {
            for (Object item : incArr) {
                if (item instanceof Number n) {
                    incidents.add(n.longValue());
                }
            }
        }
        Map<String, FeatureSummary> baseline = new TreeMap<>();
        Map<String, Object> bfObj = MinimalJson.getObject(root, "baselineFeatures");
        if (bfObj != null) {
            for (Map.Entry<String, Object> e : bfObj.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fMap = (Map<String, Object>) raw;
                    long count = MinimalJson.getLong(fMap, "count", 0L);
                    double mean = MinimalJson.getDouble(fMap, "mean", Double.NaN);
                    double var = MinimalJson.getDouble(fMap, "var", Double.NaN);
                    if (count > 0 && Double.isFinite(mean) && Double.isFinite(var) && var >= 0.0) {
                        baseline.put(e.getKey(), new FeatureSummary(count, mean, var));
                    }
                }
            }
        }
        CovarianceSummary covSummary = null;
        Map<String, Object> covObj = MinimalJson.getObject(root, "combatCovariance");
        if (covObj != null) {
            int dim = (int) MinimalJson.getLong(covObj, "dim", 0L);
            long count = MinimalJson.getLong(covObj, "count", 0L);
            double[] mean = toDoubleArray(MinimalJson.getArray(covObj, "mean"));
            double[] cm = toDoubleArray(MinimalJson.getArray(covObj, "coMoments"));
            if (dim > 0 && count > 0 && mean.length == dim && cm.length == dim * dim) {
                covSummary = new CovarianceSummary(dim, count, mean, cm);
            }
        }
        return new SubjectProfile(expectedId, lastSeen, totalCases, autoBans, overturned, byCheck, incidents, baseline, covSummary);
    }

    private static double[] toDoubleArray(List<Object> arr) {
        if (arr == null || arr.isEmpty()) {
            return new double[0];
        }
        double[] out = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            if (!(arr.get(i) instanceof Number n)) {
                return new double[0];
            }
            double v = n.doubleValue();
            if (!Double.isFinite(v)) {
                return new double[0];
            }
            out[i] = v;
        }
        return out;
    }

    private static String escape(String s) {
        return MinimalJson.escape(s);
    }
}
