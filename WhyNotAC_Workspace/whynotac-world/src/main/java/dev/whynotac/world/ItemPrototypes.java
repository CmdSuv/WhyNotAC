package dev.whynotac.world;

import dev.whynotac.protocol.packet.Stack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What every 1.21.11 item has by default (its prototype components) that a stack's patch can override:
 * its attack range ({@code minecraft:attack_range}, what spears reach with) and what it turns into when
 * used up ({@code minecraft:use_remainder}). The table is the game's own: {@code ItemPrototypesDataTest}
 * rebuilds it from the game's item registry.
 */
public final class ItemPrototypes {

    private static final String TABLE = "item-prototypes.tsv";

    /** One item: its default attack range (null: none) and the item its use leaves (null: none). */
    public record Prototype(String key, Stack.AttackRange attackRange, String useRemainder) {}

    private static volatile ItemPrototypes vanilla;

    private final Map<String, Prototype> items;

    private ItemPrototypes(Map<String, Prototype> items) {
        this.items = items;
    }

    /** The 1.21.11 table. */
    public static ItemPrototypes vanilla() {
        ItemPrototypes table = vanilla;
        if (table == null) {
            Map<String, Prototype> items = new HashMap<>();
            for (String line : vanillaTable().split("\n")) {
                if (!line.isEmpty()) {
                    String[] cells = line.split("\t");
                    Stack.AttackRange range = null;
                    if (!cells[1].equals("-")) {
                        String[] f = cells[1].split(":");
                        range = new Stack.AttackRange(Float.parseFloat(f[0]), Float.parseFloat(f[1]), Float.parseFloat(f[2]), Float.parseFloat(f[3]),
                                Float.parseFloat(f[4]), Float.parseFloat(f[5]));
                    }
                    items.put(cells[0], new Prototype(cells[0], range, cells[2].equals("-") ? null : cells[2]));
                }
            }
            vanilla = table = new ItemPrototypes(Map.copyOf(items));
        }
        return table;
    }

    /** The item's prototype, or null for an item this table does not know (a newer protocol). */
    public Prototype get(String item) {
        return items.get(item);
    }

    public int size() {
        return items.size();
    }

    /** The shipped table: {@code key attackRange(min:max:minCreative:maxCreative:margin:mobFactor | -) useRemainder(item | -)}. */
    public static String vanillaTable() {
        try (InputStream in = ItemPrototypes.class.getResourceAsStream(TABLE)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
