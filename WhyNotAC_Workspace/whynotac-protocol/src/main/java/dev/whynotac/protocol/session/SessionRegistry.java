package dev.whynotac.protocol.session;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All live sessions plus the listeners that analyse them.
 *
 * <p>Listener failures are isolated: an exception in one listener is reported to the
 * {@link ErrorHandler} and the remaining listeners still see the event. A broken check must never
 * stop other checks, and must never touch the connection.
 */
public final class SessionRegistry {

    /** Receives listener failures (never thrown back into the network or game thread). */
    @FunctionalInterface
    public interface ErrorHandler {
        void onListenerError(PlayerSession session, TimelineEvent event, Throwable error);
    }

    public static final int DEFAULT_MAILBOX_CAPACITY = 16_384;

    private final Executor executor;
    private final int mailboxCapacity;
    private final ErrorHandler errorHandler;
    private final List<TimelineListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSession> byPlayer = new ConcurrentHashMap<>();
    private final AtomicLong connectionIds = new AtomicLong();

    public SessionRegistry(Executor executor, int mailboxCapacity, ErrorHandler errorHandler) {
        this.executor = executor;
        this.mailboxCapacity = mailboxCapacity;
        this.errorHandler = errorHandler;
    }

    public void addListener(TimelineListener listener) {
        listeners.add(listener);
    }

    public void removeListener(TimelineListener listener) {
        listeners.remove(listener);
    }

    /** Creates a session for a newly accepted connection. */
    public PlayerSession open(String remoteAddress) {
        PlayerSession session = new PlayerSession(connectionIds.incrementAndGet(), remoteAddress, this, mailboxCapacity);
        sessions.put(session.connectionId(), session);
        return session;
    }

    public Optional<PlayerSession> byPlayer(UUID playerId) {
        return Optional.ofNullable(byPlayer.get(playerId));
    }

    public Collection<PlayerSession> sessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }

    Executor executor() {
        return executor;
    }

    /** Runs on the analysis executor, in per-session order. */
    void dispatch(PlayerSession session, TimelineEvent event) {
        if (event instanceof TimelineEvent.Bound bound) {
            byPlayer.put(bound.playerId(), session);
        }
        for (TimelineListener listener : listeners) {
            try {
                listener.onEvent(session, event);
            } catch (RuntimeException | LinkageError | AssertionError error) {
                errorHandler.onListenerError(session, event, error);
            }
        }
        if (event instanceof TimelineEvent.Disconnected) {
            sessions.remove(session.connectionId(), session);
            PlayerSession.Identity identity = session.identity();
            if (identity != null) {
                byPlayer.remove(identity.playerId(), session); // a quick reconnect may already own the mapping
            }
        }
    }
}
