package dev.whynotac.verdict;

import java.util.Map;

/**
 * Independence groups for evidence correlation (VER-01..VER-04).
 *
 * <p>Checks sharing the same sensor, prediction mechanism, or failure mode are grouped together so
 * correlated findings saturate inside one group rather than masquerading as independent corroboration.
 */
public enum DependencyGroup {
    ATTACK_REACH("attack-reach"),
    MOVEMENT_PHYSICS("movement-physics"),
    RAYCAST_AIM("raycast-aim"),
    CLOCK_AND_FRAMING("clock-and-framing"),
    BLOCK_TIMING("block-timing"),
    PACKET_GRAMMAR("packet-grammar"),
    STATISTICAL_SIGNALS("statistical-signals"),
    OTHER("other");

    private static final Map<String, DependencyGroup> CHECK_GROUPS = Map.ofEntries(
            Map.entry("attack-reach", ATTACK_REACH),
            Map.entry("movement", MOVEMENT_PHYSICS),
            Map.entry("attack-aim", RAYCAST_AIM),
            Map.entry("use-aim", RAYCAST_AIM),
            Map.entry("client-clock", CLOCK_AND_FRAMING),
            Map.entry("tick-framing", CLOCK_AND_FRAMING),
            Map.entry("transaction-order", CLOCK_AND_FRAMING),
            Map.entry("break-time", BLOCK_TIMING),
            Map.entry("action-grammar", PACKET_GRAMMAR),
            Map.entry("prediction-sequence", PACKET_GRAMMAR),
            Map.entry("click-pattern", STATISTICAL_SIGNALS),
            Map.entry("aim-pattern", STATISTICAL_SIGNALS),
            Map.entry("container-timing", STATISTICAL_SIGNALS),
            Map.entry("design-probe", STATISTICAL_SIGNALS));

    private final String id;

    DependencyGroup(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Returns the independence group governing {@code checkId}. */
    public static DependencyGroup ofCheck(String checkId) {
        return CHECK_GROUPS.getOrDefault(checkId, OTHER);
    }

    /** Explicit check-to-group mappings (unmodifiable). */
    public static Map<String, DependencyGroup> mappings() {
        return CHECK_GROUPS;
    }
}
