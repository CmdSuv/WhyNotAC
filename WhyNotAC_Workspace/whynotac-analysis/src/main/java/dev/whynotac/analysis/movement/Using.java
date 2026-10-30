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
 *
 * <p>{@code used}: the ticks the use has run ({@code getUseDuration} less {@code useItemRemaining}: one each
 * {@code updatingUsingItem}), counted up to {@link #USED_KNOWN} (a trident's release reads only whether it reached 10),
 * from where the client started it: in its item actions, or where it processed the server's flags (each state takes
 * them at its own place in the packets); -1 when not known (a use guessed going on).
 */
public record Using(Hand hand, String item, OnFoot.ItemUse effects, ItemPrototypes.UseKind kind, int used) {

    /** {@code TridentItem.releaseUsing}: a riptide needs the trident used 10 ticks at least. */
    static final int USED_KNOWN = 10;

    /** {@code startUsingItem(hand)} from the living-entity flags the server sent: the item is the hand's then. */
    static Using fromServer(Hand hand) {
        return new Using(hand, null, null, null, 0);
    }

    /** {@code updatingUsingItem}: the use going on one tick more with the hand's stack. */
    Using ticked(String newItem, OnFoot.ItemUse newEffects, ItemPrototypes.UseKind newKind) {
        return new Using(hand, newItem, newEffects, newKind, used < 0 ? -1 : Math.min(used + 1, USED_KNOWN));
    }

    boolean resolved() {
        return item != null;
    }
}
