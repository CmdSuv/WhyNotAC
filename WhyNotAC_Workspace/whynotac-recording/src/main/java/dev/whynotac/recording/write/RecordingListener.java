package dev.whynotac.recording.write;

import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records every session that reaches the game, from the first packet of its connection.
 *
 * <p>Events before login completes are buffered and only written once the session is bound to a
 * player: server-list pings and failed logins never create a file. The {@code Bound} event is
 * rewritten with the pseudonym; nothing else in the timeline identifies the player. Replay sessions
 * are never re-recorded.
 */
public final class RecordingListener implements TimelineListener {

    /** Pre-login events kept per connection. A measured vanilla login uses 16; this leaves room for modded clients. */
    public static final int MAX_BUFFERED_BEFORE_BIND = 512;

    private static final Object STATE = new Object();

    private final RecordingStore store;
    private final Pseudonymizer pseudonymizer;
    private final int protocolVersion;
    private final String gameVersion;
    private final String whynotacVersion;

    public RecordingListener(RecordingStore store, Pseudonymizer pseudonymizer, int protocolVersion, String gameVersion, String whynotacVersion) {
        this.store = store;
        this.pseudonymizer = pseudonymizer;
        this.protocolVersion = protocolVersion;
        this.gameVersion = gameVersion;
        this.whynotacVersion = whynotacVersion;
    }

    private static final class State {
        final List<TimelineEvent> pending = new ArrayList<>();
        long droppedBeforeBind;
        RecordingWriter writer;
        boolean finished;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        if (session.isReplay()) {
            return;
        }
        State state = session.attachment(STATE, State::new);
        if (state.finished) {
            return;
        }
        if (state.writer == null) {
            if (event instanceof TimelineEvent.Bound bound) {
                start(state, bound);
            } else if (event instanceof TimelineEvent.Disconnected) {
                state.finished = true; // never logged in: nothing to keep
                state.pending.clear();
                return;
            } else {
                if (state.pending.size() < MAX_BUFFERED_BEFORE_BIND) {
                    state.pending.add(event);
                } else {
                    state.droppedBeforeBind++;
                }
                return;
            }
        } else {
            state.writer.append(event instanceof TimelineEvent.Bound bound ? anonymise(bound) : event);
        }
        if (event instanceof TimelineEvent.Disconnected) {
            state.writer.close();
            state.finished = true;
        }
    }

    private void start(State state, TimelineEvent.Bound bound) {
        UUID subject = pseudonymizer.pseudonym(bound.playerId());
        RecordingHeader header = new RecordingHeader(RecordingFormat.VERSION, protocolVersion, gameVersion, whynotacVersion,
                UUID.randomUUID(), subject, System.currentTimeMillis());
        state.writer = store.open(header);
        for (TimelineEvent pending : state.pending) {
            state.writer.append(pending);
        }
        state.pending.clear();
        // Overflow dropped the newest pre-login events, i.e. those between the buffer and the login.
        state.writer.reportLost(state.droppedBeforeBind);
        state.writer.append(anonymise(bound));
    }

    private TimelineEvent.Bound anonymise(TimelineEvent.Bound bound) {
        UUID subject = pseudonymizer.pseudonym(bound.playerId());
        return new TimelineEvent.Bound(bound.sequence(), bound.nanoTime(), subject, Pseudonymizer.displayName(subject), bound.entityId());
    }
}
