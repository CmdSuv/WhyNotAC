package dev.whynotac.analysis.cli;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
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
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Replicas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Reproducible TPR / FPR / Replay-Cost benchmark runner ({@code java -jar WhyNotAC.jar benchmark [regression-dir]}).
 *
 * <p>Executes:
 * <ol>
 *   <li>All honest {@code *.wnrec} recordings in the regression directory (when present), verifying
 *       zero false positives and measuring per-event replay cost;</li>
 *   <li>A built-in 10-scenario adversarial + legitimate benchmark matrix spanning {@code P-A},
 *       {@code P-M}, and {@code S} check families.</li>
 * </ol>
 */
public final class BenchmarkRunner {

    private static final ClientboundPacket.Dimension OVERWORLD =
            new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);
    private static final int STONE = BlockStates.vanilla().firstStateOf("minecraft:stone");
    private static final int AIR = BlockStates.vanilla().firstStateOf("minecraft:air");

    public record BenchmarkReport(
            int honestRecordings,
            int honestFalsePositives,
            long totalHonestEvents,
            double usPerEvent,
            int scenariosTotal,
            int scenariosPassed,
            Map<String, Integer> scenarioFindings) {

        public boolean allPassed() {
            return honestFalsePositives == 0 && scenariosPassed == scenariosTotal;
        }
    }

    private BenchmarkRunner() {}

    public static void runCli(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path regressionDir = args.length >= 2 ? Path.of(args[1]) : Path.of("regression");
        BenchmarkReport report = run(regressionDir);
        out.printf(Locale.ROOT,
                "=== WhyNotAC Benchmark Report ===%n"
                        + "Honest .wnrec recordings : %d (events=%d, falsePositives=%d, cost=%.2f us/event)%n"
                        + "Synthetic scenarios      : %d/%d PASSED%n",
                report.honestRecordings(),
                report.totalHonestEvents(),
                report.honestFalsePositives(),
                report.usPerEvent(),
                report.scenariosPassed(),
                report.scenariosTotal());
        for (Map.Entry<String, Integer> e : report.scenarioFindings().entrySet()) {
            out.printf(Locale.ROOT, "  %-34s -> findings=%d%n", e.getKey(), e.getValue());
        }
        out.printf(Locale.ROOT,
                "BENCHMARK_JSON: {\"honestRecordings\":%d,\"honestFalsePositives\":%d,"
                        + "\"totalHonestEvents\":%d,\"usPerEvent\":%.3f,"
                        + "\"scenariosPassed\":%d,\"scenariosTotal\":%d,\"allPassed\":%s}%n",
                report.honestRecordings(),
                report.honestFalsePositives(),
                report.totalHonestEvents(),
                report.usPerEvent(),
                report.scenariosPassed(),
                report.scenariosTotal(),
                report.allPassed());
        if (!report.allPassed()) {
            err.println("Benchmark failed: honestFalsePositives=" + report.honestFalsePositives()
                    + ", scenariosPassed=" + report.scenariosPassed() + "/" + report.scenariosTotal());
            System.exit(2);
        }
    }

    public static BenchmarkReport run(Path regressionDir) throws IOException {
        int honestRecordings = 0;
        int honestFalsePositives = 0;
        long totalHonestEvents = 0L;
        long replayElapsedNanos = 0L;

        if (regressionDir != null && Files.isDirectory(regressionDir)) {
            Set<Path> files = new TreeSet<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(regressionDir, "*.wnrec")) {
                for (Path p : ds) {
                    files.add(p);
                }
            }
            for (Path rec : files) {
                honestRecordings++;
                List<Evidence> found = new ArrayList<>();
                SessionRegistry registry = newRegistry(found);
                PlayerSession session = registry.openReplay(rec.getFileName().toString());
                RecordingReader reader = RecordingReader.open(rec);
                TimelineEvent last = null;
                long t0 = System.nanoTime();
                for (TimelineEvent ev : reader) {
                    totalHonestEvents++;
                    session.deliver(ev);
                    last = ev;
                }
                if (!session.isClosed()) {
                    long seq = last == null ? 0 : last.sequence() + 1;
                    long nano = last == null ? 0 : last.nanoTime();
                    session.deliver(new TimelineEvent.Disconnected(seq, nano));
                }
                replayElapsedNanos += Math.max(1L, System.nanoTime() - t0);
                honestFalsePositives += found.size();
            }
        }

        double usPerEvent = totalHonestEvents > 0
                ? (replayElapsedNanos / 1_000.0) / totalHonestEvents
                : 0.0;

        Map<String, Integer> scenarioFindings = new LinkedHashMap<>();
        int passed = 0;
        int total = 0;

        // 1. Honest baseline session (must produce 0 findings)
        total++;
        int fHonest = runScenarioHonest();
        scenarioFindings.put("honest-baseline-session", fHonest);
        if (fHonest == 0) passed++;

        // 2. Reach 4.2-block violation (P-A: attack-reach)
        total++;
        int fReach = runScenarioReach();
        scenarioFindings.put("reach-4.2b-violation", fReach);
        if (fReach > 0) passed++;

        // 3. Off-axis / wall raycast aim violation (P-A: attack-aim)
        total++;
        int fAim = runScenarioAimOffTarget();
        scenarioFindings.put("aim-off-target-violation", fAim);
        if (fAim > 0) passed++;

        // 4. Instant stone break (P-A: break-time)
        total++;
        int fBreak = runScenarioInstantBreak();
        scenarioFindings.put("instant-stone-break", fBreak);
        if (fBreak > 0) passed++;

        // 5. 0% vertical Anti-KB (P-A: velocity-response)
        total++;
        int fKb = runScenarioZeroAntiKb();
        scenarioFindings.put("vertical-anti-kb-0pct", fKb);
        if (fKb > 0) passed++;

        // 6. Out-of-bounds hotbar & cursor (P-M: packet-bounds)
        total++;
        int fBounds = runScenarioPacketBounds();
        scenarioFindings.put("packet-bounds-hotbar-cursor", fBounds);
        if (fBounds > 0) passed++;

        // 7. InvWalk + AutoTotem attack in same tick (P-M: inventory-protocol)
        total++;
        int fInv = runScenarioInventoryProtocol();
        scenarioFindings.put("inventory-walk-autototem", fInv);
        if (fInv > 0) passed++;

        // 8. InteractAt bounding box spoof (P-M: interact-at-box)
        total++;
        int fBox = runScenarioInteractAtBox();
        scenarioFindings.put("interact-at-box-spoof", fBox);
        if (fBox > 0) passed++;

        // 9. AutoBlock + NoSlow same-tick ordering (P-M: use-item-state)
        total++;
        int fUse = runScenarioUseSlowdown();
        scenarioFindings.put("autoblock-noslow-same-tick", fUse);
        if (fUse > 0) passed++;

        // 10. Constant-interval auto-clicker (S: click-pattern)
        total++;
        int fClick = runScenarioAutoClicker();
        scenarioFindings.put("constant-interval-autoclicker", fClick);
        if (fClick > 0) passed++;

        // 11. Oscillating slow-fast balance timer (S: timer-balance)
        total++;
        int fTimer = runScenarioTimerBalance();
        scenarioFindings.put("oscillating-timer-balance", fTimer);
        if (fTimer > 0) passed++;

        // 12. Paired KeepAlive vs Ping Backtrack RTT inflation (S: ping-spoof)
        total++;
        int fPingSpoof = runScenarioPingSpoof();
        scenarioFindings.put("ping-spoof-backtrack-rtt", fPingSpoof);
        if (fPingSpoof > 0) passed++;

        return new BenchmarkReport(
                honestRecordings,
                honestFalsePositives,
                totalHonestEvents,
                usPerEvent,
                total,
                passed,
                scenarioFindings);
    }

    private static SessionRegistry newRegistry(List<Evidence> findings) {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> {
            throw new AssertionError(t);
        });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        EvidenceSink sink = new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                findings.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {}
        };
        Checks.all(sink, replicas).forEach(registry::addListener);
        return registry;
    }

    private static Harness newHarness(String name) {
        List<Evidence> findings = new ArrayList<>();
        SessionRegistry registry = newRegistry(findings);
        PlayerSession session = registry.openReplay(name);
        Harness h = new Harness(session, findings);
        h.bootstrap();
        return h;
    }

    private static int runScenarioHonest() {
        Harness h = newHarness("bench-honest");
        for (int t = 0; t < 20; t++) {
            h.in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, (t * 3.1f) % 360.0f, 0.0f, true, true, true, false));
            h.tick();
        }
        return h.findings.size();
    }

    private static int runScenarioReach() {
        Harness h = newHarness("bench-reach");
        h.spawnZombie(2, 2.5, 64.0, 7.2); // 4.7 blocks away (> 3.0 survival reach)
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
        return countCheck(h.findings, "attack-reach");
    }

    private static int runScenarioAimOffTarget() {
        Harness h = newHarness("bench-aim");
        h.spawnZombie(2, 2.5, 64.0, 4.5); // in front (+Z, yaw=0), but player looks 180 deg away (-Z)
        h.in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 180.0f, 0.0f, true, true, true, false));
        h.tick();
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
        return countCheck(h.findings, "attack-aim");
    }

    private static int runScenarioInstantBreak() {
        Harness h = newHarness("bench-break");
        BlockPosition pos = new BlockPosition(2, 63, 3);
        h.in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, pos, BlockFace.UP, 1));
        h.tick();
        h.in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, pos, BlockFace.UP, 2));
        h.tick();
        return countCheck(h.findings, "break-time");
    }

    private static int runScenarioZeroAntiKb() {
        Harness h = newHarness("bench-kb");
        h.out(ClientboundPacket.SetEntityMotion.sent(1, new Vec3d(0.25, 0.42, 0.0)));
        h.syncPing();
        h.in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0f, 0.0f, true, false, true, false));
        h.tick();
        h.tick();
        return countCheck(h.findings, "velocity-response");
    }

    private static int runScenarioPacketBounds() {
        Harness h = newHarness("bench-bounds");
        h.in(new ServerboundPacket.SetCarriedItem(9));
        h.tick();
        return countCheck(h.findings, "packet-bounds");
    }

    private static int runScenarioInventoryProtocol() {
        Harness h = newHarness("bench-inv");
        h.in(new ServerboundPacket.ContainerClick(0, 1, 36, 0, "SWAP", List.of(), Stack.EMPTY));
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
        return countCheck(h.findings, "inventory-protocol");
    }

    private static int runScenarioInteractAtBox() {
        Harness h = newHarness("bench-box");
        h.spawnZombie(2, 2.5, 64.0, 4.5);
        h.in(new ServerboundPacket.InteractEntity(
                2, ServerboundPacket.InteractEntity.Kind.INTERACT_AT, Hand.MAIN_HAND, new Vec3d(2.5, 0.5, 0.0), false));
        h.tick();
        return countCheck(h.findings, "interact-at-box");
    }

    private static int runScenarioUseSlowdown() {
        Harness h = newHarness("bench-use");
        h.in(new ServerboundPacket.PlayerAction(
                ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM, new BlockPosition(0, 0, 0), BlockFace.DOWN, 0));
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
        return countCheck(h.findings, "use-item-state");
    }

    private static int runScenarioAutoClicker() {
        Harness h = newHarness("bench-click");
        h.spawnZombie(2, 2.5, 64.0, 4.5);
        for (int i = 0; i < 65; i++) {
            h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            h.tick();
            h.tick();
        }
        return countCheck(h.findings, "click-pattern");
    }

    private static int runScenarioTimerBalance() {
        Harness h = newHarness("bench-timer-balance");
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int t = 0; t < 11; t++) {
                h.now += 96_000_000L;
                h.in(new ServerboundPacket.ClientTickEnd());
            }
            for (int t = 0; t < 28; t++) {
                h.now += 36_000_000L;
                h.in(new ServerboundPacket.ClientTickEnd());
            }
        }
        return countCheck(h.findings, "timer-balance");
    }

    private static int runScenarioPingSpoof() {
        Harness h = newHarness("bench-ping-spoof");
        long kaSent = h.now;
        h.out(new ClientboundPacket.KeepAlive(55L));
        h.now += 25_000_000L;
        h.in(new ServerboundPacket.KeepAlive(55L));
        for (int i = 0; i < 12; i++) {
            long pingSent = h.now;
            int id = ++h.pingId;
            h.out(new ClientboundPacket.Ping(id));
            for (int t = 0; t < 5; t++) {
                h.tick();
            }
            h.session.deliver(new TimelineEvent.Inbound(h.seq++, pingSent + 250_000_000L, 0, new ServerboundPacket.Pong(id)));
            h.session.deliver(new TimelineEvent.Acknowledged(h.seq++, pingSent + 250_000_000L, id, pingSent, 0));
        }
        return countCheck(h.findings, "ping-spoof");
    }

    private static int countCheck(List<Evidence> findings, String checkId) {
        int c = 0;
        for (Evidence e : findings) {
            if (checkId.equals(e.check())) {
                c++;
            }
        }
        return c;
    }

    private static final class Harness {
        final PlayerSession session;
        final List<Evidence> findings;
        long seq;
        long now = 2_000_000_000L;
        int pingId = 10;

        Harness(PlayerSession session, List<Evidence> findings) {
            this.session = session;
            this.findings = findings;
        }

        void bootstrap() {
            UUID playerId = new UUID(11, 11);
            session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "bench"));
            session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "bench"));
            session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "bench"));
            session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "bench"));
            session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "bench"));
            session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "benchPlayer", 1));
            out(new ClientboundPacket.Login(1, 4, OVERWORLD, 0));
            out(new ClientboundPacket.PlayerInfoUpdate(
                    true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerId, 0))));
            out(new ClientboundPacket.ChunkCacheCenter(0, 0));
            out(new ClientboundPacket.LevelChunk(0, 0, flatChunk()));
            out(new ClientboundPacket.PlayerPosition(1, new Vec3d(2.5, 64.0, 2.5), Vec3d.ZERO, 0.0f, 0.0f, Set.of()));
            syncPing();
            in(new ServerboundPacket.AcceptTeleportation(1));
            in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0f, 0.0f, true, true, true, false));
            in(new ServerboundPacket.PlayerLoaded());
            tick();
            in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0f, 0.0f, true, false, true, false));
            tick();
            in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0f, 0.0f, true, false, true, false));
            tick();
        }

        void spawnZombie(int id, double x, double y, double z) {
            out(new ClientboundPacket.AddEntity(
                    id, new UUID(2, id), "minecraft:zombie", x, y, z, Vec3d.ZERO, null, (byte) 0, (byte) 0, (byte) 0, 0));
            syncPing();
        }

        void out(ClientboundPacket p) {
            session.deliver(new TimelineEvent.Outbound(seq++, now, p));
        }

        void in(ServerboundPacket p) {
            session.deliver(new TimelineEvent.Inbound(seq++, now, 0, p));
        }

        void syncPing() {
            int id = ++pingId;
            out(new ClientboundPacket.Ping(id));
            in(new ServerboundPacket.Pong(id));
            session.deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
        }

        void tick() {
            in(new ServerboundPacket.ClientTickEnd());
            now += 50_000_000L;
        }
    }

    private static byte[] flatChunk() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < OVERWORLD.sections(); i++) {
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
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
