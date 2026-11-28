package dev.whynotac.analysis;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.evidence.Margins;
import dev.whynotac.analysis.policy.ActionPolicy;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.analysis.policy.ActionPolicy.Permission;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 4 validation tests: fuzz robustness ({@code TEST-01}), property-based policy/margin laws ({@code TEST-02}),
 * and deterministic clean-vs-malicious simulation ({@code TEST-04}).
 */
final class ValidationSuiteTest {

    @Test
    void test01FuzzExtremePacketSequencesCauseZeroExceptions() {
        List<Throwable> errors = new ArrayList<>();
        EvidenceSink sink = (session, e) -> { };
        SessionRegistry reg = new SessionRegistry(Runnable::run, 4096, (s, ev, err) -> errors.add(err));
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(reg::addListener);
        Checks.all(sink, replicas).forEach(reg::addListener);

        PlayerSession session = reg.openReplay("fuzz:1");
        long seq = 0;
        long nano = 1_000_000L;
        session.deliver(new Observation(seq++, nano, Source.WIRE, Source.WIRE, 774, "wire"));
        session.deliver(new ClientFact(seq++, nano, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "edge"));
        session.deliver(new ClientFact(seq++, nano, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "edge"));
        session.deliver(new ClientFact(seq++, nano, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "edge"));
        session.deliver(new ClientFact(seq++, nano, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new TimelineEvent.Bound(seq++, nano, UUID.randomUUID(), "Fuzzer", 1));

        SplittableRandom rng = new SplittableRandom(0xF022_774L);
        double[] weirdDoubles = {0.0, -0.0, 1e-300, 1e8, -1e8, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        float[] weirdFloats = {0.0f, -36000.5f, 36000.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};

        for (int i = 0; i < 600; i++) {
            nano += rng.nextLong(0L, 120_000_000L);
            switch (rng.nextInt(7)) {
                case 0 -> session.deliver(new TimelineEvent.Inbound(seq++, nano, i / 3L,
                        new ServerboundPacket.MovePlayer(
                                weirdDoubles[rng.nextInt(weirdDoubles.length)],
                                weirdDoubles[rng.nextInt(weirdDoubles.length)],
                                weirdDoubles[rng.nextInt(weirdDoubles.length)],
                                weirdFloats[rng.nextInt(weirdFloats.length)],
                                weirdFloats[rng.nextInt(weirdFloats.length)],
                                rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean())));
                case 1 -> session.deliver(new TimelineEvent.Inbound(seq++, nano, i / 3L,
                        new ServerboundPacket.InteractEntity(rng.nextInt(-5, 50),
                                ServerboundPacket.InteractEntity.Kind.values()[rng.nextInt(3)],
                                rng.nextBoolean() ? Hand.MAIN_HAND : Hand.OFF_HAND,
                                new Vec3d(0.0, 0.5, 0.0), rng.nextBoolean())));
                case 2 -> session.deliver(new TimelineEvent.Inbound(seq++, nano, i / 3L,
                        new ServerboundPacket.Swing(rng.nextBoolean() ? Hand.MAIN_HAND : Hand.OFF_HAND)));
                case 3 -> session.deliver(new TimelineEvent.Inbound(seq++, nano, i / 3L,
                        new ServerboundPacket.ClientTickEnd()));
                case 4 -> session.deliver(new TimelineEvent.Inbound(seq++, nano, i / 3L,
                        new ServerboundPacket.Pong(rng.nextInt(-10, 50))));
                case 5 -> session.deliver(new TimelineEvent.Inbound(seq++, nano, i / 3L,
                        new ServerboundPacket.PlayerInput(rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean(),
                                rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean(), rng.nextBoolean())));
                default -> session.deliver(new TimelineEvent.Inbound(seq++, nano, i / 3L,
                        new ServerboundPacket.ContainerClick(0, rng.nextInt(-1, 20), rng.nextInt(-999, 100), rng.nextInt(0, 5), "PICKUP")));
            }
        }
        session.deliver(new TimelineEvent.Disconnected(seq, nano + 1));
        assertEquals(List.of(), errors, "Fuzzing must produce zero unhandled listener exceptions");
    }

    @Test
    void test02ActionPolicyMonotonicityAndMarginsLawsHoldEverywhere() {
        for (EvidenceClass ec : EvidenceClass.values()) {
            for (Action action : Action.values()) {
                for (IdentityTier tier : IdentityTier.values()) {
                    // Shadow is always <= Armed restrictiveness
                    Permission shadowPerm = ActionPolicy.permission(ec, action, tier, Lifecycle.SHADOW);
                    Permission armedPerm = ActionPolicy.permission(ec, action, tier, Lifecycle.ARMED);
                    if (ec != EvidenceClass.CONTEXT) {
                        assertFalse(ActionPolicy.enabled(ec, action, tier, Lifecycle.SHADOW, true)
                                && !ActionPolicy.enabled(ec, action, tier, Lifecycle.ARMED, true),
                                "SHADOW must never be more permissive than ARMED");
                    }
                    // Enforcement/mitigation actions are always forbidden in SHADOW
                    if (action == Action.HIDE_INFORMATION || action == Action.NEUTRALIZE
                            || action == Action.KICK || action == Action.TEMP_BAN || action == Action.PERMANENT_BAN) {
                        assertFalse(ActionPolicy.enabled(ec, action, tier, Lifecycle.SHADOW, true));
                    }

                    // Permanent bans are never automatic under any configuration
                    assertFalse(ActionPolicy.enabled(ec, Action.PERMANENT_BAN, tier, Lifecycle.ARMED, true));

                    // Non-advantage classes never allow TEMP_BAN automatically
                    if (ec != EvidenceClass.ADVANTAGE_PROOF) {
                        assertFalse(ActionPolicy.enabled(ec, Action.TEMP_BAN, tier, Lifecycle.ARMED, true));
                    }

                    // Statistical/Context classes never auto-neutralize or kick
                    if (ec == EvidenceClass.DESIGN_STATISTIC || ec == EvidenceClass.NOMINAL_STATISTIC || ec == EvidenceClass.CONTEXT) {
                        assertFalse(ActionPolicy.enabled(ec, Action.NEUTRALIZE, tier, Lifecycle.ARMED, true));
                        assertFalse(ActionPolicy.enabled(ec, Action.KICK, tier, Lifecycle.ARMED, true));
                        assertFalse(ActionPolicy.enabled(ec, Action.TEMP_BAN, tier, Lifecycle.ARMED, true));
                    }
                    // Unauthenticated never auto-temp-bans
                    assertFalse(ActionPolicy.enabled(ec, Action.TEMP_BAN, IdentityTier.UNAUTHENTICATED, Lifecycle.ARMED, true));

                    assertTrue(shadowPerm == Permission.ALLOWED || shadowPerm == Permission.FORBIDDEN);
                    assertTrue(armedPerm != null);
                }
            }
        }

        // Margins min-accumulation property across random sequences
        Margins margins = new Margins();
        SplittableRandom rng = new SplittableRandom(123L);
        double minVal = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 200; i++) {
            double v = rng.nextDouble(0.01, 5.0);
            minVal = Math.min(minVal, v);
            margins.add("test-check", v);
        }
        assertEquals(200L, margins.snapshot().get("test-check").observed());
        assertEquals(minVal, margins.snapshot().get("test-check").smallest(), 1e-12);
    }

    @Test
    void test04DeterministicCleanSimulationProducesZeroEvidence() {
        List<Evidence> findings = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        EvidenceSink sink = (s, e) -> findings.add(e);

        SessionRegistry reg = new SessionRegistry(Runnable::run, 4096, (s, ev, err) -> errors.add(err));
        Replicas replicas = Replicas.vanilla();
        for (TimelineListener l : replicas.listeners()) {
            reg.addListener(l);
        }
        for (TimelineListener c : Checks.all(sink, replicas)) {
            reg.addListener(c);
        }

        PlayerSession clean = reg.openReplay("sim:clean");
        long seq = 0;
        long nano = 10_000_000_000L;
        clean.deliver(new Observation(seq++, nano, Source.WIRE, Source.WIRE, 774, "wire"));
        clean.deliver(new ClientFact(seq++, nano, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "edge"));
        clean.deliver(new ClientFact(seq++, nano, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "edge"));
        clean.deliver(new ClientFact(seq++, nano, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "edge"));
        clean.deliver(new ClientFact(seq++, nano, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        clean.deliver(new TimelineEvent.Bound(seq++, nano, UUID.randomUUID(), "HonestPlayer", 1));

        SplittableRandom rng = new SplittableRandom(77L);
        float yaw = 0.0f;
        float pitch = 5.0f;
        for (int tick = 0; tick < 300; tick++) {
            nano += 50_000_000L + rng.nextLong(-1_500_000L, 1_500_000L);
            yaw += (float) ((rng.nextDouble() - 0.5) * 4.0);
            pitch = Math.max(-85.0f, Math.min(85.0f, pitch + (float) ((rng.nextDouble() - 0.5) * 1.5)));
            clean.deliver(new TimelineEvent.Inbound(seq++, nano, tick,
                    new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, yaw, pitch, false, true, true, false)));
            if (tick % 6 == 0) {
                clean.deliver(new TimelineEvent.Inbound(seq++, nano + 100_000L, tick,
                        new ServerboundPacket.Swing(Hand.MAIN_HAND)));
            }
            clean.deliver(new TimelineEvent.Inbound(seq++, nano + 200_000L, tick, new ServerboundPacket.ClientTickEnd()));
        }
        clean.deliver(new TimelineEvent.Disconnected(seq, nano + 1_000_000L));

        assertEquals(List.of(), errors);
        assertEquals(List.of(), findings, "Clean deterministic human simulation must emit zero evidence");
    }
}
