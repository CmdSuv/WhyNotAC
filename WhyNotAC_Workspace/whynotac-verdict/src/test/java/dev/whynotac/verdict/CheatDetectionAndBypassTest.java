package dev.whynotac.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.movement.EngineTags;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.math.vanilla.MouseTurnModel;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.OnFoot;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Replicas;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end synthetic cheat detection, latency, bypass-resistance, and verdict-mitigation benchmark
 * across the wired replica -> checks -> VerdictEngine -> SecondVerifier -> BanWaveQueue pipeline.
 */
final class CheatDetectionAndBypassTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD =
            new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);
    private static final int STONE = STATES.firstStateOf("minecraft:stone");
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final long SECOND = 1_000_000_000L;
    private static final long TICK_NANOS = SECOND / 20;
    private static final Colliders.World FLAT_WORLD = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            return y < 64 ? STONE : AIR;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return chunkX >= -1 && chunkX <= 1 && chunkZ >= -1 && chunkZ <= 1;
        }

        @Override
        public Boolean fastLava() {
            return Boolean.FALSE;
        }
    };

    private static final Set<String> ALL_CHECK_IDS = Checks.ids();

    /** Self-contained full-stack harness driving real Replicas + OnFoot + all 12 Checks + VerdictEngine. */
    private static final class BenchSession implements AutoCloseable {
        final List<TimelineEvent> recorded = new ArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<VerdictCase> cases = new CopyOnWriteArrayList<>();
        final List<VerdictDecision> mitigations = new CopyOnWriteArrayList<>();
        final List<VerdictDecision> kicks = new CopyOnWriteArrayList<>();
        final List<VerdictDecision> tempBans = new CopyOnWriteArrayList<>();
        final List<VerdictDecision> rejectedVerifications = new CopyOnWriteArrayList<>();
        final VerdictEngine verdict;
        final PlayerSession session;
        final CountDownLatch tempBanLatch;

        long seq;
        long now = 10_000_000_000L;
        int pingId;
        int tpId = 10;
        long clientTick;
        PlayerState player;
        boolean firstTick = true;
        double sentX;
        double sentY;
        double sentZ;
        float yaw;
        float pitch;
        boolean sentOnGround;
        boolean sentCollision;
        boolean sentSprinting;
        int reminder;
        PlayerState.Keys sentKeys = PlayerState.Keys.NONE;

        BenchSession(VerdictConfig config, String identityValue, Trust identityTrust) {
            this(config, identityValue, identityTrust, 1);
        }

        BenchSession(VerdictConfig config, String identityValue, Trust identityTrust, int expectedTempBans) {
            this.tempBanLatch = new CountDownLatch(expectedTempBans);
            this.verdict = new VerdictEngine(config,
                    s -> () -> Optional.of(List.copyOf(recorded)),
                    () -> TimeUnit.NANOSECONDS.toMillis(now));
            this.verdict.addCaseListener(cases::add);
            this.verdict.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override
                public void mitigate(VerdictDecision d) {
                    mitigations.add(d);
                }

                @Override
                public void kick(VerdictDecision d) {
                    kicks.add(d);
                }

                @Override
                public void tempBan(VerdictDecision d) {
                    tempBans.add(d);
                    tempBanLatch.countDown();
                }

                @Override
                public void verificationRejected(VerdictDecision d) {
                    rejectedVerifications.add(d);
                }
            });

            SessionRegistry reg = new SessionRegistry(Runnable::run, 4096, (s, e, err) -> errors.add(err));
            Replicas replicas = Replicas.vanilla();
            replicas.listeners().forEach(reg::addListener);
            EvidenceSink capturingSink = new EvidenceSink() {
                @Override
                public void accept(PlayerSession s, Evidence e) {
                    verdict.accept(s, e);
                }

                @Override
                public void rejected(PlayerSession s, String check, Admissibility admissibility) {
                    verdict.rejected(s, check, admissibility);
                }

                @Override
                public void margin(PlayerSession s, String check, double slack) {
                    verdict.margin(s, check, slack);
                }
            };
            Checks.all(capturingSink, replicas).forEach(reg::addListener);

            UUID playerId = UUID.nameUUIDFromBytes(("OfflinePlayer:BenchBot").getBytes());
            this.session = reg.openReplay("bench:session");
            deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "wire"));
            deliver(new ClientFact(seq++, now, Fact.IDENTITY, identityValue, identityTrust, "bench"));
            deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "bench"));
            deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "bench"));
            deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "bench"));
            deliver(new TimelineEvent.Bound(seq++, now, playerId, "BenchBot", 1));

            out(vanillaTagsPacket());
            out(new ClientboundPacket.Login(1, 8, OVERWORLD, 0));
            out(new ClientboundPacket.PlayerInfoUpdate(true, true,
                    List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerId, 0))));
            out(new ClientboundPacket.ChunkCacheCenter(0, 0));
            byte[] flatChunk = chunkWithStoneFloor();
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    out(new ClientboundPacket.LevelChunk(cx, cz, flatChunk));
                }
            }
            out(new ClientboundPacket.SetHeldSlot(0));
            out(new ClientboundPacket.ContainerSetContent(0, 1,
                    new ArrayList<>(Collections.nCopies(46, Stack.EMPTY)), Stack.EMPTY));
            int spawnTp = ++tpId;
            out(new ClientboundPacket.PlayerPosition(spawnTp, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 0F, 0F, Set.of()));
            sync();
            this.player = new PlayerState(0.5, 64.0, 0.5, Vec3d.ZERO, 0F, 0F, false, false, false, false, false, 0.0,
                    false, false, false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false,
                    Vec3d.ZERO, false, false);
            in(new ServerboundPacket.AcceptTeleportation(spawnTp));
            in(new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0F, 0F, true, true, false, false));
            in(new ServerboundPacket.PlayerLoaded());
            // Run 16 authentic OnFoot idle ticks (tick 0 stays onGround=false, tick 1 lands on stone floor)
            for (int i = 0; i < 16; i++) {
                vanillaStep(PlayerState.Keys.NONE, yaw, pitch, Vec3d.ZERO, null, TICK_NANOS);
            }
            assertEquals(List.of(), cases, "Clean settle anchor must produce ZERO baseline false positives");
        }

        void vanillaStep(PlayerState.Keys keys, float yRot, float xRot, Vec3d cheatOffset, Boolean forceOnGround, long deltaNanos) {
            try {
                PlayerState pre = new PlayerState(player.x(), player.y(), player.z(), player.deltaMovement(), yRot, xRot,
                        player.onGround(), player.horizontalCollision(), player.minorHorizontalCollision(),
                        player.verticalCollision(), player.verticalCollisionBelow(), player.fallDistance(),
                        player.sprinting(), player.sprintModifier(), player.crouching(), player.pose(),
                        player.keys(), player.noJumpDelay(), player.sprintTriggerTime(), player.autoJumpTime(),
                        player.support(), player.onGroundNoBlocks(), player.stuckSpeedMultiplier(),
                        player.swimming(), player.eyeInWater(), player.fallFlying(), player.inPowderSnow(),
                        player.inWater(), player.spinAttackTicks());
                OnFoot.Step step = OnFoot.step(pre, keys, FLAT_WORLD,
                        new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, firstTick));
                firstTick = false;
                PlayerState post = step.state();
                if (!cheatOffset.equals(Vec3d.ZERO)) {
                    post = new PlayerState(post.x() + cheatOffset.x(), post.y() + cheatOffset.y(), post.z() + cheatOffset.z(),
                            post.deltaMovement(), yRot, xRot, forceOnGround != null ? forceOnGround : post.onGround(),
                            post.horizontalCollision(), post.minorHorizontalCollision(), post.verticalCollision(),
                            post.verticalCollisionBelow(), post.fallDistance(), post.sprinting(), post.sprintModifier(),
                            post.crouching(), post.pose(), post.keys(), post.noJumpDelay(), post.sprintTriggerTime(),
                            post.autoJumpTime(), post.support(), post.onGroundNoBlocks(), post.stuckSpeedMultiplier(),
                            post.swimming(), post.eyeInWater(), post.fallFlying(), post.inPowderSnow(),
                            post.inWater(), post.spinAttackTicks());
                } else if (forceOnGround != null) {
                    post = new PlayerState(post.x(), post.y(), post.z(), post.deltaMovement(), yRot, xRot,
                            forceOnGround, post.horizontalCollision(), post.minorHorizontalCollision(),
                            post.verticalCollision(), post.verticalCollisionBelow(), post.fallDistance(),
                            post.sprinting(), post.sprintModifier(), post.crouching(), post.pose(),
                            post.keys(), post.noJumpDelay(), post.sprintTriggerTime(), post.autoJumpTime(),
                            post.support(), post.onGroundNoBlocks(), post.stuckSpeedMultiplier(),
                            post.swimming(), post.eyeInWater(), post.fallFlying(), post.inPowderSnow(),
                            post.inWater(), post.spinAttackTicks());
                }
                player = post;
                if (!keys.equals(sentKeys)) {
                    in(new ServerboundPacket.PlayerInput(keys.forward(), keys.backward(), keys.left(), keys.right(),
                            keys.jump(), keys.shift(), keys.sprint()));
                    sentKeys = keys;
                }
                if (post.sprinting() != sentSprinting) {
                    in(new ServerboundPacket.PlayerCommand(1, post.sprinting()
                            ? ServerboundPacket.PlayerCommand.Action.START_SPRINTING
                            : ServerboundPacket.PlayerCommand.Action.STOP_SPRINTING, 0));
                    sentSprinting = post.sprinting();
                }
                reminder++;
                boolean sendPos = VanillaMath.lengthSquared(post.x() - sentX, post.y() - sentY, post.z() - sentZ)
                        > VanillaMath.square(2.0E-4) || reminder >= 20;
                boolean sendRot = Float.compare(yRot, yaw) != 0 || Float.compare(xRot, pitch) != 0;
                boolean sendFlags = post.onGround() != sentOnGround || post.horizontalCollision() != sentCollision;
                if (sendPos || sendRot || sendFlags) {
                    in(new ServerboundPacket.MovePlayer(
                            sendPos ? post.x() : Double.NaN,
                            sendPos ? post.y() : Double.NaN,
                            sendPos ? post.z() : Double.NaN,
                            sendRot ? yRot : Float.NaN,
                            sendRot ? xRot : Float.NaN,
                            sendPos, sendRot, post.onGround(), post.horizontalCollision()));
                }
                if (sendPos) {
                    sentX = post.x();
                    sentY = post.y();
                    sentZ = post.z();
                    reminder = 0;
                }
                if (sendRot) {
                    yaw = yRot;
                    pitch = xRot;
                }
                sentOnGround = post.onGround();
                sentCollision = post.horizontalCollision();
                tick(deltaNanos);
            } catch (Colliders.Unknown e) {
                throw new AssertionError(e);
            }
        }

        void deliver(TimelineEvent ev) {
            recorded.add(ev);
            session.deliver(ev);
        }

        void out(ClientboundPacket p) {
            deliver(new TimelineEvent.Outbound(seq++, now, p));
        }

        void in(ServerboundPacket p) {
            deliver(new TimelineEvent.Inbound(seq++, now, clientTick, p));
        }

        int sync() {
            int id = ++pingId;
            out(new ClientboundPacket.Ping(id));
            in(new ServerboundPacket.Pong(id));
            deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
            return id;
        }

        void spawnMob(int entityId, String type, double x, double y, double z) {
            out(ClientboundPacket.AddEntity.received(entityId, new UUID(99L, entityId), type,
                    x, y, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
            sync();
        }

        void move(double x, double y, double z, float yaw, float pitch, boolean hasPos, boolean hasRot, boolean onGround) {
            if (hasRot) {
                this.yaw = yaw;
                this.pitch = pitch;
            }
            in(new ServerboundPacket.MovePlayer(x, y, z, yaw, pitch, hasPos, hasRot, onGround, false));
        }

        void tick(long deltaNanos) {
            in(new ServerboundPacket.ClientTickEnd());
            clientTick++;
            now += deltaNanos;
        }

        void idleTick(long deltaNanos) {
            vanillaStep(PlayerState.Keys.NONE, yaw, pitch, Vec3d.ZERO, null, deltaNanos);
        }

        long countCases(String checkId) {
            return cases.stream().filter(c -> c.evidence().check().equals(checkId)).count();
        }

        @Override
        public void close() {
            verdict.close();
            assertEquals(List.of(), errors, "Zero listener exceptions allowed across all scenarios");
        }
    }

    private static VerdictConfig armedConfig(boolean autoBan) {
        return VerdictConfig.builder()
                .armedChecks(ALL_CHECK_IDS)
                .mitigationsEnabled(true)
                .autoKickEnabled(false)
                .autoBanEnabled(autoBan)
                .secondVerifierEnabled(true)
                .requireCorroborationForTempBan(true)
                .maxUniquePenalizedPlayersPerMinute(50)
                .minScoreForMitigation(0.5)
                .build();
    }

    @Test
    void reachSubtleAndBlatantTriggerInstantMitigationAndSecondVerifierTempBan() throws Exception {
        // 1. Authenticated account: subtle +0.15 reach (target at x=3.95, player at x=0.5, box minX=3.65 => 3.15 > 3.0)
        try (BenchSession bench = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 1)) {
            bench.spawnMob(20, "minecraft:zombie", 3.60, 64.0, 0.5); // legal boundary: minX = 3.30 => dist = 2.80 <= 3.0
            bench.vanillaStep(PlayerState.Keys.NONE, -90.0F, 0.0F, Vec3d.ZERO, null, TICK_NANOS);
            bench.in(new ServerboundPacket.InteractEntity(20, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            bench.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            bench.idleTick(TICK_NANOS);
            assertEquals(0L, bench.countCases("attack-reach"), "legal boundary hit must emit 0 cases");
            assertTrue(bench.verdict.margins().get("attack-reach").observed() >= 1);

            // Subtle +0.15 reach cheat -> 1-tick detection + CANCEL_IMPOSSIBLE_HIT + SecondVerifier TEMP_BAN
            bench.spawnMob(21, "minecraft:zombie", 3.95, 64.0, 0.5); // minX = 3.65 => dist = 3.15 > 3.0
            bench.in(new ServerboundPacket.InteractEntity(21, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            bench.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            bench.idleTick(TICK_NANOS);

            assertEquals(1L, bench.countCases("attack-reach"));
            assertTrue(bench.mitigations.stream().anyMatch(d ->
                    d.evidence().check().equals("attack-reach") && d.mitigation() == MitigationKind.CANCEL_IMPOSSIBLE_HIT));
            assertTrue(bench.tempBanLatch.await(5, TimeUnit.SECONDS), "SecondVerifier must verify P-A attack-reach");
            assertEquals(1, bench.tempBans.size());
            assertEquals(Action.TEMP_BAN, bench.tempBans.getFirst().action());
        }

        // 2. Unauthenticated offline account: same blatant reach catches + mitigates, NEVER auto-bans
        try (BenchSession unauth = new BenchSession(armedConfig(true), "UNAUTHENTICATED", Trust.CLAIMED, 0)) {
            unauth.spawnMob(22, "minecraft:zombie", 6.0, 64.0, 0.5);
            unauth.vanillaStep(PlayerState.Keys.NONE, -90.0F, 0.0F, Vec3d.ZERO, null, TICK_NANOS);
            unauth.in(new ServerboundPacket.InteractEntity(22, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            unauth.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            unauth.idleTick(TICK_NANOS);
            assertEquals(1L, unauth.countCases("attack-reach"));
            assertEquals(0, unauth.tempBans.size(), "UNAUTHENTICATED identity must never trigger auto TEMP_BAN");
        }
    }

    @Test
    void timerSpeedupsVsHonestLagBurstAndSubPercentQuartzBound() {
        // Honest burst (10 ticks catchup freeze recovery) + 20 Hz jitter -> 0 cases
        try (BenchSession honest = new BenchSession(armedConfig(false), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC)) {
            SplittableRandom rng = new SplittableRandom(42L);
            for (int cycle = 0; cycle < 20; cycle++) {
                int ping = honest.sync();
                // Client frozen for 500ms, then runs max 10 catch-up ticks in 1 frame before settling
                honest.now += 500_000_000L;
                honest.out(new ClientboundPacket.Ping(ping + 1));
                for (int k = 0; k < 10; k++) {
                    honest.idleTick(100_000L);
                }
                honest.in(new ServerboundPacket.Pong(ping + 1));
                honest.deliver(new TimelineEvent.Acknowledged(honest.seq++, honest.now, ping + 1, honest.now, 0));
                for (int k = 0; k < 15; k++) {
                    honest.idleTick(TICK_NANOS + rng.nextLong(-1_000_000L, 1_000_000L));
                }
            }
            assertEquals(0L, honest.countCases("client-clock"), "Honest 10-tick catchup bursts must trigger 0 timer cases");
            assertTrue(honest.verdict.margins().get("client-clock").smallest() > 0.0);
        }

        // Documented boundary: 0.5% clock drift stays within 1% quartz margin -> 0 detections
        try (BenchSession microTimer = new BenchSession(armedConfig(false), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC)) {
            long tickStep = (long) (TICK_NANOS / 1.005);
            for (int t = 0; t < 400; t++) {
                if (t % 20 == 0) {
                    microTimer.sync();
                }
                microTimer.idleTick(tickStep);
            }
            assertEquals(0L, microTimer.countCases("client-clock"),
                    "Sub-1% drift must remain unflagged per soundness note");
        }

        // Subtle 1.10x Timer + Aggressive 1.50x Timer -> both caught with measured tick latency
        int latency150 = measureTimerDetectionTicks(1.50, 200);
        int latency110 = measureTimerDetectionTicks(1.10, 400);
        assertTrue(latency150 > 20 && latency150 <= 80,
                "1.50x Timer must be detected within ~1.5s (~46 ticks), got " + latency150);
        assertTrue(latency110 > 100 && latency110 <= 320,
                "1.10x Subtle Timer must be detected within bounded window, got " + latency110);
    }

    private static int measureTimerDetectionTicks(double speedMultiplier, int maxTicks) {
        try (BenchSession timer = new BenchSession(armedConfig(false), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC)) {
            timer.sync(); // anchor after initial warmup ticks
            long tickStep = Math.round(TICK_NANOS / speedMultiplier);
            for (int t = 1; t <= maxTicks; t++) {
                timer.idleTick(tickStep);
                if (t % 20 == 0) {
                    timer.sync();
                    if (timer.countCases("client-clock") > 0) {
                        return t;
                    }
                }
            }
            return -1;
        }
    }

    @Test
    void tickFramingTeleportAbuseAndSelectivePingWithholdingAntiKbBypass() {
        try (BenchSession bench = new BenchSession(armedConfig(false), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC)) {
            // 1. Teleport allowance allows ONLY 1 PosRot reply; smuggling a 2nd MovePlayer triggers tick-framing immediately
            bench.out(new ClientboundPacket.PlayerPosition(77, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 0F, 0F, Set.of()));
            bench.in(new ServerboundPacket.AcceptTeleportation(77));
            bench.move(0.5, 64.0, 0.5, 0F, 0F, true, true, false); // legitimate reply #1
            bench.move(0.5, 64.0, 0.5, 0F, 0F, true, false, true); // regular tick move #1
            bench.move(0.6, 64.0, 0.5, 0F, 0F, true, false, true); // cheat extra move #2 without ClientTickEnd!
            assertEquals(1L, bench.countCases("tick-framing"), "Instant split-tick detection without waiting for TickEnd");
            bench.tick(TICK_NANOS);

            // 2. Selective Ping withholding past KeepAlive (anti-KB / blink): caught at tick 13 (> MAX_TICKS_AFTER_KEEPALIVE=12)
            bench.out(new ClientboundPacket.Ping(500));
            bench.out(new ClientboundPacket.KeepAlive(9001L));
            bench.in(new ServerboundPacket.KeepAlive(9001L));
            int detectedAt = -1;
            for (int t = 1; t <= 16; t++) {
                bench.idleTick(TICK_NANOS);
                if (bench.countCases("transaction-order") > 0 && detectedAt < 0) {
                    detectedAt = t;
                }
            }
            assertEquals(13, detectedAt, "Must fire exactly on tick 13 after KeepAlive");

            // 3. Out-of-order Pong after newer Teleport Accept
            bench.now += 2_000_000_000L; // past 1s report cooldown
            bench.out(new ClientboundPacket.Ping(501));
            bench.out(new ClientboundPacket.PlayerPosition(78, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 0F, 0F, Set.of()));
            bench.in(new ServerboundPacket.AcceptTeleportation(78));
            bench.move(0.5, 64.0, 0.5, 0F, 0F, true, true, false);
            bench.tick(TICK_NANOS);
            bench.in(new ServerboundPacket.Pong(501));
            bench.deliver(new TimelineEvent.Acknowledged(bench.seq++, bench.now, 501, bench.now, 0));
            assertEquals(2L, bench.countCases("transaction-order"));
        }
    }

    @Test
    void silentKillAuraFastBreakScaffoldAndGrammarMitigations() {
        try (BenchSession bench = new BenchSession(armedConfig(false), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC)) {
            // 1. Silent KillAura (off-aim) -> interact FIRST, then tick's MovePlayer looking NORTH (180F) while mob is SOUTH (0F)
            bench.spawnMob(30, "minecraft:zombie", 0.5, 64.0, 3.0); // straight south (yaw=0)
            bench.in(new ServerboundPacket.InteractEntity(30, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            bench.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            bench.vanillaStep(PlayerState.Keys.NONE, 180.0F, 0.0F, Vec3d.ZERO, null, TICK_NANOS); // looking NORTH
            assertEquals(1L, bench.countCases("attack-aim"));
            assertTrue(bench.mitigations.stream().anyMatch(d ->
                    d.evidence().check().equals("attack-aim") && d.mitigation() == MitigationKind.CANCEL_IMPOSSIBLE_HIT));

            // 2. Action Grammar: NoSwing after attack
            bench.now += 2_000_000_000L;
            bench.spawnMob(31, "minecraft:zombie", 0.5, 64.0, -2.0);
            bench.in(new ServerboundPacket.InteractEntity(31, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            bench.idleTick(TICK_NANOS);
            assertTrue(bench.countCases("action-grammar") >= 1);

            // 3. FastBreak (dirt takes 15 ticks by hand; stop at tick 12) + PredictionSequence skip
            bench.now += 2_000_000_000L;
            BlockPosition target = new BlockPosition(0, 64, -2);
            bench.out(new ClientboundPacket.BlockUpdate(target, STATES.firstStateOf("minecraft:dirt")));
            bench.sync();
            // Look straight north toward (0.5, 64.5, -1.5)
            bench.vanillaStep(PlayerState.Keys.NONE, 180.0F, 35.0F, Vec3d.ZERO, null, TICK_NANOS);
            bench.in(new ServerboundPacket.PlayerAction(
                    ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, target, BlockFace.SOUTH, 1));
            for (int t = 0; t < 11; t++) {
                bench.idleTick(TICK_NANOS);
            }
            // Cheat stops 1-2 ticks early AND jumps prediction sequence directly from 1 -> 9
            bench.in(new ServerboundPacket.PlayerAction(
                    ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, target, BlockFace.SOUTH, 9));
            bench.idleTick(TICK_NANOS);
            assertTrue(bench.countCases("break-time") >= 1, "FastBreak must be detected before progress=1.0");
            assertTrue(bench.countCases("prediction-sequence") >= 1, "Skipped sequence 1->9 must trigger prediction-sequence");
            assertTrue(bench.mitigations.stream().anyMatch(d ->
                    d.evidence().check().equals("break-time") && d.mitigation() == MitigationKind.CANCEL_IMPOSSIBLE_BLOCK_ACTION));

            // 4. Scaffold / off-face UseItemOn -> use-aim + CANCEL_IMPOSSIBLE_BLOCK_ACTION
            bench.now += 2_000_000_000L;
            BlockPosition under = new BlockPosition(0, 63, 0);
            bench.vanillaStep(PlayerState.Keys.NONE, 0.0F, 90.0F, Vec3d.ZERO, null, TICK_NANOS); // looking straight DOWN
            bench.in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, under, BlockFace.NORTH,
                    new Vec3d(0.5, 0.5, 0.0), false, false, 10)); // clicking NORTH side while above top face
            bench.idleTick(TICK_NANOS);
            assertTrue(bench.countCases("use-aim") >= 1, "Off-face Scaffold click must trigger use-aim");
            assertTrue(bench.mitigations.stream().anyMatch(d ->
                    d.evidence().check().equals("use-aim") && d.mitigation() == MitigationKind.CANCEL_IMPOSSIBLE_BLOCK_ACTION));
        }
    }

    @Test
    void movementPhysicsExploitsTriggerSetbackWhileGapInjectionBlocksConviction() {
        // Test Fly / Step / Horizontal Speed / NoFall on settled OnFoot state -> SETBACK_POSITION
        String[] movementCheats = {"step-highjump", "fly-hover", "speed-warp", "nofall-spoof"};
        PlayerState.Keys jumpKeys = new PlayerState.Keys(false, false, false, false, true, false, false);
        PlayerState.Keys forwardKeys = new PlayerState.Keys(true, false, false, false, false, false, false);
        for (String mode : movementCheats) {
            try (BenchSession bench = new BenchSession(armedConfig(false), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC)) {
                switch (mode) {
                    case "step-highjump" -> bench.vanillaStep(PlayerState.Keys.NONE, 0F, 0F, new Vec3d(0.0, 1.25, 0.0), false, TICK_NANOS);
                    case "fly-hover" -> {
                        bench.vanillaStep(jumpKeys, 0F, 0F, Vec3d.ZERO, null, TICK_NANOS);
                        // Cancel gravity delta (+0.08 on top of falling step, wait: send same height with force offset)
                        bench.vanillaStep(PlayerState.Keys.NONE, 0F, 0F, new Vec3d(0.0, 0.15, 0.0), false, TICK_NANOS);
                    }
                    case "speed-warp" -> bench.vanillaStep(forwardKeys, 0F, 0F, new Vec3d(0.95, 0.0, 0.0), true, TICK_NANOS);
                    case "nofall-spoof" -> bench.vanillaStep(jumpKeys, 0F, 0F, Vec3d.ZERO, Boolean.TRUE, TICK_NANOS);
                }
                assertTrue(bench.countCases("movement") >= 1, mode + " must trigger movement detection");
                assertTrue(bench.mitigations.stream().anyMatch(d ->
                                d.evidence().check().equals("movement") && d.mitigation() == MitigationKind.SETBACK_POSITION),
                        mode + " must authorize SETBACK_POSITION mitigation");
            }
        }

        // Fail-safe Gap & non-native bypass guard: missing packets (Gap) withdraw replicas without false conviction,
        // and translated sessions reject active evidence via AdmissibilityTracker (0 cases, >=1 rejected).
        try (BenchSession gapped = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 0)) {
            gapped.deliver(new TimelineEvent.Gap(gapped.seq++, gapped.now, 1));
            gapped.spawnMob(40, "minecraft:zombie", 8.0, 64.0, 0.5);
            gapped.in(new ServerboundPacket.InteractEntity(40, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            gapped.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            gapped.tick(TICK_NANOS);
            assertEquals(List.of(), gapped.cases.stream().map(c -> c.evidence().check()).toList(),
                    "Gap inside window must forbid conviction");
        }
        try (BenchSession translated = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 0)) {
            translated.deliver(new ClientFact(translated.seq++, translated.now, Fact.TRANSLATOR, "ViaVersion", Trust.FORMAT_BOUND, "edge"));
            translated.spawnMob(41, "minecraft:zombie", 8.0, 64.0, 0.5);
            translated.in(new ServerboundPacket.InteractEntity(41, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            translated.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            translated.tick(TICK_NANOS);
            assertEquals(0L, translated.countCases("attack-reach"), "Translated client must yield 0 P-A cases");
            assertTrue(translated.verdict.rejectedCount() >= 1, "AdmissibilityTracker must record rejection");
        }
    }

    @Test
    void statisticalAutoClickerAndKillAuraSignalsNeverBanOrNeutralizeUnderPolicy() {
        // 1. Clean variable human clicking + discrete MouseTurnModel aim -> 0 findings across ALL 12 checks
        try (BenchSession human = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 0)) {
            SplittableRandom rng = new SplittableRandom(2026L);
            double mult = MouseTurnModel.multiplier(0.5, false);
            for (int t = 0; t < 160; t++) {
                float nextYaw = human.yaw + MouseTurnModel.countIncrement(rng.nextInt(-5, 6), mult);
                float nextPitch = Math.max(-90F, Math.min(90F, human.pitch + MouseTurnModel.countIncrement(rng.nextInt(-2, 3), mult)));
                if (t % rng.nextInt(2, 5) == 0) {
                    human.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
                }
                human.vanillaStep(PlayerState.Keys.NONE, nextYaw, nextPitch, Vec3d.ZERO, null, TICK_NANOS);
            }
            assertEquals(List.of(), human.cases, "Clean variable human session must yield zero false positives across all checks");
        }

        // 2. AutoClicker (constant 2-tick interval over 65 clicks) + Robotic Tracking (fixed 3.0 deg/tick while attacking)
        try (BenchSession macro = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 0)) {
            macro.spawnMob(50, "minecraft:zombie", 0.5, 64.0, 2.5);
            float yaw = 0F;
            for (int i = 0; i < 66; i++) {
                yaw += 3.0F;
                macro.in(new ServerboundPacket.InteractEntity(50, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
                macro.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
                macro.vanillaStep(PlayerState.Keys.NONE, yaw, 0.0F, Vec3d.ZERO, null, TICK_NANOS);

                yaw += 3.0F;
                macro.vanillaStep(PlayerState.Keys.NONE, yaw, 0.0F, Vec3d.ZERO, null, TICK_NANOS);
            }
            assertTrue(macro.countCases("click-pattern") >= 1, "AutoClicker constant cadence must trigger click-pattern");
            assertTrue(macro.countCases("aim-pattern") >= 1, "Robotic angular velocity must trigger aim-pattern");

            // 3. ChestStealer (12 rapid QUICK_MOVE transfers across 3 ticks inside an open generic_9x3 chest)
            macro.out(new ClientboundPacket.OpenScreen(3, "minecraft:generic_9x3"));
            macro.sync();
            for (int slot = 0; slot < 12; slot++) {
                macro.in(new ServerboundPacket.ContainerClick(3, 1, slot, 0, "QUICK_MOVE", List.of(), Stack.EMPTY));
                if (slot % 4 == 3) {
                    macro.idleTick(TICK_NANOS);
                }
            }
            assertTrue(macro.countCases("container-timing") >= 1, "ChestStealer burst must trigger container-timing");

            // POLICY.md guarantee: even with autoBanEnabled + autoKickEnabled, S checks NEVER neutralize/kick/ban
            assertTrue(macro.mitigations.stream().noneMatch(d ->
                    d.evidence().check().equals("click-pattern")
                            || d.evidence().check().equals("aim-pattern")
                            || d.evidence().check().equals("container-timing")));
            assertEquals(0, macro.kicks.size());
            assertEquals(0, macro.tempBans.size());
        }
    }

    @Test
    void legitimateExtremeEdgeBehavioursProduceZeroFalseCasesAcrossAllChecks() {
        // 1. High-FPS (240 Hz => 12 render frames per client tick) MouseTurnModel accumulation
        try (BenchSession highFps = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 0)) {
            SplittableRandom rng = new SplittableRandom(777L);
            double mult = MouseTurnModel.multiplier(0.65, false);
            for (int tick = 0; tick < 120; tick++) {
                float y = highFps.yaw;
                float p = highFps.pitch;
                for (int frame = 0; frame < 12; frame++) {
                    y = MouseTurnModel.turnYaw(y, rng.nextLong(-3, 4) * mult);
                    p = MouseTurnModel.turnPitch(p, rng.nextLong(-2, 3) * mult);
                }
                if (tick % 4 == 0) {
                    highFps.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
                }
                highFps.vanillaStep(PlayerState.Keys.NONE, y, p, Vec3d.ZERO, null, TICK_NANOS);
            }
            assertEquals(List.of(), highFps.cases, "240 FPS multi-frame mouse accumulation must open 0 cases");
        }

        // 2. Extreme 10-second freeze recovery + 600 ms KeepAlive/Ping burst
        try (BenchSession freeze = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 0)) {
            freeze.sync();
            for (int t = 0; t < 40; t++) {
                freeze.idleTick(TICK_NANOS);
            }
            int ping1 = ++freeze.pingId;
            freeze.out(new ClientboundPacket.Ping(ping1));
            freeze.out(new ClientboundPacket.KeepAlive(555L));
            // Freeze for 10 seconds (0 ticks emitted), then answer KeepAlive + burst 10 catch-up ticks in 1 frame
            freeze.now += 10_000_000_000L;
            freeze.in(new ServerboundPacket.KeepAlive(555L));
            for (int k = 0; k < 10; k++) {
                freeze.idleTick(100_000L);
            }
            freeze.in(new ServerboundPacket.Pong(ping1));
            freeze.deliver(new TimelineEvent.Acknowledged(freeze.seq++, freeze.now, ping1, freeze.now, 0));
            // 600 ms recovery ticks
            freeze.now += 600_000_000L;
            for (int t = 0; t < 30; t++) {
                freeze.idleTick(TICK_NANOS);
            }
            assertEquals(List.of(), freeze.cases, "10s freeze + 600ms lag recovery must open 0 cases");
        }

        // 3. Toggle-sprint & toggle-crouch OnFoot transitions including under server-opened chest screen
        try (BenchSession toggle = new BenchSession(armedConfig(true), "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, 0)) {
            PlayerState.Keys forwardSprint = new PlayerState.Keys(true, false, false, false, false, false, true);
            PlayerState.Keys sneakSprintHold = new PlayerState.Keys(false, false, false, false, false, true, true);
            for (int t = 0; t < 20; t++) {
                toggle.vanillaStep(forwardSprint, 0F, 0F, Vec3d.ZERO, null, TICK_NANOS);
            }
            for (int t = 0; t < 10; t++) {
                toggle.vanillaStep(sneakSprintHold, 0F, 0F, Vec3d.ZERO, null, TICK_NANOS);
            }
            // Server opens chest screen while toggle sneak + sprint remain pressed (action-grammar exempts sneak/sprint)
            toggle.out(new ClientboundPacket.OpenScreen(2, "minecraft:generic_9x3"));
            toggle.sync();
            for (int t = 0; t < 10; t++) {
                toggle.vanillaStep(sneakSprintHold, 0F, 0F, Vec3d.ZERO, null, TICK_NANOS);
            }
            toggle.out(new ClientboundPacket.ContainerClose(2));
            toggle.sync();
            for (int t = 0; t < 10; t++) {
                toggle.vanillaStep(PlayerState.Keys.NONE, 0F, 0F, Vec3d.ZERO, null, TICK_NANOS);
            }
            assertEquals(List.of(), toggle.cases, "Toggle sprint/crouch across movement & open GUI must open 0 cases");
        }
    }

    private static ClientboundPacket.UpdateTags vanillaTagsPacket() {
        List<Integer> climbable = new ArrayList<>();
        for (String b : OnFoot.CLIMBABLE) {
            climbable.add(STATES.blockId(STATES.firstStateOf(b)));
        }
        List<Integer> canGlideThrough = new ArrayList<>();
        for (String b : OnFoot.CAN_GLIDE_THROUGH) {
            canGlideThrough.add(STATES.blockId(STATES.firstStateOf(b)));
        }
        List<Integer> fallDamageResetting = new ArrayList<>();
        for (String b : OnFoot.FALL_DAMAGE_RESETTING) {
            fallDamageResetting.add(STATES.blockId(STATES.firstStateOf(b)));
        }
        List<Integer> fences = new ArrayList<>();
        List<Integer> walls = new ArrayList<>();
        for (int id = 0; id < STATES.blockCount(); id++) {
            String key = STATES.blockKey(id);
            if (key.endsWith("_fence")) {
                fences.add(id);
            } else if (key.endsWith("_wall")) {
                walls.add(id);
            }
        }
        return new ClientboundPacket.UpdateTags(List.of(
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:block", List.of(
                        tag("minecraft:climbable", climbable),
                        tag("minecraft:can_glide_through", canGlideThrough),
                        tag("minecraft:fall_damage_resetting", fallDamageResetting),
                        tag("minecraft:fences", fences),
                        tag("minecraft:walls", walls),
                        tag("minecraft:strider_warm_blocks", List.of(STATES.blockId(STATES.firstStateOf("minecraft:lava")))))),
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:entity_type", List.of(
                        tag("minecraft:powder_snow_walkable_mobs", List.of()),
                        tag("minecraft:can_float_while_ridden", List.of(
                                EngineTags.HORSE, EngineTags.ZOMBIE_HORSE, EngineTags.MULE,
                                EngineTags.DONKEY, EngineTags.CAMEL, EngineTags.CAMEL_HUSK)))),
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:fluid", List.of(
                        tag("minecraft:water", List.of(EngineTags.WATER, EngineTags.FLOWING_WATER)),
                        tag("minecraft:lava", List.of(EngineTags.LAVA, EngineTags.FLOWING_LAVA))))));
    }

    private static ClientboundPacket.UpdateTags.Tag tag(String name, List<Integer> ids) {
        return new ClientboundPacket.UpdateTags.Tag(name, ids.stream().mapToInt(Integer::intValue).toArray());
    }

    private static byte[] chunkWithStoneFloor() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int sections = OVERWORLD.sections();
        // Sections 0..7 cover y=-64..63 (stone), 8..23 cover y=64..319 (air)
        for (int i = 0; i < sections; i++) {
            boolean solid = i < 8;
            data.write(solid ? 0x10 : 0);
            data.write(0);
            data.write(0);
            varInt(data, solid ? STONE : AIR);
            data.write(0);
            varInt(data, 0);
        }
        varInt(body, data.size());
        body.writeBytes(data.toByteArray());
        varInt(body, 0);
        return body.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
