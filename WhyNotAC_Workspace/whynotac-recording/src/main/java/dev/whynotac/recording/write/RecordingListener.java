package dev.whynotac.recording.write;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records every session that reaches the game, from the first packet of its connection.
 *
 * <p>Events before login completes are buffered and only written once the session is bound to a
 * player: server-list pings and failed logins never create a file. Every UUID in the timeline is
 * rewritten with its pseudonym: the {@code Bound} event's, and the profiles of the player list and the
 * entities the client was sent (the player's own entry among them, and every other player's). The same
 * UUID always gets the same pseudonym, so the replicas link a player-list entry to its entity in a replay
 * exactly as live. Replay sessions are never re-recorded.
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
            state.writer.append(anonymise(event));
        }
        if (event instanceof TimelineEvent.Disconnected) {
            state.writer.close();
            state.finished = true;
        }
    }

    /**
     * Where this session's recording will be once it is finished, if it is being recorded. Call it from
     * a listener of the same registry (the state is confined to the analysis thread of the session).
     */
    public Optional<Path> recordingFile(PlayerSession session) {
        State state = session.attachment(STATE, State::new);
        return state.writer == null ? Optional.empty() : state.writer.file();
    }

    private void start(State state, TimelineEvent.Bound bound) {
        UUID subject = pseudonymizer.pseudonym(bound.playerId());
        RecordingHeader header = new RecordingHeader(RecordingFormat.VERSION, protocolVersion, gameVersion, whynotacVersion,
                UUID.randomUUID(), subject, System.currentTimeMillis());
        state.writer = store.open(header);
        for (TimelineEvent pending : state.pending) {
            state.writer.append(anonymise(pending));
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

    /** The event as it is recorded: every UUID it carries replaced by its pseudonym. */
    public TimelineEvent anonymise(TimelineEvent event) {
        return switch (event) {
            case TimelineEvent.Bound bound -> anonymise(bound);
            case TimelineEvent.Outbound out when carriesUuids(out.packet()) -> new TimelineEvent.Outbound(out.sequence(), out.nanoTime(), anonymise(out.packet()));
            case TimelineEvent.OutboundDivergence d when carriesUuids(d.packet()) ->
                    new TimelineEvent.OutboundDivergence(d.sequence(), d.nanoTime(), d.kind(), anonymise(d.packet()));
            default -> event;
        };
    }

    private static boolean carriesUuids(ClientboundPacket packet) {
        return packet instanceof ClientboundPacket.PlayerInfoUpdate || packet instanceof ClientboundPacket.PlayerInfoRemove
                || packet instanceof ClientboundPacket.AddEntity;
    }

    private ClientboundPacket anonymise(ClientboundPacket packet) {
        return switch (packet) {
            case ClientboundPacket.PlayerInfoUpdate u -> new ClientboundPacket.PlayerInfoUpdate(u.addPlayers(), u.updateGameMode(),
                    u.entries().stream().map(e -> new ClientboundPacket.PlayerInfoUpdate.Entry(pseudonym(e.profileId()), e.gameMode())).toList());
            case ClientboundPacket.PlayerInfoRemove r -> new ClientboundPacket.PlayerInfoRemove(r.profileIds().stream().map(this::pseudonym).toList());
            case ClientboundPacket.AddEntity a -> new ClientboundPacket.AddEntity(a.entityId(), pseudonym(a.uuid()), a.type(), a.x(), a.y(), a.z(),
                    a.clientMotion(), a.serverMotion(), a.xRot(), a.yRot(), a.yHeadRot(), a.data());
            default -> packet;
        };
    }

    private UUID pseudonym(UUID id) {
        return id == null ? null : pseudonymizer.pseudonym(id);
    }
}
