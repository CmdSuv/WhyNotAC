package dev.whynotac.analysis.checks;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The look of the client's pick, tick by tick: the rotation every action of a tick was made with.
 *
 * <p>Decompiled 1.21.11 client: {@code LocalPlayer.getViewXRot}/{@code getViewYRot} are the player's current rotation
 * off a vehicle. Nothing between the pick and the tick's {@code LocalPlayer.sendPosition} turns it (the mouse turns it
 * per frame, packets between frames; the actions of {@code handleKeybinds} do not). So the look of the tick's actions
 * is the rotation the tick's own movement packet carries, which is the next movement packet after them in that tick;
 * without rotation in it, or without such a packet, the rotation is unchanged since the last packet
 * {@code sendPosition} sent with one ({@code yRotLast}: replies to the server's teleports and rotations do not change
 * it, and when they turned the player {@code sendPosition} sends the new rotation). Every rotation that last one may
 * have carried is kept ({@link MoveReplies} says which packets may be replies). After a login or respawn nothing is
 * known until the client says it has loaded ({@code PlayerLoaded}: the new player sends no movement before), and the
 * last sent rotation is not known again until the client sends one of its own; nor while it may ride (a passenger's
 * rotation packets do not set it).
 *
 * <p>Fed every event of one session, in order.
 */
final class TickLook {

    /** Rotations the last sent one may be; past this many it is not known until the client certainly sends its own. */
    static final int MAX_ROTATIONS = 8;

    /** A rotation the client sent, and the sequence and time of the packet that carried it. */
    record Rotation(float yRot, float xRot, long sequence, long nanoTime) {

        boolean sameAs(float yaw, float pitch) {
            return Float.compare(yRot, yaw) == 0 && Float.compare(xRot, pitch) == 0;
        }
    }

    private final MoveReplies replies = new MoveReplies();
    /** The client said it loaded the level since the last login or respawn sent. */
    private boolean loaded;
    /** The rotations {@code yRotLast} may be; null: not known. */
    private List<Rotation> lastSent;
    /** The interval's last movement packet: its rotation (null: none, or no rotation), and what it may be. */
    private Rotation lastMove;
    private boolean lastMoveOwn;
    private boolean lastMoveReply;
    /** An action of the tick was sent in this interval: movement packets after it are the tick's own. */
    private boolean acted;

    void onOutbound(TimelineEvent.Outbound out) {
        replies.onOutbound(out);
        if (out.packet() instanceof ClientboundPacket.Login || out.packet() instanceof ClientboundPacket.Respawn) {
            loaded = false; // a new player: yRotLast is its own, and it sends nothing until it has loaded
            lastSent = null;
        }
    }

    void onAcknowledged(TimelineEvent.Acknowledged ack) {
        replies.onAcknowledged(ack);
    }

    void onLoaded() {
        loaded = true;
    }

    void onAccept(ServerboundPacket.AcceptTeleportation accept) {
        replies.onAccept(accept);
    }

    /** An action of the tick (made with the pick's look): the movement packets after it in the interval are the tick's own. */
    void onAction() {
        acted = true;
    }

    /** Whether the client has loaded since the last login or respawn: before, it makes no actions with a look. */
    boolean loaded() {
        return loaded;
    }

    /**
     * A movement packet. Returns its rotation, null without one: for actions sent before it in the tick, the look is that
     * rotation, or without one {@link #candidates()} (asked before {@link #onTickEnd}).
     */
    Rotation onMove(TimelineEvent.Inbound in, ServerboundPacket.MovePlayer move) {
        boolean reply = replies.takeTeleportReply();
        Rotation rotation = move.hasRotation() ? new Rotation(move.yaw(), move.pitch(), in.sequence(), in.nanoTime()) : null;
        lastMove = rotation;
        lastMoveOwn = acted || !reply && !mayBeReply(move);
        lastMoveReply = reply;
        return rotation;
    }

    /** The end of the client tick: the tick's own packet, if any, is the interval's last movement packet that is not a reply. */
    void onTickEnd(boolean mayRide) {
        if (lastMove != null && loaded) {
            if (lastMoveOwn) {
                lastSent = new ArrayList<>(List.of(lastMove));
            } else if (!lastMoveReply && lastSent != null) {
                add(lastMove); // the tick's own or a reply
            }
        }
        if (mayRide) {
            lastSent = null; // a passenger's rotation packets do not set yRotLast
        }
        replies.closeInterval();
        lastMove = null;
        lastMoveOwn = false;
        lastMoveReply = false;
        acted = false;
    }

    /** The rotations the pick may have had when the tick sent none after its actions: the last sent ones (null: not known). */
    List<Rotation> candidates() {
        return lastSent == null ? null : new ArrayList<>(lastSent);
    }

    private void add(Rotation rotation) {
        for (Rotation r : lastSent) {
            if (r.sameAs(rotation.yRot(), rotation.xRot())) {
                return;
            }
        }
        lastSent.add(rotation);
        if (lastSent.size() > MAX_ROTATIONS) {
            lastSent = null;
        }
    }

    /** A rotation-only or position-and-rotation packet that a rotation or entity teleport the client may have handled explains. */
    private boolean mayBeReply(ServerboundPacket.MovePlayer move) {
        if (replies.saturated()) {
            return true;
        }
        if (move.hasRotation() && move.hasPosition()) {
            return replies.vehicleTokens() > 0;
        }
        return move.hasRotation() && replies.rotationTokens() > 0;
    }
}
