package dev.whynotac.protocol.network;

import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Identity;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated plugin-messaging wire envelope for {@code whynotac:bridge} between a trusted Velocity
 * companion proxy and backend Paper servers (NET-01 / NET-22).
 *
 * <p>Every frame carries a version header and 32-byte HMAC-SHA256 tag over the exact payload bytes.
 * Profile synchronization frames reject unauthenticated identities ({@code UNAUTHENTICATED} / {@code CLAIMED_BY_PROXY})
 * in accordance with {@code docs/POLICY.md}.
 */
public final class NetworkEnvelope {

    public static final String CHANNEL = "whynotac:bridge";
    public static final byte MAGIC_VERSION = 1;
    private static final int HMAC_LEN = 32;
    private static final int MAX_PAYLOAD_BYTES = 16_384;

    public sealed interface Payload permits ProxyClientEdge, ProfileSummarySync, EnforcementNotice, CovarianceSummarySync {
        long issuedAtMillis();
    }

    /** Proxy-authenticated edge metadata for translated or forwarded clients (NET-01). */
    public record ProxyClientEdge(
            long issuedAtMillis,
            UUID playerId,
            int clientProtocol,
            String translator,
            String transactionOrigin,
            Identity identity,
            boolean bedrock) implements Payload {}

    /** Authenticated behavioral / risk summary synced across servers behind the proxy (NET-22). */
    public record ProfileSummarySync(
            long issuedAtMillis,
            UUID playerId,
            Identity identity,
            String featureName,
            long sampleCount,
            double mean,
            double sampleVariance,
            int falseBanIncidents) implements Payload {

        public ProfileSummarySync {
            if (!isLongLivedAuthenticated(identity)) {
                throw new IllegalArgumentException("Unauthenticated identity cannot be synced across servers: " + identity);
            }
        }
    }

    /** Signed cross-server enforcement / appeal / GDPR erasure notice (NET-22). */
    public record EnforcementNotice(
            long issuedAtMillis,
            UUID playerId,
            String kind,
            String caseId,
            String certificateDigest,
            long durationMillis,
            String reason) implements Payload {}

    /** Authenticated multivariate combat covariance summary synced across servers behind the proxy (NET-22). */
    public record CovarianceSummarySync(
            long issuedAtMillis,
            UUID playerId,
            Identity identity,
            int dim,
            long sampleCount,
            double[] mean,
            double[] flatCoMoments) implements Payload {

        public CovarianceSummarySync {
            if (!isLongLivedAuthenticated(identity)) {
                throw new IllegalArgumentException("Unauthenticated identity cannot be synced across servers: " + identity);
            }
            if (dim <= 0 || dim > 16 || mean == null || mean.length != dim
                    || flatCoMoments == null || flatCoMoments.length != dim * dim) {
                throw new IllegalArgumentException("Invalid covariance summary dimensions: dim=" + dim);
            }
            mean = mean.clone();
            flatCoMoments = flatCoMoments.clone();
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

    private static final byte TYPE_PROXY_EDGE = 1;
    private static final byte TYPE_PROFILE_SYNC = 2;
    private static final byte TYPE_ENFORCEMENT = 3;
    private static final byte TYPE_COVARIANCE_SYNC = 4;

    private NetworkEnvelope() {}

    public static boolean isLongLivedAuthenticated(Identity identity) {
        return identity == Identity.AUTHENTICATED_MOJANG
                || identity == Identity.AUTHENTICATED_BY_PROXY
                || identity == Identity.AUTH_PLUGIN_CONFIRMED;
    }

    public static byte[] encode(Payload payload, byte[] hmacKey) {
        Objects.requireNonNull(payload, "payload");
        if (hmacKey == null || hmacKey.length < 16) {
            throw new IllegalArgumentException("hmacKey must be >= 16 bytes");
        }
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(body);
            out.writeByte(MAGIC_VERSION);
            switch (payload) {
                case ProxyClientEdge edge -> {
                    out.writeByte(TYPE_PROXY_EDGE);
                    out.writeLong(edge.issuedAtMillis());
                    out.writeLong(edge.playerId().getMostSignificantBits());
                    out.writeLong(edge.playerId().getLeastSignificantBits());
                    out.writeInt(edge.clientProtocol());
                    writeStr(out, edge.translator());
                    writeStr(out, edge.transactionOrigin());
                    writeStr(out, edge.identity().name());
                    out.writeBoolean(edge.bedrock());
                }
                case ProfileSummarySync sync -> {
                    out.writeByte(TYPE_PROFILE_SYNC);
                    out.writeLong(sync.issuedAtMillis());
                    out.writeLong(sync.playerId().getMostSignificantBits());
                    out.writeLong(sync.playerId().getLeastSignificantBits());
                    writeStr(out, sync.identity().name());
                    writeStr(out, sync.featureName());
                    out.writeLong(sync.sampleCount());
                    out.writeDouble(sync.mean());
                    out.writeDouble(sync.sampleVariance());
                    out.writeInt(sync.falseBanIncidents());
                }
                case EnforcementNotice notice -> {
                    out.writeByte(TYPE_ENFORCEMENT);
                    out.writeLong(notice.issuedAtMillis());
                    out.writeLong(notice.playerId().getMostSignificantBits());
                    out.writeLong(notice.playerId().getLeastSignificantBits());
                    writeStr(out, notice.kind());
                    writeStr(out, notice.caseId());
                    writeStr(out, notice.certificateDigest());
                    out.writeLong(notice.durationMillis());
                    writeStr(out, notice.reason());
                }
                case CovarianceSummarySync cov -> {
                    out.writeByte(TYPE_COVARIANCE_SYNC);
                    out.writeLong(cov.issuedAtMillis());
                    out.writeLong(cov.playerId().getMostSignificantBits());
                    out.writeLong(cov.playerId().getLeastSignificantBits());
                    writeStr(out, cov.identity().name());
                    out.writeInt(cov.dim());
                    out.writeLong(cov.sampleCount());
                    for (double v : cov.mean()) {
                        out.writeDouble(v);
                    }
                    for (double v : cov.flatCoMoments()) {
                        out.writeDouble(v);
                    }
                }
            }
            out.flush();
            byte[] payloadBytes = body.toByteArray();
            if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Payload exceeds max length: " + payloadBytes.length);
            }
            byte[] tag = hmac(hmacKey, payloadBytes);
            byte[] frame = new byte[payloadBytes.length + HMAC_LEN];
            System.arraycopy(payloadBytes, 0, frame, 0, payloadBytes.length);
            System.arraycopy(tag, 0, frame, payloadBytes.length, HMAC_LEN);
            return frame;
        } catch (IOException e) {
            throw new IllegalStateException("In-memory encoding failed", e);
        }
    }

    /** Verifies HMAC-SHA256 and clock skew bounds before decoding the envelope payload. */
    public static Payload decodeAndVerify(byte[] frame, byte[] hmacKey, long nowMillis, long maxSkewMillis)
            throws IOException {
        if (frame == null || frame.length <= 2 + HMAC_LEN || frame.length > MAX_PAYLOAD_BYTES + HMAC_LEN) {
            throw new IOException("Invalid frame length");
        }
        if (hmacKey == null || hmacKey.length < 16) {
            throw new IOException("Missing HMAC key");
        }
        int bodyLen = frame.length - HMAC_LEN;
        byte[] body = new byte[bodyLen];
        byte[] receivedTag = new byte[HMAC_LEN];
        System.arraycopy(frame, 0, body, 0, bodyLen);
        System.arraycopy(frame, bodyLen, receivedTag, 0, HMAC_LEN);

        byte[] expectedTag = hmac(hmacKey, body);
        if (!MessageDigest.isEqual(expectedTag, receivedTag)) {
            throw new IOException("HMAC authentication failed");
        }

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        byte version = in.readByte();
        if (version != MAGIC_VERSION) {
            throw new IOException("Unsupported envelope version: " + version);
        }
        byte type = in.readByte();
        long issuedAt = in.readLong();
        if (maxSkewMillis >= 0 && Math.abs(nowMillis - issuedAt) > maxSkewMillis) {
            throw new IOException("Envelope outside replay/skew window");
        }
        UUID playerId = new UUID(in.readLong(), in.readLong());
        Payload parsed = switch (type) {
            case TYPE_PROXY_EDGE -> {
                int proto = in.readInt();
                String translator = readStr(in);
                String txOrigin = readStr(in);
                Identity identity = Identity.valueOf(readStr(in));
                boolean bedrock = in.readBoolean();
                yield new ProxyClientEdge(issuedAt, playerId, proto, translator, txOrigin, identity, bedrock);
            }
            case TYPE_PROFILE_SYNC -> {
                Identity identity = Identity.valueOf(readStr(in));
                String feature = readStr(in);
                long count = in.readLong();
                double mean = in.readDouble();
                double var = in.readDouble();
                int incidents = in.readInt();
                yield new ProfileSummarySync(issuedAt, playerId, identity, feature, count, mean, var, incidents);
            }
            case TYPE_ENFORCEMENT -> {
                String kind = readStr(in);
                String caseId = readStr(in);
                String digest = readStr(in);
                long duration = in.readLong();
                String reason = readStr(in);
                yield new EnforcementNotice(issuedAt, playerId, kind, caseId, digest, duration, reason);
            }
            case TYPE_COVARIANCE_SYNC -> {
                Identity identity = Identity.valueOf(readStr(in));
                int dim = in.readInt();
                if (dim <= 0 || dim > 16) {
                    throw new IOException("Invalid covariance dim: " + dim);
                }
                long count = in.readLong();
                double[] mean = new double[dim];
                for (int i = 0; i < dim; i++) {
                    mean[i] = in.readDouble();
                }
                double[] cm = new double[dim * dim];
                for (int i = 0; i < cm.length; i++) {
                    cm[i] = in.readDouble();
                }
                yield new CovarianceSummarySync(issuedAt, playerId, identity, dim, count, mean, cm);
            }
            default -> throw new IOException("Unknown envelope type: " + type);
        };
        if (in.available() != 0) {
            throw new IOException("Trailing bytes in envelope");
        }
        return parsed;
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4096) {
            throw new IOException("String too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readStr(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len > 4096) {
            throw new IOException("String length exceeds bound: " + len);
        }
        byte[] buf = in.readNBytes(len);
        if (buf.length != len) {
            throw new IOException("Truncated string");
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
