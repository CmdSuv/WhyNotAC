package dev.whynotac.protocol.session;

import dev.whynotac.protocol.packet.ClientboundPacket;

/**
 * Receives every timeline event of every session (but the {@link #entityMotion entity motion} a listener does not read).
 *
 * <p>Calls for one session are serial and in {@link TimelineEvent#sequence()} order, but may happen
 * on different analysis threads over time (with happens-before between consecutive calls). A
 * listener may therefore keep per-session state without locking, as long as it only touches that
 * state from {@code onEvent}. Calls never run on a network thread or the server main thread.
 */
@FunctionalInterface
public interface TimelineListener {

    void onEvent(PlayerSession session, TimelineEvent event);

    /**
     * Whether this listener reads {@link #entityMotion entity motion}. One that does not never receives it, so it must
     * neither read those packets nor do anything for every event or every outbound packet (count them, keep the last
     * sequence): everything else of the timeline still reaches it, in order.
     */
    default boolean readsEntityMotion() {
        return true;
    }

    /**
     * The packets that move or turn an entity the client sees, about half of all events with players and mobs around
     * ({@code MoveEntity}, {@code RotateHead}, {@code SetEntityMotion}, {@code EntityPositionSync}); the entity replica
     * reads them, most checks do not.
     */
    static boolean entityMotion(TimelineEvent event) {
        return event instanceof TimelineEvent.Outbound out && (out.packet() instanceof ClientboundPacket.MoveEntity
                || out.packet() instanceof ClientboundPacket.RotateHead || out.packet() instanceof ClientboundPacket.SetEntityMotion
                || out.packet() instanceof ClientboundPacket.EntityPositionSync);
    }
}
