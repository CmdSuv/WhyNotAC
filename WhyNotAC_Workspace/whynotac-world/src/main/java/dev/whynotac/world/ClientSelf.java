package dev.whynotac.world;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The client's own player, as far as the crosshair pick and the attack read it (decompiled 1.21.11),
 * rebuilt in the order the client processes it. Like the other replicas it follows every prefix of the
 * packets between the last answered ping and the first unanswered one.
 *
 * <h2>Where the eye is</h2>
 * The pick at the start of a tick ({@code GameRenderer.pick(1.0F)}) and the attack in the same tick use the
 * player's position after the previous tick's movement. That tick ended with {@code LocalPlayer.sendPosition},
 * which sends the position whenever it moved more than {@link #SEND_THRESHOLD} from the last one sent: so
 * the eye is within that of the last position the client sent, unless something moved the player after it
 * without a reply. Those are, from the client's code: a vehicle (the rider is placed by it, no position is
 * sent while riding, and dismounting keeps the rider where it was), a new player (login, respawn), a
 * block-prediction rollback ({@code BlockChangedAck} snaps the player back to where it was when it
 * predicted), a teleport or sync packet for its own id, and a packet WhyNotAC cannot read. The teleport
 * packet ({@code PlayerPosition}) is not one: its handler sends the new position at once. After any of
 * them that the client may have processed since its last position, the eye is unknown. Other entities and
 * block entities that tick after the player (shulkers, moving pistons, opening shulker boxes) can push it
 * too; this replica cannot see them, the check that reads the eye looks for them.
 *
 * <h2>How high the eye is</h2>
 * The eye height is a cached field set from the pose's dimensions and the scale at the last
 * {@code refreshDimensions}: standing 1.62, crouching 1.27, swimming, gliding and spin attack 0.4, all
 * times the scale, sleeping 0.2 and dying 1.62 whatever the scale. The pose is not followed: the eye is
 * anywhere in that envelope. The scale is the attribute's value when the field was last refreshed, which
 * is at the end of the first tick after a change (or earlier, at a pose change): until then the old value
 * still counts.
 *
 * <h2>Modes, camera, attributes</h2>
 * {@code Player.isCreative()} and {@code isSpectator()} read the player's own entry in the client's player
 * list (the entry it cached; a removed and re-added entry is not followed). The pick starts at the camera
 * entity's eye, which is the player's own unless {@code SetCamera} named another entity.
 * {@code entity_interaction_range} and {@code scale} follow {@code UpdateAttributes} for the player's id,
 * and respawn keeps their modifiers only if the server says so (otherwise the base values).
 *
 * <p>Confined to the session's analysis thread.
 */
public final class ClientSelf {

    /** {@code LocalPlayer.sendPosition}: a position is sent when it moved more than this (Euclidean) since the last one. */
    public static final double SEND_THRESHOLD = 2.0E-4;
    public static final String INTERACTION_RANGE = "minecraft:entity_interaction_range";
    public static final String SCALE = "minecraft:scale";
    /** {@code Player.createAttributes} and the attributes' ranges ({@code Attributes}). */
    static final double DEFAULT_INTERACTION_RANGE = 3.0;
    static final double MIN_INTERACTION_RANGE = 0.0;
    static final double MAX_INTERACTION_RANGE = 64.0;
    static final double DEFAULT_SCALE = 1.0;
    static final double MIN_SCALE = 0.0625;
    static final double MAX_SCALE = 16.0;
    /** {@code Avatar.POSES}: the scaled eye heights (standing and crouching above, the 0.6-tall poses below). */
    static final float EYE_STANDING = 1.62F;
    static final float EYE_LOW_POSE = 0.4F;
    /** The fixed poses: sleeping ({@code LivingEntity.SLEEPING_DIMENSIONS}) and dying (standing's eye). */
    static final float EYE_SLEEPING = 0.2F;
    static final float EYE_DYING = 1.62F;

    /** Player-list game modes: no entry for the player ({@code gameMode()} null), or not followed. */
    public static final int MODE_NONE = -1;
    public static final int MODE_UNKNOWN = -2;
    static final int CREATIVE = 1;
    static final int SPECTATOR = 3;
    private static final int NONE = -1;
    private static final int UNKNOWN = -2;
    private static final int MAX_TRACKED_PINGS = 4096;
    private static final int MAX_RESETS = 4096;
    /** Changes kept per value while the client does not answer pings; beyond, the value is not claimed until it does. */
    static final int MAX_CHANGES = 1024;

    /** Where the client's eye may be: x and z within {@code radius} of the point, y on {@code [y0, y1]} widened by it. */
    public record Eye(double x, double z, double y0, double y1, double radius) {}

    // --- client order ---------------------------------------------------------------------------------
    private final ArrayDeque<long[]> pings = new ArrayDeque<>();
    /** The client processed every op before this sequence. */
    private long certain = Long.MIN_VALUE;

    // --- server order, with history ---------------------------------------------------------------------
    private int selfId = NONE;
    private UUID selfUuid;
    private int logins;
    /** The vehicle of the player (NONE), or UNKNOWN. */
    private final Versioned vehicle = new Versioned(NONE);
    /** 1: the camera is the player; 0: another entity. */
    private final Versioned camera = new Versioned(1);
    /** {@code MultiPlayerGameMode}'s mode (login, respawn, game event 3); UNKNOWN before the login. */
    private final Versioned localMode = new Versioned(UNKNOWN);
    /** The player's cached player-list entry's mode: MODE_NONE without one, MODE_UNKNOWN when not followed. */
    private final Versioned listMode = new Versioned(MODE_UNKNOWN);
    /** The player list's game modes by profile (server order), for the entry a new player will look up. */
    private final java.util.Map<UUID, Integer> playerList = new java.util.HashMap<>();
    /** The cached entry was removed from the list while the player held it. */
    private boolean listEntryReplaced;
    private final Ranges interactionRange = new Ranges(DEFAULT_INTERACTION_RANGE);
    /** Cached: the eye height follows it only when the dimensions are refreshed. */
    private final Ranges scale = new Ranges(DEFAULT_SCALE, true);
    private double interactionBase = DEFAULT_INTERACTION_RANGE;
    private double scaleBase = DEFAULT_SCALE;
    private boolean basesKnown = true;

    // --- the player's own position ------------------------------------------------------------------------
    private boolean sent;
    private double sentX;
    private double sentY;
    private double sentZ;
    private long sentSequence;
    private long sentNanoTime;
    /** {@link #certain} when the position was sent: ops before it are in the position. */
    private long sentCertain;
    /** Sequences of ops after which the position is not the one sent (ascending). */
    private final ArrayDeque<Long> resets = new ArrayDeque<>();
    /** The last login or respawn, and whether the client said it loaded the world since (certainly after it). */
    private long lastNewPlayer = Long.MIN_VALUE;
    private boolean loaded;

    /** Applies one timeline event, in sequence order. */
    public void onEvent(TimelineEvent event) {
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(out.sequence(), out.packet());
            case TimelineEvent.Inbound in -> onInbound(in.sequence(), in.nanoTime(), in.packet());
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.transactionId());
            case TimelineEvent.Bound bound -> bind(bound.sequence(), bound.playerId());
            case TimelineEvent.Gap gap -> lose(gap.sequence());
            case TimelineEvent.Disconnected ignored -> pings.clear();
            default -> { }
        }
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
                boolean first = logins++ == 0;
                selfId = login.entityId();
                // A new ClientPacketListener: an empty player list, camera and mode from the packet. The first
                // login of a connection makes a new player; a later one may keep it (handleLogin makes one only
                // without a player), with its attributes and the player-list entry it cached from the old list.
                playerList.clear();
                listEntryReplaced = !first;
                listMode.set(seq, first ? MODE_NONE : MODE_UNKNOWN);
                localMode.set(seq, login.gameMode() >= 0 ? gameType(login.gameMode()) : UNKNOWN);
                camera.set(seq, 1);
                vehicle.set(seq, NONE);
                if (first) {
                    interactionRange.set(seq, DEFAULT_INTERACTION_RANGE, DEFAULT_INTERACTION_RANGE);
                    scale.set(seq, DEFAULT_SCALE, DEFAULT_SCALE);
                    interactionBase = DEFAULT_INTERACTION_RANGE;
                    scaleBase = DEFAULT_SCALE;
                    basesKnown = true;
                } else {
                    interactionRange.widen(seq, DEFAULT_INTERACTION_RANGE);
                    scale.widen(seq, DEFAULT_SCALE);
                    basesKnown = false;
                }
                newPlayer(seq);
            }
            case ClientboundPacket.Respawn respawn -> {
                // Always a new LocalPlayer: no vehicle, its own camera, the list entry looked up afresh.
                listEntryReplaced = false;
                listMode.set(seq, listedMode());
                localMode.set(seq, respawn.gameMode() >= 0 ? gameType(respawn.gameMode()) : UNKNOWN);
                camera.set(seq, 1);
                vehicle.set(seq, NONE);
                int keep = respawn.dataToKeep();
                if (keep < 0) {
                    // not recorded: kept or base only
                    interactionRange.widen(seq, interactionBase);
                    scale.widen(seq, scaleBase);
                } else if ((keep & ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS) == 0) {
                    // assignBaseValues: the base values, no modifiers
                    if (basesKnown) {
                        interactionRange.set(seq, interactionBase, interactionBase);
                        scale.set(seq, scaleBase, scaleBase);
                    } else {
                        interactionRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                        scale.unknown(seq, MIN_SCALE, MAX_SCALE);
                    }
                }
                newPlayer(seq);
            }
            case ClientboundPacket.GameEvent event when event.type() == ClientboundPacket.GameEvent.CHANGE_GAME_MODE ->
                    // handleGameEvent: GameType.byId(Mth.floor(param + 0.5F))
                    localMode.set(seq, gameType((int) Math.floor(event.param() + 0.5F)));
            case ClientboundPacket.PlayerInfoUpdate update -> {
                for (ClientboundPacket.PlayerInfoUpdate.Entry entry : update.entries()) {
                    UUID profile = entry.profileId();
                    if (update.addPlayers()) {
                        // putIfAbsent: a new entry starts in the default mode (survival)
                        playerList.putIfAbsent(profile, 0);
                    }
                    if (update.updateGameMode() && entry.gameMode() >= 0 && playerList.containsKey(profile)) {
                        playerList.put(profile, gameType(entry.gameMode()));
                    }
                }
                if (selfUuid != null) {
                    listMode.set(seq, listEntryReplaced ? MODE_UNKNOWN : listedMode());
                }
            }
            case ClientboundPacket.PlayerInfoRemove remove -> {
                for (UUID profile : remove.profileIds()) {
                    playerList.remove(profile);
                    if (profile.equals(selfUuid)) {
                        // the player may still hold the removed entry (AbstractClientPlayer caches it)
                        listEntryReplaced = true;
                        listMode.set(seq, MODE_UNKNOWN);
                    }
                }
            }
            case ClientboundPacket.SetCamera set -> camera.set(seq, set.entityId() == selfId && selfId != NONE ? 1 : 0);
            case ClientboundPacket.SetPassengers passengers -> {
                int now = vehicle.head();
                boolean rides = false;
                for (int id : passengers.passengerIds()) {
                    rides |= id == selfId;
                }
                if (rides && now != passengers.vehicleId()) {
                    vehicle.set(seq, passengers.vehicleId());
                    reset(seq);
                } else if (!rides && now == passengers.vehicleId()) {
                    vehicle.set(seq, NONE);
                    reset(seq);
                }
            }
            case ClientboundPacket.RemoveEntities remove -> {
                int now = vehicle.head();
                for (int id : remove.entityIds()) {
                    if (now != NONE && id == now) {
                        vehicle.set(seq, NONE);
                        reset(seq);
                    }
                }
            }
            case ClientboundPacket.TeleportEntity teleport when teleport.entityId() == selfId -> reset(seq);
            case ClientboundPacket.EntityPositionSync sync when sync.entityId() == selfId -> reset(seq);
            case ClientboundPacket.MoveEntity move when move.entityId() == selfId -> reset(seq);
            case ClientboundPacket.BlockChangedAck ignored -> reset(seq); // may roll the player back
            case ClientboundPacket.UpdateAttributes attributes when attributes.entityId() == selfId && selfId != NONE -> {
                for (ClientboundPacket.UpdateAttributes.Attribute attribute : attributes.attributes()) {
                    if (attribute.key().equals(INTERACTION_RANGE)) {
                        double[] range = AttributeValues.range(attribute, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                        interactionRange.set(seq, range[0], range[1]);
                        interactionBase = clamp(attribute.base(), MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                    } else if (attribute.key().equals(SCALE)) {
                        double[] range = AttributeValues.range(attribute, MIN_SCALE, MAX_SCALE);
                        scale.set(seq, range[0], range[1]);
                        scaleBase = clamp(attribute.base(), MIN_SCALE, MAX_SCALE);
                    }
                }
            }
            case ClientboundPacket.Untranslatable ignored -> {
                // anything may have changed
                reset(seq);
                vehicle.set(seq, UNKNOWN);
                camera.set(seq, 0);
                localMode.set(seq, UNKNOWN);
                listMode.set(seq, MODE_UNKNOWN);
                listEntryReplaced = true;
                interactionRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                scale.unknown(seq, MIN_SCALE, MAX_SCALE);
                basesKnown = false;
            }
            default -> { }
        }
    }

    /** {@code GameType.byId}: an id out of range is survival ({@code OutOfBoundsStrategy.ZERO}). */
    static int gameType(int id) {
        return id >= 0 && id <= SPECTATOR ? id : 0;
    }

    private int listedMode() {
        if (selfUuid == null) {
            return MODE_UNKNOWN;
        }
        Integer mode = playerList.get(selfUuid);
        return mode == null ? MODE_NONE : mode;
    }

    private void bind(long seq, UUID playerId) {
        selfUuid = playerId;
        listMode.set(seq, listEntryReplaced ? MODE_UNKNOWN : listedMode());
    }

    private void newPlayer(long seq) {
        reset(seq);
        lastNewPlayer = seq;
        loaded = false;
    }

    private void reset(long seq) {
        resets.add(seq);
        if (resets.size() > MAX_RESETS) {
            // too many to follow: the position is unknown until the client sends one after all of them
            resets.pollFirst();
            sent = false;
        }
    }

    private void lose(long seq) {
        reset(seq);
        sent = false;
        vehicle.set(seq, UNKNOWN);
        camera.set(seq, 0);
        localMode.set(seq, UNKNOWN);
        listMode.set(seq, MODE_UNKNOWN);
        listEntryReplaced = true;
        interactionRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
        scale.unknown(seq, MIN_SCALE, MAX_SCALE);
        basesKnown = false;
    }

    private void onInbound(long seq, long nanoTime, ServerboundPacket packet) {
        switch (packet) {
            case ServerboundPacket.MovePlayer move when move.hasPosition() && !move.hasNonFiniteValue() -> {
                sent = true;
                sentX = move.x();
                sentY = move.y();
                sentZ = move.z();
                sentSequence = seq;
                sentNanoTime = nanoTime;
                sentCertain = certain;
                while (!resets.isEmpty() && resets.peekFirst() < sentCertain) {
                    resets.pollFirst(); // processed before this position was sent: in it
                }
            }
            case ServerboundPacket.PlayerLoaded ignored -> loaded = certain > lastNewPlayer;
            case ServerboundPacket.ClientTickEnd ignored -> {
                // The tick that just ended refreshed the cached dimensions with the scale of everything the
                // client processed before it: at least what was certain then (LocalPlayer.tick runs only once loaded).
                if (loaded) {
                    scale.settle(certain);
                }
            }
            default -> { }
        }
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
            return; // not ours, or forgotten
        }
        while (!pings.isEmpty() && pings.peekFirst()[1] <= seq) {
            pings.pollFirst();
        }
        certain = Math.max(certain, seq);
        vehicle.prune(certain);
        camera.prune(certain);
        localMode.prune(certain);
        listMode.prune(certain);
        interactionRange.prune(certain);
    }

    private long windowEnd() {
        long[] first = pings.peekFirst();
        return first == null ? Long.MAX_VALUE : first[1];
    }

    // --- queries ----------------------------------------------------------------------------------------------------

    /**
     * Where the client's eye may be for the pick and attack it is making now, or null if this replica cannot
     * bound it: no position sent since a new player or since something moved the player without a reply,
     * a vehicle, or another camera.
     */
    public Eye eye() {
        long end = windowEnd();
        if (!sent || vehicle.mayBeOtherThan(certain, end, NONE) || camera.mayBeOtherThan(certain, end, 1)) {
            return null;
        }
        for (long r : resets) {
            if (r >= sentCertain && r < end) {
                return null;
            }
        }
        double[] s = scale.hull(certain, end);
        float low = (float) s[0];
        float high = (float) s[1];
        // EntityDimensions.scale: float products of the unscaled eye heights
        double eyeLow = Math.min(EYE_SLEEPING, EYE_LOW_POSE * low);
        double eyeHigh = Math.max(EYE_DYING, EYE_STANDING * high);
        return new Eye(sentX, sentZ, sentY + eyeLow, sentY + eyeHigh, SEND_THRESHOLD);
    }

    /** The sequence of the position {@link #eye()} is based on. */
    public long eyeSequence() {
        return sentSequence;
    }

    /** When the position {@link #eye()} is based on arrived. */
    public long eyeNanoTime() {
        return sentNanoTime;
    }

    /**
     * Every op before this sequence was processed before the tick that sent the position {@link #eye()} is
     * based on: anything sent at or after it may have changed the client's world after that position.
     */
    public long eyeCertain() {
        return sentCertain;
    }

    /** The largest scale the player's cached dimensions may have now (its box is 0.6 by 1.8 times it). */
    public double maxScale() {
        return scale.hull(certain, windowEnd())[1];
    }

    /** The largest {@code entity_interaction_range} the client may have now. */
    public double maxInteractionRange() {
        return interactionRange.hull(certain, windowEnd())[1];
    }

    /**
     * Whether the client may be in creative or spectator mode now ({@code Player.isCreative()},
     * {@code isSpectator()}, or the game mode's own), or WhyNotAC does not know.
     */
    public boolean mayBeCreativeOrSpectator() {
        long end = windowEnd();
        return listMode.mayBeAnyOf(certain, end, CREATIVE, SPECTATOR, MODE_UNKNOWN)
                || localMode.mayBeAnyOf(certain, end, CREATIVE, SPECTATOR, UNKNOWN);
    }

    /** The player's entity id, or -1 before the login. */
    public int selfId() {
        return selfId;
    }

    /** The last position the client sent, or null. */
    public Vec3d sentPosition() {
        return sent ? new Vec3d(sentX, sentY, sentZ) : null;
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }

    /** An int changing at sequence numbers, read back for the prefixes the client may have processed. */
    static final class Versioned {
        private final ArrayDeque<long[]> changes = new ArrayDeque<>(); // {seq, value}
        private int base;
        /** Changes up to here were dropped unprocessed: until the client answers past it, any value is possible. */
        private long overflow = Long.MIN_VALUE;

        Versioned(int initial) {
            this.base = initial;
        }

        void set(long seq, int value) {
            if (head() != value) {
                changes.add(new long[] {seq, value});
                if (changes.size() > MAX_CHANGES) {
                    base = head();
                    changes.clear();
                    overflow = seq;
                }
            }
        }

        int head() {
            long[] last = changes.peekLast();
            return last == null ? base : (int) last[1];
        }

        /** Changes the client certainly processed fold into the base. */
        void prune(long certain) {
            while (!changes.isEmpty() && changes.peekFirst()[0] < certain) {
                base = (int) changes.pollFirst()[1];
            }
        }

        /** Whether some prefix ending in {@code [certain, end)} gives a value other than {@code value}. */
        boolean mayBeOtherThan(long certain, long end, int value) {
            if (certain <= overflow) {
                return true;
            }
            int v = base;
            for (long[] c : changes) {
                if (c[0] >= end) {
                    break;
                }
                if (c[0] < certain) {
                    v = (int) c[1];
                } else {
                    if (v != value) {
                        return true;
                    }
                    v = (int) c[1];
                }
            }
            return v != value;
        }

        /** Whether some prefix ending in {@code [certain, end)} gives one of {@code values}. */
        boolean mayBeAnyOf(long certain, long end, int... values) {
            if (certain <= overflow) {
                return true;
            }
            int v = base;
            for (long[] c : changes) {
                if (c[0] >= end) {
                    break;
                }
                if (c[0] < certain) {
                    v = (int) c[1];
                } else {
                    if (contains(values, v)) {
                        return true;
                    }
                    v = (int) c[1];
                }
            }
            return contains(values, v);
        }

        private static boolean contains(int[] values, int v) {
            for (int value : values) {
                if (value == v) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * An attribute's value as an interval, changing at sequence numbers. {@link #settle} drops the values
     * that can no longer be the one in use (for a cached value, refreshed at a tick); without it the old
     * values stay possible.
     */
    static final class Ranges {
        private final List<double[]> values = new ArrayList<>(); // {seq, low, high}, ascending
        private final boolean cached;

        Ranges(double initial) {
            this(initial, false);
        }

        Ranges(double initial, boolean cached) {
            this.cached = cached;
            values.add(new double[] {Long.MIN_VALUE, initial, initial});
        }

        void set(long seq, double low, double high) {
            add(new double[] {seq, low, high});
        }

        /** The value now is either the current one or {@code value}. */
        void widen(long seq, double value) {
            double[] last = values.getLast();
            add(new double[] {seq, Math.min(last[1], value), Math.max(last[2], value)});
        }

        void unknown(long seq, double min, double max) {
            add(new double[] {seq, min, max});
        }

        private void add(double[] value) {
            values.add(value);
            if (values.size() > MAX_CHANGES) {
                // the two oldest as one: their hull from the first one's sequence (wider, still sound)
                double[] first = values.removeFirst();
                double[] second = values.getFirst();
                values.set(0, new double[] {first[0], Math.min(first[1], second[1]), Math.max(first[2], second[2])});
            }
        }

        /** For a live value: entries certainly replaced before {@code certain} go. */
        void prune(long certain) {
            while (values.size() > 1 && values.get(1)[0] < certain) {
                values.removeFirst();
            }
        }

        /** For a cached value refreshed at a tick that began after everything before {@code certain}. */
        void settle(long certain) {
            prune(certain);
        }

        /** {@code [low, high]} over the values the client may use with any prefix ending in {@code [certain, end)}. */
        double[] hull(long certain, long end) {
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < values.size(); i++) {
                double[] v = values.get(i);
                if (v[0] >= end) {
                    break;
                }
                // a live value is the last one before the prefix's end; earlier ones count only if a later change may not be processed
                double[] next = i + 1 < values.size() ? values.get(i + 1) : null;
                if (!cached && next != null && next[0] < certain) {
                    continue;
                }
                low = Math.min(low, v[1]);
                high = Math.max(high, v[2]);
            }
            return new double[] {low, high};
        }
    }
}
