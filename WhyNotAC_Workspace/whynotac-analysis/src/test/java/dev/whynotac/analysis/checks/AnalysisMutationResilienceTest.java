package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
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
import org.junit.jupiter.api.Test;

/**
 * Boundary &amp; operator mutation resilience tests (TEST-03) across deterministic and statistical check
 * thresholds in {@link TransactionOrderCheck} v2, {@link VelocityCheck} v3, {@link PacketBoundsCheck} v4,
 * and {@link PingSpoofCheck} v2.
 */
final class AnalysisMutationResilienceTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void transactionOrderKeepAliveBoundarySeparates12ValidTicksFrom13DelayedTicks() {
        List<Evidence> out12 = runKeepAliveTicks(TransactionOrderCheck.MAX_TICKS_AFTER_KEEPALIVE);
        assertTrue(out12.isEmpty(), "Exactly MAX_TICKS_AFTER_KEEPALIVE (12) ticks must remain admissible/clean");

        List<Evidence> out13 = runKeepAliveTicks(TransactionOrderCheck.MAX_TICKS_AFTER_KEEPALIVE + 1);
        assertEquals(1, out13.size(), "MAX_TICKS_AFTER_KEEPALIVE + 1 (13) ticks must report");
        assertEquals("ping-delayed-past-keepalive", out13.getFirst().details().get("reason"));
    }

    private static List<Evidence> runKeepAliveTicks(int ticks) {
        List<Evidence> found = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new TransactionOrderCheck(capturing(found)));
        PlayerSession s = openAdmissibleSession(registry);
        long seq = 20;
        long now = SECOND;
        s.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(101)));
        s.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.KeepAlive(55L)));
        s.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.KeepAlive(55L)));
        for (int i = 0; i < ticks; i++) {
            now += 50_000_000L;
            s.deliver(new TimelineEvent.Inbound(seq++, now, i, new ServerboundPacket.ClientTickEnd()));
        }
        return found;
    }

    @Test
    void packetBoundsPitchAndHotbarExactBoundaryChecks() {
        List<Evidence> found = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new PacketBoundsCheck(capturing(found), replicas));
        PlayerSession s = openAdmissibleSession(registry);
        long seq = 20;
        long now = SECOND;

        // Exact legal extremes: pitch = +90.0f, -90.0f, hotbar 0 -> 8, container button PICKUP=0/1, THROW=0/1
        s.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 0.0f, 90.0f, false, true, true, false)));
        s.deliver(new TimelineEvent.Inbound(seq++, now + 10, 0,
                new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 0.0f, -90.0f, false, true, true, false)));
        s.deliver(new TimelineEvent.Inbound(seq++, now + 20, 0, new ServerboundPacket.SetCarriedItem(0)));
        s.deliver(new TimelineEvent.Inbound(seq++, now + 30, 0, new ServerboundPacket.SetCarriedItem(8)));
        s.deliver(new TimelineEvent.Inbound(seq++, now + 40, 0,
                new ServerboundPacket.ContainerClick(0, 1, 10, 1, "THROW", List.of(), Stack.EMPTY)));
        assertTrue(found.isEmpty(), "Exact boundary legal packets must produce 0 findings: " + found);

        // Just past upper pitch boundary -> invalid-pitch
        s.deliver(new TimelineEvent.Inbound(seq++, now + SECOND, 1,
                new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 0.0f, 90.01f, false, true, true, false)));
        assertEquals(1, found.size());
        assertEquals("invalid-pitch", found.getFirst().details().get("reason"));

        // Invalid container click button -> invalid-container-button
        s.deliver(new TimelineEvent.Inbound(seq++, now + 3 * SECOND, 2,
                new ServerboundPacket.ContainerClick(0, 1, 10, 2, "THROW", List.of(), Stack.EMPTY)));
        assertEquals(2, found.size());
        assertEquals("invalid-container-button", found.get(1).details().get("reason"));
    }

    @Test
    void velocityThresholdsMaintainVanillaJumpResetSafetyMarginInvariants() {
        assertTrue(VelocityCheck.MIN_INPUT_HORIZONTAL_KB > VelocityCheck.MAX_VANILLA_JUMP_RESET_COUNTER,
                "active-input KB floor must exceed max vanilla jump-reset counter so positive residual displacement exists");
        assertTrue(VelocityCheck.MIN_INPUT_HORIZONTAL_KB - VelocityCheck.MAX_VANILLA_JUMP_RESET_COUNTER
                >= VelocityCheck.MIN_HORIZONTAL_DISPLACEMENT - 1e-9);
        assertTrue(VelocityCheck.STATIONARY_HORIZ_FACTOR > 0.0 && VelocityCheck.STATIONARY_HORIZ_FACTOR < 0.6);
        assertTrue(VelocityCheck.MIN_EXPECTED_RISE_FACTOR > 0.5 && VelocityCheck.MIN_EXPECTED_RISE_FACTOR < 0.85);
    }

    @Test
    void pingSpoofSustainedInflationBoundaryRequiresExact15Samples() {
        List<Evidence> at14 = runSustainedInflation(PingSpoofCheck.REQUIRED_INFLATED_SAMPLES - 1);
        assertTrue(at14.isEmpty(), "14 samples must not report sustained inflation");

        List<Evidence> at15 = runSustainedInflation(PingSpoofCheck.REQUIRED_INFLATED_SAMPLES);
        assertEquals(1, at15.size(), "15 samples must report sustained-ping-rtt-inflation");
        assertEquals("sustained-ping-rtt-inflation", at15.getFirst().details().get("reason"));
    }

    private static List<Evidence> runSustainedInflation(int samples) {
        List<Evidence> found = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        registry.addListener(new PingSpoofCheck(capturing(found)));
        PlayerSession s = openAdmissibleSession(registry);
        long seq = 20;
        long now = SECOND;
        s.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.KeepAlive(1L)));
        now += 20_000_000L;
        s.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.KeepAlive(1L)));
        for (int i = 0; i < samples; i++) {
            long sent = now;
            int pid = 500 + i;
            s.deliver(new TimelineEvent.Outbound(seq++, sent, new ClientboundPacket.Ping(pid)));
            for (int t = 0; t < 5; t++) {
                now += 50_000_000L;
                s.deliver(new TimelineEvent.Inbound(seq++, now, i * 5 + t, new ServerboundPacket.ClientTickEnd()));
            }
            long ack = sent + 250_000_000L; // +230ms over 20ms KA (> 200ms threshold)
            s.deliver(new TimelineEvent.Inbound(seq++, ack, 0, new ServerboundPacket.Pong(pid)));
            s.deliver(new TimelineEvent.Acknowledged(seq++, ack, pid, sent, 0));
        }
        return found;
    }

    private static PlayerSession openAdmissibleSession(SessionRegistry registry) {
        PlayerSession s = registry.openReplay("mutation-boundary");
        long seq = 0;
        long now = 1000L;
        s.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        s.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        s.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        s.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        s.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        s.deliver(new TimelineEvent.Bound(seq++, now, new UUID(9, 9), "BoundaryTester", 1));
        s.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Login(
                1, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
        s.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
        s.deliver(new TimelineEvent.Inbound(seq++, now + 50_000_000L, 0, new ServerboundPacket.ClientTickEnd()));
        return s;
    }

    private static EvidenceSink capturing(List<Evidence> out) {
        return new EvidenceSink() {
            @Override
            public void accept(PlayerSession session, Evidence evidence) {
                out.add(evidence);
            }

            @Override
            public void rejected(PlayerSession session, String check, Admissibility admissibility) {}
        };
    }
}
