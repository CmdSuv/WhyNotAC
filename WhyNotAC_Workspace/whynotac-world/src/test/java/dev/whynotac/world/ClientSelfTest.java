package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientSelfTest {

    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final UUID ME = new UUID(7, 7);
    private static final int SELF = 1;
    private static final int SURVIVAL = 0;
    private static final int CREATIVE = 1;

    private ClientSelf self;
    private long sequence;
    private int pingId;

    @BeforeEach
    void setUp() {
        self = new ClientSelf();
        sequence = 0;
        self.onEvent(new TimelineEvent.Bound(sequence++, sequence, ME, "me", SELF));
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD, SURVIVAL));
        out(listed(SURVIVAL));
        sync();
        in(new ServerboundPacket.PlayerLoaded());
        move(10.0, 64.0, -3.5);
    }

    private void out(ClientboundPacket packet) {
        self.onEvent(new TimelineEvent.Outbound(sequence++, sequence, packet));
    }

    private void in(ServerboundPacket packet) {
        self.onEvent(new TimelineEvent.Inbound(sequence++, sequence, 0, packet));
    }

    private int ping() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        return id;
    }

    private void ack(int id) {
        self.onEvent(new TimelineEvent.Acknowledged(sequence++, sequence, id, 0, 0));
    }

    /** The client processed everything sent so far. */
    private void sync() {
        ack(ping());
    }

    private void move(double x, double y, double z) {
        in(new ServerboundPacket.MovePlayer(x, y, z, 0F, 0F, true, false, true, false));
    }

    private void tick() {
        in(new ServerboundPacket.ClientTickEnd());
    }

    private static ClientboundPacket.PlayerInfoUpdate listed(int mode) {
        return new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, mode)));
    }

    private static ClientboundPacket.UpdateAttributes attribute(String key, double base, ClientboundPacket.UpdateAttributes.Modifier... modifiers) {
        return new ClientboundPacket.UpdateAttributes(SELF, List.of(new ClientboundPacket.UpdateAttributes.Attribute(key, base, List.of(modifiers))));
    }

    @Test
    void theEyeIsAboveTheLastPositionSentWithinThePoseEnvelope() {
        ClientSelf.Eye eye = self.eye();
        assertNotNull(eye);
        assertEquals(10.0, eye.x());
        assertEquals(-3.5, eye.z());
        // sleeping (0.2) up to standing or dying (1.62), at scale 1
        assertEquals(64.0 + 0.2F, eye.y0(), 1e-12);
        assertEquals(64.0 + 1.62F, eye.y1(), 1e-12);
        assertEquals(ClientSelf.SEND_THRESHOLD, eye.radius());
        assertFalse(self.mayBeCreativeOrSpectator());
        assertEquals(3.0, self.maxInteractionRange());
    }

    @Test
    void noPositionNoEye() {
        ClientSelf fresh = new ClientSelf();
        fresh.onEvent(new TimelineEvent.Outbound(0, 0, new ClientboundPacket.Login(SELF, 8, OVERWORLD, SURVIVAL)));
        assertNull(fresh.eye());
        assertTrue(fresh.mayBeCreativeOrSpectator(), "the player's own list entry is not known yet");
    }

    @Test
    void aRotationOnlyPacketKeepsTheLastPosition() {
        in(new ServerboundPacket.MovePlayer(0, 0, 0, 90F, 10F, false, true, true, false));
        assertEquals(10.0, self.eye().x());
    }

    @Test
    void theTeleportReplyCarriesTheNewPosition() {
        out(new ClientboundPacket.PlayerPosition(1, new Vec3d(100.0, 70.0, 100.0), Vec3d.ZERO, 0F, 0F, Set.of()));
        sync();
        in(new ServerboundPacket.AcceptTeleportation(1));
        in(new ServerboundPacket.MovePlayer(100.0, 70.0, 100.0, 0F, 0F, true, true, false, false));
        assertEquals(100.0, self.eye().x());
    }

    @Test
    void aGameModeChangeCountsFromWhenTheClientMayHaveProcessedIt() {
        int before = ping();
        out(listed(CREATIVE));
        assertFalse(self.mayBeCreativeOrSpectator(), "after the first unanswered ping: not processed yet");
        int after = ping();
        ack(before);
        assertTrue(self.mayBeCreativeOrSpectator(), "between the last answered and the first unanswered ping: maybe");
        ack(after);
        assertTrue(self.mayBeCreativeOrSpectator());
        out(listed(SURVIVAL));
        out(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, SURVIVAL));
        sync();
        assertFalse(self.mayBeCreativeOrSpectator());
        out(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, 3.0F));
        sync();
        assertTrue(self.mayBeCreativeOrSpectator(), "MultiPlayerGameMode in spectator");
        out(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, 9.0F));
        sync();
        assertFalse(self.mayBeCreativeOrSpectator(), "GameType.byId: out of range is survival");
    }

    @Test
    void aRemovedListEntryIsNotFollowedUntilANewPlayer() {
        out(new ClientboundPacket.PlayerInfoRemove(List.of(ME)));
        out(listed(SURVIVAL));
        sync();
        assertTrue(self.mayBeCreativeOrSpectator(), "the player may still read the removed entry");
        out(new ClientboundPacket.Respawn(OVERWORLD, SURVIVAL, 0));
        sync();
        assertFalse(self.mayBeCreativeOrSpectator());
    }

    @Test
    void aLaterLoginMayKeepThePlayerAndItsCachedEntry() {
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD, SURVIVAL));
        out(listed(SURVIVAL));
        sync();
        assertTrue(self.mayBeCreativeOrSpectator());
    }

    @Test
    void aVehicleHidesTheEyeUntilAPositionAfterDismounting() {
        out(new ClientboundPacket.SetPassengers(20, new int[] {SELF}));
        assertNull(self.eye(), "may be processed already");
        sync();
        assertNull(self.eye());
        move(11.0, 64.0, -3.5); // a passenger sends no position; even if one came
        assertNull(self.eye());
        out(new ClientboundPacket.SetPassengers(20, new int[0]));
        sync();
        assertNull(self.eye(), "dismounted where the vehicle left it: not sent yet");
        move(12.0, 64.0, -3.5);
        assertEquals(12.0, self.eye().x());
    }

    @Test
    void aRemovedVehicleDismounts() {
        out(new ClientboundPacket.SetPassengers(20, new int[] {SELF}));
        sync();
        out(new ClientboundPacket.RemoveEntities(new int[] {20}));
        sync();
        move(12.0, 64.0, -3.5);
        assertEquals(12.0, self.eye().x());
    }

    @Test
    void aBlockAckTheClientMayHaveProcessedAfterThePositionHidesIt() {
        out(new ClientboundPacket.BlockChangedAck(5));
        assertNull(self.eye(), "a rollback may have moved the player since it sent its position");
        sync();
        assertNull(self.eye());
        move(10.0, 64.0, -3.5);
        assertNotNull(self.eye(), "sent after the ack was processed: the rollback is in it");
    }

    @Test
    void aResetAfterTheFirstUnansweredPingIsNotProcessedYet() {
        int unanswered = ping();
        out(new ClientboundPacket.BlockChangedAck(5));
        assertNotNull(self.eye());
        ack(unanswered);
        assertNull(self.eye());
    }

    @Test
    void anotherCameraHidesTheEye() {
        out(new ClientboundPacket.SetCamera(30));
        sync();
        assertNull(self.eye());
        out(new ClientboundPacket.SetCamera(SELF));
        sync();
        assertNotNull(self.eye());
    }

    @Test
    void theInteractionRangeFollowsTheAttributeInEveryPrefix() {
        int first = ping();
        out(attribute(ClientSelf.INTERACTION_RANGE, 3.0,
                new ClientboundPacket.UpdateAttributes.Modifier("test:long", 2.0, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE)));
        int second = ping();
        ack(first);
        assertEquals(5.0, self.maxInteractionRange(), "between the pings: either");
        ack(second);
        assertEquals(5.0, self.maxInteractionRange());
        out(attribute(ClientSelf.INTERACTION_RANGE, 2.5));
        sync();
        assertEquals(2.5, self.maxInteractionRange(), "a live value: the old one is gone once processed");
        out(attribute(ClientSelf.INTERACTION_RANGE, 100.0));
        sync();
        assertEquals(64.0, self.maxInteractionRange(), "the attribute's range");
    }

    @Test
    void respawnKeepsModifiersOnlyIfTheServerSaysSo() {
        out(attribute(ClientSelf.INTERACTION_RANGE, 3.5,
                new ClientboundPacket.UpdateAttributes.Modifier("test:long", 2.0, ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE)));
        sync();
        assertEquals(5.5, self.maxInteractionRange());
        out(new ClientboundPacket.Respawn(OVERWORLD, SURVIVAL, ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS));
        sync();
        assertEquals(5.5, self.maxInteractionRange());
        out(new ClientboundPacket.Respawn(OVERWORLD, SURVIVAL, 0));
        sync();
        assertEquals(3.5, self.maxInteractionRange(), "assignBaseValues: the base without modifiers");
    }

    @Test
    void respawnNeedsANewPosition() {
        out(new ClientboundPacket.Respawn(OVERWORLD, SURVIVAL, 0));
        sync();
        assertNull(self.eye());
        move(0.5, 80.0, 0.5);
        assertEquals(80.0 + 0.2F, self.eye().y0(), 1e-12);
    }

    @Test
    void theEyeHeightFollowsAScaleChangeOnlyAfterATick() {
        out(attribute(ClientSelf.SCALE, 2.0));
        sync();
        assertEquals(64.0 + 1.62F * 2.0F, self.eye().y1(), 1e-6);
        out(attribute(ClientSelf.SCALE, 1.0));
        sync();
        assertEquals(64.0 + 1.62F * 2.0F, self.eye().y1(), 1e-6, "the cached dimensions are refreshed at the end of a tick");
        tick();
        assertEquals(64.0 + 1.62F, self.eye().y1(), 1e-6);
        out(attribute(ClientSelf.SCALE, 0.25));
        sync();
        tick();
        assertEquals(64.0 + 0.4F * 0.25F, self.eye().y0(), 1e-6, "swimming at a quarter of the size is lower than sleeping");
    }

    @Test
    void aTickBeforeTheWorldLoadedRefreshesNothing() {
        out(new ClientboundPacket.Respawn(OVERWORLD, SURVIVAL, 1));
        out(attribute(ClientSelf.SCALE, 3.0));
        sync();
        move(0.0, 64.0, 0.0);
        tick();
        out(attribute(ClientSelf.SCALE, 1.0));
        sync();
        tick();
        assertEquals(64.0 + 1.62F * 3.0F, self.eye().y1(), 1e-6, "LocalPlayer.tick runs once the client loaded");
        in(new ServerboundPacket.PlayerLoaded());
        tick();
        assertEquals(64.0 + 1.62F, self.eye().y1(), 1e-6);
    }

    @Test
    void lostEventsLoseEverything() {
        self.onEvent(new TimelineEvent.Gap(sequence++, sequence, 10));
        assertNull(self.eye());
        assertTrue(self.mayBeCreativeOrSpectator());
        assertEquals(64.0, self.maxInteractionRange());
    }
}
