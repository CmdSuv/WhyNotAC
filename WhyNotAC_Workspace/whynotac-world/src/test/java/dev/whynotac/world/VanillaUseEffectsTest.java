package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.protocol.packet.Stack;
import java.util.List;
import org.junit.jupiter.api.Test;

class VanillaUseEffectsTest {

    private final VanillaUseEffects effects = new VanillaUseEffects(ItemPrototypes.vanilla());

    private static Stack stack(String item, int count, List<String> added, List<String> removed) {
        return new Stack(item, count, added, removed, null, false);
    }

    @Test
    void mostUsesKeepTheStackOrLeaveNone() {
        for (ClientInventory.Use use : ClientInventory.Use.values()) {
            assertEquals(ClientInventory.Outcome.KEEP_OR_NONE, effects.outcome(stack("minecraft:golden_apple", 3, List.of(), List.of()), use));
            assertEquals(ClientInventory.Outcome.KEEP_OR_NONE, effects.outcome(stack("minecraft:iron_spear", 1, List.of(), List.of()), use));
            // a stew's remainder is the bowl (its prototype's): no attack range
            assertEquals(ClientInventory.Outcome.KEEP_OR_NONE, effects.outcome(stack("minecraft:mushroom_stew", 1, List.of(), List.of()), use));
        }
    }

    @Test
    void anEquippableSwapsWithTheArmor() {
        assertEquals(ClientInventory.Outcome.UNKNOWN, effects.outcome(stack("minecraft:diamond_chestplate", 1, List.of(), List.of()), ClientInventory.Use.ITEM));
        assertEquals(ClientInventory.Outcome.KEEP_OR_NONE_ADD,
                effects.outcome(stack("minecraft:diamond_chestplate", 2, List.of("minecraft:max_stack_size"), List.of()), ClientInventory.Use.ITEM));
        assertEquals(ClientInventory.Outcome.UNKNOWN, effects.outcome(stack("minecraft:elytra", 1, List.of(), List.of()), ClientInventory.Use.ITEM));
        // only Item.use swaps
        assertEquals(ClientInventory.Outcome.KEEP_OR_NONE,
                effects.outcome(stack("minecraft:diamond_chestplate", 1, List.of(), List.of()), ClientInventory.Use.ITEM_ON_BLOCK));
        // a patch may make anything equippable, or take it away
        assertEquals(ClientInventory.Outcome.UNKNOWN,
                effects.outcome(stack("minecraft:stick", 1, List.of(VanillaUseEffects.EQUIPPABLE), List.of()), ClientInventory.Use.ITEM));
        assertEquals(ClientInventory.Outcome.KEEP_OR_NONE,
                effects.outcome(stack("minecraft:diamond_chestplate", 1, List.of(), List.of(VanillaUseEffects.EQUIPPABLE)), ClientInventory.Use.ITEM));
    }

    @Test
    void aRemainderFromThePatchIsAnything() {
        for (ClientInventory.Use use : ClientInventory.Use.values()) {
            assertEquals(ClientInventory.Outcome.UNKNOWN,
                    effects.outcome(stack("minecraft:bread", 1, List.of(VanillaUseEffects.USE_REMAINDER), List.of()), use));
        }
        assertEquals(ClientInventory.Outcome.UNKNOWN, effects.outcome(stack("othermod:thing", 1, List.of(), List.of()), ClientInventory.Use.ITEM));
    }

    @Test
    void aCompassOnALodestoneCopiesItsPatchIntoTheInventory() {
        assertEquals(ClientInventory.Outcome.KEEP_OR_NONE_ADD,
                effects.outcome(stack("minecraft:compass", 2, List.of(Stack.ATTACK_RANGE), List.of()), ClientInventory.Use.ITEM_ON_BLOCK));
        assertEquals(ClientInventory.Outcome.KEEP_OR_NONE,
                effects.outcome(stack("minecraft:compass", 2, List.of(), List.of()), ClientInventory.Use.ITEM_ON_BLOCK));
    }
}
