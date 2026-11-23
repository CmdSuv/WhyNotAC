package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vanilla.MouseTurnModel;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.RelativeFlag;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AimPatternCheckTest {

    private static final long SECOND = 1_000_000_000L;
    private static final int SELF = 1;
    private static final int ZOMBIE = 20;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now;
    private int pingId;

    @BeforeEach
    void setUp() {
        initSession(Source.WIRE, Stack.EMPTY);
    }

    private void initSession(Source inbound, Stack mainHand) {
        evidence.clear();
        rejected.clear();
        seq = 0;
        now = 0;
        pingId = 0;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new AimPatternCheck(new EvidenceSink() {
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
        deliver(new TimelineEvent.Observation(seq++, now, inbound, Source.WIRE, 774, "test"));
        deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        deliver(new TimelineEvent.Bound(seq++, now, new UUID(2, 2), "me", SELF));
        out(new ClientboundPacket.Login(SELF, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0));
        out(new ClientboundPacket.SetHeldSlot(0));
        out(new ClientboundPacket.ContainerSetContent(0, 1, inventory(mainHand), Stack.EMPTY));
        sync();
        in(new ServerboundPacket.PlayerLoaded());
        in(new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0F, 0F, true, true, true, false));
        tick();
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

    private void look(float yaw, float pitch) {
        in(new ServerboundPacket.MovePlayer(0, 0, 0, yaw, pitch, false, true, true, false));
    }

    private void attack() {
        in(new ServerboundPacket.InteractEntity(ZOMBIE, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        in(new ServerboundPacket.Swing(dev.whynotac.protocol.packet.Hand.MAIN_HAND));
    }

    private void tick() {
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    private static List<Stack> inventory(Stack mainHand) {
        List<Stack> items = new ArrayList<>(Collections.nCopies(46, Stack.EMPTY));
        items.set(36, mainHand);
        return items;
    }

    @Test
    void vanillaMouseMovementInAndOutOfCombatProducesNothing() {
        double m = MouseTurnModel.multiplier(0.5, false);
        SplittableRandom rng = new SplittableRandom(123);
        float yaw = 0.0F;
        float pitch = 5.0F;
        for (int t = 0; t < 60; t++) {
            // Simulate 2 frames per tick
            for (int f = 0; f < 2; f++) {
                yaw = MouseTurnModel.turnYaw(yaw, rng.nextLong(-15, 16) * m);
                pitch = MouseTurnModel.turnPitch(pitch, rng.nextLong(-6, 7) * m);
            }
            if (t >= 30 && t % 2 == 0) {
                attack();
            }
            look(yaw, pitch);
            tick();
        }
        assertTrue(evidence.isEmpty(), "honest MouseTurnModel turns must produce no evidence: " + evidence);
        assertTrue(rejected.isEmpty());
    }

    @Test
    void cinematicOrFractionalInputIsExemptWhenNotOnLattice() {
        SplittableRandom rng = new SplittableRandom(99);
        float yaw = 0.0F;
        float pitch = 0.0F;
        // Continuous fractional turns everywhere (like SmoothDouble / HiDPI / gamepad)
        for (int t = 0; t < 60; t++) {
            yaw += (float) (rng.nextDouble(-2.5, 2.5));
            pitch = Math.clamp(pitch + (float) (rng.nextDouble(-1.0, 1.0)), -80F, 80F);
            if (t >= 30) {
                attack();
            }
            look(yaw, pitch);
            tick();
        }
        assertTrue(evidence.isEmpty(), "fractional/cinematic turns must not fail lattice baseline: " + evidence);
    }

    @Test
    void spyglassExemptsLatticeBreaks() {
        initSession(Source.WIRE, new Stack("minecraft:spyglass", 1, List.of(), List.of(), null, false));
        double m = MouseTurnModel.multiplier(0.5, false);
        SplittableRandom rng = new SplittableRandom(11);
        float yaw = 0.0F;
        float pitch = 0.0F;
        for (int t = 0; t < 25; t++) {
            yaw = MouseTurnModel.turnYaw(yaw, rng.nextLong(1, 10) * m);
            pitch = MouseTurnModel.turnPitch(pitch, rng.nextLong(1, 6) * m);
            look(yaw, pitch);
            tick();
        }
        for (int t = 0; t < 18; t++) {
            yaw += 0.37123F;
            pitch += 0.19317F;
            attack();
            look(yaw, pitch);
            tick();
        }
        assertTrue(evidence.isEmpty(), "spyglass must exempt lattice breaks: " + evidence);
    }

    @Test
    void largeYawPrecisionLossExemptsLatticeBreaks() {
        double m = MouseTurnModel.multiplier(0.0, false);
        float yaw = 20_000.0F; // Math.ulp(20000f) = 0.001953125 > MAX_LATTICE_ULP (0.001)
        float pitch = 0.0F;
        SplittableRandom rng = new SplittableRandom(5);
        for (int t = 0; t < 25; t++) {
            yaw = MouseTurnModel.turnYaw(yaw, rng.nextLong(1, 10) * m);
            pitch = MouseTurnModel.turnPitch(pitch, rng.nextLong(1, 5) * m);
            look(yaw, pitch);
            tick();
        }
        for (int t = 0; t < 18; t++) {
            yaw += 0.37123F;
            pitch += 0.19317F;
            attack();
            look(yaw, pitch);
            tick();
        }
        assertTrue(evidence.isEmpty(), "large yaw must exempt lattice breaks: " + evidence);
    }

    @Test
    void combatLatticeBreakDetectsMixedAimAssist() {
        double m = MouseTurnModel.multiplier(0.5, false);
        SplittableRandom rng = new SplittableRandom(42);
        float yaw = 0.0F;
        float pitch = 0.0F;
        // 25 clean vanilla turns establish the exact mouse sensitivity baseline
        for (int t = 0; t < 25; t++) {
            long cy = rng.nextInt(1, 8) * (rng.nextBoolean() ? 1L : -1L);
            long cp = rng.nextInt(1, 5) * (rng.nextBoolean() ? 1L : -1L);
            yaw = MouseTurnModel.turnYaw(yaw, cy * m);
            pitch = MouseTurnModel.turnPitch(pitch, cp * m);
            look(yaw, pitch);
            tick();
        }
        // Combat starts: AimAssist blends arbitrary continuous floats off-lattice
        for (int t = 0; t < 16; t++) {
            yaw += 0.41783F;
            pitch += 0.13729F;
            attack();
            look(yaw, pitch);
            tick();
        }
        assertFalse(evidence.isEmpty(), "expected combat-lattice-break evidence");
        assertEquals(AimPatternCheck.ID, evidence.get(0).check());
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.get(0).evidenceClass());
        assertEquals("combat-lattice-break", evidence.get(0).details().get("reason"));
    }

    @Test
    void hitAndSnapbackIsCaught() {
        float baseYaw = 10.0F;
        float basePitch = 5.0F;
        look(baseYaw, basePitch);
        tick();
        for (int i = 0; i < 3; i++) {
            attack();
            look(baseYaw + 45.0F, basePitch - 15.0F);
            tick();
            look(baseYaw + 0.5F, basePitch);
            tick();
            tick();
        }
        assertFalse(evidence.isEmpty(), "expected hit-and-snapback evidence");
        assertEquals("hit-and-snapback", evidence.get(0).details().get("reason"));
    }

    @Test
    void serverTeleportIsNotTreatedAsSnapback() {
        float yaw = 0.0F;
        look(yaw, 0F);
        tick();
        for (int i = 0; i < 4; i++) {
            out(new ClientboundPacket.PlayerPosition(i + 1, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 90.0F, 0.0F, Set.of()));
            in(new ServerboundPacket.AcceptTeleportation(i + 1));
            in(new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 90.0F, 0.0F, true, true, false, false));
            tick();
            attack();
            look(0.0F, 0.0F);
            tick();
        }
        assertTrue(evidence.isEmpty(), "server teleports must reset tracking: " + evidence);
    }

    @Test
    void roboticConstantAngularSpeedIsCaught() {
        float yaw = 0.0F;
        for (int t = 0; t < 26; t++) {
            yaw += 3.5F;
            if (t % 2 == 0) {
                attack();
            }
            look(yaw, 0.0F);
            tick();
        }
        assertFalse(evidence.isEmpty(), "expected robotic-tracking evidence");
        assertEquals("robotic-tracking", evidence.get(0).details().get("reason"));
    }

    @Test
    void inadmissibleSessionIsRejectedNotAccepted() {
        initSession(Source.GAME, Stack.EMPTY);
        float yaw = 0.0F;
        for (int t = 0; t < 26; t++) {
            yaw += 3.5F;
            attack();
            look(yaw, 0.0F);
            tick();
        }
        assertTrue(evidence.isEmpty());
        assertFalse(rejected.isEmpty());
    }

    @Test
    void sensitivityChangeOutOfCombatInvalidatesOldBaseline() {
        double m1 = MouseTurnModel.multiplier(0.5, false);
        double m2 = MouseTurnModel.multiplier(0.83, false);
        SplittableRandom rng = new SplittableRandom(314);
        float yaw = 0.0F;
        float pitch = 0.0F;
        for (int t = 0; t < 25; t++) {
            yaw = MouseTurnModel.turnYaw(yaw, rng.nextLong(1, 8) * m1);
            pitch = MouseTurnModel.turnPitch(pitch, rng.nextLong(1, 5) * m1);
            look(yaw, pitch);
            tick();
        }
        // Player changes sensitivity in options menu and turns out of combat for 6 ticks, then enters combat with new sens
        for (int t = 0; t < 6; t++) {
            yaw = MouseTurnModel.turnYaw(yaw, rng.nextLong(1, 8) * m2);
            pitch = MouseTurnModel.turnPitch(pitch, rng.nextLong(1, 5) * m2);
            look(yaw, pitch);
            tick();
        }
        for (int t = 0; t < 20; t++) {
            yaw = MouseTurnModel.turnYaw(yaw, rng.nextLong(1, 8) * m2);
            pitch = MouseTurnModel.turnPitch(pitch, rng.nextLong(1, 5) * m2);
            attack();
            look(yaw, pitch);
            tick();
        }
        assertTrue(evidence.isEmpty(), "sensitivity change out of combat must reset baseline cleanly: " + evidence);
    }

    @Test
    void twoStepAlternatingKillAuraTriggersLowEntropyAimWithoutBreakingRoboticStdDev() {
        float yaw = 0.0F;
        for (int t = 0; t < 34; t++) {
            // Alternates between +2.0 and +4.0 deg/tick (stdDev ~ 1.0 >> 0.015, so robotic-tracking stays silent!)
            yaw += (t % 2 == 0) ? 2.0F : 4.0F;
            if (t % 3 == 0) {
                attack();
            }
            look(yaw, 0.0F);
            tick();
        }
        assertFalse(evidence.isEmpty(), "low-entropy quantized combat aim must trigger aim-pattern");
        assertTrue(evidence.stream().anyMatch(e -> "low-entropy-aim".equals(e.details().get("reason"))),
                "expected low-entropy-aim finding, got: " + evidence);
        assertEquals(EvidenceClass.NOMINAL_STATISTIC, evidence.getFirst().evidenceClass());
    }

    @Test
    void linearInterpolationGhostAimbotIsCaughtWhileHumanSinusoidalAimProducesZeroFp() {
        // 1. Human sinusoidal acceleration/deceleration across 35 combat ticks -> 0 FP
        float yaw = 0.0F;
        for (int t = 0; t < 35; t++) {
            float step = (float) (0.8 + 1.8 * Math.sin(t * 0.45));
            yaw += Math.max(0.2F, step);
            if (t % 5 == 0) {
                attack();
            }
            look(yaw, (float) (2.0 * Math.cos(t * 0.3)));
            tick();
        }
        assertTrue(evidence.isEmpty(), "Human sinusoidal combat aim must produce 0 FP: " + evidence);

        // 2. Ghost linear-interpolation aimbot at 0.85 deg/tick (below robotic 1.5 deg threshold) with |ddYaw| <= 0.005
        for (int t = 0; t < 27; t++) {
            yaw += 0.85F + (t * 0.005F);
            if (t % 5 == 0) {
                attack();
            }
            look(yaw, 0.0F);
            tick();
        }
        assertTrue(evidence.stream().anyMatch(e -> "linear-combat-tracking".equals(e.details().get("reason"))),
                "Expected linear-combat-tracking, got: " + evidence);
    }

    @Test
    void registeredInChecks() {
        assertEquals(AimPatternCheck.VERSION, Checks.version(AimPatternCheck.ID));
        assertTrue(Checks.all((s, e) -> { }, Replicas.vanilla()).stream().anyMatch(AimPatternCheck.class::isInstance));
        assertNotNull(Checks.create(AimPatternCheck.ID, (s, e) -> { }));
    }
}
