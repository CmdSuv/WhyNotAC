package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Use aim (P-M): the vanilla 1.21.11 client uses an item with the look of its tick.
 *
 * <p>{@code MultiPlayerGameMode.useItem} (decompiled 1.21.11; its one caller is {@code Minecraft.startUseItem}, from
 * {@code handleKeybinds}) sends {@code ServerboundUseItemPacket} with the player's current {@code getYRot()} and
 * {@code getXRot()}. Nothing turns the player between that and the tick's {@code sendPosition}, so they are bit for bit
 * the rotation of the tick ({@link TickLook}): the one its own movement packet carries, or without one the last sent.
 * A use with any other rotation proves a modified client: one that turns only for the server (aims a throw, a pearl or
 * a potion without the player's look moving), or puts a fake rotation in the packet.
 *
 * <p>No claim while the rotation of the tick is not known ({@link TickLook}), nor while the player may ride (a vehicle
 * may turn it within the tick). At most one finding per {@link #REPORT_INTERVAL_NANOS} per session; the next one
 * counts what came between.
 */
public final class UseAimCheck implements TimelineListener {

    public static final String ID = "use-aim";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public UseAimCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    /** A use waiting for the look of its tick. */
    private record Pending(TimelineEvent.Inbound in, ServerboundPacket.UseItem use) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final TickLook look = new TickLook();
        final List<Pending> pending = new ArrayList<>();
        long lastReportNanos = Long.MIN_VALUE;
        long rotationSinceReport;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out -> state.look.onOutbound(out);
            case TimelineEvent.Acknowledged ack -> state.look.onAcknowledged(ack);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerLoaded ignored -> state.look.onLoaded();
            case ServerboundPacket.AcceptTeleportation accept -> state.look.onAccept(accept);
            case ServerboundPacket.UseItem use -> {
                state.look.onAction();
                if (state.look.loaded() && !replicas.self().self(session).mayRide()) {
                    state.pending.add(new Pending(in, use));
                }
            }
            case ServerboundPacket.MovePlayer move -> {
                TickLook.Rotation rotation = state.look.onMove(in, move);
                if (!state.pending.isEmpty()) {
                    judge(session, state, in, rotation != null ? List.of(rotation) : state.look.candidates());
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> {
                if (!state.pending.isEmpty()) {
                    judge(session, state, in, state.look.candidates()); // no movement packet after the use: the rotation is unchanged
                }
                state.look.onTickEnd(replicas.self().self(session).mayRide());
            }
            default -> {
                if (ActionGrammarCheck.action(in.packet()) != null || in.packet() instanceof ServerboundPacket.Swing) {
                    state.look.onAction();
                }
            }
        }
    }

    private void judge(PlayerSession session, State state, TimelineEvent.Inbound at, List<TickLook.Rotation> looks) {
        List<Pending> pending = new ArrayList<>(state.pending);
        state.pending.clear();
        if (looks == null || replicas.self().self(session).mayRide()) {
            return; // the rotation is not known
        }
        for (Pending p : pending) {
            boolean same = false;
            for (TickLook.Rotation look : looks) {
                same |= look.sameAs(p.use().yaw(), p.use().pitch());
            }
            if (!same) {
                state.rotationSinceReport++;
                report(session, state, p, looks, at);
            }
        }
    }

    private void report(PlayerSession session, State state, Pending p, List<TickLook.Rotation> looks, TimelineEvent.Inbound at) {
        if (state.lastReportNanos != Long.MIN_VALUE && p.in().nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        long from = p.in().sequence();
        long fromNanos = p.in().nanoTime();
        List<String> rotations = new ArrayList<>();
        for (TickLook.Rotation look : looks) {
            if (look.sequence() < from) {
                from = look.sequence();
                fromNanos = look.nanoTime();
            }
            rotations.add(format(look.yRot()) + "/" + format(look.xRot()));
        }
        state.lastReportNanos = p.in().nanoTime();
        Admissibility admissibility = state.admissibility.evaluate(from);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        String used = format(p.use().yaw()) + "/" + format(p.use().pitch());
        Map<String, String> details = new TreeMap<>();
        details.put("reason", "rotation");
        details.put("hand", p.use().hand().name());
        details.put("used", used);
        details.put("looks", String.join(",", rotations));
        details.put("rotationUses", Long.toString(state.rotationSinceReport));
        state.rotationSinceReport = 0;
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, from, at.sequence(), fromNanos, at.nanoTime(),
                "used the item with the rotation " + used + ", not the tick's look " + String.join(" or ", rotations), details, admissibility));
    }

    /** Exact: the shortest decimal that reads back to the same float. */
    private static String format(float value) {
        return Float.toString(value);
    }
}
