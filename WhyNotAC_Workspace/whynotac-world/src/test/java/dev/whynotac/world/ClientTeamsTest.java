package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ClientTeams}: the client's handling of team packets and {@code EntitySelector.pushableBy}'s rules. */
class ClientTeamsTest {

    private static final String MOB = "6f1d2c3b-4a59-4e68-9d7c-8b1a2f3e4d5c";

    private static ClientboundPacket.SetPlayerTeam add(String team, String rule, String... players) {
        return new ClientboundPacket.SetPlayerTeam(team, ClientboundPacket.SetPlayerTeam.ADD, rule, List.of(players));
    }

    private static ClientboundPacket.SetPlayerTeam join(String team, String... players) {
        return new ClientboundPacket.SetPlayerTeam(team, ClientboundPacket.SetPlayerTeam.JOIN, null, List.of(players));
    }

    @Test
    void withoutTeamsEverythingPushes() {
        assertEquals(Boolean.TRUE, ClientTeams.NONE.pushes(MOB, "me"));
        assertEquals(Boolean.TRUE, ClientTeams.NONE.pushes(null, "me"));
        assertEquals(Boolean.TRUE, ClientTeams.NONE.apply(add("tags", "ALWAYS", "me", "alice")).pushes(null, "me"), "rules all ALWAYS");
        assertNull(ClientTeams.LOST.pushes(MOB, "me"));
    }

    @Test
    void neverOnEitherSideStopsThePush() {
        ClientTeams t = ClientTeams.NONE.apply(add("calm", "NEVER", "me"));
        assertEquals(Boolean.FALSE, t.pushes(MOB, "me"));
        assertEquals(Boolean.FALSE, t.pushes(null, "me"), "whatever the pusher's team");
        t = ClientTeams.NONE.apply(add("calm", "NEVER", MOB));
        assertEquals(Boolean.FALSE, t.pushes(MOB, "me"));
        assertNull(t.pushes(null, "me"), "a pusher not known by name may be in it or not");
    }

    @Test
    void ownAndOtherTeamRulesDependOnAlliance() {
        ClientTeams t = ClientTeams.NONE.apply(add("red", "PUSH_OTHER_TEAMS", "me", MOB)).apply(add("blue", "ALWAYS", "bob"));
        assertEquals(Boolean.TRUE, t.pushes(MOB, "me"), "allies, other-teams rule: PUSH_OTHER_TEAMS needs allies to pass its second test");
        assertEquals(Boolean.FALSE, t.pushes("bob", "me"), "not allies: the other-teams rule stops it");
        assertEquals(Boolean.FALSE, t.pushes("nobody", "me"), "no team: not allies");
        t = ClientTeams.NONE.apply(add("red", "PUSH_OWN_TEAM", "me", MOB));
        assertEquals(Boolean.FALSE, t.pushes(MOB, "me"), "allies under the own-team rule");
        assertEquals(Boolean.TRUE, t.pushes("nobody", "me"));
    }

    @Test
    void theClientsHandlingOfEntriesAndTeams() {
        ClientTeams t = ClientTeams.NONE.apply(add("calm", "NEVER")).apply(join("calm", "me"));
        assertEquals(Boolean.FALSE, t.pushes(MOB, "me"));
        ClientTeams moved = t.apply(add("loud", "ALWAYS", "me")); // addPlayerToTeam moves the entry
        assertEquals(Boolean.TRUE, moved.pushes(MOB, "me"));
        ClientTeams again = t.apply(add("calm", "ALWAYS")); // an existing team: kept, its parameters set
        assertEquals(Boolean.TRUE, again.pushes(MOB, "me"));
        assertSame(t, t.apply(join("unknown", "me")), "a team the client does not have: nothing");
        assertFalse(t.apply(new ClientboundPacket.SetPlayerTeam("calm", ClientboundPacket.SetPlayerTeam.LEAVE, null, List.of("bob"))).known(),
                "removePlayerFromTeam throws for an entry not in the team: the client disconnects");
        ClientTeams removed = t.apply(new ClientboundPacket.SetPlayerTeam("calm", ClientboundPacket.SetPlayerTeam.REMOVE, null, List.of()));
        assertEquals(Boolean.TRUE, removed.pushes(MOB, "me"));
        assertFalse(removed.anyRuleOtherThanAlways());
        assertNull(ClientTeams.NONE.apply(add("odd", "UNKNOWN", "me")).pushes(MOB, "me"), "a rule this build does not know");
        assertTrue(t.anyRuleOtherThanAlways());
    }
}
