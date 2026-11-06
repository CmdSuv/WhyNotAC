package dev.whynotac.interop;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** The real thing: PacketEvents' own silent send and packet cancellation. */
final class PacketEventsEdge implements InteropProbePlugin.Edge {

    private final Logger logger;
    /** Entity id whose next velocity packet is cancelled, or -1. */
    private final AtomicInteger cancelFor = new AtomicInteger(-1);
    private final PacketListenerCommon listener;

    PacketEventsEdge(Logger logger) {
        this.logger = logger;
        this.listener = PacketEvents.getAPI().getEventManager().registerListener(new PacketListener() {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                if (event.getPacketType() != PacketType.Play.Server.ENTITY_VELOCITY) {
                    return;
                }
                int target = cancelFor.get();
                if (target >= 0 && new WrapperPlayServerEntityVelocity(event).getEntityId() == target && cancelFor.compareAndSet(target, -1)) {
                    event.setCancelled(true);
                    logger.info("[wnprobe] packetevents cancelled a velocity packet");
                }
            }
        }, PacketListenerPriority.NORMAL);
    }

    @Override
    public String name() {
        return "packetevents";
    }

    @Override
    public void inject(Player player, Vector v) {
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player,
                new WrapperPlayServerEntityVelocity(player.getEntityId(), new Vector3d(v.getX(), v.getY(), v.getZ())));
    }

    @Override
    public void cancelNextVelocity(Player player) {
        cancelFor.set(player.getEntityId());
    }

    @SuppressWarnings("unused")
    private PacketListenerCommon listener() {
        return listener;
    }
}
