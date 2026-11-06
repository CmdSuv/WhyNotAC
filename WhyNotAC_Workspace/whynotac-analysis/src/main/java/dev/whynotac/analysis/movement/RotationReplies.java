package dev.whynotac.analysis.movement;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import java.util.ArrayDeque;

/**
 * The client's replies to the rotations the server sets ({@code ClientPacketListener.handleRotatePlayer}: the rotation
 * set, then {@code Rot(yRot, xRot, false, false)} sent at once, while the client processes packets, outside its tick).
 * A rotation sent after a ping is handled once the client has answered that ping and before it answers the next, so its
 * reply is the first movement packet in between, a teleport's reply aside. A packet shaped as the reply is held out of
 * the tick until the stream shows it was sent while the client processed packets: a second movement packet before the
 * tick ends (a tick sends one, after its other packets), or the answer to the next ping. A tick that ends first leaves
 * it open: the reply before a tick that sent no movement, or the tick's own with the rotation handled after the tick
 * ({@link Outcome#AMBIGUOUS}; the packet stays out, the rotation may still be answered).
 *
 * <p>Fed every event of one session, in order.
 */
public final class RotationReplies {

    /** What an event showed beyond the packets kept out of the ticks. */
    public enum Outcome {
        NONE,
        /** The client answered a ping sent after a rotation without a reply to it the client would send. */
        UNANSWERED,
        /** A tick ended with a packet held: it may have been the tick's own. */
        AMBIGUOUS,
        /** Too many rotations waiting for their reply: all forgotten. */
        OVERFLOW
    }

    static final int MAX_ROTATIONS = 64;
    static final int MAX_PINGS = 4_096;

    /** The reply's yaw and pitch, NaN where not known (relative, or a value the client discards). */
    private record Rotation(long sequence, long pingBefore, float yaw, float pitch, boolean maybeAnswered) {}

    private final ArrayDeque<Rotation> rotations = new ArrayDeque<>();
    /** {id, sequence} of the pings not answered yet, in the order sent. */
    private final ArrayDeque<long[]> pings = new ArrayDeque<>();
    private long lastPing = Long.MIN_VALUE;
    /** The sequence of the last ping the client answered. */
    private long certain = Long.MIN_VALUE;
    /** A movement packet shaped as the first rotation's reply is held. */
    private boolean held;
    /** A teleport was accepted and no movement packet has followed yet: the next is its reply. */
    private boolean afterAccept;

    public Outcome onOutbound(long sequence, ClientboundPacket packet) {
        switch (packet) {
            case ClientboundPacket.Ping ping -> {
                lastPing = sequence;
                pings.addLast(new long[] {ping.id(), sequence});
                if (pings.size() > MAX_PINGS) {
                    pings.pollFirst(); // the client stopped answering: an answer to it is ignored
                }
            }
            case ClientboundPacket.PlayerRotation rotation -> {
                if (rotations.size() >= MAX_ROTATIONS) {
                    clear();
                    return Outcome.OVERFLOW;
                }
                rotations.addLast(rotation(sequence, rotation));
            }
            default -> { }
        }
        return Outcome.NONE;
    }

    /**
     * {@code PositionMoveRotation.calculateAbsolute} (a relative part is added to the client's rotation, not known here;
     * an absolute one is {@code 0.0F +} the value, the pitch {@code Mth.clamp}ed to ±90), then {@code setYRot} and
     * {@code setXRot} (a value not finite is discarded; the pitch {@code % 360.0F} and clamped again).
     */
    private Rotation rotation(long sequence, ClientboundPacket.PlayerRotation r) {
        float yaw = Float.NaN;
        if (!r.relativeYaw()) {
            float y = 0.0F + r.yaw();
            yaw = Float.isFinite(y) ? y : Float.NaN;
        }
        float pitch = Float.NaN;
        if (!r.relativePitch()) {
            float x = 0.0F + r.pitch();
            x = x < -90.0F ? -90.0F : Math.min(x, 90.0F);
            pitch = Float.isFinite(x) ? Math.clamp(x % 360.0F, -90.0F, 90.0F) : Float.NaN;
        }
        return new Rotation(sequence, lastPing, yaw, pitch, false);
    }

    public Outcome onAcknowledged(int id) {
        long sequence = Long.MIN_VALUE;
        for (long[] ping : pings) {
            if (ping[0] == id) {
                sequence = ping[1];
                break;
            }
        }
        if (sequence == Long.MIN_VALUE) {
            return Outcome.NONE;
        }
        while (!pings.isEmpty() && pings.peekFirst()[1] <= sequence) {
            pings.pollFirst();
        }
        certain = Math.max(certain, sequence);
        Outcome outcome = Outcome.NONE;
        while (!rotations.isEmpty() && rotations.peekFirst().sequence() < certain) {
            Rotation r = rotations.pollFirst(); // the client answered a ping sent after it: it handled it, and replied before this answer
            if (held) {
                held = false; // sent while the client processed packets, no tick ended since: the reply
            } else if (!r.maybeAnswered()) {
                outcome = Outcome.UNANSWERED;
            }
        }
        return outcome;
    }

    /** A packet the client sent, other than the end of its tick: whether it is kept out of the tick (a rotation's reply, or held as one). */
    public boolean onInbound(ServerboundPacket packet) {
        if (packet instanceof ServerboundPacket.AcceptTeleportation) {
            afterAccept = true;
            return false;
        }
        if (!(packet instanceof ServerboundPacket.MovePlayer move)) {
            return false;
        }
        if (afterAccept) {
            afterAccept = false; // the teleport's reply
            return false;
        }
        if (held) {
            held = false; // a second movement packet: the held one came before the tick
            rotations.pollFirst();
        }
        Rotation r = rotations.peekFirst();
        if (r == null || certain < r.pingBefore() || !replyShaped(move, r)) {
            return false;
        }
        held = true;
        return true;
    }

    /** {@code Rot(yRot, xRot, false, false)} with the rotation the client set, where it is known. */
    private static boolean replyShaped(ServerboundPacket.MovePlayer move, Rotation r) {
        return move.hasRotation() && !move.hasPosition() && !move.onGround() && !move.horizontalCollision()
                && (Float.isNaN(r.yaw()) || Float.floatToIntBits(move.yaw()) == Float.floatToIntBits(r.yaw()))
                && (Float.isNaN(r.pitch()) || Float.floatToIntBits(move.pitch()) == Float.floatToIntBits(r.pitch()));
    }

    /** The client's tick ended. */
    public Outcome atTickEnd() {
        if (!held) {
            return Outcome.NONE;
        }
        held = false;
        Rotation r = rotations.pollFirst();
        rotations.addFirst(new Rotation(r.sequence(), r.pingBefore(), r.yaw(), r.pitch(), true));
        return Outcome.AMBIGUOUS;
    }

    /** Events were lost or the connection ended: nothing waits any more. */
    public void clear() {
        rotations.clear();
        pings.clear();
        held = false;
        afterAccept = false;
    }
}
