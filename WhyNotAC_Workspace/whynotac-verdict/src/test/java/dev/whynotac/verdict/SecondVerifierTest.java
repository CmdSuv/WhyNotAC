package dev.whynotac.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.math.vector.Vec3d;
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
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.write.BlockSink;
import dev.whynotac.recording.write.RecordingWriter;
import dev.whynotac.verdict.EvidenceCertificate.VerificationStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecondVerifierTest {

    @TempDir
    Path tempDir;

    private static List<TimelineEvent> reachViolationTimeline(double targetX) {
        List<TimelineEvent> events = new ArrayList<>();
        long seq = 0;
        long now = 1_000_000_000L;
        long tickNanos = 50_000_000L;
        int pingId = 0;
        int selfId = 1;
        int targetId = 20;
        UUID playerUuid = new UUID(10L, 20L);
        ClientboundPacket.Dimension overworld = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);

        events.add(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        events.add(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        events.add(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        events.add(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        events.add(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        events.add(new TimelineEvent.Bound(seq++, now, playerUuid, "Cheater", selfId));

        events.add(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Login(selfId, 8, overworld, 0)));
        events.add(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.PlayerInfoUpdate(true, true,
                        List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(playerUuid, 0)))));
        events.add(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.SetHeldSlot(0)));
        events.add(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.ContainerSetContent(0, 1, new ArrayList<>(Collections.nCopies(46, Stack.EMPTY)), Stack.EMPTY)));
        events.add(new TimelineEvent.Outbound(seq++, now,
                ClientboundPacket.AddEntity.received(targetId, new UUID(99L, 99L), "minecraft:sheep", targetX, 64.0, 0.5,
                        Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0)));
        int pid = ++pingId;
        events.add(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(pid)));
        events.add(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(pid)));
        events.add(new TimelineEvent.Acknowledged(seq++, now, pid, now, 0));

        events.add(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.PlayerLoaded()));
        events.add(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, -90F, 0F, true, false, true, false)));
        events.add(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        now += tickNanos;

        // Next tick: attack sheep at targetX from x=0.5
        events.add(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(targetId, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        events.add(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        events.add(new TimelineEvent.Disconnected(seq, now + tickNanos));
        return events;
    }

    private static Path writeRecording(Path target, List<TimelineEvent> events) throws IOException {
        RecordingHeader header = new RecordingHeader(RecordingFormat.VERSION, 774, "1.21.11", "test",
                UUID.randomUUID(), new UUID(10L, 20L), System.currentTimeMillis());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header.encode());
        Deflater deflater = new Deflater();
        try {
            BlockSink sink = new BlockSink() {
                @Override
                public boolean accept(Block block) {
                    try {
                        out.write(block.encode(deflater));
                        return true;
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public void close(long totalEvents, long lostEvents) {
                    try {
                        ByteSink end = new ByteSink(32);
                        EventCodec.Encoder.encodeEnd(totalEvents, lostEvents, end);
                        out.write(new Block(0, 0, 0, 0, 0, end.toByteArray()).encode(deflater));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            RecordingWriter writer = new RecordingWriter(sink, RecordingWriter.DEFAULT_TARGET_BLOCK_BYTES,
                    RecordingWriter.DEFAULT_MAX_BLOCK_AGE_NANOS, target);
            for (TimelineEvent event : events) {
                writer.append(event);
            }
            writer.close();
        } finally {
            deflater.end();
        }
        Files.write(target, out.toByteArray());
        return target;
    }

    @Test
    void asyncSecondVerifierReproducesAttackReachFromRecordingBeforeAuthorizingTempBan() throws Exception {
        Path recording = writeRecording(tempDir.resolve("reach.wnrec"), reachViolationTimeline(7.5));
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach"))
                .mitigationsEnabled(true)
                .autoBanEnabled(true)
                .secondVerifierEnabled(true)
                .requireCorroborationForTempBan(true)
                .build();

        CountDownLatch banLatch = new CountDownLatch(1);
        AtomicReference<VerdictDecision> banRef = new AtomicReference<>();
        AtomicReference<VerdictDecision> mitigateRef = new AtomicReference<>();

        try (VerdictEngine engine = new VerdictEngine(cfg,
                SecondVerifier.ReplaySource.fromRecordingPaths(s -> Optional.of(recording)))) {
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override
                public void mitigate(VerdictDecision decision) {
                    mitigateRef.set(decision);
                }

                @Override
                public void tempBan(VerdictDecision decision) {
                    banRef.set(decision);
                    banLatch.countDown();
                }
            });

            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {
                throw new AssertionError(err);
            });
            TimelineListener listener = Checks.create("attack-reach", engine);
            registry.addListener(listener);
            PlayerSession session = registry.openReplay("live-like");
            for (TimelineEvent event : RecordingReader.open(recording)) {
                session.deliver(event);
            }

            assertTrue(mitigateRef.get() != null, "NEUTRALIZE should fire immediately for ARMED P-A");
            assertTrue(banLatch.await(10, TimeUnit.SECONDS), "SecondVerifier should approve async TEMP_BAN");
            VerdictDecision ban = banRef.get();
            assertEquals(VerificationStatus.VERIFIED, ban.certificate().verificationStatus());
            assertTrue(ban.certificate().verifyIntegrity());
        }
    }

    @Test
    void mismatchedReplayAbortsTempBanAndConsumesFalseBanBudget() throws Exception {
        Path honestRecording = writeRecording(tempDir.resolve("honest.wnrec"), reachViolationTimeline(2.0));
        VerdictConfig cfg = VerdictConfig.builder()
                .armedChecks(Set.of("attack-reach"))
                .autoBanEnabled(true)
                .secondVerifierEnabled(true)
                .requireCorroborationForTempBan(true)
                .maxFalseBanBudgetPerWindow(1)
                .build();

        CountDownLatch rejectLatch = new CountDownLatch(1);
        AtomicReference<VerdictDecision> rejectedRef = new AtomicReference<>();
        AtomicReference<VerdictDecision> banRef = new AtomicReference<>();

        try (VerdictEngine engine = new VerdictEngine(cfg,
                SecondVerifier.ReplaySource.fromRecordingPaths(s -> Optional.of(honestRecording)))) {
            engine.addEnforcementListener(new VerdictEngine.EnforcementListener() {
                @Override
                public void tempBan(VerdictDecision decision) {
                    banRef.set(decision);
                }

                @Override
                public void verificationRejected(VerdictDecision decision) {
                    rejectedRef.set(decision);
                    rejectLatch.countDown();
                }
            });

            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, err) -> {});
            List<Evidence> captured = new ArrayList<>();
            TimelineListener reachCheck = Checks.create("attack-reach", (s, ev) -> captured.add(ev));
            registry.addListener(reachCheck);
            PlayerSession s = registry.openReplay("mismatch");
            for (TimelineEvent ev : reachViolationTimeline(7.5)) {
                s.deliver(ev);
            }
            assertEquals(1, captured.size());
            engine.accept(s, captured.getFirst());

            assertTrue(rejectLatch.await(10, TimeUnit.SECONDS), "Mismatch should invoke verificationRejected");
            assertNull(banRef.get());
            assertEquals(VerificationStatus.MISMATCH, rejectedRef.get().certificate().verificationStatus());
            assertTrue(rejectedRef.get().certificate().verifyIntegrity());
            assertTrue(engine.falseBanBudget().isLocked(), "Mismatch must lock FalseBanBudget when limit is 1");
        }
    }
}
