package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionOrderCheckTest {

    private static final long SECOND = 1_000_000_000L;

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
        registry.addListener(new TransactionOrderCheck(new EvidenceSink() {
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
        session.deliver(new TimelineEvent.Observation(seq++, now, inbound, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        session.deliver(new TimelineEvent.Bound(seq++, now, new UUID(3, 3), "me", 1));
        out(new ClientboundPacket.Login(1, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0));
        in(new ServerboundPacket.PlayerLoaded());
        tick();
    }

    private void out(ClientboundPacket packet) {
        session.deliver(new TimelineEvent.Outbound(seq++, now, packet));
    }

    private void in(ServerboundPacket packet) {
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, packet));
    }

    private void pong(int id) {
        in(new ServerboundPacket.Pong(id));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
    }

    private void tick() {
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    @Test
    void vanillaCausalityProducesNothingEvenThroughTenTickFrameCatchup() {
        out(new ClientboundPacket.Ping(10));
        out(new ClientboundPacket.KeepAlive(100L));
        in(new ServerboundPacket.KeepAlive(100L));
        // Client runs up to 10 catch-up ticks in the active frame before next frame drains Ping(10)
        for (int t = 0; t < 10; t++) {
            tick();
        }
        pong(10);
        // Teleport sent after Ping(11): Ping(11) answered before AcceptTeleportation
        out(new ClientboundPacket.Ping(11));
        out(new ClientboundPacket.PlayerPosition(5, Vec3d.ZERO, Vec3d.ZERO, 0F, 0F, Set.of()));
        pong(11);
        in(new ServerboundPacket.AcceptTeleportation(5));
        tick();
        assertTrue(evidence.isEmpty(), "vanilla order must produce no evidence: " + evidence);
        assertTrue(rejected.isEmpty());
    }

    @Test
    void skippedPingSupersededByNewerPongDoesNotTriggerKeepAliveRule() {
        out(new ClientboundPacket.Ping(1));
        out(new ClientboundPacket.Ping(2));
        pong(2); // skips Ping(1)
        out(new ClientboundPacket.KeepAlive(99L));
        in(new ServerboundPacket.KeepAlive(99L));
        for (int t = 0; t < 20; t++) {
            tick();
        }
        assertTrue(evidence.isEmpty(), "skipped ping cleared by newer pong must not trigger: " + evidence);
    }

    @Test
    void clientFreezeWithoutTicksDoesNotTrigger() {
        out(new ClientboundPacket.Ping(1));
        out(new ClientboundPacket.KeepAlive(10L));
        in(new ServerboundPacket.KeepAlive(10L));
        now += 3 * SECOND; // frozen for 3 seconds: no ClientTickEnd sent
        pong(1);
        assertTrue(evidence.isEmpty(), "client freeze without ClientTickEnd must not trigger: " + evidence);
    }

    @Test
    void pingDelayedPastKeepAliveIsCaught() {
        out(new ClientboundPacket.Ping(1));
        out(new ClientboundPacket.KeepAlive(200L));
        in(new ServerboundPacket.KeepAlive(200L));
        for (int t = 0; t <= TransactionOrderCheck.MAX_TICKS_AFTER_KEEPALIVE; t++) {
            tick();
        }
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(TransactionOrderCheck.ID, e.check());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("ping-delayed-past-keepalive", e.details().get("reason"));
    }

    @Test
    void pingAnsweredAfterLaterTeleportAcceptIsCaught() {
        out(new ClientboundPacket.Ping(50));
        out(new ClientboundPacket.PlayerPosition(9, Vec3d.ZERO, Vec3d.ZERO, 0F, 0F, Set.of()));
        in(new ServerboundPacket.AcceptTeleportation(9));
        pong(50);
        assertEquals(1, evidence.size());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.getFirst().evidenceClass());
        assertEquals("ping-after-later-teleport-accept", evidence.getFirst().details().get("reason"));
    }

    @Test
    void skippedTransactionProducesModificationProof() {
        out(new ClientboundPacket.Ping(70001));
        out(new ClientboundPacket.Ping(70002));
        in(new ServerboundPacket.Pong(70002));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 70002, now, 1));
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(TransactionOrderCheck.ID, e.check());
        assertEquals(TransactionOrderCheck.VERSION, e.checkVersion());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("skipped-transaction", e.details().get("reason"));
        assertEquals("1", e.details().get("skippedCount"));
    }

    @Test
    void duplicateTransactionProducesModificationProof() {
        out(new ClientboundPacket.Ping(80001));
        pong(80001);
        assertTrue(evidence.isEmpty());
        tick();
        in(new ServerboundPacket.Pong(80001));
        session.deliver(new TimelineEvent.Anomaly(seq++, now, TimelineEvent.Anomaly.Kind.DUPLICATE_TRANSACTION,
                "pong " + Integer.toHexString(80001)));
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("duplicate-transaction", e.details().get("reason"));
    }

    @Test
    void gapClearsPendingAndRememberedTransactionsSoNoFalsePositiveFollows() {
        out(new ClientboundPacket.Ping(90001));
        pong(90001);
        session.deliver(new TimelineEvent.Gap(seq++, now, 10));
        in(new ServerboundPacket.Pong(90001));
        session.deliver(new TimelineEvent.Anomaly(seq++, now, TimelineEvent.Anomaly.Kind.DUPLICATE_TRANSACTION,
                "pong " + Integer.toHexString(90001)));
        assertTrue(evidence.isEmpty(), "duplicate after Gap must not produce evidence: " + evidence);
    }

    @Test
    void inadmissibleSessionIsRejectedNotAccepted() {
        initSession(Source.GAME);
        out(new ClientboundPacket.Ping(50));
        out(new ClientboundPacket.PlayerPosition(9, Vec3d.ZERO, Vec3d.ZERO, 0F, 0F, Set.of()));
        in(new ServerboundPacket.AcceptTeleportation(9));
        pong(50);
        assertTrue(evidence.isEmpty());
        assertFalse(rejected.isEmpty());
    }

    @Test
    void registeredInChecks() {
        assertEquals(TransactionOrderCheck.VERSION, Checks.version(TransactionOrderCheck.ID));
        assertTrue(Checks.all((s, e) -> { }, Replicas.vanilla()).stream().anyMatch(TransactionOrderCheck.class::isInstance));
        assertNotNull(Checks.create(TransactionOrderCheck.ID, (s, e) -> { }));
    }
}
