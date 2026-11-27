package dev.whynotac.analysis.movement;

import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.BoatState;
import dev.whynotac.physics.HorseState;
import dev.whynotac.physics.Border;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.OnFoot;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.ClientTeams;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.ItemPrototypes;
import dev.whynotac.world.Shape;
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
 * vehicle, a packet the client may have handled in a way not followed), there is no claim and the
 * set is dropped until an anchor fixes the state again:
 * <ul>
 *   <li>a new player (login, or a respawn that keeps no entity data), whose state is the client's defaults;</li>
 *   <li>a player standing still: no key for {@link #SETTLE_KEYLESS_TICKS} ticks and the same position, to the
 *       bit, in two position packets at least {@link #SETTLE_MIN_TICKS} ticks apart with nothing sent in
 *       between and on the ground throughout. Its horizontal motion is then zero (anything faster than the
 *       client's zeroing threshold would have moved it beyond the send threshold) and its vertical motion is
 *       one tick of gravity (slow falling's, if on; never under levitation); the rest follows from the world,
 *       checked by running the step once more.</li>
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
     * @param closestBoat for an unexplained tick of a boat the player steers: the prediction closest to what was sent
     * @param sentVehicle for a tick of a boat or a horse the player steers: its {@code MoveVehicle}
     * @param closestHorse for an unexplained tick of a horse the player rides: the prediction closest to what was sent
     */
    public record Tick(long number, long sequence, long nanoTime, Outcome outcome, String reason, int hypotheses, long since, PlayerState closest,
            ServerboundPacket.MovePlayer sent, BoatState closestBoat, ServerboundPacket.MoveVehicle sentVehicle, HorseState closestHorse,
            boolean horse) {

        /** A tick on foot (or of nothing). */
        public Tick(long number, long sequence, long nanoTime, Outcome outcome, String reason, int hypotheses, long since, PlayerState closest,
                ServerboundPacket.MovePlayer sent) {
            this(number, sequence, nanoTime, outcome, reason, hypotheses, since, closest, sent, null, null);
        }

        /** A tick on foot or of a boat the player steers. */
        public Tick(long number, long sequence, long nanoTime, Outcome outcome, String reason, int hypotheses, long since, PlayerState closest,
                ServerboundPacket.MovePlayer sent, BoatState closestBoat, ServerboundPacket.MoveVehicle sentVehicle) {
            this(number, sequence, nanoTime, outcome, reason, hypotheses, since, closest, sent, closestBoat, sentVehicle, null, false);
        }

        /** A tick of the boat ({@link BoatRide}) or the horse ({@link HorseRide}) the player steers. */
        public boolean riding() {
            return closestBoat != null || sentVehicle != null || horse;
        }
    }

    /** An unexplained tick's reason when every state it may have started in was using an item while it attacked or used one. */
    public static final String ACTED_WHILE_USING = "attacked, interacted or used an item while already using one";
    /**
     * The start of an unexplained tick's reason when it sent what only a passenger sends while the client rode nothing
     * (the packets follow in brackets): a vehicle move ({@code LocalPlayer.tick}, {@code handleMoveVehicle} and
     * {@code handleTeleportEntity} for its root vehicle), a paddle packet ({@code AbstractBoat.tick} for a boat it
     * controls), a riding jump ({@code sendRidingJump}, for a jumping vehicle it controls; the client never sends its stop)
     * or the vehicle's inventory ({@code sendOpenInventory}, when {@code isServerControlledInventory}).
     */
    public static final String VEHICLE_ON_FOOT = "sent what only a passenger sends while riding nothing";
    /** The reason of a tick that sent what only a vehicle's controller sends while riding one no player controls. */
    public static final String VEHICLE_UNCONTROLLED = "sent what only a vehicle's controller sends while riding one no player controls";
    /** An unexplained tick's reason when it sent its abilities while no state it may have been in could fly. */
    public static final String ABILITIES_WITHOUT_FLIGHT = "sent its flight abilities while it could not fly";
    static final int MAX_HYPOTHESES = 64;
    static final int MAX_PENDING = 4096;
    static final int SETTLE_KEYLESS_TICKS = 12;
    static final int SETTLE_MIN_TICKS = 3;
    /** Maximum value of vanilla 1.21.11 {@code Options.sprintWindow} ({@code OptionInstance.IntRange(0, 10)}). */
    static final int MAX_SPRINT_WINDOW = 10;
    /** The run anchor tries once this many steady ticks are on record, and again every this many ticks while they last. */
    static final int RUN_MIN_TICKS = 20;
    static final int RUN_MAX_TICKS = 80;
    /** After the velocity is known exactly, the ticks the engine must still give the sent positions of, bit for bit. */
    static final int RUN_CONFIRM_TICKS = 3;
    /** Entities whose position the client simulates count as near within this of the server's position for them. */
    static final double UNKNOWN_ENTITY_RADIUS = 64.0;
    /**
     * Blocks around where the player may be searched for pistons and shulker boxes: they move what meets their
     * moving block or lid, one block.
     */
    static final int MOVER_MARGIN = 1;
    /** Entities pushing or blocking the player: their boxes searched this much beyond where the player may be. */
    static final double ENTITY_MARGIN = 1.5;
    /** A world border not known exactly ({@link ClientBorder#known}) collides within a few blocks: this much is kept free of it. */
    static final double BORDER_MARGIN = 16.0;
    /** {@code sendPosition}'s threshold, squared as {@code Mth.square(2.0E-4)}. */
    static final double SEND_THRESHOLD_SQR = VanillaMath.square(2.0E-4);
    /** One tick of gravity from rest, with drag: {@code (0.0 - 0.08) * 0.98F}. */
    static final double SETTLED_Y_MOTION = (0.0 - 0.08) * 0.98F;

    // --- transactions (client order) ----------------------------------------------------------------------------
    private final PingLedger ledger;
    private final boolean sharedLedger;

    // --- the server's rotations and the client's replies, kept out of the ticks -------------------------------------
    private final RotationReplies rotationReplies;
    /** A rotation's reply may have been taken for the tick's own packet or the other way: the last sent rotation and flags are not known. */
    private boolean sentUnsure;
    /** Whether the last tick sent a movement packet of its own (not a reply to a teleport or a rotation). */
    private boolean lastTickMoved;

    // --- packets about the player, server order; index = queueBase + position -------------------------------------
    private record Pending(long sequence, ClientboundPacket packet) {}

    private final List<Pending> pending = new ArrayList<>();
    private long queueBase;
    private int selfId = -1;

    // --- the client's scoreboard teams: the state the client certainly holds, then each later one from its packet's sequence ---
    private static final int MAX_TEAM_CHANGES = 4096;
    /** The player's scoreboard name (its profile name), from the session's binding; null: not known. */
    private String selfName;
    private ClientTeams teamsBase = ClientTeams.NONE;
    /** {sequence, teams after it}, ascending, from {@code ledger.certain()} on. */
    private final ArrayDeque<Object[]> teamChanges = new ArrayDeque<>();

    // --- the tick being collected ------------------------------------------------------------------------------------
    private final List<TimelineEvent.Inbound> inbound = new ArrayList<>();
    private long ticks;
    /**
     * The most ticks after the last one in which the client may still be in a spin attack, for the settle anchor: one
     * starts only at a trident's release ({@code TridentItem.releaseUsing}: {@code startAutoSpinAttack(20)}, before the
     * tick that runs it down first), the client's own or one a use the server completed may make
     * ({@code completeUsingItem} on another stack, wherever the client processed it), and runs down one each tick.
     */
    private int spinMayRun;
    /** The queue index of a completed use the client may not have processed yet (its release may start a spin), or -1. */
    private long spinStartQueued = -1;

    // --- the items in the player's hands (the inventory replica, after the event being handled) ------------------------
    static final String USE_EFFECTS = "minecraft:use_effects";
    private static final ItemPrototypes PROTOTYPES = ItemPrototypes.vanilla();
    private ClientInventory inventory;
    /**
     * The hands' stacks at the last tracked tick's end (null: not known): a use the server's flags start may predate a
     * change. Taken at every tracked tick: the flags may come ticks after the use they echo.
     */
    private Map<Hand, List<HandItem>> lastHands;
    /**
     * The server sent an item cooldown ({@code minecraft:cooldown}, not decoded), or events were lost: a held item may be on
     * cooldown, and {@code MultiPlayerGameMode.performUseItemOn} then uses no item.
     */
    private boolean cooldownMayRun;
    /** Hand slots (bit k: hotbar slot k, bit 9 the off hand) a use on a block not resolved here used since their stack was set. */
    private int unresolvedUseSlots;
    static final String COOLDOWN = "minecraft:cooldown";
    static final String CONSUMABLE = "minecraft:consumable";
    static final String USE_COOLDOWN = "minecraft:use_cooldown";
    /** This tick's {@link #handItems}, by hand. */
    private final Map<Hand, List<HandItem>> handsNow = new java.util.EnumMap<>(Hand.class);
    static final String LEATHER_BOOTS = "minecraft:leather_boots";
    /** What a pig's rider holds to steer it ({@code Pig.getControllingPassenger}), and a strider's ({@code Strider}'s). */
    static final String CARROT_ON_A_STICK = "minecraft:carrot_on_a_stick";
    static final String WARPED_FUNGUS_ON_A_STICK = "minecraft:warped_fungus_on_a_stick";
    /** This tick's feet: leather boots in every state the inventory replica holds, in none, or null (not known). */
    private Boolean leatherBoots;

    // --- tracking ---------------------------------------------------------------------------------------------------
    private List<Hypothesis> hypotheses;
    private List<Hypothesis> sent = List.of();
    private long pushesFollowed;
    private long rocketBoosts;
    private long hypothesisOverflows;
    /** Session-wide narrowed PlatformCos bitmask (reset on Login; pruned once glide cosine distinguishes CPU intrinsic). */
    private int lockedCosineMask = Hypothesis.ALL_COSINES;
    /** True when the most recent tracking loss came from a short bounded action (riptide/trident/item-use) eligible for fastPostActionAnchor. */
    private boolean lostFromShortAction;
    private long since = Long.MIN_VALUE;
    /** While untracked: the player's state as far as the server knows it for certain, and what it last sent. */
    private SelfState base = SelfState.INITIAL;
    private Hypothesis.Sent baseSent = Hypothesis.Sent.fresh(PlayerState.Keys.NONE, false);
    private long baseApplied;
    private String lostReason = "no login yet";
    /** False after lost events: the base may have missed packets, so only a new player anchors again. */
    private boolean baseReliable = true;
    /**
     * The base's gliding flag: the server's shared flags the client certainly processed last ({@link #lastBaseFlags}), or
     * a new player's. The client sets it itself when it starts gliding ({@link #lastGlideStart}): an anchor waits for
     * shared flags the client processed after that.
     */
    private boolean baseMayGlide;
    private long lastBaseFlags = Long.MIN_VALUE;
    private long lastGlideStart = Long.MIN_VALUE;

    // --- the boat the player steers (BoatRide) -----------------------------------------------------------------------
    /** Packets about a ride, server order: what starts or ends one, and the vehicle's own ({@link #serverVehicle}'s). */
    private final ArrayDeque<RidePacket> ridePending = new ArrayDeque<>();
    /** The vehicle the server last put the player on (server order), or -1. */
    private int serverVehicle = -1;
    private BoatRide ride;
    /** Ride ticks: explained, not claimed (by reason), unexplained. */
    private long rideExplained;
    private long rideUnexplained;
    private final Map<String, Long> rideNoClaim = new java.util.TreeMap<>();
    // --- the horse the player rides (HorseRide) ----------------------------------------------------------------------
    private HorseRide horseRide;
    private long horseExplained;
    private long horseUnexplained;
    private final Map<String, Long> horseNoClaim = new java.util.TreeMap<>();
    /** The positions the client sent (replies to teleports too): {client tick, x, y, z}, the last few. */
    private final ArrayDeque<double[]> sentPositions = new ArrayDeque<>();
    private static final int MAX_SENT_POSITIONS = 8;
    /**
     * The vehicles the client may have the player on, from the ride packets it certainly processed: a
     * {@code SetPassengers} listing the player adds its vehicle (the only way a client mounts; it may also ignore one for
     * a vehicle it does not know), one not listing it or the vehicle's removal takes it away, a new player has none.
     * Null when not known: no new player yet, events lost, a packet WhyNotAC cannot read.
     */
    private java.util.Set<Integer> clientVehicles;
    /** Where {@link #clientVehicles} last became empty. */
    private long onNoVehicleSince = Long.MIN_VALUE;
    /** Where the client last put the player on a vehicle ({@link #VEHICLE_UNCONTROLLED}'s anchor). */
    private long mountedSince = Long.MIN_VALUE;
    private long vehicleOnFoot;
    private long vehicleUncontrolled;
    private long ridingUncontrolled;
    private long ridingUnsaddled;
    private long ridingNothing;

    /**
     * A packet about a ride; {@code ambiguous}: at some tick the client may or may not have processed it;
     * {@code mayMount}: processing it may put the player on a vehicle (a {@code SetPassengers} listing it, by its id when
     * the server sent it, or a packet WhyNotAC cannot read).
     */
    private static final class RidePacket {
        final long sequence;
        final ClientboundPacket packet;
        final boolean mayMount;
        boolean ambiguous;

        RidePacket(long sequence, ClientboundPacket packet, boolean mayMount) {
            this.sequence = sequence;
            this.packet = packet;
            this.mayMount = mayMount;
        }
    }

    // --- the settle anchor's bookkeeping (from what the client sent) --------------------------------------------------
    private PlayerState.Keys currentKeys = PlayerState.Keys.NONE;
    private int keylessTicks;
    private long lastPositionTick = Long.MIN_VALUE;
    private long lastPositionSequence = Long.MIN_VALUE;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean quietSincePosition;
    private ClientboundPacket.PlayerPosition dismountTeleport;
    private long dismountTeleportSequence = Long.MIN_VALUE;
    private boolean dismountAccepted;
    private boolean dismountedPendingTeleport;
    private boolean dismountFallClean;
    private boolean dismountTeleportFallClean;
    private int dismountWaitTicks;
    /**
     * Two-tick verified ground-settled teleport anchor (P4): an absolute-position, zero-delta {@code PlayerPosition}
     * accepted on foot in clean standing space (Stage 1: clears old pose/fluids/stuck/support/onGround) followed on the
     * very next tick by a stationary ground landing (Stage 2: resets {@code fallDistance = 0}, proves independence of
     * whether the pre-teleport supporting chunk was loaded, clears {@code noJumpDelay = 0}, and branches
     * {@code sprintModifier}, {@code sprintTriggerTime} and held-item {@code using}).
     */
    private record TpStage1(long tick, long tpSequence, double tx, double ty, double tz, boolean previousShift, Hypothesis.Sent sentAfterTick1) {}

    private ClientboundPacket.PlayerPosition pendingTeleport;
    private long pendingTeleportSeq = Long.MIN_VALUE;
    private boolean pendingTeleportAccepted;
    private TpStage1 tpStage1;
    private boolean tickCertainlyOnFoot;
    private boolean tickCertainlyRiding;
    private float baseOnFootYRot;
    private float baseOnFootXRot;
    private boolean baseOnFootRotKnown = true;
    /**
     * The run anchor's record ({@link #runAnchor}): the positions the client sent at the ends of its last steady ticks
     * (oldest first, at most {@link #RUN_MAX_TICKS} + 1), and the keys, rotation and sprinting they all had.
     */
    private final ArrayDeque<double[]> runPositions = new ArrayDeque<>();
    private PlayerState.Keys runKeys = PlayerState.Keys.NONE;
    private float runYRot;
    private float runXRot;
    private boolean runSprinting;
    private long runLastAttempt = Long.MIN_VALUE;

    public MovementTracker() {
        this(null);
    }

    public MovementTracker(PingLedger sharedLedger) {
        this.sharedLedger = sharedLedger != null;
        this.ledger = sharedLedger != null ? sharedLedger : new PingLedger();
        this.rotationReplies = new RotationReplies(this.ledger);
    }

    /** The current hypotheses (empty while untracked), for audits. */
    public List<Hypothesis> hypotheses() {
        return hypotheses == null ? List.of() : List.copyOf(hypotheses);
    }

    /**
     * The last explained tick's hypotheses as they stood when the client sent its packets, before the entities that
     * tick after the player pushed it ({@link #hypotheses} holds them after): what a client-side record of that moment
     * is one of. Empty after any other tick.
     */
    public List<Hypothesis> sentHypotheses() {
        return sent;
    }

    /** States a push of a living entity changed, since the start (diagnostics). */
    public long pushesFollowed() {
        return pushesFollowed;
    }

    /**
     * Whether the tick last closed sent a movement packet of its own: not the reply to a teleport nor to a rotation
     * the server set (diagnostics: the real client's ticks that sent one, in order).
     */
    public boolean lastTickMoved() {
        return lastTickMoved;
    }

    /** The firework rocket boosts followed so far ({@link #boosted}). */
    public long rocketBoosts() {
        return rocketBoosts;
    }

    /** Ticks of a boat the player steers that some state explained, that none did, and those not claimed by reason (diagnostics). */
    public long rideExplained() {
        return rideExplained;
    }

    /** Ticks that sent what only a passenger sends while the client certainly rode nothing ({@link #VEHICLE_ON_FOOT}). */
    public long vehicleOnFoot() {
        return vehicleOnFoot;
    }

    /** Ticks that sent what only a vehicle's controller sends while riding one no player controls ({@link #VEHICLE_UNCONTROLLED}). */
    public long vehicleUncontrolled() {
        return vehicleUncontrolled;
    }

    /** Ticks over which the client rode only vehicles no player controls: those {@link #VEHICLE_UNCONTROLLED} is claimed over. */
    public long ridingUncontrolled() {
        return ridingUncontrolled;
    }

    /** Those of {@link #ridingUncontrolled} on a vehicle of a type a player controls, without the saddle (or harness) it needs. */
    public long ridingUnsaddled() {
        return ridingUnsaddled;
    }

    /** Ticks over which the client certainly rode nothing: those {@link #VEHICLE_ON_FOOT} is claimed over. */
    public long ridingNothing() {
        return ridingNothing;
    }

    public long rideUnexplained() {
        return rideUnexplained;
    }

    public Map<String, Long> rideNoClaim() {
        return java.util.Collections.unmodifiableMap(rideNoClaim);
    }

    /** Ticks of a horse the player rides that some state explained, that none did, and those not claimed by reason (diagnostics). */
    public long horseExplained() {
        return horseExplained;
    }

    public long horseUnexplained() {
        return horseUnexplained;
    }

    public Map<String, Long> horseNoClaim() {
        return java.util.Collections.unmodifiableMap(horseNoClaim);
    }

    /** The states the horse the player rides may be in as its last tick left them (empty when none is followed), for audits. */
    public List<HorseState> horseStates() {
        return horseRide == null ? List.of() : horseRide.ticked;
    }

    /** The states the boat the player steers may be in now (empty when none is followed), for audits. */
    public List<BoatState> rideStates() {
        return ride == null ? List.of() : ride.states;
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
        return onEvent(event, world, entities, null);
    }

    /**
     * {@link #onEvent(TimelineEvent, ClientWorld, ClientEntities)} with the client's inventory as the replica holds it
     * after the same event (null: not followed, so no claim while an item is used).
     */
    public Tick onEvent(TimelineEvent event, ClientWorld world, ClientEntities entities, ClientInventory inventory) {
        this.inventory = inventory;
        switch (event) {
            case TimelineEvent.Outbound out -> {
                if (out.packet() instanceof ClientboundPacket.Unmodeled u && COOLDOWN.equals(u.type())) {
                    cooldownMayRun = true; // for the session: how long it runs is the client's
                }
                onOutbound(out.sequence(), out.packet());
            }
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.transactionId());
            case TimelineEvent.Bound bound -> {
                if (bound.entityId() >= 0) {
                    selfId = bound.entityId();
                }
                selfName = bound.playerName();
            }
            case TimelineEvent.Gap gap -> {
                lose("events were lost");
                cooldownMayRun = true; // one among them
                unresolvedUseSlots = (1 << ClientInventory.HOTBAR + 1) - 1; // and uses on blocks
                spinMayRun = OnFoot.SPIN_ATTACK_TICKS; // a release among them
                if (ride != null) {
                    ride.unclaim("events were lost");
                }
                ridePending.clear();
                sentPositions.clear();
                clientVehicles = null; // until a new player
                dismountTeleport = null;
                dismountAccepted = false;
                dismountedPendingTeleport = false;
                dismountFallClean = false;
                dismountTeleportFallClean = false;
                dismountWaitTicks = 0;
                clearTeleportAnchor();
                baseOnFootRotKnown = false;
                teamChange(gap.sequence(), ClientTeams.LOST); // until a login
                baseReliable = false; // until a new player, what the server knows for certain may be wrong
                if (!sharedLedger) {
                    ledger.clear();
                }
                inbound.clear();
                rotationReplies.clear();
            }
            case TimelineEvent.Disconnected ignored -> {
                if (!sharedLedger) {
                    ledger.clear();
                }
                rotationReplies.clear();
            }
            case TimelineEvent.Inbound in -> {
                if (in.packet() instanceof ServerboundPacket.ClientTickEnd) {
                    if (rotationReplies.atTickEnd() == RotationReplies.Outcome.AMBIGUOUS) {
                        rotationUnsure("a rotation set by the server whose reply may be the tick's own packet");
                    }
                    return closeTick(in, world, entities);
                }
                if (rotationReplies.onInbound(in.packet())) {
                    return null; // a rotation's reply, not the tick's
                }
                if (in.packet() instanceof ServerboundPacket.MovePlayer move && move.hasPosition()) {
                    sentPositions.add(new double[] {ticks, move.x(), move.y(), move.z()});
                    if (sentPositions.size() > MAX_SENT_POSITIONS) {
                        sentPositions.pollFirst();
                    }
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
            if (!sharedLedger) {
                ledger.onPing(ping.id(), seq);
            }
            rotationReplies.onOutbound(seq, packet);
            return;
        }
        if (rotationReplies.onOutbound(seq, packet) == RotationReplies.Outcome.OVERFLOW) {
            rotationUnsure("too many rotations set by the server waiting for the client's reply");
        }
        if (packet instanceof ClientboundPacket.Login login) {
            selfId = login.entityId();
            teamChange(seq, ClientTeams.NONE); // a new ClientPacketListener, with a new scoreboard
        }
        // Another entity's motion, half of all events with players and mobs around: what the switches below (each
        // testing the types one by one) would decide, nothing (no team, no ride's, not about the player).
        if (packet instanceof ClientboundPacket.RotateHead
                || packet instanceof ClientboundPacket.MoveEntity m && m.entityId() != selfId
                || packet instanceof ClientboundPacket.EntityPositionSync s && s.entityId() != selfId
                || packet instanceof ClientboundPacket.SetEntityMotion v && v.entityId() != selfId && v.entityId() != serverVehicle) {
            return;
        }
        switch (packet) {
            case ClientboundPacket.SetPlayerTeam team -> teamChange(seq, latestTeams().apply(team));
            case ClientboundPacket.Unmodeled u when u.type().equals("minecraft:set_player_team") -> teamChange(seq, ClientTeams.LOST); // an older recording
            case ClientboundPacket.Untranslatable u when u.type().equals("minecraft:set_player_team") -> teamChange(seq, ClientTeams.LOST);
            default -> { }
        }
        ridePacket(seq, packet);
        if (!aboutSelf(packet)) {
            return;
        }
        pending.add(new Pending(seq, packet));
        if (packet instanceof ClientboundPacket.EntityEvent e && e.event() == ClientboundPacket.EntityEvent.FINISH_USING_ITEM) {
            spinStartQueued = queueBase + pending.size() - 1;
        }
        if (pending.size() > MAX_PENDING) {
            // the client stopped answering: forget the oldest (they are applied to nothing any more)
            lose("too many packets waiting for the client");
            baseReliable = false;
            clearTeleportAnchor();
            baseOnFootRotKnown = false;
            pending.remove(0);
            queueBase++;
            baseApplied = Math.max(baseApplied, queueBase);
        }
    }

    /** Queues what a ride reads ({@link #ridePending}), following the vehicle in server order. */
    private void ridePacket(long seq, ClientboundPacket packet) {
        boolean mayMount = packet instanceof ClientboundPacket.Untranslatable;
        boolean queue = switch (packet) {
            case ClientboundPacket.SetPassengers p -> {
                boolean on = false;
                for (int id : p.passengerIds()) {
                    on |= id == selfId;
                }
                if (on) {
                    serverVehicle = p.vehicleId();
                } else if (p.vehicleId() == serverVehicle) {
                    serverVehicle = -1;
                }
                mayMount = on || selfId < 0;
                yield true;
            }
            case ClientboundPacket.Login ignored -> {
                serverVehicle = -1;
                sentPositions.clear();
                yield true;
            }
            case ClientboundPacket.Respawn ignored -> {
                serverVehicle = -1;
                sentPositions.clear();
                yield true;
            }
            case ClientboundPacket.RemoveEntities ignored -> true;
            case ClientboundPacket.PlayerPosition ignored -> true;
            case ClientboundPacket.MoveVehicle ignored -> true;
            case ClientboundPacket.Untranslatable ignored -> true;
            case ClientboundPacket.SetEntityMotion m -> m.entityId() == serverVehicle;
            case ClientboundPacket.SetEntityData d -> d.entityId() == serverVehicle || d.entityId() == selfId; // the rider's sprint too
            case ClientboundPacket.TeleportEntity t -> t.entityId() == serverVehicle;
            case ClientboundPacket.UpdateAttributes a -> a.entityId() == serverVehicle;
            case ClientboundPacket.MobEffect e -> e.entityId() == serverVehicle;
            case ClientboundPacket.RemoveMobEffect e -> e.entityId() == serverVehicle;
            case ClientboundPacket.SetEquipment e -> e.entityId() == serverVehicle;
            default -> false;
        };
        if (!queue) {
            return;
        }
        ridePending.add(new RidePacket(seq, packet, mayMount));
        if (ridePending.size() > MAX_PENDING) {
            ridePending.pollFirst();
            clientVehicles = null; // until a new player
            if (ride != null) {
                ride.unclaim("too many packets waiting for the client");
            }
            if (horseRide != null) {
                horseRide.unclaim("too many packets waiting for the client");
            }
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
            case ClientboundPacket.SetTime ignored -> true; // the game time a ridden camel's pose reads (gameTimeAtLeast)
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
            case ClientboundPacket.Cooldown ignored -> true;
            case ClientboundPacket.Untranslatable ignored -> true;
            default -> false;
        };
    }

    private void onAcknowledged(int id) {
        long seq = sharedLedger ? ledger.lastMatched() : ledger.onAcknowledged(id);
        if (rotationReplies.onAcknowledged(id) == RotationReplies.Outcome.UNANSWERED) {
            rotationUnsure("a rotation set by the server the client did not answer");
        }
        if (seq == Long.MIN_VALUE) {
            return;
        }
        long certain = ledger.certain();
        while (!teamChanges.isEmpty() && (long) teamChanges.peekFirst()[0] < certain) {
            teamsBase = (ClientTeams) teamChanges.pollFirst()[1];
        }
    }

    // --- the server's rotations --------------------------------------------------------------------------------------

    /** No claim, and no anchor until the client sends a rotation: the last rotation and flags it sent are not known. */
    private void rotationUnsure(String reason) {
        lose(reason);
        if (ride != null) {
            ride.unclaim(reason);
        }
        if (horseRide != null) {
            horseRide.unclaim(reason);
        }
        sentUnsure = true;
        clearTeleportAnchor();
        baseOnFootRotKnown = false;
    }

    // --- teams ------------------------------------------------------------------------------------------------------

    private ClientTeams latestTeams() {
        return teamChanges.isEmpty() ? teamsBase : (ClientTeams) teamChanges.peekLast()[1];
    }

    private void teamChange(long seq, ClientTeams after) {
        teamChanges.add(new Object[] {seq, after});
        if (teamChanges.size() > MAX_TEAM_CHANGES) {
            teamChanges.pollFirst();
            teamsBase = ClientTeams.LOST; // the client stopped answering: which of them it holds is not followed
        }
    }

    /**
     * Whether teams let a pusher's push reach the player in this tick ({@link ClientTeams#pushes}), the same in every
     * state the client may hold: the certain one and each one sent before the window's end. Null where they differ or
     * do not know.
     */
    private Boolean teamsLetPush(String pusher) {
        long windowEnd = ledger.firstPending();
        Boolean answer = teamsBase.pushes(pusher, selfName);
        if (answer == null) {
            return null;
        }
        for (Object[] change : teamChanges) {
            if ((long) change[0] >= windowEnd) {
                break;
            }
            if (!answer.equals(((ClientTeams) change[1]).pushes(pusher, selfName))) {
                return null;
            }
        }
        return answer;
    }

    /** Queue indices: packets before {@code must} were handled before this tick, those from {@code may} on were not. */
    private long[] window() {
        long certain = ledger.certain();
        long windowEnd = ledger.firstPending();
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
        /** {@code START_FALL_FLYING}: the tick started gliding ({@code LocalPlayer.aiStep}, {@code tryToStartFallFlying}). */
        int glideStarts;
        ServerboundPacket.MovePlayer move;
        int moves;
        int attacks;
        /** Stabs, interactions with entities: with attacks and uses, what handleKeybinds does not do while an item is in use. */
        int stabs;
        int interactions;
        boolean loaded;
        final List<Integer> acceptedTeleports = new ArrayList<>(1);
        ServerboundPacket.MovePlayer teleportReply;
        /** {@code PlayerAbilities}: only {@code LocalPlayer.aiStep} sends it, for a player that may fly or flies. */
        int abilities;
        /** Riding jumps and vehicle inventories ({@link #VEHICLE_ON_FOOT}). */
        final List<ServerboundPacket.PlayerCommand.Action> passengerCommands = new ArrayList<>(1);
        /** {@code START_RIDING_JUMP}s ({@link HorseRide}), their power. */
        final List<ServerboundPacket.PlayerCommand> ridingJumps = new ArrayList<>(1);
        String unmodelled;
        /** The boat's {@code PaddleBoat} and the passenger's {@code MoveVehicle} ({@link BoatRide}). */
        final List<ServerboundPacket.PaddleBoat> paddles = new ArrayList<>(1);
        final List<ServerboundPacket.MoveVehicle> vehicleMoves = new ArrayList<>(1);
        /** {@code handleKeybinds}' item actions in order: a {@link ServerboundPacket.UseItem} or {@link #RELEASE}. */
        final List<Object> itemActions = new ArrayList<>();
        /** Block-action sequences of {@link ServerboundPacket.UseItem} packets resolved in this tick. */
        final Set<Integer> resolvedItemUseSeqs = new java.util.HashSet<>(2);
        /**
         * The client changed what its hands hold in this tick, or may have: a selected slot sent (the selection changed
         * since the last one), a click in a menu, a creative edit, a drop, an interaction.
         */
        boolean handsTouched;
        /** The uses on a block, in order ({@link #resolveBlockUses}); how many of them, from the first, were resolved. */
        final List<ServerboundPacket.UseItemOn> blockUses = new ArrayList<>(1);
        int resolvedBlockUses;
        /** Something that changes the hands came after a use on a block in this tick: the inventory's states are after it. */
        boolean handsAfterBlockUse;
    }

    /**
     * Entity events that change nothing the step reads on the client's own player (decompiled {@code LocalPlayer},
     * {@code Player}, {@code LivingEntity}, {@code Entity} and {@code ClientPacketListener.handleEntityEvent}): a
     * kinetic hit's feedback (2), the death sound (3), shield sounds (29, 30), the totem (35), teleport particles
     * (46), items breaking (47 to 52, 65, 68), honey particles (53, 54), poof and drown particles (60, 67),
     * reduced debug info and permission levels (22 to 28).
     */
    private static final Set<Integer> NEUTRAL_ENTITY_EVENTS = Set.of(2, 3, 22, 23, 24, 25, 26, 27, 28, 29, 30, 35, 46, 47, 48, 49, 50, 51, 52, 53,
            54, 60, 65, 67, 68);

    /** {@code MultiPlayerGameMode.releaseUsingItem}: {@code PlayerAction RELEASE_USE_ITEM}. */
    private static final Object RELEASE = new Object();

    private TickInput sortOut() {
        TickInput t = new TickInput();
        boolean replyDue = false;
        for (TimelineEvent.Inbound in : inbound) {
            if (!t.blockUses.isEmpty() && changesHands(in.packet())) {
                t.handsAfterBlockUse = true;
            }
            switch (in.packet()) {
                case ServerboundPacket.AcceptTeleportation accept -> {
                    t.acceptedTeleports.add(accept.teleportId());
                    replyDue = true;
                }
                case ServerboundPacket.MovePlayer move -> {
                    if (replyDue) {
                        replyDue = false; // handleMovePlayer's reply, sent right after the accept
                        t.teleportReply = move;
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
                        case START_FALL_FLYING -> {
                            t.glideStarts++;
                            lastGlideStart = in.sequence();
                        }
                        case OPEN_INVENTORY -> t.passengerCommands.add(command.action());
                        case START_RIDING_JUMP, STOP_RIDING_JUMP -> {
                            t.unmodelled = "player command " + command.action();
                            t.passengerCommands.add(command.action());
                            if (command.action() == ServerboundPacket.PlayerCommand.Action.START_RIDING_JUMP) {
                                t.ridingJumps.add(command);
                            } else {
                                t.ridingJumps.add(null); // never sent by the client: no tick explains it
                            }
                        }
                        default -> t.unmodelled = "player command " + command.action();
                    }
                }
                case ServerboundPacket.InteractEntity interact -> {
                    if (interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK) {
                        t.attacks++;
                    } else {
                        t.interactions++;
                        t.handsTouched = true; // an interaction may use up or fill the stack in its hand
                    }
                }
                case ServerboundPacket.PlayerLoaded ignored -> t.loaded = true;
                case ServerboundPacket.UseItem use -> t.itemActions.add(use);
                case ServerboundPacket.UseItemOn use -> {
                    t.blockUses.add(use); // resolved for the world replica, or the claim ends (resolveBlockUses)
                    t.handsTouched = true; // a placement may use up the stack in its hand
                }
                case ServerboundPacket.PlayerAction action -> {
                    switch (action.action()) {
                        case RELEASE_USE_ITEM -> t.itemActions.add(RELEASE);
                        case DROP_ITEM, DROP_ALL_ITEMS -> t.handsTouched = true;
                        case UNKNOWN -> t.unmodelled = "player action " + action.action();
                        case STAB -> t.stabs++; // Player.onAttack only resets the attack strength: nothing on the movement
                        // digging (the world replica follows the client's predictions), drops and swaps (the inventory
                        // replica): nothing on the movement
                        default -> { }
                    }
                }
                case ServerboundPacket.SetCarriedItem ignored -> t.handsTouched = true;
                case ServerboundPacket.ContainerClick ignored -> t.handsTouched = true;
                case ServerboundPacket.SetCreativeModeSlot ignored -> t.handsTouched = true;
                case ServerboundPacket.PlayerAbilities ignored -> t.abilities++;
                case ServerboundPacket.MoveVehicle move -> {
                    t.unmodelled = "vehicle";
                    t.vehicleMoves.add(move);
                }
                case ServerboundPacket.PaddleBoat paddle -> {
                    t.unmodelled = "vehicle";
                    t.paddles.add(paddle);
                }
                case ServerboundPacket.Untranslatable ignored -> t.unmodelled = "a packet WhyNotAC cannot read";
                default -> { }
            }
        }
        return t;
    }

    /** What the inventory replica follows as a change of the hands (ClientInventory.onInbound). */
    private static boolean changesHands(ServerboundPacket packet) {
        return switch (packet) {
            case ServerboundPacket.ContainerClick ignored -> true;
            case ServerboundPacket.SetCarriedItem ignored -> true;
            case ServerboundPacket.SetCreativeModeSlot ignored -> true;
            case ServerboundPacket.UseItem ignored -> true;
            case ServerboundPacket.InteractEntity interact -> interact.kind() != ServerboundPacket.InteractEntity.Kind.ATTACK;
            case ServerboundPacket.PlayerAction action -> action.action() == ServerboundPacket.PlayerAction.Action.DROP_ITEM
                    || action.action() == ServerboundPacket.PlayerAction.Action.DROP_ALL_ITEMS
                    || action.action() == ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM;
            case ServerboundPacket.Unmodeled unmodeled -> "minecraft:select_trade".equals(unmodeled.type());
            case ServerboundPacket.Untranslatable ignored -> true;
            default -> false;
        };
    }

    private Tick closeTick(TimelineEvent.Inbound end, ClientWorld world, ClientEntities entities) {
        long number = ticks++;
        sent = List.of();
        handsNow.clear();
        leatherBoots = inventory == null ? null : inventory.wearsOnFeet(LEATHER_BOOTS);
        TickInput in = sortOut();
        lastTickMoved = in.move != null;
        inbound.clear();
        long[] w = window();
        if (spinStartQueued >= w[0]) {
            spinMayRun = OnFoot.SPIN_ATTACK_TICKS; // the client may process it after this tick
        } else if (spinStartQueued >= 0 || in.itemActions.contains(RELEASE)) {
            spinMayRun = OnFoot.SPIN_ATTACK_TICKS - 1;
            spinStartQueued = -1;
        } else {
            spinMayRun = Math.max(spinMayRun - 1, 0);
        }
        Hypothesis fresh = advanceBase(w[0]);
        if (hypotheses == null && fresh != null) {
            hypotheses = List.of(fresh); // a new player: the client's defaults, from the packet that made it
            since = pendingAt(fresh.applied() - 1).sequence();
        }
        SelfState baseBefore = base;
        Hypothesis.Sent sentBefore = baseSent;
        PlayerState.Keys keysBefore = sentBefore.input(); // the rider's keys in its last tick, for a horse it mounts now
        boolean sprintBefore = sentBefore.sprinting(); // and its sprint (sendIsSprintingIfNeeded: what it is after each tick on foot)
        observeBase(in); // the anchor below starts from what the client sent in this very tick
        Tick riding = rideTick(number, end, in, w, world, entities, keysBefore, sprintBefore);
        if (tickCertainlyOnFoot) {
            if (base.ticking() && !sentUnsure && in.move != null && in.move.hasRotation()) {
                baseOnFootYRot = in.move.yaw();
                baseOnFootXRot = in.move.pitch();
                baseOnFootRotKnown = true;
            }
        } else if (!tickCertainlyRiding && in.move != null && in.move.hasRotation()) {
            baseOnFootRotKnown = false;
        }
        Tick result;
        if (hypotheses != null) {
            clearTeleportAnchor();
            result = tracked(number, end, in, w, world, entities, riding == null);
        } else {
            result = untracked(number, end, in, w, world, entities, baseBefore, sentBefore);
        }
        if (riding != null) {
            result = riding; // the player rides: its own tick has no claim, the boat's or the horse's may
        }
        unresolvedUses(in);
        settleBookkeeping(number, end, in);
        base = base.withBorder(base.border().ticked(entities.lastTickRanNormally()));
        prune();
        return result;
    }

    // --- the boat the player steers ----------------------------------------------------------------------------------

    /**
     * The ride's part of a tick ({@link BoatRide}): the ride packets the client processed before it, in order, then the
     * boat's tick. Null when the player steers no boat.
     */
    private Tick rideTick(long number, TimelineEvent.Inbound end, TickInput in, long[] w, ClientWorld world, ClientEntities entities,
            PlayerState.Keys keysBefore, boolean sprintBefore) {
        long certain = ledger.certain();
        long windowEnd = ledger.firstPending();
        View view = new View(world, entities, selfId);
        // the rider's isSprinting at a mount now: what it sent in its last tick on foot (on a vehicle it may not have sent it)
        mountSprint = clientVehicles != null && clientVehicles.isEmpty() ? sprintBefore : null;
        // the client rode nothing since the last tick when it rode nothing after the packets it certainly processed by
        // then, and none it may have processed since may have put it on a vehicle
        boolean mayHaveRidden = clientVehicles == null || !clientVehicles.isEmpty();
        // every vehicle the client may have had the player on since the last tick (for VEHICLE_UNCONTROLLED)
        java.util.Set<Integer> mayBeOn = clientVehicles == null ? null : new java.util.HashSet<>(clientVehicles);
        while (!ridePending.isEmpty() && ridePending.peekFirst().sequence < certain) {
            RidePacket p = ridePending.pollFirst();
            mayHaveRidden |= p.mayMount;
            if (mayBeOn != null && p.mayMount && p.packet instanceof ClientboundPacket.SetPassengers set) {
                mayBeOn.add(set.vehicleId());
            }
            rideApply(p, number, view, world, entities, keysBefore);
        }
        boolean ambiguous = false;
        boolean ambiguousMayMount = false;
        boolean ambiguousMayDismount = false;
        for (RidePacket p : ridePending) {
            if (p.sequence >= windowEnd) {
                break;
            }
            p.ambiguous = true; // the client may or may not have processed it before this tick
            ambiguous = true;
            mayHaveRidden |= p.mayMount;
            ambiguousMayMount |= p.mayMount;
            if (p.packet instanceof ClientboundPacket.SetPassengers || p.packet instanceof ClientboundPacket.RemoveEntities
                    || p.packet instanceof ClientboundPacket.Login || p.packet instanceof ClientboundPacket.Respawn
                    || p.packet instanceof ClientboundPacket.Untranslatable) {
                ambiguousMayDismount = true;
            }
            if (mayBeOn != null && p.mayMount && p.packet instanceof ClientboundPacket.SetPassengers set) {
                mayBeOn.add(set.vehicleId());
            }
        }
        tickCertainlyOnFoot = clientVehicles != null && clientVehicles.isEmpty() && !ambiguousMayMount;
        tickCertainlyRiding = clientVehicles != null && !clientVehicles.isEmpty() && !ambiguousMayDismount;
        mayHaveRidden |= clientVehicles == null || !clientVehicles.isEmpty();
        if (!mayHaveRidden) {
            ridingNothing++;
        }
        if (!mayHaveRidden && (!in.vehicleMoves.isEmpty() || !in.paddles.isEmpty() || !in.passengerCommands.isEmpty())) {
            vehicleOnFoot++;
            List<String> what = new ArrayList<>();
            if (!in.vehicleMoves.isEmpty()) {
                what.add("MoveVehicle");
            }
            if (!in.paddles.isEmpty()) {
                what.add("PaddleBoat");
            }
            in.passengerCommands.forEach(a -> what.add("PlayerCommand " + a));
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNEXPLAINED, VEHICLE_ON_FOOT + " (" + String.join(", ", what) + ")", 0,
                    onNoVehicleSince, null, null, null, in.vehicleMoves.isEmpty() ? null : in.vehicleMoves.getLast());
        }
        // LocalPlayer.tick sends MoveVehicle only for a root vehicle it controls (isLocalInstanceAuthoritative), the boat its
        // PaddleBoat, the player a riding jump only on a vehicle it controls (jumpableVehicle): a player controls only a
        // vehicle whose first passenger it is, so never one of a type no player controls, nor one without the saddle (or
        // harness) its type needs, nor what that one rides
        if (mayHaveRidden && mayBeOn != null && !mayBeOn.isEmpty()) {
            String type = null;
            boolean unsaddled = false;
            for (int id : mayBeOn) {
                String t = entities.type(id);
                if (entities.mayBePlayerControlled(id)) {
                    type = null;
                    break;
                }
                type = ClientEntities.playerMayControl(t) ? t + " with no " + ClientEntities.controlItem(t) : t;
                unsaddled |= ClientEntities.playerMayControl(t);
            }
            if (type != null) {
                ridingUncontrolled++;
                if (unsaddled) {
                    ridingUnsaddled++;
                }
            }
            if (type != null && (!in.vehicleMoves.isEmpty() || !in.paddles.isEmpty() || !in.ridingJumps.isEmpty())) {
                vehicleUncontrolled++;
                List<String> what = new ArrayList<>();
                if (!in.vehicleMoves.isEmpty()) {
                    what.add("MoveVehicle");
                }
                if (!in.paddles.isEmpty()) {
                    what.add("PaddleBoat");
                }
                if (!in.ridingJumps.isEmpty()) {
                    what.add("a riding jump");
                }
                return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNEXPLAINED, VEHICLE_UNCONTROLLED + " (" + String.join(", ", what)
                        + " on a " + type + ")", 0, mountedSince, null, null, null, in.vehicleMoves.isEmpty() ? null : in.vehicleMoves.getLast());
            }
        }
        if (horseRide != null) {
            return horseTick(number, end, in, w, ambiguous, view, entities);
        }
        if (ride == null) {
            return null;
        }
        if (ambiguous || w[0] != w[1]) {
            ride.unclaim("packets the client may or may not have processed");
        }
        BoatRide.Step step = ride.tick(in.paddles, in.vehicleMoves, baseSent.input(), view, entities, selfId, base.border());
        ServerboundPacket.MoveVehicle sentVehicle = in.vehicleMoves.isEmpty() ? null : in.vehicleMoves.getLast();
        if (step == null) {
            rideNoClaim.merge(ride.unclaimed, 1L, Long::sum);
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.NO_CLAIM, "riding: " + ride.unclaimed, 0, ride.since, null, null, null,
                    sentVehicle);
        }
        if (step.explained()) {
            rideExplained++;
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.EXPLAINED, null, ride.states.size(), ride.since, null, null, null,
                    sentVehicle);
        }
        rideUnexplained++;
        return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNEXPLAINED, step.reason(), 0, ride.since, null, null, step.closest(),
                sentVehicle);
    }

    /** The horse's part of a tick ({@link HorseRide}). */
    /** The rider's {@code isSprinting} for a horse it mounts before this tick (null: not known). */
    private Boolean mountSprint;
    /** The client's game time once it processed the packets certainly processed so far, from below ({@code SetTime}; 0 in a new level). */
    private long baseGameTime;

    /** A lower bound of the client's game time now: the base's, or 0 where it may have entered a new level since. */
    private long gameTimeAtLeast() {
        long[] w = window();
        for (long i = w[0]; i < w[1]; i++) {
            ClientboundPacket packet = pendingAt(i).packet();
            if (packet instanceof ClientboundPacket.Login || packet instanceof ClientboundPacket.Respawn || packet instanceof ClientboundPacket.Untranslatable) {
                return 0L;
            }
        }
        return baseGameTime;
    }

    private Tick horseTick(long number, TimelineEvent.Inbound end, TickInput in, long[] w, boolean ambiguous, View view, ClientEntities entities) {
        if (ambiguous || w[0] != w[1]) {
            horseRide.unclaim("packets the client may or may not have processed");
        }
        if (in.ridingJumps.contains(null)) {
            horseRide.unclaim("a stop of a riding jump, which the client never sends");
        }
        HorseRide.Step step = horseRide.tick(in.vehicleMoves, in.move, in.ridingJumps, baseSent.input(), view, entities, selfId, base.border(),
                horseRide.pig ? holding(CARROT_ON_A_STICK) : horseRide.strider ? holding(WARPED_FUNGUS_ON_A_STICK) : Boolean.TRUE, baseSent.sprinting());
        ServerboundPacket.MoveVehicle sentVehicle = in.vehicleMoves.isEmpty() ? null : in.vehicleMoves.getLast();
        if (step == null) {
            horseNoClaim.merge(horseRide.unclaimed, 1L, Long::sum);
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.NO_CLAIM, "riding: " + horseRide.unclaimed, 0, horseRide.since, null, null,
                    null, sentVehicle, null, true);
        }
        if (step.explained()) {
            horseExplained++;
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.EXPLAINED, null, horseRide.states.size(), horseRide.since, null, null, null,
                    sentVehicle, null, true);
        }
        horseUnexplained++;
        return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNEXPLAINED, step.reason(), 0, horseRide.since, null, null, null, sentVehicle,
                step.closest(), true);
    }

    /** A ride packet the client processed before the tick {@code number}. */
    private void rideApply(RidePacket p, long number, View view, ClientWorld world, ClientEntities entities, PlayerState.Keys keysBefore) {
        followVehicles(p);
        switch (p.packet) {
            case ClientboundPacket.SetPassengers set -> {
                int[] ids = set.passengerIds();
                boolean on = false;
                for (int id : ids) {
                    on |= id == selfId;
                }
                boolean controls = ids.length > 0 && ids[0] == selfId; // getControllingPassenger: the first
                if (ride != null && (set.vehicleId() == ride.vehicle ? !controls : on)) {
                    ride = null; // off it, or put on another vehicle
                }
                if (horseRide != null && (set.vehicleId() == horseRide.vehicle ? !controls : on)) {
                    horseRide = null;
                }
                String type = entities.type(set.vehicleId());
                boolean horse = type != null && (ClientEntities.HORSE_TYPES.contains(type) || type.equals(ClientEntities.PIG)
                        || type.equals(ClientEntities.STRIDER) || type.equals(ClientEntities.HAPPY_GHAST) || ClientEntities.NAUTILUSES.contains(type)
                        || ClientEntities.CAMELS.contains(type));
                if (controls && ride == null && horseRide == null && horse) {
                    // a horse the player rides first: it steers it once saddled (HorseRide.start rules out the others); a pig
                    // while the player holds a carrot on a stick too, a strider a warped fungus on a stick; a happy ghast in a harness;
                    // a nautilus saddled
                    horseRide = p.ambiguous ? HorseRide.unclaimed(set.vehicleId(), p.sequence, "the mount may have been processed a tick earlier")
                            : HorseRide.start(set.vehicleId(), p.sequence, entities.horseMounted(set.vehicleId()), view, world, keysBefore, mountSprint,
                                    gameTimeAtLeast());
                } else if (controls && ride == null && horseRide == null) {
                    ride = p.ambiguous ? BoatRide.unclaimed(set.vehicleId(), p.sequence, "the mount may have been processed a tick earlier")
                            : BoatRide.start(set.vehicleId(), p.sequence, entities.mounted(set.vehicleId()), view, world, positionsDuring(number - 1));
                }
                if (on) {
                    sentPositions.clear(); // a passenger sends no position: where it is follows its vehicle
                }
            }
            case ClientboundPacket.RemoveEntities removed -> {
                for (int id : removed.entityIds()) {
                    if (ride != null && (id == ride.vehicle || id == selfId)) {
                        ride = null;
                    }
                    if (horseRide != null && (id == horseRide.vehicle || id == selfId)) {
                        horseRide = null;
                    }
                }
            }
            case ClientboundPacket.Login ignored -> {
                ride = null;
                horseRide = null;
            }
            case ClientboundPacket.Respawn ignored -> {
                ride = null;
                horseRide = null;
            }
            case ClientboundPacket.PlayerPosition ignored -> {
                if (ride != null) {
                    ride.unclaim("the player teleported while riding");
                }
                if (horseRide != null) {
                    horseRide.unclaim("the player teleported while riding");
                }
            }
            case ClientboundPacket.MoveVehicle ignored -> {
                if (ride != null) {
                    ride.unclaim("the server moved the vehicle"); // handleMoveVehicle snaps it and answers outside the tick
                }
                if (horseRide != null) {
                    horseRide.unclaim("the server moved the vehicle");
                }
            }
            case ClientboundPacket.TeleportEntity teleport -> {
                if (ride != null && teleport.entityId() == ride.vehicle) {
                    ride.unclaim("the server moved the vehicle");
                }
                if (horseRide != null && teleport.entityId() == horseRide.vehicle) {
                    horseRide.unclaim("the server moved the vehicle");
                }
            }
            case ClientboundPacket.Untranslatable untranslatable -> {
                if (ride != null) {
                    ride.unclaim("a packet WhyNotAC cannot read: " + untranslatable.type());
                }
                if (horseRide != null) {
                    horseRide.unclaim("a packet WhyNotAC cannot read: " + untranslatable.type());
                }
            }
            case ClientboundPacket.SetEntityMotion motion -> {
                if (ride != null && motion.entityId() == ride.vehicle) {
                    ride.setMotion(motion.clientMotion());
                }
                if (horseRide != null && motion.entityId() == horseRide.vehicle) {
                    horseRide.apply(motion);
                }
            }
            case ClientboundPacket.UpdateAttributes attributes when horseRide != null && attributes.entityId() == horseRide.vehicle ->
                    horseRide.apply(attributes);
            case ClientboundPacket.MobEffect effect when horseRide != null && effect.entityId() == horseRide.vehicle -> horseRide.apply(effect);
            case ClientboundPacket.RemoveMobEffect effect when horseRide != null && effect.entityId() == horseRide.vehicle -> horseRide.apply(effect);
            case ClientboundPacket.SetEquipment equipment when horseRide != null && equipment.entityId() == horseRide.vehicle ->
                    horseRide.apply(equipment);
            case ClientboundPacket.SetEntityData data -> {
                if (horseRide != null && data.entityId() == horseRide.vehicle) {
                    horseRide.apply(data);
                }
                if (data.entityId() == selfId) {
                    for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                        if (e.index() == 0) { // DATA_SHARED_FLAGS_ID: its sprint flag sets the rider's isSprinting
                            Boolean sprinting = p.ambiguous ? null : (e.value() & (1 << 3)) != 0;
                            mountSprint = sprinting;
                            if (horseRide != null) {
                                horseRide.riderSprinting(sprinting);
                            }
                        }
                    }
                }
                if (ride != null && data.entityId() == ride.vehicle) {
                    for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                        if (e.index() == 5 && e.kind() == ClientboundPacket.SetEntityData.Kind.BOOLEAN) { // DATA_NO_GRAVITY
                            ride.noGravity = e.value() != 0;
                        }
                    }
                }
            }
            default -> { }
        }
    }

    /** The vehicles the client may have the player on ({@link #clientVehicles}) once it processed {@code p}. */
    private void followVehicles(RidePacket p) {
        switch (p.packet) {
            case ClientboundPacket.Login ignored -> clientVehicles = new java.util.HashSet<>(); // a new player
            case ClientboundPacket.Respawn ignored -> clientVehicles = new java.util.HashSet<>();
            case ClientboundPacket.Untranslatable ignored -> clientVehicles = null;
            case ClientboundPacket.SetPassengers set when clientVehicles != null -> {
                if (p.mayMount) {
                    clientVehicles.add(set.vehicleId());
                    mountedSince = p.sequence;
                } else if (clientVehicles.remove(set.vehicleId()) && clientVehicles.isEmpty()) {
                    onNoVehicleSince = p.sequence; // ejectPassengers
                }
            }
            case ClientboundPacket.RemoveEntities removed when clientVehicles != null -> {
                boolean was = !clientVehicles.isEmpty();
                for (int id : removed.entityIds()) {
                    if (id == selfId) {
                        clientVehicles = null;
                        return;
                    }
                    clientVehicles.remove(id); // its passengers stop riding (Entity.setRemoved)
                }
                if (was && clientVehicles.isEmpty()) {
                    onNoVehicleSince = p.sequence;
                }
            }
            default -> { }
        }
        if (clientVehicles != null && clientVehicles.isEmpty() && (p.packet instanceof ClientboundPacket.Login || p.packet instanceof ClientboundPacket.Respawn)) {
            onNoVehicleSince = p.sequence;
        }
    }

    /**
     * Where the player was during client tick {@code tick}, each within {@link BoatRide#UNSENT} of the client's: the
     * positions it sent in it and the last one before (it moves no further without sending one). Null when not known.
     */
    private List<Vec3d> positionsDuring(long tick) {
        List<Vec3d> out = new ArrayList<>(2);
        double[] before = null;
        for (double[] p : sentPositions) {
            if (p[0] < tick) {
                before = p;
            } else if (p[0] == tick) {
                out.add(new Vec3d(p[1], p[2], p[3]));
            }
        }
        if (before == null) {
            return null;
        }
        out.add(new Vec3d(before[1], before[2], before[3]));
        return out;
    }

    /** {@code ClientLevel.tick}, after the entities: the world border's step, when the level ran normally. */
    private static List<Hypothesis> borderTicked(List<Hypothesis> states, Boolean ranNormally) {
        List<Hypothesis> out = new ArrayList<>(states.size());
        for (Hypothesis h : states) {
            ClientBorder border = h.self().border();
            ClientBorder ticked = border.ticked(ranNormally);
            out.add(ticked == border ? h : h.withSelf(h.self().withBorder(ticked)));
        }
        return out;
    }

    private Tick tracked(long number, TimelineEvent.Inbound end, TickInput in, long[] w, ClientWorld world, ClientEntities entities,
            boolean onFoot) {
        boolean actedWhileUsing = false;
        Set<Hypothesis> next = new LinkedHashSet<>();
        List<PlayerState> predictions = new ArrayList<>();
        View view = new View(world, entities, selfId);
        try {
            List<Hypothesis> starts = new ArrayList<>();
            for (Hypothesis h : hypotheses) {
                for (long k = Math.max(h.applied(), w[0]); k <= Math.max(h.applied(), w[1]); k++) {
                    starts.add(applyPending(h, k));
                }
            }
            if (in.itemActions.stream().anyMatch(ServerboundPacket.UseItem.class::isInstance)) {
                resolveItemUses(in, starts, world, onFoot);
            }
            if (!in.blockUses.isEmpty()) {
                // handleKeybinds' uses come before the entities' ticks, from states not using an item (startUseItem)
                String why = onFoot ? resolveBlockUses(in, starts.stream().filter(a -> a.self().using() == null).toList(), world, entities)
                        : "riding";
                if (why != null) {
                    throw new Lose("item use on a block: " + why);
                }
            }
            starts = pushed(starts, true, view); // the entities that tick before the player, after the packets
            if (in.unmodelled == null && (in.attacks > 0 || in.stabs > 0 || in.interactions > 0 || !in.blockUses.isEmpty()
                    || in.itemActions.stream().anyMatch(ServerboundPacket.UseItem.class::isInstance))) {
                // Minecraft.handleKeybinds, before the player's tick: while it is using an item it only releases it (the use
                // key up) and consumes the attack and use keys' clicks; attacks, stabs, interactions and uses come from
                // startAttack and startUseItem, which only a state not using an item runs
                boolean any = !starts.isEmpty();
                starts.removeIf(a -> a.self().using() != null);
                actedWhileUsing = any && starts.isEmpty();
            }
            view.checkSurroundings(starts);
            Map<StepKey, OnFoot.Step> steps = new HashMap<>();
            for (Hypothesis a : starts) {
                simulate(a, in, view, next, steps, predictions);
                if (next.size() > MAX_HYPOTHESES) {
                    List<Hypothesis> deduped = merged(next);
                    next.clear();
                    next.addAll(deduped);
                    if (next.size() > MAX_HYPOTHESES) {
                        hypothesisOverflows++;
                        throw new Lose("more than " + MAX_HYPOTHESES + " hypotheses");
                    }
                }
            }
        } catch (Lose e) {
            lose(e.getMessage());
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.NO_CLAIM, e.getMessage(), 0, since, null, null);
        }
        if (next.isEmpty()) {
            long from = since;
            lose("unexplained movement");
            String reason = actedWhileUsing ? ACTED_WHILE_USING : in.abilities > 0 ? ABILITIES_WITHOUT_FLIGHT : "no vanilla client sends this";
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNEXPLAINED, reason, 0, from, closest(predictions, in.move), in.move);
        }
        lastHands = hands();
        sent = merged(next);
        try {
            // the entities that tick after the player, in this same client tick
            hypotheses = merged(new LinkedHashSet<>(borderTicked(pushed(sent, false, view), entities.lastTickRanNormally())));
            if (hypotheses.size() > MAX_HYPOTHESES) {
                hypothesisOverflows++;
                throw new Lose("more than " + MAX_HYPOTHESES + " hypotheses");
            }
        } catch (Lose e) {
            lose(e.getMessage()); // this tick is explained; the next one starts untracked
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.EXPLAINED, null, 0, since, null, null);
        }
        int unionCos = 0;
        for (Hypothesis h : hypotheses) {
            unionCos |= h.cosineMask();
        }
        if (unionCos != 0 && (lockedCosineMask & unionCos) != 0) {
            lockedCosineMask &= unionCos;
        }
        return new Tick(number, end.sequence(), end.nanoTime(), Outcome.EXPLAINED, null, hypotheses.size(), since, null, null);
    }

    /** More living entities than this pushing the player at once end the claim (their order is tried in full). */
    static final int MAX_PUSHERS = 3;

    /**
     * Each state after the pushes of the living entities whose tick fell where the state stands
     * ({@code LivingEntity.pushEntities}, then {@code Entity.push(Entity)} on the player): those ticking before the
     * player in the client's tick ({@code beforeSelf}: the states are where the packets before the tick left the player)
     * or after it (where the player's own tick left it). Teams and the player decide whether a push lands
     * ({@code EntitySelector.pushableBy}: alive, not a spectator, not on a climbable block); the pushes of several
     * entities add up in their tick order, which is not followed: every order is a state.
     */
    private List<Hypothesis> pushed(List<Hypothesis> states, boolean beforeSelf, View view) throws Lose {
        List<Hypothesis> out = new ArrayList<>(states.size());
        for (Hypothesis h : states) {
            PlayerState p = h.player();
            if (p == null || !h.self().positionKnown()) {
                // no position yet: the first teleport sets the motion (one that keeps it ends the claim, see apply)
                addIfNew(out, h);
                continue;
            }
            Box box = p.box();
            List<ClientEntities.Pusher> pushers = view.entities.pushers(selfId, box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                    beforeSelf, UNKNOWN_ENTITY_RADIUS);
            if (pushers == null) {
                throw new Lose("an entity that may push the player is near: " + view.entities.whyNotKnown());
            }
            if (pushers.isEmpty() || !pushable(h, view)) {
                addIfNew(out, boosted(h, beforeSelf, false, view));
                continue;
            }
            List<ClientEntities.Pusher> landing = new ArrayList<>(pushers.size());
            for (ClientEntities.Pusher pusher : pushers) {
                Boolean teams = teamsLetPush(pusher.scoreboardName());
                if (teams == null) {
                    throw new Lose("teams may keep an entity from pushing the player");
                }
                if (teams) {
                    landing.add(pusher);
                }
            }
            if (landing.size() > MAX_PUSHERS) {
                throw new Lose("more than " + MAX_PUSHERS + " entities push the player");
            }
            for (List<ClientEntities.Pusher> order : orders(landing)) {
                Vec3d d = p.deltaMovement();
                for (ClientEntities.Pusher pusher : order) {
                    d = pushedBy(d, p.x(), p.z(), pusher.x(), pusher.z());
                }
                addIfNew(out, boosted(h.withPlayer(withDelta(p, d)), beforeSelf, !landing.isEmpty(), view));
            }
            if (!landing.isEmpty()) {
                pushesFollowed++;
            }
        }
        return out;
    }

    /**
     * After the player's tick: each firework rocket attached to it ({@code FireworkRocketEntity.tick}, ticking after it,
     * {@link ClientEntities#rocketsBoosting}) boosts a gliding player toward its look ({@code getLookAngle}) for the
     * next tick. A rocket boost and a push in the same tick end the claim: their order (the tick list's) is not followed.
     */
    private Hypothesis boosted(Hypothesis h, boolean beforeSelf, boolean pushed, View view) throws Lose {
        PlayerState p = h.player();
        if (beforeSelf || !p.fallFlying()) {
            return h; // isFallFlying, as the player's tick left it
        }
        Integer rockets = view.entities.rocketsBoosting(selfId, p.x(), p.z());
        if (rockets == null) {
            throw new Lose("a firework rocket may boost the gliding player: " + view.entities.whyNotKnown());
        }
        if (rockets == 0) {
            return h;
        }
        if (pushed) {
            throw new Lose("a firework rocket boost and a push in one tick");
        }
        Vec3d look = Vec3d.viewVector(p.xRot(), p.yRot());
        Vec3d d = p.deltaMovement();
        for (int i = 0; i < rockets; i++) {
            d = rocketBoost(d, look); // each rocket's the same function of the motion: their order does not matter
        }
        rocketBoosts += rockets;
        return h.withPlayer(withDelta(p, d));
    }

    /** {@code FireworkRocketEntity.tick}'s boost of a gliding entity looking along {@code look}, as the client computes it. */
    static Vec3d rocketBoost(Vec3d d, Vec3d look) {
        return d.add(look.x() * 0.1 + (look.x() * 1.5 - d.x()) * 0.5, look.y() * 0.1 + (look.y() * 1.5 - d.y()) * 0.5,
                look.z() * 0.1 + (look.z() * 1.5 - d.z()) * 0.5);
    }

    /** {@code LivingEntity.isPushable} for the player: alive, not a spectator, not on a climbable block (unless flying). */
    private static boolean pushable(Hypothesis h, View view) throws Lose {
        SelfState s = h.self();
        if (!(s.health() > 0.0F) || s.gameMode() == 3) {
            return false;
        }
        if (s.flying()) {
            return true; // Player.onClimbable: never while flying
        }
        try {
            return !OnFoot.onClimbable(h.player(), view);
        } catch (Colliders.Unknown e) {
            throw new Lose(e.getMessage());
        }
    }

    /** Every order of {@code items}. */
    private static <T> List<List<T>> orders(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        if (items.size() <= 1) {
            out.add(items);
            return out;
        }
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T first = rest.remove(i);
            for (List<T> tail : orders(rest)) {
                List<T> order = new ArrayList<>(items.size());
                order.add(first);
                order.addAll(tail);
                out.add(order);
            }
        }
        return out;
    }

    /**
     * {@code Entity.push(Entity)} on the player at {@code (lx, lz)} from an entity at {@code (rx, rz)}, as the client
     * computes it: away from it along the larger axis' square root, 0.05 at most, added to the motion.
     */
    static Vec3d pushedBy(Vec3d delta, double lx, double lz, double rx, double rz) {
        double d = rx - lx;
        double e = rz - lz;
        double max = Math.max(Math.abs(d), Math.abs(e)); // Mth.absMax
        if (!(max >= 0.01F)) {
            return delta;
        }
        max = Math.sqrt(max);
        d /= max;
        e /= max;
        double g = 1.0 / max;
        if (g > 1.0) {
            g = 1.0;
        }
        d *= g;
        e *= g;
        d *= 0.05F;
        e *= 0.05F;
        return new Vec3d(delta.x() + -d, delta.y() + 0.0, delta.z() + -e); // push(-d, 0.0, -e): getDeltaMovement().add
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
    static List<Hypothesis> merged(Set<Hypothesis> states) {
        Map<Hypothesis, int[]> masks = new LinkedHashMap<>();
        for (Hypothesis h : states) {
            int[] m = masks.computeIfAbsent(new Hypothesis(h.player(), h.self(), h.sent(), 0, 0, 0, h.applied()), k -> new int[3]);
            m[0] |= h.windowMask();
            m[1] |= h.autoJumpMask();
            m[2] |= h.cosineMask();
        }
        List<Hypothesis> out = new ArrayList<>(masks.size());
        for (Map.Entry<Hypothesis, int[]> e : masks.entrySet()) {
            Hypothesis k = e.getKey();
            out.add(new Hypothesis(k.player(), k.self(), k.sent(), e.getValue()[0], e.getValue()[1], e.getValue()[2], k.applied()));
        }
        return List.copyOf(out);
    }

    /**
     * A state a tick may start from, with the speed attribute it holds then and the item it uses; {@code riptide}: the
     * spin attack strength a trident released in this tick's item actions pushes the player with, or null.
     */
    private record Start(PlayerState player, SpeedAttribute speed, Using using, Float riptide) {
        Start withUsing(Using u) {
            return new Start(player, speed, u, riptide);
        }

        Start withRiptide(float strength) {
            return new Start(player, speed, using, strength);
        }
    }

    /**
     * One stack a hand may hold, as far as using it goes: the item (null: none), its use effects and what its use does;
     * {@code spin}: a trident's spin attack strength ({@link dev.whynotac.protocol.packet.Stack#spinAttack}: NaN where not
     * known exactly, null for another item or where not known at all).
     */
    private record HandItem(String item, OnFoot.ItemUse effects, ItemPrototypes.UseKind kind, Float spin) {
        static final HandItem NONE = new HandItem(null, null, null, null);

        /** A trident whose spin attack strength is known and above 0: its use needs water or rain. */
        boolean riptide() {
            return spin != null && spin > 0.0F;
        }
    }

    /** What one on-foot step depends on, the double-tap counter aside (see {@link #simulate}). */
    private record StepKey(PlayerState start, PlayerState.Keys keys, boolean food, boolean autoJump, SpeedAttribute speed, OnFoot.Effects effects,
            OnFoot.ItemUse use, int window, boolean glider, PlatformCos cosine, Border border, Boolean leatherBoots, Float riptide, boolean spinAttack) {}

    private void lose(String reason) {
        hypotheses = null; // the base goes on from what the server knows for certain
        lostReason = reason;
        quietSincePosition = false;
        lostFromShortAction = isShortActionLoss(reason);
    }

    private static boolean isShortActionLoss(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("riptide") || reason.contains("trident") || reason.contains("self-ending")
                || reason.contains("item use") || reason.contains("brush") || reason.contains("bundle");
    }

    /** Number of ticks where tracking was dropped because deduplicated hypotheses still exceeded {@link #MAX_HYPOTHESES}. */
    public long hypothesisOverflows() {
        return hypothesisOverflows;
    }

    /** Session-wide narrowed {@link PlatformCos} bitmask ({@link Hypothesis#ALL_COSINES} until a glide distinguishes CPU cosine). */
    public int lockedCosineMask() {
        return lockedCosineMask;
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
            case ClientboundPacket.Login login -> freshPlayer(h, loggedIn(login), login.gameMode(), false, false);
            case ClientboundPacket.Respawn respawn -> respawned(h, respawn);
            case ClientboundPacket.PlayerPosition teleport -> {
                if (s.vehicle() != -1) {
                    yield h; // handleMovePlayer: not while riding
                }
                Set<RelativeFlag> r = teleport.relatives();
                if (!s.positionKnown() && (r.contains(RelativeFlag.DELTA_X) || r.contains(RelativeFlag.DELTA_Y) || r.contains(RelativeFlag.DELTA_Z))) {
                    // entities near where the client had the player may have pushed it: their pushes are not followed there
                    throw new Lose("a teleport that keeps the motion from before the player's position was known");
                }
                yield h.withPlayer(teleported(p, teleport)).withSelf(s.withPositionKnown());
            }
            case ClientboundPacket.PlayerRotation ignored -> h; // the reply is outside the tick (RotationReplies), sendPosition's last rotation is the client's
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
            case ClientboundPacket.MobEffect effect -> h.withSelf(s.withEffect(effect.effectKey(), effect.amplifier(), true));
            case ClientboundPacket.RemoveMobEffect effect -> h.withSelf(s.withEffect(effect.effectKey(), 0, false));
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
            case ClientboundPacket.InitializeBorder b -> h.withSelf(s.withBorder(s.border().initialized(b)));
            case ClientboundPacket.SetBorderCenter b -> h.withSelf(s.withBorder(s.border().withCenter(b.centerX(), b.centerZ())));
            case ClientboundPacket.SetBorderLerpSize b -> h.withSelf(s.withBorder(s.border().lerp(b.oldSize(), b.newSize(), b.lerpTime())));
            case ClientboundPacket.SetBorderSize b -> h.withSelf(s.withBorder(s.border().withSize(b.size())));
            case ClientboundPacket.EntityEvent event -> {
                int e = event.event();
                if (e == ClientboundPacket.EntityEvent.FINISH_USING_ITEM) {
                    // Player.handleEntityEvent: completeUsingItem, then stopUsingItem (or releaseUsingItem when the hand changed)
                    Using u = s.using();
                    if (u == null) {
                        yield h;
                    }
                    if (u.kind() == ItemPrototypes.UseKind.RELEASE_MOVES) {
                        throw new Lose("a trident's use completed (a release may move the player on the client)");
                    }
                    if (s.food() <= 6 && !s.mayFly()) {
                        yield h.withSelf(s.withUsing(null).withFoodEither());
                    }
                    yield h.withSelf(s.withUsing(null));
                }
                if (NEUTRAL_ENTITY_EVENTS.contains(e)) {
                    yield h;
                }
                throw new Lose("entity event " + e + " on the player");
            }
            case ClientboundPacket.TeleportEntity ignored -> throw new Lose("the player's entity moved by the server");
            case ClientboundPacket.EntityPositionSync ignored -> throw new Lose("the player's entity moved by the server");
            case ClientboundPacket.MoveEntity ignored -> throw new Lose("the player's entity moved by the server");
            case ClientboundPacket.Cooldown cooldown -> h.withSelf(s.withCooldown(cooldown.group(), cooldown.duration()));
            case ClientboundPacket.Untranslatable u -> throw new Lose("a packet WhyNotAC cannot read: " + u.type());
            default -> h;
        };
    }

    /**
     * {@code handleGameEvent}'s weather: the rain level a start of rain (0), its end (1) or a change (the value) sets
     * ({@code ClientLevel.setRainLevel}); null for any other event.
     */
    private static Float rainLevel(ClientboundPacket.GameEvent event) {
        return switch (event.type()) {
            case 1 -> 0.0F; // START_RAINING
            case 2 -> 1.0F; // STOP_RAINING
            case 7 -> event.param(); // RAIN_LEVEL_CHANGE
            default -> null;
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
        Float rain = rainLevel(event);
        if (rain != null) {
            return h.withSelf(h.self().withRainLevel(rain));
        }
        // thunder, sounds, respawn and crafting settings, the level loading signal: nothing that moves the player
        if (type == 0 || type >= 6 && type <= 13) {
            return h;
        }
        throw new Lose("game event " + type);
    }

    /** {@code handleSetEntityData} for the player's own entity: the shared flags, pose and living flags it applies. */
    private Hypothesis entityData(Hypothesis h, ClientboundPacket.SetEntityData data) throws Lose {
        PlayerState p = h.player();
        SelfState s = h.self();
        boolean noGravity = s.noGravity();
        Using using = s.using();
        boolean spinAttack = s.spinAttack();
        for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
            switch (e.index()) {
                case 0 -> { // Entity.DATA_SHARED_FLAGS_ID
                    int flags = (int) e.value();
                    p = p.withFallFlying((flags & (1 << 7)) != 0); // the server's gliding flag: it starts or ends the client's glide
                    p = withSprinting(p, (flags & (1 << 3)) != 0); // the flag alone: no modifier change
                    p = p.withWater((flags & (1 << 4)) != 0, p.eyeInWater()); // swimming, the server's own
                }
                case 5 -> noGravity = e.value() != 0; // DATA_NO_GRAVITY
                case 6 -> { // DATA_POSE
                    ClientboundPacket.EntityPose pose = ClientboundPacket.EntityPose.values()[(int) Math.min(e.value(),
                            ClientboundPacket.EntityPose.UNKNOWN.ordinal())];
                    p = switch (pose) {
                        case STANDING -> withPose(p, PlayerState.Pose.STANDING);
                        case CROUCHING -> withPose(p, PlayerState.Pose.CROUCHING);
                        case SWIMMING -> withPose(p, PlayerState.Pose.SWIMMING);
                        case FALL_FLYING -> withPose(p, PlayerState.Pose.FALL_FLYING);
                        case SPIN_ATTACK -> withPose(p, PlayerState.Pose.SPIN_ATTACK);
                        default -> throw new Lose("pose " + pose + " set by the server");
                    };
                }
                // DATA_TICKS_FROZEN (7): what freezing changes on the client's move is the speed, an attribute the server sends
                case 8 -> { // LivingEntity.DATA_LIVING_ENTITY_FLAGS: LocalPlayer.onSyncedDataUpdated starts or stops its own use to match
                    boolean flag = (e.value() & 1) != 0;
                    if (flag && using == null) {
                        using = Using.fromServer((e.value() & 2) != 0 ? Hand.OFF_HAND : Hand.MAIN_HAND);
                    } else if (!flag) {
                        using = null;
                    }
                    spinAttack = (e.value() & 4) != 0; // isAutoSpinAttack: the pose's, the client sets no such flag itself
                }
                default -> { }
            }
        }
        return h.withPlayer(p).withSelf(s.withNoGravity(noGravity).withUsing(using).withSpinAttack(spinAttack));
    }

    /** A new {@code LocalPlayer} (login or respawn): the client's defaults, its rotation {@code -180, 0}, not ticking until loaded. */
    private Hypothesis freshPlayer(Hypothesis h, SelfState s, int gameMode, boolean keepAttributes, boolean keepData) throws Lose {
        PlayerState old = h.player();
        if (keepData && old == null) {
            throw new Lose("a respawn keeps the state of an untracked player");
        }
        SpeedAttribute speed = s.speed() == null ? null : keepAttributes ? s.speed().copied() : s.speed().baseOnly();
        SelfState self = SelfState.fresh(gameMode < 0 ? s.gameMode() : gameMode, speed,
                keepAttributes ? s.otherAttributes() : Set.of(), s.border(), s.rainLevel(),
                keepAttributes ? s.blockRange() : s.blockRangeBase(), s.blockRangeBase()).withGameMode(gameMode < 0 ? s.gameMode() : gameMode);
        Vec3d delta = keepData ? old.deltaMovement() : Vec3d.ZERO;
        float yRot = keepData ? old.yRot() : -180.0F;
        float xRot = keepData ? old.xRot() : 0.0F;
        boolean sprinting = keepData && old.sprinting(); // the entity data, with its flags
        boolean sprintModifier = speed != null && speed.holdsSprinting();
        PlayerState.Pose pose = keepData ? old.pose() : PlayerState.Pose.STANDING;
        PlayerState player = new PlayerState(0.0, 0.0, 0.0, delta, yRot, xRot, false, false, false, false, false, 0.0, sprinting, sprintModifier, false,
                pose, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, keepData && old.swimming(), false, // a new entity's eye holds no fluid
                keepData && old.fallFlying(), false, false, 0); // the entity data, with its flags; a new entity touched no water and spins not
        Hypothesis.Sent sent = keepData ? Hypothesis.Sent.fresh(h.sent().input(), old.sprinting()) : Hypothesis.Sent.fresh(PlayerState.Keys.NONE, false);
        return new Hypothesis(player, self, sent, h.windowMask(), h.autoJumpMask(), h.cosineMask(), h.applied());
    }

    private Hypothesis respawned(Hypothesis h, ClientboundPacket.Respawn respawn) throws Lose {
        int keep = respawn.dataToKeep();
        if (keep < 0) {
            throw new Lose("a respawn from an old recording (what it keeps is not known)");
        }
        boolean keepAttributes = (keep & ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS) != 0;
        boolean keepData = (keep & ClientboundPacket.Respawn.KEEP_ENTITY_DATA) != 0;
        return freshPlayer(h, respawnedInto(h.self(), respawn), respawn.gameMode(), keepAttributes, keepData);
    }

    /** A login's state before the player: a new level, its new border, no rain (the server sends its weather after). */
    private static SelfState loggedIn(ClientboundPacket.Login login) {
        return SelfState.INITIAL.withBorder(ClientBorder.fresh(login.dimension() == null ? null : login.dimension().key())).withRainLevel(0.0F);
    }

    /** {@code handleRespawn}: a new level (and border, and no rain) only into another dimension. */
    private static SelfState respawnedInto(SelfState s, ClientboundPacket.Respawn respawn) {
        String key = respawn.dimension() == null ? null : respawn.dimension().key();
        String was = s.border().dimension();
        float rain = key != null && key.equals(was) ? s.rainLevel() : key != null && was != null ? 0.0F : Float.NaN;
        return s.withBorder(s.border().respawned(key)).withRainLevel(rain);
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
                p.stuckSpeedMultiplier(), p.swimming(), p.eyeInWater(), p.fallFlying(), p.inPowderSnow(), p.inWater(), p.spinAttackTicks());
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
        if (self.food() == SelfState.FOOD_EITHER) {
            simulate(h.withSelf(self.withHealth(self.health(), 6)), in, view, out, steps, predictions);
            simulate(h.withSelf(self.withHealth(self.health(), 20)), in, view, out, steps, predictions);
            return;
        }
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
        if (in.abilities > 0) {
            // LocalPlayer.aiStep sends the abilities when a spectator starts flying, a double jump toggles flying (both
            // with mayfly) or flying stops on the ground; a state that may fly or flies is out of scope above, and the
            // client changes neither itself without them
            return;
        }
        if (in.inputs > 1 || in.sprints > 1 || in.moves > 1 || in.glideStarts > 1) {
            throw new Lose("several input, sprint, gliding or movement packets in one tick");
        }
        // the client sends START_FALL_FLYING exactly when tryToStartFallFlying succeeded: with it, it had a glider
        boolean glider = in.glideStarts > 0;
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
        List<Start> starts = itemActions(new Start(pre, self.speed(), self.using(), null), in, view, self);
        for (int i = 0; i < in.attacks; i++) {
            // Player.causeExtraKnockback, when the client's own simulation of the hit knocks back: slowed, not sprinting
            List<Start> more = new ArrayList<>();
            for (Start s : starts) {
                SpeedAttribute speed = s.speed().setSprinting(false);
                if (speed == null) {
                    throw new Lose("more speed modifiers than the client's table holds unresized");
                }
                Start slowed = new Start(withSprinting(withSprintModifier(withDelta(s.player(), s.player().deltaMovement().multiply(0.6, 1.0, 0.6)),
                        false), false), speed, s.using(), s.riptide());
                if (!starts.contains(slowed) && !more.contains(slowed)) {
                    more.add(slowed);
                }
            }
            starts.addAll(more);
        }
        starts = updatingUsingItem(starts, in);
        boolean autoJump = self.firstTick() || (h.autoJumpMask() & Hypothesis.AUTO_JUMP_ON) != 0;
        int window = Integer.numberOfTrailingZeros(h.windowMask());
        PlatformCos[] platforms = PlatformCos.values();
        int effectiveCosMask = (h.cosineMask() & lockedCosineMask) != 0 ? (h.cosineMask() & lockedCosineMask) : h.cosineMask();
        for (Start begin : starts) {
            PlatformCos any = platforms[Integer.numberOfTrailingZeros(effectiveCosMask)];
            OnFoot.Step first = step(begin, keys, view, self, autoJump, window, glider, any, steps);
            if (!first.readCosine()) {
                successors(h, self, in, begin, first, glider, keys, effectiveCosMask, out, predictions);
                continue;
            }
            // a glide: Math.cos is the client's CPU's; each cosine the platforms still possible give at this pitch is a step
            for (int left = effectiveCosMask; left != 0; ) {
                PlatformCos p = platforms[Integer.numberOfTrailingZeros(left)];
                long cosine = Double.doubleToRawLongBits(OnFoot.glideCosine(p, begin.player().xRot()));
                int same = 0;
                for (int m = left; m != 0; m &= m - 1) {
                    PlatformCos q = platforms[Integer.numberOfTrailingZeros(m)];
                    if (Double.doubleToRawLongBits(OnFoot.glideCosine(q, begin.player().xRot())) == cosine) {
                        same |= 1 << q.ordinal();
                    }
                }
                OnFoot.Step step = p == any ? first : step(begin, keys, view, self, autoJump, window, glider, p, steps);
                successors(h, self, in, begin, step, glider, keys, same, out, predictions);
                left &= ~same;
            }
        }
    }

    /**
     * The step from {@code begin}: from {@code steps} when only the double-tap counter differs (it only counts down
     * unless the tick met a forward press it could start sprinting on), else run.
     */
    private OnFoot.Step step(Start begin, PlayerState.Keys keys, View view, SelfState self, boolean autoJump, int window, boolean glider,
            PlatformCos cosine, Map<StepKey, OnFoot.Step> steps) throws Lose {
        PlayerState start = begin.player();
        OnFoot.ItemUse use = begin.using() == null ? null : begin.using().effects();
        Border border = self.border().collision(); // as it is at the move: the level ticks it after the entities
        StepKey key = new StepKey(withSprintTrigger(start, 0), keys, self.foodAllowsSprint(), autoJump, begin.speed(), self.effects(), use, window,
                glider, cosine, border, leatherBoots, begin.riptide(), self.spinAttack());
        OnFoot.Step step = steps.get(key);
        if (step != null && !step.readSprintTrigger()) {
            // the same step: only this state's own counter counted down (or was cleared by shift or backward)
            boolean slowDueToUse = use != null && !use.canSprint();
            int counter = start.keys().shift() || slowDueToUse || keys.backward() ? 0 : Math.max(start.sprintTriggerTime() - 1, 0);
            return new OnFoot.Step(withSprintTrigger(step.state(), counter), false, false, step.autoJumpTriggered(), step.speed(),
                    step.startedGliding(), step.readCosine());
        }
        try {
            step = OnFoot.step(start, keys, view, new OnFoot.Conditions(self.foodAllowsSprint(), window, autoJump, begin.speed(), self.effects(),
                    use, glider, cosine, border, leatherBoots).withRiptide(begin.riptide()).withSpinAttack(self.spinAttack()));
        } catch (Colliders.Unknown e) {
            throw new Lose(e.getMessage());
        }
        if (start.spinAttackTicks() != 0 || begin.riptide() != null) {
            // LivingEntity.checkAutoSpinAttack: the box from the tick's travel on (a riptide's lift before it) to after it
            Box a = start.box();
            Box b = step.state().box();
            view.checkSpinAttack(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()), Math.max(a.maxX(), b.maxX()),
                    Math.max(a.maxY() + OnFoot.RIPTIDE_LIFT, b.maxY()), Math.max(a.maxZ(), b.maxZ()));
        }
        steps.putIfAbsent(key, step);
        return step;
    }

    /**
     * The hypotheses after {@code step} from {@code begin} that send what the client sent, with the options the step read;
     * {@code cosineMask}: the platforms whose {@code Math.cos} the step took.
     */
    private void successors(Hypothesis h, SelfState self, TickInput in, Start begin, OnFoot.Step step, boolean glider, PlayerState.Keys keys,
            int cosineMask, Set<Hypothesis> out, List<PlayerState> predictions) {
        Hypothesis.Sent sent = h.sent();
        if (step.startedGliding() != glider) {
            return; // START_FALL_FLYING sent without the start, or a start without it
        }
        SelfState after = self.afterFirstTick().tickCooldowns().withSpeed((SpeedAttribute) step.speed()).withUsing(begin.using());
        List<Hypothesis> variants = new ArrayList<>();
        int jumpMask = self.firstTick() ? h.autoJumpMask() : step.autoJumpTriggered() ? h.autoJumpMask() & Hypothesis.AUTO_JUMP_ON : h.autoJumpMask();
        PlayerState s = step.state();
        if (step.readSprintWindow()) {
            for (int option = 0; option <= 10; option++) {
                if ((h.windowMask() & (1 << option)) != 0) {
                    variants.add(new Hypothesis(withSprintTrigger(s, option), self, sent, 1 << option, jumpMask, cosineMask, h.applied()));
                }
            }
        } else {
            variants.add(new Hypothesis(s, self, sent, h.windowMask(), jumpMask, cosineMask, h.applied()));
        }
        if (step.autoJumpTriggered() && !self.firstTick() && (h.autoJumpMask() & Hypothesis.AUTO_JUMP_OFF) != 0) {
            int count = variants.size();
            for (int i = 0; i < count; i++) {
                Hypothesis v = variants.get(i);
                variants.add(new Hypothesis(withAutoJump(v.player(), 0), self, sent, v.windowMask(), Hypothesis.AUTO_JUMP_OFF, cosineMask,
                        h.applied()));
            }
        }
        for (Hypothesis v : variants) {
            predictions.add(v.player());
            Hypothesis.Sent sentAfter = observe(v.player(), sent, keys, in);
            if (sentAfter != null) {
                out.add(new Hypothesis(v.player(), after, sentAfter, v.windowMask(), v.autoJumpMask(), cosineMask, h.applied()));
            }
        }
    }

    /**
     * {@code Entity.isInWaterOrRain} where a use in this tick reads it: false only when the client's level certainly
     * does not rain ({@code isRaining}: its rain level at most 0.2, so no rain anywhere: {@code isRainingAt}) and no
     * fluid lies within reach of where the player was in its last tick's start ({@code isInWater}: the
     * {@code wasTouchingWater} that tick's {@code baseTick} left), widened by twice its motion; a block not known may
     * be water.
     */
    private static boolean mayBeInWaterOrRain(PlayerState p, View view, SelfState self) {
        if (!Boolean.FALSE.equals(self.raining())) {
            return true;
        }
        Vec3d d = p.deltaMovement();
        double m = 2.0 + 2.0 * (Math.abs(d.x()) + Math.abs(d.y()) + Math.abs(d.z()));
        if (!(m < 16.0)) {
            return true;
        }
        int x0 = (int) Math.floor(p.x() - m);
        int x1 = (int) Math.floor(p.x() + m);
        int y0 = (int) Math.floor(p.y() - m);
        int y1 = (int) Math.floor(p.y() + 1.8 + m);
        int z0 = (int) Math.floor(p.z() - m);
        int z1 = (int) Math.floor(p.z() + m);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!view.isLoaded(x >> 4, z >> 4)) {
                    return true;
                }
                for (int y = y0; y <= y1; y++) {
                    int state = view.blockState(x, y, z);
                    if (state == ClientWorld.UNKNOWN || view.states().fluidKind(state) != BlockStates.FLUID_NONE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code Minecraft.handleKeybinds}' item actions, before the player's tick: an item use may start using the stack in
     * its hand ({@code Item.use}: a consumable the player can eat, a shield, a spear, a bow with ammunition...) or not
     * (an item without a use, a cooldown, food when full), both are followed; a release stops the use. A use that may
     * change blocks where the client's ray hits (buckets, lily pads) ends the claim: the world replica cannot place it.
     */
    private List<Start> itemActions(Start first, TickInput in, View view, SelfState self) throws Lose {
        List<Start> starts = new ArrayList<>(List.of(first));
        for (Object action : in.itemActions) {
            List<Start> next = new ArrayList<>();
            if (action instanceof ServerboundPacket.UseItem use) {
                Hand hand = use.hand();
                boolean resolved = in.resolvedItemUseSeqs.contains(use.sequence());
                List<HandItem> items = handItems(hand);
                if (!resolved) {
                    for (HandItem item : items) {
                        if (item.kind() == ItemPrototypes.UseKind.CHANGES_BLOCKS) {
                            throw new Lose("an item use that may change a block where WhyNotAC cannot place it");
                        }
                    }
                }
                for (Start s : starts) {
                    addIfNew(next, s);
                    if (!resolved && s.using() == null) { // LocalPlayer.startUsingItem: not while using, not with an empty hand
                        for (HandItem item : items) {
                            // TridentItem.use: a riptide trident fails out of water and rain (isInWaterOrRain)
                            if (item.item() != null && (!item.riptide() || !Boolean.FALSE.equals(inWaterOrRain(s.player(), view, self)))) {
                                addIfNew(next, s.withUsing(new Using(hand, item.item(), item.effects(), item.kind(), 0)));
                            }
                        }
                    }
                }
            } else {
                for (Start s : starts) {
                    addIfNew(next, released(s, in, view, self));
                }
            }
            starts = next;
        }
        return starts;
    }

    /**
     * {@code LivingEntity.releaseUsingItem}, then {@code stopUsingItem}. A trident's release ({@code TridentItem.releaseUsing},
     * with the hand's stack: the same item as the use's) pushes the player along its look with its spin attack strength
     * and starts a spin attack when it was used 10 ticks at least, the strength is above 0 and the player in water or rain
     * ({@code isInWaterOrRain}, before the tick: the last tick's {@code wasTouchingWater}); the step applies it
     * ({@link OnFoot.Conditions#withRiptide}). A release that may be such a one where WhyNotAC cannot tell ends the claim.
     */
    private Start released(Start s, TickInput in, View view, SelfState self) throws Lose {
        Using u = s.using();
        Start stopped = s.withUsing(null);
        if (u == null) {
            return stopped;
        }
        List<HandItem> items = handItems(u.hand());
        boolean trident = u.kind() == ItemPrototypes.UseKind.RELEASE_MOVES;
        for (HandItem item : items) {
            trident |= item.kind() == ItemPrototypes.UseKind.RELEASE_MOVES;
        }
        if (!trident || u.used() >= 0 && u.used() < Using.USED_KNOWN) {
            return stopped; // used under 10 ticks, whatever stack the hand holds (a use the server's flags started too)
        }
        if (in.handsTouched) {
            throw new Lose("a trident released in a tick the client changed its hands in");
        }
        Float spin = null;
        for (HandItem item : items) {
            if (item.item() == null || !item.item().equals(u.item()) || item.spin() == null || item.spin().isNaN()
                    || spin != null && Float.compare(spin, item.spin()) != 0) {
                throw new Lose("a trident released whose riptide is not known");
            }
            spin = item.spin();
        }
        if (spin == null || !(spin > 0.0F)) {
            return stopped; // thrown by the server: nothing on the client's movement
        }
        if (u.used() < 0) {
            throw new Lose("a riptide released from a use whose ticks are not known");
        }
        Boolean wet = inWaterOrRain(s.player(), view, self);
        if (wet == null) {
            throw new Lose("a riptide released where WhyNotAC cannot tell water or rain");
        }
        return wet ? stopped.withRiptide(spin) : stopped;
    }

    /**
     * {@code Entity.isInWaterOrRain} before the tick: true in water ({@code wasTouchingWater}), false out of it where the
     * level certainly does not rain or no fluid is in reach ({@link #mayBeInWaterOrRain}), else null (in rain or not: the
     * sky and the biome at the player).
     */
    private static Boolean inWaterOrRain(PlayerState p, View view, SelfState self) {
        if (Boolean.TRUE.equals(p.inWater())) {
            return true;
        }
        if (Boolean.FALSE.equals(p.inWater()) && Boolean.FALSE.equals(self.raining())) {
            return false;
        }
        return mayBeInWaterOrRain(p, view, self) ? null : false;
    }

    /**
     * {@code LivingEntity.updatingUsingItem}, at the start of the player's tick: the use goes on with the hand's stack
     * while it is the same item ({@code ItemStack.isSameItem}: the item alone), else it stops; where the client touched
     * its hands in this tick, it may have stopped too. A use the server's flags started takes the hand's stack as it
     * was then: the hand's now, or none as well where it may have changed since.
     */
    private List<Start> updatingUsingItem(List<Start> starts, TickInput in) throws Lose {
        List<Start> out = new ArrayList<>(starts.size());
        for (Start s : starts) {
            Using u = s.using();
            if (u == null) {
                addIfNew(out, s);
                continue;
            }
            List<HandItem> items = handItems(u.hand());
            for (HandItem item : items) {
                boolean goesOn = item.item() != null && (!u.resolved() || item.item().equals(u.item()));
                addIfNew(out, s.withUsing(goesOn ? u.ticked(item.item(), item.effects(), item.kind()) : null));
            }
            if (in.handsTouched || !u.resolved() && (items.size() > 1 || lastHands == null || !items.equals(lastHands.get(u.hand())))) {
                addIfNew(out, s.withUsing(null));
            }
        }
        for (Start s : out) {
            if (s.using() != null && s.using().kind() == ItemPrototypes.UseKind.SELF_ENDING) {
                throw new Lose("a use the client may end by itself (a brush, a bundle)");
            }
        }
        return out;
    }

    private static <T> void addIfNew(List<T> list, T value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    /**
     * Every stack the hand may hold now, from the inventory replica's states: the selected hotbar slot's or the off
     * hand's. Where the replica says a stack may have been used up, it is still the stack for a use: what uses it up
     * on the client either ends the use (the finished use's entity event, followed apart) or is one of this tick's
     * own actions ({@link TickInput#handsTouched}); a use started on a stack already gone is only a hypothesis more.
     */
    private List<HandItem> handItems(Hand hand) throws Lose {
        List<HandItem> known = handsNow.get(hand);
        if (known != null) {
            return known;
        }
        if (inventory == null) {
            throw new Lose("held items not followed");
        }
        List<HandItem> out = new ArrayList<>();
        for (ClientInventory.View v : inventory.views()) {
            int slot = hand == Hand.MAIN_HAND ? v.selected() : ClientInventory.HOTBAR;
            if (slot < 0) {
                throw new Lose("the selected hotbar slot is not known");
            }
            Stack stack = hand == Hand.MAIN_HAND ? v.hotbar().get(slot) : v.offhand();
            if (stack == null) {
                throw new Lose("a held item is not known");
            }
            addIfNew(out, handItem(stack));
        }
        if (out.isEmpty()) {
            throw new Lose("no inventory state");
        }
        List<HandItem> items = List.copyOf(out);
        handsNow.put(hand, items);
        return items;
    }

    // --- uses on a block: what they did to the blocks ------------------------------------------------------------------

    /**
     * Resolves this tick's uses on a block for the world replica ({@link ClientWorld#resolveUse}), in order, from the states
     * the tick starts from (not using an item): {@code Minecraft.handleKeybinds} runs {@code startUseItem} before the
     * player's tick and the entities'. A use is resolved where the client's outcome is certain (decompiled 1.21.11:
     * {@code MultiPlayerGameMode.useItemOn}, {@code performUseItemOn}, {@code ItemStack.useOn}, {@code BlockItem.place}):
     * every state may build ({@code mayBuild}: survival or creative); no item may be on cooldown ({@code ItemCooldowns}:
     * none sent); the hand holds, in every inventory state, a stack of one block item that places one state of a full
     * cube ({@link ItemPrototypes.Prototype#places}), not made consumable nor given a cooldown by its patch, of which
     * the uses on a block since it was last set (each takes one at most) leave one; the blocks decide where it goes
     * ({@link ClientWorld#placement}); no entity that keeps blocks out is near; and the player's box is in the way of the new block in every
     * state or in none ({@code Level.isUnobstructed}: entities with {@code blocksBuilding}, the player too). In the way,
     * nothing is placed and the use fails; else the block is placed. Returns null when every use is resolved, else why
     * not (the ones before stay resolved).
     */
    private String resolveBlockUses(TickInput in, List<Hypothesis> starts, ClientWorld world, ClientEntities entities) {
        if (cooldownMayRun || starts.stream().anyMatch(h -> !h.self().cooldowns().isEmpty())) {
            return "an item may be on cooldown";
        }
        if (in.handsAfterBlockUse) {
            return "the hands changed after it in the same tick";
        }
        if (inventory == null) {
            return "held items not followed";
        }
        if (starts.isEmpty()) {
            return "no state that may use an item";
        }
        for (Hypothesis h : starts) {
            if (h.player() == null || !h.self().ticking() || !h.self().positionKnown()) {
                return "the player's position is not known";
            }
            if (h.self().gameMode() != SelfState.SURVIVAL && h.self().gameMode() != SelfState.CREATIVE) {
                return "game mode " + h.self().gameMode();
            }
        }
        List<ClientInventory.View> views = inventory.views();
        int selected = views.isEmpty() ? -1 : views.get(0).selected();
        for (ClientInventory.View v : views) {
            if (v.selected() != selected) {
                selected = -1;
            }
        }
        int[] slots = new int[in.blockUses.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = in.blockUses.get(i).hand() == Hand.MAIN_HAND ? selected : ClientInventory.HOTBAR;
        }
        boolean allNotSneaking = true;
        for (Hypothesis h : starts) {
            if (h.player().keys().shift()) {
                allNotSneaking = false;
                break;
            }
        }
        BlockStates states = world.states();
        for (int i = 0; i < slots.length; i++) {
            ServerboundPacket.UseItemOn use = in.blockUses.get(i);
            int slot = slots[i];
            if (allNotSneaking) {
                String heldForToggle = null;
                if (slot >= 0 && (unresolvedUseSlots & 1 << slot) == 0 && !views.isEmpty()) {
                    boolean consistent = true;
                    String common = null;
                    for (ClientInventory.View v : views) {
                        Stack s = slot == ClientInventory.HOTBAR ? v.offhand() : v.hotbar().get(slot);
                        if (s == null) {
                            consistent = false;
                            break;
                        }
                        String name = s.isEmpty() ? "" : s.item();
                        if (common == null) {
                            common = name;
                        } else if (!common.equals(name)) {
                            consistent = false;
                            break;
                        }
                    }
                    if (consistent) {
                        heldForToggle = common;
                    }
                }
                ClientWorld.PlacementPlan toggle = world.blockToggleForUse(use.sequence(), unanimousHorizontalFacing(in, starts), heldForToggle);
                if (toggle != null) {
                    world.resolveUseMulti(use.sequence(), toggle);
                    in.resolvedBlockUses = i + 1;
                    continue;
                }
            }
            if (slot < 0) {
                return "the selected hotbar slot is not known";
            }
            if ((unresolvedUseSlots & 1 << slot) != 0) {
                return "a use not resolved took from the stack";
            }
            int inTick = 0; // this tick's uses with the slot, and those before this one
            int before = 0;
            for (int j = 0; j < slots.length; j++) {
                if (slots[j] == slot) {
                    inTick++;
                    before += j < i ? 1 : 0;
                }
            }
            String item = null;
            for (ClientInventory.View v : views) {
                Stack stack = slot == ClientInventory.HOTBAR ? v.offhand() : v.hotbar().get(slot);
                int taken = v.blockUses(slot) - inTick + before; // the uses before this one since the stack was set
                if (stack == null || stack.isEmpty() || v.blockUses(slot) < 0 || taken < 0 || stack.count() - taken < 1) {
                    return "the stack in the hand is not known";
                }
                if (item != null && !item.equals(stack.item()) || stack.added().contains(CONSUMABLE) || stack.added().contains(USE_COOLDOWN)) {
                    return "the stack in the hand is not known";
                }
                item = stack.item();
            }
            ItemPrototypes.Prototype prototype = item == null ? null : PROTOTYPES.get(item);
            if (prototype == null || prototype.places() == null) {
                return "not a resolved block item";
            }
            String facing = states.onlyStateOf(prototype.places()) < 0 ? unanimousHorizontalFacing(in, starts) : null;
            ClientWorld.PlacementPlan plan = world.placementPlanForItem(use.sequence(), prototype.places(), facing);
            if (plan == null) {
                return "the blocks or item state do not decide placement";
            }
            dev.whynotac.protocol.packet.BlockPosition at = plan.primary().position();
            int state = plan.primary().state();
            if (entities.mayHaveBuildingBlockerNear(selfId, at.x() - ENTITY_MARGIN, at.y() - ENTITY_MARGIN, at.z() - ENTITY_MARGIN, at.x() + 1 + ENTITY_MARGIN,
                    at.y() + 1 + ENTITY_MARGIN, at.z() + 1 + ENTITY_MARGIN, UNKNOWN_ENTITY_RADIUS)) {
                return "an entity may be in the way";
            }
            Shape shape = states.collisionShape(state);
            int way = -2;
            for (Hypothesis h : starts) {
                int here = inTheWay(h.player().box(), at.x(), at.y(), at.z(), shape);
                if (here < 0 || way != -2 && here != way) {
                    return "the player may or may not be in the way";
                }
                way = here;
            }
            if (way == 1) {
                world.resolveUse(use.sequence(), null, state);
            } else {
                world.resolveUseMulti(use.sequence(), plan);
            }
            in.resolvedBlockUses = i + 1;
        }
        return null;
    }

    /**
     * Resolves {@link ServerboundPacket.UseItem} packets in a tracked on-foot tick before block uses and simulation:
     * non-block-changing uses (shields, bows, food...) clear their global {@code ClientWorld} unlocated markers
     * cleanly, and deterministic bucket uses resolve their placed fluid/air block across all candidate hypotheses.
     */
    private void resolveItemUses(TickInput in, List<Hypothesis> starts, ClientWorld world, boolean onFoot) {
        if (!onFoot || in.handsTouched || inventory == null || in.unmodelled != null || in.moves > 1 || in.teleportReply != null) {
            return;
        }
        for (Object action : in.itemActions) {
            if (!(action instanceof ServerboundPacket.UseItem use)) {
                continue;
            }
            List<HandItem> items;
            try {
                items = handItems(use.hand());
            } catch (Lose ignored) {
                continue;
            }
            boolean mayChangeBlocks = false;
            for (HandItem item : items) {
                if (item.kind() == ItemPrototypes.UseKind.CHANGES_BLOCKS) {
                    mayChangeBlocks = true;
                    break;
                }
            }
            if (!mayChangeBlocks) {
                world.resolveItemUseNoChange(use.sequence());
                continue;
            }
            if (in.itemActions.size() == 1 && in.blockUses.isEmpty() && tryResolveBucketUse(use, in, starts, world)) {
                in.resolvedItemUseSeqs.add(use.sequence());
            }
        }
    }

    private boolean tryResolveBucketUse(ServerboundPacket.UseItem use, TickInput in, List<Hypothesis> starts, ClientWorld world) {
        if (cooldownMayRun || starts.isEmpty()) {
            return false;
        }
        double blockRange = Double.NaN;
        List<Vec3d> eyes = new ArrayList<>(starts.size());
        for (Hypothesis h : starts) {
            SelfState s = h.self();
            PlayerState p = h.player();
            if (p == null || s.using() != null || !s.ticking() || !s.positionKnown() || !s.cooldowns().isEmpty()
                    || (s.gameMode() != SelfState.SURVIVAL && s.gameMode() != SelfState.CREATIVE)
                    || !s.border().known() || !Double.isFinite(s.blockRange()) || s.blockRange() <= 0.0) {
                return false;
            }
            if (Double.isNaN(blockRange)) {
                blockRange = s.blockRange();
            } else if (Double.compare(blockRange, s.blockRange()) != 0) {
                return false;
            }
            float yRot = in.move != null && in.move.hasRotation() ? in.move.yaw() : h.sent().yRot();
            float xRot = in.move != null && in.move.hasRotation() ? in.move.pitch() : h.sent().xRot();
            if (Math.abs(yRot - use.yaw()) > 1.0E-5F || Math.abs(xRot - use.pitch()) > 1.0E-5F) {
                return false;
            }
            Vec3d eye = new Vec3d(p.x(), p.y() + p.pose().eyeHeight(), p.z());
            if (!eyes.contains(eye)) {
                eyes.add(eye);
            }
        }
        List<ClientInventory.View> views = inventory.views();
        if (views.isEmpty()) {
            return false;
        }
        int selected = use.hand() == Hand.OFF_HAND ? ClientInventory.HOTBAR : views.getFirst().selected();
        if (selected < 0) {
            return false;
        }
        String bucketItem = null;
        for (ClientInventory.View v : views) {
            int slot = use.hand() == Hand.OFF_HAND ? ClientInventory.HOTBAR : v.selected();
            if (slot != selected) {
                return false;
            }
            Stack stack = slot == ClientInventory.HOTBAR ? v.offhand() : v.hotbar().get(slot);
            if (stack == null || stack.isEmpty() || v.itemUses(slot) != 1
                    || stack.added().contains(CONSUMABLE) || stack.added().contains(USE_COOLDOWN)) {
                return false;
            }
            if (bucketItem != null && !bucketItem.equals(stack.item())) {
                return false;
            }
            bucketItem = stack.item();
        }
        ClientWorld.Placement placed = world.bucketPlacement(use.sequence(), bucketItem, eyes, use.yaw(), use.pitch(), blockRange);
        if (placed == null) {
            return false;
        }
        dev.whynotac.protocol.packet.BlockPosition at = placed.position();
        for (Hypothesis h : starts) {
            if (!h.self().border().farFrom(at.x(), at.z(), at.x() + 1.0, at.z() + 1.0, BORDER_MARGIN)) {
                return false;
            }
        }
        world.resolveItemUseBlock(use.sequence(), at, placed.state());
        return true;
    }

    private static String unanimousHorizontalFacing(TickInput in, List<Hypothesis> starts) {
        if (in.unmodelled != null || in.moves > 1 || in.teleportReply != null || starts.isEmpty()) {
            return null;
        }
        String facing = null;
        for (Hypothesis h : starts) {
            if (h.sent() == null) {
                return null;
            }
            float yRot = in.move != null && in.move.hasRotation() ? in.move.yaw() : h.sent().yRot();
            String f = horizontalFacing(yRot);
            if (f == null || (facing != null && !facing.equals(f))) {
                return null;
            }
            facing = f;
        }
        return facing;
    }

    private static String horizontalFacing(float yRot) {
        if (!Float.isFinite(yRot)) {
            return null;
        }
        double scaled = (double) yRot * 4.0 / 360.0 + 0.5;
        double frac = scaled - Math.floor(scaled);
        if (Math.min(frac, 1.0 - frac) < 1.0E-3) {
            return null;
        }
        int idx = ((int) Math.floor(scaled)) & 3;
        return switch (idx) {
            case 0 -> "south";
            case 1 -> "west";
            case 2 -> "north";
            default -> "east";
        };
    }

    /** {@code Level.isUnobstructed} for a block collision shape at {@code (x, y, z)} and the player's box. */
    static int inTheWay(Box box, int x, int y, int z, Shape shape) {
        int count = shape.boxCount();
        if (count == 0) {
            return 0;
        }
        int result = 0;
        for (int b = 0; b < count; b++) {
            double ox = Math.min(box.maxX(), x + shape.box(b, 3)) - Math.max(box.minX(), x + shape.box(b, 0));
            double oy = Math.min(box.maxY(), y + shape.box(b, 4)) - Math.max(box.minY(), y + shape.box(b, 1));
            double oz = Math.min(box.maxZ(), z + shape.box(b, 5)) - Math.max(box.minZ(), z + shape.box(b, 2));
            if (!(ox > 0) || !(oy > 0) || !(oz > 0)) {
                continue;
            }
            if (ox >= 1.0E-6 && oy >= 1.0E-6 && oz >= 1.0E-6) {
                return 1;
            }
            result = -1;
        }
        return result;
    }

    /**
     * {@code Level.isUnobstructed} for a full cube at the block and the player's box: 1 in the way, 0 not, -1 too close to
     * tell. The box is only among {@code getEntities}' when it overlaps the cube's bounds ({@code AABB.intersects}, strict),
     * and {@code Shapes.joinIsNotEmpty} then merges coordinates closer than 1e-7 ({@code IndirectMerger}): an overlap of
     * 1e-6 on every axis is one, none on some axis is none.
     */
    static int inTheWay(Box box, int x, int y, int z) {
        double ox = Math.min(box.maxX(), x + 1.0) - Math.max(box.minX(), x);
        double oy = Math.min(box.maxY(), y + 1.0) - Math.max(box.minY(), y);
        double oz = Math.min(box.maxZ(), z + 1.0) - Math.max(box.minZ(), z);
        if (!(ox > 0) || !(oy > 0) || !(oz > 0)) {
            return 0;
        }
        return ox >= 1.0E-6 && oy >= 1.0E-6 && oz >= 1.0E-6 ? 1 : -1;
    }

    /**
     * After every tick: the uses on a block this tracker did not resolve may take anything from their hand's stack, until
     * the inventory replica sees the slot set again (no block use since in any state).
     */
    private void unresolvedUses(TickInput in) {
        List<ClientInventory.View> views = inventory == null ? List.of() : inventory.views();
        for (int slot = 0; slot <= ClientInventory.HOTBAR && unresolvedUseSlots != 0; slot++) {
            if ((unresolvedUseSlots & 1 << slot) != 0 && !views.isEmpty()) {
                boolean set = true;
                for (ClientInventory.View v : views) {
                    set &= v.blockUses(slot) == 0;
                }
                if (set) {
                    unresolvedUseSlots &= ~(1 << slot);
                }
            }
        }
        for (int i = in.resolvedBlockUses; i < in.blockUses.size(); i++) {
            if (in.blockUses.get(i).hand() == Hand.OFF_HAND) {
                unresolvedUseSlots |= 1 << ClientInventory.HOTBAR;
            } else {
                unresolvedUseSlots |= (1 << ClientInventory.HOTBAR) - 1; // whichever hotbar slot was selected
            }
        }
    }

    /** The stack's {@code use_effects} ({@code UseEffects.DEFAULT} where its patch removes them) and use kind. */
    /**
     * {@code Player.isHolding(item)}: the item in the main hand or the off hand, in every state the inventory replica
     * holds (true), in none (false); null when the states differ or a held stack is not known.
     */
    private Boolean holding(String item) {
        if (inventory == null) {
            return null;
        }
        Boolean all = null;
        for (ClientInventory.View v : inventory.views()) {
            int slot = v.selected();
            Stack main = slot < 0 ? null : v.hotbar().get(slot);
            Stack off = v.offhand();
            if (main == null || off == null) {
                return null;
            }
            boolean held = item.equals(main.item()) || item.equals(off.item());
            if (all != null && all != held) {
                return null;
            }
            all = held;
        }
        return all;
    }

    private static HandItem handItem(Stack stack) throws Lose {
        if (stack.isEmpty()) {
            return HandItem.NONE;
        }
        ItemPrototypes.Prototype p = PROTOTYPES.get(stack.item());
        if (p == null) {
            throw new Lose("an item this build does not know");
        }
        if (stack.added().contains(USE_EFFECTS)) {
            throw new Lose("an item with use effects of its own");
        }
        OnFoot.ItemUse effects = stack.removed().contains(USE_EFFECTS) ? OnFoot.ItemUse.DEFAULT : new OnFoot.ItemUse(p.useCanSprint(), p.useSpeed());
        // TridentItem.releaseUsing: with no spin attack strength (no riptide) the client throws it and does not move; a
        // strength not known exactly is a riptide's
        Float spin = p.useKind() == ItemPrototypes.UseKind.RELEASE_MOVES ? stack.spinAttack() : null;
        ItemPrototypes.UseKind kind = spin != null && Float.compare(spin, 0.0F) == 0 ? ItemPrototypes.UseKind.PLAIN : p.useKind();
        return new HandItem(stack.item(), effects, kind, spin);
    }

    /** The hands' stacks now, for the next tick's check of a use the server's flags start (null where not known). */
    private Map<Hand, List<HandItem>> hands() {
        Map<Hand, List<HandItem>> out = new java.util.EnumMap<>(Hand.class);
        for (Hand hand : Hand.values()) {
            try {
                out.put(hand, handItems(hand));
            } catch (Lose e) {
                return null;
            }
        }
        return out;
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

    private Tick untracked(long number, TimelineEvent.Inbound end, TickInput in, long[] w, ClientWorld world, ClientEntities entities,
            SelfState baseBefore, Hypothesis.Sent sentBefore) {
        if (dismountTeleport != null && in.acceptedTeleports.contains(dismountTeleport.teleportId())) {
            dismountAccepted = true;
        }
        if (dismountTeleport != null && dismountAccepted) {
            List<Hypothesis> dismounted = baseReliable ? dismountAnchor(in, w, world, entities, baseBefore, sentBefore) : null;
            if (dismounted != null) {
                dismountTeleport = null;
                dismountAccepted = false;
                dismountTeleportFallClean = false;
                clearTeleportAnchor();
                hypotheses = List.copyOf(borderTicked(dismounted, entities.lastTickRanNormally()));
                lastHands = hands();
                since = end.sequence();
                return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNTRACKED, "anchored: dismounted", hypotheses.size(), since, null, null);
            }
            if (in.move != null) {
                dismountTeleport = null;
                dismountAccepted = false;
                dismountTeleportFallClean = false;
            }
        }
        if (dismountTeleport != null && !dismountAccepted) {
            dismountTeleportFallClean = false;
        }
        if (dismountedPendingTeleport && dismountTeleport == null) {
            dismountFallClean = false;
            if (++dismountWaitTicks > 4) {
                dismountedPendingTeleport = false;
                dismountWaitTicks = 0;
            }
        }
        if (pendingTeleport != null && !in.acceptedTeleports.isEmpty()
                && in.acceptedTeleports.getLast() == pendingTeleport.teleportId()) {
            pendingTeleportAccepted = true;
        }
        if (tpStage1 != null) {
            TpStage1 st1 = tpStage1;
            tpStage1 = null;
            List<Hypothesis> teleported = baseReliable && number == st1.tick() + 1
                    ? tryTeleportStage2(st1, in, w, world, entities) : null;
            if (teleported != null) {
                clearTeleportAnchor();
                hypotheses = List.copyOf(borderTicked(teleported, entities.lastTickRanNormally()));
                lostFromShortAction = false;
                lastHands = hands();
                since = end.sequence();
                return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNTRACKED, "anchored: teleported", hypotheses.size(), since, null,
                        null);
            }
        }
        if (pendingTeleport != null && base.ticking()) {
            ClientboundPacket.PlayerPosition tp = pendingTeleport;
            long tpSeq = pendingTeleportSeq;
            boolean accepted = pendingTeleportAccepted;
            pendingTeleport = null;
            pendingTeleportSeq = Long.MIN_VALUE;
            pendingTeleportAccepted = false;
            if (accepted && baseReliable) {
                tpStage1 = tryTeleportStage1(number, tp, tpSeq, in, w, world, entities, sentBefore);
            }
        }
        Hypothesis settled = baseReliable ? settleAnchor(number, in, w, world, entities) : null;
        boolean fastPostAction = false;
        if (settled == null && baseReliable && lostFromShortAction) {
            settled = fastPostActionAnchor(number, in, w, world, entities);
            fastPostAction = settled != null;
        }
        List<Hypothesis> uses = settled == null ? null : usesAtAnchor(settled);
        int maxSprintTrigger = fastPostAction ? Math.max(0, MAX_SPRINT_WINDOW - keylessTicks) : 0;
        if (uses != null && fastPostAction && (long) uses.size() * 2L * (maxSprintTrigger + 1L) > MAX_HYPOTHESES) {
            uses = null; // wait until keylessTicks shrinks the remaining sprintTriggerTime fan-out within MAX_HYPOTHESES
        }
        if (uses != null) {
            boolean present = settled.self().speed().holdsSprinting();
            SpeedAttribute other = settled.self().speed().setSprinting(!present);
            List<Hypothesis> anchored = new ArrayList<>();
            for (Hypothesis u : uses) {
                for (int trigger = 0; trigger <= maxSprintTrigger; trigger++) {
                    PlayerState p = trigger == 0 ? u.player() : withSprintTrigger(u.player(), trigger);
                    anchored.add(u.withPlayer(p));
                    anchored.add(u.withPlayer(withSprintModifier(p, !present)).withSelf(u.self().withSpeed(other)));
                }
            }
            hypotheses = List.copyOf(borderTicked(anchored, entities.lastTickRanNormally())); // the anchor is the state after this tick
            clearTeleportAnchor();
            lostFromShortAction = false;
            lastHands = hands();
            since = end.sequence();
            String reason = fastPostAction ? "anchored: post-action recovery" : "anchored: standing still";
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNTRACKED, reason, hypotheses.size(), since, null, null);
        }
        List<Hypothesis> running = baseReliable ? runAnchor(number, in, w, world, entities) : null;
        if (running != null) {
            hypotheses = List.copyOf(borderTicked(running, entities.lastTickRanNormally())); // the state after this tick
            clearTeleportAnchor();
            lostFromShortAction = false;
            lastHands = hands();
            since = end.sequence();
            return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNTRACKED, "anchored: running steadily", hypotheses.size(), since, null,
                    null);
        }
        return new Tick(number, end.sequence(), end.nanoTime(), Outcome.UNTRACKED, lostReason, 0, since, null, null);
    }

    /**
     * The anchor with every use the client may have going on: none, or one of each stack the hands may hold (a use
     * started while untracked still slows the player once it walks). Null where a hand is not known, or holds an item
     * whose use the client may end by itself.
     */
    private List<Hypothesis> usesAtAnchor(Hypothesis settled) {
        List<Hypothesis> out = new ArrayList<>(List.of(settled.withSelf(settled.self().withUsing(null))));
        try {
            for (Hand hand : Hand.values()) {
                for (HandItem item : handItems(hand)) {
                    if (item.item() == null) {
                        continue;
                    }
                    if (item.kind() == ItemPrototypes.UseKind.SELF_ENDING) {
                        return null;
                    }
                    out.add(settled.withSelf(settled.self().withUsing(new Using(hand, item.item(), item.effects(), item.kind(), -1))));
                }
            }
        } catch (Lose e) {
            return null;
        }
        return out;
    }

    /**
     * Applies to the base what the client certainly handled before this tick. Returns the state of the last new
     * player those packets made (login, or a respawn keeping no entity data), positioned after its packet, or null.
     */
    private Hypothesis advanceBase(long must) {
        Hypothesis b = new Hypothesis(null, base, baseSent, Hypothesis.ALL_WINDOWS, Hypothesis.AUTO_JUMP_ANY, Hypothesis.ALL_COSINES, baseApplied);
        Hypothesis fresh = null;
        for (long i = baseApplied; i < must; i++) {
            var pendingEvent = pendingAt(i);
            ClientboundPacket packet = pendingEvent.packet();
            int prevVehicle = b.self().vehicle();
            try {
                b = applyBase(b, packet).withApplied(i + 1);
            } catch (Lose e) {
                b = b.withApplied(i + 1);
            }
            if (prevVehicle != -1 && b.self().vehicle() == -1) {
                dismountedPendingTeleport = true;
                dismountFallClean = true;
                dismountWaitTicks = 0;
            } else if (b.self().vehicle() != -1) {
                dismountedPendingTeleport = false;
                dismountFallClean = false;
                dismountWaitTicks = 0;
            }
            if (packet instanceof ClientboundPacket.SetTime time) {
                baseGameTime = time.gameTime(); // setTimeFromServer: counted on by its ticks, from below this
            } else if (packet instanceof ClientboundPacket.Login || packet instanceof ClientboundPacket.Respawn
                    || packet instanceof ClientboundPacket.Untranslatable) {
                baseGameTime = 0L; // a new ClientLevel's game time is 0 until the server's
            }
            switch (packet) {
                case ClientboundPacket.Login ignored -> {
                    baseMayGlide = false;
                    lockedCosineMask = Hypothesis.ALL_COSINES;
                    lostFromShortAction = false;
                }
                case ClientboundPacket.Respawn r when r.dataToKeep() < 0 -> baseMayGlide = true; // what it keeps is not known
                case ClientboundPacket.Respawn r when (r.dataToKeep() & ClientboundPacket.Respawn.KEEP_ENTITY_DATA) == 0 -> baseMayGlide = false;
                case ClientboundPacket.SetEntityData data -> {
                    for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                        if (e.index() == 0) {
                            baseMayGlide = (e.value() & (1 << 7)) != 0;
                            lastBaseFlags = pendingAt(i).sequence();
                        }
                    }
                }
                case ClientboundPacket.Untranslatable ignored -> baseMayGlide = true;
                default -> { }
            }
            boolean newPlayer = packet instanceof ClientboundPacket.Login
                    || packet instanceof ClientboundPacket.Respawn r && r.dataToKeep() >= 0 && (r.dataToKeep() & ClientboundPacket.Respawn.KEEP_ENTITY_DATA) == 0;
            if (packet instanceof ClientboundPacket.Login || packet instanceof ClientboundPacket.Respawn) {
                baseOnFootYRot = 0.0F;
                baseOnFootXRot = 0.0F;
                baseOnFootRotKnown = true;
                clearTeleportAnchor();
            } else if (packet instanceof ClientboundPacket.PlayerPosition teleport) {
                if (b.self().vehicle() == -1 && isAbsoluteZeroDeltaTeleport(teleport)) {
                    pendingTeleport = teleport;
                    pendingTeleportSeq = pendingEvent.sequence();
                    pendingTeleportAccepted = false;
                    tpStage1 = null;
                } else {
                    clearTeleportAnchor();
                }
            } else if (clearsTeleportAnchor(packet, b.self().vehicle())) {
                clearTeleportAnchor();
            }
            if (newPlayer) {
                fresh = b; // as it was right after its packet: the tick applies the rest to it
                baseReliable = true;
                quietSincePosition = false;
                dismountTeleport = null;
                dismountAccepted = false;
                dismountedPendingTeleport = false;
                dismountFallClean = false;
                dismountTeleportFallClean = false;
                dismountWaitTicks = 0;
            } else if (dismountedPendingTeleport
                    && packet instanceof ClientboundPacket.PlayerPosition teleport && b.self().vehicle() == -1
                    && isAbsoluteDismountTeleport(teleport)) {
                dismountTeleport = teleport;
                dismountTeleportSequence = pendingEvent.sequence();
                dismountTeleportFallClean = dismountFallClean;
                dismountAccepted = false;
                dismountedPendingTeleport = false;
                dismountFallClean = false;
                dismountWaitTicks = 0;
            } else if (clearsDismountTeleport(packet, b.self().vehicle())) {
                dismountTeleport = null;
                dismountAccepted = false;
                dismountTeleportFallClean = false;
            }
        }
        base = b.self();
        baseSent = b.sent();
        baseApplied = Math.max(baseApplied, must);
        return fresh;
    }

    private void clearTeleportAnchor() {
        pendingTeleport = null;
        pendingTeleportSeq = Long.MIN_VALUE;
        pendingTeleportAccepted = false;
        tpStage1 = null;
    }

    private static boolean isAbsoluteZeroDeltaTeleport(ClientboundPacket.PlayerPosition teleport) {
        return isAbsoluteDismountTeleport(teleport)
                && teleport.deltaMovement().x() == 0.0
                && teleport.deltaMovement().y() == 0.0
                && teleport.deltaMovement().z() == 0.0;
    }

    private boolean clearsTeleportAnchor(ClientboundPacket packet, int baseVehicle) {
        if (baseVehicle != -1) {
            return true;
        }
        return switch (packet) {
            case ClientboundPacket.Login ignored -> true;
            case ClientboundPacket.Respawn ignored -> true;
            case ClientboundPacket.Untranslatable ignored -> true;
            case ClientboundPacket.PlayerRotation ignored -> true;
            case ClientboundPacket.SetCamera ignored -> true;
            case ClientboundPacket.SetEntityMotion m -> m.entityId() == selfId;
            case ClientboundPacket.Explosion e -> e.playerKnockback() != null;
            case ClientboundPacket.TeleportEntity t -> t.entityId() == selfId;
            case ClientboundPacket.EntityPositionSync s -> s.entityId() == selfId;
            case ClientboundPacket.MoveEntity mv -> mv.entityId() == selfId;
            case ClientboundPacket.MobEffect e -> e.entityId() == selfId;
            case ClientboundPacket.RemoveMobEffect e -> e.entityId() == selfId;
            case ClientboundPacket.EntityEvent e -> e.entityId() == selfId && !NEUTRAL_ENTITY_EVENTS.contains(e.event());
            case ClientboundPacket.GameEvent g -> g.type() == ClientboundPacket.GameEvent.CHANGE_GAME_MODE;
            case ClientboundPacket.SetEntityData d when d.entityId() == selfId -> {
                for (ClientboundPacket.SetEntityData.DataEntry e : d.entries()) {
                    if (e.index() == 0 || e.index() == 5 || e.index() == 6 || e.index() == 8) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    /**
     * Stage 1 of the two-tick ground-settled teleport anchor (P4): verifies that the first ticking tick after an
     * absolute-position, zero-delta {@code PlayerPosition} ran keyless on foot in clean standing space with no nearby
     * pushers/colliders/pistons and sent an exact non-grounded position packet at {@code tp.position()}. Because
     * {@code deltaMovement == ZERO} and {@code Keys.NONE} perform a zero displacement in clean dry space, every
     * pre-teleport {@code pose}, {@code eyeInWater}, {@code swimming}, {@code inPowderSnow}, {@code stuckSpeedMultiplier},
     * {@code support}, {@code onGroundNoBlocks}, {@code onGround}, {@code horizontalCollision} and {@code sprinting}
     * value converges to the exact same post-tick-1 state (up to whether the old supporting block's chunk was still
     * loaded, which Stage 2 proves irrelevant on ground landing).
     */
    private TpStage1 tryTeleportStage1(long number, ClientboundPacket.PlayerPosition tp, long tpSeq, TickInput in, long[] w, ClientWorld world,
            ClientEntities entities, Hypothesis.Sent sentBefore) {
        ServerboundPacket.MovePlayer move = in.move;
        if (move == null || !move.hasPosition() || !tickCertainlyOnFoot || !base.ticking() || w[0] != w[1]
                || base.outOfScope() != null || base.spinAttack() || in.unmodelled != null || !in.blockUses.isEmpty()
                || !in.itemActions.isEmpty() || in.abilities > 0 || in.attacks > 0 || in.stabs > 0 || in.interactions > 0
                || in.moves != 1 || in.inputs > 1 || in.sprints > 1 || in.glideStarts > 0
                || baseMayGlide || lastGlideStart != Long.MIN_VALUE && lastBaseFlags < lastGlideStart
                || spinMayRun > 0 || sentUnsure || !base.speed().sprintLayoutCanonical() || baseSent.sprinting()) {
            return null;
        }
        PlayerState.Keys now = in.input != null ? keys(in.input) : currentKeys;
        if (!now.equals(PlayerState.Keys.NONE) || Double.isNaN(settledYMotion(base.effects()))) {
            return null;
        }
        double tx = tp.position().x();
        double ty = tp.position().y();
        double tz = tp.position().z();
        if (move.x() != tx || move.y() != ty || move.z() != tz || move.onGround() || move.horizontalCollision()) {
            return null;
        }
        if (!move.hasRotation() && !baseOnFootRotKnown) {
            return null;
        }
        float yRot = move.hasRotation() ? move.yaw() : baseOnFootYRot;
        float xRot = move.hasRotation() ? move.pitch() : baseOnFootXRot;
        if (!Float.isFinite(yRot) || !Float.isFinite(xRot)) {
            return null;
        }
        Box box = PlayerState.Pose.STANDING.box(tx, ty, tz);
        if (!base.border().known() && !base.border().farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN)) {
            return null;
        }
        if (world.hasUnlocatedPrediction()
                || world.mayMoveEntitiesIn((int) Math.floor(tx) - MOVER_MARGIN, (int) Math.floor(ty) - MOVER_MARGIN, (int) Math.floor(tz) - MOVER_MARGIN,
                (int) Math.floor(tx) + MOVER_MARGIN, (int) Math.floor(ty + 1.8) + MOVER_MARGIN, (int) Math.floor(tz) + MOVER_MARGIN, tpSeq)) {
            return null;
        }
        View view = new View(world, entities, selfId);
        int cx = (int) Math.floor(tx) >> 4;
        int cz = (int) Math.floor(tz) >> 4;
        if (!view.isLoaded(cx, cz) && (world.dimension() == null || !(ty > world.minY()))) {
            return null; // in an unloaded chunk at or below minY, travelInAir sets vy = 0 instead of -0.1
        }
        try {
            view.check(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), true);
            if (!OnFoot.cleanTeleportSpace(tx, ty, tz, view, base.border().collision(), leatherBoots)) {
                return null;
            }
        } catch (Colliders.Unknown | Lose e) {
            return null;
        }
        Hypothesis.Sent sentAfterTick1 = new Hypothesis.Sent(tx, ty, tz, yRot, xRot, false, false, 0, false, PlayerState.Keys.NONE);
        return new TpStage1(number, tpSeq, tx, ty, tz, sentBefore.input().shift(), sentAfterTick1);
    }

    /**
     * Stage 2 of the two-tick ground-settled teleport anchor (P4): runs on the immediate next tick after Stage 1,
     * verifies stationary landing on solid ground identically from both {@code settledY} (loaded pre-TP support chunk)
     * and {@code -0.1 * 0.98F} (unloaded pre-TP support chunk), resets {@code fallDistance = 0.0} and
     * {@code noJumpDelay = 0}, matches {@link #observe} bit-for-bit, and branches {@code sprintModifier},
     * {@code sprintTriggerTime} and held-item {@code using}.
     */
    private List<Hypothesis> tryTeleportStage2(TpStage1 st1, TickInput in, long[] w, ClientWorld world, ClientEntities entities) {
        if (!tickCertainlyOnFoot || !base.ticking() || w[0] != w[1] || base.outOfScope() != null || base.spinAttack()
                || in.unmodelled != null || !in.blockUses.isEmpty() || !in.itemActions.isEmpty() || in.abilities > 0
                || in.attacks > 0 || in.stabs > 0 || in.interactions > 0 || in.moves > 1 || in.inputs > 1 || in.sprints > 1
                || in.glideStarts > 0 || !in.acceptedTeleports.isEmpty() || in.loaded
                || baseMayGlide || lastGlideStart != Long.MIN_VALUE && lastBaseFlags < lastGlideStart
                || spinMayRun > 0 || sentUnsure || !base.speed().sprintLayoutCanonical() || baseSent.sprinting()) {
            return null;
        }
        PlayerState.Keys now = in.input != null ? keys(in.input) : currentKeys;
        if (!now.equals(PlayerState.Keys.NONE)) {
            return null;
        }
        double settled = settledYMotion(base.effects());
        if (Double.isNaN(settled)) {
            return null;
        }
        Hypothesis.Sent st1Sent = st1.sentAfterTick1();
        float yRot = st1Sent.yRot();
        float xRot = st1Sent.xRot();
        if (in.move != null && in.move.hasRotation()) {
            yRot = in.move.yaw();
            xRot = in.move.pitch();
            if (!Float.isFinite(yRot) || !Float.isFinite(xRot) || yRot == st1Sent.yRot() && xRot == st1Sent.xRot()) {
                return null;
            }
        }
        double tx = st1.tx();
        double ty = st1.ty();
        double tz = st1.tz();
        Box box = PlayerState.Pose.STANDING.box(tx, ty, tz);
        if (!base.border().known() && !base.border().farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN)) {
            return null;
        }
        if (world.hasUnlocatedPrediction()
                || world.mayMoveEntitiesIn((int) Math.floor(tx) - MOVER_MARGIN, (int) Math.floor(ty) - MOVER_MARGIN, (int) Math.floor(tz) - MOVER_MARGIN,
                (int) Math.floor(tx) + MOVER_MARGIN, (int) Math.floor(ty + 1.8) + MOVER_MARGIN, (int) Math.floor(tz) + MOVER_MARGIN, st1.tpSequence())) {
            return null;
        }
        View view = new View(world, entities, selfId);
        boolean present = base.speed().holdsSprinting();
        PlayerState preNormal = new PlayerState(tx, ty, tz, new Vec3d(0.0, settled, 0.0), yRot, xRot, false, false, false, false, false, Double.NaN,
                false, present, st1.previousShift(), PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, false,
                false, false, false, 0);
        PlayerState preUnloaded = withDelta(preNormal, new Vec3d(0.0, -0.1 * 0.98F, 0.0));
        PlayerState landed;
        try {
            view.check(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), true);
            OnFoot.Conditions conditions = new OnFoot.Conditions(base.foodAllowsSprint(), OnFoot.DEFAULT_SPRINT_WINDOW, false, base.speed(),
                    base.effects(), null, false, PlatformCos.X86_64, base.border().collision(), leatherBoots).withSpinAttack(base.spinAttack());
            landed = OnFoot.tick(preNormal, PlayerState.Keys.NONE, view, conditions);
            PlayerState landedFromUnloaded = OnFoot.tick(preUnloaded, PlayerState.Keys.NONE, view, conditions);
            PlayerState again = OnFoot.tick(landed, PlayerState.Keys.NONE, view, conditions);
            if (!landed.equals(landedFromUnloaded) || !landed.equals(again)
                    || landed.x() != tx || landed.y() != ty || landed.z() != tz
                    || !landed.onGround() || landed.horizontalCollision() || landed.fallDistance() != 0.0
                    || landed.deltaMovement().x() != 0.0 || landed.deltaMovement().z() != 0.0 || landed.deltaMovement().y() != settled
                    || landed.sprinting()) {
                return null;
            }
        } catch (Colliders.Unknown | Lose e) {
            return null;
        }
        Hypothesis.Sent sentAfter = observe(landed, st1Sent, PlayerState.Keys.NONE, in);
        if (sentAfter == null) {
            return null;
        }
        Hypothesis baseHyp = new Hypothesis(landed, base, sentAfter, Hypothesis.ALL_WINDOWS,
                base.firstTick() ? Hypothesis.AUTO_JUMP_ON : Hypothesis.AUTO_JUMP_ANY, lockedCosineMask, baseApplied);
        List<Hypothesis> uses = usesAtAnchor(baseHyp);
        if (uses == null) {
            return null;
        }
        int keylessAfterTick2 = keylessTicks + 1;
        int maxSprintTrigger = st1.previousShift() ? 0 : Math.max(0, MAX_SPRINT_WINDOW - keylessAfterTick2);
        if ((long) uses.size() * 2L * (maxSprintTrigger + 1L) > MAX_HYPOTHESES) {
            return null;
        }
        SpeedAttribute other = base.speed().setSprinting(!present);
        List<Hypothesis> anchored = new ArrayList<>();
        for (Hypothesis u : uses) {
            for (int trigger = 0; trigger <= maxSprintTrigger; trigger++) {
                PlayerState p = trigger == 0 ? u.player() : withSprintTrigger(u.player(), trigger);
                anchored.add(u.withPlayer(p));
                anchored.add(u.withPlayer(withSprintModifier(p, !present)).withSelf(u.self().withSpeed(other)));
            }
        }
        return anchored;
    }

    private static boolean isAbsoluteDismountTeleport(ClientboundPacket.PlayerPosition teleport) {
        Set<RelativeFlag> r = teleport.relatives();
        return !r.contains(RelativeFlag.X) && !r.contains(RelativeFlag.Y) && !r.contains(RelativeFlag.Z)
                && !r.contains(RelativeFlag.DELTA_X) && !r.contains(RelativeFlag.DELTA_Y) && !r.contains(RelativeFlag.DELTA_Z)
                && !r.contains(RelativeFlag.ROTATE_DELTA) && !r.contains(RelativeFlag.UNKNOWN)
                && Double.isFinite(teleport.position().x()) && Double.isFinite(teleport.position().y()) && Double.isFinite(teleport.position().z())
                && teleport.deltaMovement().isFinite();
    }

    private boolean clearsDismountTeleport(ClientboundPacket packet, int baseVehicle) {
        if (baseVehicle != -1) {
            return true;
        }
        return switch (packet) {
            case ClientboundPacket.Login ignored -> true;
            case ClientboundPacket.Respawn ignored -> true;
            case ClientboundPacket.Untranslatable ignored -> true;
            case ClientboundPacket.SetCamera ignored -> true;
            case ClientboundPacket.SetEntityMotion m -> m.entityId() == selfId;
            case ClientboundPacket.Explosion e -> e.playerKnockback() != null;
            case ClientboundPacket.TeleportEntity t -> t.entityId() == selfId;
            case ClientboundPacket.EntityPositionSync s -> s.entityId() == selfId;
            case ClientboundPacket.MoveEntity mv -> mv.entityId() == selfId;
            default -> false;
        };
    }

    private List<Hypothesis> dismountAnchor(TickInput in, long[] w, ClientWorld world, ClientEntities entities,
            SelfState baseBefore, Hypothesis.Sent sentBefore) {
        ClientboundPacket.PlayerPosition tp = dismountTeleport;
        if (tp == null || w[0] != w[1] || !base.ticking() || baseBefore.outOfScope() != null || !in.blockUses.isEmpty()
                || in.unmodelled != null || in.abilities > 0 || in.attacks > 0 || in.stabs > 0 || in.interactions > 0
                || in.moves > 1 || in.inputs > 1 || in.sprints > 1 || in.glideStarts > 0
                || baseMayGlide || lastGlideStart != Long.MIN_VALUE && lastBaseFlags < lastGlideStart
                || spinMayRun > 0 || sentUnsure || !baseBefore.speed().sprintLayoutCanonical()) {
            return null;
        }
        double tx = tp.position().x();
        double ty = tp.position().y();
        double tz = tp.position().z();
        if (world.mayMoveEntitiesIn((int) Math.floor(tx) - MOVER_MARGIN, (int) Math.floor(ty) - MOVER_MARGIN, (int) Math.floor(tz) - MOVER_MARGIN,
                (int) Math.floor(tx) + MOVER_MARGIN, (int) Math.floor(ty + 1.8) + MOVER_MARGIN, (int) Math.floor(tz) + MOVER_MARGIN,
                dismountTeleportSequence)) {
            return null;
        }
        float yaw = tp.relatives().contains(RelativeFlag.Y_ROT) ? sentBefore.yRot() + tp.yaw() : tp.yaw();
        float pitch = tp.relatives().contains(RelativeFlag.X_ROT) ? sentBefore.xRot() + tp.pitch() : tp.pitch();
        Hypothesis.Sent startSent = sentBefore;
        View view = new View(world, entities, selfId);
        Set<Hypothesis> anchored = new LinkedHashSet<>();
        Map<StepKey, OnFoot.Step> steps = new HashMap<>();
        List<PlayerState> predictions = new ArrayList<>();
        boolean present = baseBefore.speed().holdsSprinting();
        boolean[] groundOpts = {true, false};
        boolean[] crouchOpts = {false, true};
        boolean[] modOpts = {present, !present};
        double initialFallDistance = dismountTeleportFallClean ? 0.0 : Double.NaN;
        try {
            for (boolean ground : groundOpts) {
                for (boolean crouch : crouchOpts) {
                    PlayerState.Pose pose = crouch ? PlayerState.Pose.CROUCHING : PlayerState.Pose.STANDING;
                    PlayerState cand = new PlayerState(tx, ty, tz, tp.deltaMovement(), yaw, pitch, ground, false, false, ground,
                            ground, initialFallDistance, sentBefore.sprinting(), present, crouch, pose, sentBefore.input(), 0, 0, 0, null,
                            false, Vec3d.ZERO, false, false, false, false, null, 0);
                    Box box = cand.box();
                    if (!baseBefore.border().known() && !baseBefore.border().farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN)) {
                        return null;
                    }
                    view.check(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), true);
                    for (boolean mod : modOpts) {
                        SelfState self = mod == present ? baseBefore : baseBefore.withSpeed(baseBefore.speed().setSprinting(mod));
                        PlayerState p = mod == present ? cand : withSprintModifier(cand, mod);
                        Hypothesis baseHyp = new Hypothesis(p, self, startSent, Hypothesis.ALL_WINDOWS,
                                baseBefore.firstTick() ? Hypothesis.AUTO_JUMP_ON : Hypothesis.AUTO_JUMP_ANY, lockedCosineMask, baseApplied);
                        List<Hypothesis> withUses = usesAtAnchor(baseHyp);
                        if (withUses == null) {
                            continue;
                        }
                        for (Hypothesis u : withUses) {
                            simulate(u, in, view, anchored, steps, predictions);
                            if (anchored.size() > MAX_HYPOTHESES) {
                                return null;
                            }
                        }
                    }
                }
            }
        } catch (Lose e) {
            return null;
        }
        anchored.removeIf(h -> Double.isNaN(h.player().fallDistance()));
        return anchored.isEmpty() ? null : List.copyOf(merged(anchored));
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
        if (rotation) {
            sentUnsure = false; // a rotation and flags the client sent, exactly
        }
        baseSent = new Hypothesis.Sent(position ? move.x() : s.x(), position ? move.y() : s.y(), position ? move.z() : s.z(),
                rotation ? move.yaw() : s.yRot(), rotation ? move.pitch() : s.xRot(), move != null ? move.onGround() : s.onGround(),
                move != null ? move.horizontalCollision() : s.horizontalCollision(), position ? 0 : s.positionReminder() + 1, sprinting, keys);
        base = base.afterFirstTick().tickCooldowns();
    }

    /** The base's view of a packet: as {@link #apply}, without a physics state (only what does not depend on it). */
    private Hypothesis applyBase(Hypothesis b, ClientboundPacket packet) throws Lose {
        return switch (packet) {
            case ClientboundPacket.Login login -> freshPlayer(b, loggedIn(login), login.gameMode(), false, false);
            case ClientboundPacket.Respawn respawn -> {
                int keep = respawn.dataToKeep();
                SelfState into = respawnedInto(b.self(), respawn);
                if (keep >= 0 && (keep & ClientboundPacket.Respawn.KEEP_ENTITY_DATA) == 0) {
                    yield freshPlayer(b, into, respawn.gameMode(), (keep & ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS) != 0, false);
                }
                quietSincePosition = false;
                yield b.withSelf(into.withTicking(false));
            }
            case ClientboundPacket.InitializeBorder ignored -> processedBefore(apply(b, packet));
            case ClientboundPacket.SetBorderLerpSize ignored -> processedBefore(apply(b, packet));
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
                for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                    switch (e.index()) {
                        case 5 -> noGravity = e.value() != 0;
                        default -> { } // the living flags: an anchor tries every use the hands allow
                    }
                }
                yield b.withSelf(b.self().withNoGravity(noGravity));
            }
            case ClientboundPacket.GameEvent event -> {
                if (event.type() == ClientboundPacket.GameEvent.CHANGE_GAME_MODE) {
                    quietSincePosition = false;
                    yield b.withSelf(b.self().withGameMode((int) event.param()));
                }
                Float rain = rainLevel(event);
                yield rain == null ? b : b.withSelf(b.self().withRainLevel(rain));
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

    /** The base processes a packet once the client certainly has: a lerp it started at a tick not known ({@link ClientBorder#processedBefore}). */
    private static Hypothesis processedBefore(Hypothesis b) {
        return b.withSelf(b.self().withBorder(b.self().border().processedBefore()));
    }

    /** The settle anchor (see the class comment) for the tick just sent, or null. */
    private Hypothesis settleAnchor(long number, TickInput in, long[] w, ClientWorld world, ClientEntities entities) {
        ServerboundPacket.MovePlayer move = in.move;
        if (move == null || !move.hasPosition() || !base.ticking() || w[0] != w[1] || in.unmodelled != null || !in.blockUses.isEmpty()
                || in.abilities > 0 || in.attacks > 0 || in.moves > 1) {
            return null;
        }
        PlayerState.Keys now = in.input != null ? keys(in.input) : currentKeys;
        if (lastPositionTick == Long.MIN_VALUE || number - lastPositionTick < SETTLE_MIN_TICKS || !quietSincePosition
                || !now.equals(PlayerState.Keys.NONE) || keylessTicks < SETTLE_KEYLESS_TICKS - 1 || move.x() != lastX || move.y() != lastY || move.z() != lastZ || !move.onGround()
                || move.horizontalCollision() || base.outOfScope() != null) {
            return null;
        }
        if (baseMayGlide || lastGlideStart != Long.MIN_VALUE && lastBaseFlags < lastGlideStart) {
            return null; // the client may still glide: only the server's flags, processed after its start, end it
        }
        if (spinMayRun > 0) {
            return null; // a spin attack may still run, its ticks not known
        }
        if (sentUnsure) {
            return null; // what the client sent last is not known (rotationUnsure)
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
        double settled = settledYMotion(base.effects());
        if (Double.isNaN(settled)) {
            return null;
        }
        PlayerState guess = new PlayerState(x, y, z, new Vec3d(0.0, settled, 0.0), baseSent.yRot(), baseSent.xRot(), true, false, false, true,
                true, 0.0, baseSent.sprinting(), base.speed().holdsSprinting(), false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null,
                false, Vec3d.ZERO, false, false, false, false, null, 0); // the step finds the eye; standing still, it does not swim
        try {
            Box box = guess.box();
            if (!base.border().known() && !base.border().farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN)) {
                return null;
            }
            view.check(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), true);
            OnFoot.Conditions conditions = new OnFoot.Conditions(base.foodAllowsSprint(), OnFoot.DEFAULT_SPRINT_WINDOW, false, base.speed(), base.effects(),
                    null, false, PlatformCos.X86_64, base.border().collision(), leatherBoots).withSpinAttack(base.spinAttack());
            PlayerState first = OnFoot.tick(guess, PlayerState.Keys.NONE, view, conditions);
            PlayerState again = OnFoot.tick(first, PlayerState.Keys.NONE, view, conditions);
            if (!first.equals(again) || first.x() != x || first.y() != y || first.z() != z || !first.onGround() || first.horizontalCollision()
                    || first.deltaMovement().x() != 0.0 || first.deltaMovement().z() != 0.0 || first.deltaMovement().y() != settled
                    || first.sprinting() != baseSent.sprinting()) {
                return null;
            }
            return new Hypothesis(first, base, baseSent, Hypothesis.ALL_WINDOWS, base.firstTick() ? Hypothesis.AUTO_JUMP_ON : Hypothesis.AUTO_JUMP_ANY,
                    lockedCosineMask, baseApplied);
        } catch (Colliders.Unknown | Lose e) {
            return null;
        }
    }

    /**
     * Fast post-action anchor recovery ({@code PH21-04}): after a short bounded loss (Riptide trident, self-ending
     * brush/bundle, or unresolved item use), re-anchors in 1-2 stationary on-ground ticks once the client sends an
     * exact repeated position packet ({@code move.x == lastX && move.y == lastY && move.z == lastZ}) with no keys
     * held. Because vanilla {@code LivingEntity.aiStep} zeroes any horizontal velocity below {@code 0.003} (and clears
     * {@code noJumpDelay} to {@code 0} whenever jump is not pressed) while any velocity {@code >= 0.003} strictly moves
     * the position away from {@code lastX/lastZ}, an exact repeated ground position proves
     * {@code deltaMovement == (0.0, settledY, 0.0)} bit-for-bit. Because {@code LocalPlayer.sprintTriggerTime} can
     * still be non-zero for up to {@link #MAX_SPRINT_WINDOW} ({@code Options.sprintWindow} range {@code 0..10}) ticks
     * after a forward tap (for example against a wall), callers fan out hypotheses across
     * {@code 0..max(0, MAX_SPRINT_WINDOW - keylessTicks)}.
     */
    private Hypothesis fastPostActionAnchor(long number, TickInput in, long[] w, ClientWorld world, ClientEntities entities) {
        ServerboundPacket.MovePlayer move = in.move;
        if (move == null || !move.hasPosition() || !base.ticking() || w[0] != w[1] || in.unmodelled != null || !in.blockUses.isEmpty()
                || in.abilities > 0 || in.attacks > 0 || in.moves > 1) {
            return null;
        }
        PlayerState.Keys now = in.input != null ? keys(in.input) : currentKeys;
        if (lastPositionTick == Long.MIN_VALUE || number - lastPositionTick < 1 || !quietSincePosition
                || !now.equals(PlayerState.Keys.NONE) || keylessTicks < 1
                || move.x() != lastX || move.y() != lastY || move.z() != lastZ || !move.onGround()
                || move.horizontalCollision() || base.outOfScope() != null) {
            return null;
        }
        if (baseMayGlide || lastGlideStart != Long.MIN_VALUE && lastBaseFlags < lastGlideStart
                || spinMayRun > 0 || sentUnsure || !base.speed().sprintLayoutCanonical()) {
            return null;
        }
        double x = move.x();
        double y = move.y();
        double z = move.z();
        if (world.mayMoveEntitiesIn((int) Math.floor(x) - MOVER_MARGIN, (int) Math.floor(y) - MOVER_MARGIN, (int) Math.floor(z) - MOVER_MARGIN,
                (int) Math.floor(x) + MOVER_MARGIN, (int) Math.floor(y + 1.8) + MOVER_MARGIN, (int) Math.floor(z) + MOVER_MARGIN, lastPositionSequence)) {
            return null;
        }
        double settled = settledYMotion(base.effects());
        if (Double.isNaN(settled)) {
            return null;
        }
        View view = new View(world, entities, selfId);
        PlayerState guess = new PlayerState(x, y, z, new Vec3d(0.0, settled, 0.0), baseSent.yRot(), baseSent.xRot(), true, false, false, true,
                true, 0.0, baseSent.sprinting(), base.speed().holdsSprinting(), false, PlayerState.Pose.STANDING, PlayerState.Keys.NONE, 0, 0, 0, null,
                false, Vec3d.ZERO, false, false, false, false, null, 0);
        try {
            Box box = guess.box();
            if (!base.border().known() && !base.border().farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN)) {
                return null;
            }
            view.check(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), true);
            OnFoot.Conditions conditions = new OnFoot.Conditions(base.foodAllowsSprint(), OnFoot.DEFAULT_SPRINT_WINDOW, false, base.speed(), base.effects(),
                    null, false, PlatformCos.X86_64, base.border().collision(), leatherBoots).withSpinAttack(base.spinAttack());
            PlayerState first = OnFoot.tick(guess, PlayerState.Keys.NONE, view, conditions);
            PlayerState again = OnFoot.tick(first, PlayerState.Keys.NONE, view, conditions);
            if (!first.equals(again) || first.x() != x || first.y() != y || first.z() != z || !first.onGround() || first.horizontalCollision()
                    || first.deltaMovement().x() != 0.0 || first.deltaMovement().z() != 0.0 || first.deltaMovement().y() != settled
                    || first.sprinting() != baseSent.sprinting()) {
                return null;
            }
            return new Hypothesis(first, base, baseSent, Hypothesis.ALL_WINDOWS, base.firstTick() ? Hypothesis.AUTO_JUMP_ON : Hypothesis.AUTO_JUMP_ANY,
                    lockedCosineMask, baseApplied);
        } catch (Colliders.Unknown | Lose e) {
            return null;
        }
    }

    /**
     * Whether this tick continues the run on record ({@link #runAnchor}): one position packet without a horizontal
     * collision (on the ground or in a jump), sent the tick after the last one with nothing in between that the
     * client may have been moved or changed by (no server packet that ends the settle anchor's quiet, no action of its
     * own), the same keys (no shift; the jump key may be held), rotation and sprinting as the run, the player ticking.
     */
    private boolean steadyStep(long number, TickInput in) {
        ServerboundPacket.MovePlayer move = in.move;
        if (runPositions.isEmpty() || move == null || !move.hasPosition() || in.moves != 1 || move.horizontalCollision()
                || lastPositionTick != number - 1 || !quietSincePosition || !base.ticking() || in.unmodelled != null || !in.blockUses.isEmpty()
                || in.abilities > 0 || in.attacks > 0 || in.stabs > 0 || in.interactions > 0 || !in.itemActions.isEmpty() || in.handsTouched
                || in.sprint != null || in.glideStarts > 0 || in.loaded) {
            return false;
        }
        PlayerState.Keys now = in.input != null ? keys(in.input) : currentKeys;
        return now.equals(runKeys) && !now.shift()
                && Float.floatToIntBits(baseSent.yRot()) == Float.floatToIntBits(runYRot) && Float.floatToIntBits(baseSent.xRot()) == Float.floatToIntBits(runXRot)
                && baseSent.sprinting() == runSprinting;
    }

    /**
     * The run anchor: a player that ran on the ground with the same keys, rotation and sprinting for {@link #RUN_MIN_TICKS}
     * ticks or more, nothing in its way. There the client's tick changes the horizontal motion by {@code v -> (v + a) * g}
     * (moveRelative's input {@code a}, the block's friction {@code g}, decompiled 1.21.11 {@code LivingEntity.travel}),
     * whatever the position: a map of doubles that rounding keeps monotone, which shrinks every interval by {@code g}. The
     * motions that take the first sent position to the second are an interval (found by bisection on the engine's own
     * step); its two ends, stepped from each sent position (each of them consistent with the next one), become one double
     * when every motion in between does, and that is the client's. From there the engine must give the next
     * {@link #RUN_CONFIRM_TICKS} or more sent positions bit for bit; the state after this tick is the anchor, once for
     * each place the sprinting modifier may have in the client's attributes that fits. Null where any of that fails.
     */
    private List<Hypothesis> runAnchor(long number, TickInput in, long[] w, ClientWorld world, ClientEntities entities) {
        if (runPositions.size() < RUN_MIN_TICKS || !steadyStep(number, in) || w[0] != w[1] || base.outOfScope() != null || baseMayGlide
                || lastGlideStart != Long.MIN_VALUE && lastBaseFlags < lastGlideStart || spinMayRun > 0 || sentUnsure
                || !base.speed().sprintLayoutCanonical() || runLastAttempt != Long.MIN_VALUE && number - runLastAttempt < RUN_MIN_TICKS) {
            return null;
        }
        runLastAttempt = number;
        double settled = settledYMotion(base.effects());
        if (Double.isNaN(settled)) {
            return null;
        }
        List<double[]> path = new ArrayList<>(runPositions);
        path.add(new double[] {in.move.x(), in.move.y(), in.move.z(), Double.NaN, in.move.onGround() ? 1 : 0});
        while (!path.isEmpty() && path.get(0)[4] == 0) {
            path.remove(0); // the run starts where the client stood on the ground: its vertical motion is known there
        }
        if (path.size() < RUN_MIN_TICKS) {
            return null;
        }
        View view = new View(world, entities, selfId);
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        double y = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] p : path) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            y = Math.min(y, p[1]);
            maxY = Math.max(maxY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        try {
            if (!base.border().known() && !base.border().farFrom(minX - 0.3, minZ - 0.3, maxX + 0.3, maxZ + 0.3, BORDER_MARGIN)) {
                return null;
            }
            view.check(minX - 0.3, y, minZ - 0.3, maxX + 0.3, maxY + 1.8, maxZ + 0.3, true);
        } catch (Lose e) {
            return null;
        }
        if (world.mayMoveEntitiesIn((int) Math.floor(minX) - MOVER_MARGIN, (int) Math.floor(y) - MOVER_MARGIN, (int) Math.floor(minZ) - MOVER_MARGIN,
                (int) Math.floor(maxX) + MOVER_MARGIN, (int) Math.floor(maxY + 1.8) + MOVER_MARGIN, (int) Math.floor(maxZ) + MOVER_MARGIN,
                (long) path.get(0)[3])) {
            return null;
        }
        boolean present = base.speed().holdsSprinting();
        List<Hypothesis> out = new ArrayList<>();
        for (boolean modifier : new boolean[] {present, !present}) {
            SelfState self = modifier == present ? base : base.withSpeed(base.speed().setSprinting(!present));
            OnFoot.Conditions conditions = new OnFoot.Conditions(self.foodAllowsSprint(), OnFoot.DEFAULT_SPRINT_WINDOW, false, self.speed(),
                    self.effects(), null, false, PlatformCos.X86_64, self.border().collision(), leatherBoots).withSpinAttack(self.spinAttack());
            PlayerState anchor = runFrom(path, settled, modifier, conditions, view);
            if (anchor != null) {
                out.add(new Hypothesis(anchor, self, baseSent, Hypothesis.ALL_WINDOWS, self.firstTick() ? Hypothesis.AUTO_JUMP_ON : Hypothesis.AUTO_JUMP_ANY,
                        lockedCosineMask, baseApplied));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** {@link #runAnchor} for one place of the sprinting modifier: the state after the last sent position's tick, or null. */
    private PlayerState runFrom(List<double[]> path, double settledY, boolean modifier, OnFoot.Conditions conditions, View view) {
        try {
            double[] p0 = path.get(0);
            double[] p1 = path.get(1);
            runNext = p1;
            // the motions at the first position whose step ends at the second: [lo, hi] on each axis (the step is monotone);
            // the step moves by the motion plus moveRelative's input (well under half a block), so it is within half a block
            // of the distance between them
            double dx = p1[0] - p0[0];
            double dz = p1[2] - p0[2];
            // LivingEntity.aiStep (a player): a horizontal motion under 0.003 is zeroed whole, both axes together; away from
            // that disc each axis moves on its own. The faster axis is searched first, the other at its distance; the
            // slower then at the faster's motion found, which keeps the disc away
            int first = Math.abs(dx) >= Math.abs(dz) ? 0 : 1;
            double[] lo = new double[2];
            double[] hi = new double[2];
            double[] d = {dx, dz};
            for (int axis : new int[] {first, 1 - first}) {
                double other = axis == first ? d[1 - axis] : lo[1 - axis];
                double low = bisect(axis, other, p0, p1, settledY, modifier, conditions, view, d[axis] - 0.5, d[axis] + 0.5, true);
                double high = bisect(axis, other, p0, p1, settledY, modifier, conditions, view, d[axis] - 0.5, d[axis] + 0.5, false);
                if (Double.isNaN(low) || Double.isNaN(high) || low > high) {
                    return null;
                }
                lo[axis] = low;
                hi[axis] = high;
            }
            if (!clearOfZeroing(lo, hi)) {
                return null;
            }
            // the two ends, each stepped by the engine from every sent position with its own vertical state (which the
            // horizontal motion does not change: no collision, away from the zeroing): they must agree on everything but
            // the horizontal, the client's height and ground flag among it, and bracket each sent position
            PlayerState a = runGuess(p0, lo[0], lo[1], settledY, modifier);
            PlayerState b = runGuess(p0, hi[0], hi[1], settledY, modifier);
            for (int k = 1; k < path.size(); k++) {
                double[] to = path.get(k);
                a = OnFoot.tick(a, runKeys, view, conditions);
                b = OnFoot.tick(b, runKeys, view, conditions);
                if (!sameButHorizontal(a, b) || a.horizontalCollision() || a.y() != to[1] || a.onGround() != (to[4] != 0)
                        || a.sprinting() != runSprinting || a.x() > to[0] || b.x() < to[0] || a.z() > to[2] || b.z() < to[2]) {
                    return null; // no motion in the interval sends this position
                }
                lo = new double[] {a.deltaMovement().x(), a.deltaMovement().z()};
                hi = new double[] {b.deltaMovement().x(), b.deltaMovement().z()};
                if (lo[0] > hi[0] || lo[1] > hi[1] || !clearOfZeroing(lo, hi)) {
                    return null; // not monotone after all, or the axes no longer on their own
                }
                a = at(a, to); // where the client was
                b = at(b, to);
                if (lo[0] == hi[0] && lo[1] == hi[1]) {
                    // the motion at sent position k is known: the engine from there must give every later one exactly
                    if (path.size() - 1 - k < RUN_CONFIRM_TICKS) {
                        return null;
                    }
                    PlayerState state = a;
                    for (int j = k + 1; j < path.size(); j++) {
                        state = OnFoot.tick(state, runKeys, view, conditions);
                        double[] sent = path.get(j);
                        if (state.x() != sent[0] || state.y() != sent[1] || state.z() != sent[2] || state.onGround() != (sent[4] != 0)
                                || state.horizontalCollision()) {
                            return null;
                        }
                    }
                    return state;
                }
            }
            return null; // not narrowed to one motion within the run
        } catch (Colliders.Unknown e) {
            return null;
        }
    }

    /**
     * The lowest ({@code lowest}) or highest motion on one axis (0: x, 1: z) in {@code [lo, hi]}, the other axis's motion
     * {@code other}, whose step from {@code from} ends at {@code to} on that axis, by bisection over the doubles' order
     * (the step's end only grows with the motion); NaN where a step leaves the ground or collides, or none ends there.
     */
    private double bisect(int axis, double other, double[] from, double[] to, double settledY, boolean modifier, OnFoot.Conditions conditions,
            View view, double lo, double hi, boolean lowest) throws Colliders.Unknown {
        long a = ordered(lo);
        long b = ordered(hi);
        double want = to[axis == 0 ? 0 : 2];
        for (int i = 0; i < 70 && a < b; i++) {
            // the floor (lowest) or ceiling (highest) of the midpoint, without overflow
            long m = lowest ? (a & b) + ((a ^ b) >> 1) : (a | b) - ((a ^ b) >> 1);
            double v = unordered(m);
            PlayerState s = axis == 0 ? runStep(from, v, other, settledY, modifier, conditions, view)
                    : runStep(from, other, v, settledY, modifier, conditions, view);
            if (s == null) {
                return Double.NaN;
            }
            double at = axis == 0 ? s.x() : s.z();
            if (lowest) {
                if (at >= want) {
                    b = m;
                } else {
                    a = m + 1;
                }
            } else {
                if (at <= want) {
                    a = m;
                } else {
                    b = m - 1;
                }
            }
        }
        double found = unordered(lowest ? a : b);
        PlayerState s = axis == 0 ? runStep(from, found, other, settledY, modifier, conditions, view)
                : runStep(from, other, found, settledY, modifier, conditions, view);
        return s != null && (axis == 0 ? s.x() : s.z()) == want ? found : Double.NaN;
    }

    /** No horizontal motion in the box {@code [lo, hi]} is one the client zeroes ({@code horizontalDistanceSqr() < 9.0E-6}). */
    static boolean clearOfZeroing(double[] lo, double[] hi) {
        double nx = lo[0] > 0 ? lo[0] : hi[0] < 0 ? -hi[0] : 0.0; // the nearest to zero on each axis
        double nz = lo[1] > 0 ? lo[1] : hi[1] < 0 ? -hi[1] : 0.0;
        return nx * nx + nz * nz >= 9.0E-6 * 1.001;
    }

    /**
     * The engine's tick from the run's first sent position (on the ground) with this motion; null where it collides, or
     * does not give the next sent position's height and ground flag.
     */
    private PlayerState runStep(double[] from, double vx, double vz, double settledY, boolean modifier, OnFoot.Conditions conditions, View view)
            throws Colliders.Unknown {
        PlayerState s = OnFoot.tick(runGuess(from, vx, vz, settledY, modifier), runKeys, view, conditions);
        double[] next = runNext;
        return !s.horizontalCollision() && s.y() == next[1] && s.onGround() == (next[4] != 0) && s.sprinting() == runSprinting ? s : null;
    }

    /** The second sent position of the run being searched ({@link #runStep}). */
    private double[] runNext;

    /** The state at another position, everything else kept. */
    private static PlayerState at(PlayerState p, double[] position) {
        return new PlayerState(position[0], position[1], position[2], p.deltaMovement(), p.yRot(), p.xRot(), p.onGround(), p.horizontalCollision(),
                p.minorHorizontalCollision(), p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), p.sprintModifier(),
                p.crouching(), p.pose(), p.keys(), p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(), p.onGroundNoBlocks(),
                p.stuckSpeedMultiplier(), p.swimming(), p.eyeInWater(), p.fallFlying(), p.inPowderSnow(), p.inWater(), p.spinAttackTicks());
    }

    /** Equal but for the horizontal position and motion. */
    static boolean sameButHorizontal(PlayerState a, PlayerState b) {
        return flat(a).equals(flat(b));
    }

    private static PlayerState flat(PlayerState p) {
        PlayerState q = at(p, new double[] {0.0, p.y(), 0.0});
        return withDelta(q, new Vec3d(0.0, p.deltaMovement().y(), 0.0));
    }

    /** A state at a sent position, on the ground, with this motion and the run's keys, rotation and sprinting (the step finds the rest). */
    private PlayerState runGuess(double[] at, double vx, double vz, double settledY, boolean modifier) {
        return new PlayerState(at[0], at[1], at[2], new Vec3d(vx, settledY, vz), runYRot, runXRot, true, false, false, true, true, 0.0, runSprinting,
                modifier, false, PlayerState.Pose.STANDING, runKeys, 0, 0, 0, null, false, Vec3d.ZERO, false, false, false, false, null, 0);
    }

    /** Doubles in their numeric order as longs (and back): bisection over every double between two. */
    static long ordered(double d) {
        long bits = Double.doubleToRawLongBits(d == 0.0 ? 0.0 : d);
        return bits < 0 ? Long.MIN_VALUE - bits : bits;
    }

    static double unordered(long o) {
        return Double.longBitsToDouble(o < 0 ? Long.MIN_VALUE - o : o);
    }

    /**
     * The vertical motion of a player standing on the ground at the end of a tick: gravity (slow falling's while not
     * rising) after the landing cleared it, times the drag. NaN under levitation, which lifts the player off.
     */
    static double settledYMotion(OnFoot.Effects effects) {
        if (effects.levitation() > 0) {
            return Double.NaN;
        }
        return effects.slowFalling() ? (0.0 - Math.min(0.08, 0.01)) * 0.98F : SETTLED_Y_MOTION;
    }

    private static Hypothesis withSprintModifierHyp(Hypothesis h, boolean present) {
        return h.withPlayer(withSprintModifier(h.player(), present));
    }

    /** The settle anchor's record of the position packets and of the keys, from what the client sent. */
    private void settleBookkeeping(long number, TimelineEvent.Inbound end, TickInput in) {
        boolean steady = steadyStep(number, in); // before this tick's flags and keys are taken in
        if (in.unmodelled != null || !in.blockUses.isEmpty() || in.abilities > 0 || in.attacks > 0 || !base.ticking()) {
            quietSincePosition = false;
        }
        if (in.input != null) {
            currentKeys = keys(in.input);
        }
        ServerboundPacket.MovePlayer sentMove = in.move;
        if (!steady) {
            runPositions.clear();
            runLastAttempt = Long.MIN_VALUE;
        }
        if (sentMove != null && sentMove.hasPosition()) {
            if (runPositions.isEmpty()) {
                runKeys = currentKeys;
                runYRot = baseSent.yRot();
                runXRot = baseSent.xRot();
                runSprinting = baseSent.sprinting();
            }
            // a sequence fits a double; the ground flag the client sent, 1 or 0
            runPositions.addLast(new double[] {sentMove.x(), sentMove.y(), sentMove.z(), end.sequence(), sentMove.onGround() ? 1 : 0});
            while (runPositions.size() > RUN_MAX_TICKS + 1) {
                runPositions.pollFirst();
            }
        } else {
            runPositions.clear();
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

        /** A block that may still change reads as it is where every state it may hold moves the player alike. */
        @Override
        public int blockState(int x, int y, int z) {
            int state = world.settledBlockState(x, y, z);
            if (state != ClientWorld.UNKNOWN) {
                return state;
            }
            int[] possible = world.possibleStates(x, y, z);
            // any of them (the client's own placement may hold one the server's state is not yet)
            return possible != null && OnFoot.sameForMovement(world.states(), possible) ? possible[possible.length - 1] : ClientWorld.UNKNOWN;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return world.isLoaded(chunkX, chunkZ) && world.settled(chunkX << 4, world.minY(), chunkZ << 4);
        }

        /** The level's lava speed: its dimension's, from the login or the respawn that made it. */
        @Override
        public Boolean fastLava() {
            return world.dimension() == null ? null : world.dimension().fastLava();
        }

        /**
         * No entity that collides (or pushes otherwise than living entities in their tick, which {@link #pushed} follows), no
         * piston, the world border far, around everywhere the player may be this tick.
         */
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
                ClientBorder border = h.self().border();
                if (!border.known() && !border.farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN + reach)) {
                    throw new Lose("near a world border not known exactly");
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
            check(minX, minY, minZ, maxX, maxY, maxZ, false);
        }

        /**
         * {@code LivingEntity.checkAutoSpinAttack} with no entity at all in the box the spin attack sweeps: one there may
         * meet it (a living one ends it and bounces the player back) or keep a horizontal collision from ending it.
         */
        void checkSpinAttack(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) throws Lose {
            if (entities.mayHaveEntityNear(self, minX - ENTITY_MARGIN, minY - ENTITY_MARGIN, minZ - ENTITY_MARGIN, maxX + ENTITY_MARGIN,
                    maxY + ENTITY_MARGIN, maxZ + ENTITY_MARGIN, UNKNOWN_ENTITY_RADIUS)) {
                throw new Lose("an entity near a spin attack");
            }
        }

        /**
         * {@link #checkSurroundings} for one box that may be swept this tick; {@code living}: living entities too (the
         * anchor, which follows no push).
         */
        void check(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean living) throws Lose {
            if (living ? entities.mayHavePusherNear(self, minX - ENTITY_MARGIN, minY - ENTITY_MARGIN, minZ - ENTITY_MARGIN, maxX + ENTITY_MARGIN,
                    maxY + ENTITY_MARGIN, maxZ + ENTITY_MARGIN, UNKNOWN_ENTITY_RADIUS)
                    : entities.mayHaveColliderNear(self, minX - ENTITY_MARGIN, minY - ENTITY_MARGIN, minZ - ENTITY_MARGIN, maxX + ENTITY_MARGIN,
                    maxY + ENTITY_MARGIN, maxZ + ENTITY_MARGIN, UNKNOWN_ENTITY_RADIUS)) {
                throw new Lose(living ? "an entity that may push the player is near" : "an entity that may collide with the player is near");
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
                p.noJumpDelay(), sprintTrigger, autoJump, p.support(), p.onGroundNoBlocks(), p.stuckSpeedMultiplier(), p.swimming(), p.eyeInWater(),
                p.fallFlying(), p.inPowderSnow(), p.inWater(), p.spinAttackTicks());
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
