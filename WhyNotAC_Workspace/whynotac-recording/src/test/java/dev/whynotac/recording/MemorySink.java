package dev.whynotac.recording;

import dev.whynotac.recording.format.Block;
import dev.whynotac.recording.format.ByteSink;
import dev.whynotac.recording.format.EventCodec;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import dev.whynotac.recording.write.BlockSink;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.zip.Deflater;

/** In-memory recording target: same bytes as a file, with an optional rejection rule per block index. */
public final class MemorySink implements BlockSink {

    public static final RecordingHeader HEADER = new RecordingHeader(RecordingFormat.VERSION, 774, "1.21.11", "test",
            new UUID(1, 2), new UUID(3, 4), 1_700_000_000_000L);

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
    private final IntPredicate reject;
    private final List<Integer> blockOffsets = new ArrayList<>();
    private final List<Block> accepted = new ArrayList<>();
    private int index;
    private boolean closed;

    public MemorySink(IntPredicate reject) {
        this.reject = reject;
        out.writeBytes(HEADER.encode());
    }

    public MemorySink() {
        this(i -> false);
    }

    @Override
    public boolean accept(Block block) {
        if (reject.test(index++)) {
            return false;
        }
        blockOffsets.add(out.size());
        accepted.add(block);
        out.writeBytes(block.encode(deflater));
        return true;
    }

    @Override
    public void close(long totalEvents, long lostEvents) {
        ByteSink end = new ByteSink(32);
        EventCodec.Encoder.encodeEnd(totalEvents, lostEvents, end);
        blockOffsets.add(out.size());
        out.writeBytes(new Block(0, 0, 0, 0, 0, end.toByteArray()).encode(deflater));
        closed = true;
    }

    public byte[] bytes() {
        return out.toByteArray();
    }

    public List<Integer> blockOffsets() {
        return blockOffsets;
    }

    public List<Block> acceptedBlocks() {
        return accepted;
    }

    public boolean closed() {
        return closed;
    }
}
