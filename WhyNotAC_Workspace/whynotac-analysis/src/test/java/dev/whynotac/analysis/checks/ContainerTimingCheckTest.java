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
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ContainerTimingCheckTest {

    private static final long TICK_NANOS = 50_000_000L;
    private static final int SELF = 1;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        initSession(Source.WIRE);
    }

    private void initSession(Source inbound) {
        evidence.clear();
        rejected.clear();
        seq = 0;
        now = 1_000_000_000L;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new ContainerTimingCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        session = registry.openReplay("container-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, inbound, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        UUID playerId = new UUID(5, 5);
        session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "looter", SELF));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(SELF, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerInfoUpdate(true, true,
                        List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerId, 0)))));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.OpenScreen(3, "minecraft:generic_9x3")));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(1)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(1)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 1, now, 0));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
        tick();
    }

    private void click(int containerId, int slot) {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.ContainerClick(containerId, 1, slot, 0, "QUICK_MOVE", List.of(), Stack.EMPTY)));
    }

    private void tick() {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        now += TICK_NANOS;
    }

    @Test
    void humanVariableChestLootingProducesZeroEvidence() {
        SplittableRandom rng = new SplittableRandom(77L);
        for (int slot = 0; slot < 12; slot++) {
            click(3, slot);
            int delay = rng.nextInt(3, 8);
            for (int t = 0; t < delay; t++) {
                tick();
            }
        }
        assertEquals(List.of(), evidence, "human-paced container looting must yield 0 findings");
    }

    @Test
    void chestStealerBurstTriggersRapidContainerTransfer() {
        for (int slot = 0; slot < 10; slot++) {
            click(3, slot);
            if (slot % 2 == 0) {
                tick();
            }
        }
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(ContainerTimingCheck.ID, e.check());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, e.evidenceClass());
        assertEquals("rapid-container-transfer", e.details().get("reason"));
    }

    @Test
    void roboticConstantDelayStealerTriggersConstantContainerCadence() {
        for (int slot = 0; slot < 16; slot++) {
            click(3, slot);
            tick();
            tick(); // exact constant 2-tick cadence (below rapid burst, exact robotic stdDev=0)
        }
        assertFalse(evidence.isEmpty());
        assertTrue(evidence.stream().anyMatch(e -> "constant-container-cadence".equals(e.details().get("reason"))));
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.getFirst().evidenceClass());
    }

    @Test
    void repeatedClicksOnSameSlotAndInadmissibleSessionsDoNotConvict() {
        for (int i = 0; i < 20; i++) {
            click(3, 4); // same slot drag/pickup spam must not count as distinct looting burst
            tick();
        }
        assertEquals(List.of(), evidence);

        initSession(Source.GAME);
        for (int slot = 0; slot < 10; slot++) {
            click(3, slot);
        }
        assertEquals(List.of(), evidence);
        assertFalse(rejected.isEmpty());
    }

    @Test
    void registeredInChecksAndZeroFalsePositivesWhenCreative() {
        assertEquals(ContainerTimingCheck.VERSION, Checks.version(ContainerTimingCheck.ID));
        assertNotNull(Checks.create(ContainerTimingCheck.ID, (s, e) -> { }));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.GameEvent(3, 1.0F))); // CHANGE_GAME_MODE -> creative (1)
        for (int slot = 0; slot < 12; slot++) {
            click(3, slot);
        }
        assertEquals(List.of(), evidence, "creative mode container interactions must be excluded");
    }
}
