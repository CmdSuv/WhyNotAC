package dev.whynotac.paper;

import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.recording.write.Pseudonymizer;
import dev.whynotac.recording.write.RecordingListener;
import dev.whynotac.recording.write.RecordingStore;
import dev.whynotac.recording.write.RecordingWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;
import net.minecraft.SharedConstants;
import org.bukkit.configuration.ConfigurationSection;

/** Builds the recorder from {@code config.yml}; nothing is created when recording is disabled. */
final class RecordingSetup {

    static final String SECRET_FILE = "recording-secret.key";

    private RecordingSetup() {}

    /** The recording store and the listener that writes sessions into it. */
    record Recording(RecordingStore store, RecordingListener listener) {}

    static Optional<Recording> install(ConfigurationSection config, Path dataFolder, SessionRegistry registry, String pluginVersion, Logger logger)
            throws IOException {
        if (config == null || !config.getBoolean("enabled", false)) {
            return Optional.empty();
        }
        RecordingStore.Settings settings = new RecordingStore.Settings(
                config.getLong("max-queued-megabytes", 64) << 20,
                Duration.ofDays(config.getLong("max-age-days", 14)),
                config.getLong("max-total-megabytes", 2048) << 20,
                RecordingWriter.DEFAULT_TARGET_BLOCK_BYTES,
                RecordingWriter.DEFAULT_MAX_BLOCK_AGE_NANOS);
        RecordingStore store = new RecordingStore(dataFolder.resolve("recordings"), settings, logger);
        Pseudonymizer pseudonymizer = new Pseudonymizer(loadOrCreateSecret(dataFolder.resolve(SECRET_FILE)));
        RecordingListener listener = new RecordingListener(store, pseudonymizer, SharedConstants.getProtocolVersion(),
                SharedConstants.getCurrentVersion().name(), pluginVersion);
        registry.addListener(listener);
        store.scheduleRetention();
        logger.info("WhyNotAC session recording enabled (pseudonymised) -> " + store.directory());
        return Optional.of(new Recording(store, listener));
    }

    private static byte[] loadOrCreateSecret(Path file) throws IOException {
        if (Files.exists(file)) {
            byte[] secret = Files.readAllBytes(file);
            if (secret.length >= Pseudonymizer.SECRET_BYTES) {
                return secret;
            }
            throw new IOException(file + " is shorter than " + Pseudonymizer.SECRET_BYTES + " bytes; refusing to use a weak key");
        }
        Files.createDirectories(file.getParent());
        byte[] secret = Pseudonymizer.newSecret();
        Files.write(file, secret, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: rely on the server directory's ACLs
        }
        return secret;
    }
}
