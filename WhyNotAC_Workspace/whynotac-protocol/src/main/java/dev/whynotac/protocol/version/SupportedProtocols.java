package dev.whynotac.protocol.version;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of verified Minecraft protocol versions, table bindings, and soundness gates
 * ({@code PROD-14}, {@code docs/UPGRADE-GUIDE-PROD14.md}).
 *
 * <p>A protocol version is eligible for full deterministic ({@code P-A}/{@code P-M}) claims only when its
 * wire codec, world/entity tables ({@link VersionedTables}), and bit-exact physics oracle have passed the
 * real-client ground-truth gate. Sessions behind a Java protocol translator may still opt into causal
 * translator-safe checks via {@link #supportsTranslatorSafe(String, boolean)}.
 */
public final class SupportedProtocols {

    /** Table resource bindings for a specific Minecraft client/server release. */
    public record VersionedTables(
            String minecraftVersion,
            String blockStatesResource,
            String entitySizesResource,
            String itemPrototypesResource) {

        public static final VersionedTables MC_1_21_11 = new VersionedTables(
                "1.21.11",
                "/dev/whynotac/world/block-states.txt.gz",
                "/dev/whynotac/world/entity-sizes.tsv",
                "/dev/whynotac/world/item-prototypes.tsv");
    }

    public record ProtocolSpec(
            int protocolVersion,
            String minecraftVersion,
            boolean physicsVerified,
            boolean combatVerified,
            boolean clockAndOrderVerified,
            VersionedTables tables) {

        public ProtocolSpec(
                int protocolVersion,
                String minecraftVersion,
                boolean physicsVerified,
                boolean combatVerified,
                boolean clockAndOrderVerified) {
            this(
                    protocolVersion,
                    minecraftVersion,
                    physicsVerified,
                    combatVerified,
                    clockAndOrderVerified,
                    VersionedTables.MC_1_21_11);
        }

        public boolean fullyVerified() {
            return physicsVerified && combatVerified && clockAndOrderVerified;
        }
    }

    /** Official verified Minecraft 1.21.11 (Protocol 774) baseline. */
    public static final ProtocolSpec V1_21_11 =
            new ProtocolSpec(774, "1.21.11", true, true, true, VersionedTables.MC_1_21_11);

    private static final ConcurrentHashMap<Integer, ProtocolSpec> BY_PROTOCOL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ProtocolSpec> BY_PROTOCOL_STRING = new ConcurrentHashMap<>();

    static {
        registerInternal(V1_21_11);
    }

    private static void registerInternal(ProtocolSpec spec) {
        BY_PROTOCOL.put(spec.protocolVersion(), spec);
        BY_PROTOCOL_STRING.put(Integer.toString(spec.protocolVersion()), spec);
    }

    /**
     * Registers an additional verified or staged Minecraft protocol specification at runtime
     * ({@code PROD-14} multi-version extensibility). Built-in {@link #V1_21_11} (774) cannot be overwritten.
     */
    public static void registerAdditional(ProtocolSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (spec.protocolVersion() <= 0) {
            throw new IllegalArgumentException("protocolVersion must be positive: " + spec.protocolVersion());
        }
        if (spec.minecraftVersion() == null || spec.minecraftVersion().isBlank()) {
            throw new IllegalArgumentException("minecraftVersion must not be blank");
        }
        Objects.requireNonNull(spec.tables(), "tables");
        if (spec.protocolVersion() == V1_21_11.protocolVersion()) {
            throw new IllegalArgumentException("Built-in baseline protocol " + V1_21_11.protocolVersion() + " cannot be overwritten");
        }
        registerInternal(spec);
    }

    /** Package-private test cleanup helper. */
    static boolean unregisterForTest(int protocolVersion) {
        if (protocolVersion == V1_21_11.protocolVersion()) {
            return false;
        }
        BY_PROTOCOL_STRING.remove(Integer.toString(protocolVersion));
        return BY_PROTOCOL.remove(protocolVersion) != null;
    }

    private SupportedProtocols() {}

    public static Optional<ProtocolSpec> find(int protocolVersion) {
        return Optional.ofNullable(BY_PROTOCOL.get(protocolVersion));
    }

    public static Optional<ProtocolSpec> find(String protocolVersion) {
        if (protocolVersion == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_PROTOCOL_STRING.get(protocolVersion));
    }

    /** True when {@code protocolVersion} has passed all physics, combat, and wire soundness gates. */
    public static boolean isFullyVerified(String protocolVersion) {
        ProtocolSpec spec = protocolVersion == null ? null : BY_PROTOCOL_STRING.get(protocolVersion);
        return spec != null && spec.fullyVerified();
    }

    /**
     * Resolves {@link VersionedTables} for {@code protocolVersion}, defaulting to {@code V1_21_11}
     * tables only when explicitly requested via {@link #find(int)}.
     */
    public static Optional<VersionedTables> tablesFor(int protocolVersion) {
        return find(protocolVersion).map(ProtocolSpec::tables);
    }

    /** True when non-Bedrock sessions behind a Java protocol translator may run causal translator-safe checks. */
    public static boolean supportsTranslatorSafe(String translator, boolean bedrock) {
        return !bedrock && translator != null && !translator.isBlank();
    }

    public static Map<Integer, ProtocolSpec> all() {
        return Collections.unmodifiableMap(BY_PROTOCOL);
    }
}
