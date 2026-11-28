package dev.whynotac.paper.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class WireObserverPlacementTest {

    private static RegistryAccess registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void eventDrivenSelfRepairMovesTapsBackAndEnforcesFourPerSecondRateLimit() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, err) -> { throw new AssertionError(err); });
        PlayerSession session = registry.open("placement-test");
        WireObserver observer = new WireObserver(session, new PacketTranslator(), new WireProtocols(() -> registries),
                Logger.getLogger("placement-test"), 774, WireObserver.DEFAULT_GRACE_NANOS);
        observer.startInPlay(1_000L);

        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        WireTaps.install(ch.pipeline(), observer, Logger.getLogger("placement-test"));
        assertFalse(WireTaps.isMisplaced(ch.pipeline()));

        // Simulate 6 rapid third-party plugin insertions right after splitter within 200ms (< 1s window)
        long t0 = 10_000_000_000L;
        int repairedCount = 0;
        for (int i = 0; i < 6; i++) {
            ch.pipeline().addAfter("splitter", "rival_plugin_" + i, new ChannelInboundHandlerAdapter());
            assertTrue(WireTaps.isMisplaced(ch.pipeline()));
            if (WireTaps.ensurePlacementRateLimited(ch.pipeline(), t0 + i * 20_000_000L)) {
                repairedCount++;
            }
        }
        assertEquals(WireObserver.MAX_REPAIRS_PER_SECOND, repairedCount,
                "Event-driven self-repair must cap at 4 repairs/s per session to prevent ping-pong loops");
        assertEquals(4L, observer.status().placementRepairs());
        assertEquals(2L, observer.placementRepairRateLimited());

        // After 1 second window elapses, next insertion repairs cleanly again and moves wire_in right after splitter
        assertTrue(WireTaps.ensurePlacementRateLimited(ch.pipeline(), t0 + 1_100_000_000L));
        assertFalse(WireTaps.isMisplaced(ch.pipeline()));
        List<String> names = ch.pipeline().names();
        assertEquals(names.indexOf("splitter") + 1, names.indexOf(WireTaps.INBOUND));
        assertEquals(5L, observer.status().placementRepairs());
        ch.finishAndReleaseAll();
    }

    @Test
    void configurableIgnoredReconcilePacketsSuppressGameOnlyDivergencesWhileRetainingDefaults() {
        List<TimelineEvent> events = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 64, (s, e, err) -> { throw new AssertionError(err); });
        registry.addListener((s, e) -> events.add(e));
        PlayerSession session = registry.open("reconcile-ignore-test");
        WireObserver observer = new WireObserver(session, new PacketTranslator(), new WireProtocols(() -> registries),
                Logger.getLogger("reconcile-ignore-test"), 774, WireObserver.DEFAULT_GRACE_NANOS);
        observer.startInPlay(1_000L);

        observer.setIgnoredReconcilePackets(List.of("custom:plugin_packet"));
        assertTrue(observer.ignoredReconcilePackets().contains("minecraft:set_creative_mode_slot"),
                "Vanilla default ignored packet must always remain protected");
        assertTrue(observer.ignoredReconcilePackets().contains("custom:plugin_packet"));

        // Ignored custom packet arriving only at game side produces zero divergence
        observer.onGameInbound("custom:plugin_packet", new ServerboundPacket.Unmodeled("custom:plugin_packet"), 2_000L);
        assertEquals(0L, observer.status().inboundGameOnly());

        // Non-ignored packet arriving only at game side still produces a GAME_ONLY divergence
        observer.onGameInbound("minecraft:swing", new ServerboundPacket.Unmodeled("minecraft:swing"), 3_000L);
        assertEquals(1L, observer.status().inboundGameOnly());
        assertTrue(events.stream().anyMatch(e -> e instanceof TimelineEvent.InboundDivergence));
    }
}
