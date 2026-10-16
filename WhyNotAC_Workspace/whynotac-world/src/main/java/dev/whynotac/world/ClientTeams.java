package dev.whynotac.world;

import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's scoreboard teams as far as pushing goes ({@code ClientPacketListener.handleSetPlayerTeamPacket} on
 * its {@code Scoreboard}, decompiled 1.21.11): each team's collision rule and the team of each scoreboard name (a
 * player's profile name, another entity's UUID). Immutable: {@link #apply} returns the teams after a packet.
 * {@link #LOST}: not known (a team packet that could not be read, or one the client fails on).
 */
public final class ClientTeams {

    /** {@code Team.CollisionRule}. */
    public enum Rule { ALWAYS, NEVER, PUSH_OTHER_TEAMS, PUSH_OWN_TEAM }

    /** A new {@code ClientPacketListener}'s scoreboard: no teams. */
    public static final ClientTeams NONE = new ClientTeams(Map.of(), Map.of(), true);
    public static final ClientTeams LOST = new ClientTeams(Map.of(), Map.of(), false);

    /** Team name to its rule; a null value: a rule this build does not know. */
    private final Map<String, Rule> rules;
    /** Scoreboard name to its team's name ({@code teamsByPlayer}). */
    private final Map<String, String> entries;
    private final boolean known;

    private ClientTeams(Map<String, Rule> rules, Map<String, String> entries, boolean known) {
        this.rules = rules;
        this.entries = entries;
        this.known = known;
    }

    public boolean known() {
        return known;
    }

    /** Whether some team's rule is not {@code ALWAYS}: without one, teams never keep an entity from pushing. */
    public boolean anyRuleOtherThanAlways() {
        for (Rule rule : rules.values()) {
            if (rule != Rule.ALWAYS) {
                return true;
            }
        }
        return false;
    }

    /**
     * The teams after the client handled {@code packet}: {@code addPlayerTeam} keeps a team that exists, a packet for a
     * team the client does not have changes nothing, the parameters set the rule, {@code addPlayerToTeam} moves an entry
     * from its team, {@code removePlayerFromTeam} throws for an entry not in the team (the client disconnects: lost), and
     * {@code removePlayerTeam} drops the team with its entries.
     */
    public ClientTeams apply(ClientboundPacket.SetPlayerTeam packet) {
        if (!known) {
            return this;
        }
        String team = packet.team();
        int method = packet.method();
        if (method != ClientboundPacket.SetPlayerTeam.ADD && !rules.containsKey(team)) {
            return this; // "Received packet for unknown team"
        }
        Map<String, Rule> r = new HashMap<>(rules);
        Map<String, String> e = new HashMap<>(entries);
        if (method == ClientboundPacket.SetPlayerTeam.ADD && !r.containsKey(team)) {
            r.put(team, Rule.ALWAYS); // a new PlayerTeam
        }
        if (packet.collisionRule() != null && (method == ClientboundPacket.SetPlayerTeam.ADD || method == ClientboundPacket.SetPlayerTeam.CHANGE)) {
            r.put(team, rule(packet.collisionRule()));
        }
        if (method == ClientboundPacket.SetPlayerTeam.ADD || method == ClientboundPacket.SetPlayerTeam.JOIN) {
            for (String name : packet.players()) {
                e.put(name, team);
            }
        } else if (method == ClientboundPacket.SetPlayerTeam.LEAVE) {
            for (String name : packet.players()) {
                if (!team.equals(e.get(name))) {
                    return LOST;
                }
                e.remove(name);
            }
        }
        if (method == ClientboundPacket.SetPlayerTeam.REMOVE) {
            r.remove(team);
            e.values().removeIf(team::equals);
        }
        return new ClientTeams(Collections.unmodifiableMap(r), Collections.unmodifiableMap(e), true);
    }

    private static Rule rule(String name) {
        return switch (name) {
            case "ALWAYS" -> Rule.ALWAYS;
            case "NEVER" -> Rule.NEVER;
            case "PUSH_OTHER_TEAMS" -> Rule.PUSH_OTHER_TEAMS;
            case "PUSH_OWN_TEAM" -> Rule.PUSH_OWN_TEAM;
            default -> null;
        };
    }

    /**
     * Whether teams let {@code pusher}'s push reach the client's own player {@code self} ({@code EntitySelector.pushableBy}
     * on the client, past {@code isPushable}): neither rule {@code NEVER}; not allies where either rule is
     * {@code PUSH_OWN_TEAM}; allies where either is {@code PUSH_OTHER_TEAMS} (allies: the same team). A null name is
     * not known (a player's profile name, a tamed animal's owner's team): every team it may be in counts. Null when the
     * answer depends on what is not known.
     */
    public Boolean pushes(String pusher, String self) {
        if (!known) {
            return null;
        }
        if (!anyRuleOtherThanAlways()) {
            return Boolean.TRUE;
        }
        Boolean answer = null;
        for (String rTeam : teams(pusher)) {
            for (String lTeam : teams(self)) {
                Rule rRule = rTeam == null ? Rule.ALWAYS : rules.get(rTeam);
                Rule lRule = lTeam == null ? Rule.ALWAYS : rules.get(lTeam);
                if (rRule == null || lRule == null) {
                    return null;
                }
                boolean pushes;
                if (rRule == Rule.NEVER || lRule == Rule.NEVER) {
                    pushes = false;
                } else {
                    boolean allied = rTeam != null && rTeam.equals(lTeam);
                    pushes = (rRule != Rule.PUSH_OWN_TEAM && lRule != Rule.PUSH_OWN_TEAM || !allied)
                            && (rRule != Rule.PUSH_OTHER_TEAMS && lRule != Rule.PUSH_OTHER_TEAMS || allied);
                }
                if (answer != null && answer != pushes) {
                    return null;
                }
                answer = pushes;
            }
        }
        return answer;
    }

    /** The teams {@code name} may be in (null: none): its own, or, not known, none or any. */
    private List<String> teams(String name) {
        if (name != null) {
            List<String> one = new ArrayList<>(1);
            one.add(entries.get(name));
            return one;
        }
        List<String> all = new ArrayList<>(rules.size() + 1);
        all.add(null);
        all.addAll(rules.keySet());
        return all;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClientTeams t && known == t.known && rules.equals(t.rules) && entries.equals(t.entries);
    }

    @Override
    public int hashCode() {
        return rules.hashCode() * 31 + entries.hashCode() + (known ? 1 : 0);
    }

    @Override
    public String toString() {
        return known ? "ClientTeams" + rules + entries : "ClientTeams[lost]";
    }
}
