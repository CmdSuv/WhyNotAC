package dev.whynotac.analysis.cli;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.movement.SpeedAttribute;
import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Border;
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
import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.write.BlockSink;
import dev.whynotac.recording.write.RecordingWriter;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Replicas;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.Deflater;

/**
 * Reproducible TPR / FPR / TTD / Replay-Cost benchmark runner
 * ({@code java -jar WhyNotAC.jar benchmark [regression-dir] [cheat-corpus-dir]}).
 *
 * <p>Executes:
 * <ol>
 *   <li>All honest {@code *.wnrec} recordings in the regression directory (when present), verifying
 *       zero false positives and measuring per-event replay cost;</li>
 *   <li>All adversarial {@code *.wnrec} recordings in the {@code cheat-corpus} directory (when present),
 *       verifying zero false negatives;</li>
 *   <li>A built-in 25-scenario adversarial + legitimate benchmark matrix spanning {@code P-A},
 *       {@code P-M}, and {@code S} check families with per-scenario ticks-to-detect (TTD) and runtime.</li>
 * </ol>
 */
public final class BenchmarkRunner {

    private static final ClientboundPacket.Dimension OVERWORLD =
            new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);
    private static final int STONE = BlockStates.vanilla().firstStateOf("minecraft:stone");
    private static final int AIR = BlockStates.vanilla().firstStateOf("minecraft:air");

    public record ScenarioResult(String name, String expectedCheck, boolean expectFinding, int findings, long ttdTicks, double elapsedMs) {
        public boolean passed() {
            return expectFinding ? findings > 0 : findings == 0;
        }
    }

    public record BenchmarkReport(
            int honestRecordings,
            int honestFalsePositives,
            long totalHonestEvents,
            double usPerEvent,
            int cheatRecordings,
            int cheatFalseNegatives,
            long totalCheatEvents,
            int scenariosTotal,
            int scenariosPassed,
            Map<String, Integer> scenarioFindings,
            List<ScenarioResult> scenarioResults) {

        public boolean allPassed() {
            return honestFalsePositives == 0 && cheatFalseNegatives == 0 && scenariosPassed == scenariosTotal;
        }
    }

    private BenchmarkRunner() {}

    public static void runCli(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length >= 3 && "--write-corpus".equals(args[1])) {
            Path outDir = Path.of(args[2]);
            int written = generateCheatCorpus(outDir);
            out.println("Wrote " + written + " cheat corpus recordings to " + outDir.toAbsolutePath());
            return;
        }
        Path regressionDir = args.length >= 2 ? Path.of(args[1]) : Path.of("regression");
        Path cheatCorpusDir = args.length >= 3 ? Path.of(args[2]) : defaultCheatCorpus(regressionDir);
        BenchmarkReport report = run(regressionDir, cheatCorpusDir);
        out.printf(Locale.ROOT,
                "=== WhyNotAC Benchmark Report ===%n"
                        + "Honest .wnrec recordings : %d (events=%d, falsePositives=%d, cost=%.2f us/event)%n"
                        + "Cheat  .wnrec corpus     : %d (events=%d, falseNegatives=%d)%n"
                        + "Synthetic scenarios      : %d/%d PASSED%n",
                report.honestRecordings(),
                report.totalHonestEvents(),
                report.honestFalsePositives(),
                report.usPerEvent(),
                report.cheatRecordings(),
                report.totalCheatEvents(),
                report.cheatFalseNegatives(),
                report.scenariosPassed(),
                report.scenariosTotal());
        for (ScenarioResult r : report.scenarioResults()) {
            out.printf(Locale.ROOT, "  %-36s [%-18s] -> findings=%d, ttd=%dt, time=%.2fms (%s)%n",
                    r.name(),
                    r.expectedCheck() == null ? "honest" : r.expectedCheck(),
                    r.findings(),
                    r.ttdTicks(),
                    r.elapsedMs(),
                    r.passed() ? "PASS" : "FAIL");
        }
        out.printf(Locale.ROOT,
                "BENCHMARK_JSON: {\"honestRecordings\":%d,\"honestFalsePositives\":%d,"
                        + "\"totalHonestEvents\":%d,\"usPerEvent\":%.3f,"
                        + "\"cheatRecordings\":%d,\"cheatFalseNegatives\":%d,\"totalCheatEvents\":%d,"
                        + "\"scenariosPassed\":%d,\"scenariosTotal\":%d,\"allPassed\":%s}%n",
                report.honestRecordings(),
                report.honestFalsePositives(),
                report.totalHonestEvents(),
                report.usPerEvent(),
                report.cheatRecordings(),
                report.cheatFalseNegatives(),
                report.totalCheatEvents(),
                report.scenariosPassed(),
                report.scenariosTotal(),
                report.allPassed());
        if (!report.allPassed()) {
            err.println("Benchmark failed: honestFalsePositives=" + report.honestFalsePositives()
                    + ", cheatFalseNegatives=" + report.cheatFalseNegatives()
                    + ", scenariosPassed=" + report.scenariosPassed() + "/" + report.scenariosTotal());
            System.exit(2);
        }
    }

    private static Path defaultCheatCorpus(Path regressionDir) {
        if (regressionDir == null) {
            return Path.of("cheat-corpus");
        }
        Path parent = regressionDir.getParent();
        return parent == null ? Path.of("cheat-corpus") : parent.resolve("cheat-corpus");
    }

    public static BenchmarkReport run(Path regressionDir) throws IOException {
        return run(regressionDir, defaultCheatCorpus(regressionDir));
    }

    public static BenchmarkReport run(Path regressionDir, Path cheatCorpusDir) throws IOException {
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

        Map<String, Integer> scenarioFindings = new LinkedHashMap<>();
        int cheatRecordings = 0;
        int cheatFalseNegatives = 0;
        long totalCheatEvents = 0L;
        if (cheatCorpusDir != null && Files.isDirectory(cheatCorpusDir)) {
            Set<Path> cheatFiles = new TreeSet<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(cheatCorpusDir, "*.wnrec")) {
                for (Path p : ds) {
                    cheatFiles.add(p);
                }
            }
            for (Path rec : cheatFiles) {
                cheatRecordings++;
                List<Evidence> found = new ArrayList<>();
                SessionRegistry registry = newRegistry(found);
                PlayerSession session = registry.openReplay(rec.getFileName().toString());
                RecordingReader reader = RecordingReader.open(rec);
                TimelineEvent last = null;
                for (TimelineEvent ev : reader) {
                    totalCheatEvents++;
                    session.deliver(ev);
                    last = ev;
                }
                if (!session.isClosed()) {
                    long seq = last == null ? 0 : last.sequence() + 1;
                    long nano = last == null ? 0 : last.nanoTime();
                    session.deliver(new TimelineEvent.Disconnected(seq, nano));
                }
                scenarioFindings.put("corpus:" + rec.getFileName(), found.size());
                if (found.isEmpty()) {
                    cheatFalseNegatives++;
                }
            }
        }

        double usPerEvent = totalHonestEvents > 0
                ? (replayElapsedNanos / 1_000.0) / totalHonestEvents
                : 0.0;

        List<ScenarioResult> scenarioResults = new ArrayList<>();
        // Legitimate baselines (0 findings expected)
        scenarioResults.add(measure("honest-baseline-session", null, false, h -> scenarioHonest(h)));
        scenarioResults.add(measure("honest-lag-burst-with-pings", null, false, h -> scenarioHonestLagBurst(h)));
        scenarioResults.add(measure("honest-sprint-jump-motion", null, false, h -> scenarioHonestSprint(h)));
        scenarioResults.add(measure("honest-water-bucket-place", null, false, h -> scenarioHonestWaterBucket(h)));
        scenarioResults.add(measure("honest-double-slab-merge", null, false, h -> scenarioHonestDoubleSlabMerge(h)));
        scenarioResults.add(measure("honest-instant-break-slime", null, false, h -> scenarioHonestInstantBreakSlime(h)));
        scenarioResults.add(measure("honest-pvp-jump-reset", null, false, h -> scenarioHonestPvpJumpReset(h)));
        // Deterministic P-A physics/combat checks
        scenarioResults.add(measure("reach-4.2b-violation", "attack-reach", true, h -> scenarioReach(h)));
        scenarioResults.add(measure("aim-off-target-violation", "attack-aim", true, h -> scenarioAimOffTarget(h)));
        scenarioResults.add(measure("instant-stone-break", "break-time", true, h -> scenarioInstantBreak(h)));
        scenarioResults.add(measure("block-reach-6.5b-violation", "use-aim", true, h -> scenarioUseAimReach(h)));
        scenarioResults.add(measure("movement-step-fly-violation", "movement", true, h -> scenarioMovementFly(h)));
        scenarioResults.add(measure("vertical-anti-kb-0pct", "velocity-response", true, h -> scenarioZeroAntiKb(h)));
        scenarioResults.add(measure("partial-vertical-anti-kb-30pct", "velocity-response", true, h -> scenarioPartialVerticalAntiKb(h)));
        scenarioResults.add(measure("explosion-anti-kb", "velocity-response", true, h -> scenarioExplosionAntiKb(h)));
        scenarioResults.add(measure("horizontal-anti-kb-0pct", "velocity-response", true, h -> scenarioHorizontalAntiKb(h)));
        scenarioResults.add(measure("partial-horizontal-anti-kb-30pct", "velocity-response", true, h -> scenarioPartialHorizontalAntiKb(h)));
        // Deterministic P-M grammar/protocol checks
        scenarioResults.add(measure("packet-bounds-hotbar-cursor", "packet-bounds", true, h -> scenarioPacketBounds(h)));
        scenarioResults.add(measure("packet-bounds-pitch-flip", "packet-bounds", true, h -> scenarioPacketBoundsPitchFlip(h)));
        scenarioResults.add(measure("packet-bounds-duplicate-hotbar", "packet-bounds", true, h -> scenarioPacketBoundsDuplicateHotbar(h)));
        scenarioResults.add(measure("inventory-walk-autototem", "inventory-protocol", true, h -> scenarioInventoryProtocol(h)));
        scenarioResults.add(measure("unopened-container-click", "inventory-protocol", true, h -> scenarioUnopenedContainerClick(h)));
        scenarioResults.add(measure("interact-at-box-spoof", "interact-at-box", true, h -> scenarioInteractAtBox(h)));
        scenarioResults.add(measure("autoblock-noslow-same-tick", "use-item-state", true, h -> scenarioUseSlowdown(h)));
        scenarioResults.add(measure("shield-autoblock-cross-tick", "use-item-state", true, h -> scenarioCrossTickShieldAutoBlock(h)));
        scenarioResults.add(measure("tick-framing-multi-move", "tick-framing", true, h -> scenarioTickFraming(h)));
        scenarioResults.add(measure("transaction-skipped-ping", "transaction-order", true, h -> scenarioTransactionSkippedPing(h)));
        scenarioResults.add(measure("transaction-duplicate-pong", "transaction-order", true, h -> scenarioTransactionDuplicatePong(h)));
        // Statistical S checks (ALERT only)
        scenarioResults.add(measure("constant-interval-autoclicker", "click-pattern", true, h -> scenarioAutoClicker(h)));
        scenarioResults.add(measure("oscillating-timer-balance", "timer-balance", true, h -> scenarioTimerBalance(h)));
        scenarioResults.add(measure("ping-spoof-backtrack-rtt", "ping-spoof", true, h -> scenarioPingSpoof(h)));
        scenarioResults.add(measure("combat-rtt-drop-backtrack", "ping-spoof", true, h -> scenarioCombatRttDrop(h)));

        int passed = 0;
        for (ScenarioResult r : scenarioResults) {
            scenarioFindings.put(r.name(), r.findings());
            if (r.passed()) {
                passed++;
            }
        }

        return new BenchmarkReport(
                honestRecordings,
                honestFalsePositives,
                totalHonestEvents,
                usPerEvent,
                cheatRecordings,
                cheatFalseNegatives,
                totalCheatEvents,
                scenarioResults.size(),
                passed,
                scenarioFindings,
                List.copyOf(scenarioResults));
    }

    @FunctionalInterface
    private interface ScenarioBody {
        void run(Harness h);
    }

    private static ScenarioResult measure(String name, String checkId, boolean expectFinding, ScenarioBody body) {
        long t0 = System.nanoTime();
        Harness h = newHarness("bench-" + name, null);
        h.watchedCheck = checkId;
        body.run(h);
        double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
        int findings = checkId == null ? h.findings.size() : countCheck(h.findings, checkId);
        long ttd = findings == 0 ? (expectFinding ? -1L : 0L)
                : Math.max(1L, h.firstFindingTick - Math.max(0, h.cheatStartTick) + 1L);
        return new ScenarioResult(name, checkId, expectFinding, findings, ttd, elapsedMs);
    }

    /** Generates deterministic adversarial {@code .wnrec} files into {@code outputDir}. */
    public static int generateCheatCorpus(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        record CorpusEntry(String fileName, ScenarioBody body) {}
        List<CorpusEntry> entries = List.of(
                new CorpusEntry("cheat-reach-4.2b.wnrec", BenchmarkRunner::scenarioReach),
                new CorpusEntry("cheat-movement-fly.wnrec", BenchmarkRunner::scenarioMovementFly),
                new CorpusEntry("cheat-velocity-antikb.wnrec", BenchmarkRunner::scenarioZeroAntiKb),
                new CorpusEntry("cheat-invwalk-bounds.wnrec", h -> {
                    scenarioInventoryProtocol(h);
                    scenarioPacketBounds(h);
                }),
                new CorpusEntry("cheat-tick-framing-timer.wnrec", h -> {
                    scenarioTickFraming(h);
                    scenarioTimerBalance(h);
                }),
                new CorpusEntry("cheat-ping-spoof.wnrec", BenchmarkRunner::scenarioPingSpoof),
                new CorpusEntry("cheat-shield-autoblock.wnrec", BenchmarkRunner::scenarioCrossTickShieldAutoBlock),
                new CorpusEntry("cheat-partial-velocity-explosion.wnrec", h -> {
                    scenarioPartialVerticalAntiKb(h);
                    h.now += 2_000_000_000L;
                    scenarioExplosionAntiKb(h);
                    h.now += 2_000_000_000L;
                    scenarioPartialHorizontalAntiKb(h);
                }),
                new CorpusEntry("cheat-pitch-hotbar-spam.wnrec", h -> {
                    scenarioPacketBoundsPitchFlip(h);
                    h.now += 2_000_000_000L;
                    scenarioPacketBoundsDuplicateHotbar(h);
                }),
                new CorpusEntry("cheat-transaction-skip-dup.wnrec", h -> {
                    scenarioTransactionSkippedPing(h);
                    h.now += 2_000_000_000L;
                    scenarioTransactionDuplicatePong(h);
                }));
        for (CorpusEntry entry : entries) {
            Path file = outputDir.resolve(entry.fileName());
            writeScenarioRecording(file, entry.fileName(), entry.body());
        }
        return entries.size();
    }

    private static void writeScenarioRecording(Path file, String name, ScenarioBody body) throws IOException {
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            IOException[] failure = {null};
            RecordingHeader header = new RecordingHeader(
                    RecordingFormat.VERSION,
                    774,
                    "1.21.11",
                    "whynotac-bench",
                    UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
                    new UUID(11, 11),
                    1_700_000_000_000L);
            writeChannel(channel, header.encode());
            BlockSink sink = new BlockSink() {
                @Override
                public boolean accept(Block block) {
                    try {
                        writeChannel(channel, block.encode(deflater));
                    } catch (IOException ex) {
                        failure[0] = ex;
                    }
                    return true;
                }

                @Override
                public void close(long totalEvents, long lostEvents) {
                    ByteSink end = new ByteSink(32);
                    EventCodec.Encoder.encodeEnd(totalEvents, lostEvents, end);
                    accept(new Block(0, 0, 0, 0, 0, end.toByteArray()));
                }
            };
            RecordingWriter writer = new RecordingWriter(sink, RecordingWriter.DEFAULT_TARGET_BLOCK_BYTES, Long.MAX_VALUE);
            Harness h = newHarness(name, writer);
            body.run(h);
            h.deliver(new TimelineEvent.Disconnected(h.seq++, h.now));
            writer.close();
            deflater.end();
            if (failure[0] != null) {
                throw failure[0];
            }
            channel.force(true);
        }
    }

    private static void writeChannel(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
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

    private static Harness newHarness(String name, RecordingWriter recorder) {
        List<Evidence> findings = new ArrayList<>();
        SessionRegistry registry = newRegistry(findings);
        PlayerSession session = registry.openReplay(name);
        Harness h = new Harness(session, findings, recorder);
        h.bootstrap();
        return h;
    }

    private static void scenarioHonest(Harness h) {
        for (int t = 0; t < 20; t++) {
            h.stepVanilla(Harness.KEYS_NONE, (t * 3.1f) % 360.0f, 0.0f);
        }
    }

    private static void scenarioHonestLagBurst(Harness h) {
        for (int t = 0; t < 12; t++) {
            h.stepVanilla(Harness.KEYS_NONE, (t * 1.5f) % 360.0f, 0.0f);
        }
        // Sudden 180 ms gap then buffered ticks: legitimate client stall must yield 0 findings
        h.now += 180_000_000L;
        for (int t = 0; t < 5; t++) {
            h.now += 5_000_000L;
            h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        }
    }

    private static void scenarioHonestSprint(Harness h) {
        PlayerState.Keys forward = new PlayerState.Keys(true, false, false, false, false, false, false);
        for (int i = 0; i < 6; i++) {
            h.stepVanilla(forward, 0.0f, 0.0f);
            h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        }
    }

    private static void scenarioHonestWaterBucket(Harness h) {
        List<Stack> inv = new ArrayList<>(46);
        for (int i = 0; i < 46; i++) {
            inv.add(Stack.EMPTY);
        }
        inv.set(36, new Stack("minecraft:water_bucket", 1, List.of(), List.of(), null, false));
        h.out(new ClientboundPacket.ContainerSetContent(0, 2, inv, Stack.EMPTY));
        h.syncPing();
        // Aim slightly ahead onto stone (2, 63, 4) from (2.5, 65.62, 2.5) and place water cleanly
        h.stepVanilla(Harness.KEYS_NONE, 0.0f, 39.0f);
        h.in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 1, 0.0f, 39.0f));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.stepVanilla(Harness.KEYS_NONE, 0.0f, 39.0f);
        List<Stack> after = new ArrayList<>(inv);
        after.set(36, new Stack("minecraft:bucket", 1, List.of(), List.of(), null, false));
        h.out(new ClientboundPacket.ContainerSetContent(0, 3, after, Stack.EMPTY));
        h.out(new ClientboundPacket.BlockUpdate(
                new BlockPosition(2, 64, 4), BlockStates.vanilla().stateOf("minecraft:water", "level=0")));
        h.out(new ClientboundPacket.BlockChangedAck(1));
        h.syncPing();
        for (int t = 0; t < 5; t++) {
            h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        }
    }

    private static void scenarioHonestDoubleSlabMerge(Harness h) {
        BlockStates states = BlockStates.vanilla();
        int bottomSlab = states.stateOf("minecraft:stone_slab", "type=bottom,waterlogged=false");
        int doubleSlab = states.stateOf("minecraft:stone_slab", "type=double,waterlogged=false");
        // Settle residual forward velocity from bootstrap() so the client eye is stationary at the click tick
        for (int t = 0; t < 6; t++) {
            h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        }
        int tx = (int) Math.floor(h.player.x());
        int tz = (int) Math.floor(h.player.z()) + 2;
        h.out(new ClientboundPacket.BlockUpdate(new BlockPosition(tx, 64, tz), bottomSlab));
        List<Stack> inv = new ArrayList<>(46);
        for (int i = 0; i < 46; i++) {
            inv.add(Stack.EMPTY);
        }
        inv.set(36, new Stack("minecraft:stone_slab", 64, List.of(), List.of(), null, false));
        h.out(new ClientboundPacket.ContainerSetContent(0, 2, inv, Stack.EMPTY));
        h.syncPing();
        // Aim squarely at the top face (y=64.5) of the bottom slab and merge into type=double
        double eyeX = h.player.x();
        double eyeY = h.player.y() + 1.62;
        double eyeZ = h.player.z();
        double dx = (tx + 0.5) - eyeX;
        double dy = 64.5 - eyeY;
        double dz = (tz + 0.5) - eyeZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz)));
        h.stepVanilla(Harness.KEYS_NONE, yaw, pitch);
        eyeX = h.player.x();
        eyeY = h.player.y() + 1.62;
        eyeZ = h.player.z();
        Vec3d look = Vec3d.viewVector(pitch, yaw);
        double tHit = (64.5 - eyeY) / look.y();
        Vec3d hit = new Vec3d(eyeX + look.x() * tHit, 64.5, eyeZ + look.z() * tHit);
        h.in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(tx, 64, tz), BlockFace.UP, hit, false, false, 1));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.stepVanilla(Harness.KEYS_NONE, yaw, pitch);
        h.out(new ClientboundPacket.BlockUpdate(new BlockPosition(tx, 64, tz), doubleSlab));
        h.out(new ClientboundPacket.BlockChangedAck(1));
        h.syncPing();
        for (int t = 0; t < 4; t++) {
            h.stepVanilla(Harness.KEYS_NONE, yaw, pitch);
        }
    }

    private static void scenarioHonestInstantBreakSlime(Harness h) {
        BlockStates states = BlockStates.vanilla();
        int slime = states.onlyStateOf("minecraft:slime_block");
        int air = states.onlyStateOf("minecraft:air");
        for (int t = 0; t < 6; t++) {
            h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        }
        int tx = (int) Math.floor(h.player.x());
        int tz = (int) Math.floor(h.player.z() + 0.4) + 1;
        h.out(new ClientboundPacket.BlockUpdate(new BlockPosition(tx, 64, tz), slime));
        h.syncPing();
        double eyeX = h.player.x();
        double eyeY = h.player.y() + 1.62;
        double eyeZ = h.player.z();
        double dx = (tx + 0.5) - eyeX;
        double dy = 64.5 - eyeY;
        double dz = tz - eyeZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz)));
        h.stepVanilla(Harness.KEYS_NONE, yaw, pitch);
        // Instant-break slime_block with START_DESTROY_BLOCK and walk straight into (tx, 64, tz) before BlockChangedAck
        h.in(new ServerboundPacket.PlayerAction(
                ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, new BlockPosition(tx, 64, tz), BlockFace.NORTH, 1));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        PlayerState.Keys forward = new PlayerState.Keys(true, false, false, false, false, false, false);
        for (int t = 0; t < 8; t++) {
            h.stepVanilla(forward, yaw, pitch);
        }
        h.out(new ClientboundPacket.BlockUpdate(new BlockPosition(tx, 64, tz), air));
        h.out(new ClientboundPacket.BlockChangedAck(1));
        h.syncPing();
        for (int t = 0; t < 3; t++) {
            h.stepVanilla(Harness.KEYS_NONE, yaw, pitch);
        }
    }

    private static void scenarioHonestPvpJumpReset(Harness h) {
        // Settle facing west (-X, yaw=90), then receive +0.36 X / +0.40 Y knockback and execute a vanilla PvP Jump-Reset
        for (int t = 0; t < 6; t++) {
            h.stepVanilla(Harness.KEYS_NONE, 90.0f, 0.0f);
        }
        ClientboundPacket.SetEntityMotion kb = ClientboundPacket.SetEntityMotion.sent(1, new Vec3d(0.36, 0.40, 0.0));
        h.out(kb);
        h.syncPing();
        PlayerState p = h.player;
        h.player = new PlayerState(p.x(), p.y(), p.z(), kb.clientMotion(), p.yRot(), p.xRot(),
                p.onGround(), p.horizontalCollision(), p.minorHorizontalCollision(), p.verticalCollision(),
                p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), p.sprintModifier(), p.crouching(),
                p.pose(), p.keys(), p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(),
                p.onGroundNoBlocks(), p.stuckSpeedMultiplier(), p.swimming(), p.eyeInWater(),
                p.fallFlying(), p.inPowderSnow(), p.inWater(), p.spinAttackTicks());
        PlayerState.Keys jumpSprint = new PlayerState.Keys(true, false, false, false, true, false, true);
        PlayerState.Keys forwardSprint = new PlayerState.Keys(true, false, false, false, false, false, true);
        h.stepVanilla(jumpSprint, 90.0f, 0.0f);
        for (int t = 0; t < 4; t++) {
            h.stepVanilla(forwardSprint, 90.0f, 0.0f);
        }
    }

    private static void scenarioReach(Harness h) {
        h.markCheatStart();
        h.spawnZombie(2, 2.5, 64.0, 7.2); // 4.7 blocks away (> 3.0 survival reach)
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
    }

    private static void scenarioAimOffTarget(Harness h) {
        h.spawnZombie(2, 2.5, 64.0, 4.5); // in front (+Z, yaw=0), but player looks 180 deg away (-Z)
        h.stepVanilla(Harness.KEYS_NONE, 180.0f, 0.0f);
        h.markCheatStart();
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
    }

    private static void scenarioInstantBreak(Harness h) {
        h.markCheatStart();
        BlockPosition pos = new BlockPosition(2, 63, 3);
        h.in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, pos, BlockFace.UP, 1));
        h.tick();
        h.in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, pos, BlockFace.UP, 2));
        h.tick();
    }

    private static void scenarioUseAimReach(Harness h) {
        h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        h.out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 65, 9), STONE));
        h.syncPing();
        h.markCheatStart();
        h.in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(2, 65, 9), BlockFace.NORTH,
                new Vec3d(2.5, 65.62, 9.0), false, false, 1));
        h.tick();
    }

    private static void scenarioMovementFly(Harness h) {
        h.markCheatStart();
        h.in(new ServerboundPacket.MovePlayer(h.player.x(), h.player.y() + 0.65, h.player.z(), 0.0f, 0.0f, true, false, false, false));
        h.tick();
    }

    private static void scenarioZeroAntiKb(Harness h) {
        h.markCheatStart();
        h.out(ClientboundPacket.SetEntityMotion.sent(1, new Vec3d(0.25, 0.42, 0.0)));
        h.syncPing();
        h.in(new ServerboundPacket.MovePlayer(h.player.x(), h.player.y(), h.player.z(), 0.0f, 0.0f, true, false, true, false));
        h.tick();
        h.tick();
    }

    private static void scenarioPartialVerticalAntiKb(Harness h) {
        h.markCheatStart();
        h.out(ClientboundPacket.SetEntityMotion.sent(1, new Vec3d(0.0, 0.42, 0.0)));
        h.syncPing();
        // Cheater takes only 30% vertical knockback (+0.126) and spoofs onGround=false
        h.in(new ServerboundPacket.MovePlayer(h.player.x(), h.player.y() + 0.126, h.player.z(), 0.0f, 0.0f, true, false, false, false));
        h.tick();
        h.in(new ServerboundPacket.MovePlayer(h.player.x(), h.player.y() + 0.14, h.player.z(), 0.0f, 0.0f, true, false, false, false));
        h.tick();
    }

    private static void scenarioExplosionAntiKb(Harness h) {
        h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        h.markCheatStart();
        h.out(new ClientboundPacket.Explosion(
                new Vec3d(h.player.x() - 1.0, h.player.y(), h.player.z()), 4.0f, new Vec3d(0.45, 0.50, 0.0)));
        h.syncPing();
        h.in(new ServerboundPacket.MovePlayer(h.player.x(), h.player.y(), h.player.z(), 0.0f, 0.0f, true, false, true, false));
        h.tick();
        h.tick();
    }

    private static void scenarioHorizontalAntiKb(Harness h) {
        h.markCheatStart();
        h.out(ClientboundPacket.SetEntityMotion.sent(1, new Vec3d(0.45, 0.36, 0.0)));
        h.syncPing();
        // Cheater accepts upward jump but suppresses all horizontal displacement
        h.in(new ServerboundPacket.MovePlayer(h.player.x(), h.player.y() + 0.36, h.player.z(), 0.0f, 0.0f, true, false, false, false));
        h.tick();
        h.tick();
    }

    private static void scenarioPartialHorizontalAntiKb(Harness h) {
        // Settle stationary with no input so zero counter-impulse is possible
        for (int t = 0; t < 6; t++) {
            h.stepVanilla(Harness.KEYS_NONE, 0.0f, 0.0f);
        }
        h.markCheatStart();
        h.out(ClientboundPacket.SetEntityMotion.sent(1, new Vec3d(0.50, 0.36, 0.0)));
        h.syncPing();
        // Cheater accepts vertical rise (+0.36) but reduces horizontal knockback to 30% (+0.15 < 0.225)
        h.in(new ServerboundPacket.MovePlayer(h.player.x() + 0.15, h.player.y() + 0.36, h.player.z(), 0.0f, 0.0f, true, false, false, false));
        h.tick();
        h.in(new ServerboundPacket.MovePlayer(h.player.x() + 0.15, h.player.y() + 0.60, h.player.z(), 0.0f, 0.0f, true, false, false, false));
        h.tick();
    }

    private static void scenarioPacketBounds(Harness h) {
        h.markCheatStart();
        h.in(new ServerboundPacket.SetCarriedItem(9));
        h.tick();
    }

    private static void scenarioPacketBoundsPitchFlip(Harness h) {
        h.markCheatStart();
        h.in(new ServerboundPacket.MovePlayer(h.player.x(), h.player.y(), h.player.z(), 0.0f, 105.0f, true, true, true, false));
        h.tick();
    }

    private static void scenarioPacketBoundsDuplicateHotbar(Harness h) {
        h.in(new ServerboundPacket.SetCarriedItem(2));
        h.tick();
        h.markCheatStart();
        h.in(new ServerboundPacket.SetCarriedItem(2));
        h.tick();
    }

    private static void scenarioInventoryProtocol(Harness h) {
        h.markCheatStart();
        h.in(new ServerboundPacket.ContainerClick(0, 1, 36, 0, "SWAP", List.of(), Stack.EMPTY));
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
    }

    private static void scenarioUnopenedContainerClick(Harness h) {
        h.markCheatStart();
        h.in(new ServerboundPacket.ContainerClick(3, 1, 5, 0, "PICKUP", List.of(), Stack.EMPTY));
        h.tick();
    }

    private static void scenarioInteractAtBox(Harness h) {
        h.spawnZombie(2, 2.5, 64.0, 4.5);
        h.markCheatStart();
        h.in(new ServerboundPacket.InteractEntity(
                2, ServerboundPacket.InteractEntity.Kind.INTERACT_AT, Hand.MAIN_HAND, new Vec3d(2.5, 0.5, 0.0), false));
        h.tick();
    }

    private static void scenarioUseSlowdown(Harness h) {
        h.markCheatStart();
        h.in(new ServerboundPacket.PlayerAction(
                ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM, new BlockPosition(0, 0, 0), BlockFace.DOWN, 0));
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
    }

    private static void scenarioCrossTickShieldAutoBlock(Harness h) {
        h.spawnZombie(2, 2.5, 64.0, 4.5);
        List<Stack> inv = new ArrayList<>(46);
        for (int i = 0; i < 46; i++) {
            inv.add(Stack.EMPTY);
        }
        inv.set(36, new Stack("minecraft:diamond_sword", 1, List.of(), List.of(), null, false));
        inv.set(45, new Stack("minecraft:shield", 1, List.of(), List.of(), null, false));
        h.out(new ClientboundPacket.ContainerSetContent(0, 2, inv, Stack.EMPTY));
        h.syncPing();
        h.in(new ServerboundPacket.UseItem(Hand.OFF_HAND, 1, 0.0f, 0.0f));
        h.tick();
        h.markCheatStart();
        // Keep-using AutoBlock: attacks while offhand shield remains raised across ticks without release or swap
        h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
        h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
        h.tick();
    }

    private static void scenarioTickFraming(Harness h) {
        h.markCheatStart();
        h.in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0f, 0.0f, true, false, true, false));
        h.in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0f, 0.0f, true, false, true, false));
        h.tick();
    }

    private static void scenarioTransactionSkippedPing(Harness h) {
        h.markCheatStart();
        int p1 = ++h.pingId;
        int p2 = ++h.pingId;
        h.out(new ClientboundPacket.Ping(p1));
        h.out(new ClientboundPacket.Ping(p2));
        h.in(new ServerboundPacket.Pong(p2));
        h.deliver(new TimelineEvent.Acknowledged(h.seq++, h.now, p2, h.now, 1));
        h.tick();
    }

    private static void scenarioTransactionDuplicatePong(Harness h) {
        int p1 = ++h.pingId;
        h.out(new ClientboundPacket.Ping(p1));
        h.in(new ServerboundPacket.Pong(p1));
        h.deliver(new TimelineEvent.Acknowledged(h.seq++, h.now, p1, h.now, 0));
        h.tick();
        h.markCheatStart();
        h.in(new ServerboundPacket.Pong(p1));
        h.deliver(new TimelineEvent.Anomaly(h.seq++, h.now, TimelineEvent.Anomaly.Kind.DUPLICATE_TRANSACTION,
                "pong " + Integer.toHexString(p1)));
        h.tick();
    }

    private static void scenarioAutoClicker(Harness h) {
        h.spawnZombie(2, 2.5, 64.0, 4.5);
        h.markCheatStart();
        for (int i = 0; i < 65; i++) {
            h.in(new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
            h.in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
            h.tick();
            h.tick();
        }
    }

    private static void scenarioTimerBalance(Harness h) {
        // Complete the initial 1.0s warmup bucket cleanly (21st tick is at t = +1.000s)
        while (h.tickCount <= 20) {
            h.tick();
        }
        h.markCheatStart();
        for (int cycle = 0; cycle < 5; cycle++) {
            for (int t = 0; t < 11; t++) {
                h.now += 96_000_000L;
                h.in(new ServerboundPacket.ClientTickEnd());
                h.noteTick();
            }
            for (int t = 0; t < 28; t++) {
                h.now += 36_000_000L;
                h.in(new ServerboundPacket.ClientTickEnd());
                h.noteTick();
            }
        }
    }

    private static void scenarioPingSpoof(Harness h) {
        h.out(new ClientboundPacket.KeepAlive(55L));
        h.now += 25_000_000L;
        h.in(new ServerboundPacket.KeepAlive(55L));
        h.markCheatStart();
        for (int i = 0; i < 16; i++) {
            long pingSent = h.now;
            int id = ++h.pingId;
            h.out(new ClientboundPacket.Ping(id));
            for (int t = 0; t < 5; t++) {
                h.tick();
            }
            h.deliver(new TimelineEvent.Inbound(h.seq++, pingSent + 250_000_000L, 0, new ServerboundPacket.Pong(id)));
            h.deliver(new TimelineEvent.Acknowledged(h.seq++, pingSent + 250_000_000L, id, pingSent, 0));
        }
    }

    private static void scenarioCombatRttDrop(Harness h) {
        h.out(new ClientboundPacket.KeepAlive(77L));
        h.now += 25_000_000L;
        h.in(new ServerboundPacket.KeepAlive(77L));
        h.markCheatStart();
        for (int cycle = 0; cycle < 2; cycle++) {
            if (cycle == 1) {
                for (int t = 0; t < 12; t++) {
                    h.tick();
                }
            }
            for (int i = 0; i < 5; i++) {
                long pingSent = h.now;
                int id = ++h.pingId;
                h.out(new ClientboundPacket.Ping(id));
                h.tick();
                long ackTime = pingSent + 260_000_000L;
                h.deliver(new TimelineEvent.Inbound(h.seq++, ackTime, 0, new ServerboundPacket.Pong(id)));
                h.deliver(new TimelineEvent.Acknowledged(h.seq++, ackTime, id, pingSent, 0));
            }
            long dropPingSent = h.now;
            int dropId = ++h.pingId;
            h.out(new ClientboundPacket.Ping(dropId));
            h.deliver(new TimelineEvent.Inbound(h.seq++, dropPingSent + 10_000_000L, 0,
                    new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
            h.tick();
            long dropAckTime = dropPingSent + 30_000_000L;
            h.deliver(new TimelineEvent.Inbound(h.seq++, dropAckTime, 0, new ServerboundPacket.Pong(dropId)));
            h.deliver(new TimelineEvent.Acknowledged(h.seq++, dropAckTime, dropId, dropPingSent, 0));
        }
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

    private static final Colliders.World FLAT_WORLD = new Colliders.World() {
        @Override
        public BlockStates states() {
            return BlockStates.vanilla();
        }

        @Override
        public int blockState(int x, int y, int z) {
            return y < 64 ? STONE : AIR;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }
    };

    private static final class Harness {
        static final PlayerState.Keys KEYS_NONE = new PlayerState.Keys(false, false, false, false, false, false, false);
        final PlayerSession session;
        final List<Evidence> findings;
        final RecordingWriter recorder;
        long seq;
        long now = 2_000_000_000L;
        int pingId = 10;
        int tickCount;
        int cheatStartTick = -1;
        int firstFindingTick = -1;
        String watchedCheck;
        PlayerState player;
        boolean firstTick = true;
        OnFoot.Speed speed = SpeedAttribute.DEFAULT;
        PlayerState.Keys sentInput = KEYS_NONE;
        boolean sentSprinting;
        double sentX;
        double sentY;
        double sentZ;
        float sentYRot;
        float sentXRot;
        boolean sentOnGround;
        boolean sentCollision;
        int reminder;

        Harness(PlayerSession session, List<Evidence> findings, RecordingWriter recorder) {
            this.session = session;
            this.findings = findings;
            this.recorder = recorder;
        }

        void deliver(TimelineEvent ev) {
            if (recorder != null) {
                recorder.append(ev);
            }
            int before = findings.size();
            session.deliver(ev);
            if (firstFindingTick < 0 && findings.size() > before) {
                for (int i = before; i < findings.size(); i++) {
                    if (watchedCheck == null || watchedCheck.equals(findings.get(i).check())) {
                        firstFindingTick = tickCount;
                        break;
                    }
                }
            }
        }

        void markCheatStart() {
            cheatStartTick = tickCount;
        }

        void bootstrap() {
            UUID playerId = new UUID(11, 11);
            deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "bench"));
            deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "bench"));
            deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "bench"));
            deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "bench"));
            deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "bench"));
            deliver(new TimelineEvent.Bound(seq++, now, playerId, "benchPlayer", 1));
            out(minimalTags());
            out(new ClientboundPacket.Login(1, 4, OVERWORLD, 0));
            out(new ClientboundPacket.PlayerInfoUpdate(
                    true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerId, 0))));
            out(new ClientboundPacket.ChunkCacheCenter(0, 0));
            byte[] chunk = flatChunk();
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    out(new ClientboundPacket.LevelChunk(cx, cz, chunk));
                }
            }
            out(new ClientboundPacket.SetHeldSlot(0));
            List<Stack> inv = new ArrayList<>(46);
            for (int i = 0; i < 46; i++) {
                inv.add(Stack.EMPTY);
            }
            out(new ClientboundPacket.ContainerSetContent(0, 1, inv, Stack.EMPTY));
            player = new PlayerState(2.5, 64.0, 2.5, Vec3d.ZERO, -180.0f, 0.0f, false, false, false, false, false, 0.0,
                    false, false, false, PlayerState.Pose.STANDING, KEYS_NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, false);
            out(new ClientboundPacket.PlayerPosition(1, new Vec3d(2.5, 64.0, 2.5), Vec3d.ZERO, 0.0f, 0.0f, Set.of()));
            syncPing();
            in(new ServerboundPacket.AcceptTeleportation(1));
            in(new ServerboundPacket.MovePlayer(2.5, 64.0, 2.5, 0.0f, 0.0f, true, true, false, false));
            in(new ServerboundPacket.PlayerLoaded());
            PlayerState.Keys forward = new PlayerState.Keys(true, false, false, false, false, false, false);
            stepVanilla(KEYS_NONE, 15.0f, 0.0f);
            stepVanilla(KEYS_NONE, 0.0f, 0.0f);
            stepVanilla(forward, 0.0f, 0.0f);
            stepVanilla(forward, 0.0f, 0.0f);
            stepVanilla(KEYS_NONE, 0.0f, 0.0f);
        }

        void stepVanilla(PlayerState.Keys keys, float yRot, float xRot) {
            PlayerState pre = new PlayerState(player.x(), player.y(), player.z(), player.deltaMovement(), yRot, xRot,
                    player.onGround(), player.horizontalCollision(), player.minorHorizontalCollision(),
                    player.verticalCollision(), player.verticalCollisionBelow(), player.fallDistance(),
                    player.sprinting(), player.sprintModifier(), player.crouching(), player.pose(), player.keys(),
                    player.noJumpDelay(), player.sprintTriggerTime(), player.autoJumpTime(), player.support(),
                    player.onGroundNoBlocks(), player.stuckSpeedMultiplier(), player.swimming(), player.eyeInWater(),
                    player.fallFlying(), player.inPowderSnow(), player.inWater(), player.spinAttackTicks());
            try {
                OnFoot.Step step = OnFoot.step(pre, keys, FLAT_WORLD,
                        new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, firstTick, speed, OnFoot.Effects.NONE,
                                null, false, PlatformCos.X86_64,
                                new Border(-30_000_000.0, -30_000_000.0, 30_000_000.0, 30_000_000.0), null));
                firstTick = false;
                speed = step.speed();
                PlayerState post = step.state();
                player = post;
                if (!keys.equals(sentInput)) {
                    in(new ServerboundPacket.PlayerInput(keys.forward(), keys.backward(), keys.left(), keys.right(),
                            keys.jump(), keys.shift(), keys.sprint()));
                    sentInput = keys;
                }
                if (post.sprinting() != sentSprinting) {
                    in(new ServerboundPacket.PlayerCommand(1,
                            post.sprinting() ? ServerboundPacket.PlayerCommand.Action.START_SPRINTING
                                    : ServerboundPacket.PlayerCommand.Action.STOP_SPRINTING, 0));
                    sentSprinting = post.sprinting();
                }
                reminder++;
                boolean position = VanillaMath.lengthSquared(post.x() - sentX, post.y() - sentY, post.z() - sentZ)
                        > VanillaMath.square(2.0E-4) || reminder >= 20;
                boolean rotation = yRot != sentYRot || xRot != sentXRot;
                boolean flags = post.onGround() != sentOnGround || post.horizontalCollision() != sentCollision;
                if (position || rotation || flags) {
                    in(new ServerboundPacket.MovePlayer(
                            position ? post.x() : Double.NaN,
                            position ? post.y() : Double.NaN,
                            position ? post.z() : Double.NaN,
                            rotation ? yRot : Float.NaN,
                            rotation ? xRot : Float.NaN,
                            position, rotation, post.onGround(), post.horizontalCollision()));
                }
                if (position) {
                    sentX = post.x();
                    sentY = post.y();
                    sentZ = post.z();
                    reminder = 0;
                }
                if (rotation) {
                    sentYRot = yRot;
                    sentXRot = xRot;
                }
                sentOnGround = post.onGround();
                sentCollision = post.horizontalCollision();
                tick();
            } catch (Colliders.Unknown ex) {
                throw new AssertionError(ex);
            }
        }

        void spawnZombie(int id, double x, double y, double z) {
            out(new ClientboundPacket.AddEntity(
                    id, new UUID(2, id), "minecraft:zombie", x, y, z, Vec3d.ZERO, null, (byte) 0, (byte) 0, (byte) 0, 0));
            syncPing();
        }

        void out(ClientboundPacket p) {
            deliver(new TimelineEvent.Outbound(seq++, now, p));
        }

        void in(ServerboundPacket p) {
            deliver(new TimelineEvent.Inbound(seq++, now, tickCount, p));
        }

        void syncPing() {
            int id = ++pingId;
            out(new ClientboundPacket.Ping(id));
            in(new ServerboundPacket.Pong(id));
            deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
        }

        void noteTick() {
            tickCount++;
        }

        void tick() {
            in(new ServerboundPacket.ClientTickEnd());
            now += 50_000_000L;
            tickCount++;
        }
    }

    private static ClientboundPacket.UpdateTags minimalTags() {
        BlockStates states = BlockStates.vanilla();
        List<Integer> climbable = new ArrayList<>();
        for (String b : dev.whynotac.physics.OnFoot.CLIMBABLE) {
            climbable.add(states.blockId(states.firstStateOf(b)));
        }
        List<Integer> glideThrough = new ArrayList<>();
        for (String b : dev.whynotac.physics.OnFoot.CAN_GLIDE_THROUGH) {
            glideThrough.add(states.blockId(states.firstStateOf(b)));
        }
        List<Integer> fallReset = new ArrayList<>();
        for (String b : dev.whynotac.physics.OnFoot.FALL_DAMAGE_RESETTING) {
            fallReset.add(states.blockId(states.firstStateOf(b)));
        }
        List<Integer> fences = new ArrayList<>();
        List<Integer> walls = new ArrayList<>();
        for (int id = 0; id < states.blockCount(); id++) {
            String key = states.blockKey(id);
            if (key.endsWith("_fence")) {
                fences.add(id);
            } else if (key.endsWith("_wall")) {
                walls.add(id);
            }
        }
        return new ClientboundPacket.UpdateTags(List.of(
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:block", List.of(
                        tag("minecraft:climbable", climbable),
                        tag("minecraft:can_glide_through", glideThrough),
                        tag("minecraft:fall_damage_resetting", fallReset),
                        tag("minecraft:fences", fences),
                        tag("minecraft:walls", walls),
                        tag("minecraft:strider_warm_blocks", List.of(states.blockId(states.firstStateOf("minecraft:lava")))))),
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:entity_type", List.of(
                        tag("minecraft:powder_snow_walkable_mobs", List.of()),
                        tag("minecraft:can_float_while_ridden", List.of(
                                dev.whynotac.analysis.movement.EngineTags.HORSE,
                                dev.whynotac.analysis.movement.EngineTags.ZOMBIE_HORSE,
                                dev.whynotac.analysis.movement.EngineTags.MULE,
                                dev.whynotac.analysis.movement.EngineTags.DONKEY,
                                dev.whynotac.analysis.movement.EngineTags.CAMEL,
                                dev.whynotac.analysis.movement.EngineTags.CAMEL_HUSK)))),
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:fluid", List.of(
                        new ClientboundPacket.UpdateTags.Tag("minecraft:water",
                                new int[] {dev.whynotac.analysis.movement.EngineTags.WATER, dev.whynotac.analysis.movement.EngineTags.FLOWING_WATER}),
                        new ClientboundPacket.UpdateTags.Tag("minecraft:lava",
                                new int[] {dev.whynotac.analysis.movement.EngineTags.LAVA, dev.whynotac.analysis.movement.EngineTags.FLOWING_LAVA})))));
    }

    private static ClientboundPacket.UpdateTags.Tag tag(String name, List<Integer> ids) {
        return new ClientboundPacket.UpdateTags.Tag(name, ids.stream().mapToInt(Integer::intValue).toArray());
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
