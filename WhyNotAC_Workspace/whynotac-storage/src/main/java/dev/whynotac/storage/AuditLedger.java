package dev.whynotac.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Append-only SHA-256 + HMAC-SHA256 hash-chained audit ledger (STO-01, STO-02, PROD-13).
 *
 * <p>Each entry chains from the previous entry's hash ({@link #GENESIS_HASH} for sequence 0), includes
 * the build identifier ({@code buildTag}), seals the payload with SHA-256, and signs the digest with a
 * private server HMAC key. Player UUIDs are stored only as HMAC tokens so data-subject erasures never
 * break historical chain verification.
 */
public final class AuditLedger implements AutoCloseable {

    public static final String GENESIS_HASH = "0".repeat(64);
    public static final long DEFAULT_MAX_SEGMENT_ENTRIES = 100_000L;
    private static final String CHECKPOINT_PREFIX = "#checkpoint ";

    public enum EntryKind {
        CASE_OPENED,
        MITIGATION_APPLIED,
        KICK_ISSUED,
        TEMP_BAN_ISSUED,
        VERIFICATION_REJECTED,
        CIRCUIT_BREAKER_TRIPPED,
        REVIEW_RECORDED,
        APPEAL_RESOLVED,
        SUBJECT_FORGOTTEN,
        CONFIG_RELOAD
    }

    public record LedgerEntry(
            long sequence,
            long timestampMillis,
            EntryKind kind,
            String caseId,
            String certificateDigest,
            String buildTag,
            String subjectToken,
            String summary,
            String prevHash,
            String entryHash,
            String hmacTag) {}

    public record ChainVerification(boolean valid, long entryCount, long lastSequence, String headHash, String failureReason) {}

    private static final int SECRET_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final Path ledgerFile;
    private final byte[] secretKey;
    private final String buildTag;

    private long nextSequence;
    private String headHash = GENESIS_HASH;

    public AuditLedger(Path ledgerFile, Path secretKeyFile, String buildTag) throws IOException {
        this(ledgerFile, loadOrCreateSecret(secretKeyFile), buildTag);
    }

    public AuditLedger(Path ledgerFile, byte[] secretKey, String buildTag) throws IOException {
        this.ledgerFile = Objects.requireNonNull(ledgerFile, "ledgerFile");
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey").clone();
        if (this.secretKey.length < SECRET_BYTES) {
            throw new IllegalArgumentException("secretKey must be at least " + SECRET_BYTES + " bytes");
        }
        this.buildTag = Objects.requireNonNull(buildTag, "buildTag");
        initializeFromDisk();
    }

    private void initializeFromDisk() throws IOException {
        if (!Files.exists(ledgerFile)) {
            return;
        }
        ChainVerification verification = verify();
        if (!verification.valid()) {
            throw new IOException("Audit ledger integrity check failed: " + verification.failureReason());
        }
        this.nextSequence = verification.lastSequence() < 0 ? 0L : verification.lastSequence() + 1L;
        this.headHash = verification.headHash();
    }

    /** Appends and fsyncs one hash-chained entry synchronously on the ledger lock. */
    public synchronized LedgerEntry append(
            EntryKind kind,
            long timestampMillis,
            String caseId,
            String certificateDigest,
            UUID subjectOrNull,
            String summary) throws IOException {
        Objects.requireNonNull(kind, "kind");
        String safeCaseId = caseId == null ? "" : caseId;
        String safeCert = certificateDigest == null ? "" : certificateDigest;
        String safeSubject = subjectOrNull == null ? "" : hmacHex(secretKey, "subject:" + subjectOrNull);
        String safeSummary = summary == null ? "" : summary;

        long seq = nextSequence;
        String prev = headHash;
        String entryHash = computeEntryHash(seq, timestampMillis, kind, safeCaseId, safeCert, buildTag, safeSubject, safeSummary, prev);
        String hmacTag = hmacHex(secretKey, entryHash);

        LedgerEntry entry = new LedgerEntry(seq, timestampMillis, kind, safeCaseId, safeCert, buildTag, safeSubject, safeSummary, prev, entryHash, hmacTag);
        Path parent = ledgerFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = (toJsonLine(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(ledgerFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
        nextSequence = seq + 1L;
        headHash = entryHash;
        return entry;
    }

    /** Re-reads and cryptographically verifies every entry from genesis to tail in O(1) memory. */
    public synchronized ChainVerification verify() throws IOException {
        if (!Files.exists(ledgerFile)) {
            return new ChainVerification(true, 0L, -1L, GENESIS_HASH, "");
        }
        String expectedPrev = GENESIS_HASH;
        long expectedSeq = 0L;
        long count = 0L;
        long lastSeq = -1L;

        try (BufferedReader reader = Files.newBufferedReader(ledgerFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (count == 0L && line.startsWith(CHECKPOINT_PREFIX)) {
                    String cpFailure = verifyCheckpointHeader(line);
                    if (cpFailure != null) {
                        return new ChainVerification(false, 0L, -1L, expectedPrev, cpFailure);
                    }
                    expectedSeq = extractLongField(line, "startSeq");
                    expectedPrev = extractStringField(line, "prevHash");
                    lastSeq = expectedSeq - 1L;
                    continue;
                }
                LedgerEntry parsed;
                try {
                    parsed = parseJsonLine(line);
                } catch (RuntimeException e) {
                    return new ChainVerification(false, count, lastSeq, expectedPrev,
                            "malformed entry at seq " + expectedSeq + ": " + e.getMessage());
                }
                if (parsed.sequence() != expectedSeq) {
                    return new ChainVerification(false, count, lastSeq, expectedPrev,
                            "sequence gap at " + expectedSeq + " (found " + parsed.sequence() + ")");
                }
                if (!expectedPrev.equals(parsed.prevHash())) {
                    return new ChainVerification(false, count, lastSeq, expectedPrev,
                            "prevHash mismatch at seq " + parsed.sequence());
                }
                String expectedHash = computeEntryHash(parsed.sequence(), parsed.timestampMillis(), parsed.kind(),
                        parsed.caseId(), parsed.certificateDigest(), parsed.buildTag(), parsed.subjectToken(),
                        parsed.summary(), parsed.prevHash());
                if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                        parsed.entryHash().getBytes(StandardCharsets.US_ASCII))) {
                    return new ChainVerification(false, count, lastSeq, expectedPrev,
                            "entryHash mismatch at seq " + parsed.sequence());
                }
                String expectedHmac = hmacHex(secretKey, expectedHash);
                if (!MessageDigest.isEqual(expectedHmac.getBytes(StandardCharsets.US_ASCII),
                        parsed.hmacTag().getBytes(StandardCharsets.US_ASCII))) {
                    return new ChainVerification(false, count, lastSeq, expectedPrev,
                            "hmacTag mismatch at seq " + parsed.sequence());
                }
                expectedPrev = parsed.entryHash();
                lastSeq = parsed.sequence();
                expectedSeq++;
                count++;
            }
        }
        return new ChainVerification(true, count, lastSeq, expectedPrev, "");
    }

    public synchronized String headHash() {
        return headHash;
    }

    public synchronized long size() {
        return nextSequence;
    }

    /**
     * Rotates the active ledger file to {@code audit-ledger-<epochMillis>.jsonl} when {@link #size()} reaches
     * {@code maxEntries}, seeding the fresh active ledger with an HMAC-signed {@code #checkpoint} header carrying
     * the previous segment's {@code headHash} and {@code nextSequence} so startup verification stays O(active segment).
     */
    public synchronized Optional<Path> rotateIfExceeds(long maxEntries, long epochMillis) throws IOException {
        if (maxEntries <= 0 || nextSequence < maxEntries || !Files.exists(ledgerFile)) {
            return Optional.empty();
        }
        ChainVerification current = verify();
        if (!current.valid() || current.entryCount() < maxEntries) {
            return Optional.empty();
        }
        String archiveName = "audit-ledger-" + epochMillis + "-seq" + current.lastSequence() + ".jsonl";
        Path archived = ledgerFile.resolveSibling(archiveName);
        Files.move(ledgerFile, archived, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        String cpPayload = "startSeq=" + nextSequence + "|prevHash=" + headHash + "|buildTag=" + buildTag;
        String cpHmac = hmacHex(secretKey, "checkpoint:" + cpPayload);
        String header = CHECKPOINT_PREFIX + "{\"startSeq\":" + nextSequence
                + ",\"prevHash\":\"" + headHash
                + "\",\"buildTag\":\"" + escape(buildTag)
                + "\",\"hmacTag\":\"" + cpHmac + "\"}\n";
        byte[] bytes = header.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(ledgerFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
        return Optional.of(archived);
    }

    private String verifyCheckpointHeader(String line) {
        try {
            long startSeq = extractLongField(line, "startSeq");
            String prev = extractStringField(line, "prevHash");
            String tag = extractStringField(line, "buildTag");
            String hmac = extractStringField(line, "hmacTag");
            if (startSeq <= 0 || prev == null || prev.length() != 64 || tag == null || hmac == null) {
                return "malformed checkpoint header";
            }
            String expectedHmac = hmacHex(secretKey, "checkpoint:startSeq=" + startSeq + "|prevHash=" + prev + "|buildTag=" + tag);
            if (!MessageDigest.isEqual(expectedHmac.getBytes(StandardCharsets.US_ASCII),
                    hmac.getBytes(StandardCharsets.US_ASCII))) {
                return "checkpoint hmacTag mismatch";
            }
            return null;
        } catch (RuntimeException ex) {
            return "malformed checkpoint header: " + ex.getMessage();
        }
    }

    private static long extractLongField(String line, String key) {
        return Long.parseLong(requireNumber(line, key));
    }

    private static String extractStringField(String line, String key) {
        return requireString(line, key);
    }

    public String pseudonymFor(UUID playerUuid) {
        return playerUuid == null ? "" : hmacHex(secretKey, "subject:" + playerUuid);
    }

    @Override
    public void close() {}

    private static byte[] loadOrCreateSecret(Path file) throws IOException {
        if (Files.exists(file)) {
            byte[] existing = Files.readAllBytes(file);
            if (existing.length < SECRET_BYTES) {
                throw new IOException(file + " is shorter than " + SECRET_BYTES + " bytes");
            }
            return existing;
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] key = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(key);
        Files.write(file, key, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems rely on parent directory ACLs
        }
        return key;
    }

    private static String computeEntryHash(
            long seq,
            long ts,
            EntryKind kind,
            String caseId,
            String certDigest,
            String buildTag,
            String subjectToken,
            String summary,
            String prevHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, Long.toString(seq));
            feed(md, Long.toString(ts));
            feed(md, kind.name());
            feed(md, caseId);
            feed(md, certDigest);
            feed(md, buildTag);
            feed(md, subjectToken);
            feed(md, summary);
            feed(md, prevHash);
            return HEX.formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void feed(MessageDigest md, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        md.update((byte) (bytes.length >>> 24));
        md.update((byte) (bytes.length >>> 16));
        md.update((byte) (bytes.length >>> 8));
        md.update((byte) bytes.length);
        md.update(bytes);
    }

    private static String hmacHex(byte[] key, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 failed", e);
        }
    }

    private static String toJsonLine(LedgerEntry e) {
        return "{"
                + "\"seq\":" + e.sequence() + ","
                + "\"ts\":" + e.timestampMillis() + ","
                + "\"kind\":\"" + escape(e.kind().name()) + "\","
                + "\"caseId\":\"" + escape(e.caseId()) + "\","
                + "\"cert\":\"" + escape(e.certificateDigest()) + "\","
                + "\"build\":\"" + escape(e.buildTag()) + "\","
                + "\"subject\":\"" + escape(e.subjectToken()) + "\","
                + "\"summary\":\"" + escape(e.summary()) + "\","
                + "\"prev\":\"" + escape(e.prevHash()) + "\","
                + "\"hash\":\"" + escape(e.entryHash()) + "\","
                + "\"hmac\":\"" + escape(e.hmacTag()) + "\""
                + "}";
    }

    private static LedgerEntry parseJsonLine(String line) {
        long seq = Long.parseLong(requireNumber(line, "seq"));
        long ts = Long.parseLong(requireNumber(line, "ts"));
        EntryKind kind = EntryKind.valueOf(requireString(line, "kind"));
        String caseId = requireString(line, "caseId");
        String cert = requireString(line, "cert");
        String build = requireString(line, "build");
        String subject = requireString(line, "subject");
        String summary = requireString(line, "summary");
        String prev = requireString(line, "prev");
        String hash = requireString(line, "hash");
        String hmac = requireString(line, "hmac");
        return new LedgerEntry(seq, ts, kind, caseId, cert, build, subject, summary, prev, hash, hmac);
    }

    private static String requireNumber(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":(-?\\d+)").matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException("missing numeric field " + key);
        }
        return m.group(1);
    }

    private static String requireString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) {
            throw new IllegalArgumentException("missing string field " + key);
        }
        return unescape(m.group(1));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(ch < 0x20 ? String.format("\\u%04x", (int) ch) : ch);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"', '\\' -> sb.append(next);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
