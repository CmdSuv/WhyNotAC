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
 * its attack range ({@code minecraft:attack_range}, what spears reach with), what it turns into when
 * used up ({@code minecraft:use_remainder}) and the equipment slot a use swaps it with
 * ({@code minecraft:equippable} when swappable), how using it slows the player ({@code minecraft:use_effects})
 * and whether a use may end or move the player on the client by itself, and how it mines ({@code minecraft:tool}). The table is the game's own: {@code ItemPrototypesDataTest}
 * rebuilds it from the game's item registry.
 */
public final class ItemPrototypes {

    private static final String TABLE = "item-prototypes.tsv";

    /**
     * One item: its default attack range (null: none), the item its use leaves (null: none), and the
     * equipment slot a use swaps it with (a swappable {@code minecraft:equippable}; null: none), and whether
     * it is a piercing weapon ({@code minecraft:piercing_weapon}: the main hand stabs instead of attacking),
     * and whether a use never changes its stack on the client ({@code stable}: no use method of its class
     * changes the inventory client-side, and it is not consumable, has no remainder and swaps with nothing),
     * its {@code minecraft:use_effects} ({@code useCanSprint}, {@code useSpeed}: {@code UseEffects.canSprint} and
     * {@code speedMultiplier}), and what a use does on the client beyond slowing ({@code useKind}).
     */
    public record Prototype(String key, Stack.AttackRange attackRange, String useRemainder, String swapSlot, boolean piercing, boolean stable,
            boolean useCanSprint, float useSpeed, UseKind useKind, Tool tool) {}

    /**
     * {@code net.minecraft.world.item.component.Tool} (null on a prototype without one): the rules in order and the
     * speed when none sets one. {@code Tool.getMiningSpeed} is the speed of the first rule with a speed whose blocks
     * hold the state's block, {@code isCorrectForDrops} the value of the first rule with one (false without).
     */
    public record Tool(java.util.List<Rule> rules, float defaultMiningSpeed) {
        public Tool {
            rules = java.util.List.copyOf(rules);
        }
    }

    /**
     * One {@code Tool.Rule}: its blocks are a tag ({@code tag}, the server's) or a list of block keys ({@code blocks});
     * {@code speed} and {@code correctForDrops} are null where the rule has none.
     */
    public record Rule(String tag, java.util.Set<String> blocks, Float speed, Boolean correctForDrops) {
        public Rule {
            blocks = blocks == null ? null : java.util.Set.copyOf(blocks);
        }
    }

    /**
     * What a use of the item ({@code Item.use}) does on the client beyond {@code use_effects}: nothing that moves the
     * player or changes the world ({@code PLAIN}); the client may end it by itself, without a packet
     * ({@code SELF_ENDING}: a brush not aimed at a block, a bundle dropping its contents); its release may move the
     * player ({@code RELEASE_MOVES}: a trident's riptide); it may place or take a block where the client's ray hits
     * ({@code CHANGES_BLOCKS}: buckets, lily pads and frogspawn).
     */
    public enum UseKind { PLAIN, SELF_ENDING, RELEASE_MOVES, CHANGES_BLOCKS }

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
                    String[] use = cells[6].split(":");
                    items.put(cells[0], new Prototype(cells[0], range, cells[2].equals("-") ? null : cells[2], cells[3].equals("-") ? null : cells[3],
                            cells[4].equals("piercing"), cells[5].equals("stable"), use[0].equals("sprint"), Float.parseFloat(use[1]),
                            switch (cells[7]) {
                                case "self-ending" -> UseKind.SELF_ENDING;
                                case "release-moves" -> UseKind.RELEASE_MOVES;
                                case "changes-blocks" -> UseKind.CHANGES_BLOCKS;
                                default -> UseKind.PLAIN;
                            }, tool(cells[8])));
                }
            }
            vanilla = table = new ItemPrototypes(Map.copyOf(items));
        }
        return table;
    }

    /** The tool cell: {@code -}, or the default speed and then each rule, {@code |}-separated (see {@link #vanillaTable}). */
    private static Tool tool(String cell) {
        if (cell.equals("-")) {
            return null;
        }
        String[] parts = cell.split("\\|");
        java.util.List<Rule> rules = new java.util.ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String[] r = parts[i].split("=", -1);
            String tag = r[0].startsWith("#") ? r[0].substring(1) : null;
            java.util.Set<String> blocks = tag == null ? java.util.Set.copyOf(java.util.Arrays.asList(r[0].split(","))) : null;
            rules.add(new Rule(tag, blocks, r[1].equals("-") ? null : Float.parseFloat(r[1]), r[2].equals("-") ? null : Boolean.parseBoolean(r[2])));
        }
        return new Tool(rules, Float.parseFloat(parts[0]));
    }

    /** The item's prototype, or null for an item this table does not know (a newer protocol). */
    public Prototype get(String item) {
        return items.get(item);
    }

    public int size() {
        return items.size();
    }

    /**
     * The shipped table: {@code key attackRange(min:max:minCreative:maxCreative:margin:mobFactor | -) useRemainder(item | -)
     * swapSlot(slot | -) piercing(piercing | -) stable(stable | -) useEffects(sprint|slow:speedMultiplier)
     * useKind(self-ending | release-moves | changes-blocks | -) tool(defaultSpeed|rule|rule... | -)}, a rule being
     * {@code blocks=speed=correctForDrops} with {@code blocks} a tag ({@code #key}) or comma-separated block keys and
     * {@code -} for no speed or no drop rule.
     */
    public static String vanillaTable() {
        try (InputStream in = ItemPrototypes.class.getResourceAsStream(TABLE)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
