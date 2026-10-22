package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The check over the replicas it reads, fed a timeline as the plugin would. */
class ActionGrammarCheckTest {

    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final UUID ME = new UUID(7, 7);
    private static final int SELF = 1;
    private static final int TARGET = 20;
    private static final long SECOND = 1_000_000_000L;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private SessionRegistry registry;
    private PlayerSession session;
    private long seq;
    private long now;
    private int pingId;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new ActionGrammarCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        session = registry.openReplay("test");
        nativeClient();
        join(0);
    }

    // --- timeline helpers ---

    private void nativeClient() {
        deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
    }

    /** Logged in, in the player list in {@code mode}, the client's processing caught up. */
    private void join(int mode) {
        deliver(new TimelineEvent.Bound(seq++, now, ME, "me", SELF));
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD, mode));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, mode))));
        sync();
    }

    private void deliver(TimelineEvent event) {
        session.deliver(event);
    }

    private void out(ClientboundPacket packet) {
        deliver(new TimelineEvent.Outbound(seq++, now, packet));
    }

    private void in(ServerboundPacket packet) {
        deliver(new TimelineEvent.Inbound(seq++, now, 0, packet));
    }

    /** A transaction the client answers at its next frame's start. */
    private void sync() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        in(new ServerboundPacket.Pong(id));
        deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
    }

    private void move() {
        in(new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0F, 0F, true, false, true, false));
    }

    private void tickEnd() {
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    private void attack() {
        in(new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false));
    }

    private void stab() {
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.STAB, new BlockPosition(0, 0, 0), BlockFace.DOWN, 0));
    }

    private void swing() {
        in(new ServerboundPacket.Swing(Hand.MAIN_HAND));
    }

    /** Every other action handleKeybinds sends: an interaction, block uses, digging, a release, a drop, a swap, the hotbar. */
    private void otherActions() {
        in(new ServerboundPacket.SetCarriedItem(2));
        in(new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.INTERACT_AT, Hand.MAIN_HAND, Vec3d.ZERO, false));
        in(new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.INTERACT, Hand.MAIN_HAND, null, false));
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(0, 63, 0), BlockFace.UP, new Vec3d(0.5, 64.0, 0.5), false, false, 1));
        in(new ServerboundPacket.UseItem(Hand.OFF_HAND, 2, 0F, 0F));
        for (ServerboundPacket.PlayerAction.Action action : List.of(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK,
                ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, ServerboundPacket.PlayerAction.Action.ABORT_DESTROY_BLOCK,
                ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM, ServerboundPacket.PlayerAction.Action.DROP_ITEM,
                ServerboundPacket.PlayerAction.Action.DROP_ALL_ITEMS, ServerboundPacket.PlayerAction.Action.SWAP_ITEM_WITH_OFFHAND)) {
            in(new ServerboundPacket.PlayerAction(action, new BlockPosition(0, 63, 0), BlockFace.UP, 3));
        }
    }

    private void keepAlive() {
        in(new ServerboundPacket.KeepAlive(42));
    }

    // --- tests ---

    @Test
    void vanillaAttacksNeverProduceEvidence() {
        tickEnd();
        for (int t = 0; t < 400; t++) {
            if (t % 3 == 0) {
                sync(); // the frame's packets first
            }
            if (t % 5 == 0) {
                attack();
                if (t % 10 == 0) {
                    keepAlive(); // the network thread's own answers may land between the two
                    in(new ServerboundPacket.ChunkBatchReceived(9.0F));
                }
                swing();
                attack(); // a second queued click in the same tick
                swing();
            }
            if (t % 7 == 0) {
                stab();
                swing();
            }
            if (t % 11 == 0) {
                swing(); // a miss, or an attack the range component did not let through
            }
            if (t % 13 == 0) {
                otherActions();
            }
            move();
            keepAlive();
            tickEnd();
        }
        assertEquals(List.of(), evidence);
        assertEquals(List.of(), rejected);
    }

    @Test
    void repliesBeforeTheAttacksAreNotTheTicksOwnMovement() {
        tickEnd();
        // a teleport: its accept and reply at frame start, then the tick's attack and movement
        out(new ClientboundPacket.PlayerPosition(7, Vec3d.ZERO, Vec3d.ZERO, 0F, 0F, Set.of()));
        in(new ServerboundPacket.AcceptTeleportation(7));
        in(new ServerboundPacket.MovePlayer(0, 0, 0, 0, 0, true, true, false, false));
        attack();
        swing();
        move();
        tickEnd();
        // a rotation, and a teleport of the vehicle the player left: their replies, then the attack
        out(new ClientboundPacket.PlayerRotation(90F, false, 0F, false));
        out(new ClientboundPacket.TeleportEntity(40, Vec3d.ZERO, Vec3d.ZERO, 0F, 0F, Set.of(), true));
        sync();
        in(new ServerboundPacket.MovePlayer(0, 0, 0, 90F, 0F, false, true, false, false));
        in(new ServerboundPacket.MovePlayer(0, 0, 0, 90F, 0F, true, true, false, false));
        attack();
        swing();
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
    }

    @Test
    void anAttackWithoutItsSwingIsProven() {
        tickEnd();
        attack();
        move();
        Evidence e = evidence.getFirst();
        assertEquals(1, evidence.size());
        assertEquals(ActionGrammarCheck.ID, e.check());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("attack on entity 20 not followed by the main hand's swing (next: MovePlayer)", e.summary());
        assertEquals("1", e.details().get(ActionGrammarCheck.NO_SWING));
        assertEquals("0", e.details().get(ActionGrammarCheck.AFTER_MOVEMENT));
        assertTrue(e.admissibility().admissible());
        tickEnd();
        now += SECOND;
        attack();
        in(new ServerboundPacket.Swing(Hand.OFF_HAND)); // not the hand startAttack swings
        assertEquals(2, evidence.size());
        stab();
        tickEnd(); // within the second: counted into the next finding
        assertEquals(2, evidence.size());
        now += SECOND;
        attack();
        attack(); // the first one's swing never came
        assertEquals("2", evidence.get(2).details().get(ActionGrammarCheck.NO_SWING));
    }

    @Test
    void anAttackAfterTheTicksOwnMovementIsProven() {
        tickEnd();
        move();
        keepAlive();
        attack();
        swing();
        tickEnd();
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertTrue(e.summary().startsWith("attack on entity 20 after the tick's own movement packet"), e.summary());
        assertEquals("1", e.details().get(ActionGrammarCheck.AFTER_MOVEMENT));
        assertEquals("0", e.details().get(ActionGrammarCheck.NO_SWING));
        now += SECOND;
        move();
        stab();
        swing();
        assertEquals(2, evidence.size());
        assertTrue(evidence.get(1).summary().startsWith("stab after the tick's own movement packet"), evidence.get(1).summary());
    }

    @Test
    void everyOtherActionAfterTheTicksOwnMovementIsProvenToo() {
        tickEnd();
        otherActions(); // before the movement: the client's order
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            now += SECOND;
            move();
            int before = evidence.size();
            switch (i) {
                case 0 -> in(new ServerboundPacket.SetCarriedItem(3));
                case 1 -> in(new ServerboundPacket.InteractEntity(TARGET, ServerboundPacket.InteractEntity.Kind.INTERACT, Hand.MAIN_HAND, null, false));
                case 2 -> in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(0, 63, 0), BlockFace.UP, Vec3d.ZERO, false, false, 1));
                case 3 -> in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 2, 0F, 0F));
                default -> in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.values()[i - 4], new BlockPosition(0, 63, 0),
                        BlockFace.UP, 3));
            }
            assertEquals(before + 1, evidence.size(), "action " + i);
            found.add(evidence.getLast().summary().replaceAll(" after the tick's own movement packet.*", ""));
            tickEnd();
        }
        assertEquals(List.of("hotbar selection", "interaction with entity 20", "use of the item on a block", "use of the item",
                "digging (START_DESTROY_BLOCK)", "digging (ABORT_DESTROY_BLOCK)", "digging (STOP_DESTROY_BLOCK)", "item drop", "item drop",
                "release of the item in use", "hand swap"), found);
        now += SECOND;
        move();
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.UNKNOWN, new BlockPosition(0, 0, 0), BlockFace.DOWN, 0));
        tickEnd(); // a constant this build does not know: no claim
        assertEquals(11, evidence.size());
    }

    private void openMenu() {
        out(new ClientboundPacket.OpenScreen(5, "minecraft:generic_9x3"));
    }

    private void input(boolean forward, boolean jump, boolean shift, boolean sprint) {
        in(new ServerboundPacket.PlayerInput(forward, false, false, false, jump, shift, sprint));
    }

    @Test
    void underAMenuTheClientNeitherActsNorWalks() {
        tickEnd();
        int before = ++pingId;
        out(new ClientboundPacket.Ping(before));
        openMenu();
        in(new ServerboundPacket.Pong(before));
        deliver(new TimelineEvent.Acknowledged(seq++, now, before, now, 0)); // answered before it handled the OpenScreen
        attack(); // so it may not have handled it yet
        swing();
        input(true, false, false, false);
        sync(); // now it has
        input(false, false, false, false); // its keys, released by setScreen
        input(false, true, true, true); // jump, sneak and sprint: not claimed
        in(new ServerboundPacket.SetCarriedItem(4)); // the tick's start sends it under a screen too
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
        attack();
        swing();
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals("attack on entity 20 while a menu was open", e.summary());
        assertEquals("1", e.details().get(ActionGrammarCheck.IN_MENU));
        tickEnd();
        now += SECOND;
        input(true, false, false, false);
        assertEquals(2, evidence.size());
        assertEquals("movement keys pressed while a menu was open", evidence.getLast().summary());
        now += SECOND;
        in(new ServerboundPacket.ContainerClose(5)); // the client closed it: free again
        input(true, false, false, false);
        attack();
        swing();
        move();
        tickEnd();
        assertEquals(2, evidence.size());
    }

    @Test
    void noMenuClaimOnceTheServerMayHaveClosedOrCoveredIt() {
        tickEnd();
        List<ClientboundPacket> covering = List.of(new ClientboundPacket.ContainerClose(5), new ClientboundPacket.MountScreenOpen(6, 3, 40),
                new ClientboundPacket.Respawn(OVERWORLD, 0, 0), new ClientboundPacket.GameEvent(ActionGrammarCheck.MenuScreen.WIN_GAME, 1F),
                new ClientboundPacket.GameEvent(ActionGrammarCheck.MenuScreen.DEMO_EVENT, 0F), new ClientboundPacket.Unmodeled("minecraft:open_sign_editor"),
                new ClientboundPacket.Unmodeled("minecraft:player_combat_kill"), new ClientboundPacket.Unmodeled("minecraft:show_dialog"),
                new ClientboundPacket.OpenScreen(7, null)); // a menu type this build does not know
        for (ClientboundPacket packet : covering) {
            openMenu();
            sync();
            out(packet);
            sync();
            attack();
            swing();
            input(true, false, false, false);
            input(false, false, false, false);
            tickEnd();
        }
        out(new ClientboundPacket.GameEvent(3, 0F)); // a game mode change covers nothing
        openMenu();
        out(new ClientboundPacket.Unmodeled("minecraft:sound"));
        sync();
        assertEquals(List.of(), evidence);
        attack();
        assertEquals(1, evidence.size());
    }

    @Test
    void aPossibleSpectatorNeedNotSwingButStillAttacksBeforeItsMovement() {
        out(new ClientboundPacket.PlayerInfoUpdate(false, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, 3))));
        tickEnd();
        attack(); // a spectator's attack is not followed by a swing
        move();
        tickEnd();
        assertEquals(List.of(), evidence);
        stab(); // a stab always is
        move();
        assertEquals(1, evidence.size());
        tickEnd();
        now += SECOND;
        move();
        attack();
        assertEquals(2, evidence.size());
        assertTrue(evidence.get(1).summary().contains("after the tick's own movement packet"), evidence.get(1).summary());
    }

    @Test
    void noMovementClaimBeforeTheFirstTickEndOrWhileARotationMayBeAnswered() {
        move();
        attack(); // where the tick started is not known yet
        swing();
        tickEnd();
        out(new ClientboundPacket.PlayerRotation(90F, false, 0F, false)); // not yet handled: any rotation-only packet may be its reply
        in(new ServerboundPacket.MovePlayer(0, 0, 0, 90F, 0F, false, true, false, false));
        attack();
        swing();
        tickEnd();
        assertEquals(List.of(), evidence);
    }

    @Test
    void aPacketNotUnderstoodInTheWindowRejectsIt() {
        tickEnd();
        attack();
        in(new ServerboundPacket.Untranslatable("lost")); // perhaps the swing
        assertEquals(1, rejected.size());
        tickEnd();
        now += SECOND;
        in(new ServerboundPacket.Untranslatable("lost")); // perhaps a teleport's accept
        move();
        attack();
        swing();
        assertEquals(2, rejected.size());
        assertEquals(List.of(), evidence);
    }

    @Test
    void theCheckIsShipped() {
        assertEquals(ActionGrammarCheck.VERSION, Checks.version(ActionGrammarCheck.ID));
        assertTrue(Checks.all((s, e) -> { }, Replicas.vanilla()).stream().anyMatch(ActionGrammarCheck.class::isInstance));
    }
}
