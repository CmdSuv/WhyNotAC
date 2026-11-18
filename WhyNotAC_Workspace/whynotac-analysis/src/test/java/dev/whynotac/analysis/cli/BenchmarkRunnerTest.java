package dev.whynotac.analysis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BenchmarkRunnerTest {

    @Test
    void benchmarkSuitePassesAll20ScenariosWithZeroHonestFpAndZeroCheatFn(@TempDir Path tempCorpus) throws Exception {
        Path corpusDir = Path.of("../cheat-corpus");
        if (!java.nio.file.Files.isDirectory(corpusDir)) {
            corpusDir = tempCorpus;
            BenchmarkRunner.generateCheatCorpus(corpusDir);
        }
        BenchmarkRunner.BenchmarkReport report = BenchmarkRunner.run(Path.of("../regression"), corpusDir);
        assertEquals(0, report.honestFalsePositives(), "Honest recordings must produce 0 false positives");
        assertTrue(report.cheatRecordings() >= 7, "Must replay at least 7 cheat corpus recordings");
        assertEquals(0, report.cheatFalseNegatives(), "Cheat corpus must produce 0 false negatives: " + report.scenarioFindings());
        assertTrue(report.scenariosTotal() >= 22, "Must run at least 22 scenarios");
        assertEquals(report.scenariosTotal(), report.scenariosPassed(),
                "All synthetic benchmark scenarios must pass: " + report.scenarioFindings());
        assertTrue(report.allPassed());
    }

    @Test
    void stress500ConcurrentSessionsAcrossAll21ChecksDropsZeroEventsAndZeroFalsePositives() throws Exception {
        final int sessionsCount = 500;
        AtomicInteger findings = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        SessionRegistry registry = new SessionRegistry(workers, 1, (s, e, t) -> errors.incrementAndGet());
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        EvidenceSink sink = new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                findings.incrementAndGet();
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {}
        };
        Checks.all(sink, replicas).forEach(registry::addListener);

        List<PlayerSession> sessions = new java.util.ArrayList<>(sessionsCount);
        ClientboundPacket.Dimension overworld = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);
        for (int i = 0; i < sessionsCount; i++) {
            PlayerSession s = registry.openReplay("scale-" + i);
            UUID uid = new UUID(42, i);
            long seq = 0;
            long t = 1_000_000_000L;
            s.deliver(new TimelineEvent.Observation(seq++, t, Source.WIRE, Source.WIRE, 774, "scale"));
            s.deliver(new ClientFact(seq++, t, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "scale"));
            s.deliver(new ClientFact(seq++, t, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "scale"));
            s.deliver(new ClientFact(seq++, t, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "scale"));
            s.deliver(new ClientFact(seq++, t, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "scale"));
            s.deliver(new TimelineEvent.Bound(seq++, t, uid, "Bot" + i, 1));
            s.deliver(new TimelineEvent.Outbound(seq++, t, new ClientboundPacket.Login(1, 4, overworld, 0)));
            s.deliver(new TimelineEvent.Outbound(seq++, t,
                    new ClientboundPacket.PlayerPosition(1, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 0.0F, 0.0F, Set.of())));
            s.deliver(new TimelineEvent.Inbound(seq++, t, 0, new ServerboundPacket.AcceptTeleportation(1)));
            s.deliver(new TimelineEvent.Inbound(seq++, t, 0,
                    new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0.0F, 0.0F, true, true, false, false)));
            s.deliver(new TimelineEvent.Inbound(seq++, t, 0, new ServerboundPacket.PlayerLoaded()));
            for (int tick = 0; tick < 10; tick++) {
                t += 50_000_000L;
                s.deliver(new TimelineEvent.Inbound(seq++, t, tick,
                        new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, (tick * 2.0F) % 360.0F, 0.0F, false, true, true, false)));
                s.deliver(new TimelineEvent.Inbound(seq++, t, tick, new ServerboundPacket.ClientTickEnd()));
            }
            s.deliver(new TimelineEvent.Disconnected(seq++, t + 1_000_000L));
            sessions.add(s);
        }
        workers.shutdown();
        assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "500 sessions must drain within 30s");
        assertEquals(0, errors.get(), "0 listener exceptions across 500 concurrent sessions");
        assertEquals(0, findings.get(), "0 false positives across 500 honest sessions");
        for (PlayerSession s : sessions) {
            assertEquals(0, s.counters().dropped(), "0 dropped events across 500 sessions");
        }
    }
}
