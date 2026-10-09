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
 * Keeps one {@link ClientEntities} per session, fed in timeline order. Register it before every listener
 * that reads the entities (listeners see an event in registration order). Other threads ask for something
 * computed from a session's entities with {@link #query}, which runs on the session's analysis thread
 * after its next event.
 */
public final class ClientEntitiesTracker implements TimelineListener {

    private static final Object KEY = new Object();

    private final Supplier<ClientEntities> factory;
    private final Map<PlayerSession, Query<?>> queries = new ConcurrentHashMap<>();
    private final ClientEntities.Totals totals = new ClientEntities.Totals();

    public ClientEntitiesTracker(EntityTypes types) {
        this.factory = () -> new ClientEntities(types, totals);
    }

    /** Counters summed over every session. Any thread. */
    public ClientEntities.Totals totals() {
        return totals;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        ClientEntities entities = session.attachment(KEY, factory);
        entities.onEvent(event);
        if (!queries.isEmpty()) {
            Query<?> query = queries.remove(session);
            if (query != null) {
                query.run(entities);
            }
        }
    }

    /** The session's entities. Only from a listener's {@code onEvent} (the session's analysis thread). */
    public ClientEntities entities(PlayerSession session) {
        return session.attachment(KEY, factory);
    }

    /** Computes {@code function} on the session's entities on its analysis thread, after its next event. */
    public <T> CompletableFuture<T> query(PlayerSession session, Function<ClientEntities, T> function) {
        Query<T> query = new Query<>(function);
        Query<?> previous = queries.put(session, query);
        if (previous != null) {
            previous.future.completeExceptionally(new IllegalStateException("replaced by a newer query"));
        }
        return query.future;
    }

    private record Query<T>(Function<ClientEntities, T> function, CompletableFuture<T> future) {
        Query(Function<ClientEntities, T> function) {
            this(function, new CompletableFuture<>());
        }

        void run(ClientEntities entities) {
            try {
                future.complete(function.apply(entities));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
    }
}
