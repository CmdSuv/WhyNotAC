package dev.whynotac.bench;

import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.write.BlockSink;
import dev.whynotac.recording.write.RecordingWriter;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost of recording one event: encoding on the analysis thread plus the amortised block compression
 * and checksum that the I/O thread performs (disk write excluded).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "-Xmx1g")
public class RecordingBenchmark {

    private RecordingWriter writer;
    private long sequence;
    private long nanoTime;
    private float yaw;
    private int encodedBytes;

    @Setup
    public void setUp() {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        writer = new RecordingWriter(new BlockSink() {
            @Override
            public boolean accept(Block block) {
                encodedBytes += block.encode(deflater).length; // what the I/O thread does, minus the disk
                return true;
            }

            @Override
            public void close(long totalEvents, long lostEvents) {
            }
        }, RecordingWriter.DEFAULT_TARGET_BLOCK_BYTES, Long.MAX_VALUE);
    }

    @Benchmark
    public int recordMovement() {
        nanoTime += 50_000_000L;
        yaw += 0.15F;
        writer.append(new TimelineEvent.Inbound(sequence++, nanoTime, sequence >> 2,
                new ServerboundPacket.MovePlayer(100.25 + yaw, 64.0, -20.5, yaw, 12.5F, true, true, true, false)));
        return encodedBytes;
    }
}
