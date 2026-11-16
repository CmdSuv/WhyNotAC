package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
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

class ClickPatternCheckTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int SELF = 1;
    private static final int ZOMBIE = 20;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now;

    @BeforeEach
    void setUp() {
        initSession(Source.WIRE);
    }

    private void initSession(Source inbound) {
        evidence.clear();
        rejected.clear();
        seq = 0;
        now = 0;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new ClickPatternCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        session = registry.openReplay("test");
        session.deliver(new TimelineEvent.Observation(seq++, now, inbound, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.Bound(seq++, now, new UUID(1, 1), "me", SELF));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(SELF, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
        tick();
    }

    private void in(ServerboundPacket packet) {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, packet));
    }

    private void tick() {
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    private void swing(boolean attack) {
        if (attack) {
            in(new ServerboundPacket.InteractEntity(ZOMBIE, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        }
        in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
    }

    @Test
    void humanJitterClickingProducesNothing() {
        SplittableRandom rng = new SplittableRandom(77);
        for (int i = 0; i < 80; i++) {
            swing(i % 4 == 0);
            int gap = rng.nextInt(1, 4); // realistic 1..3 tick jitter
            for (int t = 0; t < gap; t++) {
                tick();
            }
        }
        assertTrue(evidence.isEmpty(), "expected no evidence for jitter clicking, got " + evidence);
        assertTrue(rejected.isEmpty());
    }

    @Test
    void miningSwingsAreExcluded() {
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK,
                new BlockPosition(0, 64, 0), BlockFace.UP, 1));
        in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        tick();
        for (int i = 0; i < 120; i++) {
            in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            tick();
        }
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK,
                new BlockPosition(0, 64, 0), BlockFace.UP, 2));
        tick();
        assertTrue(evidence.isEmpty(), "mining swings must be excluded: " + evidence);
    }

    @Test
    void slowPeriodicClickingLikeLoadBotsIsIgnored() {
        for (int i = 0; i < 80; i++) {
            swing(true);
            for (int t = 0; t < 10; t++) {
                tick();
            }
        }
        assertTrue(evidence.isEmpty(), "slow 2 CPS periodic clicking must not trigger: " + evidence);
    }

    @Test
    void constantIntervalAutoclickerIsCaught() {
        // 10.0 CPS exact constant interval: 1 swing every 2 ticks for 65 clicks
        for (int i = 0; i < 65; i++) {
            swing(i % 5 == 0);
            tick();
            tick();
        }
        assertFalse(evidence.isEmpty(), "expected constant-interval autoclicker finding");
        Evidence first = evidence.get(0);
        assertEquals(ClickPatternCheck.ID, first.check());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, first.evidenceClass());
        assertEquals("constant-interval", first.details().get("reason"));
    }

    @Test
    void periodTwoHardwareMacroIsCaught() {
        // 8.0 CPS exact 125 ms hardware macro -> alternating 2, 3, 2, 3... ticks
        for (int i = 0; i < 65; i++) {
            swing(i % 5 == 0);
            int gap = (i % 2 == 0) ? 2 : 3;
            for (int t = 0; t < gap; t++) {
                tick();
            }
        }
        assertFalse(evidence.isEmpty(), "expected period-2 macro finding");
        assertEquals("macro-periodicity", evidence.get(0).details().get("reason"));
    }

    @Test
    void periodThreeHardwareMacroIsCaught() {
        // 12.0 CPS exact ~83 ms hardware macro -> repeating 1, 2, 2, 1, 2, 2... ticks
        for (int i = 0; i < 65; i++) {
            swing(i % 5 == 0);
            int gap = (i % 3 == 0) ? 1 : 2;
            for (int t = 0; t < gap; t++) {
                tick();
            }
        }
        assertFalse(evidence.isEmpty(), "expected period-3 macro finding");
        assertEquals("macro-periodicity", evidence.get(0).details().get("reason"));
    }

    @Test
    void sustainedSuperhumanCpsIsCaught() {
        SplittableRandom rng = new SplittableRandom(7);
        for (int t = 0; t < 40; t++) {
            int count = rng.nextInt(1, 4); // randomized 20..60 CPS burst (mean ~40 CPS, non-periodic)
            for (int i = 0; i < count; i++) {
                swing(i == 0 && t % 5 == 0);
            }
            tick();
        }
        assertFalse(evidence.isEmpty(), "expected sustained CPS finding");
        assertEquals("sustained-cps", evidence.get(0).details().get("reason"));
    }

    @Test
    void inadmissibleSessionIsRejectedNotAccepted() {
        initSession(Source.GAME);
        for (int t = 0; t < 40; t++) {
            swing(t % 5 == 0);
            swing(false);
            tick();
        }
        assertTrue(evidence.isEmpty());
        assertFalse(rejected.isEmpty());
    }

    @Test
    void registeredInChecks() {
        assertEquals(ClickPatternCheck.VERSION, Checks.version(ClickPatternCheck.ID));
        assertTrue(Checks.all((s, e) -> { }, Replicas.vanilla()).stream().anyMatch(ClickPatternCheck.class::isInstance));
        assertNotNull(Checks.create(ClickPatternCheck.ID, (s, e) -> { }));
    }
}
