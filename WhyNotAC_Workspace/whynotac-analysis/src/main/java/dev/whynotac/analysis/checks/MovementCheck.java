package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.movement.MovementTracker;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Movement (P-M): the client moved on foot as no vanilla 1.21.11 client could, every state it may have been
 * in considered ({@link MovementTracker}). Its own player is followed tick by tick through the bit-exact
 * on-foot step, from an anchor where its state is known (a new player, or one standing still), through what
 * the server sends it (teleports, knockback, explosions, attributes, effects, food, its own flags), each in the client
 * tick the transactions place it in or, where they cannot, in every tick it may be in. A tick is a finding
 * when no state the client may have been in sends what it sent: the position to the bit, the flags, the
 * sprint and input packets. Whatever the step does not model (fluids, climbing, items in use,
 * vehicles, pushing entities, blocks still changing, the world border) ends the claim until the next anchor.
 *
 * <p>A modified client is proven, not a cheat: a client mod that moves the player differently (or even
 * rounds it differently) produces this too; fly, speed, step, no-slow, jump and velocity cheats on foot all
 * do. At most one finding per {@link #REPORT_INTERVAL_NANOS} per session.
 */
public final class MovementCheck implements TimelineListener {

    public static final String ID = "movement";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public MovementCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final MovementTracker tracker = new MovementTracker();
        long lastReportNanos = Long.MIN_VALUE;
        long unexplainedSinceReport;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        MovementTracker.Tick tick = state.tracker.onEvent(event, replicas.world().world(session), replicas.entities().entities(session));
        if (tick != null && tick.outcome() == MovementTracker.Outcome.UNEXPLAINED) {
            report(session, state, tick);
        }
    }

    private void report(PlayerSession session, State state, MovementTracker.Tick tick) {
        state.unexplainedSinceReport++;
        if (state.lastReportNanos != Long.MIN_VALUE && tick.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = state.admissibility.evaluate(tick.since());
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("clientTick", Long.toString(tick.number()));
        details.put("unexplainedTicks", Long.toString(state.unexplainedSinceReport));
        ServerboundPacket.MovePlayer sent = tick.sent();
        details.put("sent", sent == null ? "nothing" : describe(sent));
        PlayerState closest = tick.closest();
        if (closest != null) {
            details.put("predicted", format(closest.x()) + "," + format(closest.y()) + "," + format(closest.z()) + " onGround=" + closest.onGround()
                    + " horizontalCollision=" + closest.horizontalCollision() + " sprinting=" + closest.sprinting());
            if (sent != null && sent.hasPosition()) {
                double dx = sent.x() - closest.x();
                double dy = sent.y() - closest.y();
                double dz = sent.z() - closest.z();
                details.put("offset", format(dx) + "," + format(dy) + "," + format(dz));
            }
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, tick.since(), tick.sequence(), tick.nanoTime(), tick.nanoTime(),
                "moved on foot as no vanilla client could (tick " + tick.number() + ")", details, admissibility));
        state.lastReportNanos = tick.nanoTime();
        state.unexplainedSinceReport = 0;
    }

    private static String describe(ServerboundPacket.MovePlayer m) {
        StringBuilder b = new StringBuilder(m.shape().name());
        if (m.hasPosition()) {
            b.append(' ').append(format(m.x())).append(',').append(format(m.y())).append(',').append(format(m.z()));
        }
        if (m.hasRotation()) {
            b.append(" rot ").append(m.yaw()).append(',').append(m.pitch());
        }
        return b.append(" onGround=").append(m.onGround()).append(" horizontalCollision=").append(m.horizontalCollision()).toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
