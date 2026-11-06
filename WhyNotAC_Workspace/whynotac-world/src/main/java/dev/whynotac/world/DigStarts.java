package dev.whynotac.world;

import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;

/**
 * Resolves the client's {@code START_DESTROY_BLOCK} predictions that set no block ({@link ClientWorld#resolveDigStartedOnly}):
 * outside creative ({@code MultiPlayerGameMode.startDestroyBlock}'s creative branch breaks at once), where the dig
 * certainly only started ({@link Digging#certainlyStartsOnly}) for the block the client may have held, its main hand and
 * its mining effects and attributes as the replicas hold them when the dig comes. A held attack button walked along a
 * wall starts a dig at each block the crosshair meets: none of those changes the client's world.
 * Registered after the world, inventory, self and tag replicas ({@link Replicas#listeners}).
 */
final class DigStarts implements TimelineListener {

    private final ClientWorldTracker world;
    private final ClientInventoryTracker inventory;
    private final ClientSelfTracker self;
    private final ClientTagsTracker tags;

    DigStarts(ClientWorldTracker world, ClientInventoryTracker inventory, ClientSelfTracker self, ClientTagsTracker tags) {
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
        if (!(event instanceof TimelineEvent.Inbound in) || !(in.packet() instanceof ServerboundPacket.PlayerAction action)
                || action.action() != ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK) {
            return;
        }
        ClientWorld w = world.world(session);
        int[] before = w.digBefore(action.sequence());
        ClientSelf player = self.self(session);
        if (before == null || player.mayBeCreativeOrSpectator() || w.hasUnlocatedPredictionBefore(action.sequence())) {
            return;
        }
        if (Digging.certainlyStartsOnly(w.states(), before, inventory.inventory(session).mainHandStacks(), player.miningBounds(),
                Digging.TagLookupByBlock.of(tags.tags(session)), ItemPrototypes.vanilla())) {
            w.resolveDigStartedOnly(action.sequence());
        }
    }
}
