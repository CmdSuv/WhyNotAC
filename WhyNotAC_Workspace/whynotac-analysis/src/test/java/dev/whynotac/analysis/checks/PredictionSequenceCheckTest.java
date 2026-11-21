package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The check over the replicas it reads, fed a timeline as the plugin would. */
class PredictionSequenceCheckTest {

    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final ClientboundPacket.Dimension NETHER = new ClientboundPacket.Dimension("minecraft:the_nether", 0, 256);
    private static final BlockPosition BLOCK = new BlockPosition(2, 65, 0);
    private static final long SECOND = 1_000_000_000L;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now;
    private int pingId;

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new PredictionSequenceCheck(new EvidenceSink() {
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
        deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        deliver(new TimelineEvent.Bound(seq++, now, new UUID(7, 7), "me", 1));
        out(new ClientboundPacket.Login(1, 8, OVERWORLD, 0));
        sync();
    }

    private void deliver(TimelineEvent event) {
        session.deliver(event);
    }

    private void out(ClientboundPacket packet) {
        deliver(new TimelineEvent.Outbound(seq++, now, packet));
    }

    private void in(ServerboundPacket packet) {
        deliver(new TimelineEvent.Inbound(seq++, now, 0, packet));
    }

    private void sync() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        in(new ServerboundPacket.Pong(id));
        deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
    }

    private void action(ServerboundPacket.PlayerAction.Action action, int sequence) {
        in(new ServerboundPacket.PlayerAction(action, BLOCK, BlockFace.WEST, sequence));
    }

    private void start(int sequence) {
        action(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, sequence);
    }

    /** {@code numbers} predicted packets in turn: starts, finishes, clicks and uses. */
    private void predict(int... numbers) {
        for (int i = 0; i < numbers.length; i++) {
            switch (i % 4) {
                case 0 -> start(numbers[i]);
                case 1 -> action(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, numbers[i]);
                case 2 -> in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, BLOCK, BlockFace.UP, new Vec3d(2.5, 66.0, 0.5), false, false, numbers[i]));
                default -> in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, numbers[i], 0F, 0F));
            }
        }
    }

    /** A second later: the next finding is not held back by the last. */
    private void later() {
        now += 2 * SECOND;
    }

    @Test
    void theVanillaClientsNumbersOpenNothing() {
        predict(1, 2, 3, 4, 5);
        action(ServerboundPacket.PlayerAction.Action.ABORT_DESTROY_BLOCK, 0);
        action(ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM, 0);
        action(ServerboundPacket.PlayerAction.Action.DROP_ITEM, 0);
        action(ServerboundPacket.PlayerAction.Action.SWAP_ITEM_WITH_OFFHAND, 0);
        action(ServerboundPacket.PlayerAction.Action.STAB, 0);
        predict(6, 7, 8);
        assertEquals(List.of(), evidence);
        assertEquals(List.of(), rejected);
    }

    @Test
    void aPredictedPacketNumberedZeroOrAgainIsNotTheClients() {
        start(0); // a packet built with the constructor that leaves the number out
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(PredictionSequenceCheck.ID, e.check());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("START_DESTROY_BLOCK", e.details().get("packet"));
        assertEquals("1 or more", e.details().get("expected"));
        later();
        predict(1, 2);
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, BLOCK, BlockFace.UP, new Vec3d(2.5, 66.0, 0.5), false, false, 2));
        assertEquals(2, evidence.size());
        assertEquals("3", evidence.get(1).details().get("expected"));
        assertEquals("same level", evidence.get(1).details().get("level"));
        predict(3); // it goes on from what was sent
        assertEquals(2, evidence.size());
    }

    @Test
    void anActionThatPredictsNothingCarriesZero() {
        action(ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM, 5);
        assertEquals(1, evidence.size());
        assertEquals("RELEASE_USE_ITEM", evidence.getFirst().details().get("packet"));
        assertEquals("0", evidence.getFirst().details().get("expected"));
        assertEquals("unpredicted", evidence.getFirst().details().get("level"));
    }

    @Test
    void aRespawnIntoAnotherDimensionStartsOverOnceTheClientProcessedIt() {
        predict(1, 2);
        out(new ClientboundPacket.Respawn(NETHER, 0, 0));
        predict(3); // sent before it processed the respawn
        predict(1, 2); // after
        assertEquals(List.of(), evidence);
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0));
        sync();
        start(3);
        assertEquals(1, evidence.size(), "processed for sure: a new level");
        assertEquals("1", evidence.getFirst().details().get("expected"));
        assertEquals("new level", evidence.getFirst().details().get("level"));
    }

    @Test
    void theNumbersBeforeTheRespawnSayWhichLevelTheClientWasIn() {
        predict(1, 2);
        out(new ClientboundPacket.Respawn(NETHER, 0, 0));
        predict(3); // the old level: the respawn was not processed yet
        sync();
        start(4);
        assertEquals(1, evidence.size(), "it has processed it since: 1");
        later();
        predict(5); // on from what it sent
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0));
        predict(1); // the new level
        sync();
        start(1);
        assertEquals(2, evidence.size(), "the new level goes on: 2");
        assertEquals("2", evidence.get(1).details().get("expected"));
    }

    @Test
    void numbersStartedOverAndGoneOnStayInTheNewLevel() {
        predict(1, 2);
        out(new ClientboundPacket.Respawn(NETHER, 0, 0));
        predict(1, 2); // started over before the respawn was answered for: the new level
        sync();
        start(1);
        assertEquals(1, evidence.size(), "no level since: 3");
        assertEquals("3", evidence.getFirst().details().get("expected"));
    }

    @Test
    void aPacketNotUnderstoodMayHaveBeenARespawn() {
        predict(1, 2);
        out(new ClientboundPacket.Untranslatable("minecraft:respawn"));
        sync();
        predict(1);
        assertEquals(List.of(), evidence);
        assertEquals(List.of(), rejected, "not even a finding held back for the loss");
    }

    @Test
    void aRespawnInTheSameDimensionKeepsTheLevel() {
        predict(1, 2);
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0));
        sync();
        predict(3);
        assertEquals(List.of(), evidence);
        start(1);
        assertEquals(1, evidence.size());
    }

    @Test
    void everyLoginIsANewLevel() {
        predict(1, 2);
        out(new ClientboundPacket.Login(1, 8, OVERWORLD, 0)); // after a reconfiguration
        sync();
        predict(1, 2);
        assertEquals(List.of(), evidence);
        out(new ClientboundPacket.Login(1, 8, OVERWORLD, 0));
        sync();
        start(3);
        assertEquals(1, evidence.size());
    }

    @Test
    void aDimensionNotKnownMayOrMayNotStartOver() {
        predict(1, 2);
        out(new ClientboundPacket.Respawn(null, 0, 0));
        sync();
        predict(3);
        out(new ClientboundPacket.Respawn(null, 0, 0));
        sync();
        predict(1);
        out(new ClientboundPacket.Untranslatable("minecraft:respawn"));
        sync();
        predict(1);
        assertEquals(List.of(), evidence);
    }

    @Test
    void lostEventsOrAnUnreadablePacketStartTheCountOver() {
        predict(1, 2);
        deliver(new TimelineEvent.Gap(seq++, now, 4));
        predict(9, 10);
        in(new ServerboundPacket.Untranslatable("minecraft:use_item_on"));
        predict(12);
        action(ServerboundPacket.PlayerAction.Action.UNKNOWN, 13);
        predict(14);
        assertEquals(List.of(), evidence);
        predict(14);
        assertEquals(1, evidence.size());
    }

    @Test
    void findingsAreAtMostOneASecondAndCountWhatCameBetween() {
        predict(1, 1, 1);
        assertEquals(1, evidence.size());
        later();
        predict(1);
        assertEquals(2, evidence.size());
        assertEquals("2", evidence.get(1).details().get("misnumbered"), "the one held back and this one");
    }

    @Test
    void registeredWithTheOtherChecks() {
        assertEquals(1, Checks.all((s, e) -> { }, Replicas.vanilla()).stream().filter(PredictionSequenceCheck.class::isInstance).count());
        assertNotNull(Checks.create(PredictionSequenceCheck.ID, (s, e) -> { }));
    }
}
