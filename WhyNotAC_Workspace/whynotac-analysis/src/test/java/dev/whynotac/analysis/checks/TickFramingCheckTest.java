package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TickFramingCheckTest {

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private int nextPing = 1000;

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new TickFramingCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }));
        session = registry.openReplay("test");
    }

    // --- timeline builders ----------------------------------------------------------------------

    private void deliver(TimelineEvent event) {
        session.deliver(event);
    }

    private long next() {
        return seq++;
    }

    /** What a vanilla 774 client at the edge establishes before playing. */
    private void nativeClient() {
        deliver(new TimelineEvent.Observation(next(), 0, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(next(), 0, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        deliver(new ClientFact(next(), 0, Fact.TOPOLOGY, "DIRECT", Trust.AUTHENTIC, "test"));
        deliver(new ClientFact(next(), 0, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(next(), 0, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(next(), 0, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
    }

    private void move() {
        deliver(new TimelineEvent.Inbound(next(), seq, 0,
                new ServerboundPacket.MovePlayer(1, 2, 3, 0, 0, true, true, true, false)));
    }

    private void tickEnd() {
        deliver(new TimelineEvent.Inbound(next(), seq, 0, new ServerboundPacket.ClientTickEnd()));
    }

    private void tick() {
        move();
        tickEnd();
    }

    private int ping() {
        int id = nextPing++;
        deliver(new TimelineEvent.Outbound(next(), seq, new ClientboundPacket.Ping(id)));
        return id;
    }

    private void pong(int id) {
        deliver(new TimelineEvent.Acknowledged(next(), seq, id, 0, 0));
    }

    private void teleport() {
        deliver(new TimelineEvent.Outbound(next(), seq,
                new ClientboundPacket.PlayerPosition(7, Vec3d.ZERO, Vec3d.ZERO, 0F, 0F, Set.of())));
    }

    private void teleportReply() {
        deliver(new TimelineEvent.Inbound(next(), seq, 0, new ServerboundPacket.AcceptTeleportation(7)));
        deliver(new TimelineEvent.Inbound(next(), seq, 0,
                new ServerboundPacket.MovePlayer(0, 0, 0, 0, 0, true, true, false, false)));
    }

    // --- tests ------------------------------------------------------------------------------------

    @Test
    void aVanillaClientNeverProducesEvidence() {
        nativeClient();
        tickEnd();
        for (int t = 0; t < 1_000; t++) {
            int id = ping();
            if (t % 3 == 0) {
                pong(id); // frame start
            }
            tick();
            if (t % 7 == 0) {
                tickEnd(); // a tick without movement (standing still within 2e-4, no reminder yet)
            }
        }
        assertEquals(List.of(), evidence);
        assertEquals(List.of(), rejected);
    }

    @Test
    void twoMovementPacketsInOneTickAreAModificationProof() {
        nativeClient();
        tickEnd();
        tick();
        long start = seq - 1;
        move();
        move(); // a mace-damage hack: several positions in one tick
        tickEnd();
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("2", e.details().get("movePackets"));
        assertEquals("0", e.details().get("replyAllowance"));
        assertEquals(start, e.fromSequence());
        assertEquals(Admissibility.IdentityTier.AUTHENTICATED, e.admissibility().identity());
    }

    @Test
    void aTeleportReplyAndTheTicksOwnPacketShareAnInterval() {
        nativeClient();
        tickEnd();
        teleport();
        int id = ping();
        // frame start: the client handles the teleport (reply), then the ping (pong), then ticks
        teleportReply();
        pong(id);
        tick();
        tick();
        assertEquals(List.of(), evidence, "the reply is allowed even though its pong came before the tick's packet");
    }

    @Test
    void aRotationPacketAllowsItsReplyToo() {
        nativeClient();
        tickEnd();
        deliver(new TimelineEvent.Outbound(next(), seq, new ClientboundPacket.PlayerRotation(90F, false, 0F, false)));
        int id = ping();
        deliver(new TimelineEvent.Inbound(next(), seq, 0, new ServerboundPacket.MovePlayer(0, 0, 0, 90, 0, false, true, false, false)));
        pong(id);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void aTicksOwnPacketCannotUseUpAReplysAllowance() {
        nativeClient();
        tickEnd();
        teleport();
        tick(); // the client has not handled the teleport yet: one packet, no allowance used
        tick();
        int id = ping();
        teleportReply(); // handled now, at the start of a later frame
        pong(id);
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void theReplyAndItsPongMayArriveInDifferentFrames() {
        nativeClient();
        tickEnd();
        teleport();
        teleportReply(); // this frame handled the teleport ...
        tick();
        int id = ping();
        pong(id);        // ... and the ping only arrived for the next frame
        tick();
        assertEquals(List.of(), evidence);
    }

    @Test
    void anAllowanceExpiresWithTheIntervalInWhichItsTransactionCameBack() {
        nativeClient();
        tickEnd();
        teleport();
        int id = ping();
        pong(id);  // the client handled the teleport, but sent no reply (it was riding, say): nothing to spend
        tick();
        move();
        move();    // two packets later on: the old allowance is gone
        tickEnd();
        assertEquals(1, evidence.size());
    }

    @Test
    void oneAllowancePerServerPacket() {
        nativeClient();
        tickEnd();
        teleport();
        int id = ping();
        teleportReply();
        move();
        move();
        pong(id);
        tickEnd();
        assertEquals(1, evidence.size());
        assertEquals("3", evidence.getFirst().details().get("movePackets"));
        assertEquals("1", evidence.getFirst().details().get("replyAllowance"));
    }

    @Test
    void aFloodOfServerTeleportsSilencesTheCheckUntilItDrains() {
        nativeClient();
        tickEnd();
        for (int i = 0; i < TickFramingCheck.MAX_TOKENS + 10; i++) {
            teleport();
        }
        for (int i = 0; i < TickFramingCheck.MAX_TOKENS + 5; i++) {
            move(); // more than the allowances that were recorded
        }
        tickEnd();
        assertEquals(List.of(), evidence, "saturated: some allowances were never recorded");
        int id = ping();
        pong(id);
        tickEnd(); // the tokens expire with this interval
        move();
        move();
        tickEnd();
        assertEquals(1, evidence.size(), "silence ends once the flood has drained");
    }

    @Test
    void withoutEstablishedFactsNothingIsEvidence() {
        deliver(new TimelineEvent.Observation(next(), 0, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        tickEnd();
        move();
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
        assertEquals(1, rejected.size());
        assertTrue(rejected.getFirst().reasons().stream().anyMatch(r -> r.contains("native protocol")), rejected.toString());
    }

    @Test
    void aClaimedProtocolNeverQualifies() {
        deliver(new TimelineEvent.Observation(next(), 0, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(next(), 0, Fact.NATIVE_PROTOCOL, "774", Trust.CLAIMED, "proxy"));
        deliver(new ClientFact(next(), 0, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(next(), 0, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        tickEnd();
        move();
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
    }

    @Test
    void theGameViewTranslatedClientsAndBedrockAreInadmissible() {
        nativeClient();
        deliver(new ClientFact(next(), 0, Fact.BEDROCK, "true", Trust.AUTHENTIC, "floodgate"));
        tickEnd();
        move();
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
        assertTrue(rejected.getFirst().reasons().stream().anyMatch(r -> r.contains("Bedrock")));
    }

    @Test
    void lostEventsInsideTheWindowMakeItInadmissible() {
        nativeClient();
        tickEnd();
        move();
        deliver(new TimelineEvent.Gap(next(), seq, 3)); // three events were dropped: a reply's trigger may be among them
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
        move();
        move();
        tickEnd();
        assertEquals(1, evidence.size(), "the next window is clean again");
    }

    @Test
    void anEdgeLossInsideTheWindowMakesItInadmissible() {
        nativeClient();
        tickEnd();
        move();
        deliver(new TimelineEvent.Observation(next(), seq, Source.GAME, Source.GAME, 774, "tap removed"));
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
    }

    @Test
    void theFingerprintIsStableAcrossRuns() {
        nativeClient();
        tickEnd();
        move();
        move();
        tickEnd();
        String first = evidence.getFirst().fingerprint();
        evidence.clear();
        setUp();
        seq = 0;
        nativeClient();
        tickEnd();
        move();
        move();
        tickEnd();
        assertEquals(first, evidence.getFirst().fingerprint());
    }
}
