package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.EntitySizes;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Attack aim (P-M): the vanilla 1.21.11 client attacks only an entity its look met at the start of the tick
 * ({@link AttackAim}). A look that cannot meet the attacked entity's box from any eye the client may have had (pose by
 * pose: {@code ClientSelf#eyes}, so the pitch counts too), for any box the entity may have had, proves a modified client: one that attacks what it is not looking at (a kill aura
 * that turns only some of the time, or hits several entities with one look).
 *
 * <p>Which look (decompiled 1.21.11 client): {@code LocalPlayer.getViewXRot}/{@code getViewYRot} are the player's
 * current rotation off a vehicle. Nothing between the pick and the tick's {@code LocalPlayer.sendPosition} turns it
 * (the mouse turns it per frame, packets between frames; the actions of {@code handleKeybinds} do not). So the
 * look of the pick is the rotation the tick's own movement packet carries, which is the next movement packet after
 * the attack in that tick; without rotation in it, or without such a packet, the rotation is unchanged since the
 * last packet {@code sendPosition} sent with one ({@code yRotLast}: replies to the server's teleports and
 * rotations do not change it, and when they turned the player {@code sendPosition} sends the new rotation). This
 * check keeps every rotation that last one may have carried ({@link MoveReplies} says which packets may be
 * replies). The head's yaw ({@code attack_range} items) is the yaw at the previous tick's {@code aiStep}, which is
 * {@code yRotLast} after that tick (teleports set the view's yaw, not the head's): one of the same rotations. After a login or respawn nothing is claimed until the client says it has loaded
 * ({@code PlayerLoaded}: the new player sends no movement before), and the last sent rotation is not known again
 * until the client sends one of its own; nor while it may ride (a passenger's rotation packets do not set it).
 *
 * <p>Nor does it attack an entity behind a block (the pick, {@code GameRenderer.pick}: the entity only if the look meets it
 * nearer than the block its clip hit). Without an {@code attack_range} item, a look that meets the entity only past a block
 * that certainly stops it ({@link ClientWorld#certainOutlineHit}), from every eye and for every box the entity may have
 * had, proves the same. The blocks are those the client may have held when it attacked: nothing the client confirmed after
 * the attack is taken for it.
 *
 * <p>No claim either where {@link AttackReachCheck} makes none: the player's eye or the target's box not bounded,
 * a spectator, something that may have moved the player after the position its eye is based on, an item reach
 * not known. At most one finding per {@link #REPORT_INTERVAL_NANOS} per session; the next one counts what came between.
 */
public final class AttackAimCheck implements TimelineListener {

    public static final String ID = "attack-aim";
    public static final int VERSION = 2;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    /** Rotations the last sent one may be; past this many it is not known until the client certainly sends its own. */
    static final int MAX_ROTATIONS = 8;
    /** How far along the look a report measures the miss. */
    static final double REPORT_LENGTH = 64.0;

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public AttackAimCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    /** A rotation the client sent, and the sequence and time of the packet that carried it. */
    record Rotation(float yRot, float xRot, long sequence, long nanoTime) {}

    /**
     * An attack waiting for the look of its tick: every box of offsets from an eye to the target, state by state and in
     * each the eyes pose by pose; and how many acknowledgements the session had seen then.
     */
    private record Pending(TimelineEvent.Inbound attack, int target, String type, AttackReach.Eye eye, long eyeSequence, long eyeNanos,
                           List<AttackReach.Eye> eyes, List<AttackReach.Box> picked, List<AttackReach.Box> ranged, ClientSelf.Eye own,
                           int states, long acknowledgements) {}

    /** A block every look stops at, {@code gap} blocks along it at least before it could meet the target (the weakest case). */
    private record Wall(double gap, int x, int y, int z) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final MoveReplies replies = new MoveReplies();
        /** The client said it loaded the level since the last login or respawn sent. */
        boolean loaded;
        /** The rotations {@code yRotLast} may be; null: not known. */
        List<Rotation> lastSent;
        /** The interval's last movement packet: its rotation (null: none, or no rotation), and what it may be. */
        Rotation lastMove;
        boolean lastMoveOwn;
        boolean lastMoveReply;
        /** An action of the tick was sent in this interval: movement packets after it are the tick's own. */
        boolean acted;
        final List<Pending> pending = new ArrayList<>();
        /** Acknowledgements seen: after one the world may hold less than the client may have had at an earlier attack. */
        long acknowledgements;
        long lastReportNanos = Long.MIN_VALUE;
        long offAimSinceReport;
        long throughBlocksSinceReport;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out -> {
                state.replies.onOutbound(out);
                if (out.packet() instanceof ClientboundPacket.Login || out.packet() instanceof ClientboundPacket.Respawn) {
                    state.loaded = false; // a new player: yRotLast is its own, and it sends nothing until it has loaded
                    state.lastSent = null;
                }
            }
            case TimelineEvent.Acknowledged ack -> {
                state.replies.onAcknowledged(ack);
                state.acknowledgements++;
            }
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerLoaded ignored -> state.loaded = true;
            case ServerboundPacket.AcceptTeleportation accept -> state.replies.onAccept(accept);
            case ServerboundPacket.InteractEntity interact when interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK -> {
                state.acted = true;
                onAttack(session, state, in, interact.entityId());
            }
            case ServerboundPacket.MovePlayer move -> onMove(session, state, in, move);
            case ServerboundPacket.ClientTickEnd ignored -> onTickEnd(session, state, in);
            default -> {
                if (ActionGrammarCheck.action(in.packet()) != null || in.packet() instanceof ServerboundPacket.Swing) {
                    state.acted = true;
                }
            }
        }
    }

    private void onAttack(PlayerSession session, State state, TimelineEvent.Inbound in, int target) {
        ClientSelf self = replicas.self().self(session);
        if (!state.loaded || target == self.selfId() || self.mayBeSpectator()) {
            return;
        }
        ClientSelf.Eye own = self.eye();
        List<ClientSelf.Eye> poses = self.eyes();
        if (own == null || poses == null) {
            return;
        }
        AttackReach.Eye eye = new AttackReach.Eye(own.x(), own.z(), own.y0(), own.y1(), own.radius());
        List<AttackReach.Eye> eyes = new ArrayList<>(poses.size());
        for (ClientSelf.Eye e : poses) {
            eyes.add(new AttackReach.Eye(e.x(), e.z(), e.y0(), e.y1(), e.radius()));
        }
        Set<ClientInventory.Reach> pick = replicas.inventory().inventory(session).pickReach();
        if (pick.isEmpty() || pick.contains(ClientInventory.Reach.UNKNOWN)) {
            return;
        }
        boolean ranged = false;
        double margin = 0.0;
        for (ClientInventory.Reach reach : pick) {
            if (reach.range() != null) {
                ranged = true;
                margin = Math.max(margin, reach.range().hitboxMargin());
            }
        }
        List<ClientEntities.PossibleState> states = new ArrayList<>();
        replicas.entities().entities(session).forEachPossibleState(target, states::add);
        if (states.isEmpty()) {
            return;
        }
        List<AttackReach.Box> picked = new ArrayList<>();
        List<AttackReach.Box> rangedBoxes = ranged ? new ArrayList<>() : null;
        String type = null;
        for (ClientEntities.PossibleState s : states) {
            if (!s.present() || !s.known() || !s.positionKnown() || s.bounds() == null || s.pickable() == 0) {
                return;
            }
            EntitySizes.Box b = s.bounds(); // the position's radius included
            AttackReach.Box box = new AttackReach.Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
            for (AttackReach.Eye e : eyes) {
                picked.add(AttackAim.offsets(e, box.inflate(s.pickRadius())));
                if (ranged) {
                    rangedBoxes.add(AttackAim.offsets(e, box.inflate(Math.max(s.pickRadius(), margin))));
                }
            }
            type = s.type();
        }
        state.pending.add(new Pending(in, target, type, eye, self.eyeSequence(), self.eyeNanoTime(), eyes, picked, rangedBoxes, own, states.size(),
                state.acknowledgements));
    }

    private void onMove(PlayerSession session, State state, TimelineEvent.Inbound in, ServerboundPacket.MovePlayer move) {
        boolean reply = state.replies.takeTeleportReply();
        Rotation rotation = move.hasRotation() ? new Rotation(move.yaw(), move.pitch(), in.sequence(), in.nanoTime()) : null;
        if (!state.pending.isEmpty()) {
            // the tick's own packet: its rotation is the pick's, or the last sent one is
            judge(session, state, in, rotation != null ? List.of(rotation) : candidates(state));
        }
        state.lastMove = rotation;
        state.lastMoveOwn = state.acted || !reply && !mayBeReply(state.replies, move);
        state.lastMoveReply = reply;
    }

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound in) {
        if (!state.pending.isEmpty()) {
            judge(session, state, in, candidates(state)); // no movement packet after the attack: the rotation is unchanged
        }
        // the tick's own packet is the interval's last movement packet, if it is not a reply
        if (state.lastMove != null && state.loaded) {
            if (state.lastMoveOwn) {
                state.lastSent = new ArrayList<>(List.of(state.lastMove));
            } else if (!state.lastMoveReply && state.lastSent != null) {
                add(state, state.lastMove); // the tick's own or a reply
            }
        }
        if (replicas.self().self(session).eyeState().equals("vehicle")) {
            state.lastSent = null; // a passenger's rotation packets do not set yRotLast
        }
        state.replies.closeInterval();
        state.lastMove = null;
        state.lastMoveOwn = false;
        state.lastMoveReply = false;
        state.acted = false;
    }

    private static void add(State state, Rotation rotation) {
        for (Rotation r : state.lastSent) {
            if (Float.compare(r.yRot(), rotation.yRot()) == 0 && Float.compare(r.xRot(), rotation.xRot()) == 0) {
                return;
            }
        }
        state.lastSent.add(rotation);
        if (state.lastSent.size() > MAX_ROTATIONS) {
            state.lastSent = null;
        }
    }

    /** The rotations the pick may have had when the tick sent none after the attack: the last sent ones (null: not known). */
    private static List<Rotation> candidates(State state) {
        return state.lastSent == null ? null : new ArrayList<>(state.lastSent);
    }

    /** A rotation-only or position-and-rotation packet that a rotation or entity teleport the client may have handled explains. */
    private static boolean mayBeReply(MoveReplies replies, ServerboundPacket.MovePlayer move) {
        if (replies.saturated()) {
            return true;
        }
        if (move.hasRotation() && move.hasPosition()) {
            return replies.vehicleTokens() > 0;
        }
        return move.hasRotation() && replies.rotationTokens() > 0;
    }

    private void judge(PlayerSession session, State state, TimelineEvent.Inbound at, List<Rotation> looks) {
        List<Pending> pending = new ArrayList<>(state.pending);
        state.pending.clear();
        if (looks == null) {
            return; // the rotation is not known
        }
        // the head's yaw: yRotLast before this tick's own packet
        List<Rotation> heads = candidates(state);
        ClientWorld world = replicas.world().world(session);
        for (Pending p : pending) {
            Wall wall = null;
            if (meets(p.picked(), looks, looks) || p.ranged() != null && (heads == null || meets(p.ranged(), looks, heads))) {
                // the look meets it: through blocks? (the world as the client may have had it at the attack)
                if (p.ranged() != null || p.acknowledgements() != state.acknowledgements || (wall = throughBlocks(world, p, looks)) == null) {
                    continue;
                }
            }
            String moved = AttackReachCheck.mayHaveMoved(replicas.self().self(session), p.own(), replicas.entities().entities(session), world);
            if (moved != null) {
                continue;
            }
            if (wall == null) {
                state.offAimSinceReport++;
            } else {
                state.throughBlocksSinceReport++;
            }
            report(session, state, p, looks, wall, at);
        }
    }

    /**
     * The block every look of {@code looks} certainly stops at before it could meet the target, from every eye where it
     * meets one of the target's boxes; null if some look may reach the target first.
     */
    private static Wall throughBlocks(ClientWorld world, Pending p, List<Rotation> looks) {
        Wall weakest = null;
        int poses = p.eyes().size();
        for (Rotation look : looks) {
            double[] v = AttackAim.view(look.xRot(), look.yRot());
            for (int i = 0; i < p.picked().size(); i++) {
                double target = AttackAim.enter(v, p.picked().get(i));
                if (target == Double.POSITIVE_INFINITY) {
                    continue; // from this pose, at this box, the look misses it
                }
                AttackReach.Eye e = p.eyes().get(i % poses);
                double r = e.radius() + AttackAim.EPSILON;
                double block = world.certainOutlineHit(e.x() - r, e.y0() - r, e.z() - r, e.x() + r, e.y1() + r, e.z() + r, v[0], v[1], v[2],
                        target);
                if (!(block < target)) {
                    return null;
                }
                if (weakest == null || target - block < weakest.gap()) {
                    double y = (e.y0() + e.y1()) / 2.0;
                    weakest = new Wall(target - block, (int) Math.floor(e.x() + v[0] * block), (int) Math.floor(y + v[1] * block),
                            (int) Math.floor(e.z() + v[2] * block));
                }
            }
        }
        return weakest;
    }

    /** Whether some look, with the pitch of one of {@code pitches} and the yaw of one of {@code yaws}, meets some box. */
    private static boolean meets(List<AttackReach.Box> boxes, List<Rotation> pitches, List<Rotation> yaws) {
        for (Rotation pitch : pitches) {
            for (Rotation yaw : yaws) {
                double[] v = AttackAim.view(pitch.xRot(), yaw.yRot());
                for (AttackReach.Box d : boxes) {
                    if (AttackAim.meets(v, d)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void report(PlayerSession session, State state, Pending p, List<Rotation> looks, Wall wall, TimelineEvent.Inbound at) {
        TimelineEvent.Inbound attack = p.attack();
        if (state.lastReportNanos != Long.MIN_VALUE && attack.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        long from = p.eyeSequence();
        long fromNanos = p.eyeNanos();
        double miss = Double.POSITIVE_INFINITY;
        for (Rotation look : looks) {
            if (look.sequence() < from) {
                from = look.sequence();
                fromNanos = look.nanoTime();
            }
            double[] v = AttackAim.view(look.xRot(), look.yRot());
            for (AttackReach.Box d : p.picked()) {
                miss = Math.min(miss, AttackAim.missBy(v, d, REPORT_LENGTH));
            }
        }
        state.lastReportNanos = attack.nanoTime();
        Admissibility admissibility = state.admissibility.evaluate(from);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        List<String> rotations = new ArrayList<>();
        for (Rotation look : looks) {
            rotations.add(format(look.yRot()) + "/" + format(look.xRot()));
        }
        Map<String, String> details = new TreeMap<>();
        details.put("target", Integer.toString(p.target()));
        details.put("targetType", p.type());
        details.put("looks", String.join(",", rotations));
        details.put("eye", format(p.eye().x()) + "," + format(p.eye().y0()) + ".." + format(p.eye().y1()) + "," + format(p.eye().z()));
        details.put("targetStates", Integer.toString(p.states()));
        details.put("offAimAttacks", Long.toString(state.offAimSinceReport));
        details.put("throughBlocksAttacks", Long.toString(state.throughBlocksSinceReport));
        state.offAimSinceReport = 0;
        state.throughBlocksSinceReport = 0;
        String summary;
        if (wall == null) {
            details.put("reason", "look");
            details.put("missBy", format(miss));
            summary = "attacked " + p.type() + " " + p.target() + " with a look passing at least " + format(miss) + " blocks from it";
        } else {
            details.put("reason", "blocks");
            details.put("wall", wall.x() + "," + wall.y() + "," + wall.z());
            details.put("stoppedBefore", format(wall.gap()));
            summary = "attacked " + p.type() + " " + p.target() + " through blocks: its look stops at the block " + wall.x() + "," + wall.y() + ","
                    + wall.z() + ", at least " + format(wall.gap()) + " blocks before it";
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, from, at.sequence(), fromNanos, at.nanoTime(), summary,
                details, admissibility));
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
