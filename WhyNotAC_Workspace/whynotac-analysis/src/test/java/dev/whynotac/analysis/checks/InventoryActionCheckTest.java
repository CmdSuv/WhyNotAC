package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class InventoryActionCheckTest {

    private final List<Evidence> evidence = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        seq = 0;
        now = 1_000_000_000L;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new InventoryActionCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {}
        }, replicas));
        session = registry.openReplay("inv-protocol-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        UUID playerId = new UUID(6, 6);
        session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "invUser", 1));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(1, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
    }

    @Test
    void honestInventoryClickAndCloseBeforeActionProducesZeroEvidence() {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, 36, 0, "QUICK_MOVE", List.of(), Stack.EMPTY)));
        // Closing container in the same tick allows subsequent keybind action
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ContainerClose(0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.PlayerInput(true, false, false, false, false, false, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        assertTrue(evidence.isEmpty());
    }

    @Test
    void detectsInvWalkAndAutoTotemAttackInSameTickWithoutClose() {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(0, 1, 45, 0, "SWAP", List.of(), Stack.EMPTY)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        assertEquals(1, evidence.size());
        assertEquals("action-during-container-click", evidence.get(0).details().get("reason"));
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.get(0).evidenceClass());

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.ContainerClick(0, 1, 9, 0, "PICKUP", List.of(), Stack.EMPTY)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.PlayerInput(true, false, false, false, false, false, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));
        assertEquals(2, evidence.size());
        assertEquals("walk-during-container-click", evidence.get(1).details().get("reason"));
    }

    @Test
    void detectsUnopenedContainerClickAndAllowsOpenedThenClosedFlow() {
        // Clicking containerId=3 without OpenScreen is impossible in vanilla
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(3, 1, 5, 0, "PICKUP", List.of(), Stack.EMPTY)));
        assertEquals(1, evidence.size());
        assertEquals("unopened-container-click", evidence.get(0).details().get("reason"));
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.get(0).evidenceClass());

        // Server opens containerId=4 -> click allowed -> client closes -> subsequent click flagged again
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.OpenScreen(4, "minecraft:generic_9x3")));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(4, 1, 5, 0, "PICKUP", List.of(), Stack.EMPTY)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ContainerClose(4)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        assertEquals(1, evidence.size());

        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(4, 1, 5, 0, "PICKUP", List.of(), Stack.EMPTY)));
        assertEquals(2, evidence.size());
        assertEquals("unopened-container-click", evidence.get(1).details().get("reason"));
    }

    @Test
    void inFlightServerCloseRespawnAndSprintEchoProduceZeroEvidence() {
        // 1. Server sends ClientboundContainerClose(5) -> before ack, client's in-flight ContainerClick(5) arrives,
        // then client processes server close (no ServerboundContainerClose sent) and walks in the same tick.
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.OpenScreen(5, "minecraft:generic_9x3")));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.ContainerClose(5)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(5, 1, 2, 0, "PICKUP", List.of(), Stack.EMPTY)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.PlayerInput(true, false, false, false, false, false, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        assertTrue(evidence.isEmpty(), () -> "In-flight server ContainerClose before ack must not flag: " + evidence);

        // 2. Server opens container 6, then sends Respawn -> client's in-flight ContainerClick(6) before Respawn ack must not flag.
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.OpenScreen(6, "minecraft:generic_9x3")));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Respawn(new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.ContainerClick(6, 1, 2, 0, "PICKUP", List.of(), Stack.EMPTY)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));
        assertTrue(evidence.isEmpty(), () -> "In-flight ContainerClick before Respawn ack must not flag: " + evidence);

        // Acknowledge respawn via ping so container 6 is now truly closed
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(55)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.Pong(55)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 55, now, 0));

        // 3. Self SetEntityData sprint echo during inventory click suppresses START_SPRINTING in the same tick
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.SetEntityData(
                1, List.of(new ClientboundPacket.SetEntityData.DataEntry(0, ClientboundPacket.SetEntityData.Kind.BYTE, 0x08L)), 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2,
                new ServerboundPacket.ContainerClick(0, 1, 9, 0, "PICKUP", List.of(), Stack.EMPTY)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2, new ServerboundPacket.ClientTickEnd()));
        assertTrue(evidence.isEmpty(), () -> "Self sprint flag echo during ContainerClick must not flag: " + evidence);
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(InventoryActionCheck.VERSION, Checks.version(InventoryActionCheck.ID));
        assertNotNull(Checks.create(InventoryActionCheck.ID, (s, e) -> { }));
        assertFalse(new InventoryActionCheck((s, e) -> { }, Replicas.vanilla()).readsEntityMotion());
    }
}
