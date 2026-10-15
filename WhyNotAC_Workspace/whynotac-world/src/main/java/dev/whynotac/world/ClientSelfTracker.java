package dev.whynotac.world;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Keeps one {@link ClientSelf} per session, fed in timeline order. Register it before every listener that
 * reads the client's own player (listeners see an event in registration order). Other threads ask for
 * something computed from it with {@link #query}, which runs on the session's analysis thread after its
 * next event.
 */
public final class ClientSelfTracker implements TimelineListener {

    private static final Object KEY = new Object();

    private final Map<PlayerSession, Query<?>> queries = new ConcurrentHashMap<>();

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        ClientSelf self = session.attachment(KEY, ClientSelf::new);
        self.onEvent(event);
        if (!queries.isEmpty()) {
            Query<?> query = queries.remove(session);
            if (query != null) {
                query.run(self);
            }
        }
    }

    /** The session's own player. Only from a listener's {@code onEvent} (the session's analysis thread). */
    public ClientSelf self(PlayerSession session) {
        return session.attachment(KEY, ClientSelf::new);
    }

    /** Computes {@code function} on the session's own player on its analysis thread, after its next event. */
    public <T> CompletableFuture<T> query(PlayerSession session, Function<ClientSelf, T> function) {
        Query<T> query = new Query<>(function);
        Query<?> previous = queries.put(session, query);
        if (previous != null) {
            previous.future.completeExceptionally(new IllegalStateException("replaced by a newer query"));
        }
        return query.future;
    }

    private record Query<T>(Function<ClientSelf, T> function, CompletableFuture<T> future) {
        Query(Function<ClientSelf, T> function) {
            this(function, new CompletableFuture<>());
        }

        void run(ClientSelf self) {
            try {
                future.complete(function.apply(self));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        }
    }
}
