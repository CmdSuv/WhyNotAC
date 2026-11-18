package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Replicas;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Velocity response ({@code velocity-response}, P-A, SHADOW): verifies that when the server sends a positive
 * vertical knockback ({@code ClientboundSetEntityMotionPacket} with {@code vy >= 0.12}) to an on-ground survival
 * player with open air above their head, the client does not ignore the vertical impulse ({@code 0%} Anti-KB /
 * velocity cancellation) in the client tick following transaction acknowledgement.
 *
 * <p>Even when nearby entities cause {@link MovementCheck} to withdraw its bit-exact horizontal step claim,
 * {@code Entity.push} is strictly horizontal ({@code dy = 0}) and cannot cancel a vertical {@code SetEntityMotion}
 * impulse when no ceiling block or climbable/fluid block obstructs the player.
 */
public final class VelocityCheck implements TimelineListener {

    public static final String ID = "velocity-response";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final double MIN_VERTICAL_KB = 0.12;
    static final double MIN_EXPECTED_RISE_FACTOR = 0.50;

    private static final int AIR_STATE = BlockStates.vanilla().firstStateOf("minecraft:air");
    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public VelocityCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private record PendingKb(long sentSeq, long sentNano, double vy, double baseY) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final PingLedger ledger;
        final boolean sharedLedger;
        long lastReportNanos = Long.MIN_VALUE;

        boolean loaded;
        int groundPosCount;
        double lastX = Double.NaN;
        double lastY = Double.NaN;
        double lastZ = Double.NaN;
        boolean lastOnGround;
        boolean movedUpSinceKb;

        PendingKb pending;
        PendingKb activeAcked;
        int ticksSinceAck;

        State(PingLedger shared) {
            this.sharedLedger = shared != null;
            this.ledger = shared != null ? shared : new PingLedger();
        }

        static State fromSession(PlayerSession session) {
            return new State(session.pingLedger());
        }

        void reset() {
            groundPosCount = 0;
            lastOnGround = false;
            movedUpSinceKb = false;
            pending = null;
            activeAcked = null;
            ticksSinceAck = 0;
        }
    }

    @Override
    public boolean readsEntityMotion() {
        return true;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        if (event instanceof TimelineEvent.Outbound out
                && (out.packet() instanceof ClientboundPacket.MoveEntity
                || out.packet() instanceof ClientboundPacket.RotateHead
                || out.packet() instanceof ClientboundPacket.EntityPositionSync)) {
            return;
        }
        State state = session.attachment(STATE, State::fromSession);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> state.reset();
            case TimelineEvent.Outbound out -> onOutbound(session, state, out);
            case TimelineEvent.Acknowledged ack -> onAcknowledged(state, ack);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onOutbound(PlayerSession session, State state, TimelineEvent.Outbound out) {
        switch (out.packet()) {
            case ClientboundPacket.Login ignored -> {
                state.loaded = false;
                state.reset();
            }
            case ClientboundPacket.Respawn ignored -> {
                state.loaded = false;
                state.reset();
            }
            case ClientboundPacket.PlayerPosition ignored -> state.reset();
            case ClientboundPacket.Ping ping -> {
                if (!state.sharedLedger) {
                    state.ledger.onPing(ping.id(), out.sequence());
                }
            }
            case ClientboundPacket.SetEntityMotion motion -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && motion.entityId() == self.selfId()) {
                    Vec3d v = motion.clientMotion();
                    if (v != null && v.y() >= MIN_VERTICAL_KB && state.loaded && state.groundPosCount >= 2
                            && state.lastOnGround && Double.isFinite(state.lastY)
                            && !self.mayBeCreativeOrSpectator() && !self.mayRide()
                            && hasOpenAirAbove(replicas.world().world(session), state.lastX, state.lastY, state.lastZ)) {
                        state.pending = new PendingKb(out.sequence(), out.nanoTime(), v.y(), state.lastY);
                        state.activeAcked = null;
                        state.movedUpSinceKb = false;
                        state.ticksSinceAck = 0;
                    } else {
                        state.pending = null;
                        state.activeAcked = null;
                    }
                }
            }
            default -> { }
        }
    }

    private void onAcknowledged(State state, TimelineEvent.Acknowledged ack) {
        long pingSeq = state.sharedLedger ? state.ledger.lastMatched() : state.ledger.onAcknowledged(ack.transactionId());
        if (pingSeq != Long.MIN_VALUE && state.pending != null && pingSeq > state.pending.sentSeq()) {
            state.activeAcked = state.pending;
            state.pending = null;
            state.ticksSinceAck = 0;
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerLoaded ignored -> {
                state.loaded = true;
                state.reset();
            }
            case ServerboundPacket.AcceptTeleportation ignored -> state.reset();
            case ServerboundPacket.MovePlayer move -> {
                if (move.hasPosition() && !move.hasNonFiniteValue()) {
                    if (state.pending != null || state.activeAcked != null) {
                        double refY = state.activeAcked != null ? state.activeAcked.baseY() : state.pending.baseY();
                        double expVy = state.activeAcked != null ? state.activeAcked.vy() : state.pending.vy();
                        if (move.y() - refY >= expVy * MIN_EXPECTED_RISE_FACTOR - 1.0e-4 || !move.onGround()) {
                            state.movedUpSinceKb = true;
                        }
                    }
                    state.lastX = move.x();
                    state.lastY = move.y();
                    state.lastZ = move.z();
                    if (state.loaded && move.onGround()) {
                        state.groundPosCount++;
                    }
                } else if (!move.onGround() && (state.pending != null || state.activeAcked != null)) {
                    state.movedUpSinceKb = true;
                }
                state.lastOnGround = move.onGround();
            }
            case ServerboundPacket.ClientTickEnd ignored -> onTickEnd(session, state, in);
            default -> { }
        }
    }

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        if (state.activeAcked == null) {
            return;
        }
        ClientSelf self = replicas.self().self(session);
        if (self.mayBeCreativeOrSpectator() || self.mayRide()
                || !hasOpenAirAbove(replicas.world().world(session), state.lastX, state.lastY, state.lastZ)) {
            state.activeAcked = null;
            return;
        }
        if (state.movedUpSinceKb) {
            state.activeAcked = null;
            return;
        }
        state.ticksSinceAck++;
        if (state.ticksSinceAck >= 2) {
            PendingKb kb = state.activeAcked;
            state.activeAcked = null;
            if (state.lastReportNanos != Long.MIN_VALUE && tickEnd.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
                return;
            }
            Admissibility admissibility = state.admissibility.evaluate(kb.sentSeq());
            if (!admissibility.admissible()) {
                sink.rejected(session, ID, admissibility);
                return;
            }
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "ignored-vertical-knockback");
            details.put("sentVy", String.format(Locale.ROOT, "%.4f", kb.vy()));
            details.put("baseY", String.format(Locale.ROOT, "%.4f", kb.baseY()));
            details.put("currentY", String.format(Locale.ROOT, "%.4f", state.lastY));
            sink.accept(session, new Evidence(
                    ID, VERSION, EvidenceClass.ADVANTAGE_PROOF,
                    kb.sentSeq(), tickEnd.sequence(), kb.sentNano(), tickEnd.nanoTime(),
                    String.format(Locale.ROOT, "ignored vertical knockback vy=%.4f (stayed onGround at y=%.2f)", kb.vy(), state.lastY),
                    details, admissibility));
            state.lastReportNanos = tickEnd.nanoTime();
        }
    }

    private static boolean hasOpenAirAbove(ClientWorld world, double x, double y, double z) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        if (!world.isLoaded(bx >> 4, bz >> 4)) {
            return false;
        }
        int feet = world.settledBlockState(bx, by, bz);
        int head = world.settledBlockState(bx, by + 1, bz);
        int aboveHead = world.settledBlockState(bx, by + 2, bz);
        return feet == AIR_STATE && head == AIR_STATE && aboveHead == AIR_STATE;
    }
}
