package dev.whynotac.world;

import dev.whynotac.protocol.packet.Stack;

/**
 * What a 1.21.11 client's item use may leave in the used hand, from the decompiled client: what runs
 * on the client side of {@code MultiPlayerGameMode.useItem}, {@code useItemOn} and {@code interact}.
 *
 * <p>Every use may shrink the stack, use it up, or turn it into a stack built from an item constant or
 * from the held stack (a filled bucket or bottle, a honey bottle, a remainder from the item's own
 * prototype): none of those has an attack range of its own, so the hand keeps the stack or has none
 * ({@code KEEP_OR_NONE}). What may bring a stack the replica does not follow into the hand is unknown:
 * a remainder from the stack's own patch ({@code UseRemainder.convertIntoRemainder} copies it), and an
 * equippable's swap with the player's armor ({@code Equippable.swapWithEquipmentSlot}, reached from
 * {@code Item.use}): a single item takes the armor's place, a stacked one sends the armor to the
 * inventory.
 */
public final class VanillaUseEffects implements ClientInventory.UseEffects {

    static final String USE_REMAINDER = "minecraft:use_remainder";
    static final String EQUIPPABLE = "minecraft:equippable";

    private final ItemPrototypes prototypes;

    public VanillaUseEffects(ItemPrototypes prototypes) {
        this.prototypes = prototypes;
    }

    @Override
    public ClientInventory.Outcome outcome(Stack stack, ClientInventory.Use use) {
        ItemPrototypes.Prototype prototype = prototypes.get(stack.item());
        if (prototype == null || stack.added().contains(USE_REMAINDER)) {
            return ClientInventory.Outcome.UNKNOWN;
        }
        if (use == ClientInventory.Use.ENTITY) {
            return ClientInventory.Outcome.UNKNOWN;
        }
        boolean swaps = stack.added().contains(EQUIPPABLE) || prototype.swapSlot() != null && !stack.removed().contains(EQUIPPABLE);
        if (swaps && use == ClientInventory.Use.ITEM) {
            return stack.count() <= 1 ? ClientInventory.Outcome.UNKNOWN : ClientInventory.Outcome.KEEP_OR_NONE_ADD;
        }
        if (prototype.useRemainder() != null && !stack.removed().contains(USE_REMAINDER)) {
            ItemPrototypes.Prototype into = prototypes.get(prototype.useRemainder());
            if (into == null || into.attackRange() != null) {
                return ClientInventory.Outcome.UNKNOWN;
            }
        }
        return ClientInventory.Outcome.KEEP_OR_NONE;
    }
}
