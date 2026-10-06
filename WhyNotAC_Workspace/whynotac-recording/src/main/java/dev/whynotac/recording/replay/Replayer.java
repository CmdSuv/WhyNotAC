package dev.whynotac.recording.replay;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.read.RecordingReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic replay: feeds a recording into listeners through a replay {@link PlayerSession},
 * single-threaded, with the recorded sequence numbers, timestamps and client ticks. Running the same
 * listeners over the same file always produces the same calls in the same order.
 *
 * <p>A recording that ends without {@code Disconnected} (crash, truncation) gets a synthetic one so
 * listeners can finalise; {@link Result#disconnectSynthesized()} says so.
 */
public final class Replayer {

    private Replayer() {}

    public record ListenerError(TimelineEvent event, Throwable error) {}

    public record Result(RecordingReader.Report report, long delivered, boolean disconnectSynthesized, List<ListenerError> listenerErrors) {}

    public static Result replay(RecordingReader reader, List<? extends TimelineListener> listeners) {
        List<ListenerError> errors = new ArrayList<>();
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (session, event, error) -> errors.add(new ListenerError(event, error)));
        listeners.forEach(registry::addListener);
        PlayerSession session = registry.openReplay("replay:" + reader.header().recordingId());
        long delivered = 0;
        TimelineEvent last = null;
        for (TimelineEvent event : reader) {
            session.deliver(event);
            delivered++;
            last = event;
        }
        boolean synthesized = false;
        if (!session.isClosed()) {
            long sequence = last == null ? 0 : last.sequence() + 1;
            long nanoTime = last == null ? 0 : last.nanoTime();
            session.deliver(new TimelineEvent.Disconnected(sequence, nanoTime));
            synthesized = true;
        }
        return new Result(reader.report(), delivered, synthesized, List.copyOf(errors));
    }
}
