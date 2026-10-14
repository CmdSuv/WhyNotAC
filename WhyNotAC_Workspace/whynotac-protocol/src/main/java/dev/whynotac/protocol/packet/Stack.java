package dev.whynotac.protocol.packet;

import java.util.List;

/**
 * An item stack as the client holds it. {@code item}: the item's key, null for the empty stack. The
 * component patch over the item's defaults: the types it sets ({@code added}) and clears
 * ({@code removed}), by key. {@code attackRange}: the value of {@code minecraft:attack_range} when the
 * patch sets it and the packet carries values; a stack the client sends in a click carries only a hash of
 * each component ({@code hashed}), so never values.
 */
public record Stack(String item, int count, List<String> added, List<String> removed, AttackRange attackRange, boolean hashed) {

    public static final Stack EMPTY = new Stack(null, 0, List.of(), List.of(), null, false);

    /** The data component that sets a weapon's reach. */
    public static final String ATTACK_RANGE = "minecraft:attack_range";

    public Stack {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }

    public boolean isEmpty() {
        return item == null;
    }

    /** {@code net.minecraft.world.item.component.AttackRange}. */
    public record AttackRange(float minRange, float maxRange, float minCreativeRange, float maxCreativeRange, float hitboxMargin, float mobFactor) {}
}
