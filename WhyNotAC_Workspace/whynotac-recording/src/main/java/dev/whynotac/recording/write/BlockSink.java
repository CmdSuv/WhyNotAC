package dev.whynotac.recording.write;

import dev.whynotac.recording.format.Block;

/** Destination of sealed blocks. Implementations must never block the caller on I/O. */
public interface BlockSink {

    /** Queues a block; returns false if it was rejected (back-pressure), in which case its events are lost. */
    boolean accept(Block block);

    /** Writes the end record and finalises the recording. Always accepted. */
    void close(long totalEvents, long lostEvents);
}
