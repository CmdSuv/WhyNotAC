package dev.whynotac.world;

import dev.whynotac.math.vanilla.PositionCodec;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The other entities one client holds, rebuilt in the order the client processes them, tick by tick.
 *
 * <h2>When the client processes what</h2>
 * The 1.21.11 client handles packets only in {@code PacketProcessor.processQueuedPackets} at the start of
 * a frame (until the queue is empty), then runs that frame's ticks; each tick ticks the entities
 * ({@code ClientLevel.tickEntities}) and ends with one {@code ServerboundClientTickEnd}. Pongs are sent while
 * handling pings, so the client's own send order places every ping between two ticks. At any moment the
 * client has processed exactly a prefix of what it was sent: at least everything before the last ping it
 * answered, at most everything before the first ping it has not answered yet. Packets in between (the
 * <em>window</em>) may or may not have been processed before a tick. Pings around every flush leave the
 * window empty almost always; when it is not, this replica follows every possibility.
 *
 * <h2>Candidates</h2>
 * Each entity has one or more candidate states, each exactly what the client would hold for one possible
 * order of the window's packets and its ticks. Every rule is the client's own (decompiled 1.21.11):
 * {@code VecDeltaCodec}, {@code InterpolationHandler}, {@code Entity.snapTo}/{@code absSnapTo},
 * {@code LivingEntity.aiStep} and its head rotation, chunk-ticking ({@code TransientEntitySectionManager}),
 * the frozen tick rate, passengers, the player list. Where the client's choice depends on something this
 * replica does not model (a collision test inside interpolation), both outcomes are kept. Candidates that
 * reach the same targets are merged into one with a radius that bounds every member: with radius 0 a
 * candidate is bit-exact. Past {@link #MAX_CANDIDATES} the entity is lost until the server adds it again.
 *
 * <h2>What is not known</h2>
 * Positions of passengers (placed by their vehicle), of a vehicle this player may control, and of types
 * the client moves itself ({@link ClientMotion}) are reported as lost; a boat's state at the moment its player
 * mounts it is kept for the movement check ({@link #mounted}). After lost events ({@code Gap}) or
 * an entity packet WhyNotAC could not read, entities are unknown until the server adds them again.
 *
 * <p>Confined to the session's analysis thread.
 */
public final class ClientEntities {

    /** Candidate states one entity may have before it is given up as lost. */
    public static final int MAX_CANDIDATES = 16;
    /** {@code distanceToSqr > 4096}: the client snaps instead of interpolating. */
    private static final double SNAP_DISTANCE_SQR = 4096.0;
    private static final double HORIZONTAL_LIMIT = 3.0E7;
    private static final int MAX_TRACKED_PINGS = 4096;
    /** Ops kept per entity while the client does not answer pings; beyond it the entity is lost. */
    private static final int MAX_PENDING_OPS = 4096;
    private static final int NONE = -1;
    /** {@code GameType} ids. */
    private static final int GAME_MODE_SURVIVAL = 0;
    private static final int GAME_MODE_SPECTATOR = ClientboundPacket.PlayerInfoUpdate.SPECTATOR;
    private static final int POSE_STANDING = ClientboundPacket.EntityPose.STANDING.ordinal();
    private static final int POSE_SLEEPING = ClientboundPacket.EntityPose.SLEEPING.ordinal();
    /** {@code Entity.DATA_SHARED_FLAGS_ID}, {@code Entity.DATA_NO_GRAVITY} and {@code Entity.DATA_POSE}. */
    private static final int DATA_FLAGS = 0;
    private static final int DATA_NO_GRAVITY = 5;
    /** The attribute the size scales with ({@code Attributes.SCALE}). */
    static final String SCALE_ATTRIBUTE = "minecraft:scale";
    private static final int DATA_POSE = 6;
    /** {@code AbstractHorse.DATA_ID_FLAGS}: after {@code Entity}'s eight, {@code LivingEntity}'s seven, {@code Mob}'s and {@code AgeableMob}'s one each. */
    private static final int DATA_HORSE_FLAGS = 17;
    /** A pig's first passenger ({@code Op.Rider}): none, the client's own player, another player, another entity. */
    private static final int RIDER_NONE = 0;
    private static final int RIDER_SELF = 1;
    private static final int RIDER_PLAYER = 2;
    private static final int RIDER_OTHER = 3;
    /** {@code Pig.DATA_BOOST_TIME} and {@code Strider.DATA_BOOST_TIME}: at the same place as a horse's flags (an int). */
    private static final int DATA_PIG_BOOST = 17;
    /** {@code Mob.DATA_MOB_FLAGS_ID}: bit 1 is NoAI ({@code isNoAi}). */
    private static final int DATA_MOB_FLAGS = 15;
    /** The horses whose state the replica keeps for their player's mount ({@link #horseMounted}). */
    public static final Set<String> HORSE_TYPES = ClientMotion.HORSES;
    /** The pig ({@code Pig}): its player steers it with a carrot on a stick, its state kept for the mount as a horse's. */
    public static final String PIG = ClientMotion.PIG;
    /** The strider ({@code Strider}): its player steers it with a warped fungus on a stick, kept as the pig is. */
    public static final String STRIDER = ClientMotion.STRIDER;
    /**
     * Not an attribute: a strider's movement speed with the suffocating modifier ({@code Strider.SUFFOCATING_MODIFIER},
     * which its client adds and removes itself each tick); {@link #HORSE_ATTRIBUTES}' movement speed is its value without.
     */
    public static final String SUFFOCATING_SPEED = "whynotac:suffocating_movement_speed";
    /** {@code Strider.SUFFOCATING_MODIFIER}: {@code -0.34F}, {@code ADD_MULTIPLIED_BASE}. */
    private static final ClientboundPacket.UpdateAttributes.Modifier SUFFOCATING_MODIFIER = new ClientboundPacket.UpdateAttributes.Modifier(
            "minecraft:suffocating", -0.34F, ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_BASE);
    /**
     * The types besides boats and rafts whose controlling passenger may be a player ({@code getControllingPassenger},
     * decompiled 1.21.11: {@code AbstractHorse}, {@code AbstractNautilus}, {@code Pig}, {@code Strider} and
     * {@code HappyGhast} override it so; {@code Entity}'s is null and {@code Mob}'s only ever a mob).
     */
    private static final Set<String> PLAYER_CONTROLLED = Set.of("minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:skeleton_horse", "minecraft:zombie_horse", "minecraft:camel", "minecraft:camel_husk", "minecraft:llama",
            "minecraft:trader_llama", "minecraft:nautilus", "minecraft:zombie_nautilus", PIG, "minecraft:strider", "minecraft:happy_ghast");

    /**
     * Whether a vehicle of {@code type} may be controlled by a player: only on one does a vanilla client send what its
     * controller sends ({@code MoveVehicle}, {@code PaddleBoat}, a riding jump). True for a type not known.
     */
    public static boolean playerMayControl(String type) {
        return type == null || ClientMotion.interpolatesOnly(type) || PLAYER_CONTROLLED.contains(type);
    }
    /** The attributes a ridden horse's tick reads: {@code getRiddenSpeed}, {@code getJumpPower}, {@code maxUpStep}, {@code getGravity}. */
    public static final Set<String> HORSE_ATTRIBUTES = Set.of("minecraft:movement_speed", "minecraft:jump_strength", "minecraft:step_height",
            "minecraft:gravity");
    /** {@code LocalPlayer.sendPosition}'s threshold: the client is within this of the position it last sent. */
    private static final double UNSENT = 2.0E-4;
    /**
     * {@code EntityType.FIREWORK_ROCKET} and its {@code DATA_ATTACHED_TO_TARGET} (an {@code OptionalInt}: the entity it
     * is attached to; {@code FireworkRocketEntity} defines it second after {@code Entity}'s eight).
     */
    public static final String FIREWORK_ROCKET = "minecraft:firework_rocket";
    public static final int DATA_ROCKET_ATTACHED = 9;
    /** Not in the packet ({@code Op.Data.attached}). */
    private static final int UNSET = Integer.MIN_VALUE;
    /** A rocket's target set while the candidate was lost: not known. */
    private static final int ATTACHED_UNKNOWN = Integer.MIN_VALUE + 1;

    /** Whether the client has an entity with a given id. */
    public enum Existence { PRESENT, ABSENT, MAYBE, UNKNOWN }

    private final EntityTypes types;
    private final EntitySizes sizes = EntitySizes.vanilla();

    // --- server order: what the client will hold once it has processed everything sent so far ---------
    private boolean level;
    /** Nothing was lost since the level began (or the client has no level): an id without a track is certainly absent. */
    private boolean complete;
    private boolean playerInfoComplete;
    private int selfId = NONE;
    /** The sequence of the packet that made the client's current player ({@code Login}, {@code Respawn}): it joined the tick list then. */
    private long selfCreated = Long.MIN_VALUE;
    private String whyNotKnown;
    private String dimension;
    private int minY = -64;
    private int serverChunkRadius = -1;
    private final Map<Integer, Head> heads = new HashMap<>();
    private final Set<UUID> playerInfo = new java.util.HashSet<>();
    /**
     * The game mode of each profile in the client's player list (server order; GameType ids), while the list
     * is known ({@link #playerInfoComplete}). Spectators cannot be picked.
     */
    private final Map<UUID, Integer> gameModes = new HashMap<>();
    private Head self = new Head(NONE, null, EntityTypes.PLAYER, true);
    // where the client's own player is, for a horse it may push (horseTick): client order, from what it sent
    /** The position the client's own player last sent ({@code MovePlayer}), and that as it stood at the client's last tick end; null: none. */
    private Vec3d selfSent;
    private Vec3d selfSentAtTick;
    /**
     * Server order: the packet that put the client's own player on a vehicle (while it rides), and the last one it may
     * still have been riding at when the client processes it. A passenger sends no position.
     */
    private long selfMountedAt = Long.MIN_VALUE;
    private long selfRodeUntil = Long.MIN_VALUE;
    /** The largest scale the client's own player may have had: its box is at most that times a standing player's. */
    private double selfScale = 1.0;
    /** Where the client's own player may have been during the tick being followed (min x, y, z, max x, y, z), or null: not known. */
    private double[] selfDuring;
    private final ChunkTicking chunks = new ChunkTicking();
    private final Versioned frozen = new Versioned(FROZEN_NO);
    private boolean stepSeen;

    // --- client order ---------------------------------------------------------------------------------
    /** Pings not answered yet, in order: {id, sequence}. */
    private final ArrayDeque<long[]> pings = new ArrayDeque<>();
    /** The sequence of the last answered ping: the client processed it and everything before it. */
    private long certain = Long.MIN_VALUE;
    /** The prefixes the client's last tick could fall in ({@link #lastTickRanNormally}). */
    private long lastTickLower = Long.MIN_VALUE;
    private long lastTickUpper = Long.MAX_VALUE;
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Set<Track> pendingTracks = new LinkedHashSet<>();

    /**
     * Types whose removals are remembered for {@link #mayHaveNear}: shulkers move entities on the client
     * ({@code Shulker.onPeekAmountChange}), also in the tick before their removal was processed.
     */
    static final Set<String> REMEMBERED_REMOVALS = Set.of("minecraft:shulker");
    private static final int MAX_REMOVALS = 256;
    /** {sequence, type, base position or null}, ascending. */
    private final ArrayDeque<Object[]> removals = new ArrayDeque<>();
    private long removalsDropped = Long.MIN_VALUE;

    private long ticks;
    private long branches;
    private long merges;
    private long overflows;
    private long losses;

    /** Counters summed over every session (diagnostics); each replica adds what it counted since it last did. */
    public static final class Totals {
        private final java.util.concurrent.atomic.LongAdder ticks = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder branches = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder merges = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder overflows = new java.util.concurrent.atomic.LongAdder();
        private final java.util.concurrent.atomic.LongAdder losses = new java.util.concurrent.atomic.LongAdder();

        public long ticks() {
            return ticks.sum();
        }

        public long branches() {
            return branches.sum();
        }

        public long merges() {
            return merges.sum();
        }

        public long overflows() {
            return overflows.sum();
        }

        public long losses() {
            return losses.sum();
        }
    }

    private final Totals totals;
    private final long[] reported = new long[5];

    public ClientEntities(EntityTypes types) {
        this(types, null);
    }

    /** {@code totals}: where this replica's counters are summed with every other's (null: nowhere). */
    public ClientEntities(EntityTypes types, Totals totals) {
        this.types = types;
        this.totals = totals;
    }

    private void report() {
        if (totals == null) {
            return;
        }
        long[] now = {ticks, branches, merges, overflows, losses};
        java.util.concurrent.atomic.LongAdder[] into = {totals.ticks, totals.branches, totals.merges, totals.overflows, totals.losses};
        for (int i = 0; i < now.length; i++) {
            if (now[i] != reported[i]) {
                into[i].add(now[i] - reported[i]);
                reported[i] = now[i];
            }
        }
    }

    // --- timeline ---------------------------------------------------------------------------------------

    /** Applies one timeline event, in sequence order. */
    public void onEvent(TimelineEvent event) {
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(out.sequence(), out.packet());
            case TimelineEvent.Inbound in when in.packet() instanceof ServerboundPacket.ClientTickEnd -> onTick();
            case TimelineEvent.Inbound in when in.packet() instanceof ServerboundPacket.MovePlayer move && move.hasPosition() ->
                    selfSent = new Vec3d(move.x(), move.y(), move.z());
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.transactionId());
            case TimelineEvent.Gap gap -> lostEverything();
            case TimelineEvent.Disconnected ignored -> release();
            default -> { }
        }
    }

    private void release() {
        report();
        tracks.clear();
        pendingTracks.clear();
        heads.clear();
        pings.clear();
        level = false;
        complete = false;
    }

    private void onOutbound(long seq, ClientboundPacket packet) {
        if (self.vehicle != NONE) {
            selfRodeUntil = seq; // the client may still ride until it has processed this packet
        }
        switch (packet) {
            case ClientboundPacket.Ping ping -> {
                pings.add(new long[] {ping.id(), seq});
                if (pings.size() > MAX_TRACKED_PINGS) {
                    pings.pollFirst(); // a later window end: wider, still sound
                }
            }
            case ClientboundPacket.Login login -> {
                // A new ClientPacketListener and ClientLevel: no entities, no player list.
                selfId = login.entityId();
                selfCreated = seq;
                selfSent = null;
                selfSentAtTick = null;
                self = new Head(selfId, null, EntityTypes.PLAYER, true);
                serverChunkRadius = login.chunkRadius();
                playerInfo.clear();
                gameModes.clear();
                playerInfoComplete = true;
                newLevel(seq, login.dimension());
            }
            case ClientboundPacket.Respawn respawn -> {
                selfCreated = seq; // a new LocalPlayer, added to the level (and its tick list) last
                selfSent = null;
                selfSentAtTick = null;
                // ClientPacketListener.handleRespawn makes a new ClientLevel only for another dimension.
                if (respawn.dimension() == null || dimension == null || !respawn.dimension().key().equals(dimension)) {
                    newLevel(seq, respawn.dimension());
                }
            }
            case ClientboundPacket.LevelChunk chunk -> chunks.load(seq, chunk.x(), chunk.z());
            case ClientboundPacket.ForgetLevelChunk forget -> chunks.forget(seq, forget.x(), forget.z());
            case ClientboundPacket.ChunkCacheCenter center -> chunks.center(center.x(), center.z());
            case ClientboundPacket.ChunkCacheRadius radius -> {
                serverChunkRadius = radius.radius();
                chunks.resize(ChunkTicking.range(radius.radius()));
            }
            case ClientboundPacket.TickingState state -> {
                if (level) {
                    frozen.set(seq, !state.frozen() ? FROZEN_NO : stepSeen ? FROZEN_MAYBE : FROZEN_YES);
                }
            }
            case ClientboundPacket.TickingStep step -> {
                if (level) {
                    stepSeen = true; // the steps run down one per client tick, which is not followed here
                    if (frozen.head() != FROZEN_NO) {
                        frozen.set(seq, FROZEN_MAYBE);
                    }
                }
            }
            case ClientboundPacket.PlayerInfoUpdate update -> playerInfoUpdate(seq, update);
            case ClientboundPacket.PlayerInfoRemove remove -> {
                for (UUID profile : remove.profileIds()) {
                    playerInfo.remove(profile);
                    gameModes.remove(profile);
                    // A player entity keeps the PlayerInfo it looked up (AbstractClientPlayer.getPlayerInfo caches it):
                    // what that object says from now on is not followed.
                    forPlayer(profile, head -> {
                        head.infoReplaced = true;
                        enqueue(seq, head.id, new Op.GameMode(seq, NONE));
                    });
                }
            }
            case ClientboundPacket.AddEntity add -> addEntity(seq, add);
            case ClientboundPacket.RemoveEntities remove -> {
                for (int id : remove.entityIds()) {
                    Head head = heads.get(id);
                    if (head != null) {
                        removeHead(seq, head);
                        enqueue(seq, id, Op.REMOVE);
                    }
                }
            }
            case ClientboundPacket.MoveEntity move -> moveEntity(seq, move);
            case ClientboundPacket.EntityPositionSync sync -> {
                Head head = heads.get(sync.entityId());
                if (head != null) {
                    head.base(sync.position());
                    enqueue(seq, head.id, new Op.Sync(seq, sync.position(), sync.yRot(), sync.xRot(), sync.onGround()));
                }
            }
            case ClientboundPacket.TeleportEntity teleport -> {
                Head head = heads.get(teleport.entityId());
                if (head != null) {
                    // An old recording kept the id only; a flag this build does not know changes what the client does.
                    enqueue(seq, head.id, teleport.position() == null || teleport.relatives().contains(RelativeFlag.UNKNOWN)
                            ? Op.LOSE_POSITION
                            : new Op.Teleport(seq, teleport.position(), teleport.yRot(), teleport.xRot(), teleport.relatives(), teleport.onGround(),
                                    teleport.deltaMovement()));
                }
            }
            case ClientboundPacket.RotateHead rotate -> {
                Head head = heads.get(rotate.entityId());
                if (head != null) {
                    enqueue(seq, head.id, new Op.HeadRotation(seq, VanillaMath.unpackDegrees(rotate.yHeadRot())));
                }
            }
            case ClientboundPacket.SetEntityData data -> entityData(seq, data);
            case ClientboundPacket.SetEntityMotion motion -> {
                Head head = heads.get(motion.entityId());
                if (head != null && ClientMotion.keptAtMount(head.type)) {
                    // handleSetEntityMotion: lerpMotion sets the motion; a boat the client does not control zeroes it in its tick,
                    // a horse's slows down in each (LivingEntity.aiStep)
                    enqueue(seq, head.id, new Op.Motion(seq, motion.clientMotion()));
                }
            }
            case ClientboundPacket.SetEquipment equipment -> {
                Head head = heads.get(equipment.entityId());
                if (head != null && head.type != null && ClientMotion.RIDDEN.contains(head.type)) {
                    for (ClientboundPacket.SetEquipment.Slot slot : equipment.slots()) {
                        if (slot.slot().equals("saddle")) {
                            enqueue(seq, head.id, new Op.Saddle(seq, saddle(slot.item())));
                        }
                    }
                }
            }
            case ClientboundPacket.MobEffect effect -> horseEffect(seq, effect.entityId(), effect.effectKey(), effect.amplifier());
            case ClientboundPacket.RemoveMobEffect effect -> horseEffect(seq, effect.entityId(), effect.effectKey(), NONE);
            case ClientboundPacket.UpdateAttributes attributes -> updateAttributes(seq, attributes);
            case ClientboundPacket.SetPassengers passengers -> setPassengers(seq, passengers);
            case ClientboundPacket.Unmodeled unmodeled -> unmodeled(seq, unmodeled.type());
            case ClientboundPacket.Untranslatable untranslatable -> untranslatable(seq, untranslatable.type());
            default -> { }
        }
    }

    // --- server order: entities ---------------------------------------------------------------------------

    private void newLevel(long seq, ClientboundPacket.Dimension newDimension) {
        // Everything of the old level goes when the client processes this packet, not before.
        for (Head head : heads.values()) {
            enqueue(seq, head.id, Op.REMOVE);
        }
        for (Track track : tracks.values()) {
            if (!heads.containsKey(track.id)) {
                enqueue(seq, track.id, Op.REMOVE); // unknown ids too: the new level has none of them
            }
        }
        heads.clear();
        self.vehicle = NONE;
        self.passengers.clear();
        dimension = newDimension == null ? null : newDimension.key();
        minY = newDimension == null ? -64 : newDimension.minY();
        chunks.reset(seq, ChunkTicking.range(serverChunkRadius));
        frozen.set(seq, FROZEN_NO); // a new ClientLevel has a new TickRateManager
        stepSeen = false;
        level = true;
        complete = true;
    }

    /** The client leaves the level ({@code start_configuration}: {@code clearClientLevel}). */
    private void leaveLevel(long seq) {
        for (Head head : heads.values()) {
            enqueue(seq, head.id, Op.REMOVE);
        }
        heads.clear();
        self.vehicle = NONE;
        self.passengers.clear();
        chunks.reset(seq, ChunkTicking.range(serverChunkRadius));
        level = false;
        complete = true; // no level: certainly no entities
    }

    private void addEntity(long seq, ClientboundPacket.AddEntity add) {
        int id = add.entityId();
        if (!level || id == selfId) {
            return; // no level: dropped; the player's own id: not another entity
        }
        boolean player = EntityTypes.PLAYER.equals(add.type());
        boolean maybe = false;
        if (player && !playerInfo.contains(add.uuid())) {
            if (playerInfoComplete) {
                return; // createEntityFromPacket: "Server attempted to add player prior to sending player info": ignored
            }
            maybe = true; // the player list was lost: the client may or may not know this profile
        }
        Head old = heads.get(id);
        if (old != null) {
            // ClientLevel.addEntity removes the entity with this id first. (If the client may have skipped the
            // packet, the old entity's passengers may still ride it: passengers are never placed anyway.)
            removeHead(seq, old);
        }
        EntityTypes.Kind kind = types.kind(add.type());
        Head head = new Head(id, kind, add.type(), player);
        head.uuid = add.uuid();
        head.base(new Vec3d(add.x(), add.y(), add.z())); // syncPacketPositionCodec
        heads.put(id, head);
        if (!tracks.containsKey(id)) {
            tracks.put(id, new Track(id, complete ? State.absent() : State.lostAll()));
        }
        enqueue(seq, id, new Op.Add(seq, kind, add.type(), player, minY, add.x(), add.y(), add.z(), VanillaMath.unpackDegrees(add.yRot()),
                VanillaMath.unpackDegrees(add.xRot()), VanillaMath.unpackDegrees(add.yHeadRot()), maybe, player ? spectator(add.uuid()) : 0, add.uuid(),
                add.clientMotion()));
    }

    /** The entity leaves the client's level: its passengers stop riding it, it stops riding its vehicle. */
    private void removeHead(long seq, Head head) {
        heads.remove(head.id);
        if (REMEMBERED_REMOVALS.contains(head.type)) {
            removals.add(new Object[] {seq, head.type, head.baseKnown ? new Vec3d(head.bx, head.by, head.bz) : null});
            if (removals.size() > MAX_REMOVALS) {
                removalsDropped = Math.max(removalsDropped, (long) removals.pollFirst()[0]);
            }
        }
        for (int passenger : head.passengers) {
            Head p = head(passenger);
            if (p != null) {
                p.vehicle = NONE;
                if (p != self) {
                    enqueue(seq, passenger, new Op.Vehicle(seq, NONE));
                }
            }
        }
        head.passengers.clear();
        Head vehicle = head.vehicle == NONE ? null : head(head.vehicle);
        if (vehicle != null) {
            vehicle.passengers.remove((Integer) head.id);
        }
        head.vehicle = NONE;
    }

    private Head head(int id) {
        return id == selfId && id != NONE ? self : heads.get(id);
    }


    private void moveEntity(long seq, ClientboundPacket.MoveEntity move) {
        Head head = heads.get(move.entityId());
        if (head == null) {
            return;
        }
        Vec3d position = null;
        boolean known = head.baseKnown;
        if (move.hasPosition()) {
            // VecDeltaCodec.decode against the base, which then becomes the base.
            position = PositionCodec.decode(new Vec3d(head.bx, head.by, head.bz), move.xa(), move.ya(), move.za());
            head.base(position);
            head.baseKnown = known;
        }
        enqueue(seq, head.id, new Op.Move(seq, position, known, move.hasRotation(), VanillaMath.unpackDegrees(move.yRot()),
                VanillaMath.unpackDegrees(move.xRot()), move.onGround()));
    }

    private void entityData(long seq, ClientboundPacket.SetEntityData data) {
        Head head = heads.get(data.entityId());
        if (head == null) {
            return;
        }
        int pose = NONE;
        int flags = NONE;
        int baby = NONE;
        int dataSet = 0;
        int data0 = 0;
        int data1 = 0;
        int attached = UNSET;
        int noGravity = NONE;
        int horseFlags = NONE;
        int mobFlags = NONE;
        int pigBoost = UNSET;
        boolean horse = head.type != null && ClientMotion.HORSES.contains(head.type);
        boolean pig = PIG.equals(head.type) || STRIDER.equals(head.type); // steered by an item on a stick (ItemBasedSteering)
        EntitySizes.Entry size = sizes.entry(head.type);
        int babyIndex = size == null ? -1 : size.babyIndex();
        List<EntitySizes.DataField> fields = size == null ? List.of() : size.data();
        for (ClientboundPacket.SetEntityData.DataEntry entry : data.entries()) {
            if (entry.index() == DATA_POSE && entry.kind() == ClientboundPacket.SetEntityData.Kind.POSE) {
                pose = (int) entry.value();
            } else if (entry.index() == DATA_FLAGS && entry.kind() == ClientboundPacket.SetEntityData.Kind.BYTE) {
                flags = (int) entry.value() & 0xFF;
            } else if (entry.index() == babyIndex && entry.kind() == ClientboundPacket.SetEntityData.Kind.BOOLEAN) {
                baby = entry.value() != 0 ? 1 : 0; // the flag isBaby() reads (DATA_BABY_ID of the type's class)
            } else if (entry.index() == DATA_NO_GRAVITY && entry.kind() == ClientboundPacket.SetEntityData.Kind.BOOLEAN) {
                noGravity = entry.value() != 0 ? 1 : 0;
            } else if (horse && entry.index() == DATA_HORSE_FLAGS && entry.kind() == ClientboundPacket.SetEntityData.Kind.BYTE) {
                horseFlags = (int) entry.value() & 0xFF;
            } else if ((horse || pig) && entry.index() == DATA_MOB_FLAGS && entry.kind() == ClientboundPacket.SetEntityData.Kind.BYTE) {
                mobFlags = (int) entry.value() & 0xFF;
            } else if (pig && entry.index() == DATA_PIG_BOOST && entry.kind() == ClientboundPacket.SetEntityData.Kind.INT) {
                pigBoost = (int) entry.value(); // onSyncedDataUpdated: the boost starts (ItemBasedSteering.onSynced), whatever the value
            } else if (FIREWORK_ROCKET.equals(head.type) && entry.index() == DATA_ROCKET_ATTACHED
                    && entry.kind() == ClientboundPacket.SetEntityData.Kind.OPTIONAL_UNSIGNED_INT) {
                attached = (int) Math.max(entry.value(), NONE); // empty: -1
            } else {
                // the values the type's size reads (slime size, armor stand flags, interaction width and height, ...)
                for (int i = 0; i < fields.size(); i++) {
                    EntitySizes.DataField field = fields.get(i);
                    if (entry.index() == field.index() && entry.kind() == field.kind()) {
                        int value = field.kind() == ClientboundPacket.SetEntityData.Kind.BYTE ? (int) entry.value() & 0xFF : (int) entry.value();
                        if (i == 0) {
                            data0 = value;
                        } else {
                            data1 = value;
                        }
                        dataSet |= 1 << i;
                    }
                }
            }
        }
        if (pose != NONE || flags != NONE || baby != NONE || dataSet != 0 || attached != UNSET || noGravity != NONE || horseFlags != NONE
                || mobFlags != NONE || pigBoost != UNSET) {
            enqueue(seq, head.id, new Op.Data(seq, pose, flags, baby, dataSet, data0, data1, attached, noGravity, horseFlags, mobFlags, pigBoost));
        }
    }

    /**
     * {@code handleUpdateAttributes}: base value set, modifiers replaced. Only the scale attribute matters
     * here (the size). Its value is exact unless one operation has two modifiers or more: the client sums
     * them in its hash map's order, and floating-point sums depend on the order.
     */
    private void updateAttributes(long seq, ClientboundPacket.UpdateAttributes packet) {
        if (packet.entityId() == selfId && selfId != NONE) {
            for (ClientboundPacket.UpdateAttributes.Attribute attribute : packet.attributes()) {
                if (attribute.key().equals(SCALE_ATTRIBUTE)) {
                    double value = attributeValue(attribute);
                    selfScale = Double.isNaN(value) ? Double.POSITIVE_INFINITY : Math.max(selfScale, value); // never smaller: sound
                }
            }
            return;
        }
        Head head = heads.get(packet.entityId());
        if (head == null) {
            return;
        }
        boolean horse = head.type != null && ClientMotion.RIDDEN.contains(head.type);
        for (ClientboundPacket.UpdateAttributes.Attribute attribute : packet.attributes()) {
            if (attribute.key().equals(SCALE_ATTRIBUTE)) {
                enqueue(seq, head.id, new Op.Scale(seq, attributeValue(attribute)));
            } else if (horse && STRIDER.equals(head.type) && attribute.key().equals("minecraft:movement_speed")) {
                double[] speeds = striderSpeeds(attribute);
                enqueue(seq, head.id, new Op.HorseAttribute(seq, attribute.key(), speeds[0]));
                enqueue(seq, head.id, new Op.HorseAttribute(seq, SUFFOCATING_SPEED, speeds[1]));
            } else if (horse && HORSE_ATTRIBUTES.contains(attribute.key())) {
                enqueue(seq, head.id, new Op.HorseAttribute(seq, attribute.key(), horseAttributeValue(attribute)));
            }
        }
    }

    /**
     * One of {@link #HORSE_ATTRIBUTES} as the client computes it: {@code AttributeInstance.calculateValue}, then the
     * attribute's range ({@code RangedAttribute.sanitizeValue}: movement speed 0 to 1024, jump strength 0 to 32, step height
     * 0 to 10, gravity -1 to 1); NaN when the client's order of modifiers matters.
     */
    public static double horseAttributeValue(ClientboundPacket.UpdateAttributes.Attribute attribute) {
        double[] bounds = switch (attribute.key()) {
            case "minecraft:movement_speed" -> new double[] {0.0, 1024.0};
            case "minecraft:jump_strength" -> new double[] {0.0, 32.0};
            case "minecraft:step_height" -> new double[] {0.0, 10.0};
            case "minecraft:gravity" -> new double[] {-1.0, 1.0};
            default -> throw new IllegalArgumentException(attribute.key());
        };
        double[] range = AttributeValues.range(attribute, bounds[0], bounds[1]);
        return range[0] == range[1] ? range[0] : Double.NaN;
    }

    /**
     * A strider's movement speed as its client computes it in a tick ({@link #horseAttributeValue}): {without, with} the
     * suffocating modifier, whichever the server sent (its {@code setSuffocating} each tick adds or removes it by id).
     */
    public static double[] striderSpeeds(ClientboundPacket.UpdateAttributes.Attribute attribute) {
        List<ClientboundPacket.UpdateAttributes.Modifier> others = new ArrayList<>();
        for (ClientboundPacket.UpdateAttributes.Modifier m : attribute.modifiers()) {
            if (!m.id().equals(SUFFOCATING_MODIFIER.id())) {
                others.add(m);
            }
        }
        List<ClientboundPacket.UpdateAttributes.Modifier> with = new ArrayList<>(others);
        with.add(SUFFOCATING_MODIFIER);
        return new double[] {horseAttributeValue(new ClientboundPacket.UpdateAttributes.Attribute(attribute.key(), attribute.base(), others)),
                horseAttributeValue(new ClientboundPacket.UpdateAttributes.Attribute(attribute.key(), attribute.base(), with))};
    }

    /** A horse's saddle slot: 1 a saddle as the registry makes it, 0 empty or an item that cannot be one, -1 not known. */
    private static int saddle(dev.whynotac.protocol.packet.Stack item) {
        if (item == null || item.item() == null || item.count() <= 0) {
            return 0;
        }
        if (item.hashed() || item.added().contains("minecraft:equippable") || item.removed().contains("minecraft:equippable")) {
            return NONE; // its equippable component is not the item's own
        }
        return item.item().equals("minecraft:saddle") ? 1 : 0; // the only item whose own equippable slot is the saddle's
    }

    /** {@code handleUpdateMobEffect} and {@code handleRemoveMobEffect} for a horse ({@code amplifier} -1: removed). */
    private void horseEffect(long seq, int entityId, String key, int amplifier) {
        Head head = heads.get(entityId);
        if (head != null && head.type != null && ClientMotion.RIDDEN.contains(head.type)) {
            enqueue(seq, head.id, new Op.Effect(seq, key, amplifier));
        }
    }

    /** {@code AttributeInstance.calculateValue} then the scale's range; NaN when the client's order of modifiers matters. */
    static double attributeValue(ClientboundPacket.UpdateAttributes.Attribute attribute) {
        ClientboundPacket.UpdateAttributes.Modifier add = null;
        ClientboundPacket.UpdateAttributes.Modifier multiplyBase = null;
        ClientboundPacket.UpdateAttributes.Modifier multiplyTotal = null;
        for (ClientboundPacket.UpdateAttributes.Modifier modifier : attribute.modifiers()) {
            switch (modifier.operation()) {
                case ADD_VALUE -> {
                    if (add != null) {
                        return Double.NaN;
                    }
                    add = modifier;
                }
                case ADD_MULTIPLIED_BASE -> {
                    if (multiplyBase != null) {
                        return Double.NaN;
                    }
                    multiplyBase = modifier;
                }
                case ADD_MULTIPLIED_TOTAL -> {
                    if (multiplyTotal != null) {
                        return Double.NaN;
                    }
                    multiplyTotal = modifier;
                }
                default -> {
                    return Double.NaN; // an operation this build does not know
                }
            }
        }
        double base = attribute.base();
        if (add != null) {
            base += add.amount();
        }
        double value = base;
        if (multiplyBase != null) {
            value += base * multiplyBase.amount();
        }
        if (multiplyTotal != null) {
            value *= 1.0 + multiplyTotal.amount();
        }
        return EntitySizes.clampScale(value);
    }

    /** {@code handleSetEntityPassengersPacket}: eject everyone, then {@code startRiding(vehicle, true, false)} each. */
    private void setPassengers(long seq, ClientboundPacket.SetPassengers packet) {
        Head vehicle = head(packet.vehicleId());
        if (vehicle == null) {
            return; // "Received passengers for unknown entity"
        }
        boolean selfRode = self.vehicle != NONE;
        for (int passenger : List.copyOf(vehicle.passengers)) {
            Head p = head(passenger);
            if (p != null) {
                p.vehicle = NONE;
                if (p != self) {
                    enqueue(seq, passenger, new Op.Vehicle(seq, NONE));
                }
            }
        }
        vehicle.passengers.clear();
        for (int id : packet.passengerIds()) {
            Head p = head(id);
            if (p == vehicle) {
                if (p != self) {
                    enqueue(seq, id, Op.LOSE_ALL); // an entity told to ride itself: whatever the client makes of it
                }
                continue;
            }
            if (p == null || p.vehicle == vehicle.id || !couldAcceptPassenger(vehicle)) {
                continue;
            }
            boolean loop = false;
            for (Head v = vehicle; v != null && v.vehicle != NONE; v = head(v.vehicle)) {
                if (v.vehicle == id) {
                    loop = true;
                    break;
                }
            }
            if (loop) {
                continue;
            }
            if (p.vehicle != NONE) {
                Head old = head(p.vehicle);
                if (old != null) {
                    old.passengers.remove((Integer) id);
                }
            }
            p.vehicle = vehicle.id;
            vehicle.passengers.add(id);
            if (p == self && !selfRode) {
                selfMountedAt = seq;
            }
            if (p == self) {
                if (vehicle != self && vehicle.passengers.getFirst() == id) {
                    // The player may control it (isLocalInstanceAuthoritative): its position is the client's own physics. A boat's
                    // state at that moment is kept for the movement check (mounted).
                    enqueue(seq, vehicle.id, new Op.Control(seq));
                }
            } else {
                enqueue(seq, id, new Op.Vehicle(seq, vehicle.id));
            }
        }
        if (PIG.equals(vehicle.type) || STRIDER.equals(vehicle.type)) { // after its Control: the mount's state is the one before the player's ticks
            Head first = vehicle.passengers.isEmpty() ? null : head(vehicle.passengers.getFirst());
            int rider = first == null ? RIDER_NONE : first == self ? RIDER_SELF
                    : first.type == null || EntityTypes.PLAYER.equals(first.type) ? RIDER_PLAYER : RIDER_OTHER; // a type not known may be a player
            enqueue(seq, vehicle.id, new Op.Rider(seq, rider));
        }
    }

    private static boolean couldAcceptPassenger(Head vehicle) {
        return vehicle.type == null || !(vehicle.type.equals("minecraft:marker") || vehicle.type.equals("minecraft:ominous_item_spawner"));
    }

    private void unmodeled(long seq, String type) {
        switch (type) {
            case "minecraft:start_configuration" -> leaveLevel(seq);
            case "minecraft:chunks_biomes" -> chunks.uncertain(seq); // onChunkLoaded for chunks WhyNotAC does not read
            default -> { }
        }
    }

    private void untranslatable(long seq, String type) {
        switch (type) {
            case "minecraft:start_configuration" -> leaveLevel(seq);
            case "minecraft:level_chunk_with_light", "minecraft:forget_level_chunk", "minecraft:set_chunk_cache_center",
                    "minecraft:set_chunk_cache_radius", "minecraft:chunks_biomes" -> chunks.uncertain(seq);
            case "minecraft:ticking_state", "minecraft:ticking_step" -> {
                stepSeen = true;
                frozen.set(seq, FROZEN_MAYBE);
            }
            case "minecraft:player_info_update", "minecraft:player_info_remove" -> losePlayerList(seq);
            case "minecraft:login", "minecraft:respawn" -> {
                lostFrom(seq);
                chunks.uncertain(seq);
                frozen.set(seq, FROZEN_MAYBE);
                losePlayerList(seq);
            }
            case "minecraft:add_entity", "minecraft:remove_entities", "minecraft:move_entity_pos", "minecraft:move_entity_pos_rot",
                    "minecraft:move_entity_rot", "minecraft:entity_position_sync", "minecraft:teleport_entity", "minecraft:rotate_head",
                    "minecraft:set_entity_data", "minecraft:set_passengers" -> lostFrom(seq);
            default -> { }
        }
    }

    /**
     * {@code handlePlayerInfoUpdate}: entries added first ({@code putIfAbsent}: a profile already listed keeps
     * its PlayerInfo; a new one is survival), then each entry's game mode if the packet carries it.
     */
    private void playerInfoUpdate(long seq, ClientboundPacket.PlayerInfoUpdate update) {
        for (ClientboundPacket.PlayerInfoUpdate.Entry entry : update.entries()) {
            UUID profile = entry.profileId();
            if (update.addPlayers() && playerInfo.add(profile)) {
                gameModes.put(profile, GAME_MODE_SURVIVAL); // a new PlayerInfo: GameType.DEFAULT_MODE
            }
            if (update.updateGameMode() && entry.gameMode() >= 0 && gameModes.containsKey(profile)) {
                gameModes.put(profile, entry.gameMode());
            }
            if (update.addPlayers() || update.updateGameMode()) {
                int spectator = spectator(profile);
                forPlayer(profile, head -> {
                    if (!head.infoReplaced) {
                        enqueue(seq, head.id, new Op.GameMode(seq, spectator));
                    }
                });
            }
        }
    }

    /** 1 if the client's player list says {@code profile} is a spectator, 0 if not, -1 if not known. */
    private int spectator(UUID profile) {
        Integer mode = playerInfoComplete ? gameModes.get(profile) : null;
        return mode == null ? NONE : mode == GAME_MODE_SPECTATOR ? 1 : 0;
    }

    private void forPlayer(UUID profile, Consumer<Head> action) {
        for (Head head : heads.values()) {
            if (head.player && profile.equals(head.uuid)) {
                action.accept(head);
            }
        }
    }

    /** The player list is no longer known: neither is any player's game mode. */
    private void losePlayerList(long seq) {
        playerInfoComplete = false;
        gameModes.clear();
        for (Head head : heads.values()) {
            if (head.player) {
                enqueue(seq, head.id, new Op.GameMode(seq, NONE));
            }
        }
    }

    /** An entity packet the replica could not read: from it on, every entity is unknown until added again. */
    private void lostFrom(long seq) {
        for (Track track : tracks.values()) {
            enqueue(seq, track.id, Op.LOSE_ALL);
        }
        heads.clear();
        self.vehicle = NONE;
        self.passengers.clear();
        complete = false;
    }

    /** Events were lost ({@code Gap}): nothing about the entities, the player list or the chunks is known any more. */
    private void lostEverything() {
        losses++;
        tracks.clear(); // with the level incomplete, an id without a track is unknown
        pendingTracks.clear();
        heads.clear();
        self.vehicle = NONE;
        self.passengers.clear();
        complete = false;
        playerInfoComplete = false;
        gameModes.clear();
        chunks.uncertain(Long.MIN_VALUE);
        frozen.set(Long.MIN_VALUE, FROZEN_MAYBE);
        stepSeen = true;
    }

    private void enqueue(long seq, int id, Op op) {
        Track track = tracks.get(id);
        if (track == null) {
            return; // never added: the client has no such entity either (or it is unknown and stays so)
        }
        if (track.pending.size() >= MAX_PENDING_OPS) {
            // The client stopped answering: give the entity up rather than hold its history.
            track.pending.clear();
            track.candidates.clear();
            track.candidates.add(State.lostAll());
            overflows++;
        }
        track.pending.add(op.seq() == Long.MIN_VALUE ? op.at(seq) : op);
        pendingTracks.add(track);
    }

    // --- client order ---------------------------------------------------------------------------------------

    /** The end of the first unanswered ping's prefix: the client cannot have processed it or anything after it. */
    private long windowEnd() {
        long[] first = pings.peekFirst();
        return first == null ? Long.MAX_VALUE : first[1];
    }

    private void onAcknowledged(int id) {
        long seq = Long.MIN_VALUE;
        for (Iterator<long[]> it = pings.iterator(); it.hasNext(); ) {
            long[] ping = it.next();
            if (ping[0] == id) {
                seq = ping[1];
                break;
            }
        }
        if (seq == Long.MIN_VALUE) {
            return; // not ours, or forgotten: nothing becomes certain
        }
        while (!pings.isEmpty() && pings.peekFirst()[1] <= seq) {
            pings.pollFirst(); // skipped ones too: the client processed them before this one
        }
        certain = seq;
        for (Iterator<Track> it = pendingTracks.iterator(); it.hasNext(); ) {
            Track track = it.next();
            int done = 0;
            while (done < track.pending.size() && track.pending.get(done).seq() < seq) {
                done++;
            }
            if (done == 0) {
                continue;
            }
            List<State> next = new ArrayList<>(track.candidates.size());
            for (State state : track.candidates) {
                applyUpTo(track, state, done, next);
            }
            for (State state : next) {
                state.applied -= done;
            }
            track.pending.subList(0, done).clear();
            track.candidates = merge(next);
            if (track.pending.isEmpty()) {
                it.remove();
                State only = track.candidates.size() == 1 ? track.candidates.getFirst() : null;
                if (only != null && (only.isAbsent() && complete || only.lostAll && !complete)) {
                    tracks.remove(track.id); // gone for certain, or unknown: what an id without a track means
                }
            }
        }
        chunks.prune(seq);
        frozen.prune(seq);
    }

    /** Applies the track's pending ops [state.applied, upTo) to {@code state}, adding every outcome to {@code out}. */
    private void applyUpTo(Track track, State state, int upTo, List<State> out) {
        List<State> current = new ArrayList<>(1);
        current.add(state);
        for (int i = state.applied; i < upTo; i++) {
            Op op = track.pending.get(i);
            List<State> next = new ArrayList<>(current.size());
            for (State s : current) {
                apply(op, s, next);
            }
            for (State s : next) {
                s.applied = i + 1;
            }
            current = next;
        }
        out.addAll(current);
    }

    private void onTick() {
        ticks++;
        long end = windowEnd();
        lastTickLower = certain;
        lastTickUpper = end;
        selfDuring = selfBox(end);
        List<State> scratch = new ArrayList<>(2);
        for (Track track : tracks.values()) {
            int applicable = applicable(track, end);
            if (track.candidates.size() == 1 && track.candidates.getFirst().applied == applicable) {
                // The common case: nothing of this entity is in doubt.
                State only = track.candidates.getFirst();
                if (only.present && !only.lostAll && (!only.lostPosition || !only.kind.living())) {
                    scratch.clear();
                    tick(only, lower(track, only.applied), upper(track, only.applied, end), scratch);
                    if (scratch.size() != 1 || scratch.getFirst() != only) {
                        track.candidates = merge(new ArrayList<>(scratch));
                    }
                }
                continue;
            }
            List<State> next = new ArrayList<>();
            for (State state : track.candidates) {
                // Any further prefix of the window may have been processed before this tick.
                for (State s : prefixes(track, state, applicable)) {
                    tick(s, lower(track, s.applied), upper(track, s.applied, end), next);
                }
            }
            track.candidates = merge(next);
        }
        selfSentAtTick = selfSent;
        report();
    }

    /**
     * {@code state} and every state it reaches by processing further pending ops of the track, up to
     * {@code applicable}, with every outcome of each. {@code state} itself is the first element and is not
     * changed; the others are new.
     */
    private List<State> prefixes(Track track, State state, int applicable) {
        List<State> all = new ArrayList<>(applicable - state.applied + 1);
        List<State> current = List.of(state);
        for (int k = state.applied; ; k++) {
            all.addAll(current);
            if (k == applicable) {
                return all;
            }
            List<State> next = new ArrayList<>(current.size());
            for (State s : current) {
                apply(track.pending.get(k), s.copy(), next);
            }
            for (State s : next) {
                s.applied = k + 1;
            }
            branches++;
            current = next;
        }
    }

    /** Pending ops of {@code track} the client may have processed: those before the window end. */
    private static int applicable(Track track, long end) {
        int n = 0;
        while (n < track.pending.size() && track.pending.get(n).seq() < end) {
            n++;
        }
        return n;
    }

    /** With {@code applied} of the track's pending ops processed, the client's prefix X satisfies lower < X. */
    private long lower(Track track, int applied) {
        return applied == 0 ? certain : Math.max(certain, track.pending.get(applied - 1).seq());
    }

    /** ... and X <= upper. */
    private static long upper(Track track, int applied, long end) {
        return applied < track.pending.size() ? Math.min(end, track.pending.get(applied).seq()) : end;
    }

    // --- one candidate: ops -----------------------------------------------------------------------------------

    private void apply(Op op, State s, List<State> out) {
        switch (op) {
            case Op.Add add -> {
                if (add.maybe()) {
                    out.add(s.copy()); // the client skipped it: whatever it had stays
                    branches++;
                }
                out.add(State.added(add));
            }
            case Op.Simple simple -> {
                switch (simple.kind()) {
                    case Op.KIND_REMOVE -> out.add(State.absent());
                    case Op.KIND_LOSE_ALL -> out.add(State.lostAll());
                    case Op.KIND_LOSE_POSITION -> {
                        if (s.present) {
                            s.lostPosition = true;
                        }
                        out.add(s);
                    }
                    default -> throw new IllegalStateException();
                }
            }
            case Op.Move move -> {
                if (!s.present || s.lostAll || s.lostPosition) {
                    out.add(s);
                    return;
                }
                if (move.position() != null && !move.positionKnown()) {
                    s.lostPosition = true; // decoded against a base WhyNotAC lost
                    out.add(s);
                    return;
                }
                List<State> moved = new ArrayList<>(1);
                if (move.position() != null) {
                    moveOrInterpolateTo(s, move.position(), move.hasRotation(), move.yRot(), move.xRot(), moved);
                } else if (move.hasRotation()) {
                    moveOrInterpolateTo(s, null, true, move.yRot(), move.xRot(), moved);
                } else {
                    moved.add(s);
                }
                for (State m : moved) {
                    m.onGround = move.onGround();
                    m.onGroundKnown = true;
                    m.groundSet();
                }
                out.addAll(moved);
            }
            case Op.Sync sync -> {
                if (!s.present || s.lostAll || s.lostPosition) {
                    out.add(s);
                    return;
                }
                // handleEntityPositionSync: interpolate if ticking and within 64 blocks, else snap.
                Tri ticking = s.player ? Tri.TRUE : chunkTickingAt(s, sync.seq() - 1, sync.seq());
                Tri near = distanceSqrAtMost(s.x, s.y, s.z, s.rPos, sync.position(), SNAP_DISTANCE_SQR);
                Tri interpolate = ticking.and(near);
                List<State> result = new ArrayList<>(2);
                if (interpolate != Tri.FALSE) {
                    State t = interpolate == Tri.MAYBE ? s.copy() : s;
                    moveOrInterpolateTo(t, sync.position(), true, sync.yRot(), sync.xRot(), result);
                }
                if (interpolate != Tri.TRUE) {
                    snapTo(s, sync.position(), sync.yRot(), sync.xRot());
                    result.add(s);
                }
                if (interpolate == Tri.MAYBE) {
                    branches++;
                }
                for (State r : result) {
                    r.onGround = sync.onGround();
                    r.onGroundKnown = true;
                    r.groundSet();
                }
                out.addAll(result);
            }
            case Op.Teleport teleport -> teleport(s, teleport, out);
            case Op.HeadRotation head -> {
                if (s.present && !s.lostAll && s.kind.living()) {
                    s.headTarget = head.yHeadRot(); // LivingEntity.lerpHeadTo(yHeadRot, 3); Entity's does nothing visible
                    s.headSteps = 3;
                }
                out.add(s);
            }
            case Op.Data data -> {
                if (s.lostAll && data.attached() != UNSET) {
                    s.attached = ATTACHED_UNKNOWN;
                }
                if (s.present && !s.lostAll) {
                    if (data.pose() != NONE) {
                        s.pose = data.pose();
                        if (s.pose == POSE_SLEEPING) {
                            s.lostPosition = true; // placed on its bed (setPosToBed), and off it again on waking
                        }
                    }
                    if (data.flags() != NONE) {
                        s.flags = data.flags();
                    }
                    if (data.baby() != NONE) {
                        s.baby = data.baby() == 1;
                    }
                    if ((data.dataSet() & 1) != 0) {
                        s.data0 = data.data0();
                    }
                    if ((data.dataSet() & 2) != 0) {
                        s.data1 = data.data1();
                    }
                    if (data.attached() != UNSET) {
                        s.attached = data.attached();
                    }
                    if (data.noGravity() != NONE) {
                        s.noGravity = data.noGravity() == 1;
                    }
                    if (data.horseFlags() != NONE && s.horse != null) {
                        s.horse = s.horse.withFlags(data.horseFlags());
                    }
                    if (data.mobFlags() != NONE && s.horse != null) {
                        s.horse = s.horse.withMobFlags(data.mobFlags());
                    }
                    if (data.pigBoost() != UNSET && s.horse != null) {
                        s.horse = s.horse.boostSynced(data.pigBoost());
                    }
                }
                out.add(s);
            }
            case Op.GameMode mode -> {
                if (s.present && !s.lostAll && s.player) {
                    s.spectator = mode.spectator();
                }
                out.add(s);
            }
            case Op.Scale scale -> {
                if (s.present && !s.lostAll && s.kind.living()) {
                    s.scale = scale.value(); // a non-living entity: the client throws (Server tried to update attributes of a non-living entity)
                    s.scaleLow = Math.min(s.scaleLow, s.scale); // NaN stays NaN
                    s.scaleHigh = Math.max(s.scaleHigh, s.scale);
                }
                out.add(s);
            }
            case Op.Motion motion -> {
                if (s.present && !s.lostAll && !s.lostPosition) {
                    s.motion = motion.motion();
                    if (s.horse != null) {
                        s.horse = s.horse.motionSet(motion.seq(), motion.motion() != null && motion.motion().isFinite(), s.x, s.y, s.z, s.rPos);
                    }
                }
                out.add(s);
            }
            case Op.Control control -> {
                // a vehicle not followed (lost, ridden by the player before, ...) has no state at this mount: not the last one's
                boolean known = s.present && !s.lostAll && !s.lostPosition;
                s.mounted = known && ClientMotion.interpolatesOnly(s.type) ? s.mounted(control.seq()) : null;
                s.horseMounted = known && s.horse != null ? s.horseMounted(control.seq()) : null;
                if (s.present && !s.lostAll) {
                    s.lostPosition = true;
                }
                out.add(s);
            }
            case Op.Saddle saddle -> {
                if (s.present && !s.lostAll && s.horse != null) {
                    s.horse = s.horse.withSaddle(saddle.saddle());
                }
                out.add(s);
            }
            case Op.HorseAttribute attribute -> {
                if (s.present && !s.lostAll && s.horse != null) {
                    s.horse = s.horse.withAttribute(attribute.key(), attribute.value());
                }
                out.add(s);
            }
            case Op.Effect effect -> {
                if (s.present && !s.lostAll && s.horse != null) {
                    s.horse = s.horse.withEffect(effect.key(), effect.amplifier());
                }
                out.add(s);
            }
            case Op.Rider rider -> {
                if (s.present && !s.lostAll && s.horse != null) {
                    s.horse = s.horse.ridden(rider.rider());
                }
                out.add(s);
            }
            case Op.Vehicle vehicle -> {
                if (s.present && !s.lostAll) {
                    if (vehicle.vehicleId() != NONE) {
                        s.pose = POSE_STANDING; // Entity.startRiding sets the standing pose
                        s.lostPosition = true; // a passenger is placed by its vehicle (positionRider)
                    }
                    s.vehicle = vehicle.vehicleId();
                }
                out.add(s);
            }
        }
    }

    /** {@code ClientPacketListener.handleTeleportEntity} with {@code setValuesFromPositionPacket} for an entity the client does not control. */
    private void teleport(State s, Op.Teleport t, List<State> out) {
        if (!s.present || s.lostAll || s.lostPosition) {
            out.add(s);
            return;
        }
        Set<RelativeFlag> rel = t.relatives();
        // PositionMoveRotation.of: the interpolation target while interpolating, else the entity's own.
        boolean interpolating = s.kind.interpolationSteps() > 0 && s.steps > 0;
        double cx = interpolating ? s.tx : s.x;
        double cy = interpolating ? s.ty : s.y;
        double cz = interpolating ? s.tz : s.z;
        double cr = interpolating ? s.rTarget : s.rPos;
        float cyRot = interpolating ? s.tyRot : s.yRot;
        float cxRot = interpolating ? s.txRot : s.xRot;
        float crRot = interpolating ? s.rTargetRot : s.rRot;
        // PositionMoveRotation.calculateAbsolute.
        double x = (rel.contains(RelativeFlag.X) ? cx : 0.0) + t.position().x();
        double y = (rel.contains(RelativeFlag.Y) ? cy : 0.0) + t.position().y();
        double z = (rel.contains(RelativeFlag.Z) ? cz : 0.0) + t.position().z();
        boolean relativePosition = rel.contains(RelativeFlag.X) || rel.contains(RelativeFlag.Y) || rel.contains(RelativeFlag.Z);
        float yRot = (rel.contains(RelativeFlag.Y_ROT) ? cyRot : 0.0F) + t.yRot();
        float xRot = VanillaMath.clamp((rel.contains(RelativeFlag.X_ROT) ? cxRot : 0.0F) + t.xRot(), -90.0F, 90.0F);
        boolean relativeRotation = rel.contains(RelativeFlag.Y_ROT) || rel.contains(RelativeFlag.X_ROT);
        if (relativePosition && cr > 0 || relativeRotation && crRot > 0) {
            s.lostPosition = true; // relative to a value known only within a radius: not followed
            out.add(s);
            return;
        }
        Vec3d target = new Vec3d(x, y, z);
        Tri near = distanceSqrAtMost(cx, cy, cz, cr, target, SNAP_DISTANCE_SQR);
        List<State> result = new ArrayList<>(2);
        if (near != Tri.FALSE) {
            moveOrInterpolateTo(near == Tri.MAYBE ? s.copy() : s, target, true, yRot, xRot, result);
        }
        if (near != Tri.TRUE) {
            // setPos, setYRot, setXRot: no interpolation, rotation not wrapped.
            setPos(s, x, y, z, 0.0);
            setYRot(s, yRot);
            setXRot(s, xRot);
            s.rRot = 0.0F;
            s.rotMoved = true;
            result.add(s);
        }
        if (near == Tri.MAYBE) {
            branches++;
        }
        for (State r : result) {
            r.onGround = t.onGround();
            r.onGroundKnown = true;
            r.groundSet();
            if (r.horse != null) {
                // PositionMoveRotation.calculateAbsolute's motion: the packet's, unless relative to the one the horse had
                boolean absolute = t.delta() != null && t.delta().isFinite() && !rel.contains(RelativeFlag.DELTA_X) && !rel.contains(RelativeFlag.DELTA_Y)
                        && !rel.contains(RelativeFlag.DELTA_Z) && !rel.contains(RelativeFlag.ROTATE_DELTA);
                r.motion = absolute ? t.delta() : r.motion;
                r.horse = r.horse.motionSet(t.seq(), absolute, r.x, r.y, r.z, r.rPos);
            }
        }
        out.addAll(result);
    }

    /**
     * {@code Entity.moveOrInterpolateTo}: with an interpolation handler, {@code interpolateTo} with the
     * missing parts taken from the handler; without one, the position and rotation are set at once.
     * {@code position} null: only the rotation.
     */
    private void moveOrInterpolateTo(State s, Vec3d position, boolean hasRotation, float yRot, float xRot, List<State> out) {
        int interpolationSteps = s.kind.interpolationSteps();
        if (interpolationSteps == EntityTypes.NO_INTERPOLATION) {
            if (position != null) {
                setPos(s, position.x(), position.y(), position.z(), 0.0);
            }
            if (hasRotation) {
                setYRot(s, yRot % 360.0F);
                setXRot(s, xRot % 360.0F);
                s.rRot = 0.0F;
                s.rotMoved = true;
            }
            out.add(s);
            return;
        }
        boolean active = s.steps > 0;
        // InterpolationHandler.position()/yRot()/xRot(): the target while active, else the entity's own.
        double px;
        double py;
        double pz;
        double pr;
        if (position != null) {
            px = position.x();
            py = position.y();
            pz = position.z();
            pr = 0.0;
        } else if (active) {
            px = s.tx;
            py = s.ty;
            pz = s.tz;
            pr = s.rTarget;
        } else {
            px = s.x;
            py = s.y;
            pz = s.z;
            pr = s.rPos;
        }
        float ty = hasRotation ? yRot : active ? s.tyRot : s.yRot;
        float tx = hasRotation ? xRot : active ? s.txRot : s.xRot;
        float tr = hasRotation ? 0.0F : active ? s.rTargetRot : s.rRot;
        if (interpolationSteps == 0) {
            // A handler of 0 steps (displays): snapTo, cancel.
            snapTo(s, new Vec3d(px, py, pz), ty, tx);
            s.rPos = pr;
            s.rRot = tr;
            cancel(s);
            out.add(s);
            return;
        }
        Tri same;
        if (!active) {
            same = Tri.FALSE;
        } else {
            same = Tri.TRUE;
            if (hasRotation) {
                same = same.and(sameFloat(s.tyRot, yRot, s.rTargetRot)).and(sameFloat(s.txRot, xRot, s.rTargetRot));
            }
            if (position != null) {
                same = same.and(sameDouble(s.tx, px, s.rTarget)).and(sameDouble(s.ty, py, s.rTarget)).and(sameDouble(s.tz, pz, s.rTarget));
            }
        }
        if (same == Tri.TRUE) {
            out.add(s); // the same target: the running interpolation goes on
            return;
        }
        if (same == Tri.MAYBE) {
            out.add(s.copy());
            branches++;
        }
        // A new interpolation of the type's steps, from where the entity is now.
        s.steps = interpolationSteps;
        s.tx = px;
        s.ty = py;
        s.tz = pz;
        s.rTarget = pr;
        s.tyRot = ty;
        s.txRot = tx;
        s.rTargetRot = tr;
        s.hasPrevPos = true;
        s.px = s.x;
        s.py = s.y;
        s.pz = s.z;
        s.rPrev = s.rPos;
        s.posMoved = false;
        s.hasPrevRot = true;
        s.pxRot = s.xRot;
        s.pyRot = s.yRot;
        s.rPrevRot = s.rRot;
        s.rotMoved = false;
        out.add(s);
    }

    /** {@code Entity.snapTo(x, y, z, yRot, xRot)}: {@code setPosRaw}, rotation set (not wrapped); interpolation is not cancelled. */
    private static void snapTo(State s, Vec3d p, float yRot, float xRot) {
        setPos(s, p.x(), p.y(), p.z(), 0.0);
        setYRot(s, yRot);
        setXRot(s, xRot);
        s.rRot = 0.0F;
        s.rotMoved = true;
    }

    /** The position changes outside interpolation: a running interpolation sees it as a movement since its last step. */
    private static void setPos(State s, double x, double y, double z, double radius) {
        s.x = x;
        s.y = y;
        s.z = z;
        s.rPos = radius;
        s.posMoved = true;
    }

    /** {@code Entity.setYRot}: non-finite values are discarded. */
    private static void setYRot(State s, float yRot) {
        if (Float.isFinite(yRot)) {
            s.yRot = yRot;
        }
    }

    /** {@code Entity.setXRot}: wrapped and clamped to [-90, 90]; non-finite values discarded. */
    private static void setXRot(State s, float xRot) {
        if (Float.isFinite(xRot)) {
            s.xRot = Math.clamp(xRot % 360.0F, -90.0F, 90.0F);
        }
    }

    private static void cancel(State s) {
        s.steps = 0;
        s.hasPrevPos = false;
        s.hasPrevRot = false;
    }

    // --- one candidate: a tick ----------------------------------------------------------------------------------

    /** One client tick for this candidate, with the client's prefix X in (lower, upper]. */
    private void tick(State s, long lower, long upper, List<State> out) {
        s.ticked = false;
        s.tickedKnown = true;
        if (!s.present || s.lostAll) {
            out.add(s);
            return;
        }
        if (s.lostPosition) {
            if (FIREWORK_ROCKET.equals(s.type) && s.attached == selfId && selfId != NONE) {
                // FireworkRocketEntity.tick places it at the player it is attached to (boosting a glide): it stays where the
                // player is, so it ticks where the player's chunk does, which rocketsBoosting decides
                Tri frozen = notFrozen(lower, upper);
                s.ticked = frozen == Tri.TRUE;
                s.tickedKnown = frozen != Tri.MAYBE;
                s.tickLower = lower;
                s.tickUpper = upper;
                out.add(s);
                return;
            }
            if (!s.kind.living()) {
                s.lostAll = true; // wherever it went, it may have fallen out of the world and been discarded (checkBelowWorld)
            }
            out.add(s);
            return;
        }
        if (s.vehicle != NONE) {
            s.lostPosition = true; // passengers are ticked by their vehicle, which places them
            out.add(s);
            return;
        }
        // ClientLevel.tickEntities: ticking entities that are not frozen (players never are, nor carriers of players).
        Tri ticks = s.player ? Tri.TRUE : notFrozen(lower, upper).and(chunkTicking(s, lower, upper));
        if (ticks == Tri.FALSE) {
            out.add(s);
            return;
        }
        if (ticks == Tri.MAYBE) {
            out.add(s.copy());
            branches++;
        }
        int from = out.size();
        tickNow(s, lower, out);
        for (int i = from; i < out.size(); i++) {
            out.get(i).ticked = true;
        }
    }

    private void tickNow(State s, long lower, List<State> out) {
        if (!s.kind.living()) {
            // Entity.baseTick -> checkBelowWorld -> onBelowWorld: discard.
            double belowWorld = s.minY - 64;
            Tri below = s.y - s.rPos < belowWorld ? (s.y + s.rPos < belowWorld ? Tri.TRUE : Tri.MAYBE) : Tri.FALSE;
            if (below == Tri.MAYBE) {
                boatTick(s.copy(), lower, out);
                branches++;
            }
            if (below == Tri.FALSE) {
                boatTick(s, lower, out);
            } else {
                out.add(State.absent());
            }
            return;
        }
        // LivingEntity.aiStep (RemotePlayer.aiStep for players): interpolation, then the head.
        int from = out.size();
        boolean interpolating = s.kind.interpolationSteps() > 0 && s.steps > 0;
        if (interpolating) {
            interpolate(s, out);
        } else {
            out.add(s);
        }
        for (int i = from; i < out.size(); i++) {
            State t = out.get(i);
            if (t.horse != null) {
                horseTick(t, interpolating);
            }
            // LivingEntity.tick ends with refreshDirtyAttributes: the box follows the scale
            t.scaleLow = t.scale;
            t.scaleHigh = t.scale;
            if (t.animated) {
                // PolarBear.tick, after LivingEntity.tick: the previous value, then one step towards the standing flag
                t.animO = t.anim;
                t.anim = Math.max(0, Math.min(EntitySizes.POLAR_BEAR_STAND_TICKS, t.anim + (t.data0 != 0 ? 1 : -1)));
            }
            if (t.headSteps > 0) {
                // LivingEntity.lerpHeadRotationStep: yHeadRot = (float) rotLerp(1.0 / steps, yHeadRot, target).
                double step = 1.0 / t.headSteps;
                if (t.rHead > 0) {
                    t.rHead = nearWrap(t.headTarget - t.yHeadRot, t.rHead) ? 360.0F : (float) ((1.0 - step) * t.rHead) + rotMargin();
                }
                t.yHeadRot = (float) VanillaMath.rotLerp(step, (double) t.yHeadRot, t.headTarget);
                t.headSteps--;
            }
        }
    }

    /**
     * A horse's tick on a client that does not control it, after its interpolation's step: {@code LivingEntity.aiStep}
     * slows its motion (by 0.98, while not interpolating: {@code canSimulateMovement} is false) and zeroes each part below
     * 0.003 (not a player's); its {@code pushEntities} pushes the client's own player when their boxes meet
     * ({@code EntitySelector.pushableBy} holds no other entity on the client), and with it the horse
     * ({@code Entity.push}: one not ridden is pushable), which is not followed. The water and lava it passes through
     * push it too ({@code Entity.baseTick}): where it has been is kept for the movement check to rule them out.
     */
    private void horseTick(State t, boolean interpolating) {
        t.everTicked = true;
        if (t.motion != null) {
            Vec3d m = interpolating ? t.motion : t.motion.scale(0.98);
            t.motion = new Vec3d(Math.abs(m.x()) < 0.003 ? 0.0 : m.x(), Math.abs(m.y()) < 0.003 ? 0.0 : m.y(), Math.abs(m.z()) < 0.003 ? 0.0 : m.z());
        }
        Ridable h = t.horse.swept(t.x, t.y, t.z, t.rPos);
        if (h.motionKnown()) {
            EntitySizes.Box box = t.view().bounds();
            double[] p = selfDuring;
            if (box == null || p == null || box.minX() <= p[3] && box.maxX() >= p[0] && box.minY() <= p[4] && box.maxY() >= p[1]
                    && box.minZ() <= p[5] && box.maxZ() >= p[2]) {
                h = h.pushed();
            }
        }
        t.horse = h;
    }

    /**
     * Where the client's own player may have been during this tick: a standing player's box at its largest scale, around
     * the position it had sent by the last tick's end and the one by this tick's, each within {@link #UNSENT} of the
     * client's. Null when not known: nothing sent yet since its last ride, or it may ride: the client may have processed
     * the packet that put it on a vehicle (before {@code end}) and not the one that took it off (a passenger sends no position).
     */
    private double[] selfBox(long end) {
        if (self.vehicle != NONE ? selfMountedAt < end : certain <= selfRodeUntil) {
            selfSent = null; // sent before the ride: not where it is
            selfSentAtTick = null;
            return null;
        }
        if (selfSent == null || selfSentAtTick == null || !Double.isFinite(selfScale)) {
            return null;
        }
        double r = UNSENT + 1.0E-6;
        double half = 0.3 * selfScale + r;
        return new double[] {Math.min(selfSent.x(), selfSentAtTick.x()) - half, Math.min(selfSent.y(), selfSentAtTick.y()) - r,
                Math.min(selfSent.z(), selfSentAtTick.z()) - half, Math.max(selfSent.x(), selfSentAtTick.x()) + half,
                Math.max(selfSent.y(), selfSentAtTick.y()) + 1.8 * selfScale + r, Math.max(selfSent.z(), selfSentAtTick.z()) + half};
    }

    /**
     * The rest of a non-living entity's tick that stays in the world: for a boat the client does not control
     * ({@code AbstractBoat.tick}), after {@code baseTick} the interpolation's step, then its motion set to zero (the
     * effects of the blocks it meets after that are the movement check's to rule out); nothing for the others.
     */
    private void boatTick(State s, long lower, List<State> out) {
        if (!ClientMotion.interpolatesOnly(s.type)) {
            out.add(s);
            return;
        }
        s.everTicked = true;
        s.hasTickStart = true;
        s.sx = s.x;
        s.sy = s.y;
        s.sz = s.z;
        s.rStart = s.rPos;
        s.tickStartLower = lower;
        s.motion = null;
        if (s.steps > 0) {
            interpolate(s, out);
        } else {
            out.add(s);
        }
    }

    /** {@code InterpolationHandler.interpolate}. */
    private void interpolate(State s, List<State> out) {
        double d = 1.0 / s.steps;
        int from = out.size();
        if (s.hasPrevPos) {
            // If the entity was moved since the last step, the target moves with it when the box at the
            // moved target is free of collisions: unknown here, so both. Not moved: the movement is exactly
            // zero in every member (x - x), which still turns a -0.0 target into 0.0.
            double dx = s.posMoved ? s.x - s.px : 0.0;
            double dy = s.posMoved ? s.y - s.py : 0.0;
            double dz = s.posMoved ? s.z - s.pz : 0.0;
            double dr = s.posMoved ? s.rPos + s.rPrev : 0.0;
            boolean zero = dr == 0.0 && dx == 0.0 && dy == 0.0 && dz == 0.0;
            boolean changes = !zero || isNegativeZero(s.tx) || isNegativeZero(s.ty) || isNegativeZero(s.tz); // -0.0 + 0.0 = 0.0
            if (changes) {
                State added = s.copy();
                added.tx = s.tx + dx;
                added.ty = s.ty + dy;
                added.tz = s.tz + dz;
                added.rTarget = s.rTarget + dr + (dr > 0 || s.rTarget > 0
                        ? margin(Math.max(Math.abs(added.tx), Math.max(Math.abs(added.ty), Math.abs(added.tz))), 0.0) : 0.0);
                out.add(added);
                branches++;
            }
        }
        out.add(s);
        for (int i = from; i < out.size(); i++) {
            State t = out.get(i);
            if (t.hasPrevRot) {
                // The same for the rotation, without a collision test.
                float dyRot = t.rotMoved ? t.yRot - t.pyRot : 0.0F;
                float dxRot = t.rotMoved ? t.xRot - t.pxRot : 0.0F;
                t.tyRot += dyRot;
                t.txRot += dxRot;
                if (t.rotMoved && (t.rRot > 0 || t.rPrevRot > 0)) {
                    t.rTargetRot += t.rRot + t.rPrevRot + rotMargin();
                }
            }
            double nx = VanillaMath.lerp(d, t.x, t.tx);
            double ny = VanillaMath.lerp(d, t.y, t.ty);
            double nz = VanillaMath.lerp(d, t.z, t.tz);
            float nyRot = (float) VanillaMath.rotLerp(d, (double) t.yRot, (double) t.tyRot);
            float nxRot = (float) VanillaMath.lerp(d, (double) t.xRot, (double) t.txRot);
            if (t.rPos > 0 || t.rTarget > 0) {
                double magnitude = Math.max(Math.max(Math.abs(t.x), Math.abs(t.tx)), Math.max(Math.max(Math.abs(t.y), Math.abs(t.ty)),
                        Math.max(Math.abs(t.z), Math.abs(t.tz))));
                double m = margin(magnitude + t.rPos + t.rTarget, 0.0);
                t.rPos = d == 1.0 ? t.rTarget + m : (1.0 - d) * t.rPos + d * t.rTarget + m;
            }
            if (t.rRot > 0 || t.rTargetRot > 0) {
                float r = (float) ((1.0 - d) * t.rRot + d * t.rTargetRot) + rotMargin();
                t.rRot = nearWrap(t.tyRot - t.yRot, t.rRot + t.rTargetRot) ? 360.0F : r;
            }
            t.x = nx;
            t.y = ny;
            t.z = nz;
            // Entity.setRot: setYRot(y % 360), setXRot(x % 360).
            setYRot(t, nyRot % 360.0F);
            setXRot(t, nxRot % 360.0F);
            t.steps--;
            t.hasPrevPos = true;
            t.px = t.x;
            t.py = t.y;
            t.pz = t.z;
            t.rPrev = t.rPos;
            t.posMoved = false;
            t.hasPrevRot = true;
            t.pxRot = t.xRot;
            t.pyRot = t.yRot;
            t.rPrevRot = t.rRot;
            t.rotMoved = false;
        }
    }

    /** rotLerp takes the short way round: an uncertain start near the opposite side may go either way. */
    private static boolean nearWrap(double difference, double radius) {
        return Math.abs(VanillaMath.wrapDegrees(difference)) + radius >= 180.0;
    }

    /** Rounding of one lerp step at this magnitude, generously. */
    private static double margin(double a, double b) {
        return 4.0 * Math.ulp(Math.max(Math.abs(a), Math.abs(b)) + 1.0);
    }

    private static float rotMargin() {
        return 4.0F * Math.ulp(360.0F);
    }

    private static boolean isNegativeZero(double value) {
        return Double.doubleToRawLongBits(value) == Long.MIN_VALUE;
    }

    // --- global facts at the client's prefix ------------------------------------------------------------------------

    /**
     * Whether the client's level ran its last tick normally ({@code tickRateManager().runsNormally()}: not frozen, or
     * stepping), whatever it had processed by then: the world border and the time advance only then. Null when not known.
     */
    public Boolean lastTickRanNormally() {
        return switch (frozen.range(lastTickLower, lastTickUpper)) {
            case FROZEN_NO -> Boolean.TRUE;
            case FROZEN_YES -> Boolean.FALSE;
            default -> null; // stepping may or may not have been left, or the state changed within the window
        };
    }

    /** Whether the client's TickRateManager lets non-player entities tick for every prefix X in (lower, upper]. */
    private Tri notFrozen(long lower, long upper) {
        return switch (frozen.range(lower, upper)) {
            case Versioned.ALWAYS_NO -> Tri.TRUE;
            default -> Tri.MAYBE; // frozen entities with player passengers still tick: never a certain no
        };
    }

    /** Whether the candidate's chunk ticks entities for every prefix X in (lower, upper]. */
    private Tri chunkTicking(State s, long lower, long upper) {
        // Every chunk the candidate may be in (blockPosition >> 4): one when its position is exact.
        int x0 = VanillaMath.floor(s.x - s.rPos) >> 4;
        int x1 = VanillaMath.floor(s.x + s.rPos) >> 4;
        int z0 = VanillaMath.floor(s.z - s.rPos) >> 4;
        int z1 = VanillaMath.floor(s.z + s.rPos) >> 4;
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > 16) {
            return Tri.MAYBE;
        }
        Tri result = null;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                Tri chunk = chunks.ticking(ClientWorld.chunkKey(x, z), lower, upper);
                if (result != null && chunk != result || chunk == Tri.MAYBE) {
                    return Tri.MAYBE;
                }
                result = chunk;
            }
        }
        return result;
    }

    /** At the exact prefix just after {@code seq - 1}: the client processes the op at {@code seq}. */
    private Tri chunkTickingAt(State s, long lower, long seq) {
        return chunkTicking(s, lower, seq);
    }

    private static Tri distanceSqrAtMost(double x, double y, double z, double radius, Vec3d p, double limit) {
        double dx = p.x() - x;
        double dy = p.y() - y;
        double dz = p.z() - z;
        if (radius == 0.0) {
            return dx * dx + dy * dy + dz * dz > limit ? Tri.FALSE : Tri.TRUE;
        }
        double min = sq(Math.max(0, Math.abs(dx) - radius)) + sq(Math.max(0, Math.abs(dy) - radius)) + sq(Math.max(0, Math.abs(dz) - radius));
        double max = sq(Math.abs(dx) + radius) + sq(Math.abs(dy) + radius) + sq(Math.abs(dz) + radius);
        return min > limit ? Tri.FALSE : max <= limit ? Tri.TRUE : Tri.MAYBE;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** {@code Objects.equals(Double, Double)} for a value known within {@code radius}. */
    private static Tri sameDouble(double known, double other, double radius) {
        if (radius == 0.0) {
            return Double.compare(known, other) == 0 ? Tri.TRUE : Tri.FALSE;
        }
        return Math.abs(known - other) > radius ? Tri.FALSE : Tri.MAYBE;
    }

    /** {@code Objects.equals(Float, Float)} for a value known within {@code radius} degrees. */
    private static Tri sameFloat(float known, float other, float radius) {
        if (radius == 0.0F) {
            return Float.compare(known, other) == 0 ? Tri.TRUE : Tri.FALSE;
        }
        return Math.abs(known - other) > radius ? Tri.FALSE : Tri.MAYBE;
    }

    // --- merging ----------------------------------------------------------------------------------------------------

    /**
     * Folds candidates the client cannot tell apart from now on into one: equal in everything discrete and
     * in their targets, apart in position and rotation by at most the radius kept.
     */
    private List<State> merge(List<State> states) {
        if (states.size() == 1) {
            return states;
        }
        List<State> result = new ArrayList<>(Math.min(states.size(), 4));
        outer:
        for (State s : states) {
            for (State r : result) {
                if (r.mergeable(s)) {
                    r.absorb(s);
                    merges++;
                    continue outer;
                }
            }
            result.add(s);
        }
        if (result.size() > MAX_CANDIDATES) {
            // Too many: nothing is claimed until the server adds or removes the entity after every member's
            // last processed op (ops before that may be behind some members already).
            overflows++;
            State lost = State.lostAll();
            for (State s : result) {
                lost.applied = Math.max(lost.applied, s.applied);
            }
            result.clear();
            result.add(lost);
        }
        return result;
    }

    // --- queries ----------------------------------------------------------------------------------------------------

    /**
     * One state the client may hold for an entity right now. Radii bound the true value (0: exact). {@code size}: the
     * size the client gives it, when known exactly and its box is that size around its position; {@code bounds}: a box
     * holding the client's box for every position and size this state allows (the radius included), or null.
     */
    public record PossibleState(boolean present, boolean known, boolean positionKnown, String type, boolean player, double x, double y, double z,
                                double positionRadius, float yRot, float xRot, float rotationRadius, float yHeadRot, float headRadius, int pose,
                                int flags, int onGround, int vehicle, EntitySizes.Size size, EntitySizes.Box bounds, int pickable, float pickRadius) {}

    /**
     * A boat's client state when the client's own player became its controlling passenger
     * ({@code handleSetEntityPassengersPacket}, the player first): until then the client moved it by interpolation
     * alone (and set its motion to zero in each tick); from then on its own physics moves it, which the replica does
     * not follow. {@code exact}: every value is the client's to the bit (no radius). {@code steps}, {@code target} and
     * the target's rotation: its {@code InterpolationHandler} ({@code steps} 0: none running); {@code previousPosition}
     * ({@code previousTickPosition}, null: none) and the previous rotation (when {@code hasPreviousRotation}).
     * {@code onGround} 1, 0 or -1 (not known); {@code groundSetAt}: where it was when a packet last set that flag (null:
     * none since it was added). {@code motion}: its motion from a {@code SetEntityMotion} after its last tick (null: that
     * tick's zero). {@code everTicked} false: its first tick is still to come. {@code lastTickStart}: where its last tick
     * started (null: none), with the client's packets up to {@code lastTickLower} processed by then.
     */
    public record Mounted(long sequence, String type, boolean exact, Vec3d position, float yRot, float xRot, int steps, Vec3d target,
                          float targetYRot, float targetXRot, Vec3d previousPosition, boolean hasPreviousRotation, float previousYRot,
                          float previousXRot, int onGround, Vec3d groundSetAt, boolean noGravity, Vec3d motion, boolean everTicked,
                          Vec3d lastTickStart, long lastTickLower) {}

    /**
     * The client's state for the boat {@code entityId} at its player's mount, one per candidate (equal ones once); null
     * when not known for some candidate: the mount not processed in it, the boat not followed before (lost, or ridden by
     * the player before), or not a boat.
     */
    public List<Mounted> mounted(int entityId) {
        Track track = tracks.get(entityId);
        if (track == null) {
            return null;
        }
        List<Mounted> out = new ArrayList<>(1);
        for (State state : track.candidates) {
            if (state.mounted == null) {
                return null;
            }
            if (!out.contains(state.mounted)) {
                out.add(state.mounted);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * A horse's client state when the client's own player became its controlling passenger ({@code
     * handleSetEntityPassengersPacket}, the player first): until then the client moved it by interpolation alone and
     * slowed its motion each tick; from then on its own physics moves it. As {@link Mounted} for a boat, and:
     * {@code motion} its motion (null: not known, the client's own player may have pushed it or a teleport set it
     * relative to one not followed), set at {@code motionSeq}; {@code sweptMin} and {@code sweptMax} bound where it has
     * been since (the fluids there push it in its ticks); {@code saddle} 1, 0 or -1 (not known); {@code flags} its
     * {@code DATA_ID_FLAGS}, {@code mobFlags} its {@code DATA_MOB_FLAGS_ID}; {@code attributes} the values the server sent
     * of {@link #HORSE_ATTRIBUTES} (NaN: not known exactly; absent: the type's default); {@code effects} by key and amplifier;
     * a pig's {@code boost} (0 none, 1 started by the server's boost time and not counted since, -1 not known) and
     * {@code boostTotal} (its {@code DATA_BOOST_TIME}).
     */
    public record HorseMounted(long sequence, String type, boolean exact, Vec3d position, float yRot, float xRot, int steps, Vec3d target,
                               float targetYRot, float targetXRot, Vec3d previousPosition, boolean hasPreviousRotation, float previousYRot,
                               float previousXRot, int onGround, Vec3d groundSetAt, boolean noGravity, Vec3d motion, boolean everTicked,
                               Vec3d sweptMin, Vec3d sweptMax, long motionSeq, int saddle, int flags, int mobFlags, boolean baby, double scale,
                               Map<String, Double> attributes, Map<String, Integer> effects, int boost, int boostTotal) {}

    /** The type of the entity {@code entityId} once the client has processed everything sent so far (server order), or null. */
    public String type(int entityId) {
        Head head = heads.get(entityId);
        return head == null ? null : head.type;
    }

    /**
     * The client's state for the horse {@code entityId} at its player's mount, one per candidate (equal ones once); null
     * when not known for some candidate: the mount not processed in it, the horse not followed before (lost, or ridden
     * by the player before), or not a horse.
     */
    public List<HorseMounted> horseMounted(int entityId) {
        Track track = tracks.get(entityId);
        if (track == null) {
            return null;
        }
        List<HorseMounted> out = new ArrayList<>(1);
        for (State state : track.candidates) {
            if (state.horseMounted == null) {
                return null;
            }
            if (!out.contains(state.horseMounted)) {
                out.add(state.horseMounted);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * What a horse's or a pig's candidate holds for its player's mount beyond the rest of its state; immutable, shared by
     * copies. {@code motionKnown}: its motion ({@code State.motion}) is the client's; {@code motionSeq}: where it was last
     * set; {@code sweptMin} and {@code sweptMax}: where it has been since (its position's radius included). A pig's boost
     * ({@code ItemBasedSteering}): {@code boost} 0 none, 1 started and not counted since, -1 not known; {@code boostTotal}
     * its {@code DATA_BOOST_TIME}; {@code rider} its first passenger ({@code RIDER_NONE} ...).
     */
    private record Ridable(boolean motionKnown, long motionSeq, Vec3d sweptMin, Vec3d sweptMax, int saddle, int flags, int mobFlags,
                           Map<String, Double> attributes, Map<String, Integer> effects, int boost, int boostTotal, int rider) {

        static Ridable added(long seq, boolean motionKnown, double x, double y, double z) {
            Vec3d at = new Vec3d(x, y, z);
            // defineSynchedData: no flags, no saddle, no boost
            return new Ridable(motionKnown, seq, at, at, 0, 0, 0, Map.of(), Map.of(), 0, 0, RIDER_NONE);
        }

        Ridable motionSet(long seq, boolean known, double x, double y, double z, double r) {
            return new Ridable(known, seq, new Vec3d(x - r, y - r, z - r), new Vec3d(x + r, y + r, z + r), saddle, flags, mobFlags, attributes, effects,
                    boost, boostTotal, rider);
        }

        Ridable swept(double x, double y, double z, double r) {
            return new Ridable(motionKnown, motionSeq, new Vec3d(Math.min(sweptMin.x(), x - r), Math.min(sweptMin.y(), y - r), Math.min(sweptMin.z(), z - r)),
                    new Vec3d(Math.max(sweptMax.x(), x + r), Math.max(sweptMax.y(), y + r), Math.max(sweptMax.z(), z + r)), saddle, flags, mobFlags,
                    attributes, effects, boost, boostTotal, rider);
        }

        Ridable pushed() {
            return new Ridable(false, motionSeq, sweptMin, sweptMax, saddle, flags, mobFlags, attributes, effects, boost, boostTotal, rider);
        }

        Ridable withSaddle(int value) {
            return new Ridable(motionKnown, motionSeq, sweptMin, sweptMax, value, flags, mobFlags, attributes, effects, boost, boostTotal, rider);
        }

        Ridable withMobFlags(int value) {
            return new Ridable(motionKnown, motionSeq, sweptMin, sweptMax, saddle, flags, value, attributes, effects, boost, boostTotal, rider);
        }

        Ridable withFlags(int value) {
            return new Ridable(motionKnown, motionSeq, sweptMin, sweptMax, saddle, value, mobFlags, attributes, effects, boost, boostTotal, rider);
        }

        Ridable withAttribute(String key, double value) {
            Map<String, Double> m = new java.util.TreeMap<>(attributes);
            m.put(key, value);
            return new Ridable(motionKnown, motionSeq, sweptMin, sweptMax, saddle, flags, mobFlags, java.util.Collections.unmodifiableMap(m), effects,
                    boost, boostTotal, rider);
        }

        Ridable withEffect(String key, int amplifier) {
            Map<String, Integer> m = new java.util.TreeMap<>(effects);
            if (amplifier < 0) {
                m.remove(key);
            } else {
                m.put(key, amplifier);
            }
            return new Ridable(motionKnown, motionSeq, sweptMin, sweptMax, saddle, flags, mobFlags, attributes, java.util.Collections.unmodifiableMap(m),
                    boost, boostTotal, rider);
        }

        /**
         * {@code onSynced}: the server's boost time reached the client, which starts the boost from 0; one a player may
         * be steering counts it in their ticks, not followed here ({@code boost} -1).
         */
        Ridable boostSynced(int total) {
            return new Ridable(motionKnown, motionSeq, sweptMin, sweptMax, saddle, flags, mobFlags, attributes, effects,
                    rider == RIDER_SELF || rider == RIDER_PLAYER ? NONE : 1, total, rider);
        }

        /** Its first passenger now: a player in it may steer it, and a boost then counts in their ticks. */
        Ridable ridden(int value) {
            int b = boost == 1 && (value == RIDER_SELF || value == RIDER_PLAYER) ? NONE : boost;
            return new Ridable(motionKnown, motionSeq, sweptMin, sweptMax, saddle, flags, mobFlags, attributes, effects, b, boostTotal, value);
        }
    }

    /**
     * Every state the client may hold for {@code entityId} now: each candidate, after any further prefix
     * of the window the client may have processed since its last tick. Empty when the id is unknown.
     */
    public void forEachPossibleState(int entityId, Consumer<PossibleState> action) {
        Track track = tracks.get(entityId);
        if (track == null) {
            if (complete) {
                action.accept(new PossibleState(false, true, false, null, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NONE, null, null, 0, 0.0F));
            }
            return;
        }
        int applicable = applicable(track, windowEnd());
        for (State state : track.candidates) {
            for (State s : prefixes(track, state, applicable)) {
                action.accept(s.view());
            }
        }
    }

    /**
     * Whether the client may hold an entity of {@code type} whose position is within {@code radius} of the
     * point on every axis now: one there in some possible state, one of the type whose position is not known,
     * an entity whose type is not known, or entities WhyNotAC lost track of; or, for a type in
     * {@link #REMEMBERED_REMOVALS}, may have held one there until a removal sent at or after {@code since}.
     */
    public boolean mayHaveNear(String type, double x, double y, double z, double radius, long since) {
        if (!complete) {
            return true;
        }
        if (REMEMBERED_REMOVALS.contains(type)) {
            if (since <= removalsDropped) {
                return true;
            }
            for (java.util.Iterator<Object[]> it = removals.descendingIterator(); it.hasNext(); ) {
                Object[] r = it.next();
                if ((long) r[0] < since) {
                    break;
                }
                Vec3d p = (Vec3d) r[2];
                if (type.equals(r[1]) && (p == null || Math.abs(p.x() - x) <= radius && Math.abs(p.y() - y) <= radius && Math.abs(p.z() - z) <= radius)) {
                    return true;
                }
            }
        }
        boolean[] near = {false};
        for (Track track : tracks.values()) {
            Head head = heads.get(track.id);
            forEachPossibleState(track.id, st -> {
                if (near[0] || st.known() && !st.present()) {
                    return;
                }
                // a candidate lost in place keeps its type (as in push)
                String t = st.type() != null ? st.type() : head != null ? head.type : null;
                if (t != null && !t.equals(type)) {
                    return;
                }
                if (!st.known() || !st.positionKnown()) {
                    near[0] = true;
                } else {
                    double r = radius + st.positionRadius();
                    near[0] = Math.abs(st.x() - x) <= r && Math.abs(st.y() - y) <= r && Math.abs(st.z() - z) <= r;
                }
            });
            if (near[0]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether an entity the client may hold now could push the player or stop it ({@code LivingEntity.pushEntities},
     * {@code Entity.collide}'s entity colliders) in the box (inclusive bounds): a living entity, a boat or a
     * minecart (every type that is pushable or collidable for a player, decompiled 1.21.11) whose box in some
     * possible state, widened by its position's radius, meets the box. An entity of an unknown type or size
     * counts as such; one whose position the client does not take from the packets (boats and minecarts it
     * simulates) counts when the server's last position for it is within {@code unknownRadius} of the box. Not
     * counted: {@code self}, and entities WhyNotAC lost track of only if the replica is complete.
     */
    public boolean mayHavePusherNear(int self, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double unknownRadius) {
        return mayHaveNear(self, NONE, minX, minY, minZ, maxX, maxY, maxZ, unknownRadius, false);
    }

    /**
     * {@link #mayHavePusherNear} for a boat the player steers ({@code vehicle}): the entities it may meet
     * ({@code AbstractBoat.canVehicleCollide}: those it can collide with or that are pushable), but the player and itself.
     */
    public boolean mayHaveVehicleColliderNear(int self, int vehicle, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double unknownRadius) {
        return mayHaveNear(self, vehicle, minX, minY, minZ, maxX, maxY, maxZ, unknownRadius, false);
    }

    private boolean mayHaveNear(int self, int other, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double unknownRadius, boolean collidersOnly) {
        if (!complete) {
            return true;
        }
        boolean[] near = {false};
        for (Track track : tracks.values()) {
            if (track.id == self || track.id == other) {
                continue;
            }
            Head head = heads.get(track.id);
            forEachPossibleState(track.id, st -> {
                if (near[0] || st.known() && !st.present()) {
                    return;
                }
                String type = st.type() != null ? st.type() : head != null ? head.type : null;
                if (type != null && types.known(type) && !(collidersOnly ? collidesOrPushesItself(type) : pushesOrCollides(type))) {
                    return;
                }
                if (!st.known() || type == null || (st.positionKnown() ? st.bounds() == null : st.size() == null)) {
                    near[0] = true;
                    return;
                }
                if (!st.positionKnown()) {
                    Vec3d base = serverOrderBase(track.id);
                    near[0] = base == null || base.x() > minX - unknownRadius && base.x() < maxX + unknownRadius && base.y() > minY - unknownRadius
                            && base.y() < maxY + unknownRadius && base.z() > minZ - unknownRadius && base.z() < maxZ + unknownRadius;
                    return;
                }
                EntitySizes.Box b = st.bounds(); // the position's radius included
                near[0] = b.minX() <= maxX && b.maxX() >= minX && b.minY() <= maxY && b.maxY() >= minY && b.minZ() <= maxZ && b.maxZ() >= minZ;
            });
            if (near[0]) {
                return true;
            }
        }
        return false;
    }

    /** Living entities are pushable (armour stands too, conservatively), boats collide and push, minecarts push. */
    private boolean pushesOrCollides(String type) {
        return types.kind(type).living() || collidesOrPushesItself(type);
    }

    /**
     * Types that stop the player's movement or push it otherwise than {@link #pushers} follows: boats collide
     * ({@code canBeCollidedWith}) and push in their tick, minecarts push, shulkers and happy ghasts collide.
     */
    private static boolean collidesOrPushesItself(String type) {
        return type.endsWith("_boat") || type.endsWith("_raft") || type.equals("minecraft:minecart") || type.endsWith("_minecart")
                || type.equals("minecraft:shulker") || type.equals("minecraft:happy_ghast");
    }

    /**
     * {@link #mayHavePusherNear} for the entities that collide with the player or push it otherwise than living entities
     * do in their tick ({@link #collidesOrPushesItself}), and those of a type or size not known: the living ones that
     * push are followed by {@link #pushers}.
     */
    public boolean mayHaveColliderNear(int self, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double unknownRadius) {
        return mayHaveNear(self, NONE, minX, minY, minZ, maxX, maxY, maxZ, unknownRadius, true);
    }

    /**
     * Living types whose tick pushes no player ({@code LivingEntity.pushEntities} and {@code doPush} overridden, decompiled
     * 1.21.11): bats push nothing, armour stands only minecarts, parrots no player; vexes (in their tick) and the ender
     * dragon have no physics ({@code Entity.push} skips them).
     */
    static final Set<String> PUSH_NO_PLAYER = Set.of("minecraft:bat", "minecraft:armor_stand", "minecraft:parrot", "minecraft:vex",
            "minecraft:ender_dragon");
    /** {@code TamableAnimal.getTeam}: a tamed one without a team of its own is in its owner's. */
    static final Set<String> TAMABLE = Set.of("minecraft:wolf", "minecraft:cat", "minecraft:parrot", "minecraft:nautilus", "minecraft:zombie_nautilus");

    /**
     * A living entity whose tick just pushed the client's own player, or would have if teams and the player allow it
     * ({@code LivingEntity.pushEntities}: its box met the player's): where it is, and its scoreboard name (another
     * entity's UUID; null where not known: a player's profile name, a tamable animal's owner's team).
     */
    public record Pusher(int id, double x, double z, String scoreboardName) {}

    /**
     * The living entities whose tick in the client's last tick met the player's box {@code (minX..maxZ)} (strictly, as
     * {@code AABB.intersects}), of those ticking after the player ({@code beforeSelf} false) or before it: the client
     * ticks its entities in the order they joined its tick list ({@code EntityTickList}, linked). The player joins at its
     * creation ({@code Login}, {@code Respawn}); players are always ticking and join when added; any other entity joins
     * when added in a ticking chunk or later, so every entity added after the player ticks after it. Null when not known:
     * an entity of a pushing type that may meet the box with its position, size, tick or order not known exactly, one
     * WhyNotAC lost, a collider ({@link #collidesOrPushesItself}) that may meet it, or the player's own creation not
     * certainly processed. Read right after the tick (the candidates as the tick left them).
     */
    public List<Pusher> pushers(int self, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, boolean beforeSelf,
            double unknownRadius) {
        if (!complete) {
            whyNotKnown = "entities WhyNotAC lost track of";
            return null;
        }
        List<Pusher> out = new ArrayList<>(0);
        for (Track track : tracks.values()) {
            if (track.id == self) {
                continue;
            }
            Head head = heads.get(track.id);
            Pusher effect = null;
            boolean first = true;
            for (State st : track.candidates) {
                Pusher e;
                try {
                    e = push(track.id, st, head, minX, minY, minZ, maxX, maxY, maxZ, beforeSelf, unknownRadius);
                } catch (NotKnown unknown) {
                    whyNotKnown = unknown.getMessage();
                    return null;
                }
                if (!first && !java.util.Objects.equals(effect, e)) {
                    whyNotKnown = "an entity whose possible states push differently";
                    return null;
                }
                first = false;
                effect = e;
            }
            if (effect != null) {
                out.add(effect);
            }
        }
        if (!out.isEmpty() && !self().passengers.isEmpty()) {
            whyNotKnown = "the player carries passengers"; // Entity.push: not while it does
            return null;
        }
        return out;
    }

    /** Why the last {@link #pushers} that returned null did. */
    public String whyNotKnown() {
        return whyNotKnown;
    }

    /** How far from the player, on either axis, an attached rocket's chunk is looked at ({@link #rocketsBoosting}): a tick's glide and the hand. */
    static final double ROCKET_CHUNK_MARGIN = 4.0;

    /**
     * How many firework rockets attached to the client's own player boosted it in the client's last tick
     * ({@code FireworkRocketEntity.tick}: each adds to a gliding player's motion), the player being at {@code (x, z)}
     * after its own tick; null when not known (see {@link #whyNotKnown}). A rocket counts when it is present, attached
     * to the player (its target in the entity data), added after the player was made (it ticks after it and its target
     * is this player), and ticked: not frozen, and every chunk within {@link #ROCKET_CHUNK_MARGIN} of the player
     * ticking (the rocket sits where the player was). Not known: a rocket that may or may not be attached, ticked or
     * present, one from before the player was made, an entity of a type not known, or entities lost.
     */
    public Integer rocketsBoosting(int self, double x, double z) {
        if (!complete) {
            whyNotKnown = "entities WhyNotAC lost track of";
            return null;
        }
        int count = 0;
        for (Track track : tracks.values()) {
            if (track.id == self) {
                continue;
            }
            Head head = heads.get(track.id);
            Boolean boosts = null;
            for (State st : track.candidates) {
                boolean b;
                try {
                    b = boosts(st, head, self, x, z);
                } catch (NotKnown unknown) {
                    whyNotKnown = unknown.getMessage();
                    return null;
                }
                if (boosts != null && boosts != b) {
                    whyNotKnown = "a firework rocket that may or may not have boosted the player";
                    return null;
                }
                boosts = b;
            }
            if (Boolean.TRUE.equals(boosts)) {
                count++;
            }
        }
        return count;
    }

    /** One candidate's boost ({@link #rocketsBoosting}); throws where it is not known. */
    private boolean boosts(State st, Head head, int self, double x, double z) throws NotKnown {
        if (!st.lostAll && !st.present) {
            return false;
        }
        String type = st.type != null ? st.type : head != null ? head.type : null;
        if (type != null && types.known(type) && !FIREWORK_ROCKET.equals(type)) {
            return false;
        }
        if (type == null || st.lostAll && st.type == null) {
            throw new NotKnown("an entity WhyNotAC lost track of");
        }
        // a rocket: its target is set once, when it is made (FireworkRocketEntity's constructor, the pairing data)
        if (st.attached == ATTACHED_UNKNOWN) {
            throw new NotKnown("a firework rocket whose target is not known");
        }
        if (st.attached != self) {
            return false; // flying by itself (lost in place, as it moves itself) or attached to another entity
        }
        if (st.lostAll) {
            throw new NotKnown("a firework rocket attached to the player WhyNotAC lost track of");
        }
        if (selfCreated >= certain || st.addedSeq <= selfCreated) {
            throw new NotKnown("a firework rocket attached to the player from before the player was made");
        }
        if (!st.tickedKnown) {
            throw new NotKnown("a firework rocket attached to the player that may or may not have ticked");
        }
        if (!st.ticked) {
            return false;
        }
        Tri ticking = null;
        for (int cx = VanillaMath.floor(x - ROCKET_CHUNK_MARGIN) >> 4; cx <= VanillaMath.floor(x + ROCKET_CHUNK_MARGIN) >> 4; cx++) {
            for (int cz = VanillaMath.floor(z - ROCKET_CHUNK_MARGIN) >> 4; cz <= VanillaMath.floor(z + ROCKET_CHUNK_MARGIN) >> 4; cz++) {
                Tri chunk = chunks.ticking(ClientWorld.chunkKey(cx, cz), st.tickLower, st.tickUpper);
                if (ticking != null && chunk != ticking || chunk == Tri.MAYBE) {
                    throw new NotKnown("a firework rocket attached to the player near chunks that may not tick");
                }
                ticking = chunk;
            }
        }
        return ticking == Tri.TRUE;
    }

    /** One candidate's push at the box ({@link #pushers}): the pusher, or null for none; throws where it is not known. */
    private Pusher push(int id, State st, Head head, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            boolean beforeSelf, double unknownRadius) throws NotKnown {
        if (!st.lostAll && !st.present) {
            return null;
        }
        // A candidate lost in place keeps its type (a non-living entity the client moves, which may have fallen out of the world).
        String type = st.type != null ? st.type : head != null ? head.type : null;
        if (type != null && types.known(type) && (!types.kind(type).living() && !collidesOrPushesItself(type) || PUSH_NO_PLAYER.contains(type))) {
            return null;
        }
        if (st.lostAll || type == null) {
            throw new NotKnown(type == null ? "an entity WhyNotAC lost track of" : type + " WhyNotAC lost track of");
        }
        if (st.lostPosition) {
            Vec3d base = serverOrderBase(id);
            if (base == null || base.x() > minX - unknownRadius && base.x() < maxX + unknownRadius && base.y() > minY - unknownRadius
                    && base.y() < maxY + unknownRadius && base.z() > minZ - unknownRadius && base.z() < maxZ + unknownRadius) {
                throw new NotKnown(type + " whose position the client does not take from the packets");
            }
            return null;
        }
        PossibleState view = st.view();
        EntitySizes.Box bounds = view.bounds(); // the position's radius included
        if (bounds == null) {
            if (st.x > minX - unknownRadius && st.x < maxX + unknownRadius && st.y > minY - unknownRadius && st.y < maxY + unknownRadius
                    && st.z > minZ - unknownRadius && st.z < maxZ + unknownRadius) {
                throw new NotKnown(type + " of a size not known");
            }
            return null;
        }
        if (!(bounds.minX() <= maxX && bounds.maxX() >= minX && bounds.minY() <= maxY && bounds.maxY() >= minY && bounds.minZ() <= maxZ
                && bounds.maxZ() >= minZ)) {
            return null; // cannot meet the box
        }
        double r = st.rPos;
        if (r != 0 || view.size() == null || !st.tickedKnown || collidesOrPushesItself(type)) {
            throw new NotKnown(collidesOrPushesItself(type) ? type + ", which collides" : r != 0 ? type + " whose position is not known exactly"
                    : view.size() == null ? type + " whose size is not known exactly" : type + " that may or may not have ticked");
        }
        EntitySizes.Box b = view.size().box(st.x, st.y, st.z);
        boolean meets = minX < b.maxX() && maxX > b.minX() && minY < b.maxY() && maxY > b.minY() && minZ < b.maxZ() && maxZ > b.minZ();
        if (!meets || !st.ticked || st.player && st.spectator == 1) {
            return null; // apart, not ticked, or a spectator (noPhysics)
        }
        if (st.player && st.spectator != 0) {
            throw new NotKnown("a player that may be a spectator");
        }
        if (selfCreated >= certain) {
            throw new NotKnown("the player itself may have been made again"); // since the last certain point
        }
        // Its place in the tick list against the player's.
        boolean before;
        if (st.addedSeq > selfCreated) {
            before = false;
        } else if (st.player) {
            before = true;
        } else {
            throw new NotKnown(type + " from before the player was made (its place in the tick list is not followed)");
        }
        if (before != beforeSelf) {
            return null;
        }
        return new Pusher(id, st.x, st.z, st.player || TAMABLE.contains(type) || st.uuid == null ? null : st.uuid.toString());
    }

    /** {@link #push}: not known, and why. */
    private static final class NotKnown extends Exception {
        NotKnown(String reason) {
            super(reason, null, false, false);
        }
    }

    private Head self() {
        return self;
    }

    /** Whether the client has this entity now. */
    public Existence existence(int entityId) {
        boolean[] seen = new boolean[3]; // present, absent, unknown
        forEachPossibleState(entityId, s -> seen[!s.known() ? 2 : s.present() ? 0 : 1] = true);
        if (seen[2] || !seen[0] && !seen[1]) {
            return seen[0] || seen[1] ? Existence.MAYBE : Existence.UNKNOWN;
        }
        return seen[0] && seen[1] ? Existence.MAYBE : seen[0] ? Existence.PRESENT : Existence.ABSENT;
    }

    /**
     * Where the client has the entity now, if it certainly has it at one exactly known position;
     * null otherwise (absent, unknown, or several possibilities).
     */
    public Vec3d exactPosition(int entityId) {
        Vec3d[] position = {null};
        boolean[] exact = {true, false};
        forEachPossibleState(entityId, s -> {
            exact[1] = true;
            if (!s.present() || !s.known() || !s.positionKnown() || s.positionRadius() != 0) {
                exact[0] = false;
                return;
            }
            Vec3d p = new Vec3d(s.x(), s.y(), s.z());
            if (position[0] == null) {
                position[0] = p;
            } else if (!position[0].equals(p)) {
                exact[0] = false;
            }
        });
        return exact[0] && exact[1] ? position[0] : null;
    }

    /**
     * The codec base the client keeps for the entity once it has processed everything sent so far (server
     * order), or null if WhyNotAC does not know it. The server keeps the same base per entity
     * ({@code ServerEntity.positionCodec}), which makes it checkable at any quiet moment.
     */
    public Vec3d serverOrderBase(int entityId) {
        Head head = heads.get(entityId);
        return head == null || !head.baseKnown ? null : new Vec3d(head.bx, head.by, head.bz);
    }

    /** Entity ids the server has added to this client's level and not removed (server order). */
    public Set<Integer> serverOrderEntities() {
        return Set.copyOf(heads.keySet());
    }

    /** The vehicle of {@code entityId} once the client processed everything sent so far, or -1. */
    public int serverOrderVehicle(int entityId) {
        Head head = heads.get(entityId);
        return head == null ? NONE : head.vehicle;
    }

    /**
     * The size the client gives {@code entityId} now, if every state it may hold has it present with the
     * same known size; null otherwise (absent, unknown, a size the table does not follow, or several).
     */
    public EntitySizes.Size size(int entityId) {
        EntitySizes.Size[] size = {null};
        boolean[] one = {true, false};
        forEachPossibleState(entityId, s -> {
            one[1] = true;
            if (!s.present() || !s.known() || s.size() == null || size[0] != null && !size[0].equals(s.size())) {
                one[0] = false;
            } else {
                size[0] = s.size();
            }
        });
        return one[0] && one[1] ? size[0] : null;
    }

    /**
     * Whether the client can pick {@code entityId} now ({@link EntitySizes#pickable}): 1 if every state it
     * may hold has it present and pickable, 0 if none does, -1 otherwise (unknown, or the states disagree).
     */
    public int pickable(int entityId) {
        int[] seen = {0, 0}; // pickable states, not pickable (or absent) states
        boolean[] unknown = {false};
        forEachPossibleState(entityId, s -> {
            if (s.pickable() < 0) {
                unknown[0] = true;
            } else {
                seen[s.pickable()]++;
            }
        });
        return unknown[0] || seen[0] > 0 && seen[1] > 0 ? NONE : seen[1] > 0 ? 1 : 0;
    }

    /**
     * Everything the server has added to this client's level and not removed, as the client will hold it
     * once it has processed everything sent so far: per entity id its type, codec base (null if unknown)
     * and vehicle (-1: none). For comparing with the server's own tracking.
     */
    public ServerOrder serverOrder() {
        Map<Integer, ServerOrder.Entity> entities = new HashMap<>();
        for (Head head : heads.values()) {
            entities.put(head.id, new ServerOrder.Entity(head.type, head.baseKnown ? new Vec3d(head.bx, head.by, head.bz) : null, head.vehicle,
                    knowledge(tracks.get(head.id)), size(head.id), pickable(head.id)));
        }
        return new ServerOrder(level, complete, Map.copyOf(entities), stats());
    }

    /** What the replica knows of the client's state of one entity now, in a word (diagnostics). */
    private static String knowledge(Track track) {
        if (track == null) {
            return "untracked";
        }
        boolean lostAll = false;
        boolean lostPosition = false;
        double radius = 0;
        for (State s : track.candidates) {
            lostAll |= s.lostAll;
            lostPosition |= s.present && s.lostPosition;
            radius = Math.max(radius, s.rPos);
        }
        if (lostAll) {
            return "unknown";
        }
        if (lostPosition) {
            return "position-unknown";
        }
        if (track.candidates.size() > 1) {
            return track.candidates.size() + "-candidates";
        }
        return radius == 0 ? "exact" : "within-" + radius;
    }

    public record ServerOrder(boolean level, boolean complete, Map<Integer, Entity> entities, Stats stats) {
        /**
         * {@code knowledge}: "exact", "within-r", "n-candidates", "position-unknown" or "unknown".
         * {@code size}: the size the client gives it now, in client order (see {@link ClientEntities#size}).
         */
        /** {@code pickable}: {@link #pickable(int)}. */
        public record Entity(String type, Vec3d base, int vehicle, String knowledge, EntitySizes.Size size, int pickable) {}
    }

    /** Counters for diagnostics. */
    public Stats stats() {
        int candidates = 0;
        int lost = 0;
        int exact = 0;
        int pending = 0;
        for (Track track : tracks.values()) {
            candidates += track.candidates.size();
            pending += track.pending.size();
            boolean anyLost = false;
            for (State s : track.candidates) {
                anyLost |= s.lostAll || s.present && s.lostPosition;
            }
            if (anyLost) {
                lost++;
            } else if (track.candidates.size() == 1 && track.candidates.getFirst().present && track.candidates.getFirst().rPos == 0) {
                exact++;
            }
        }
        return new Stats(tracks.size(), candidates, exact, lost, pending, ticks, branches, merges, overflows, losses);
    }

    public record Stats(int tracked, int candidates, int exact, int lost, int pendingOps, long ticks, long branches, long merges, long overflows,
                        long losses) {}

    // --- internals --------------------------------------------------------------------------------------------------

    private enum Tri {
        TRUE, FALSE, MAYBE;

        Tri and(Tri other) {
            if (this == FALSE || other == FALSE) {
                return FALSE;
            }
            return this == TRUE && other == TRUE ? TRUE : MAYBE;
        }
    }

    private static final int FROZEN_NO = 0;
    private static final int FROZEN_YES = 1;
    private static final int FROZEN_MAYBE = 2;

    /** What the server has told the client about one entity, in server order. */
    private static final class Head {
        final int id;
        final EntityTypes.Kind kind;
        final String type;
        final boolean player;
        /** A player's profile; {@code infoReplaced}: its PlayerInfo was removed while the entity existed. */
        UUID uuid;
        boolean infoReplaced;
        double bx;
        double by;
        double bz;
        boolean baseKnown = true;
        int vehicle = NONE;
        final List<Integer> passengers = new ArrayList<>(1);

        Head(int id, EntityTypes.Kind kind, String type, boolean player) {
            this.id = id;
            this.kind = kind;
            this.type = type;
            this.player = player;
        }

        void base(Vec3d p) {
            bx = p.x();
            by = p.y();
            bz = p.z();
            baseKnown = true;
        }
    }

    /** One entity id in client order: its candidates and the ops the client may not have processed. */
    private static final class Track {
        final int id;
        List<State> candidates = new ArrayList<>(1);
        final ArrayList<Op> pending = new ArrayList<>();

        Track(int id, State initial) {
            this.id = id;
            candidates.add(initial);
        }
    }

    /** What the client does with one packet for one entity, in the order it processes them. */
    private sealed interface Op {
        int KIND_REMOVE = 0;
        int KIND_LOSE_ALL = 1;
        int KIND_LOSE_POSITION = 2;
        Op REMOVE = new Simple(Long.MIN_VALUE, KIND_REMOVE);
        Op LOSE_ALL = new Simple(Long.MIN_VALUE, KIND_LOSE_ALL);
        Op LOSE_POSITION = new Simple(Long.MIN_VALUE, KIND_LOSE_POSITION);

        long seq();

        /** The same op placed at {@code seq} (shared ops carry no sequence of their own). */
        default Op at(long seq) {
            return this;
        }

        record Simple(long seq, int kind) implements Op {
            @Override
            public Op at(long seq) {
                return new Simple(seq, kind);
            }
        }

        /** {@code spectator}: for a player, whether its PlayerInfo says spectator (1, 0, -1 unknown). */
        record Add(long seq, EntityTypes.Kind kind, String type, boolean player, int minY, double x, double y, double z, float yRot, float xRot,
                   float yHeadRot, boolean maybe, int spectator, UUID uuid, Vec3d motion) implements Op {}

        /** A player's PlayerInfo changed its game mode: spectator 1, 0, or -1 when no longer known. */
        record GameMode(long seq, int spectator) implements Op {}

        /** {@code position} null: rotation only; {@code positionKnown} false: decoded against a lost base. */
        record Move(long seq, Vec3d position, boolean positionKnown, boolean hasRotation, float yRot, float xRot, boolean onGround) implements Op {}

        record Sync(long seq, Vec3d position, float yRot, float xRot, boolean onGround) implements Op {}

        /** {@code delta}: the packet's motion (null: an old recording's, not known). */
        record Teleport(long seq, Vec3d position, float yRot, float xRot, Set<RelativeFlag> relatives, boolean onGround, Vec3d delta) implements Op {}

        record HeadRotation(long seq, float yHeadRot) implements Op {}

        /** -1: not in the packet. {@code baby}: 0 or 1. {@code dataSet}: which of the size's data values are in it (bit 0, bit 1). */
        /** {@code attached}: a firework rocket's target ({@code -1}: none), {@code UNSET} when the packet does not set it. */
        /** {@code horseFlags} and {@code mobFlags}: a horse's {@code DATA_ID_FLAGS} and {@code DATA_MOB_FLAGS_ID}, -1 when not in the packet. */
        /** {@code pigBoost}: a pig's {@code DATA_BOOST_TIME}, {@code UNSET} when the packet does not set it. */
        record Data(long seq, int pose, int flags, int baby, int dataSet, int data0, int data1, int attached, int noGravity, int horseFlags,
                    int mobFlags, int pigBoost) implements Op {}

        /** {@code SetEntityMotion} for a boat (its motion until its next tick) or a horse. */
        record Motion(long seq, Vec3d motion) implements Op {}

        /** A horse's saddle slot ({@code SetEquipment}): 1 a saddle, 0 none, -1 not known. */
        record Saddle(long seq, int saddle) implements Op {}

        /** One of {@link #HORSE_ATTRIBUTES} of a horse, its value (NaN: not known exactly). */
        record HorseAttribute(long seq, String key, double value) implements Op {}

        /** An effect on a horse ({@code UpdateMobEffect}, its amplifier), or taken off it (-1). */
        record Effect(long seq, String key, int amplifier) implements Op {}

        /** The client's own player became the controlling passenger of this vehicle: from now on it moves it itself. */
        record Control(long seq) implements Op {}

        /** A pig's first passenger after {@code SetPassengers}: {@code RIDER_NONE}, {@code RIDER_SELF}, {@code RIDER_PLAYER} or {@code RIDER_OTHER}. */
        record Rider(long seq, int rider) implements Op {}

        /** The scale attribute's value; NaN when it is not known exactly. */
        record Scale(long seq, double value) implements Op {}

        record Vehicle(long seq, int vehicleId) implements Op {}
    }

    /** One possible client-side state of one entity id. Mutable; copied when the client's path forks. */
    private static final class State {
        boolean present;
        /** Nothing is known about this id (lost events, or too many possibilities). */
        boolean lostAll;
        /** Present, but where it is is not known (a passenger, a vehicle the player may control, client physics). */
        boolean lostPosition;
        EntityTypes.Kind kind;
        String type;
        boolean player;
        int minY;
        double x;
        double y;
        double z;
        double rPos;
        float yRot;
        float xRot;
        float yHeadRot;
        /** Bounds yRot and xRot (and their interpolation's previous values); the head has its own. */
        float rRot;
        float rHead;
        boolean onGround;
        /** False once candidates that disagree on it were merged (it decides nothing about where the entity is). */
        boolean onGroundKnown = true;
        int pose = POSE_STANDING;
        int flags;
        int vehicle = NONE;
        // the size: isBaby(), the scale attribute's value (NaN: not known exactly) and the type's size data (EntitySizes.Entry.data)
        boolean baby;
        double scale = 1.0;
        /**
         * The values the scale attribute had since the entity's last tick (NaN: not known exactly): the cached dimensions
         * follow a new value at the end of the entity's next tick ({@code LivingEntity.refreshDirtyAttributes}), or at a
         * refresh before it (a pose or size data change) at the value the attribute had then.
         */
        double scaleLow = 1.0;
        double scaleHigh = 1.0;
        int data0;
        int data1;
        /** The type's size follows the client's own animation ({@link EntitySizes.Rule#POLAR_BEAR}). */
        boolean animated;
        /**
         * {@code PolarBear.clientSideStandAnimation} and its previous value ({@code clientSideStandAnimationO}). The
         * dimensions were refreshed at one of the two: at the start of the entity's last tick when the animation had
         * moved, or later at its value then (see {@link #view}).
         */
        int anim;
        int animO;
        /** A player's: 1 a spectator in the client's player list, 0 not, -1 not known. */
        int spectator;
        // InterpolationHandler
        int steps;
        double tx;
        double ty;
        double tz;
        double rTarget;
        float tyRot;
        float txRot;
        float rTargetRot;
        boolean hasPrevPos;
        double px;
        double py;
        double pz;
        double rPrev;
        boolean posMoved;
        boolean hasPrevRot;
        float pxRot;
        float pyRot;
        float rPrevRot;
        boolean rotMoved;
        // LivingEntity head
        int headSteps;
        double headTarget;
        /** The entity ticked in the client's last tick ({@code ClientLevel.tickEntities}); {@code tickedKnown} false once candidates that disagree on it were merged. */
        boolean ticked;
        boolean tickedKnown = true;
        /** A firework rocket's {@code DATA_ATTACHED_TO_TARGET}: the entity id, or {@code -1} for none. */
        int attached = NONE;
        /** {@code Entity.DATA_NO_GRAVITY}. */
        boolean noGravity;
        // A boat the client does not control (ClientMotion.interpolatesOnly), for the movement check at its player's mount:
        /** Its motion from a {@code SetEntityMotion} since its last tick, or null (its tick set it to zero). */
        Vec3d motion;
        /** It has ticked: {@code Entity.firstTick} is over. */
        boolean everTicked;
        /** Where its last tick started ({@code tickStartLower}: the client had processed every packet up to there by then). */
        boolean hasTickStart;
        double sx;
        double sy;
        double sz;
        double rStart;
        long tickStartLower;
        /** Where it was when a packet last set its ground flag ({@code Entity.setOnGround}, which finds its supporting block there). */
        boolean hasGround;
        double gx;
        double gy;
        double gz;
        double rGround;
        /** Its state when the client's own player became its controlling passenger, or null. */
        Mounted mounted;
        /** A horse's ({@link ClientMotion#HORSES}) state for its player's mount beyond the rest; null for every other type. */
        Ridable horse;
        /** A horse's state when the client's own player became its controlling passenger, or null. */
        HorseMounted horseMounted;
        /**
         * The prefixes its last tick could fall in, for a rocket attached to the player ({@link #rocketsBoosting}): its
         * chunk is where the player was, which the replica does not hold.
         */
        long tickLower;
        long tickUpper;
        /** The sequence of the {@code AddEntity} that made this entity object: where it joined the client's tick list relative to the player. */
        long addedSeq = Long.MIN_VALUE;
        UUID uuid;
        /** Pending ops of the track this candidate has processed. */
        int applied;

        static State absent() {
            return new State();
        }

        /** A packet set the ground flag of a boat or a horse (see {@link #hasGround}). */
        void groundSet() {
            if (ClientMotion.keptAtMount(type)) {
                hasGround = true;
                gx = x;
                gy = y;
                gz = z;
                rGround = rPos;
            }
        }

        /** This candidate as {@link Mounted}, at the mount processed at {@code seq}. */
        Mounted mounted(long seq) {
            boolean active = steps > 0;
            boolean exact = rPos == 0.0 && rRot == 0.0 && onGroundKnown && tickedKnown && (!active || rTarget == 0.0 && rTargetRot == 0.0)
                    && (!hasPrevPos || !posMoved || rPrev == 0.0) && (!hasPrevRot || !rotMoved || rPrevRot == 0.0)
                    && (!hasTickStart || rStart == 0.0) && (!hasGround || rGround == 0.0);
            Vec3d previous = !hasPrevPos ? null : posMoved ? new Vec3d(px, py, pz) : new Vec3d(x, y, z);
            return new Mounted(seq, type, exact, new Vec3d(x, y, z), yRot, xRot, steps, new Vec3d(tx, ty, tz), tyRot, txRot, previous,
                    hasPrevRot, rotMoved ? pyRot : yRot, rotMoved ? pxRot : xRot, onGroundKnown ? (onGround ? 1 : 0) : -1,
                    hasGround ? new Vec3d(gx, gy, gz) : null, noGravity, motion, everTicked, hasTickStart ? new Vec3d(sx, sy, sz) : null,
                    tickStartLower);
        }

        /** This horse candidate as {@link HorseMounted}, at the mount processed at {@code seq}. */
        HorseMounted horseMounted(long seq) {
            boolean active = steps > 0;
            boolean exact = rPos == 0.0 && rRot == 0.0 && onGroundKnown && tickedKnown && (!active || rTarget == 0.0 && rTargetRot == 0.0)
                    && (!hasPrevPos || !posMoved || rPrev == 0.0) && (!hasPrevRot || !rotMoved || rPrevRot == 0.0)
                    && (!hasGround || rGround == 0.0) && Double.compare(scaleLow, scaleHigh) == 0 && Double.compare(scale, scaleLow) == 0;
            Vec3d previous = !hasPrevPos ? null : posMoved ? new Vec3d(px, py, pz) : new Vec3d(x, y, z);
            return new HorseMounted(seq, type, exact, new Vec3d(x, y, z), yRot, xRot, steps, new Vec3d(tx, ty, tz), tyRot, txRot, previous,
                    hasPrevRot, rotMoved ? pyRot : yRot, rotMoved ? pxRot : xRot, onGroundKnown ? (onGround ? 1 : 0) : -1,
                    hasGround ? new Vec3d(gx, gy, gz) : null, noGravity, horse.motionKnown() ? motion : null, everTicked, horse.sweptMin(),
                    horse.sweptMax(), horse.motionSeq(), horse.saddle(), horse.flags(), horse.mobFlags(), baby, scale, horse.attributes(),
                    horse.effects(), horse.boost(), horse.boostTotal());
        }

        static State lostAll() {
            State s = new State();
            s.lostAll = true;
            return s;
        }

        /** {@code recreateFromPacket}: a new entity object. */
        static State added(Op.Add add) {
            State s = new State();
            s.present = true;
            s.addedSeq = add.seq();
            s.uuid = add.uuid();
            s.kind = add.kind();
            s.type = add.type();
            s.player = add.player();
            s.spectator = add.spectator();
            s.minY = add.minY();
            if (add.kind().living()) {
                // LivingEntity.recreateFromPacket: head and body from the head angle, absSnapTo.
                s.yHeadRot = add.yHeadRot();
                s.x = VanillaMath.clamp(add.x(), -HORIZONTAL_LIMIT, HORIZONTAL_LIMIT);
                s.y = add.y();
                s.z = VanillaMath.clamp(add.z(), -HORIZONTAL_LIMIT, HORIZONTAL_LIMIT);
                setYRot(s, add.yRot() % 360.0F);
                setXRot(s, VanillaMath.clamp(add.xRot(), -90.0F, 90.0F) % 360.0F);
            } else {
                // Entity.recreateFromPacket: snapTo.
                s.x = add.x();
                s.y = add.y();
                s.z = add.z();
                setYRot(s, add.yRot());
                setXRot(s, add.xRot());
            }
            // Types the client moves itself, or places other than at the packet's position (ClientMotion).
            s.lostPosition = ClientMotion.movesItself(add.type(), add.kind());
            if (add.type() != null && ClientMotion.RIDDEN.contains(add.type())) {
                // Entity.recreateFromPacket: setDeltaMovement(the packet's movement)
                s.motion = add.motion();
                s.horse = Ridable.added(add.seq(), add.motion() != null && add.motion().isFinite(), s.x, s.y, s.z);
            }
            EntitySizes.Entry size = EntitySizes.vanilla().entry(add.type());
            if (size != null) {
                s.data0 = size.initial(0); // defineSynchedData
                s.data1 = size.initial(1);
                s.animated = size.rule() == EntitySizes.Rule.POLAR_BEAR;
            }
            return s;
        }

        boolean isAbsent() {
            return !present && !lostAll;
        }

        State copy() {
            State c = new State();
            c.present = present;
            c.lostAll = lostAll;
            c.lostPosition = lostPosition;
            c.kind = kind;
            c.type = type;
            c.player = player;
            c.minY = minY;
            c.x = x;
            c.y = y;
            c.z = z;
            c.rPos = rPos;
            c.yRot = yRot;
            c.xRot = xRot;
            c.yHeadRot = yHeadRot;
            c.rRot = rRot;
            c.rHead = rHead;
            c.onGround = onGround;
            c.onGroundKnown = onGroundKnown;
            c.pose = pose;
            c.flags = flags;
            c.vehicle = vehicle;
            c.baby = baby;
            c.scale = scale;
            c.scaleLow = scaleLow;
            c.scaleHigh = scaleHigh;
            c.data0 = data0;
            c.data1 = data1;
            c.animated = animated;
            c.anim = anim;
            c.animO = animO;
            c.spectator = spectator;
            c.steps = steps;
            c.tx = tx;
            c.ty = ty;
            c.tz = tz;
            c.rTarget = rTarget;
            c.tyRot = tyRot;
            c.txRot = txRot;
            c.rTargetRot = rTargetRot;
            c.hasPrevPos = hasPrevPos;
            c.px = px;
            c.py = py;
            c.pz = pz;
            c.rPrev = rPrev;
            c.posMoved = posMoved;
            c.hasPrevRot = hasPrevRot;
            c.pxRot = pxRot;
            c.pyRot = pyRot;
            c.rPrevRot = rPrevRot;
            c.rotMoved = rotMoved;
            c.headSteps = headSteps;
            c.headTarget = headTarget;
            c.ticked = ticked;
            c.tickedKnown = tickedKnown;
            c.attached = attached;
            c.noGravity = noGravity;
            c.motion = motion;
            c.everTicked = everTicked;
            c.hasTickStart = hasTickStart;
            c.sx = sx;
            c.sy = sy;
            c.sz = sz;
            c.rStart = rStart;
            c.tickStartLower = tickStartLower;
            c.hasGround = hasGround;
            c.gx = gx;
            c.gy = gy;
            c.gz = gz;
            c.rGround = rGround;
            c.mounted = mounted;
            c.horse = horse;
            c.horseMounted = horseMounted;
            c.tickLower = tickLower;
            c.tickUpper = tickUpper;
            c.addedSeq = addedSeq;
            c.uuid = uuid;
            c.applied = applied;
            return c;
        }

        /** Same discrete state and the same targets: the client cannot tell them apart from now on but by where they are. */
        boolean mergeable(State o) {
            if (applied != o.applied || present != o.present || lostAll != o.lostAll) {
                return false;
            }
            if (!present) {
                return true;
            }
            if (lostAll) {
                // lost in place: the type stays known, and a boat's state at its player's mount
                return java.util.Objects.equals(type, o.type) && attached == o.attached && java.util.Objects.equals(mounted, o.mounted)
                        && java.util.Objects.equals(horseMounted, o.horseMounted);
            }
            if (kind != o.kind || !java.util.Objects.equals(type, o.type) || player != o.player || addedSeq != o.addedSeq || minY != o.minY
                    || lostPosition != o.lostPosition
                    || vehicle != o.vehicle || pose != o.pose || flags != o.flags || baby != o.baby || Double.compare(scale, o.scale) != 0
                    || Double.compare(scaleLow, o.scaleLow) != 0 || Double.compare(scaleHigh, o.scaleHigh) != 0
                    || data0 != o.data0 || data1 != o.data1 || anim != o.anim || animO != o.animO || spectator != o.spectator
                    || attached != o.attached || noGravity != o.noGravity || !java.util.Objects.equals(mounted, o.mounted)
                    || !java.util.Objects.equals(horse, o.horse) || !java.util.Objects.equals(horseMounted, o.horseMounted)) {
                return false;
            }
            if (ClientMotion.keptAtMount(type) && (!java.util.Objects.equals(motion, o.motion) || everTicked != o.everTicked
                    || hasTickStart != o.hasTickStart || tickStartLower != o.tickStartLower || hasGround != o.hasGround
                    || hasTickStart && (Double.compare(sx, o.sx) != 0 || Double.compare(sy, o.sy) != 0 || Double.compare(sz, o.sz) != 0 || rStart != o.rStart)
                    || hasGround && (Double.compare(gx, o.gx) != 0 || Double.compare(gy, o.gy) != 0 || Double.compare(gz, o.gz) != 0 || rGround != o.rGround))) {
                return false; // what the movement check reads at a mount
            }
            if (lostPosition) {
                return true;
            }
            return steps == o.steps && headSteps == o.headSteps && hasPrevPos == o.hasPrevPos && hasPrevRot == o.hasPrevRot
                    && posMoved == o.posMoved && rotMoved == o.rotMoved
                    && (!posMoved || Double.compare(px, o.px) == 0 && Double.compare(py, o.py) == 0 && Double.compare(pz, o.pz) == 0 && rPrev == o.rPrev)
                    && (!rotMoved || Float.compare(pxRot, o.pxRot) == 0 && Float.compare(pyRot, o.pyRot) == 0 && rPrevRot == o.rPrevRot)
                    && Double.compare(tx, o.tx) == 0 && Double.compare(ty, o.ty) == 0 && Double.compare(tz, o.tz) == 0
                    && Float.compare(tyRot, o.tyRot) == 0 && Float.compare(txRot, o.txRot) == 0 && rTarget == o.rTarget && rTargetRot == o.rTargetRot
                    && Double.compare(headTarget, o.headTarget) == 0;
        }

        /** Widens this candidate's radii to cover {@code o} as well (see {@link #mergeable}). */
        void absorb(State o) {
            if (!present || lostAll) {
                return;
            }
            onGroundKnown &= o.onGroundKnown && onGround == o.onGround;
            tickedKnown &= o.tickedKnown && ticked == o.ticked;
            tickLower = Math.min(tickLower, o.tickLower);
            tickUpper = Math.max(tickUpper, o.tickUpper);
            if (lostPosition) {
                return;
            }
            double d = Math.max(apart(x, o.x), Math.max(apart(y, o.y), apart(z, o.z)));
            rPos = Math.max(rPos, d + o.rPos);
            if (!posMoved) {
                rPrev = rPos; // prev equals the position in each member
            }
            float a = Math.max(angle(yRot, o.yRot), angle(xRot, o.xRot));
            rRot = Math.max(rRot, a + o.rRot);
            rHead = Math.max(rHead, angle(yHeadRot, o.yHeadRot) + o.rHead);
            if (!rotMoved) {
                rPrevRot = rRot;
            }
        }

        private static double apart(double a, double b) {
            return Double.compare(a, b) == 0 ? 0.0 : Math.max(Math.abs(a - b), Double.MIN_VALUE);
        }

        private static float angle(float a, float b) {
            return Float.compare(a, b) == 0 ? 0.0F : Math.max(Math.abs(VanillaMath.wrapDegrees(a - b)), Float.MIN_VALUE);
        }

        PossibleState view() {
            boolean known = !lostAll;
            boolean positionKnown = known && present && !lostPosition;
            EntitySizes sizes = EntitySizes.vanilla();
            EntitySizes.Entry entry = known && present ? sizes.entry(type) : null;
            EntitySizes.Size size = null;
            EntitySizes.Box bounds = null;
            if (entry != null) {
                // The cached dimensions were refreshed at a scale the attribute had since the last tick and (a polar bear)
                // at the animation's value before or after that tick: sizes grow with both, so the smallest and the
                // largest bound them, and one size when they agree.
                EntitySizes.Size low = sizes.size(type, pose, baby, scaleLow, data0, data1, Math.min(anim, animO));
                EntitySizes.Size high = sizes.size(type, pose, baby, scaleHigh, data0, data1, Math.max(anim, animO));
                size = low != null && low.equals(high) ? low : null;
                if (positionKnown && high != null) {
                    bounds = high.box(x, y, z).inflate(rPos);
                }
            }
            int pickable = !known ? NONE : !present ? 0 : sizes.pickable(type, data0, spectator);
            return new PossibleState(present, known, positionKnown, type, player, x, y, z, rPos, yRot, xRot, rRot, yHeadRot, rHead, pose, flags,
                    onGroundKnown ? (onGround ? 1 : 0) : -1, vehicle, size, bounds, pickable, entry == null ? 0.0F : entry.pickRadius());
        }
    }

    /** A value changing at sequence numbers, read back for a range of prefixes. */
    private static final class Versioned {
        static final int ALWAYS_NO = 0;

        private long[] seqs = new long[4];
        private int[] values = new int[4];
        private int size;
        private int initial;

        Versioned(int initial) {
            this.initial = initial;
        }

        void set(long seq, int value) {
            if (head() == value) {
                return;
            }
            if (size == seqs.length) {
                seqs = Arrays.copyOf(seqs, size * 2);
                values = Arrays.copyOf(values, size * 2);
            }
            seqs[size] = seq;
            values[size] = value;
            size++;
        }

        int head() {
            return size == 0 ? initial : values[size - 1];
        }

        /** The value for prefix X (ops with seq < X processed); a single value for every X in (lower, upper], or -1. */
        int range(long lower, long upper) {
            int value = initial;
            int i = 0;
            while (i < size && seqs[i] <= lower) {
                value = values[i++];
            }
            for (; i < size && seqs[i] < upper; i++) {
                if (values[i] != value) {
                    return -1;
                }
            }
            return value;
        }

        /** Forgets changes no prefix after {@code certain} can see apart from their result. */
        void prune(long certain) {
            int keep = 0;
            while (keep < size && seqs[keep] <= certain) {
                keep++;
            }
            if (keep == 0) {
                return;
            }
            initial = values[keep - 1];
            System.arraycopy(seqs, keep, seqs, 0, size - keep);
            System.arraycopy(values, keep, values, 0, size - keep);
            size -= keep;
        }
    }

    /**
     * Which chunks tick entities on the client ({@code TransientEntitySectionManager.tickingChunks}): a chunk
     * starts when it loads into the client's chunk storage, and stops when it is dropped from it or replaced
     * in its slot. A chunk that falls out of the storage when the view shrinks is not unloaded: it keeps
     * ticking. Geometry as {@code ClientChunkCache.Storage}.
     */
    private static final class ChunkTicking {
        private static final long EMPTY = Long.MIN_VALUE;

        private int radius;
        private int viewRange;
        private long[] slots;
        private int centerX;
        private int centerZ;
        /** Per chunk: whether it ticks (the head) and the changes not every prefix has seen. Absent: it does not tick. */
        private final LongMap<Versioned> history = new LongMap<>();
        /** The chunks whose history holds changes, the only ones {@link #prune} has to visit. */
        private long[] changed = new long[16];
        private int changedCount;
        /** From this sequence on, which chunks tick is not known. */
        private long uncertainFrom = Long.MAX_VALUE;

        ChunkTicking() {
            reset(Long.MIN_VALUE, range(-1));
            uncertainFrom = Long.MIN_VALUE; // nothing known before a level
        }

        static int range(int serverViewDistance) {
            return Math.max(2, serverViewDistance) + 3;
        }

        void reset(long seq, int newRadius) {
            history.forEach((key, h) -> {
                if (h.head() == 1) {
                    change(seq, key, false);
                }
            });
            radius = newRadius;
            viewRange = newRadius * 2 + 1;
            slots = new long[viewRange * viewRange];
            Arrays.fill(slots, EMPTY);
            uncertainFrom = Long.MAX_VALUE;
        }

        void uncertain(long seq) {
            uncertainFrom = Math.min(uncertainFrom, seq);
        }

        private int index(int x, int z) {
            return Math.floorMod(z, viewRange) * viewRange + Math.floorMod(x, viewRange);
        }

        private boolean inRange(int x, int z) {
            return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
        }

        void load(long seq, int x, int z) {
            if (!inRange(x, z)) {
                return; // "Ignoring chunk since it's not in the view range"
            }
            int index = index(x, z);
            long key = ClientWorld.chunkKey(x, z);
            long old = slots[index];
            if (old != key) {
                if (old != EMPTY) {
                    change(seq, old, false); // Storage.replace unloads the chunk it replaces
                }
                slots[index] = key;
            }
            change(seq, key, true); // onChunkLoaded
        }

        void forget(long seq, int x, int z) {
            if (!inRange(x, z)) {
                return;
            }
            int index = index(x, z);
            long key = ClientWorld.chunkKey(x, z);
            if (slots[index] == key) {
                slots[index] = EMPTY;
                change(seq, key, false);
            }
        }

        void center(int x, int z) {
            centerX = x;
            centerZ = z;
        }

        void resize(int newRadius) {
            if (newRadius == radius) {
                return;
            }
            long[] old = slots;
            radius = newRadius;
            viewRange = newRadius * 2 + 1;
            slots = new long[viewRange * viewRange];
            Arrays.fill(slots, EMPTY);
            for (long key : old) {
                if (key != EMPTY && inRange(ClientWorld.chunkX(key), ClientWorld.chunkZ(key))) {
                    slots[index(ClientWorld.chunkX(key), ClientWorld.chunkZ(key))] = key; // the rest is dropped, not unloaded
                }
            }
        }

        private void change(long seq, long key, boolean ticks) {
            int value = ticks ? 1 : 0;
            Versioned h = history.get(key);
            if ((h == null ? 0 : h.head()) == value) {
                return;
            }
            if (h == null) {
                h = new Versioned(0);
                history.put(key, h);
            }
            if (h.size == 0) {
                if (changedCount == changed.length) {
                    changed = Arrays.copyOf(changed, changedCount * 2);
                }
                changed[changedCount++] = key;
            }
            h.set(seq, value);
        }

        Tri ticking(long key, long lower, long upper) {
            if (uncertainFrom < upper) {
                return Tri.MAYBE;
            }
            Versioned h = history.get(key);
            if (h == null) {
                return Tri.FALSE;
            }
            return switch (h.range(lower, upper)) {
                case 1 -> Tri.TRUE;
                case 0 -> Tri.FALSE;
                default -> Tri.MAYBE;
            };
        }

        void prune(long certain) {
            int kept = 0;
            for (int i = 0; i < changedCount; i++) {
                long key = changed[i];
                Versioned h = history.get(key);
                h.prune(certain);
                if (h.size > 0) {
                    changed[kept++] = key;
                } else if (h.initial == 0) {
                    history.remove(key);
                }
            }
            changedCount = kept;
        }
    }
}
