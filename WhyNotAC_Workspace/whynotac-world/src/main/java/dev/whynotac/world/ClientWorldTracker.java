package dev.whynotac.world;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps one {@link ClientWorld} per session, fed in timeline order. Register it before every listener
 * that reads the world: listeners see an event in registration order, so those later ones see the
 * world with the current event already applied.
 *
 * <p>Other threads (a staff command) can ask for something computed from a session's world with
 * {@link #query}; it runs on the session's analysis thread with its next event, so it never races the
 * replica. Every live session receives a transaction every server tick, so the answer is prompt.
 */
public final class ClientWorldTracker implements TimelineListener {

    private static final PlayerSession.AttachmentKey KEY = new PlayerSession.AttachmentKey();

    private final Supplier<ClientWorld> factory;
    private final Map<PlayerSession, Query<?>> queries = new ConcurrentHashMap<>();
    private final SectionPool pool = new SectionPool();
    private final LongAdder worlds = new LongAdder();

    public ClientWorldTracker(BlockStates states) {
        this.factory = () -> { // one capturing lambda, not one per event
            worlds.increment();
            return new ClientWorld(states, pool);
        };
    }

    /** Block data held for every session's world (bytes): each distinct section once. Any thread. */
    public long retainedBytes() {
        return pool.uniqueBytes();
    }

    /** Distinct shared sections. Any thread. */
    public int sharedSections() {
        return pool.size();
    }

    /** Worlds created since start. Any thread. */
    public long worldsCreated() {
        return worlds.sum();
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        ClientWorld world = session.attachment(KEY, factory);
        world.onEvent(event);
        if (!queries.isEmpty()) {
            Query<?> query = queries.remove(session);
            if (query != null) {
                query.run(world);
            }
        }
    }

    /** The session's world. Only from a listener's {@code onEvent} (the session's analysis thread). */
    public ClientWorld world(PlayerSession session) {
        return session.attachment(KEY, factory);
    }

    /**
     * Computes {@code function} on the session's world on its analysis thread, after its next event. A
     * second query for the same session before the first ran replaces it (the first completes
     * exceptionally).
     */
    public <T> CompletableFuture<T> query(PlayerSession session, Function<ClientWorld, T> function) {
        Query<T> query = new Query<>(function);
        Query<?> previous = queries.put(session, query);
        if (previous != null) {
            previous.future.completeExceptionally(new IllegalStateException("replaced by a newer query"));
        }
        return query.future;
    }

    private record Query<T>(Function<ClientWorld, T> function, CompletableFuture<T> future) {
        Query(Function<ClientWorld, T> function) {
            this(function, new CompletableFuture<>());
        }

        void run(ClientWorld world) {
            try {
                future.complete(function.apply(world));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
    }
}
