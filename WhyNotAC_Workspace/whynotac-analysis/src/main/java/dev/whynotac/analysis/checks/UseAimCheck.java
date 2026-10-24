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
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>A block is used ({@code ServerboundUseItemOnPacket}) where the pick of the tick hit it: the block, its face and the
 * cursor are the pick's ({@link BlockClick}). A click that no look of the tick can have made from any pose's eye proves
 * a modified client: the cursor off the look ({@code look}), a face the look cannot enter ({@code face}), a point not
 * on the block's outline (a click on air, or on a block that is not there: {@code cursor}), out of
 * {@code block_interaction_range} ({@code reach}), or through blocks that certainly stop the look ({@code blocks}). The
 * blocks are those the client may have held when its pick ran, before its own predictions of the tick; nothing the
 * client confirmed after the click is taken for them. No claim for a click with an {@code attack_range} item (its own
 * pick comes first), a world border hit, a spectator, the eye not known, or where {@link AttackReachCheck} makes none.
 *
 * <p>Digging the same way: {@code START_DESTROY_BLOCK} and {@code STOP_DESTROY_BLOCK} carry the block and face of the
 * tick's pick ({@code MultiPlayerGameMode.startDestroyBlock} and {@code continueDestroyBlock}, from {@code startAttack}
 * and {@code continueAttack}), so the look must be able to enter that block's outline through that face, in reach and
 * before any block certainly stops it ({@link BlockClick#judgeDig}; no outline there at all: {@code outline}). An abort
 * names the block being broken, not the pick's, and is not judged.
 *
 * <p>No claim while the rotation of the tick is not known ({@link TickLook}), nor while the player may ride (a vehicle
 * may turn it within the tick). At most one finding per {@link #REPORT_INTERVAL_NANOS} per session; the next one
 * counts what came between.
 */
public final class UseAimCheck implements TimelineListener {

    public static final String ID = "use-aim";
    public static final int VERSION = 3;
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

    /**
     * A click ({@code UseItemOn}) or a dig ({@code PlayerAction}) waiting for the look of its tick: the eyes and ranges
     * the client may have had then.
     */
    private record Click(TimelineEvent.Inbound in, ServerboundPacket.UseItemOn click, ServerboundPacket.PlayerAction dig, List<AttackReach.Eye> eyes,
                         ClientSelf.Eye own, long eyeSequence, long eyeNanos, double blockHigh, double pickLow, double pickHigh,
                         long acknowledgements) {

        dev.whynotac.protocol.packet.BlockPosition position() {
            return click != null ? click.position() : dig.position();
        }

        dev.whynotac.protocol.packet.BlockFace face() {
            return click != null ? click.face() : dig.face();
        }

        int sequence() {
            return click != null ? click.sequence() : dig.sequence();
        }
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final TickLook look = new TickLook();
        final List<Pending> pending = new ArrayList<>();
        final List<Click> clicks = new ArrayList<>();
        /** The lowest block-action sequence of the tick: its pick saw the world before it. */
        int firstAction = Integer.MAX_VALUE;
        /** Acknowledgements seen: after one the world may hold less than the client may have had at an earlier click. */
        long acknowledgements;
        long lastReportNanos = Long.MIN_VALUE;
        long rotationSinceReport;
        long clicksSinceReport;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out -> state.look.onOutbound(out);
            case TimelineEvent.Acknowledged ack -> {
                state.look.onAcknowledged(ack);
                state.acknowledgements++;
            }
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
                action(state, use.sequence());
                if (state.look.loaded() && !replicas.self().self(session).mayRide()) {
                    state.pending.add(new Pending(in, use));
                }
            }
            case ServerboundPacket.UseItemOn click -> {
                state.look.onAction();
                action(state, click.sequence());
                onClick(session, state, in, click, null);
            }
            case ServerboundPacket.PlayerAction dig when dig.action() == ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK
                    || dig.action() == ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK -> {
                state.look.onAction();
                action(state, dig.sequence());
                onClick(session, state, in, null, dig);
            }
            case ServerboundPacket.MovePlayer move -> {
                TickLook.Rotation rotation = state.look.onMove(in, move);
                if (!state.pending.isEmpty() || !state.clicks.isEmpty()) {
                    judge(session, state, in, rotation != null ? List.of(rotation) : state.look.candidates());
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> {
                if (!state.pending.isEmpty() || !state.clicks.isEmpty()) {
                    judge(session, state, in, state.look.candidates()); // no movement packet after the use: the rotation is unchanged
                }
                state.look.onTickEnd(replicas.self().self(session).mayRide());
                state.firstAction = Integer.MAX_VALUE;
            }
            default -> {
                if (in.packet() instanceof ServerboundPacket.PlayerAction action && action.sequence() > 0) {
                    action(state, action.sequence());
                }
                if (ActionGrammarCheck.action(in.packet()) != null || in.packet() instanceof ServerboundPacket.Swing) {
                    state.look.onAction();
                }
            }
        }
    }

    /** A block action of the tick (it predicts with this sequence). */
    private static void action(State state, int sequence) {
        state.firstAction = Math.min(state.firstAction, sequence);
    }

    private void onClick(PlayerSession session, State state, TimelineEvent.Inbound in, ServerboundPacket.UseItemOn click,
            ServerboundPacket.PlayerAction dig) {
        ClientSelf self = replicas.self().self(session);
        if (!state.look.loaded() || self.mayRide() || self.mayBeSpectator() || click != null && click.worldBorderHit()) {
            return;
        }
        ClientSelf.Eye own = self.eye();
        List<ClientSelf.Eye> poses = self.eyes();
        Set<ClientInventory.Reach> pick = replicas.inventory().inventory(session).pickReach();
        if (own == null || poses == null || pick.isEmpty() || pick.contains(ClientInventory.Reach.UNKNOWN)) {
            return;
        }
        for (ClientInventory.Reach reach : pick) {
            if (reach.range() != null) {
                return; // an attack_range item picks first, with its own ray
            }
        }
        List<AttackReach.Eye> eyes = new ArrayList<>(poses.size());
        for (ClientSelf.Eye e : poses) {
            eyes.add(new AttackReach.Eye(e.x(), e.z(), e.y0(), e.y1(), e.radius()));
        }
        double[] block = self.blockRangeBounds();
        double[] entity = self.interactionRangeBounds();
        state.clicks.add(new Click(in, click, dig, eyes, own, self.eyeSequence(), self.eyeNanoTime(), block[1], Math.max(block[0], entity[0]),
                Math.max(block[1], entity[1]), state.acknowledgements));
    }

    private void judge(PlayerSession session, State state, TimelineEvent.Inbound at, List<TickLook.Rotation> looks) {
        List<Pending> pending = new ArrayList<>(state.pending);
        List<Click> clicks = new ArrayList<>(state.clicks);
        state.pending.clear();
        state.clicks.clear();
        if (looks == null || replicas.self().self(session).mayRide()) {
            return; // the rotation is not known
        }
        ClientWorld world = replicas.world().world(session);
        for (Click c : clicks) {
            BlockClick.Failure failure = judge(c, c.acknowledgements() == state.acknowledgements ? world : null, looks, state.firstAction);
            if (failure == null || AttackReachCheck.mayHaveMoved(replicas.self().self(session), c.own(), replicas.entities().entities(session),
                    world) != null) {
                continue;
            }
            state.clicksSinceReport++;
            report(session, state, c, failure, looks, at);
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

    /**
     * Null if some look of {@code looks}, from some pose's eye, may have made the click; else the failure of the
     * combination that came furthest ({@link BlockClick.Stage}).
     */
    private static BlockClick.Failure judge(Click c, ClientWorld world, List<TickLook.Rotation> looks, int firstAction) {
        int before = Math.min(firstAction, c.sequence());
        BlockClick.Failure furthest = null;
        for (TickLook.Rotation look : looks) {
            double[] v = AttackAim.view(look.xRot(), look.yRot());
            for (AttackReach.Eye eye : c.eyes()) {
                BlockClick.Failure f = c.click() != null
                        ? BlockClick.judge(world, c.position(), c.face(), c.click().location(), c.click().inside(), v, eye, c.blockHigh(), c.pickLow(),
                                c.pickHigh(), before)
                        : BlockClick.judgeDig(world, c.position(), c.face(), v, eye, c.blockHigh(), c.pickLow(), c.pickHigh(), before);
                if (f == null) {
                    return null;
                }
                if (furthest == null || f.stage().compareTo(furthest.stage()) > 0
                        || f.stage() == furthest.stage() && f.stage() == BlockClick.Stage.BLOCKS && f.gap() < furthest.gap()) {
                    furthest = f;
                }
            }
        }
        return furthest;
    }

    private void report(PlayerSession session, State state, Click c, BlockClick.Failure failure, List<TickLook.Rotation> looks,
            TimelineEvent.Inbound at) {
        if (state.lastReportNanos != Long.MIN_VALUE && c.in().nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        long from = Math.min(c.in().sequence(), c.eyeSequence());
        long fromNanos = c.in().sequence() <= c.eyeSequence() ? c.in().nanoTime() : c.eyeNanos();
        List<String> rotations = new ArrayList<>();
        for (TickLook.Rotation look : looks) {
            if (look.sequence() < from) {
                from = look.sequence();
                fromNanos = look.nanoTime();
            }
            rotations.add(format(look.yRot()) + "/" + format(look.xRot()));
        }
        state.lastReportNanos = c.in().nanoTime();
        Admissibility admissibility = state.admissibility.evaluate(from);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        ServerboundPacket.UseItemOn click = c.click();
        String block = c.position().x() + "," + c.position().y() + "," + c.position().z();
        String face = c.face().name().toLowerCase(Locale.ROOT);
        String what = click != null ? "clicked the block " + block + " (" + face + ")" : "dug the block " + block + " (" + face + ")";
        Map<String, String> details = new TreeMap<>();
        boolean noOutline = click == null && failure.stage() == BlockClick.Stage.CURSOR;
        details.put("reason", noOutline ? "outline" : failure.stage().name().toLowerCase(Locale.ROOT));
        details.put("action", click != null ? "click" : c.dig().action().name());
        details.put("block", block);
        details.put("face", c.face().name());
        if (click != null) {
            details.put("hand", click.hand().name());
            details.put("cursor", format(click.location().x()) + "," + format(click.location().y()) + "," + format(click.location().z()));
            details.put("inside", Boolean.toString(click.inside()));
        }
        details.put("looks", String.join(",", rotations));
        AttackReach.Eye eye = c.eyes().getFirst();
        details.put("eye", format(eye.x()) + "," + format(eye.y0()) + ".." + format(eye.y1()) + "," + format(eye.z()));
        details.put("offLookBlockActions", Long.toString(state.clicksSinceReport));
        details.put("rotationUses", Long.toString(state.rotationSinceReport));
        state.clicksSinceReport = 0;
        state.rotationSinceReport = 0;
        String summary = switch (failure.stage()) {
            case LOOK -> click != null ? what + " at a point no look of the tick passes through" : what + ", which no look of the tick meets";
            case FACE -> what + " on a face its look cannot enter through";
            case CURSOR -> noOutline ? what + ", which has no outline the client may see" : what + " at a point not on its outline";
            case REACH -> {
                details.put("distance", format(failure.distance()));
                details.put("allowed", format(c.blockHigh()));
                yield what + " from at least " + format(failure.distance()) + " blocks (at most " + format(c.blockHigh()) + " possible)";
            }
            case BLOCKS -> {
                String wall = failure.wallX() + "," + failure.wallY() + "," + failure.wallZ();
                details.put("wall", wall);
                details.put("stoppedBefore", format(failure.gap()));
                yield what + " through blocks: its look stops at the block " + wall + ", at least " + format(failure.gap()) + " blocks before it";
            }
        };
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, from, at.sequence(), fromNanos, at.nanoTime(), summary,
                details, admissibility));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
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
        details.put("offLookBlockActions", Long.toString(state.clicksSinceReport));
        state.rotationSinceReport = 0;
        state.clicksSinceReport = 0;
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, from, at.sequence(), fromNanos, at.nanoTime(),
                "used the item with the rotation " + used + ", not the tick's look " + String.join(" or ", rotations), details, admissibility));
    }

    /** Exact: the shortest decimal that reads back to the same float. */
    private static String format(float value) {
        return Float.toString(value);
    }
}
