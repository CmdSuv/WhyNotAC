package dev.whynotac.recording.write;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Replaces player identities with stable pseudonyms: {@code HMAC-SHA256(secret, uuid)}, truncated to
 * 128 bits and stamped as a version-8 (custom) UUID.
 *
 * <p>Within one server (one secret) the same player always maps to the same pseudonym, so profiles
 * and evidence can be linked across sessions. Without the secret the mapping cannot be reversed or
 * even confirmed by guessing UUIDs. Rotating the secret unlinks all past recordings.
 */
public final class Pseudonymizer {

    public static final int SECRET_BYTES = 32;

    private final SecretKeySpec key;

    public Pseudonymizer(byte[] secret) {
        if (secret.length < SECRET_BYTES) {
            throw new IllegalArgumentException("secret must be at least " + SECRET_BYTES + " bytes");
        }
        this.key = new SecretKeySpec(secret.clone(), "HmacSHA256");
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    public UUID pseudonym(UUID playerId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(ByteBuffer.allocate(16).putLong(playerId.getMostSignificantBits()).putLong(playerId.getLeastSignificantBits()).array());
            ByteBuffer bytes = ByteBuffer.wrap(digest, 0, 16);
            long msb = bytes.getLong();
            long lsb = bytes.getLong();
            msb = (msb & ~0xF000L) | 0x8000L;                          // version 8
            lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // RFC 4122 variant
            return new UUID(msb, lsb);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Display name derived from the pseudonym; carries no information about the real name. */
    public static String displayName(UUID pseudonym) {
        return "anon-" + HexFormat.of().toHexDigits(pseudonym.getMostSignificantBits()).substring(0, 8);
    }
}
