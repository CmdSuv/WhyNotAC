package dev.whynotac.recording.write;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Every UUID a timeline event carries, and the same event with each one mapped: the {@code Bound} event's
 * player, the profiles of the player list ({@code PlayerInfoUpdate}, {@code PlayerInfoRemove}), the
 * entities the client was sent ({@code AddEntity}) and the entities a team lists by UUID ({@code SetPlayerTeam}),
 * in either view of an outbound packet.
 */
public final class RecordingUuids {

    private RecordingUuids() {}

    /** The event with every UUID it carries replaced by {@code map}'s image (the event itself if it carries none). */
    public static TimelineEvent map(TimelineEvent event, UnaryOperator<UUID> map) {
        return switch (event) {
            case TimelineEvent.Bound bound -> new TimelineEvent.Bound(bound.sequence(), bound.nanoTime(), map.apply(bound.playerId()),
                    bound.playerName(), bound.entityId());
            case TimelineEvent.Outbound out when carriesUuids(out.packet()) -> new TimelineEvent.Outbound(out.sequence(), out.nanoTime(), map(out.packet(), map));
            case TimelineEvent.OutboundDivergence d when carriesUuids(d.packet()) ->
                    new TimelineEvent.OutboundDivergence(d.sequence(), d.nanoTime(), d.kind(), map(d.packet(), map));
            default -> event;
        };
    }

    /** How many times {@code id} occurs in the event. */
    public static int count(TimelineEvent event, UUID id) {
        int[] n = {0};
        map(event, u -> {
            if (id.equals(u)) {
                n[0]++;
            }
            return u;
        });
        return n[0];
    }

    private static boolean carriesUuids(ClientboundPacket packet) {
        return packet instanceof ClientboundPacket.PlayerInfoUpdate || packet instanceof ClientboundPacket.PlayerInfoRemove
                || packet instanceof ClientboundPacket.AddEntity || packet instanceof ClientboundPacket.SetPlayerTeam;
    }

    /** A team entry that is an entity's UUID (the scoreboard name of an entity that is not a player), mapped; any other unchanged. */
    private static String entry(String name, UnaryOperator<UUID> map) {
        return isUuid(name) ? map.apply(UUID.fromString(name)).toString() : name;
    }

    /** Whether a scoreboard name is a UUID as {@code Entity.getStringUUID} writes it. */
    public static boolean isUuid(String name) {
        if (name.length() != 36 || name.charAt(8) != '-') {
            return false;
        }
        try {
            return UUID.fromString(name).toString().equals(name);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static ClientboundPacket map(ClientboundPacket packet, UnaryOperator<UUID> map) {
        UnaryOperator<UUID> safe = id -> id == null ? null : map.apply(id);
        return switch (packet) {
            case ClientboundPacket.PlayerInfoUpdate u -> new ClientboundPacket.PlayerInfoUpdate(u.addPlayers(), u.updateGameMode(),
                    u.entries().stream().map(e -> new ClientboundPacket.PlayerInfoUpdate.Entry(safe.apply(e.profileId()), e.gameMode())).toList());
            case ClientboundPacket.PlayerInfoRemove r -> new ClientboundPacket.PlayerInfoRemove(r.profileIds().stream().map(safe).toList());
            case ClientboundPacket.AddEntity a -> new ClientboundPacket.AddEntity(a.entityId(), safe.apply(a.uuid()), a.type(), a.x(), a.y(), a.z(),
                    a.clientMotion(), a.serverMotion(), a.xRot(), a.yRot(), a.yHeadRot(), a.data());
            case ClientboundPacket.SetPlayerTeam t -> new ClientboundPacket.SetPlayerTeam(t.team(), t.method(), t.collisionRule(),
                    t.players().stream().map(n -> entry(n, safe)).toList());
            default -> packet;
        };
    }
}
