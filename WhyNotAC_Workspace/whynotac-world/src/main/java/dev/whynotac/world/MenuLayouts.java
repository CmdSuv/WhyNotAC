package dev.whynotac.world;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the player's hotbar, off hand and feet sit in each 1.21.11 menu the client can have open: for every
 * menu type, its slot count and the menu slot of each hotbar slot, of the off hand and of the feet armor
 * (-1: the menu has none).
 * The table is the game's own: {@code MenuLayoutsDataTest} builds every menu type's client menu and reads
 * which of its slots are the player's inventory.
 */
public final class MenuLayouts {

    private static final String TABLE = "menu-layouts.tsv";
    /** The key of {@code InventoryMenu}, which has no menu type: the player's own, container 0. */
    public static final String INVENTORY = "#inventory";
    public static final String HORSE = "#horse";
    public static final String NAUTILUS = "#nautilus";

    /** One menu: its slot count, the menu slot of hotbar slots 0..8 (-1: none), of the off hand and of the feet (-1: none). */
    public record Layout(String key, int size, List<Integer> hotbar, int offhand, int feet) {
        /** The off hand's {@link #playerSlot}. */
        public static final int OFFHAND = -2;
        /** The feet's {@link #playerSlot}. */
        public static final int FEET = -3;

        public Layout {
            hotbar = Collections.unmodifiableList(new ArrayList<>(hotbar));
        }

        /** The hotbar slot (0..8) at menu slot {@code slot}, {@link #OFFHAND}, {@link #FEET}, or -1 for none of them. */
        public int playerSlot(int slot) {
            if (slot == offhand && offhand >= 0) {
                return OFFHAND;
            }
            if (slot == feet && feet >= 0) {
                return FEET;
            }
            return hotbar.indexOf(slot);
        }
    }

    private static volatile MenuLayouts vanilla;

    private final Map<String, Layout> layouts;

    private MenuLayouts(Map<String, Layout> layouts) {
        this.layouts = layouts;
    }

    /** The 1.21.11 table. */
    public static MenuLayouts vanilla() {
        MenuLayouts table = vanilla;
        if (table == null) {
            Map<String, Layout> layouts = new HashMap<>();
            for (String line : vanillaTable().split("\n")) {
                if (!line.isEmpty()) {
                    String[] cells = line.split("\t");
                    List<Integer> hotbar = new ArrayList<>();
                    for (String s : cells[2].split(",")) {
                        hotbar.add(Integer.parseInt(s));
                    }
                    layouts.put(cells[0], new Layout(cells[0], Integer.parseInt(cells[1]), hotbar, Integer.parseInt(cells[3]),
                            Integer.parseInt(cells[4])));
                }
            }
            vanilla = table = new MenuLayouts(Map.copyOf(layouts));
        }
        return table;
    }

    /** The menu of a menu type ({@code minecraft:generic_9x3}), or null for one this table does not know. */
    public Layout get(String menuType) {
        return layouts.get(menuType);
    }

    public Layout inventory() {
        return layouts.get(INVENTORY);
    }

    /**
     * A horse's (any {@code AbstractHorse}'s) inventory menu with {@code columns} chest columns: the saddle,
     * the body armor, {@code 3 * columns} chest slots, then the player's 27 + 9. The table's row is the
     * zero-column menu; {@code MenuLayoutsDataTest} checks the formula against the game for other columns.
     */
    public Layout horse(int columns) {
        Layout zero = layouts.get(HORSE);
        if (columns <= 0) {
            return zero;
        }
        int shift = 3 * columns;
        List<Integer> hotbar = new ArrayList<>(zero.hotbar().size());
        for (int slot : zero.hotbar()) {
            hotbar.add(slot < 0 ? slot : slot + shift);
        }
        return new Layout(HORSE + ":" + columns, zero.size() + shift, hotbar, zero.offhand() < 0 ? -1 : zero.offhand() + shift,
                zero.feet() < 0 ? -1 : zero.feet() + shift);
    }

    /** A nautilus's inventory menu: the saddle and the body armor, then the player's; it has no chest slots whatever the columns. */
    public Layout nautilus() {
        return layouts.get(NAUTILUS);
    }

    public int size() {
        return layouts.size();
    }

    /** The shipped table, as text. */
    public static String vanillaTable() {
        try (InputStream in = MenuLayouts.class.getResourceAsStream(TABLE)) {
            if (in == null) {
                throw new IllegalStateException(TABLE + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
