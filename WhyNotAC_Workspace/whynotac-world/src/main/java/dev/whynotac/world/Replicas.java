package dev.whynotac.world;

import dev.whynotac.protocol.session.TimelineListener;
import java.util.List;

/**
 * Every replica of what one client holds, as one set: its world, the entities it sees and its own
 * inventory. Checks that read them take the set; the plugin registers its trackers before the checks
 * (listeners see an event in registration order), and {@code verify} builds a fresh set to re-run a check
 * over a recording.
 */
public final class Replicas {

    private final ClientWorldTracker world;
    private final ClientEntitiesTracker entities;
    private final ClientInventoryTracker inventory;

    public Replicas(ClientWorldTracker world, ClientEntitiesTracker entities, ClientInventoryTracker inventory) {
        this.world = world;
        this.entities = entities;
        this.inventory = inventory;
    }

    /** The 1.21.11 replicas. */
    public static Replicas vanilla() {
        return new Replicas(new ClientWorldTracker(BlockStates.vanilla()), new ClientEntitiesTracker(EntityTypes.vanilla()),
                new ClientInventoryTracker(ItemPrototypes.vanilla(), MenuLayouts.vanilla()));
    }

    /** The trackers, in the order to register them. */
    public List<TimelineListener> listeners() {
        return List.of(world, entities, inventory);
    }

    public ClientWorldTracker world() {
        return world;
    }

    public ClientEntitiesTracker entities() {
        return entities;
    }

    public ClientInventoryTracker inventory() {
        return inventory;
    }
}
