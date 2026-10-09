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
 * the client moves itself ({@link ClientMotion}) are reported as lost. After lost events ({@code Gap}) or
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
    private static final int POSE_STANDING = ClientboundPacket.EntityPose.STANDING.ordinal();
    private static final int POSE_SLEEPING = ClientboundPacket.EntityPose.SLEEPING.ordinal();
    /** {@code Entity.DATA_SHARED_FLAGS_ID} and {@code Entity.DATA_POSE}. */
    private static final int DATA_FLAGS = 0;
    private static final int DATA_POSE = 6;

    /** Whether the client has an entity with a given id. */
    public enum Existence { PRESENT, ABSENT, MAYBE, UNKNOWN }

    private final EntityTypes types;

    // --- server order: what the client will hold once it has processed everything sent so far ---------
    private boolean level;
    /** Nothing was lost since the level began (or the client has no level): an id without a track is certainly absent. */
    private boolean complete;
    private boolean playerInfoComplete;
    private int selfId = NONE;
    private String dimension;
    private int minY = -64;
    private int serverChunkRadius = -1;
    private final Map<Integer, Head> heads = new HashMap<>();
    private final Set<UUID> playerInfo = new java.util.HashSet<>();
    private Head self = new Head(NONE, null, EntityTypes.PLAYER, true);
    private final ChunkTicking chunks = new ChunkTicking();
    private final Versioned frozen = new Versioned(FROZEN_NO);
    private boolean stepSeen;

    // --- client order ---------------------------------------------------------------------------------
    /** Pings not answered yet, in order: {id, sequence}. */
    private final ArrayDeque<long[]> pings = new ArrayDeque<>();
    /** The sequence of the last answered ping: the client processed it and everything before it. */
    private long certain = Long.MIN_VALUE;
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final Set<Track> pendingTracks = new LinkedHashSet<>();

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
                self = new Head(selfId, null, EntityTypes.PLAYER, true);
                serverChunkRadius = login.chunkRadius();
                playerInfo.clear();
                playerInfoComplete = true;
                newLevel(seq, login.dimension());
            }
            case ClientboundPacket.Respawn respawn -> {
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
            case ClientboundPacket.PlayerInfoUpdate update -> {
                if (update.addPlayers()) {
                    for (ClientboundPacket.PlayerInfoUpdate.Entry entry : update.entries()) {
                        playerInfo.add(entry.profileId());
                    }
                }
            }
            case ClientboundPacket.PlayerInfoRemove remove -> remove.profileIds().forEach(playerInfo::remove);
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
                            : new Op.Teleport(seq, teleport.position(), teleport.yRot(), teleport.xRot(), teleport.relatives(), teleport.onGround()));
                }
            }
            case ClientboundPacket.RotateHead rotate -> {
                Head head = heads.get(rotate.entityId());
                if (head != null) {
                    enqueue(seq, head.id, new Op.HeadRotation(seq, VanillaMath.unpackDegrees(rotate.yHeadRot())));
                }
            }
            case ClientboundPacket.SetEntityData data -> entityData(seq, data);
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
        head.base(new Vec3d(add.x(), add.y(), add.z())); // syncPacketPositionCodec
        heads.put(id, head);
        if (!tracks.containsKey(id)) {
            tracks.put(id, new Track(id, complete ? State.absent() : State.lostAll()));
        }
        enqueue(seq, id, new Op.Add(seq, kind, add.type(), player, minY, add.x(), add.y(), add.z(), VanillaMath.unpackDegrees(add.yRot()),
                VanillaMath.unpackDegrees(add.xRot()), VanillaMath.unpackDegrees(add.yHeadRot()), maybe));
    }

    /** The entity leaves the client's level: its passengers stop riding it, it stops riding its vehicle. */
    private void removeHead(long seq, Head head) {
        heads.remove(head.id);
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
        for (ClientboundPacket.SetEntityData.DataEntry entry : data.entries()) {
            if (entry.index() == DATA_POSE && entry.kind() == ClientboundPacket.SetEntityData.Kind.POSE) {
                pose = (int) entry.value();
            } else if (entry.index() == DATA_FLAGS && entry.kind() == ClientboundPacket.SetEntityData.Kind.BYTE) {
                flags = (int) entry.value() & 0xFF;
            }
        }
        if (pose != NONE || flags != NONE) {
            enqueue(seq, head.id, new Op.Data(seq, pose, flags));
        }
    }

    /** {@code handleSetEntityPassengersPacket}: eject everyone, then {@code startRiding(vehicle, true, false)} each. */
    private void setPassengers(long seq, ClientboundPacket.SetPassengers packet) {
        Head vehicle = head(packet.vehicleId());
        if (vehicle == null) {
            return; // "Received passengers for unknown entity"
        }
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
            if (p == self) {
                if (vehicle != self && vehicle.passengers.getFirst() == id) {
                    // The player may control it (isLocalInstanceAuthoritative): its position is the client's own physics.
                    enqueue(seq, vehicle.id, Op.LOSE_POSITION);
                }
            } else {
                enqueue(seq, id, new Op.Vehicle(seq, vehicle.id));
            }
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
            case "minecraft:player_info_update", "minecraft:player_info_remove" -> playerInfoComplete = false;
            case "minecraft:login", "minecraft:respawn" -> {
                lostFrom(seq);
                chunks.uncertain(seq);
                frozen.set(seq, FROZEN_MAYBE);
                playerInfoComplete = false;
            }
            case "minecraft:add_entity", "minecraft:remove_entities", "minecraft:move_entity_pos", "minecraft:move_entity_pos_rot",
                    "minecraft:move_entity_rot", "minecraft:entity_position_sync", "minecraft:teleport_entity", "minecraft:rotate_head",
                    "minecraft:set_entity_data", "minecraft:set_passengers" -> lostFrom(seq);
            default -> { }
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
        if (!s.present || s.lostAll) {
            out.add(s);
            return;
        }
        if (s.lostPosition) {
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
        tickNow(s, out);
    }

    private void tickNow(State s, List<State> out) {
        if (!s.kind.living()) {
            // Entity.baseTick -> checkBelowWorld -> onBelowWorld: discard.
            double belowWorld = s.minY - 64;
            Tri below = s.y - s.rPos < belowWorld ? (s.y + s.rPos < belowWorld ? Tri.TRUE : Tri.MAYBE) : Tri.FALSE;
            if (below == Tri.MAYBE) {
                out.add(s.copy());
                branches++;
            }
            out.add(below == Tri.FALSE ? s : State.absent());
            return;
        }
        // LivingEntity.aiStep (RemotePlayer.aiStep for players): interpolation, then the head.
        List<State> stepped = new ArrayList<>(1);
        if (s.kind.interpolationSteps() > 0 && s.steps > 0) {
            interpolate(s, stepped);
        } else {
            stepped.add(s);
        }
        for (State t : stepped) {
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
        out.addAll(stepped);
    }

    /** {@code InterpolationHandler.interpolate}. */
    private void interpolate(State s, List<State> out) {
        double d = 1.0 / s.steps;
        List<State> branched = new ArrayList<>(2);
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
                branched.add(added);
                branches++;
            }
        }
        branched.add(s);
        for (State t : branched) {
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
            out.add(t);
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

    /** Whether the client's TickRateManager lets non-player entities tick for every prefix X in (lower, upper]. */
    private Tri notFrozen(long lower, long upper) {
        return switch (frozen.range(lower, upper)) {
            case Versioned.ALWAYS_NO -> Tri.TRUE;
            default -> Tri.MAYBE; // frozen entities with player passengers still tick: never a certain no
        };
    }

    /** Whether the candidate's chunk ticks entities for every prefix X in (lower, upper]. */
    private Tri chunkTicking(State s, long lower, long upper) {
        long[] keys = chunkKeys(s);
        if (keys == null) {
            return Tri.MAYBE;
        }
        Tri result = chunks.ticking(keys[0], lower, upper);
        for (int i = 1; i < keys.length && result != Tri.MAYBE; i++) {
            if (chunks.ticking(keys[i], lower, upper) != result) {
                result = Tri.MAYBE;
            }
        }
        return result;
    }

    /** At the exact prefix just after {@code seq - 1}: the client processes the op at {@code seq}. */
    private Tri chunkTickingAt(State s, long lower, long seq) {
        return chunkTicking(s, lower, seq);
    }

    /** The chunks the candidate may be in ({@code blockPosition >> 4}); null when there are too many to list. */
    private static long[] chunkKeys(State s) {
        int x0 = VanillaMath.floor(s.x - s.rPos) >> 4;
        int x1 = VanillaMath.floor(s.x + s.rPos) >> 4;
        int z0 = VanillaMath.floor(s.z - s.rPos) >> 4;
        int z1 = VanillaMath.floor(s.z + s.rPos) >> 4;
        if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > 16) {
            return null;
        }
        long[] keys = new long[(x1 - x0 + 1) * (z1 - z0 + 1)];
        int i = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                keys[i++] = ClientWorld.chunkKey(x, z);
            }
        }
        return keys;
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

    /** One state the client may hold for an entity right now. Radii bound the true value (0: exact). */
    public record PossibleState(boolean present, boolean known, boolean positionKnown, String type, boolean player, double x, double y, double z,
                                double positionRadius, float yRot, float xRot, float rotationRadius, float yHeadRot, float headRadius, int pose,
                                int flags, int onGround, int vehicle) {}

    /**
     * Every state the client may hold for {@code entityId} now: each candidate, after any further prefix
     * of the window the client may have processed since its last tick. Empty when the id is unknown.
     */
    public void forEachPossibleState(int entityId, Consumer<PossibleState> action) {
        Track track = tracks.get(entityId);
        if (track == null) {
            if (complete) {
                action.accept(new PossibleState(false, true, false, null, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NONE));
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
     * Everything the server has added to this client's level and not removed, as the client will hold it
     * once it has processed everything sent so far: per entity id its type, codec base (null if unknown)
     * and vehicle (-1: none). For comparing with the server's own tracking.
     */
    public ServerOrder serverOrder() {
        Map<Integer, ServerOrder.Entity> entities = new HashMap<>();
        for (Head head : heads.values()) {
            entities.put(head.id, new ServerOrder.Entity(head.type, head.baseKnown ? new Vec3d(head.bx, head.by, head.bz) : null, head.vehicle,
                    knowledge(tracks.get(head.id))));
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
        /** {@code knowledge}: "exact", "within-r", "n-candidates", "position-unknown" or "unknown". */
        public record Entity(String type, Vec3d base, int vehicle, String knowledge) {}
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

        record Add(long seq, EntityTypes.Kind kind, String type, boolean player, int minY, double x, double y, double z, float yRot, float xRot,
                   float yHeadRot, boolean maybe) implements Op {}

        /** {@code position} null: rotation only; {@code positionKnown} false: decoded against a lost base. */
        record Move(long seq, Vec3d position, boolean positionKnown, boolean hasRotation, float yRot, float xRot, boolean onGround) implements Op {}

        record Sync(long seq, Vec3d position, float yRot, float xRot, boolean onGround) implements Op {}

        record Teleport(long seq, Vec3d position, float yRot, float xRot, Set<RelativeFlag> relatives, boolean onGround) implements Op {}

        record HeadRotation(long seq, float yHeadRot) implements Op {}

        /** -1: not in the packet. */
        record Data(long seq, int pose, int flags) implements Op {}

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
        /** Pending ops of the track this candidate has processed. */
        int applied;

        static State absent() {
            return new State();
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
            s.kind = add.kind();
            s.type = add.type();
            s.player = add.player();
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
            c.applied = applied;
            return c;
        }

        /** Same discrete state and the same targets: the client cannot tell them apart from now on but by where they are. */
        boolean mergeable(State o) {
            if (applied != o.applied || present != o.present || lostAll != o.lostAll) {
                return false;
            }
            if (!present || lostAll) {
                return true;
            }
            if (kind != o.kind || !java.util.Objects.equals(type, o.type) || player != o.player || minY != o.minY || lostPosition != o.lostPosition
                    || vehicle != o.vehicle || pose != o.pose || flags != o.flags) {
                return false;
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
            return new PossibleState(present, known, positionKnown, type, player, x, y, z, rPos, yRot, xRot, rRot, yHeadRot, rHead, pose, flags,
                    onGroundKnown ? (onGround ? 1 : 0) : -1, vehicle);
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
        private final java.util.HashSet<Long> ticking = new java.util.HashSet<>();
        private final Map<Long, Versioned> history = new HashMap<>();
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
            for (Long key : List.copyOf(ticking)) {
                change(seq, key, false);
            }
            ticking.clear();
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

        private void change(long seq, long key, boolean value) {
            if (value ? !ticking.add(key) : !ticking.remove(key)) {
                return;
            }
            history.computeIfAbsent(key, k -> new Versioned(0)).set(seq, value ? 1 : 0);
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
            for (Iterator<Versioned> it = history.values().iterator(); it.hasNext(); ) {
                Versioned h = it.next();
                h.prune(certain);
                if (h.size == 0 && h.initial == 0) {
                    it.remove();
                }
            }
        }
    }
}
