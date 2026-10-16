package dev.whynotac.recording.write;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * exactly as live. Teams carry names ({@link Names}): the player's own becomes its display pseudonym (what the
 * {@code Bound} event carries), an entity's UUID its pseudonym, and any other name, and every team's, a token of
 * that recording alone; the same name the same token throughout it, so the teams replay as live. Replay sessions
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
        /** The recording's names, once bound. */
        Names names;
    }

    /**
     * What a recording writes for the names its teams carry: the recorded player's own scoreboard name (its profile
     * name) as its display pseudonym, an entity's UUID (already its pseudonym, {@link RecordingUuids}) as it is, any
     * other name as {@code entry-N} and every team's name as {@code team-N}, numbered in the order this recording first
     * meets them. The tokens derive from nothing: they link nothing beyond the one recording.
     */
    public static final class Names {
        private final String playerName;
        private final UUID subject;
        private final Map<String, String> entries = new HashMap<>();
        private final Map<String, String> teams = new HashMap<>();

        public Names(String playerName, UUID subject) {
            this.playerName = playerName;
            this.subject = subject;
        }

        ClientboundPacket.SetPlayerTeam team(ClientboundPacket.SetPlayerTeam t) {
            String team = teams.computeIfAbsent(t.team(), k -> "team-" + (teams.size() + 1));
            List<String> players = new ArrayList<>(t.players().size());
            for (String name : t.players()) {
                players.add(name.equals(playerName) ? Pseudonymizer.displayName(subject)
                        : RecordingUuids.isUuid(name) ? name : entries.computeIfAbsent(name, k -> "entry-" + (entries.size() + 1)));
            }
            return new ClientboundPacket.SetPlayerTeam(team, t.method(), t.collisionRule(), players);
        }
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
            state.writer.append(anonymise(event, state.names));
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
        state.names = new Names(bound.playerName(), subject);
        RecordingHeader header = new RecordingHeader(RecordingFormat.VERSION, protocolVersion, gameVersion, whynotacVersion,
                UUID.randomUUID(), subject, System.currentTimeMillis());
        state.writer = store.open(header);
        for (TimelineEvent pending : state.pending) {
            state.writer.append(anonymise(pending, state.names));
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

    /** {@link #anonymise(TimelineEvent)}, and a team's names as {@code names} writes them. */
    public TimelineEvent anonymise(TimelineEvent event, Names names) {
        TimelineEvent e = anonymise(event);
        return switch (e) {
            case TimelineEvent.Outbound out when out.packet() instanceof ClientboundPacket.SetPlayerTeam t ->
                    new TimelineEvent.Outbound(out.sequence(), out.nanoTime(), names.team(t));
            case TimelineEvent.OutboundDivergence d when d.packet() instanceof ClientboundPacket.SetPlayerTeam t ->
                    new TimelineEvent.OutboundDivergence(d.sequence(), d.nanoTime(), d.kind(), names.team(t));
            default -> e;
        };
    }

    /** The event as it is recorded: every UUID it carries replaced by its pseudonym. */
    public TimelineEvent anonymise(TimelineEvent event) {
        return event instanceof TimelineEvent.Bound bound ? anonymise(bound) : RecordingUuids.map(event, pseudonymizer::pseudonym);
    }
}
