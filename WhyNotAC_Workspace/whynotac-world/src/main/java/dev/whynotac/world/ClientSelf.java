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
 * {@code entity_interaction_range}, {@code block_interaction_range} and {@code scale} follow {@code UpdateAttributes} for the player's id,
 * and respawn keeps their modifiers only if the server says so (otherwise the base values).
 *
 * <h2>On the ground</h2>
 * {@code Player.getDestroySpeed} reads {@code onGround} in the tick's keybinds: the value the previous tick's movement
 * left ({@code Entity.move} sets it every tick for the local player), which that tick's {@code sendPosition} sent (every
 * movement packet carries it, and a change alone sends {@code StatusOnly}). Between them only {@code TeleportEntity} for
 * the player's own id sets it ({@code EntityPositionSync} and {@code MoveEntity} skip the local player). Some flags are
 * not the client's: {@code handleMovePlayer}, {@code handleRotatePlayer} and the teleport of a removed vehicle answer
 * with false, without touching it. While another entity is the camera nothing is sent, and the first tick after it
 * reads a flag not sent yet. So the last flag sent is what the keybinds read unless one of those packets, a camera
 * change, a new player or a packet WhyNotAC cannot read may have come to the client after it; while the player may
 * ride or watch another entity, it is not claimed.
 *
 * <h2>Levels</h2>
 * The client numbers its block predictions per {@code ClientLevel} ({@code BlockStatePredictionHandler}): a new one at
 * every login, and at a respawn only into another dimension ({@code handleRespawn}). Which one a respawn makes follows
 * from the dimension before it in server order, as the client processes them; a respawn or packet whose dimension
 * WhyNotAC does not know may or may not have made one.
 *
 * <p>Confined to the session's analysis thread.
 */
public final class ClientSelf {

    /** {@code LocalPlayer.sendPosition}: a position is sent when it moved more than this (Euclidean) since the last one. */
    public static final double SEND_THRESHOLD = 2.0E-4;
    public static final String INTERACTION_RANGE = "minecraft:entity_interaction_range";
    public static final String BLOCK_RANGE = "minecraft:block_interaction_range";
    public static final String SCALE = "minecraft:scale";
    public static final String MINING_EFFICIENCY = "minecraft:mining_efficiency";
    public static final String BLOCK_BREAK_SPEED = "minecraft:block_break_speed";
    public static final String SUBMERGED_MINING_SPEED = "minecraft:submerged_mining_speed";
    /** The effects {@code Player.getDestroySpeed} reads ({@code MobEffectUtil.hasDigSpeed}, {@code getDigSpeedAmplification}). */
    public static final String HASTE = "minecraft:haste";
    public static final String CONDUIT_POWER = "minecraft:conduit_power";
    public static final String MINING_FATIGUE = "minecraft:mining_fatigue";
    /** {@code Player.createAttributes} and the attributes' ranges ({@code Attributes}). */
    static final double DEFAULT_INTERACTION_RANGE = 3.0;
    static final double MIN_INTERACTION_RANGE = 0.0;
    static final double MAX_INTERACTION_RANGE = 64.0;
    static final double DEFAULT_BLOCK_RANGE = 4.5;
    static final double DEFAULT_SCALE = 1.0;
    static final double MIN_SCALE = 0.0625;
    static final double MAX_SCALE = 16.0;
    /** {@code Attributes}: the mining attributes' defaults and ranges. */
    static final double DEFAULT_MINING_EFFICIENCY = 0.0;
    static final double MAX_MINING_EFFICIENCY = 1024.0;
    static final double DEFAULT_BLOCK_BREAK_SPEED = 1.0;
    static final double MAX_BLOCK_BREAK_SPEED = 1024.0;
    static final double DEFAULT_SUBMERGED_MINING_SPEED = 0.2;
    static final double MAX_SUBMERGED_MINING_SPEED = 20.0;
    /** {@code MobEffectInstance}: the amplifier is clamped to this. */
    static final int MAX_AMPLIFIER = 255;
    /** {@code Avatar.POSES}: the scaled eye heights (standing and crouching above, the 0.6-tall poses below). */
    static final float EYE_STANDING = 1.62F;
    static final float EYE_CROUCHING = 1.27F;
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
    private final Ranges blockRange = new Ranges(DEFAULT_BLOCK_RANGE);
    /** Cached: the eye height follows it only when the dimensions are refreshed. */
    private final Ranges scale = new Ranges(DEFAULT_SCALE, true);
    private double interactionBase = DEFAULT_INTERACTION_RANGE;
    private double blockBase = DEFAULT_BLOCK_RANGE;
    private double scaleBase = DEFAULT_SCALE;
    private boolean basesKnown = true;
    /**
     * The mining attributes, live values; a respawn keeps them or takes their bases ({@code miningBases}: the bases the
     * server last sent, null when not known).
     */
    private final Ranges miningEfficiency = new Ranges(DEFAULT_MINING_EFFICIENCY);
    private final Ranges blockBreakSpeed = new Ranges(DEFAULT_BLOCK_BREAK_SPEED);
    private final Ranges submergedMiningSpeed = new Ranges(DEFAULT_SUBMERGED_MINING_SPEED);
    private double[] miningBases = {DEFAULT_MINING_EFFICIENCY, DEFAULT_BLOCK_BREAK_SPEED, DEFAULT_SUBMERGED_MINING_SPEED};
    /** The amplifier of each effect the destroy speed reads, NONE without it, UNKNOWN when not followed. */
    private final Versioned haste = new Versioned(NONE);
    private final Versioned conduitPower = new Versioned(NONE);
    private final Versioned miningFatigue = new Versioned(NONE);

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
    // --- the player's onGround ---------------------------------------------------------------------------------
    private boolean groundSent;
    private boolean groundFlag;
    /** {@link #certain} when the flag was sent: ops before it are in it. */
    private long groundCertain;
    /** The last op after which a flag sent may not be the client's {@code onGround}. */
    private long lastGroundReset = Long.MIN_VALUE;
    /**
     * The player may have ridden since the login: a teleport of a vehicle removed under it moves the player and answers
     * with a flag that is not its own ({@code handleTeleportEntity}, {@code removedPlayerVehicleId}).
     */
    private boolean rodeSinceLogin;

    // --- the client's levels ----------------------------------------------------------------------------------
    /** New levels not certainly processed yet: {sequence, 1 if certainly a new level, 0 if possibly}. */
    private final ArrayDeque<long[]> levelChanges = new ArrayDeque<>();
    /** Among the changes the client certainly processed: those certainly new levels, and all of them. */
    private long levelsCertain;
    private long levelsPossible;
    /** The dimension of the client's level in server order; null when not known. */
    private String levelDimension;

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
                rodeSinceLogin = false; // a new ClientPacketListener: no removed vehicle
                levelChange(seq, true); // handleLogin: always a new ClientLevel
                levelDimension = login.dimension() == null ? null : login.dimension().key();
                // The mining state is a fresh player's at every login: a login ends a configuration phase, which the
                // client enters with no player (from the login phase, or from play through handleConfigurationStart,
                // whose clearClientLevel drops the player), so handleLogin makes a new one.
                freshMining(seq);
                if (first) {
                    interactionRange.set(seq, DEFAULT_INTERACTION_RANGE, DEFAULT_INTERACTION_RANGE);
                    blockRange.set(seq, DEFAULT_BLOCK_RANGE, DEFAULT_BLOCK_RANGE);
                    scale.set(seq, DEFAULT_SCALE, DEFAULT_SCALE);
                    interactionBase = DEFAULT_INTERACTION_RANGE;
                    blockBase = DEFAULT_BLOCK_RANGE;
                    scaleBase = DEFAULT_SCALE;
                    basesKnown = true;
                } else {
                    interactionRange.widen(seq, DEFAULT_INTERACTION_RANGE);
                    blockRange.widen(seq, DEFAULT_BLOCK_RANGE);
                    scale.widen(seq, DEFAULT_SCALE);
                    basesKnown = false;
                }
                newPlayer(seq);
            }
            case ClientboundPacket.Respawn respawn -> {
                // A new ClientLevel only into another dimension (handleRespawn compares with the player's level).
                String key = respawn.dimension() == null ? null : respawn.dimension().key();
                if (key == null || levelDimension == null) {
                    levelChange(seq, false);
                } else if (!key.equals(levelDimension)) {
                    levelChange(seq, true);
                }
                levelDimension = key;
                // Always a new LocalPlayer: no vehicle, its own camera, the list entry looked up afresh.
                listEntryReplaced = false;
                listMode.set(seq, listedMode());
                localMode.set(seq, respawn.gameMode() >= 0 ? gameType(respawn.gameMode()) : UNKNOWN);
                camera.set(seq, 1);
                vehicle.set(seq, NONE);
                int keep = respawn.dataToKeep();
                // a new LocalPlayer: no effect is copied from the old one
                haste.set(seq, NONE);
                conduitPower.set(seq, NONE);
                miningFatigue.set(seq, NONE);
                respawnMining(seq, keep);
                if (keep < 0) {
                    // not recorded: kept or base only
                    interactionRange.widen(seq, interactionBase);
                    blockRange.widen(seq, blockBase);
                    scale.widen(seq, scaleBase);
                } else if ((keep & ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS) == 0) {
                    // assignBaseValues: the base values, no modifiers
                    if (basesKnown) {
                        interactionRange.set(seq, interactionBase, interactionBase);
                        blockRange.set(seq, blockBase, blockBase);
                        scale.set(seq, scaleBase, scaleBase);
                    } else {
                        interactionRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                        blockRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
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
            case ClientboundPacket.SetCamera set -> {
                camera.set(seq, set.entityId() == selfId && selfId != NONE ? 1 : 0);
                groundReset(seq);
            }
            case ClientboundPacket.SetPassengers passengers -> {
                int now = vehicle.head();
                boolean rides = false;
                for (int id : passengers.passengerIds()) {
                    rides |= id == selfId;
                }
                if (rides && now != passengers.vehicleId()) {
                    vehicle.set(seq, passengers.vehicleId());
                    rodeSinceLogin = true;
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
            case ClientboundPacket.TeleportEntity teleport -> {
                if (teleport.entityId() == selfId) {
                    reset(seq);
                    groundReset(seq); // setOnGround with the packet's flag
                } else if (rodeSinceLogin) {
                    groundReset(seq); // it may be a removed vehicle's
                }
            }
            case ClientboundPacket.PlayerPosition ignored -> groundReset(seq); // answered with onGround false
            case ClientboundPacket.PlayerRotation ignored -> groundReset(seq); // answered with onGround false
            case ClientboundPacket.EntityPositionSync sync when sync.entityId() == selfId -> reset(seq);
            case ClientboundPacket.MoveEntity move when move.entityId() == selfId -> reset(seq);
            case ClientboundPacket.BlockChangedAck ignored -> reset(seq); // may roll the player back
            case ClientboundPacket.UpdateAttributes attributes when attributes.entityId() == selfId && selfId != NONE -> {
                for (ClientboundPacket.UpdateAttributes.Attribute attribute : attributes.attributes()) {
                    if (attribute.key().equals(INTERACTION_RANGE)) {
                        double[] range = AttributeValues.range(attribute, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                        interactionRange.set(seq, range[0], range[1]);
                        interactionBase = clamp(attribute.base(), MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                    } else if (attribute.key().equals(BLOCK_RANGE)) {
                        double[] range = AttributeValues.range(attribute, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                        blockRange.set(seq, range[0], range[1]);
                        blockBase = clamp(attribute.base(), MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                    } else if (attribute.key().equals(SCALE)) {
                        double[] range = AttributeValues.range(attribute, MIN_SCALE, MAX_SCALE);
                        scale.set(seq, range[0], range[1]);
                        scaleBase = clamp(attribute.base(), MIN_SCALE, MAX_SCALE);
                    } else if (attribute.key().equals(MINING_EFFICIENCY)) {
                        miningAttribute(seq, 0, miningEfficiency, attribute, 0.0, MAX_MINING_EFFICIENCY);
                    } else if (attribute.key().equals(BLOCK_BREAK_SPEED)) {
                        miningAttribute(seq, 1, blockBreakSpeed, attribute, 0.0, MAX_BLOCK_BREAK_SPEED);
                    } else if (attribute.key().equals(SUBMERGED_MINING_SPEED)) {
                        miningAttribute(seq, 2, submergedMiningSpeed, attribute, 0.0, MAX_SUBMERGED_MINING_SPEED);
                    }
                }
            }
            case ClientboundPacket.MobEffect effect when effect.entityId() == selfId && selfId != NONE -> {
                // handleUpdateMobEffect: forceAddEffect replaces the instance; MobEffectInstance clamps the amplifier
                Versioned v = miningEffect(effect.effectKey());
                if (v != null) {
                    v.set(seq, Math.max(0, Math.min(MAX_AMPLIFIER, effect.amplifier())));
                }
            }
            case ClientboundPacket.RemoveMobEffect remove when remove.entityId() == selfId && selfId != NONE -> {
                Versioned v = miningEffect(remove.effectKey());
                if (v != null) {
                    v.set(seq, NONE);
                }
            }
            case ClientboundPacket.Untranslatable ignored -> {
                // anything may have changed
                unknownMining(seq);
                reset(seq);
                groundReset(seq);
                levelChange(seq, false); // it may have been a respawn
                levelDimension = null;
                vehicle.set(seq, UNKNOWN);
                rodeSinceLogin = true;
                camera.set(seq, 0);
                localMode.set(seq, UNKNOWN);
                listMode.set(seq, MODE_UNKNOWN);
                listEntryReplaced = true;
                interactionRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                blockRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
                scale.unknown(seq, MIN_SCALE, MAX_SCALE);
                basesKnown = false;
            }
            default -> { }
        }
    }

    private Versioned miningEffect(String key) {
        return switch (key) {
            case HASTE -> haste;
            case CONDUIT_POWER -> conduitPower;
            case MINING_FATIGUE -> miningFatigue;
            default -> null;
        };
    }

    private void miningAttribute(long seq, int index, Ranges ranges, ClientboundPacket.UpdateAttributes.Attribute attribute, double min, double max) {
        double[] range = AttributeValues.range(attribute, min, max);
        ranges.set(seq, range[0], range[1]);
        if (miningBases != null) {
            miningBases[index] = clamp(attribute.base(), min, max);
        }
    }

    private void freshMining(long seq) {
        miningEfficiency.set(seq, DEFAULT_MINING_EFFICIENCY, DEFAULT_MINING_EFFICIENCY);
        blockBreakSpeed.set(seq, DEFAULT_BLOCK_BREAK_SPEED, DEFAULT_BLOCK_BREAK_SPEED);
        submergedMiningSpeed.set(seq, DEFAULT_SUBMERGED_MINING_SPEED, DEFAULT_SUBMERGED_MINING_SPEED);
        miningBases = new double[] {DEFAULT_MINING_EFFICIENCY, DEFAULT_BLOCK_BREAK_SPEED, DEFAULT_SUBMERGED_MINING_SPEED};
        haste.set(seq, NONE);
        conduitPower.set(seq, NONE);
        miningFatigue.set(seq, NONE);
    }

    /** {@code handleRespawn}: the old player's attributes ({@code assignAllValues}) or their bases ({@code assignBaseValues}). */
    private void respawnMining(long seq, int keep) {
        Ranges[] all = {miningEfficiency, blockBreakSpeed, submergedMiningSpeed};
        double[] max = {MAX_MINING_EFFICIENCY, MAX_BLOCK_BREAK_SPEED, MAX_SUBMERGED_MINING_SPEED};
        for (int i = 0; i < all.length; i++) {
            if (keep >= 0 && (keep & ClientboundPacket.Respawn.KEEP_ATTRIBUTE_MODIFIERS) != 0) {
                continue; // the same values
            }
            if (miningBases == null) {
                all[i].unknown(seq, 0.0, max[i]);
            } else if (keep < 0) {
                all[i].widen(seq, miningBases[i]);
            } else {
                all[i].set(seq, miningBases[i], miningBases[i]);
            }
        }
    }

    private void unknownMining(long seq) {
        miningEfficiency.unknown(seq, 0.0, MAX_MINING_EFFICIENCY);
        blockBreakSpeed.unknown(seq, 0.0, MAX_BLOCK_BREAK_SPEED);
        submergedMiningSpeed.unknown(seq, 0.0, MAX_SUBMERGED_MINING_SPEED);
        miningBases = null;
        haste.set(seq, UNKNOWN);
        conduitPower.set(seq, UNKNOWN);
        miningFatigue.set(seq, UNKNOWN);
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
        groundReset(seq);
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

    private void groundReset(long seq) {
        lastGroundReset = seq;
    }

    private void levelChange(long seq, boolean certainly) {
        levelChanges.add(new long[] {seq, certainly ? 1 : 0});
        if (levelChanges.size() > MAX_RESETS) {
            // too many to follow: the oldest counts as possibly processed and possibly new (wider, still sound)
            levelChanges.pollFirst();
            levelsPossible++;
        }
    }

    private void lose(long seq) {
        unknownMining(seq);
        reset(seq);
        groundReset(seq);
        levelChange(seq, false);
        levelDimension = null;
        sent = false;
        vehicle.set(seq, UNKNOWN);
        rodeSinceLogin = true;
        camera.set(seq, 0);
        localMode.set(seq, UNKNOWN);
        listMode.set(seq, MODE_UNKNOWN);
        listEntryReplaced = true;
        interactionRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
        blockRange.unknown(seq, MIN_INTERACTION_RANGE, MAX_INTERACTION_RANGE);
        scale.unknown(seq, MIN_SCALE, MAX_SCALE);
        basesKnown = false;
    }

    private void onInbound(long seq, long nanoTime, ServerboundPacket packet) {
        switch (packet) {
            case ServerboundPacket.MovePlayer move -> {
                groundSent = true; // every shape carries onGround
                groundFlag = move.onGround();
                groundCertain = certain;
                if (move.hasPosition() && !move.hasNonFiniteValue()) {
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
        while (!levelChanges.isEmpty() && levelChanges.peekFirst()[0] < certain) {
            long[] change = levelChanges.pollFirst();
            levelsCertain += change[1];
            levelsPossible++;
        }
        vehicle.prune(certain);
        camera.prune(certain);
        localMode.prune(certain);
        listMode.prune(certain);
        interactionRange.prune(certain);
        blockRange.prune(certain);
        miningEfficiency.prune(certain);
        blockBreakSpeed.prune(certain);
        submergedMiningSpeed.prune(certain);
        haste.prune(certain);
        conduitPower.prune(certain);
        miningFatigue.prune(certain);
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
        if (!eyeState().equals("known")) {
            return null;
        }
        long end = windowEnd();
        double[] s = scale.hull(certain, end);
        float low = (float) s[0];
        float high = (float) s[1];
        // EntityDimensions.scale: float products of the unscaled eye heights
        double eyeLow = Math.min(EYE_SLEEPING, EYE_LOW_POSE * low);
        double eyeHigh = Math.max(EYE_DYING, EYE_STANDING * high);
        return new Eye(sentX, sentZ, sentY + eyeLow, sentY + eyeHigh, SEND_THRESHOLD);
    }

    /**
     * The eye positions {@link #eye()} bounds, pose by pose ({@code Avatar.POSES}): standing, crouching and the 0.6-tall
     * poses, each over the scales the player's cached dimensions may have now, then sleeping and dying (fixed sizes).
     * Null when {@link #eye()} is.
     */
    public java.util.List<Eye> eyes() {
        if (!eyeState().equals("known")) {
            return null;
        }
        double[] s = scale.hull(certain, windowEnd());
        float low = (float) s[0];
        float high = (float) s[1];
        java.util.List<Eye> out = new java.util.ArrayList<>(5);
        for (float eye : new float[] {EYE_STANDING, EYE_CROUCHING, EYE_LOW_POSE}) {
            // EntityDimensions.scale: float products
            out.add(new Eye(sentX, sentZ, sentY + eye * low, sentY + eye * high, SEND_THRESHOLD));
        }
        out.add(new Eye(sentX, sentZ, sentY + EYE_SLEEPING, sentY + EYE_SLEEPING, SEND_THRESHOLD));
        out.add(new Eye(sentX, sentZ, sentY + EYE_DYING, sentY + EYE_DYING, SEND_THRESHOLD));
        return out;
    }

    /**
     * Why {@link #eye()} is null, or {@code known}: {@code no position} (none sent since a new player),
     * {@code vehicle}, {@code camera} (another entity's), {@code moved} (something may have moved the player
     * after the position it sent).
     */
    public String eyeState() {
        long end = windowEnd();
        if (!sent) {
            return "no position";
        }
        if (vehicle.mayBeOtherThan(certain, end, NONE)) {
            return "vehicle";
        }
        if (camera.mayBeOtherThan(certain, end, 1)) {
            return "camera";
        }
        for (long r : resets) {
            if (r >= sentCertain && r < end) {
                return "moved";
            }
        }
        return "known";
    }

    /** The last {@code onGround} flag the client sent and {@link #certain} when it did. */
    public record Ground(boolean onGround, long certain) {}

    /**
     * The flag the client's keybinds read in its next tick, taken at the end of a tick ({@code ClientTickEnd}: that
     * tick's movement was sent before it); null before any.
     */
    public Ground ground() {
        return groundSent ? new Ground(groundFlag, groundCertain) : null;
    }

    /**
     * Whether the client's {@code onGround} was certainly false when its keybinds read a flag {@link #ground()} gave:
     * the flag says so, nothing that may have come to the client since may have set it or sent another, and the player
     * may neither ride nor watch another entity.
     */
    public boolean certainlyOffGround(Ground ground) {
        long end = windowEnd();
        return ground != null && !ground.onGround() && lastGroundReset < ground.certain()
                && !vehicle.mayBeOtherThan(certain, end, NONE) && !camera.mayBeOtherThan(certain, end, 1);
    }

    /**
     * How many new levels the client has made now ({@code ClientLevel}: every login, a respawn into another dimension):
     * {@code [low, high]}, the low end counting only the levels it certainly processed and certainly made.
     */
    public long[] levels() {
        long end = windowEnd();
        long high = levelsPossible;
        for (long[] change : levelChanges) {
            if (change[0] < end) {
                high++;
            }
        }
        return new long[] {levelsCertain, high};
    }

    /** Whether the player may be riding now (a passenger's rotation and position follow its vehicle). */
    public boolean mayRide() {
        return vehicle.mayBeOtherThan(certain, windowEnd(), NONE);
    }

    /** {@code [low, high]} of the {@code block_interaction_range} the client may have now ({@code Player.blockInteractionRange}). */
    public double[] blockRangeBounds() {
        return blockRange.hull(certain, windowEnd());
    }

    /** {@code [low, high]} of the {@code entity_interaction_range} the client may have now. */
    public double[] interactionRangeBounds() {
        return interactionRange.hull(certain, windowEnd());
    }

    /**
     * What {@code Player.getDestroySpeed} may read now, as bounds: the largest dig-speed amplifier ({@code max} of haste's and
     * conduit power's; -1: neither effect, {@link Integer#MAX_VALUE}: not known), the smallest mining fatigue amplifier
     * (-1: possibly none, which includes not known), and the largest {@code mining_efficiency}, {@code block_break_speed}
     * and {@code submerged_mining_speed}.
     */
    public record MiningBounds(int maxDigSpeedAmplifier, int minFatigueAmplifier, double maxMiningEfficiency, double maxBlockBreakSpeed,
            double maxSubmergedMiningSpeed) {}

    /** {@link MiningBounds} over everything the client may hold now. */
    public MiningBounds miningBounds() {
        long end = windowEnd();
        int dig = -1;
        for (Versioned v : new Versioned[] {haste, conduitPower}) {
            int[] values = v.values(certain, end);
            if (values == null) {
                dig = Integer.MAX_VALUE;
                break;
            }
            for (int value : values) {
                dig = value == UNKNOWN ? Integer.MAX_VALUE : Math.max(dig, value);
            }
        }
        int fatigue = Integer.MAX_VALUE;
        int[] values = miningFatigue.values(certain, end);
        if (values == null) {
            fatigue = -1;
        } else {
            for (int value : values) {
                fatigue = value == UNKNOWN || value == NONE ? -1 : Math.min(fatigue, value);
            }
        }
        return new MiningBounds(dig, fatigue, miningEfficiency.hull(certain, end)[1], blockBreakSpeed.hull(certain, end)[1],
                submergedMiningSpeed.hull(certain, end)[1]);
    }

    /** {@code [low, high]} of the scales the player's cached dimensions may have now. */
    public double[] scaleBounds() {
        return scale.hull(certain, windowEnd());
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

    /**
     * Whether {@code Player.isSpectator()} may hold now (the mode of the player-list entry the player cached), or
     * WhyNotAC does not know.
     */
    public boolean mayBeSpectator() {
        return listMode.mayBeAnyOf(certain, windowEnd(), SPECTATOR, MODE_UNKNOWN);
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

        /** The values some prefix ending in {@code [certain, end)} gives (repeats allowed); null when any value may be. */
        int[] values(long certain, long end) {
            if (certain <= overflow) {
                return null;
            }
            java.util.List<Integer> out = new ArrayList<>();
            int v = base;
            for (long[] c : changes) {
                if (c[0] >= end) {
                    break;
                }
                if (c[0] >= certain) {
                    out.add(v);
                }
                v = (int) c[1];
            }
            out.add(v);
            return out.stream().mapToInt(Integer::intValue).toArray();
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
