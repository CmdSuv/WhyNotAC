package dev.whynotac.protocol.session;

/**
 * Receives every timeline event of every session.
 *
 * <p>Calls for one session are serial and in {@link TimelineEvent#sequence()} order, but may happen
 * on different analysis threads over time (with happens-before between consecutive calls). A
 * listener may therefore keep per-session state without locking, as long as it only touches that
 * state from {@code onEvent}. Calls never run on a network thread or the server main thread.
 */
@FunctionalInterface
public interface TimelineListener {

    void onEvent(PlayerSession session, TimelineEvent event);
}
