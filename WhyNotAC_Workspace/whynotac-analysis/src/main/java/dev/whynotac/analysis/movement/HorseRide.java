package dev.whynotac.analysis.movement;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.BlockPos;
import dev.whynotac.physics.BoatState;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.Horse;
import dev.whynotac.physics.HorseState;
import dev.whynotac.physics.ItemSteering;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientWorld;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The horse (donkey, mule, skeleton or zombie horse), pig, strider or happy ghast the client's own player rides and steers,
 * tick by tick (decompiled 1.21.11): every state it may be in ({@link HorseState}), what its next tick reads of its rider, and what it is. While
 * the player is its controlling passenger ({@code AbstractHorse.getControllingPassenger}: saddled, the player its first
 * passenger) the client moves it with its own physics: each client tick it runs {@code AbstractHorse.tick}
 * ({@link Horse}) before its rider's tick, which then sends the rider's rotation ({@code MovePlayer.Rot}), a riding
 * jump it released ({@code START_RIDING_JUMP}, its power) and {@code MoveVehicle} (the horse after its tick: position,
 * rotation, ground flag). The horse's tick reads the input the rider's last tick left ({@code xxa}, {@code zza}: from
 * that tick's keys, {@link Horse#riderInput}), the rider's rotation now (what its {@code Rot} then sends: only frames
 * between ticks turn it) and the jump the rider released in its last tick ({@link Horse#onPlayerJump}). A tick is
 * explained when a state, run through {@link Horse}, gives exactly that {@code MoveVehicle}.
 *
 * <p>The riding jump itself is the rider's, and its power follows from the jump key alone ({@code LocalPlayer.aiStep}:
 * the ticks it was held make {@code jumpRidingScale}, a release sends {@code floor(scale * 100)}): a jump sent without a
 * release, a release without one, or another power than the key's (once a press while riding makes the count known)
 * is unexplained.
 *
 * <h2>The mount</h2>
 * Until the mount the client moved the horse by interpolation alone; the entity replica keeps its state at the mount
 * ({@link ClientEntities#horseMounted}). From it: the position and rotation, the ground flag (its supporting block found
 * where a packet set it), its motion, the rearing flag, no fall, no collision, nothing stuck, no jump pending, the
 * attributes the server sent (the type's defaults else) and the effects. Not followed, so no claim until the ride ends:
 * a mount the client may have processed a tick earlier, a horse the replica did not follow (lost, ridden by the player
 * before), its first tick, its motion not known (the player may have pushed it), a fluid where it has been since that
 * motion was set or a block there changed since, a horse not saddled, not tame, a baby or scaled, an attribute not known exactly, an effect besides jump boost,
 * and the rider's keys moving it in its last tick on foot (that input was the on-foot player's).
 *
 * <h2>The ride</h2>
 * Packets about the horse: {@code SetEntityMotion} sets its motion; its no-gravity and rearing flags follow its data,
 * its attributes and effects their packets. Anything else that moves it or the player ends the claim: the server's
 * {@code MoveVehicle}, a teleport of it or of the player, a packet WhyNotAC cannot read. No claim either while an entity
 * it may collide with may be near, near a world border not known exactly. While the horse still runs the interpolation
 * toward where the server last put it (the steps it had left at the mount), {@code MoveVehicle} carries the
 * interpolation's target and rotations ({@code ServerboundMoveVehiclePacket.fromEntity}), as a boat's.
 */
final class HorseRide {

    /** More states than this end the claim. */
    static final int MAX_STATES = 64;
    /** Entities the horse may meet: their boxes searched this much beyond where its tick may take it. */
    static final double ENTITY_MARGIN = 1.5;
    /** A world border not known exactly collides within a few blocks: this much is kept free of it. */
    static final double BORDER_MARGIN = 16.0;
    /** {@code AbstractHorse.FLAG_TAME}, {@code FLAG_STANDING}; {@code Mob}'s NoAI bit. */
    static final int FLAG_TAME = 2;
    static final int FLAG_STANDING = 32;
    static final int MOB_FLAG_NO_AI = 1;
    static final String MOVEMENT_SPEED = "minecraft:movement_speed";
    static final String JUMP_STRENGTH = "minecraft:jump_strength";
    static final String STEP_HEIGHT = "minecraft:step_height";
    static final String GRAVITY = "minecraft:gravity";
    static final String JUMP_BOOST = "minecraft:jump_boost";
    static final String FLYING_SPEED = "minecraft:flying_speed";

    final int vehicle;
    /** The mount's sequence: where the claim's state starts. */
    final long since;
    /** The states it may be in after its last tick (empty while {@link #unclaimed}). */
    List<HorseState> states;
    /** Those states as its tick left them, before the rider's jump of that tick (the client's horse then, for audits). */
    List<HorseState> ticked = List.of();
    /** What the horse is: its type's size, fluids, attributes and effects as the client holds them. */
    String type;
    /** A pig ({@code Pig}): it goes where its rider looks, at the speed its boost gives, and never jumps. */
    boolean pig;
    /** A strider ({@code Strider}): steered as a pig is; it stands on lava, and suffocates out of the warm ({@link Horse.Strider}). */
    boolean strider;
    /** A happy ghast ({@code HappyGhast}): it flies where its rider steers and looks ({@link Horse.Flying}), and never jumps. */
    boolean ghast;
    /** Its flying speed attribute's value. */
    double flyingSpeed;
    /** The movement speed attribute's value ({@code getAttributeValue}, a double: a pig's ridden speed is computed from it). */
    double speedAttribute;
    /** A strider's, with its suffocating modifier ({@link ClientEntities#SUFFOCATING_SPEED}). */
    double suffocatingSpeedAttribute;
    float speed;
    /** A pig's or strider's boost ({@code ItemBasedSteering}) after its last tick, and the boost time the client holds. */
    ItemSteering steering = ItemSteering.NONE;
    int boostTotal;
    double jumpStrength;
    float stepHeight;
    double gravityAttribute;
    int jumpBoost;
    boolean noGravity;
    /** The NoAI flag: a NoAI horse does not rear at a riding jump on the client ({@code isEffectiveAi}). */
    boolean noAi;
    /** The rider's keys in its last tick: the input the horse's next tick reads. */
    PlayerState.Keys keys;
    /** {@code LocalPlayer.jumpRidingTicks} and {@code jumpRidingScale}; {@code jumpKnown}: since a press while riding. */
    int jumpRidingTicks;
    float jumpRidingScale;
    boolean jumpKnown;
    /** Why the ride is not followed (until it ends), or null. */
    String unclaimed;

    private HorseRide(int vehicle, long since) {
        this.vehicle = vehicle;
        this.since = since;
        this.states = List.of();
    }

    static HorseRide unclaimed(int vehicle, long since, String why) {
        HorseRide ride = new HorseRide(vehicle, since);
        ride.unclaimed = why;
        return ride;
    }

    void unclaim(String why) {
        if (unclaimed == null) {
            unclaimed = why;
            states = List.of();
        }
    }

    /** The default of an attribute the server did not send ({@code DefaultAttributes} of the type). */
    static double defaultAttribute(String type, String attribute) {
        // Pig.createAttributes, Strider.createAttributes, HappyGhast.createAttributes: the animal's, a speed of 0.25, 0.175F
        // and 0.05 (a flying speed of 0.05)
        boolean pig = type.equals(ClientEntities.PIG) || type.equals(ClientEntities.STRIDER) || type.equals(ClientEntities.HAPPY_GHAST);
        return switch (attribute) {
            case MOVEMENT_SPEED -> switch (type) {
                case "minecraft:donkey", "minecraft:mule", ClientEntities.STRIDER -> 0.175F; // createBaseChestedHorseAttributes
                case "minecraft:skeleton_horse" -> 0.2F;
                case ClientEntities.PIG -> 0.25;
                case ClientEntities.HAPPY_GHAST -> 0.05;
                default -> 0.225F; // createBaseHorseAttributes
            };
            case ClientEntities.SUFFOCATING_SPEED -> 0.175F + 0.175F * (double) -0.34F; // calculateValue: the base, plus the base times the amount
            case JUMP_STRENGTH -> pig ? 0.0 : type.equals("minecraft:donkey") || type.equals("minecraft:mule") ? 0.5 : 0.7;
            case STEP_HEIGHT -> pig ? 0.6 : 1.0;
            case GRAVITY -> 0.08;
            case FLYING_SPEED -> type.equals(ClientEntities.HAPPY_GHAST) ? 0.05 : 0.4;
            default -> throw new IllegalArgumentException(attribute);
        };
    }

    /**
     * {@code EntityType.Builder.sized}: every horse is 1.3964844 wide, a donkey 1.5 tall and the others 1.6; a pig 0.9 each
     * way; a strider 0.9 wide and 1.7 tall; a happy ghast 4 each way.
     */
    static float height(String type) {
        return type.equals(ClientEntities.HAPPY_GHAST) ? 4.0F : type.equals(ClientEntities.PIG) ? 0.9F : type.equals(ClientEntities.STRIDER) ? 1.7F
                : type.equals("minecraft:donkey") ? 1.5F : 1.6F;
    }

    static float width(String type) {
        return type.equals(ClientEntities.HAPPY_GHAST) ? 4.0F : type.equals(ClientEntities.PIG) || type.equals(ClientEntities.STRIDER) ? 0.9F
                : 1.3964844F;
    }

    /** {@code BlockTags.STRIDER_WARM_BLOCKS} (vanilla; {@link EngineTags} holds the client to it). */
    static final Set<String> STRIDER_WARM_BLOCKS = Set.of("minecraft:lava");

    Horse.Conditions conditions(dev.whynotac.physics.Border border, List<Box> entities) {
        // maxUpStep: the step height, at least 1 under a player; getGravity: 0 with no gravity; a pig's getRiddenSpeed:
        // the attribute times 0.225 and its boost's factor
        // the attribute times 0.225 and its boost's factor; a strider's times 0.55, or 0.35 suffocating (with its modifier)
        float ridden = pig ? (float) (speedAttribute * 0.225 * steering.factor(boostTotal)) : speed;
        Horse.Strider s = !strider ? null : new Horse.Strider((float) (speedAttribute * 0.55F * steering.factor(boostTotal)),
                (float) (suffocatingSpeedAttribute * 0.35F * steering.factor(boostTotal)), STRIDER_WARM_BLOCKS);
        return new Horse.Conditions(ridden, jumpStrength, Math.max(stepHeight, 1.0F), noGravity ? 0.0 : gravityAttribute,
                new Horse.Effects(jumpBoost, false, 0, false), type.equals("minecraft:skeleton_horse") ? 0.96F : 0.8F,
                Horse.FLOATS_WHILE_RIDDEN.contains(type), width(type), height(type), border, entities, s, ghast ? Horse.Flying.of(flyingSpeed) : null);
    }

    /**
     * The ride from the mount: the replica's horse ({@code mounted}), the world ({@code view} reads it as the client may
     * hold it, {@code world} for its changes) and the rider's keys in its last tick ({@code keys}, on foot).
     */
    static HorseRide start(int vehicle, long since, List<ClientEntities.HorseMounted> mounted, Colliders.World view, ClientWorld world,
            PlayerState.Keys keys) {
        if (mounted == null) {
            return unclaimed(vehicle, since, "a vehicle the replica did not follow before the mount");
        }
        if (mounted.size() != 1 || !mounted.getFirst().exact()) {
            return unclaimed(vehicle, since, "the horse's state at the mount not known exactly");
        }
        ClientEntities.HorseMounted m = mounted.getFirst();
        if (!m.everTicked()) {
            return unclaimed(vehicle, since, "the horse's first tick");
        }
        boolean strider = m.type().equals(ClientEntities.STRIDER);
        boolean pig = m.type().equals(ClientEntities.PIG);
        boolean ghast = m.type().equals(ClientEntities.HAPPY_GHAST);
        boolean steered = pig || strider; // by an item on a stick: neither tame nor read their rider's keys
        if (ghast && (m.saddle() != 1 || m.baby() || Double.compare(m.scale(), 1.0) != 0)) {
            return unclaimed(vehicle, since, "a happy ghast with no harness, a baby or scaled");
        }
        if (ghast && (m.flags() & 1) != 0) { // isOnStillTimeout: getControllingPassenger is null
            return unclaimed(vehicle, since, "a happy ghast staying still (its rider does not steer it)");
        }
        if (!ghast && (m.saddle() != 1 || !steered && (m.flags() & FLAG_TAME) == 0 || m.baby() || Double.compare(m.scale(), 1.0) != 0)) {
            return unclaimed(vehicle, since, steered ? "a " + (pig ? "pig" : "strider") + " not saddled, a baby or scaled"
                    : "a horse not saddled and tame, a baby or scaled");
        }
        if (strider && (m.mobFlags() & MOB_FLAG_NO_AI) != 0) {
            return unclaimed(vehicle, since, "a NoAI strider (its suffocation is the server's)");
        }
        if (m.motion() == null) {
            return unclaimed(vehicle, since, "the horse's motion at the mount not known (the player may have pushed it)");
        }
        // a pig or strider reads none of its rider's input (getRiddenInput: always ahead)
        if (!steered && (keys == null || keys.forward() != keys.backward() || keys.left() != keys.right())) {
            return unclaimed(vehicle, since, "the rider's keys moved it on foot before the mount");
        }
        if (steered && m.boost() < 0) {
            return unclaimed(vehicle, since, "the " + (pig ? "pig" : "strider") + "'s boost not known (a player may have steered it)");
        }
        HorseRide ride = new HorseRide(vehicle, since);
        ride.type = m.type();
        ride.pig = pig;
        ride.strider = strider;
        ride.ghast = ghast;
        ride.steering = m.boost() == 1 ? ItemSteering.synced() : ItemSteering.NONE;
        ride.boostTotal = m.boostTotal();
        try {
            ride.speedAttribute = ride.attribute(m.attributes(), MOVEMENT_SPEED);
            ride.speed = (float) ride.speedAttribute;
            if (strider) {
                ride.suffocatingSpeedAttribute = ride.attribute(m.attributes(), ClientEntities.SUFFOCATING_SPEED);
            }
            ride.flyingSpeed = ride.attribute(m.attributes(), FLYING_SPEED);
            ride.jumpStrength = ride.attribute(m.attributes(), JUMP_STRENGTH);
            ride.stepHeight = (float) ride.attribute(m.attributes(), STEP_HEIGHT);
            ride.gravityAttribute = ride.attribute(m.attributes(), GRAVITY);
        } catch (MovementTracker.Lose e) {
            return unclaimed(vehicle, since, e.getMessage());
        }
        for (Map.Entry<String, Integer> effect : m.effects().entrySet()) {
            if (!effect.getKey().equals(JUMP_BOOST)) {
                return unclaimed(vehicle, since, "an effect on the horse not followed: " + effect.getKey());
            }
            ride.jumpBoost = effect.getValue() + 1;
        }
        ride.noGravity = m.noGravity();
        ride.noAi = (m.mobFlags() & MOB_FLAG_NO_AI) != 0;
        // Where its motion was set it may have met water or lava since (Entity.baseTick pushes it): none there, and the
        // blocks there unchanged since
        float height = height(ride.type);
        double half = width(ride.type) / 2.0F;
        int x0 = (int) Math.floor(Math.min(m.sweptMin().x(), m.position().x()) - half) - 1;
        int y0 = (int) Math.floor(Math.min(m.sweptMin().y(), m.position().y())) - 1;
        int z0 = (int) Math.floor(Math.min(m.sweptMin().z(), m.position().z()) - half) - 1;
        int x1 = (int) Math.floor(Math.max(m.sweptMax().x(), m.position().x()) + half) + 1;
        int y1 = (int) Math.floor(Math.max(m.sweptMax().y(), m.position().y()) + height) + 1;
        int z1 = (int) Math.floor(Math.max(m.sweptMax().z(), m.position().z()) + half) + 1;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 4096) {
            return unclaimed(vehicle, since, "the horse went far since its motion was set");
        }
        if (world.mayHaveChangedIn(x0, y0, z0, x1, y1, z1, m.motionSeq())) {
            return unclaimed(vehicle, since, "blocks where the horse has been may have changed since its motion was set");
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int state = view.blockState(x, y, z);
                    if (state == ClientWorld.UNKNOWN || view.states().fluidKind(state) != BlockStates.FLUID_NONE) {
                        return unclaimed(vehicle, since, "water or lava where the horse has been, which pushes it");
                    }
                }
            }
        }
        if (m.onGround() < 0) {
            return unclaimed(vehicle, since, "the horse's ground flag not known");
        }
        boolean onGround = m.onGround() == 1;
        BlockPos support = null;
        if (onGround) {
            if (m.groundSetAt() == null) {
                return unclaimed(vehicle, since, "the horse's ground flag not known");
            }
            try {
                support = Horse.supportingBlock(m.groundSetAt().x(), m.groundSetAt().y(), m.groundSetAt().z(), view, ride.conditions(null, List.of()));
            } catch (Colliders.Unknown e) {
                return unclaimed(vehicle, since, e.getMessage());
            }
            if (support == null) {
                return unclaimed(vehicle, since, "the horse on the ground with no block below it");
            }
        }
        Vec3d at = m.position();
        BoatState.Interpolation interpolation = m.steps() == 0 ? null : new BoatState.Interpolation(m.steps(), m.target(), m.targetYRot(),
                m.targetXRot(), m.previousPosition(), m.previousYRot(), m.previousXRot());
        ride.states = List.of(new HorseState(at.x(), at.y(), at.z(), m.motion(), m.yRot(), m.xRot(), onGround, false, false, false, 0.0, support, false,
                Vec3d.ZERO, false, 0, 0.0F, false, (m.flags() & FLAG_STANDING) != 0, 0, interpolation, 0.0, false)); // no lava where it has been
        ride.keys = keys;
        return ride;
    }

    /** An attribute's value as the client holds it: the server's, else the type's default; not known exactly ends the claim. */
    private double attribute(Map<String, Double> attributes, String key) throws MovementTracker.Lose {
        Double value = attributes.get(key);
        if (value == null) {
            return defaultAttribute(type, key);
        }
        if (value.isNaN()) {
            throw new MovementTracker.Lose("the horse's " + key + " not known exactly");
        }
        return value;
    }

    /** A packet about the horse the client processed during the ride (see the class comment). */
    void apply(ClientboundPacket packet) {
        if (unclaimed != null) {
            return;
        }
        switch (packet) {
            case ClientboundPacket.SetEntityMotion motion -> {
                if (!motion.clientMotion().isFinite()) {
                    unclaim("the horse's motion set to a value not finite");
                    return;
                }
                states = states.stream().map(s -> with(s, motion.clientMotion(), s.standing())).toList(); // lerpMotion
            }
            case ClientboundPacket.SetEntityData data -> {
                for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                    if (e.index() == 5 && e.kind() == ClientboundPacket.SetEntityData.Kind.BOOLEAN) { // DATA_NO_GRAVITY
                        noGravity = e.value() != 0;
                    } else if ((pig || strider) && e.index() == 17 && e.kind() == ClientboundPacket.SetEntityData.Kind.INT) { // DATA_BOOST_TIME
                        steering = ItemSteering.synced(); // onSynced: whatever the value
                        boostTotal = (int) e.value();
                    } else if (ghast && e.index() == 18 && e.kind() == ClientboundPacket.SetEntityData.Kind.BOOLEAN && e.value() != 0) { // STAYS_STILL
                        unclaim("the happy ghast stays still (its rider no longer steers it)");
                        return;
                    } else if (!pig && !strider && !ghast && e.index() == 17 && e.kind() == ClientboundPacket.SetEntityData.Kind.BYTE) { // DATA_ID_FLAGS
                        boolean standing = (e.value() & FLAG_STANDING) != 0;
                        if ((e.value() & FLAG_TAME) == 0) {
                            unclaim("the horse no longer tame");
                            return;
                        }
                        states = states.stream().map(s -> with(s, s.deltaMovement(), standing)).toList();
                    } else if (e.index() == 15 && e.kind() == ClientboundPacket.SetEntityData.Kind.BYTE) { // DATA_MOB_FLAGS_ID
                        noAi = (e.value() & MOB_FLAG_NO_AI) != 0;
                        if (strider && noAi) {
                            unclaim("the strider made NoAI (its suffocation is the server's)");
                            return;
                        }
                    } else if (e.index() == 16) { // DATA_BABY_ID
                        unclaim("the horse's age changed");
                        return;
                    }
                }
            }
            case ClientboundPacket.UpdateAttributes update -> {
                for (ClientboundPacket.UpdateAttributes.Attribute a : update.attributes()) {
                    if (a.key().equals("minecraft:scale")) {
                        double[] range = dev.whynotac.world.AttributeValues.range(a, 0.0625, 16.0); // Attributes.SCALE
                        if (range[0] != 1.0 || range[1] != 1.0) {
                            unclaim("the horse scaled");
                        }
                        continue;
                    }
                    if (!ClientEntities.HORSE_ATTRIBUTES.contains(a.key())) {
                        continue;
                    }
                    if (strider && a.key().equals(MOVEMENT_SPEED)) {
                        double[] speeds = ClientEntities.striderSpeeds(a);
                        if (Double.isNaN(speeds[0]) || Double.isNaN(speeds[1])) {
                            unclaim("the strider's movement speed not known exactly");
                            return;
                        }
                        speedAttribute = speeds[0];
                        suffocatingSpeedAttribute = speeds[1];
                        continue;
                    }
                    double value = ClientEntities.horseAttributeValue(a);
                    if (Double.isNaN(value)) {
                        unclaim("the horse's " + a.key() + " not known exactly");
                        return;
                    }
                    switch (a.key()) {
                        case MOVEMENT_SPEED -> {
                            speedAttribute = value;
                            speed = (float) value;
                        }
                        case JUMP_STRENGTH -> jumpStrength = value;
                        case STEP_HEIGHT -> stepHeight = (float) value;
                        case FLYING_SPEED -> flyingSpeed = value;
                        default -> gravityAttribute = value;
                    }
                }
            }
            case ClientboundPacket.MobEffect effect -> {
                if (!effect.effectKey().equals(JUMP_BOOST)) {
                    unclaim("an effect on the horse not followed: " + effect.effectKey());
                    return;
                }
                jumpBoost = effect.amplifier() + 1;
            }
            case ClientboundPacket.RemoveMobEffect effect -> {
                if (effect.effectKey().equals(JUMP_BOOST)) {
                    jumpBoost = 0;
                }
            }
            case ClientboundPacket.SetEquipment equipment -> {
                for (ClientboundPacket.SetEquipment.Slot slot : equipment.slots()) {
                    if (slot.slot().equals(ghast ? "body" : "saddle")) {
                        unclaim(ghast ? "the happy ghast's harness changed" : "the horse's saddle changed");
                        return;
                    }
                }
            }
            default -> { }
        }
    }

    private static HorseState with(HorseState s, Vec3d motion, boolean standing) {
        return new HorseState(s.x(), s.y(), s.z(), motion, s.yRot(), s.xRot(), s.onGround(), s.horizontalCollision(), s.verticalCollision(),
                s.verticalCollisionBelow(), s.fallDistance(), s.support(), s.onGroundNoBlocks(), s.stuckSpeedMultiplier(), s.inPowderSnow(),
                s.noJumpDelay(), s.jumpPendingScale(), s.allowStandSliding(), standing, s.standCounter(), s.interpolation(), s.lavaHeight(), s.suffocating());
    }

    /** The outcome of one ride tick: explained, or why not (with the prediction closest to what was sent). */
    record Step(boolean explained, String reason, HorseState closest) {}

    /**
     * One client tick of the ride: the horse's tick from each state, under the input of the rider's last tick and the
     * rotation this tick sends ({@code rot}), must give the client's {@code MoveVehicle}; then this tick's keys
     * ({@code keys}) and riding jumps ({@code jumps}) are the rider's, for the next. Returns null when nothing is
     * claimed ({@link #unclaimed} says why).
     */
    Step tick(List<ServerboundPacket.MoveVehicle> moves, ServerboundPacket.MovePlayer rot, List<ServerboundPacket.PlayerCommand> jumps,
            PlayerState.Keys now, Colliders.World view, ClientEntities entities, int self, ClientBorder border, Boolean carrot) {
        if (unclaimed != null) {
            return null;
        }
        if ((pig || strider) && !Boolean.TRUE.equals(carrot)) { // getControllingPassenger: a player holding its item on a stick
            String item = pig ? "carrot on a stick" : "warped fungus on a stick";
            unclaim(carrot == null ? "whether the rider holds a " + item + " not known" : "the rider holds no " + item);
            return null;
        }
        if (moves.size() != 1 || rot == null || !rot.hasRotation() || rot.hasPosition()) {
            unclaim("not one rotation and one vehicle move in the tick");
            return null;
        }
        ServerboundPacket.MoveVehicle move = moves.getFirst();
        dev.whynotac.physics.Border collision;
        try {
            for (HorseState s : states) {
                Box box = HorseState.box(s.x(), s.y(), s.z(), width(type), height(type));
                Vec3d d = s.deltaMovement();
                double reach = Math.abs(d.x()) + Math.abs(d.y()) + Math.abs(d.z()) + 2.0; // and a jump, a step up
                if (s.interpolation() != null) {
                    Vec3d t = s.interpolation().target();
                    reach += Math.abs(t.x() - s.x()) + Math.abs(t.y() - s.y()) + Math.abs(t.z() - s.z());
                }
                if (!border.known() && !border.farFrom(box.minX(), box.minZ(), box.maxX(), box.maxZ(), BORDER_MARGIN + reach)) {
                    throw new MovementTracker.Lose("near a world border not known exactly");
                }
                double m = reach + ENTITY_MARGIN;
                if (entities.mayHaveVehicleColliderNear(self, vehicle, box.minX() - m, box.minY() - m, box.minZ() - m, box.maxX() + m,
                        box.maxY() + m, box.maxZ() + m, MovementTracker.UNKNOWN_ENTITY_RADIUS)) {
                    throw new MovementTracker.Lose("an entity the horse may meet is near");
                }
            }
            collision = border.collision();
        } catch (MovementTracker.Lose e) {
            unclaim(e.getMessage());
            return null;
        }
        boolean steered = pig || strider;
        float[] input = steered ? new float[] {0.0F, 1.0F} : Horse.riderInput(keys); // getRiddenInput: ahead, whatever the keys
        // a happy ghast reads its rider's jump key too (applyInput: jumping)
        Horse.Rider rider = new Horse.Rider(input[0], input[1], rot.yaw(), rot.pitch(), ghast && keys.jump());
        if (steered) {
            steering = steering.tick(boostTotal); // tickRidden: tickBoost, before getRiddenSpeed
        }
        Horse.Conditions conditions = conditions(collision, List.of());
        Set<HorseState> next = new LinkedHashSet<>();
        HorseState closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (HorseState s : states) {
            HorseState after;
            try {
                after = Horse.tick(s, rider, view, conditions);
            } catch (Colliders.Unknown e) {
                unclaim(e.getMessage());
                return null;
            }
            Vec3d p = move.position();
            // ServerboundMoveVehiclePacket.fromEntity: while the horse still interpolates, the interpolation's target and rotations
            BoatState.Interpolation left = after.interpolation();
            Vec3d at = left != null ? left.target() : new Vec3d(after.x(), after.y(), after.z());
            float yaw = left != null ? left.targetYRot() : after.yRot();
            float pitch = left != null ? left.targetXRot() : after.xRot();
            boolean same = Double.compare(at.x(), p.x()) == 0 && Double.compare(at.y(), p.y()) == 0 && Double.compare(at.z(), p.z()) == 0
                    && Float.compare(yaw, move.yaw()) == 0 && Float.compare(pitch, move.pitch()) == 0 && after.onGround() == move.onGround();
            if (same) {
                next.add(after);
            } else {
                double distance = Math.abs(at.x() - p.x()) + Math.abs(at.y() - p.y()) + Math.abs(at.z() - p.z());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closest = after;
                }
            }
        }
        if (next.isEmpty()) {
            keys = now;
            unclaim("unexplained horse movement");
            return new Step(false, "no vanilla client rides a " + (pig ? "pig" : strider ? "strider" : ghast ? "happy ghast" : "horse") + " so", closest);
        }
        if (steered || ghast) { // not PlayerRideableJumping: no riding jump (LocalPlayer.jumpableVehicle)
            keys = now;
            if (!jumps.isEmpty()) {
                unclaim("unexplained riding jump");
                return new Step(false, "sent a riding jump from a " + (pig ? "pig" : strider ? "strider" : "happy ghast") + ", which has none", null);
            }
            ticked = List.copyOf(next);
            if (next.size() > MAX_STATES) {
                unclaim("more than " + MAX_STATES + " horse states");
                return new Step(true, null, null);
            }
            states = List.copyOf(next);
            return new Step(true, null, null);
        }
        // the rider's LocalPlayer.aiStep: the riding jump from its keys (the last tick's jump key, then this tick's)
        boolean was = keys.jump();
        boolean pressed = now.jump();
        Integer released = null;
        if (jumpRidingTicks < 0) {
            jumpRidingTicks++;
            if (jumpRidingTicks == 0) {
                jumpRidingScale = 0.0F;
            }
        }
        if (was && !pressed) {
            jumpRidingTicks = -10;
            released = (int) Math.floor(jumpRidingScale * 100.0F); // Mth.floor
        } else if (!was && pressed) {
            jumpRidingTicks = 0;
            jumpRidingScale = 0.0F;
            jumpKnown = true;
        } else if (was) {
            jumpRidingTicks++;
            jumpRidingScale = jumpRidingTicks < 10 ? jumpRidingTicks * 0.1F : 0.8F + 2.0F / (jumpRidingTicks - 9) * 0.1F;
        }
        keys = now;
        if (released == null ? !jumps.isEmpty() : jumps.size() != 1 || jumpKnown && jumps.getFirst().data() != released) {
            unclaim("unexplained riding jump");
            return new Step(false, released == null ? "sent a riding jump its jump key did not release"
                    : jumps.isEmpty() ? "released the jump key of a riding jump it did not send"
                    : "sent a riding jump of another power than its jump key held (" + jumps.getFirst().data() + ", not " + released + ")", null);
        }
        ticked = List.copyOf(next);
        if (released != null) {
            int power = jumps.getFirst().data();
            List<HorseState> jumped = new ArrayList<>(next.size());
            for (HorseState s : next) {
                jumped.add(Horse.onPlayerJump(s, power, !noAi));
            }
            next = new LinkedHashSet<>(jumped);
        }
        if (next.size() > MAX_STATES) {
            unclaim("more than " + MAX_STATES + " horse states");
            return new Step(true, null, null);
        }
        states = List.copyOf(next);
        return new Step(true, null, null);
    }
}
