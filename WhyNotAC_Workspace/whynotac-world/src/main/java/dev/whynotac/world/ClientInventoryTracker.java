package dev.whynotac.world;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps one {@link ClientInventory} per session, fed in timeline order. Register it before every listener
 * that reads the inventory (listeners see an event in registration order). Other threads ask for something
 * computed from a session's inventory with {@link #query}, which runs on the session's analysis thread
 * after its next event.
 */
public final class ClientInventoryTracker implements TimelineListener {

    private static final PlayerSession.AttachmentKey KEY = new PlayerSession.AttachmentKey();

    private final Supplier<ClientInventory> factory;
    private final Map<PlayerSession, Query<?>> queries = new ConcurrentHashMap<>();

    public ClientInventoryTracker(ItemPrototypes prototypes, MenuLayouts layouts) {
        ClientInventory.UseEffects uses = new VanillaUseEffects(prototypes);
        this.factory = () -> new ClientInventory(prototypes, layouts, uses);
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        ClientInventory inventory = session.attachment(KEY, factory);
        inventory.onEvent(event);
        if (!queries.isEmpty()) {
            Query<?> query = queries.remove(session);
            if (query != null) {
                query.run(inventory);
            }
        }
    }

    /** The session's inventory. Only from a listener's {@code onEvent} (the session's analysis thread). */
    public ClientInventory inventory(PlayerSession session) {
        return session.attachment(KEY, factory);
    }

    /** Computes {@code function} on the session's inventory on its analysis thread, after its next event. */
    public <T> CompletableFuture<T> query(PlayerSession session, Function<ClientInventory, T> function) {
        Query<T> query = new Query<>(function);
        Query<?> previous = queries.put(session, query);
        if (previous != null) {
            previous.future.completeExceptionally(new IllegalStateException("replaced by a newer query"));
        }
        return query.future;
    }

    private record Query<T>(Function<ClientInventory, T> function, CompletableFuture<T> future) {
        Query(Function<ClientInventory, T> function) {
            this(function, new CompletableFuture<>());
        }

        void run(ClientInventory inventory) {
            try {
                future.complete(function.apply(inventory));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
    }
}
