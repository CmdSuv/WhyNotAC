package dev.whynotac.protocol.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.Set;

/**
 * Shared 256-bit HMAC secret loader/initializer for proxy-backend bridge authentication (NET-22).
 */
public final class NetworkSecret {

    private static final int KEY_BYTES = 32;

    private NetworkSecret() {}

    public static byte[] loadOrCreate(Path path) throws IOException {
        if (Files.exists(path)) {
            byte[] existing = Files.readAllBytes(path);
            if (existing.length < 16) {
                throw new IOException("Existing network secret too short: " + path);
            }
            return existing;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] generated = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(generated);
        Files.write(path, generated);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) { }
        return generated;
    }
}
