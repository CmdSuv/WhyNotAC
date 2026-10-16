package dev.whynotac.analysis.movement;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.OnFoot;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientWorld;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client's own player on foot, tick by tick, as the set of states a vanilla 1.21.11 client may be in
 * ({@link Hypothesis}), each run through {@link OnFoot} and kept only if it sends exactly what the client sent.
 *
 * <h2>Ticks</h2>
 * Each {@code ClientTickEnd} closes one client tick ({@code Minecraft.tick}). The client handles what it
 * was sent only between ticks ({@code Minecraft.runTick}: {@code processQueuedPackets}, then the ticks), and
 * answers a transaction when it handles it: so a packet sent before a transaction answered before the
 * tick's {@code ClientTickEnd} was handled before the tick, one sent after the first transaction not yet
 * answered was not, and one in between may or may not have been (every prefix is a hypothesis). Within a
 * tick ({@code LocalPlayer.tick}): the input packet if the keys changed, the sprint command if sprinting
 * changed, then {@code sendPosition}'s packet: position when it moved more than {@code 2.0E-4} or every 20
 * ticks, rotation when it turned, the flags alone when only they changed, nothing otherwise. A teleport's
 * reply (the first movement packet after its accept) is not part of the tick.
 *
 * <h2>Claims</h2>
 * A tick is explained when some hypothesis sends exactly what the client sent: the same packets, the
 * position to the bit, the same flags. When none does, no vanilla client could have sent it
 * ({@link Outcome#UNEXPLAINED}). Where any hypothesis leaves the on-foot step's scope, or something the
 * engine does not model may act on the player (an entity that pushes, an unsettled block, an item in use, a
 * vehicle, effects, a packet the client may have handled in a way not followed), there is no claim and the
 * set is dropped until an anchor fixes the state again:
 * <ul>
 *   <li>a new player (login, or a respawn that keeps no entity data), whose state is the client's defaults;</li>
 *   <li>a player standing still: no key for {@link #SETTLE_KEYLESS_TICKS} ticks and the same position, to the
 *       bit, in two position packets at least {@link #SETTLE_MIN_TICKS} ticks apart with nothing sent in
 *       between and on the ground throughout. Its horizontal motion is then zero (anything faster than the
 *       client's zeroing threshold would have moved it beyond the send threshold) and its vertical motion is
 *       one tick of gravity; the rest follows from the world, checked by running the step once more.</li>
 * </ul>
 */
public final class MovementTracker {

    /** What one client tick came to. */
    public enum Outcome {
        /** Some hypothesis sends exactly what the client sent. */
        EXPLAINED,
        /** No hypothesis does: no vanilla client sends this. */
        UNEXPLAINED,
        /** The tick is outside what the engine can claim ({@link Tick#reason}). */
        NO_CLAIM,
        /** No hypotheses: waiting for an anchor. */
        UNTRACKED
    }

    /**
     * One client tick.
     *
     * @param number    client ticks counted before it
     * @param sequence  the timeline sequence of its {@code ClientTickEnd}
     * @param since     where the current tracking began (the anchor's sequence)
     * @param closest   for an unexplained tick: the hypothesis' prediction closest to what was sent, else null
     * @param sent      for an unexplained tick: the movement packet, or null when none was sent
     */
    public record Tick(long number, long sequence, long nanoTime, Outcome outcome, String reason, int hypotheses, long since, PlayerState closest,
            ServerboundPacket.MovePlayer sent) {}

    static final int MAX_HYPOTHESES = 64;
    static final int MAX_PENDING = 4096;
    static final int MAX_PINGS = 4096;
    static final int SETTLE_KEYLESS_TICKS = 12;
    static final int SETTLE_MIN_TICKS = 3;
    /** Entities whose position the client simulates count as near within this of the server's position for them. */
    static final double UNKNOWN_ENTITY_RADIUS = 64.0;
    /**
     * Blocks around where the player may be searched for pistons and shulker boxes: they move what meets their
     * moving block or lid, one block.
     */
    static final int MOVER_MARGIN = 1;
    /** Entities pushing or blocking the player: their boxes searched this much beyond where the player may be. */
    static final double ENTITY_MARGIN = 1.5;
    /** The world border collides within a few blocks ({@code WorldBorder.isInsideCloseToBorder}); this much is kept free. */
    static final double BORDER_MARGIN = 16.0;
    /** {@code sendPosition}'s threshold, squared as {@code Mth.square(2.0E-4)}. */
    static final double SEND_THRESHOLD_SQR = VanillaMath.square(2.0E-4);
    /** One tick of gravity from rest, with drag: {@code (0.0 - 0.08) * 0.98F}. */
    static final double SETTLED_Y_MOTION = (0.0 - 0.08) * 0.98F;

    // --- transactions (client order) ----------------------------------------------------------------------------
    private final ArrayDeque<long[]> pings = new ArrayDeque<>();
    private long certain = Long.MIN_VALUE;

    // --- packets about the player, server order; index = queueBase + position -------------------------------------
    private record Pending(long sequence, ClientboundPacket packet) {}

    private final List<Pending> pending = new ArrayList<>();
    private long queueBase;
    private int selfId = -1;

    // --- the tick being collected ------------------------------------------------------------------------------------
    private final List<TimelineEvent.Inbound> inbound = new ArrayList<>();
    private long ticks;

    // --- tracking ---------------------------------------------------------------------------------------------------
    private List<Hypothesis> hypotheses;
    private long since = Long.MIN_VALUE;
    /** While untracked: the player's state as far as the server knows it for certain, and what it last sent. */
    private SelfState base = SelfState.INITIAL;
    private Hypothesis.Sent baseSent = Hypothesis.Sent.fresh(PlayerState.Keys.NONE, false);
    private long baseApplied;
    private String lostReason = "no login yet";
    /** False after lost events: the base may have missed packets, so only a new player anchors again. */
    private boolean baseReliable = true;

    // --- the settle anchor's bookkeeping (from what the client sent) --------------------------------------------------
    private PlayerState.Keys currentKeys = PlayerState.Keys.NONE;
    private int keylessTicks;
    private long lastPositionTick = Long.MIN_VALUE;
    private long lastPositionSequence = Long.MIN_VALUE;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean quietSincePosition;

    public MovementTracker() {}

    /** The current hypotheses (empty while untracked), for audits. */
    public List<Hypothesis> hypotheses() {
        return hypotheses == null ? List.of() : List.copyOf(hypotheses);
    }

    /** Why the player is not tracked, or null while it is. */
    public String lostReason() {
        return hypotheses == null ? lostReason : null;
    }

    /**
     * One event of the session, in order, after the replicas saw it. Returns the client tick a
     * {@code ClientTickEnd} closed, null for other events.
     */
    public Tick onEvent(TimelineEvent event, ClientWorld world, ClientEntities entities) {
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(out.sequence(), out.packet());
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.transactionId());
            case TimelineEvent.Bound bound -> {
                if (bound.entityId() >= 0) {
                    selfId = bound.entityId();
                }
            }
            case TimelineEvent.Gap gap -> {
                lose("events were lost");
                baseReliable = false; // until a new player, what the server knows for certain may be wrong
                pings.clear();
                inbound.clear();
            }
            case TimelineEvent.Disconnected ignored -> pings.clear();
            case TimelineEvent.Inbound in -> {
                if (in.packet() instanceof ServerboundPacket.ClientTickEnd) {
                    return closeTick(in, world, entities);
                }
                inbound.add(in);
            }
            default -> { }
        }
        return null;
    }

    // --- outbound ------------------------------------------------------------------------------------------------

    private void onOutbound(long seq, ClientboundPacket packet) {
        if (packet instanceof ClientboundPacket.Ping ping) {
            pings.add(new long[] {ping.id(), seq});
            if (pings.size() > MAX_PINGS) {
                pings.pollFirst(); // a later window end: wider, still sound
            }
            return;
        }
        if (packet instanceof ClientboundPacket.Login login) {
            selfId = login.entityId();
        }
        if (!aboutSelf(packet)) {
            return;
        }
        pending.add(new Pending(seq, packet));
        if (pending.size() > MAX_PENDING) {
            // the client stopped answering: forget the oldest (they are applied to nothing any more)
            lose("too many packets waiting for the client");
            baseReliable = false;
            pending.remove(0);
            queueBase++;
            baseApplied = Math.max(baseApplied, queueBase);
        }
    }

    /** Packets the client applies to its own player (or that tell whether it can move on foot). */
    private boolean aboutSelf(ClientboundPacket packet) {
        return switch (packet) {
            case ClientboundPacket.Login ignored -> true;
            case ClientboundPacket.Respawn ignored -> true;
            case ClientboundPacket.PlayerPosition ignored -> true;
            case ClientboundPacket.PlayerRotation ignored -> true;
            case ClientboundPacket.SetEntityMotion m -> m.entityId() == selfId;
            case ClientboundPacket.Explosion e -> e.playerKnockback() != null;
            case ClientboundPacket.UpdateAttributes a -> a.entityId() == selfId;
            case ClientboundPacket.MobEffect e -> e.entityId() == selfId;
            case ClientboundPacket.RemoveMobEffect e -> e.entityId() == selfId;
            case ClientboundPacket.PlayerAbilities ignored -> true;
            case ClientboundPacket.GameEvent ignored -> true;
            case ClientboundPacket.SetHealth ignored -> true;
            case ClientboundPacket.SetEntityData d -> d.entityId() == selfId;
            case ClientboundPacket.SetPassengers ignored -> true;
            case ClientboundPacket.RemoveEntities ignored -> true;
            case ClientboundPacket.SetCamera ignored -> true;
            case ClientboundPacket.InitializeBorder ignored -> true;
            case ClientboundPacket.SetBorderCenter ignored -> true;
            case ClientboundPacket.SetBorderLerpSize ignored -> true;
            case ClientboundPacket.SetBorderSize ignored -> true;
            case ClientboundPacket.TeleportEntity t -> t.entityId() == selfId;
            case ClientboundPacket.EntityPositionSync s -> s.entityId() == selfId;
            case ClientboundPacket.MoveEntity m -> m.entityId() == selfId;
            case ClientboundPacket.EntityEvent e -> e.entityId() == selfId;
            case ClientboundPacket.MoveVehicle ignored -> true;
            case ClientboundPacket.Untranslatable ignored -> true;
            default -> false;
        };
    }

    private void onAcknowledged(int id) {
        long seq = Long.MIN_VALUE;
        for (long[] ping : pings) {
            if (ping[0] == id) {
                seq = ping[1];
                break;
            }
        }
        if (seq == Long.MIN_VALUE) {
            return;
        }
        while (!pings.isEmpty() && pings.peekFirst()[1] <= seq) {
            pings.pollFirst();
        }
        certain = Math.max(certain, seq);
    }

    /** Queue indices: packets before {@code must} were handled before this tick, those from {@code may} on were not. */
    private long[] window() {
        long windowEnd = pings.isEmpty() ? Long.MAX_VALUE : pings.peekFirst()[1];
        long must = queueBase;
        long may = queueBase;
        for (Pending p : pending) {
            if (p.sequence < certain) {
                must++;
            }
            if (p.sequence < windowEnd) {
                may++;
            } else {
                break;
            }
        }
        return new long[] {must, may};
    }

    private Pending pendingAt(long index) {
        return pending.get((int) (index - queueBase));
    }

    /** Drops the queued packets every state has processed. */
    private void prune() {
        long applied = baseApplied;
        if (hypotheses != null) {
            for (Hypothesis h : hypotheses) {
                applied = Math.min(applied, h.applied());
            }
        }
        int drop = (int) Math.min(pending.size(), Math.max(0, applied - queueBase));
        if (drop > 0) {
            pending.subList(0, drop).clear();
            queueBase += drop;
        }
    }

    // --- one tick ------------------------------------------------------------------------------------------------

    /** Losing all hypotheses: the reason a tick cannot be claimed. */
    static final class Lose extends Exception {
        Lose(String reason) {
            super(reason, null, false, false);
        }
    }

    /** What the client sent in one tick (and the gap before it), sorted out. */
    private static final class TickInput {
        ServerboundPacket.PlayerInput input;
        int inputs;
        ServerboundPacket.PlayerCommand sprint;
        int sprints;
        ServerboundPacket.MovePlayer move;
        int moves;
        int attacks;
        boolean loaded;
        String unmodelled;
    }

    private TickInput sortOut() {
        TickInput t = new TickInput();
        boolean replyDue = false;
        for (TimelineEvent.Inbound in : inbound) {
            switch (in.packet()) {
                case ServerboundPacket.AcceptTeleportation ignored -> replyDue = true;
                case ServerboundPacket.MovePlayer move -> {
                    if (replyDue) {
                        replyDue = false; // handleMovePlayer's reply, sent right after the accept
                    } else {
                        t.move = move;
                        t.moves++;
                    }
                }
                case ServerboundPacket.PlayerInput input -> {
                    t.input = input;
                    t.inputs++;
                }
                case ServerboundPacket.PlayerCommand command -> {
                    switch (command.action()) {
                        case START_SPRINTING, STOP_SPRINTING -> {
                            t.sprint = command;
                            t.sprints++;
                        }
                        case OPEN_INVENTORY -> { }
                        default -> t.unmodelled = "player command " + command.action();
                    }
                }
                case ServerboundPacket.InteractEntity interact -> {
                    if (interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK) {
                        t.attacks++;
                    }
                }
                case ServerboundPacket.PlayerLoaded ignored -> t.loaded = true;
                case ServerboundPacket.UseItem ignored -> t.unmodelled = "item use";
                case ServerboundPacket.UseItemOn ignored -> t.unmodelled = "item use on a block";
                case ServerboundPacket.PlayerAction action -> {
                    if (action.action() == ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM
                            || action.action() == ServerboundPacket.PlayerAction.Action.STAB
                            || action.action() == ServerboundPacket.PlayerAction.Action.UNKNOWN) {
                        t.unmodelled = "player action " + action.action();
                    }
                }
                case ServerboundPacket.PlayerAbilities ignored -> t.unmodelled = "flying toggled";
                case ServerboundPacket.MoveVehicle ignored -> t.unmodelled = "vehicle";
                case ServerboundPacket.PaddleBoat ignored -> t.unmodelled = "vehicle";
                case ServerboundPacket.Untranslatable ignored -> t.unmodelled = "a packet WhyNotAC cannot read";
                default -> { }
            }
        }
        return t;
    }

    private Tick closeTick(TimelineEvent.Inbound end, ClientWorld world, ClientEntities entities) {
        long number = ticks++;
        TickInput in = sortOut();
        inbound.clear();
        long[] w = window();
        Hypothesis fresh = advanceBase(w[0]);
        if (hypotheses == null && fresh != null) {
            hypotheses = List.of(fresh); // a new player: the client's defaults, from the packet that made it
            since = pendingAt(fresh.applied() - 1).sequence();
        }
        observeBase(in); // the anchor below starts from what the client sent in this very tick
        Tick result;
        if (hypotheses != null) {
            result = tracked(number, end, in, w, world, entities);
        } else {
            result = untracked(number, end, in, w, world, entities);
        }
        settleBookkeeping(number, end, in);
        prune();
        return result;
    }

    private Tick tracked(long number, TimelineEvent.Inbound end, TickInput in, long[] w, ClientWorld world, ClientEntities entities) {
        Set<Hypothesis> next = new LinkedHashSet<>();
        List<PlayerState> predictions = new ArrayList<>();
        try {
            View view = new View(world, entities, selfId);
            List<Hypothesis> starts = new ArrayList<>();
            for (Hypothesis h : hypotheses) {
                for (long k = Math.max(h.applied(), w[0]); k <= Math.max(h.applied(), w[1]); k++) {
                    starts.add(applyPending(h, k));
                }
            }
            view.checkSurroundings(starts);
            Map<StepKey, OnFoot.Step> steps = new HashMap<>();
            for (Hypothesis a : starts) {
                simulate(a, in, view, next, steps, predictions);
                if (next.size() > MAX_HYPOTHESES) {
                    throw new Lose("more than " + MAX_HYPOTHESES + " hypotheses");
                }
            }
        } catch (Lose e) {
            lose(e.getMessage());
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.NO_CLAIM, e.getMessage(), 0, since, null, null);
        }
        if (next.isEmpty()) {
            long from = since;
            lose("unexplained movement");
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNEXPLAINED, "no vanilla client sends this", 0, from,
                    closest(predictions, in.move), in.move);
        }
        hypotheses = merged(next);
        return new Tick(number, end.sequence(), end.nanoTime(), Outcome.EXPLAINED, null, hypotheses.size(), since, null, null);
    }

    /** The prediction nearest to the position sent (the first one when none was sent). */
    private static PlayerState closest(List<PlayerState> predictions, ServerboundPacket.MovePlayer move) {
        PlayerState best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (PlayerState p : predictions) {
            double d = move != null && move.hasPosition() ? VanillaMath.lengthSquared(p.x() - move.x(), p.y() - move.y(), p.z() - move.z()) : 0.0;
            if (best == null || d < distance) {
                best = p;
                distance = d;
            }
        }
        return best;
    }

    /**
     * States equal but for the options' masks become one with the masks' union: the options are the client's
     * own and fixed, so the union allows at least every combination either allowed.
     */
    private static List<Hypothesis> merged(Set<Hypothesis> states) {
        Map<Hypothesis, int[]> masks = new LinkedHashMap<>();
        for (Hypothesis h : states) {
            int[] m = masks.computeIfAbsent(new Hypothesis(h.player(), h.self(), h.sent(), 0, 0, h.applied()), k -> new int[2]);
            m[0] |= h.windowMask();
            m[1] |= h.autoJumpMask();
        }
        List<Hypothesis> out = new ArrayList<>(masks.size());
        for (Map.Entry<Hypothesis, int[]> e : masks.entrySet()) {
            Hypothesis k = e.getKey();
            out.add(new Hypothesis(k.player(), k.self(), k.sent(), e.getValue()[0], e.getValue()[1], k.applied()));
        }
        return List.copyOf(out);
    }

    /** A state a tick may start from, with the speed attribute it holds then. */
    private record Start(PlayerState player, SpeedAttribute speed) {}

    /** What one on-foot step depends on, the double-tap counter aside (see {@link #simulate}). */
    private record StepKey(PlayerState start, PlayerState.Keys keys, boolean food, boolean autoJump, SpeedAttribute speed, int window) {}

    private void lose(String reason) {
        hypotheses = null; // the base goes on from what the server knows for certain
        lostReason = reason;
        quietSincePosition = false;
    }

    // --- applying what the client was sent -------------------------------------------------------------------------

    /** {@code h} after the client processed the queued packets before index {@code upTo}. */
    private Hypothesis applyPending(Hypothesis h, long upTo) throws Lose {
        Hypothesis a = h;
        for (long i = h.applied(); i < upTo; i++) {
            a = apply(a, pendingAt(i).packet()).withApplied(i + 1);
        }
        return a;
    }

    private Hypothesis apply(Hypothesis h, ClientboundPacket packet) throws Lose {
        SelfState s = h.self();
        PlayerState p = h.player();
        return switch (packet) {
            case ClientboundPacket.Login login -> freshPlayer(h, SelfState.INITIAL, login.gameMode(), false, false);
            case ClientboundPacket.Respawn respawn -> respawned(h, respawn);
            case ClientboundPacket.PlayerPosition teleport -> {
                if (s.vehicle() != -1) {
                    yield h; // handleMovePlayer: not while riding
                }
                yield h.withPlayer(teleported(p, teleport)).withSelf(s.withPositionKnown());
            }
            case ClientboundPacket.PlayerRotation ignored -> throw new Lose("rotation set by the server (its reply is not modelled)");
            case ClientboundPacket.SetEntityMotion motion -> h.withPlayer(withDelta(p, motion.clientMotion()));
            case ClientboundPacket.Explosion explosion -> h.withPlayer(withDelta(p, p.deltaMovement().add(explosion.playerKnockback())));
            case ClientboundPacket.UpdateAttributes update -> {
                SelfState next = s.withAttributes(update.attributes());
                PlayerState q = p;
                if (next.speed() != null && next.speed() != s.speed()) {
                    q = withSprintModifier(q, next.speed().holdsSprinting()); // the server's set replaces the client's own
                }
                yield h.withSelf(next).withPlayer(q);
            }
            case ClientboundPacket.MobEffect effect -> h.withSelf(s.withEffect(effect.effectKey(), true));
            case ClientboundPacket.RemoveMobEffect effect -> h.withSelf(s.withEffect(effect.effectKey(), false));
            case ClientboundPacket.PlayerAbilities abilities -> h.withSelf(s.withAbilities(abilities.flying(), abilities.mayFly()));
            case ClientboundPacket.GameEvent event -> gameEvent(h, event);
            case ClientboundPacket.SetHealth health -> h.withSelf(s.withHealth(health.health(), health.food()));
            case ClientboundPacket.SetEntityData data -> entityData(h, data);
            case ClientboundPacket.SetPassengers passengers -> {
                boolean on = false;
                for (int id : passengers.passengerIds()) {
                    on |= id == selfId;
                }
                if (on) {
                    yield h.withSelf(s.withVehicle(passengers.vehicleId()));
                }
                yield passengers.vehicleId() == s.vehicle() ? h.withSelf(s.withVehicle(-1)) : h;
            }
            case ClientboundPacket.RemoveEntities removed -> {
                for (int id : removed.entityIds()) {
                    if (id == s.vehicle()) {
                        yield h.withSelf(s.withVehicle(-1)); // the vehicle is gone: the player is put down where it is
                    }
                    if (id == selfId) {
                        throw new Lose("the player's own entity removed");
                    }
                }
                yield h;
            }
            case ClientboundPacket.SetCamera camera -> h.withSelf(s.withCamera(camera.entityId() == selfId));
            case ClientboundPacket.InitializeBorder b -> h.withSelf(s.withBorder(new SelfState.Border(b.centerX(), b.centerZ(),
                    b.lerpTime() > 0 ? Math.min(b.oldSize(), b.newSize()) : b.newSize(), b.lerpTime() > 0 ? Math.max(b.oldSize(), b.newSize()) : b.newSize())));
            case ClientboundPacket.SetBorderCenter b -> h.withSelf(s.withBorder(new SelfState.Border(b.centerX(), b.centerZ(), s.border().minSize(),
                    s.border().maxSize())));
            case ClientboundPacket.SetBorderLerpSize b -> h.withSelf(s.withBorder(new SelfState.Border(s.border().centerX(), s.border().centerZ(),
                    Math.min(b.oldSize(), b.newSize()), Math.max(b.oldSize(), b.newSize()))));
            case ClientboundPacket.SetBorderSize b -> h.withSelf(s.withBorder(new SelfState.Border(s.border().centerX(), s.border().centerZ(), b.size(),
                    b.size())));
            case ClientboundPacket.EntityEvent event -> {
                int e = event.event();
                if (e >= 22 && e <= 28) {
                    yield h; // reduced debug info, permission levels
                }
                throw new Lose("entity event " + e + " on the player");
            }
            case ClientboundPacket.TeleportEntity ignored -> throw new Lose("the player's entity moved by the server");
            case ClientboundPacket.EntityPositionSync ignored -> throw new Lose("the player's entity moved by the server");
            case ClientboundPacket.MoveEntity ignored -> throw new Lose("the player's entity moved by the server");
            case ClientboundPacket.Untranslatable u -> throw new Lose("a packet WhyNotAC cannot read: " + u.type());
            default -> h;
        };
    }

    private Hypothesis gameEvent(Hypothesis h, ClientboundPacket.GameEvent event) throws Lose {
        int type = event.type();
        if (type == ClientboundPacket.GameEvent.CHANGE_GAME_MODE) {
            int mode = (int) event.param();
            PlayerState p = h.player();
            if (mode == 3) { // LocalPlayer.onGameModeChanged: a spectator stops falling
                p = withDelta(p, new Vec3d(p.deltaMovement().x(), 0.0, p.deltaMovement().z()));
            }
            return h.withSelf(h.self().withGameMode(mode)).withPlayer(p);
        }
        // weather, sounds, respawn and crafting settings, the level loading signal: nothing that moves the player
        if (type == 0 || type == 1 || type == 2 || type >= 6 && type <= 13) {
            return h;
        }
        throw new Lose("game event " + type);
    }

    /** {@code handleSetEntityData} for the player's own entity: the shared flags, pose and living flags it applies. */
    private Hypothesis entityData(Hypothesis h, ClientboundPacket.SetEntityData data) throws Lose {
        PlayerState p = h.player();
        SelfState s = h.self();
        boolean noGravity = s.noGravity();
        boolean using = s.usingItem();
        boolean frozen = s.frozen();
        for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
            switch (e.index()) {
                case 0 -> { // Entity.DATA_SHARED_FLAGS_ID
                    int flags = (int) e.value();
                    if ((flags & (1 << 4)) != 0 || (flags & (1 << 7)) != 0) {
                        throw new Lose("swimming or gliding flag set by the server");
                    }
                    p = withSprinting(p, (flags & (1 << 3)) != 0); // the flag alone: no modifier change
                }
                case 5 -> noGravity = e.value() != 0; // DATA_NO_GRAVITY
                case 6 -> { // DATA_POSE
                    ClientboundPacket.EntityPose pose = ClientboundPacket.EntityPose.values()[(int) Math.min(e.value(),
                            ClientboundPacket.EntityPose.UNKNOWN.ordinal())];
                    p = switch (pose) {
                        case STANDING -> withPose(p, PlayerState.Pose.STANDING);
                        case CROUCHING -> withPose(p, PlayerState.Pose.CROUCHING);
                        case SWIMMING -> withPose(p, PlayerState.Pose.SWIMMING);
                        default -> throw new Lose("pose " + pose + " set by the server");
                    };
                }
                case 7 -> frozen = e.value() != 0; // DATA_TICKS_FROZEN
                case 8 -> using = (e.value() & 1) != 0; // LivingEntity.DATA_LIVING_ENTITY_FLAGS: LocalPlayer starts or stops using
                default -> { }
            }
        }
        return h.withPlayer(p).withSelf(s.withFlags(noGravity, using, frozen));
    }

    /** A new {@code LocalPlayer} (login or respawn): the client's defaults, its rotation {@code -180, 0}, not ticking until loaded. */
    private Hypothesis freshPlayer(Hypothesis h, SelfState s, int gameMode, boolean keepAttributes, boolean keepData) throws Lose {
        PlayerState old = h.player();
        if (keepData && old == null) {
            throw new Lose("a respawn keeps the state of an untracked player");
        }
        SpeedAttribute speed = s.speed() == null ? null : keepAttributes ? s.speed().copied() : s.speed().baseOnly();
        SelfState self = SelfState.fresh(gameMode < 0 ? s.gameMode() : gameMode, speed,
                keepAttributes ? s.otherAttributes() : Set.of(), s.border()).withGameMode(gameMode < 0 ? s.gameMode() : gameMode);
        Vec3d delta = keepData ? old.deltaMovement() : Vec3d.ZERO;
        float yRot = keepData ? old.yRot() : -180.0F;
        float xRot = keepData ? old.xRot() : 0.0F;
        boolean sprinting = keepData && old.sprinting(); // the entity data, with its flags
        boolean sprintModifier = speed != null && speed.holdsSprinting();
        PlayerState.Pose pose = keepData ? old.pose() : PlayerState.Pose.STANDING;
        PlayerState player = new PlayerState(0.0, 0.0, 0.0, delta, yRot, xRot, false, false, false, false, false, 0.0, sprinting, sprintModifier, false,
                pose, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO);
        Hypothesis.Sent sent = keepData ? Hypothesis.Sent.fresh(h.sent().input(), old.sprinting()) : Hypothesis.Sent.fresh(PlayerState.Keys.NONE, false);
        return new Hypothesis(player, self, sent, h.windowMask(), h.autoJumpMask(), h.applied());
    }

    private Hypothesis respawned(Hypothesis h, ClientboundPacket.Respawn respawn) throws Lose {
        int keep = respawn.dataToKeep();
        if (keep < 0) {
            throw new Lose("a respawn from an old recording (what it keeps is not known)");
        }
        boolean keepAttributes = (keep & ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS) != 0;
        boolean keepData = (keep & ClientboundPacket.Respawn.KEEP_ENTITY_DATA) != 0;
        return freshPlayer(h, h.self(), respawn.gameMode(), keepAttributes, keepData);
    }

    /** {@code setValuesFromPositionPacket} ({@code PositionMoveRotation.calculateAbsolute}): position and motion. */
    private static PlayerState teleported(PlayerState p, ClientboundPacket.PlayerPosition t) throws Lose {
        Set<RelativeFlag> r = t.relatives();
        if (r.contains(RelativeFlag.ROTATE_DELTA) || r.contains(RelativeFlag.UNKNOWN)) {
            throw new Lose("a teleport that turns the motion with the rotation the client has between ticks");
        }
        double x = (r.contains(RelativeFlag.X) ? p.x() : 0.0) + t.position().x();
        double y = (r.contains(RelativeFlag.Y) ? p.y() : 0.0) + t.position().y();
        double z = (r.contains(RelativeFlag.Z) ? p.z() : 0.0) + t.position().z();
        Vec3d d = p.deltaMovement();
        Vec3d delta = new Vec3d(r.contains(RelativeFlag.DELTA_X) ? d.x() + t.deltaMovement().x() : t.deltaMovement().x(),
                r.contains(RelativeFlag.DELTA_Y) ? d.y() + t.deltaMovement().y() : t.deltaMovement().y(),
                r.contains(RelativeFlag.DELTA_Z) ? d.z() + t.deltaMovement().z() : t.deltaMovement().z());
        if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
            throw new Lose("a teleport to a non-finite position");
        }
        PlayerState moved = new PlayerState(x, y, z, delta.isFinite() ? delta : d, p.yRot(), p.xRot(), p.onGround(), p.horizontalCollision(),
                p.minorHorizontalCollision(), p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), p.sprintModifier(),
                p.crouching(), p.pose(), p.keys(), p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(), p.onGroundNoBlocks(),
                p.stuckSpeedMultiplier());
        return moved;
    }

    // --- running one tick for one hypothesis ----------------------------------------------------------------------

    /**
     * Runs the tick from {@code h} and adds every successor that sends what the client sent; every prediction made
     * goes to {@code predictions}. States that differ only in the double-tap counter share one step unless the tick
     * met a forward press it could start sprinting on (the counter matters nowhere else: it only counts down).
     */
    private void simulate(Hypothesis h, TickInput in, View view, Set<Hypothesis> out, Map<StepKey, OnFoot.Step> steps, List<PlayerState> predictions)
            throws Lose {
        SelfState self = h.self();
        if (in.loaded) {
            if (self.ticking()) {
                return; // a vanilla client says it loaded once per new player
            }
            self = self.withTicking(true);
        }
        if (!self.ticking()) {
            if (in.input == null && in.sprint == null && in.move == null) {
                out.add(h.withSelf(self));
            }
            return;
        }
        String scope = self.outOfScope();
        if (scope != null) {
            throw new Lose(scope);
        }
        if (in.unmodelled != null) {
            throw new Lose(in.unmodelled);
        }
        if (in.inputs > 1 || in.sprints > 1 || in.moves > 1) {
            throw new Lose("several input, sprint or movement packets in one tick");
        }
        Hypothesis.Sent sent = h.sent();
        PlayerState.Keys keys = in.input != null ? keys(in.input) : sent.input();
        if (in.input != null && keys.equals(sent.input())) {
            return; // the client sends its input only when it changed
        }
        float yRot = sent.yRot();
        float xRot = sent.xRot();
        ServerboundPacket.MovePlayer move = in.move;
        if (move != null && move.hasRotation()) {
            yRot = move.yaw();
            xRot = move.pitch();
            if (!Float.isFinite(yRot) || !Float.isFinite(xRot) || yRot == sent.yRot() && xRot == sent.xRot()) {
                return; // sendPosition sends a rotation only when it changed
            }
        }
        PlayerState pre = rotated(h.player(), yRot, xRot);
        List<Start> starts = new ArrayList<>(List.of(new Start(pre, self.speed())));
        for (int i = 0; i < in.attacks; i++) {
            // Player.causeExtraKnockback, when the client's own simulation of the hit knocks back: slowed, not sprinting
            List<Start> more = new ArrayList<>();
            for (Start s : starts) {
                SpeedAttribute speed = s.speed().setSprinting(false);
                if (speed == null) {
                    throw new Lose("more speed modifiers than the client's table holds unresized");
                }
                Start slowed = new Start(withSprinting(withSprintModifier(withDelta(s.player(), s.player().deltaMovement().multiply(0.6, 1.0, 0.6)),
                        false), false), speed);
                if (!starts.contains(slowed) && !more.contains(slowed)) {
                    more.add(slowed);
                }
            }
            starts.addAll(more);
        }
        boolean autoJump = self.firstTick() || (h.autoJumpMask() & Hypothesis.AUTO_JUMP_ON) != 0;
        int window = Integer.numberOfTrailingZeros(h.windowMask());
        for (Start begin : starts) {
            PlayerState start = begin.player();
            StepKey key = new StepKey(withSprintTrigger(start, 0), keys, self.foodAllowsSprint(), autoJump, begin.speed(), window);
            OnFoot.Step step = steps.get(key);
            if (step != null && !step.readSprintTrigger()) {
                // the same step: only this state's own counter counted down (or was cleared by shift or backward)
                int counter = start.keys().shift() || keys.backward() ? 0 : Math.max(start.sprintTriggerTime() - 1, 0);
                step = new OnFoot.Step(withSprintTrigger(step.state(), counter), false, false, step.autoJumpTriggered(), step.speed());
            } else {
                try {
                    step = OnFoot.step(start, keys, view, new OnFoot.Conditions(self.foodAllowsSprint(), window, autoJump, begin.speed()));
                } catch (Colliders.Unknown e) {
                    throw new Lose(e.getMessage());
                }
                steps.putIfAbsent(key, step);
            }
            SelfState after = self.afterFirstTick().withSpeed((SpeedAttribute) step.speed());
            List<Hypothesis> variants = new ArrayList<>();
            int jumpMask = self.firstTick() ? h.autoJumpMask() : step.autoJumpTriggered() ? h.autoJumpMask() & Hypothesis.AUTO_JUMP_ON : h.autoJumpMask();
            PlayerState s = step.state();
            if (step.readSprintWindow()) {
                for (int option = 0; option <= 10; option++) {
                    if ((h.windowMask() & (1 << option)) != 0) {
                        variants.add(new Hypothesis(withSprintTrigger(s, option), self, sent, 1 << option, jumpMask, h.applied()));
                    }
                }
            } else {
                variants.add(new Hypothesis(s, self, sent, h.windowMask(), jumpMask, h.applied()));
            }
            if (step.autoJumpTriggered() && !self.firstTick() && (h.autoJumpMask() & Hypothesis.AUTO_JUMP_OFF) != 0) {
                int count = variants.size();
                for (int i = 0; i < count; i++) {
                    Hypothesis v = variants.get(i);
                    variants.add(new Hypothesis(withAutoJump(v.player(), 0), self, sent, v.windowMask(), Hypothesis.AUTO_JUMP_OFF, h.applied()));
                }
            }
            for (Hypothesis v : variants) {
                predictions.add(v.player());
                Hypothesis.Sent sentAfter = observe(v.player(), sent, keys, in);
                if (sentAfter != null) {
                    out.add(new Hypothesis(v.player(), after, sentAfter, v.windowMask(), v.autoJumpMask(), h.applied()));
                }
            }
        }
    }

    /** What {@code LocalPlayer.tick} sends after the tick ended in {@code s}, against what was sent; the new record, or null if it differs. */
    private static Hypothesis.Sent observe(PlayerState s, Hypothesis.Sent sent, PlayerState.Keys keys, TickInput in) {
        // sendIsSprintingIfNeeded
        if (s.sprinting() != sent.sprinting()) {
            if (in.sprint == null || (in.sprint.action() == ServerboundPacket.PlayerCommand.Action.START_SPRINTING) != s.sprinting()) {
                return null;
            }
        } else if (in.sprint != null) {
            return null;
        }
        // sendPosition
        double dx = s.x() - sent.x();
        double dy = s.y() - sent.y();
        double dz = s.z() - sent.z();
        boolean position = VanillaMath.lengthSquared(dx, dy, dz) > SEND_THRESHOLD_SQR || sent.positionReminder() + 1 >= 20;
        ServerboundPacket.MovePlayer move = in.move;
        boolean rotation = move != null && move.hasRotation();
        boolean flags = s.onGround() != sent.onGround() || s.horizontalCollision() != sent.horizontalCollision();
        ServerboundPacket.MovePlayer.Shape expected = position && rotation ? ServerboundPacket.MovePlayer.Shape.POS_ROT
                : position ? ServerboundPacket.MovePlayer.Shape.POS
                : rotation ? ServerboundPacket.MovePlayer.Shape.ROT
                : flags ? ServerboundPacket.MovePlayer.Shape.STATUS_ONLY : null;
        if (expected != (move == null ? null : move.shape())) {
            return null;
        }
        if (move != null) {
            if (move.onGround() != s.onGround() || move.horizontalCollision() != s.horizontalCollision()) {
                return null;
            }
            if (position && !(move.x() == s.x() && move.y() == s.y() && move.z() == s.z())) {
                return null;
            }
        }
        return new Hypothesis.Sent(position ? s.x() : sent.x(), position ? s.y() : sent.y(), position ? s.z() : sent.z(),
                rotation ? move.yaw() : sent.yRot(), rotation ? move.pitch() : sent.xRot(), s.onGround(), s.horizontalCollision(),
                position ? 0 : sent.positionReminder() + 1, s.sprinting(), keys);
    }

    private static PlayerState.Keys keys(ServerboundPacket.PlayerInput i) {
        return new PlayerState.Keys(i.forward(), i.backward(), i.left(), i.right(), i.jump(), i.shift(), i.sprint());
    }

    // --- untracked: anchors ------------------------------------------------------------------------------------------

    private Tick untracked(long number, TimelineEvent.Inbound end, TickInput in, long[] w, ClientWorld world, ClientEntities entities) {
        Hypothesis settled = baseReliable ? settleAnchor(number, in, w, world, entities) : null;
        if (settled != null) {
            boolean present = settled.self().speed().holdsSprinting();
            SpeedAttribute other = settled.self().speed().setSprinting(!present);
            hypotheses = List.of(settled, settled.withPlayer(withSprintModifier(settled.player(), !present)).withSelf(settled.self().withSpeed(other)));
            since = end.sequence();
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNTRACKED, "anchored: standing still", hypotheses.size(), since, null, null);
        }
        return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNTRACKED, lostReason, 0, since, null, null);
    }

    /**
     * Applies to the base what the client certainly handled before this tick. Returns the state of the last new
     * player those packets made (login, or a respawn keeping no entity data), positioned after its packet, or null.
     */
    private Hypothesis advanceBase(long must) {
        Hypothesis b = new Hypothesis(null, base, baseSent, Hypothesis.ALL_WINDOWS, Hypothesis.AUTO_JUMP_ANY, baseApplied);
        Hypothesis fresh = null;
        for (long i = baseApplied; i < must; i++) {
            ClientboundPacket packet = pendingAt(i).packet();
            try {
                b = applyBase(b, packet).withApplied(i + 1);
            } catch (Lose e) {
                b = b.withApplied(i + 1);
            }
            boolean newPlayer = packet instanceof ClientboundPacket.Login
                    || packet instanceof ClientboundPacket.Respawn r && r.dataToKeep() >= 0 && (r.dataToKeep() & ClientboundPacket.Respawn.KEEP_ENTITY_DATA) == 0;
            if (newPlayer) {
                fresh = b; // as it was right after its packet: the tick applies the rest to it
                baseReliable = true;
                quietSincePosition = false;
            }
        }
        base = b.self();
        baseSent = b.sent();
        baseApplied = Math.max(baseApplied, must);
        return fresh;
    }

    /** What the client sent in this tick, for the base: its record of what it last sent. */
    private void observeBase(TickInput in) {
        if (in.loaded) {
            base = base.withTicking(true);
        }
        if (!base.ticking()) {
            return;
        }
        Hypothesis.Sent s = baseSent;
        PlayerState.Keys keys = in.input != null ? keys(in.input) : s.input();
        boolean sprinting = in.sprint != null ? in.sprint.action() == ServerboundPacket.PlayerCommand.Action.START_SPRINTING : s.sprinting();
        ServerboundPacket.MovePlayer move = in.move;
        boolean position = move != null && move.hasPosition();
        boolean rotation = move != null && move.hasRotation();
        baseSent = new Hypothesis.Sent(position ? move.x() : s.x(), position ? move.y() : s.y(), position ? move.z() : s.z(),
                rotation ? move.yaw() : s.yRot(), rotation ? move.pitch() : s.xRot(), move != null ? move.onGround() : s.onGround(),
                move != null ? move.horizontalCollision() : s.horizontalCollision(), position ? 0 : s.positionReminder() + 1, sprinting, keys);
        base = base.afterFirstTick();
    }

    /** The base's view of a packet: as {@link #apply}, without a physics state (only what does not depend on it). */
    private Hypothesis applyBase(Hypothesis b, ClientboundPacket packet) throws Lose {
        return switch (packet) {
            case ClientboundPacket.Login login -> freshPlayer(b, SelfState.INITIAL, login.gameMode(), false, false);
            case ClientboundPacket.Respawn respawn -> {
                int keep = respawn.dataToKeep();
                if (keep >= 0 && (keep & ClientboundPacket.Respawn.KEEP_ENTITY_DATA) == 0) {
                    yield freshPlayer(b, b.self(), respawn.gameMode(), (keep & ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS) != 0, false);
                }
                quietSincePosition = false;
                yield b.withSelf(b.self().withTicking(false));
            }
            case ClientboundPacket.PlayerPosition ignored -> {
                quietSincePosition = false;
                yield b.withSelf(b.self().withPositionKnown());
            }
            case ClientboundPacket.SetEntityMotion ignored -> quiet(b);
            case ClientboundPacket.Explosion ignored -> quiet(b);
            case ClientboundPacket.PlayerRotation ignored -> quiet(b);
            case ClientboundPacket.UpdateAttributes update -> b.withSelf(b.self().withAttributes(update.attributes()));
            case ClientboundPacket.SetEntityData data -> {
                quietSincePosition = false;
                boolean noGravity = b.self().noGravity();
                boolean using = b.self().usingItem();
                boolean frozen = b.self().frozen();
                for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                    switch (e.index()) {
                        case 5 -> noGravity = e.value() != 0;
                        case 7 -> frozen = e.value() != 0;
                        case 8 -> using = (e.value() & 1) != 0;
                        default -> { }
                    }
                }
                yield b.withSelf(b.self().withFlags(noGravity, using, frozen));
            }
            case ClientboundPacket.GameEvent event -> {
                if (event.type() == ClientboundPacket.GameEvent.CHANGE_GAME_MODE) {
                    quietSincePosition = false;
                    yield b.withSelf(b.self().withGameMode((int) event.param()));
                }
                yield b;
            }
            case ClientboundPacket.TeleportEntity ignored -> quiet(b);
            case ClientboundPacket.EntityPositionSync ignored -> quiet(b);
            case ClientboundPacket.MoveEntity ignored -> quiet(b);
            case ClientboundPacket.EntityEvent ignored -> quiet(b);
            case ClientboundPacket.Untranslatable ignored -> quiet(b);
            default -> apply(b, packet);
        };
    }

    private Hypothesis quiet(Hypothesis b) {
        quietSincePosition = false;
        return b;
    }

    /** The settle anchor (see the class comment) for the tick just sent, or null. */
    private Hypothesis settleAnchor(long number, TickInput in, long[] w, ClientWorld world, ClientEntities entities) {
        ServerboundPacket.MovePlayer move = in.move;
        if (move == null || !move.hasPosition() || !base.ticking() || w[0] != w[1] || in.unmodelled != null || in.attacks > 0 || in.moves > 1) {
            return null;
        }
        PlayerState.Keys now = in.input != null ? keys(in.input) : currentKeys;
        if (lastPositionTick == Long.MIN_VALUE || number - lastPositionTick < SETTLE_MIN_TICKS || !quietSincePosition
                || !now.equals(PlayerState.Keys.NONE) || keylessTicks < SETTLE_KEYLESS_TICKS - 1 || move.x() != lastX || move.y() != lastY || move.z() != lastZ || !move.onGround()
                || move.horizontalCollision() || base.outOfScope() != null) {
            return null;
        }
        View view = new View(world, entities, selfId);
        double x = move.x();
        double y = move.y();
        double z = move.z();
        if (world.mayMoveEntitiesIn((int) Math.floor(x) - MOVER_MARGIN, (int) Math.floor(y) - MOVER_MARGIN, (int) Math.floor(z) - MOVER_MARGIN,
                (int) Math.floor(x) + MOVER_MARGIN, (int) Math.floor(y + 1.8) + MOVER_MARGIN, (int) Math.floor(z) + MOVER_MARGIN, lastPositionSequence)) {
            return null;
        }
        // a guess for the start of the tick; the step recomputes everything the guess leaves open
        if (!base.speed().sprintLayoutCanonical()) {
            return null; // the sprinting modifier's place in the client's table would depend on a history not seen
        }
        PlayerState guess = new PlayerState(x, y, z, new Vec3d(0.0, SETTLED_Y_MOTION, 0.0), baseSent.yRot(), baseSent.xRot(), true, false, false, true,
                true, 0.0, baseSent.sprinting(), base.speed().holdsSprinting(), false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null,
                false, Vec3d.ZERO);
        try {
            Box box = guess.box();
            if (!base.border().farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN)) {
                return null;
            }
            view.check(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
            OnFoot.Conditions conditions = new OnFoot.Conditions(base.foodAllowsSprint(), OnFoot.DEFAULT_SPRINT_WINDOW, false, base.speed());
            PlayerState first = OnFoot.tick(guess, PlayerState.Keys.NONE, view, conditions);
            PlayerState again = OnFoot.tick(first, PlayerState.Keys.NONE, view, conditions);
            if (!first.equals(again) || first.x() != x || first.y() != y || first.z() != z || !first.onGround() || first.horizontalCollision()
                    || first.deltaMovement().x() != 0.0 || first.deltaMovement().z() != 0.0 || first.deltaMovement().y() != SETTLED_Y_MOTION
                    || first.sprinting() != baseSent.sprinting()) {
                return null;
            }
            return new Hypothesis(first, base, baseSent, Hypothesis.ALL_WINDOWS, base.firstTick() ? Hypothesis.AUTO_JUMP_ON : Hypothesis.AUTO_JUMP_ANY,
                    baseApplied);
        } catch (Colliders.Unknown | Lose e) {
            return null;
        }
    }

    private static Hypothesis withSprintModifierHyp(Hypothesis h, boolean present) {
        return h.withPlayer(withSprintModifier(h.player(), present));
    }

    /** The settle anchor's record of the position packets and of the keys, from what the client sent. */
    private void settleBookkeeping(long number, TimelineEvent.Inbound end, TickInput in) {
        if (in.unmodelled != null || in.attacks > 0 || !base.ticking()) {
            quietSincePosition = false;
        }
        if (in.input != null) {
            currentKeys = keys(in.input);
        }
        keylessTicks = currentKeys.equals(PlayerState.Keys.NONE) ? keylessTicks + 1 : 0;
        ServerboundPacket.MovePlayer move = in.move;
        if (move != null) {
            if (move.hasPosition()) {
                lastPositionTick = number;
                lastPositionSequence = end.sequence();
                lastX = move.x();
                lastY = move.y();
                lastZ = move.z();
                quietSincePosition = true;
            }
        }
    }

    // --- the client's world, as the step reads it -----------------------------------------------------------------

    /** The replica's blocks as the client certainly holds them this tick: a block that may still change reads as unknown. */
    private static final class View implements Colliders.World {
        private final ClientWorld world;
        private final ClientEntities entities;
        private final int self;

        View(ClientWorld world, ClientEntities entities, int self) {
            this.world = world;
            this.entities = entities;
            this.self = self;
        }

        @Override
        public BlockStates states() {
            return world.states();
        }

        @Override
        public int blockState(int x, int y, int z) {
            return world.settled(x, y, z) ? world.blockState(x, y, z) : ClientWorld.UNKNOWN;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return world.isLoaded(chunkX, chunkZ) && world.settled(chunkX << 4, world.minY(), chunkZ << 4);
        }

        /** No entity that pushes or collides, no piston, the world border far, around everywhere the player may be this tick. */
        void checkSurroundings(List<Hypothesis> starts) throws Lose {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;
            for (Hypothesis h : starts) {
                if (h.player() == null || !h.self().ticking() || !h.self().positionKnown()) {
                    continue;
                }
                PlayerState p = h.player();
                Vec3d d = p.deltaMovement();
                double reach = Math.abs(d.x()) + Math.abs(d.y()) + Math.abs(d.z());
                Box box = p.box();
                if (!h.self().border().farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN + reach)) {
                    throw new Lose("near the world border");
                }
                minX = Math.min(minX, box.minX() - reach);
                minY = Math.min(minY, box.minY() - reach);
                minZ = Math.min(minZ, box.minZ() - reach);
                maxX = Math.max(maxX, box.maxX() + reach);
                maxY = Math.max(maxY, box.maxY() + reach);
                maxZ = Math.max(maxZ, box.maxZ() + reach);
            }
            if (minX == Double.POSITIVE_INFINITY) {
                return;
            }
            check(minX, minY, minZ, maxX, maxY, maxZ);
        }

        /** {@link #checkSurroundings} for one box that may be swept this tick. */
        void check(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) throws Lose {
            if (entities.mayHavePusherNear(self, minX - ENTITY_MARGIN, minY - ENTITY_MARGIN, minZ - ENTITY_MARGIN, maxX + ENTITY_MARGIN,
                    maxY + ENTITY_MARGIN, maxZ + ENTITY_MARGIN, UNKNOWN_ENTITY_RADIUS)) {
                throw new Lose("an entity that may push the player is near");
            }
            if (world.mayMoveEntitiesIn((int) Math.floor(minX) - MOVER_MARGIN, (int) Math.floor(minY) - MOVER_MARGIN, (int) Math.floor(minZ) - MOVER_MARGIN,
                    (int) Math.floor(maxX) + MOVER_MARGIN, (int) Math.floor(maxY) + MOVER_MARGIN, (int) Math.floor(maxZ) + MOVER_MARGIN,
                    Long.MIN_VALUE + 1)) {
                throw new Lose("a piston or shulker box may move the player");
            }
        }
    }

    // --- PlayerState copies ---------------------------------------------------------------------------------------

    private static PlayerState copy(PlayerState p, Vec3d delta, float yRot, float xRot, boolean sprinting, boolean sprintModifier, PlayerState.Pose pose,
            int sprintTrigger, int autoJump) {
        return new PlayerState(p.x(), p.y(), p.z(), delta, yRot, xRot, p.onGround(), p.horizontalCollision(), p.minorHorizontalCollision(),
                p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), sprinting, sprintModifier, p.crouching(), pose, p.keys(),
                p.noJumpDelay(), sprintTrigger, autoJump, p.support(), p.onGroundNoBlocks(), p.stuckSpeedMultiplier());
    }

    private static PlayerState rotated(PlayerState p, float yRot, float xRot) {
        return copy(p, p.deltaMovement(), yRot, xRot, p.sprinting(), p.sprintModifier(), p.pose(), p.sprintTriggerTime(), p.autoJumpTime());
    }

    /** {@code setDeltaMovement}: ignored unless finite. */
    private static PlayerState withDelta(PlayerState p, Vec3d delta) {
        if (!delta.isFinite()) {
            return p;
        }
        return copy(p, delta, p.yRot(), p.xRot(), p.sprinting(), p.sprintModifier(), p.pose(), p.sprintTriggerTime(), p.autoJumpTime());
    }

    private static PlayerState withSprinting(PlayerState p, boolean sprinting) {
        return copy(p, p.deltaMovement(), p.yRot(), p.xRot(), sprinting, p.sprintModifier(), p.pose(), p.sprintTriggerTime(), p.autoJumpTime());
    }

    private static PlayerState withSprintModifier(PlayerState p, boolean present) {
        return copy(p, p.deltaMovement(), p.yRot(), p.xRot(), p.sprinting(), present, p.pose(), p.sprintTriggerTime(), p.autoJumpTime());
    }

    private static PlayerState withPose(PlayerState p, PlayerState.Pose pose) {
        return copy(p, p.deltaMovement(), p.yRot(), p.xRot(), p.sprinting(), p.sprintModifier(), pose, p.sprintTriggerTime(), p.autoJumpTime());
    }

    private static PlayerState withSprintTrigger(PlayerState p, int ticks) {
        return copy(p, p.deltaMovement(), p.yRot(), p.xRot(), p.sprinting(), p.sprintModifier(), p.pose(), ticks, p.autoJumpTime());
    }

    private static PlayerState withAutoJump(PlayerState p, int ticks) {
        return copy(p, p.deltaMovement(), p.yRot(), p.xRot(), p.sprinting(), p.sprintModifier(), p.pose(), p.sprintTriggerTime(), ticks);
    }
}
