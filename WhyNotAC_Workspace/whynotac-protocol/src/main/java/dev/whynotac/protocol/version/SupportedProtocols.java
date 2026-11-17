package dev.whynotac.protocol.version;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of verified Minecraft protocol versions and their soundness gates ({@code PROD-14},
 * {@code docs/UPGRADE-GUIDE-PROD14.md}).
 *
 * <p>A protocol version is eligible for full deterministic ({@code P-A}/{@code P-M}) claims only when its
 * wire codec, world/entity tables, and bit-exact physics oracle have passed the real-client ground-truth gate.
 */
public final class SupportedProtocols {

    public record ProtocolSpec(
            int protocolVersion,
            String minecraftVersion,
            boolean physicsVerified,
            boolean combatVerified,
            boolean clockAndOrderVerified) {

        public boolean fullyVerified() {
            return physicsVerified && combatVerified && clockAndOrderVerified;
        }
    }

    /** Official verified Minecraft 1.21.11 (Protocol 774) baseline. */
    public static final ProtocolSpec V1_21_11 = new ProtocolSpec(774, "1.21.11", true, true, true);

    private static final Map<Integer, ProtocolSpec> BY_PROTOCOL;
    private static final Map<String, ProtocolSpec> BY_PROTOCOL_STRING;

    static {
        Map<Integer, ProtocolSpec> byInt = new LinkedHashMap<>();
        Map<String, ProtocolSpec> byStr = new LinkedHashMap<>();
        register(byInt, byStr, V1_21_11);
        BY_PROTOCOL = Collections.unmodifiableMap(byInt);
        BY_PROTOCOL_STRING = Collections.unmodifiableMap(byStr);
    }

    private static void register(
            Map<Integer, ProtocolSpec> byInt,
            Map<String, ProtocolSpec> byStr,
            ProtocolSpec spec) {
        byInt.put(spec.protocolVersion(), spec);
        byStr.put(Integer.toString(spec.protocolVersion()), spec);
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

    public static Map<Integer, ProtocolSpec> all() {
        return BY_PROTOCOL;
    }
}
