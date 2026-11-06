package dev.whynotac.world;

import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;

/**
 * Resolves the client's dig predictions where the replicas decide them, as they hold the client when the dig comes
 * (decompiled 1.21.11 {@code MultiPlayerGameMode}):
 * <ul>
 *   <li>a {@code START_DESTROY_BLOCK} outside creative (whose branch breaks at once) that certainly only started the dig
 *       ({@link Digging#certainlyStartsOnly}) set no block ({@link ClientWorld#resolveDigStartedOnly}). A held attack
 *       button walked along a wall starts a dig at each block the crosshair meets: none of those changes the client's
 *       world;</li>
 *   <li>a {@code STOP_DESTROY_BLOCK} of a client in survival ({@code blockActionRestricted} passes: not adventure or
 *       spectator) whose {@code instabuild} is false ({@code Item.canDestroyBlock} passes) broke the block to what
 *       {@link Digging#brokenTo} gives ({@link ClientWorld#resolveDigBroken}): a player mining a tunnel walks into air.</li>
 * </ul>
 * Registered after the world, inventory, self and tag replicas ({@link Replicas#listeners}).
 */
final class DigPredictions implements TimelineListener {

    private final ClientWorldTracker world;
    private final ClientInventoryTracker inventory;
    private final ClientSelfTracker self;
    private final ClientTagsTracker tags;

    DigPredictions(ClientWorldTracker world, ClientInventoryTracker inventory, ClientSelfTracker self, ClientTagsTracker tags) {
        this.world = world;
        this.inventory = inventory;
        this.self = self;
        this.tags = tags;
    }

    /** It reads no entity motion. */
    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        if (!(event instanceof TimelineEvent.Inbound in) || !(in.packet() instanceof ServerboundPacket.PlayerAction action)) {
            return;
        }
        boolean start = action.action() == ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK;
        if (!start && action.action() != ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK) {
            return;
        }
        ClientWorld w = world.world(session);
        int[] before = w.digBefore(action.sequence());
        ClientSelf player = self.self(session);
        if (before == null || w.hasUnlocatedPredictionBefore(action.sequence())) {
            return;
        }
        ClientInventory hands = inventory.inventory(session);
        if (start) {
            if (!player.mayBeCreativeOrSpectator() && Digging.certainlyStartsOnly(w.states(), before, hands.mainHandStacks(),
                    player.miningBounds(), Digging.TagLookupByBlock.of(tags.tags(session)), ItemPrototypes.vanilla())) {
                w.resolveDigStartedOnly(action.sequence());
            }
        } else if (!player.mayRestrictBlockActionsOrBeCreative() && !hands.mayInstabuild()) {
            int broken = Digging.brokenTo(w.states(), before, hands.mainHandStacks());
            if (broken >= 0) {
                w.resolveDigBroken(action.sequence(), broken);
            }
        }
    }
}
