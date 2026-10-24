package dev.whynotac.analysis.movement;

import dev.whynotac.physics.OnFoot;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.world.ItemPrototypes;

/**
 * The item the client's own player is using ({@code LocalPlayer.startedUsingItem}, {@code usingItemHand} and
 * {@code LivingEntity.useItem}): the hand, the item ({@code ItemStack.isSameItem} compares only that), its
 * {@code minecraft:use_effects} and what its use does beyond them.
 *
 * <p>{@code item} null: started by the server's own flags ({@code LocalPlayer.onSyncedDataUpdated}) with whatever
 * the hand held then, resolved against the hand at the next tick.
 */
public record Using(Hand hand, String item, OnFoot.ItemUse effects, ItemPrototypes.UseKind kind) {

    /** {@code startUsingItem(hand)} from the living-entity flags the server sent: the item is the hand's then. */
    static Using fromServer(Hand hand) {
        return new Using(hand, null, null, null);
    }

    boolean resolved() {
        return item != null;
    }
}
