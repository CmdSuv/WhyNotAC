package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.AttributeValues;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Replicas;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Velocity response ({@code velocity-response}, P-A, SHADOW, v3): verifies that when the server sends a
 * vertical or horizontal knockback ({@code ClientboundSetEntityMotionPacket} or {@code ClientboundExplodePacket}
 * with {@code playerKnockback}) to an on-ground survival player with open air across their full hitbox footprint,
 * the client does not ignore or partially suppress the impulse ({@code 0%..60%} vertical Anti-KB, {@code 0%..44%}
 * stationary horizontal Anti-KB, and vanilla Jump-Reset-safe heavy horizontal Anti-KB) after transaction
 * acknowledgement.
 *
 * <p>Even when nearby entities cause {@link MovementCheck} to withdraw its bit-exact horizontal step claim,
 * {@code Entity.push} is strictly horizontal ({@code dy = 0}) and cannot cancel a vertical knockback impulse
 * when no ceiling, fluid, or climbable block intersects the player's {@code 0.6x0.6} footprint.
 *
 * <p>For horizontal knockback, a legitimate vanilla 1.21.11 PvP "Jump Reset" (holding movement + sprint + jump
 * on the knockback tick) can oppose up to {@code 0.20 (sprint-jump) + 0.13 (sprint ground input) = 0.33} blocks
 * on standard ground friction ({@code 0.6F}, speed factor {@code 1.0F}, default movement speed). When movement or
 * jump input is present, horizontal suppression is only claimed when {@code horizMag >= 0.45} against standard
 * friction and default speed with a strict {@code horizMag - 0.36} floor; when the player is stationary with no
 * movement/jump input, zero counter-impulse is possible so both {@code 0%} and partial ({@code < 45%})
 * horizontal suppression are proven.
 */
public final class VelocityCheck implements TimelineListener {

    public static final String ID = "velocity-response";
    public static final int VERSION = 5;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final double MIN_VERTICAL_KB = 0.12;
    static final double MIN_EXPECTED_RISE_FACTOR = 0.65;
    static final double MIN_HORIZONTAL_KB = 0.25;
    static final double MIN_HORIZONTAL_DISPLACEMENT = 0.08;
    static final double STATIONARY_HORIZ_FACTOR = 0.45;
    static final double MIN_STRAFE_HORIZONTAL_KB = 0.28;
    static final double MAX_VANILLA_GROUND_STRAFE_COUNTER = 0.16;
    static final double MIN_INPUT_HORIZONTAL_KB = 0.44;
    static final double MAX_VANILLA_JUMP_RESET_COUNTER = 0.36;
    static final double MIN_DELAYED_TICK1_PROJ = 0.25;
    static final double MAX_DELAYED_TICK2_RETENTION = 0.15;
    static final double EXPLOSION_GRAVITY_SLACK = 0.08;
    static final double FOOTPRINT_HALF_WIDTH = 0.3001;

    private static final int AIR_STATE = BlockStates.vanilla().firstStateOf("minecraft:air");
    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public VelocityCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private record PendingKb(long sentSeq, long sentNano, String source, double vx, double vy, double rawVy, double vz, double horizMag,
            double baseX, double baseY, double baseZ, boolean verticalCandidate, boolean horizontalCandidate) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final PingLedger ledger;
        final boolean sharedLedger;
        long lastReportNanos = Long.MIN_VALUE;

        boolean loaded;
        boolean flying;
        boolean mayFly;
        boolean dead;
        boolean noGravity;
        boolean customGravity;
        boolean customScale;
        boolean customSpeed;
        boolean hasSpeedEffect;
        boolean hasVerticalEffect;
        boolean currentHorizInput;
        boolean currentJumpInput;
        boolean currentShiftInput;
        int groundPosCount;
        double lastX = Double.NaN;
        double lastY = Double.NaN;
        double lastZ = Double.NaN;
        double lastHorizStep = Double.POSITIVE_INFINITY;
        boolean sawPosMoveThisTick;
        boolean lastOnGround;
        boolean movedUpSinceKb;
        double maxVerticalRise;
        boolean horizCollisionSinceKb;
        boolean hadHorizInputSinceKb;
        boolean hadJumpInputSinceKb;
        boolean hadShiftInputSinceKb;
        double maxHorizProj;
        double prevTickHorizProj;
        double tick1HorizStepProj;
        double tick2HorizStepProj;
        boolean sawTick1Step;
        boolean sawTick2Step;

        PendingKb pending;
        PendingKb activeAcked;
        int ticksSinceAck;

        State(PingLedger shared) {
            this.sharedLedger = shared != null;
            this.ledger = shared != null ? shared : new PingLedger();
        }

        static State fromSession(PlayerSession session) {
            return new State(session.pingLedger());
        }

        boolean exempt() {
            return flying || mayFly || dead || noGravity || customGravity || customScale || hasVerticalEffect;
        }

        void clearKb() {
            pending = null;
            activeAcked = null;
            movedUpSinceKb = false;
            maxVerticalRise = 0.0;
            horizCollisionSinceKb = false;
            hadHorizInputSinceKb = false;
            hadJumpInputSinceKb = false;
            hadShiftInputSinceKb = false;
            maxHorizProj = 0.0;
            prevTickHorizProj = 0.0;
            tick1HorizStepProj = 0.0;
            tick2HorizStepProj = 0.0;
            sawTick1Step = false;
            sawTick2Step = false;
            ticksSinceAck = 0;
        }

        void resetSession() {
            loaded = false;
            flying = false;
            mayFly = false;
            dead = false;
            noGravity = false;
            customGravity = false;
            customScale = false;
            customSpeed = false;
            hasSpeedEffect = false;
            hasVerticalEffect = false;
            currentHorizInput = false;
            currentJumpInput = false;
            currentShiftInput = false;
            reset();
        }

        void reset() {
            groundPosCount = 0;
            lastOnGround = false;
            lastHorizStep = Double.POSITIVE_INFINITY;
            sawPosMoveThisTick = false;
            clearKb();
        }
    }

    @Override
    public boolean readsEntityMotion() {
        return true;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        if (event instanceof TimelineEvent.Outbound out
                && (out.packet() instanceof ClientboundPacket.MoveEntity
                || out.packet() instanceof ClientboundPacket.RotateHead
                || out.packet() instanceof ClientboundPacket.EntityPositionSync)) {
            return;
        }
        State state = session.attachment(STATE, State::fromSession);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> state.reset();
            case TimelineEvent.Outbound out -> onOutbound(session, state, out);
            case TimelineEvent.Acknowledged ack -> onAcknowledged(state, ack);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onOutbound(PlayerSession session, State state, TimelineEvent.Outbound out) {
        switch (out.packet()) {
            case ClientboundPacket.Login ignored -> state.resetSession();
            case ClientboundPacket.Respawn ignored -> state.resetSession();
            case ClientboundPacket.PlayerPosition ignored -> state.reset();
            case ClientboundPacket.PlayerAbilities a -> {
                state.flying = a.flying();
                state.mayFly = a.mayFly();
                if (state.flying || state.mayFly) {
                    state.clearKb();
                }
            }
            case ClientboundPacket.SetHealth h -> {
                state.dead = !(h.health() > 0.0F);
                if (state.dead) {
                    state.clearKb();
                }
            }
            case ClientboundPacket.SetEntityData data -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && data.entityId() == self.selfId()) {
                    for (ClientboundPacket.SetEntityData.DataEntry e : data.entries()) {
                        if (e.index() == 5 && e.kind() == ClientboundPacket.SetEntityData.Kind.BOOLEAN) {
                            state.noGravity = e.value() != 0;
                            if (state.noGravity) {
                                state.clearKb();
                            }
                        } else if (e.index() == 0 && e.kind() == ClientboundPacket.SetEntityData.Kind.BYTE
                                && (((int) e.value()) & (1 << 7)) != 0) {
                            state.clearKb(); // gliding / fall-flying
                        }
                    }
                }
            }
            case ClientboundPacket.UpdateAttributes update -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && update.entityId() == self.selfId()) {
                    for (ClientboundPacket.UpdateAttributes.Attribute a : update.attributes()) {
                        if ("minecraft:gravity".equals(a.key())) {
                            double[] range = AttributeValues.range(a, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                            state.customGravity = !(range[0] == 0.08 && range[1] == 0.08);
                            if (state.customGravity) {
                                state.clearKb();
                            }
                        } else if ("minecraft:scale".equals(a.key())) {
                            double[] range = AttributeValues.range(a, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                            state.customScale = !(range[0] == 1.0 && range[1] == 1.0);
                            if (state.customScale) {
                                state.clearKb();
                            }
                        } else if ("minecraft:movement_speed".equals(a.key())) {
                            double[] range = AttributeValues.range(a, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                            // Default player base speed is 0.10 (0.13 while sprinting); anything above indicates speed buffs
                            state.customSpeed = range[1] > 0.1301;
                        }
                    }
                }
            }
            case ClientboundPacket.MobEffect effect -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && effect.entityId() == self.selfId()) {
                    if ("minecraft:levitation".equals(effect.effectKey()) || "minecraft:slow_falling".equals(effect.effectKey())) {
                        state.hasVerticalEffect = true;
                        state.clearKb();
                    } else if ("minecraft:speed".equals(effect.effectKey())) {
                        state.hasSpeedEffect = true;
                    }
                }
            }
            case ClientboundPacket.RemoveMobEffect effect -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && effect.entityId() == self.selfId()) {
                    if ("minecraft:levitation".equals(effect.effectKey()) || "minecraft:slow_falling".equals(effect.effectKey())) {
                        state.hasVerticalEffect = false;
                    } else if ("minecraft:speed".equals(effect.effectKey())) {
                        state.hasSpeedEffect = false;
                    }
                }
            }
            case ClientboundPacket.Ping ping -> {
                if (!state.sharedLedger) {
                    state.ledger.onPing(ping.id(), out.sequence());
                }
            }
            case ClientboundPacket.SetEntityMotion motion -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && motion.entityId() == self.selfId()) {
                    armKnockback(session, state, out, "motion", motion.clientMotion(), false);
                }
            }
            case ClientboundPacket.Explosion explosion -> {
                Vec3d pk = explosion.playerKnockback();
                if (pk != null && !pk.equals(Vec3d.ZERO)) {
                    if (!pk.isFinite() || pk.y() < 0.0) {
                        state.clearKb();
                    } else if (state.pending != null && "explosion".equals(state.pending.source()) && state.activeAcked == null) {
                        // Chained WindCharge / explosion knockback before ack: vanilla accumulates deltaMovement.add(pk)
                        PendingKb prev = state.pending;
                        Vec3d combined = new Vec3d(prev.vx() + pk.x(), prev.rawVy() + pk.y(), prev.vz() + pk.z());
                        double effVy = combined.y() - EXPLOSION_GRAVITY_SLACK;
                        double horizMag = Math.hypot(combined.x(), combined.z());
                        ClientSelf self = replicas.self().self(session);
                        ClientWorld world = replicas.world().world(session);
                        if (world == null || world.hasUnlocatedPrediction() || state.exempt() || self.mayBeCreativeOrSpectator() || self.mayRide()) {
                            state.clearKb();
                        } else {
                            boolean vert = effVy >= MIN_VERTICAL_KB && hasOpenAirAbove(world, prev.baseX(), prev.baseY(), prev.baseZ());
                            boolean sameDirHoriz = (prev.vx() * pk.x() + prev.vz() * pk.z()) >= -1.0e-6;
                            boolean horiz = prev.horizontalCandidate() && sameDirHoriz && horizMag >= MIN_HORIZONTAL_KB && effVy > 0.0
                                    && hasOpenAirBox(world, prev.baseX(), prev.baseY(), prev.baseZ())
                                    && noPusherNear(replicas.entities().entities(session), self.selfId(), prev.baseX(), prev.baseY(), prev.baseZ());
                            if (vert || horiz) {
                                state.pending = new PendingKb(out.sequence(), out.nanoTime(), "explosion",
                                        combined.x(), effVy, combined.y(), combined.z(), horizMag,
                                        prev.baseX(), prev.baseY(), prev.baseZ(), vert, horiz);
                            } else {
                                state.clearKb();
                            }
                        }
                    } else if (state.activeAcked != null && "explosion".equals(state.activeAcked.source())) {
                        // Mid-air follow-up WindCharge explosion (pk.y >= 0): upward rise only increases; guard opposing horizontal vectors
                        PendingKb active = state.activeAcked;
                        boolean opposingHoriz = active.horizontalCandidate() && (active.vx() * pk.x() + active.vz() * pk.z()) < 0.0;
                        if (opposingHoriz) {
                            if (active.verticalCandidate()) {
                                state.activeAcked = new PendingKb(active.sentSeq(), active.sentNano(), active.source(),
                                        active.vx(), active.vy(), active.rawVy(), active.vz(), active.horizMag(),
                                        active.baseX(), active.baseY(), active.baseZ(), true, false);
                            } else {
                                state.clearKb();
                            }
                        }
                    } else if (state.pending != null || state.activeAcked != null) {
                        state.clearKb();
                    } else {
                        armKnockback(session, state, out, "explosion", pk, true);
                    }
                }
            }
            default -> { }
        }
    }

    private void armKnockback(PlayerSession session, State state, TimelineEvent.Outbound out, String source, Vec3d v, boolean additive) {
        ClientSelf self = replicas.self().self(session);
        if (v == null || !v.isFinite() || !state.loaded || state.exempt() || state.groundPosCount < 2
                || !state.lastOnGround || !Double.isFinite(state.lastY)
                || !Double.isFinite(state.lastX) || !Double.isFinite(state.lastZ)
                || self.mayBeCreativeOrSpectator() || self.mayRide()) {
            state.clearKb();
            return;
        }
        ClientWorld world = replicas.world().world(session);
        if (world == null || world.hasUnlocatedPrediction()) {
            state.clearKb();
            return;
        }
        double effVy = additive ? v.y() - EXPLOSION_GRAVITY_SLACK : v.y();
        boolean vert = effVy >= MIN_VERTICAL_KB && hasOpenAirAbove(world, state.lastX, state.lastY, state.lastZ);
        double horizMag = Math.hypot(v.x(), v.z());
        boolean horizEligible = !additive || state.lastHorizStep <= 1.0e-3;
        // Vanilla Player.maybeBackOffFromEdge runs whenever movement.y <= 0 and the player is sneaking
        // (isStayingOnGroundSurface), clamping horizontal motion to 0 at block edges; require strict effVy > 0.
        boolean horiz = horizEligible && horizMag >= MIN_HORIZONTAL_KB && effVy > 0.0
                && hasOpenAirBox(world, state.lastX, state.lastY, state.lastZ)
                && noPusherNear(replicas.entities().entities(session), self.selfId(), state.lastX, state.lastY, state.lastZ);
        if (vert || horiz) {
            state.pending = new PendingKb(out.sequence(), out.nanoTime(), source, v.x(), effVy, v.y(), v.z(), horizMag,
                    state.lastX, state.lastY, state.lastZ, vert, horiz);
            state.activeAcked = null;
            state.movedUpSinceKb = false;
            state.maxVerticalRise = 0.0;
            state.horizCollisionSinceKb = false;
            state.hadHorizInputSinceKb = state.currentHorizInput || state.lastHorizStep > 1.0e-3;
            state.hadJumpInputSinceKb = state.currentJumpInput;
            state.hadShiftInputSinceKb = state.currentShiftInput;
            state.maxHorizProj = 0.0;
            state.prevTickHorizProj = 0.0;
            state.tick1HorizStepProj = 0.0;
            state.tick2HorizStepProj = 0.0;
            state.sawTick1Step = false;
            state.sawTick2Step = false;
            state.ticksSinceAck = 0;
        } else {
            state.clearKb();
        }
    }

    private void onAcknowledged(State state, TimelineEvent.Acknowledged ack) {
        long pingSeq = state.sharedLedger ? state.ledger.lastMatched() : state.ledger.onAcknowledged(ack.transactionId());
        if (pingSeq != Long.MIN_VALUE && state.pending != null && pingSeq > state.pending.sentSeq()) {
            state.activeAcked = state.pending;
            state.pending = null;
            state.ticksSinceAck = 0;
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerLoaded ignored -> {
                state.loaded = true;
                state.reset();
            }
            case ServerboundPacket.AcceptTeleportation ignored -> state.reset();
            case ServerboundPacket.PlayerAbilities a -> {
                if (a.flying()) {
                    state.flying = true;
                    state.clearKb();
                }
            }
            case ServerboundPacket.PlayerCommand cmd
                    when cmd.action() == ServerboundPacket.PlayerCommand.Action.START_FALL_FLYING -> state.clearKb();
            case ServerboundPacket.PlayerInput input -> {
                boolean horizInput = input.forward() || input.backward() || input.left() || input.right();
                boolean jumpInput = input.jump();
                boolean shiftInput = input.shift();
                state.currentHorizInput = horizInput;
                state.currentJumpInput = jumpInput;
                state.currentShiftInput = shiftInput;
                if (state.pending != null || state.activeAcked != null) {
                    if (horizInput) {
                        state.hadHorizInputSinceKb = true;
                    }
                    if (jumpInput) {
                        state.hadJumpInputSinceKb = true;
                    }
                    if (shiftInput) {
                        state.hadShiftInputSinceKb = true;
                    }
                }
            }
            case ServerboundPacket.MovePlayer move -> {
                if (move.hasPosition() && !move.hasNonFiniteValue()) {
                    state.sawPosMoveThisTick = true;
                    if (Double.isFinite(state.lastX) && Double.isFinite(state.lastZ)) {
                        state.lastHorizStep = Math.hypot(move.x() - state.lastX, move.z() - state.lastZ);
                    }
                    // Stepping off a ledge before the client has acknowledged the knockback invalidates vertical baseY
                    if (state.pending != null && state.activeAcked == null && move.y() < state.pending.baseY() - 1.0e-4) {
                        state.clearKb();
                    }
                    PendingKb kb = state.activeAcked != null ? state.activeAcked : state.pending;
                    if (kb != null) {
                        double rise = move.y() - kb.baseY();
                        if (rise > state.maxVerticalRise) {
                            state.maxVerticalRise = rise;
                        }
                        if (rise >= kb.vy() * MIN_EXPECTED_RISE_FACTOR - 1.0e-4) {
                            state.movedUpSinceKb = true;
                        }
                        if (kb.horizontalCandidate() && kb.horizMag() > 0.0) {
                            double proj = ((move.x() - kb.baseX()) * kb.vx() + (move.z() - kb.baseZ()) * kb.vz()) / kb.horizMag();
                            state.maxHorizProj = Math.max(state.maxHorizProj, proj);
                            if (state.activeAcked != null) {
                                double stepProj = proj - state.prevTickHorizProj;
                                if (!state.sawTick1Step) {
                                    state.tick1HorizStepProj = stepProj;
                                    state.sawTick1Step = true;
                                } else if (!state.sawTick2Step) {
                                    state.tick2HorizStepProj = stepProj;
                                    state.sawTick2Step = true;
                                }
                            }
                            state.prevTickHorizProj = proj;
                            if (move.horizontalCollision()) {
                                state.horizCollisionSinceKb = true;
                            }
                        }
                    }
                    state.lastX = move.x();
                    state.lastY = move.y();
                    state.lastZ = move.z();
                    if (state.loaded && move.onGround()) {
                        state.groundPosCount++;
                    }
                } else {
                    state.lastHorizStep = 0.0;
                    if (move.horizontalCollision() && (state.pending != null || state.activeAcked != null)) {
                        state.horizCollisionSinceKb = true;
                    }
                }
                state.lastOnGround = move.onGround();
            }
            case ServerboundPacket.ClientTickEnd ignored -> onTickEnd(session, state, in);
            default -> { }
        }
    }

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        if (!state.sawPosMoveThisTick && state.loaded && state.groundPosCount >= 1) {
            // Vanilla sendPosition omits MovePlayer when horizontal/vertical displacement <= 2e-4
            state.lastHorizStep = 0.0;
        }
        state.sawPosMoveThisTick = false;
        if (state.activeAcked == null) {
            return;
        }
        ClientSelf self = replicas.self().self(session);
        if (state.exempt() || self.mayBeCreativeOrSpectator() || self.mayRide()) {
            state.clearKb();
            return;
        }
        ClientWorld world = replicas.world().world(session);
        if (world == null || world.hasUnlocatedPrediction()) {
            state.clearKb();
            return;
        }
        PendingKb active = state.activeAcked;
        boolean vertReady = active.verticalCandidate() && !state.movedUpSinceKb
                && hasOpenAirAbove(world, active.baseX(), active.baseY(), active.baseZ())
                && hasOpenAirAbove(world, state.lastX, state.lastY, state.lastZ);
        double expectedHorizMin = expectedHorizontalMinimum(state, active, world);
        boolean horizReady = active.horizontalCandidate() && !state.horizCollisionSinceKb
                && Double.isFinite(expectedHorizMin)
                && state.maxHorizProj < expectedHorizMin - 1.0e-4
                && hasOpenAirBox(world, state.lastX, state.lastY, state.lastZ)
                && noPusherNear(replicas.entities().entities(session), self.selfId(), state.lastX, state.lastY, state.lastZ);
        boolean delayedBrakeEligible = active.horizontalCandidate()
                && !state.hadHorizInputSinceKb && !state.hadJumpInputSinceKb && !state.hadShiftInputSinceKb
                && !state.horizCollisionSinceKb
                && hasStandardGroundFriction(world, active.baseX(), active.baseY(), active.baseZ())
                && state.sawTick1Step && state.tick1HorizStepProj >= MIN_DELAYED_TICK1_PROJ
                && hasOpenAirBox(world, state.lastX, state.lastY, state.lastZ)
                && noPusherNear(replicas.entities().entities(session), self.selfId(), state.lastX, state.lastY, state.lastZ);
        boolean delayedHorizBrake = delayedBrakeEligible && state.ticksSinceAck >= 1 && state.sawTick2Step
                && state.tick2HorizStepProj < Math.min(0.05, state.tick1HorizStepProj * MAX_DELAYED_TICK2_RETENTION);
        if (!vertReady && !horizReady && !delayedBrakeEligible) {
            state.clearKb();
            return;
        }
        state.ticksSinceAck++;
        if (state.ticksSinceAck >= 2) {
            PendingKb kb = state.activeAcked;
            double maxRise = state.maxVerticalRise;
            double maxProj = state.maxHorizProj;
            double step1Proj = state.tick1HorizStepProj;
            double step2Proj = state.sawTick2Step ? state.tick2HorizStepProj : 0.0;
            boolean stayedOnGround = state.lastOnGround;
            boolean reportVert = vertReady;
            boolean reportHoriz = horizReady;
            boolean reportDelayed = !vertReady && !horizReady && delayedHorizBrake;
            state.clearKb();
            if (!reportVert && !reportHoriz && !reportDelayed) {
                return;
            }
            if (state.lastReportNanos != Long.MIN_VALUE && tickEnd.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
                return;
            }
            Admissibility admissibility = state.admissibility.evaluate(kb.sentSeq());
            if (!admissibility.admissible()) {
                sink.rejected(session, ID, admissibility);
                return;
            }
            Map<String, String> details = new TreeMap<>();
            details.put("source", kb.source());
            if (reportVert) {
                boolean partial = !stayedOnGround || maxRise > 1.0e-3;
                String reason = partial ? "partial-vertical-knockback" : "ignored-vertical-knockback";
                details.put("reason", reason);
                details.put("sentVy", String.format(Locale.ROOT, "%.4f", kb.vy()));
                details.put("maxRise", String.format(Locale.ROOT, "%.4f", maxRise));
                details.put("baseY", String.format(Locale.ROOT, "%.4f", kb.baseY()));
                details.put("currentY", String.format(Locale.ROOT, "%.4f", state.lastY));
                sink.accept(session, new Evidence(
                        ID, VERSION, EvidenceClass.ADVANTAGE_PROOF,
                        kb.sentSeq(), tickEnd.sequence(), kb.sentNano(), tickEnd.nanoTime(),
                        String.format(Locale.ROOT, "%s vy=%.4f (maxRise=%.4f at y=%.2f)", reason, kb.vy(), maxRise, state.lastY),
                        details, admissibility));
            } else if (reportHoriz) {
                boolean partial = maxProj > 1.0e-3;
                String reason = partial ? "partial-horizontal-knockback" : "ignored-horizontal-knockback";
                details.put("reason", reason);
                details.put("expectedVx", String.format(Locale.ROOT, "%.4f", kb.vx()));
                details.put("expectedVz", String.format(Locale.ROOT, "%.4f", kb.vz()));
                details.put("expectedHorizontal", String.format(Locale.ROOT, "%.4f", kb.horizMag()));
                details.put("minExpectedProjection", String.format(Locale.ROOT, "%.4f", expectedHorizMin));
                details.put("projectedDisplacement", String.format(Locale.ROOT, "%.4f", maxProj));
                details.put("currentX", String.format(Locale.ROOT, "%.4f", state.lastX));
                details.put("currentZ", String.format(Locale.ROOT, "%.4f", state.lastZ));
                sink.accept(session, new Evidence(
                        ID, VERSION, EvidenceClass.ADVANTAGE_PROOF,
                        kb.sentSeq(), tickEnd.sequence(), kb.sentNano(), tickEnd.nanoTime(),
                        String.format(Locale.ROOT, "%s %.4f (projected %.4f, min %.4f)",
                                reason, kb.horizMag(), maxProj, expectedHorizMin),
                        details, admissibility));
            } else {
                String reason = "delayed-horizontal-knockback";
                details.put("reason", reason);
                details.put("expectedHorizontal", String.format(Locale.ROOT, "%.4f", kb.horizMag()));
                details.put("tick1Projection", String.format(Locale.ROOT, "%.4f", step1Proj));
                details.put("tick2Projection", String.format(Locale.ROOT, "%.4f", step2Proj));
                sink.accept(session, new Evidence(
                        ID, VERSION, EvidenceClass.ADVANTAGE_PROOF,
                        kb.sentSeq(), tickEnd.sequence(), kb.sentNano(), tickEnd.nanoTime(),
                        String.format(Locale.ROOT, "%s tick1=%.4f -> tick2=%.4f (abrupt 2nd-tick momentum zeroing)",
                                reason, step1Proj, step2Proj),
                        details, admissibility));
            }
            state.lastReportNanos = tickEnd.nanoTime();
        }
    }

    private static double expectedHorizontalMinimum(State state, PendingKb kb, ClientWorld world) {
        if (state.hadShiftInputSinceKb) {
            return Double.NaN;
        }
        if (!state.hadHorizInputSinceKb && !state.hadJumpInputSinceKb) {
            return Math.max(MIN_HORIZONTAL_DISPLACEMENT, kb.horizMag() * STATIONARY_HORIZ_FACTOR);
        }
        if (state.customSpeed || state.hasSpeedEffect
                || !hasStandardGroundFriction(world, kb.baseX(), kb.baseY(), kb.baseZ())) {
            return Double.NaN;
        }
        if (!state.hadJumpInputSinceKb) {
            if (kb.horizMag() < MIN_STRAFE_HORIZONTAL_KB) {
                return Double.NaN;
            }
            return Math.max(MIN_HORIZONTAL_DISPLACEMENT, kb.horizMag() - MAX_VANILLA_GROUND_STRAFE_COUNTER);
        }
        if (kb.horizMag() < MIN_INPUT_HORIZONTAL_KB) {
            return Double.NaN;
        }
        return kb.horizMag() - MAX_VANILLA_JUMP_RESET_COUNTER;
    }

    private static boolean hasStandardGroundFriction(ClientWorld world, double x, double y, double z) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        BlockStates states = world.states();
        int minBx = (int) Math.floor(x - FOOTPRINT_HALF_WIDTH);
        int maxBx = (int) Math.floor(x + FOOTPRINT_HALF_WIDTH);
        int by = (int) Math.floor(y) - 1;
        int minBz = (int) Math.floor(z - FOOTPRINT_HALF_WIDTH);
        int maxBz = (int) Math.floor(z + FOOTPRINT_HALF_WIDTH);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int bz = minBz; bz <= maxBz; bz++) {
                if (!world.isLoaded(bx >> 4, bz >> 4)) {
                    return false;
                }
                int s = world.settledBlockState(bx, by, bz);
                if (!states.isValid(s) || !states.has(s, BlockStates.FULL_BLOCK)
                        || states.friction(s) != 0.6F || states.speedFactor(s) != 1.0F
                        || "minecraft:soul_soil".equals(states.block(s))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasOpenAirAbove(ClientWorld world, double x, double y, double z) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        int minBx = (int) Math.floor(x - FOOTPRINT_HALF_WIDTH);
        int maxBx = (int) Math.floor(x + FOOTPRINT_HALF_WIDTH);
        int by = (int) Math.floor(y);
        int minBz = (int) Math.floor(z - FOOTPRINT_HALF_WIDTH);
        int maxBz = (int) Math.floor(z + FOOTPRINT_HALF_WIDTH);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int bz = minBz; bz <= maxBz; bz++) {
                if (!world.isLoaded(bx >> 4, bz >> 4)) {
                    return false;
                }
                for (int dy = 0; dy <= 2; dy++) {
                    if (world.settledBlockState(bx, by + dy, bz) != AIR_STATE) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean hasOpenAirBox(ClientWorld world, double x, double y, double z) {
        if (world == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!world.isLoaded((bx + dx) >> 4, (bz + dz) >> 4)) {
                    return false;
                }
                for (int dy = 0; dy <= 2; dy++) {
                    if (world.settledBlockState(bx + dx, by + dy, bz + dz) != AIR_STATE) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean noPusherNear(dev.whynotac.world.ClientEntities entities, int selfId, double x, double y, double z) {
        if (entities == null || selfId < 0) {
            return false;
        }
        return !entities.mayHavePusherNear(selfId, x - 1.5, y - 1.5, z - 1.5, x + 1.5, y + 2.5, z + 1.5, 64.0);
    }
}
