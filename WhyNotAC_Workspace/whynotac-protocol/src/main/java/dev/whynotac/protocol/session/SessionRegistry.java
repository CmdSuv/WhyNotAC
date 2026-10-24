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

    /**
     * Mailbox units per session (an event is one unit, plus one per 128 bytes of chunk data it carries):
     * about 8 MB, room for a player's whole initial chunk burst even when analysis falls behind.
     */
    public static final int DEFAULT_MAILBOX_CAPACITY = 65_536;
    /** Units queued across all sessions (about 64 MB at the measured ~112 B per event, 128 B per unit of chunk data). */
    public static final long DEFAULT_GLOBAL_QUEUE_CAPACITY = 524_288;

    private final Executor executor;
    private final int mailboxCapacity;
    private final ErrorHandler errorHandler;
    private final List<TimelineListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSession> byPlayer = new ConcurrentHashMap<>();
    private final AtomicLong connectionIds = new AtomicLong();
    private final AtomicLong globalQueued = new AtomicLong();
    private final long globalQueueCapacity;

    public SessionRegistry(Executor executor, int mailboxCapacity, ErrorHandler errorHandler) {
        this(executor, mailboxCapacity, DEFAULT_GLOBAL_QUEUE_CAPACITY, errorHandler);
    }

    public SessionRegistry(Executor executor, int mailboxCapacity, long globalQueueCapacity, ErrorHandler errorHandler) {
        this.executor = executor;
        this.mailboxCapacity = mailboxCapacity;
        this.globalQueueCapacity = globalQueueCapacity;
        this.errorHandler = errorHandler;
    }

    /** Events currently queued for analysis across all sessions. */
    public long queuedEvents() {
        return globalQueued.get();
    }

    AtomicLong globalQueued() {
        return globalQueued;
    }

    long globalQueueCapacity() {
        return globalQueueCapacity;
    }

    public void addListener(TimelineListener listener) {
        listeners.add(listener);
    }

    public void removeListener(TimelineListener listener) {
        listeners.remove(listener);
    }

    /** Creates a session for a newly accepted connection. */
    public PlayerSession open(String remoteAddress) {
        PlayerSession session = new PlayerSession(connectionIds.incrementAndGet(), remoteAddress, this, mailboxCapacity, false);
        sessions.put(session.connectionId(), session);
        return session;
    }

    /**
     * Creates a session that is fed recorded events through {@link PlayerSession#deliver} instead of
     * live packets. Its mailbox is unbounded, because a replay must never lose an event. Use a
     * caller-runs executor ({@code Runnable::run}) for fully deterministic, single-threaded replays.
     */
    public PlayerSession openReplay(String label) {
        PlayerSession session = new PlayerSession(connectionIds.incrementAndGet(), label, this, Integer.MAX_VALUE, true);
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
