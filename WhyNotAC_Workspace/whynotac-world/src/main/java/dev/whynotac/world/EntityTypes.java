package dev.whynotac.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What the client does with each entity type's position (1.21.11): whether it is a living entity (head
 * rotation interpolation, clamped spawn position) and how many steps its position interpolates over.
 * The table is the game's own: {@code EntityTypesDataTest} rebuilds it from the game's classes.
 */
public final class EntityTypes {

    public static final String PLAYER = "minecraft:player";

    /** The client sets the position at once ({@code Entity.getInterpolation()} is null). */
    public static final int NO_INTERPOLATION = -1;

    /** One entity type. {@code interpolationSteps}: {@link #NO_INTERPOLATION}, 0 (a handler that snaps) or the step count. */
    public record Kind(String key, boolean living, int interpolationSteps) {}

    /** A type this table does not know (a newer protocol): treated as non-living, without interpolation. */
    public static Kind unknown(String key) {
        return new Kind(key, false, NO_INTERPOLATION);
    }

    private static final String RESOURCE = "entity-types.tsv";
    private static volatile EntityTypes vanilla;

    private final Map<String, Kind> kinds;

    private EntityTypes(Map<String, Kind> kinds) {
        this.kinds = kinds;
    }

    /** The 1.21.11 table. */
    public static EntityTypes vanilla() {
        EntityTypes table = vanilla;
        if (table == null) {
            Map<String, Kind> kinds = new HashMap<>();
            for (String line : vanillaTable().split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] cells = line.split("\t");
                kinds.put(cells[0], new Kind(cells[0], cells[1].equals("1"), Integer.parseInt(cells[2])));
            }
            vanilla = table = new EntityTypes(Map.copyOf(kinds));
        }
        return table;
    }

    /** The shipped table as text: {@code key TAB living(0/1) TAB interpolationSteps}, one line per type, sorted. */
    public static String vanillaTable() {
        try (InputStream in = EntityTypes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Kind kind(String key) {
        Kind kind = kinds.get(key);
        return kind != null ? kind : unknown(key);
    }

    public int size() {
        return kinds.size();
    }
}
