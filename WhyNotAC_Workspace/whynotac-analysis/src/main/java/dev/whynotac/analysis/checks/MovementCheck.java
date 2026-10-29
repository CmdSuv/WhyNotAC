package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.movement.EngineTags;
import dev.whynotac.analysis.movement.MovementTracker;
import dev.whynotac.physics.BoatState;
import dev.whynotac.physics.HorseState;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Movement (P-M): the client moved on foot as no vanilla 1.21.11 client could, every state it may have been
 * in considered ({@link MovementTracker}). Its own player is followed tick by tick through the bit-exact
 * on-foot step, from an anchor where its state is known (a new player, or one standing still), through what
 * the server sends it (teleports, knockback, explosions, attributes, effects, food, its own flags), each in the client
 * tick the transactions place it in or, where they cannot, in every tick it may be in. A tick is a finding
 * when no state the client may have been in sends what it sent: the position to the bit, the flags, the
 * sprint and input packets. Items in use slow it as their use effects say; while one is in use, {@code handleKeybinds}
 * neither attacks nor starts a use, so a tick that does is explained only by a state not using one. Whatever the step does not model (fluids, climbing, a use on a block,
 * vehicles, pushing entities, blocks still changing, the world border) ends the claim until the next anchor. Nor is
 * anything claimed over a span in which the client may have held other tags than the engine takes as vanilla
 * ({@link EngineTags}: what it climbs, stands on and swims in, from the server's {@code update_tags}).
 *
 * <p>A boat the player steers is followed the same way from its mount ({@code BoatRide}): the client's own state for it
 * then (the entity replica's), each tick of its physics from every state it may be in, and the vehicle move and paddle
 * packets it must send; a tick no state sends is a finding (boat fly and boat speed cheats). A horse the player rides
 * likewise ({@code HorseRide}): its tick from every state under the rider's input, rotation and riding jump, whose
 * power its jump key decides (horse speed, horse fly and jump power cheats), and a pig the player steers with a carrot
 * on a stick, ahead at the speed its boost gives and with no jump (pig speed and pig fly cheats), and a strider with a
 * warped fungus on a stick, standing on lava and slowed out of the warm (strider speed and strider fly cheats). What only a passenger
 * sends (a vehicle move, a paddle packet, a riding jump, the vehicle's inventory) while the client rode nothing is one
 * too: on foot it would otherwise only end the claim; so is what only a vehicle's controller sends (a vehicle move, a
 * paddle packet, a riding jump) while it rode only vehicles of types no player controls (a minecart, another player, a
 * mob but the rideable ones) or without the saddle (a happy ghast's harness) their type needs; so are flight abilities
 * from a client that could not fly ({@code LocalPlayer.aiStep}
 * sends them only with {@code mayfly} or while flying).
 *
 * <p>A modified client is proven, not a cheat: a client mod that moves the player differently (or even
 * rounds it differently) produces this too; fly, speed, step, no-slow, jump and velocity cheats on foot all
 * do. At most one finding per {@link #REPORT_INTERVAL_NANOS} per session.
 */
public final class MovementCheck implements TimelineListener {

    public static final String ID = "movement";
    public static final int VERSION = 9;
    private static final EngineTags ENGINE_TAGS = new EngineTags(dev.whynotac.world.BlockStates.vanilla());
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public MovementCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final MovementTracker tracker = new MovementTracker();
        long lastReportNanos = Long.MIN_VALUE;
        long unexplainedSinceReport;
        /** {@link EngineTags#mayDiffer} for the tags' revision it was asked at; the last sequence at which it was not null. */
        long tagsRevision = Long.MIN_VALUE;
        String tagsDiffer = "not asked";
        long lastTagsDiffer = Long.MIN_VALUE;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        dev.whynotac.world.ClientTags tags = replicas.tags().tags(session);
        if (tags.revision() != state.tagsRevision) {
            state.tagsRevision = tags.revision();
            state.tagsDiffer = ENGINE_TAGS.mayDiffer(tags);
        }
        if (state.tagsDiffer != null) {
            state.lastTagsDiffer = event.sequence();
        }
        MovementTracker.Tick tick = state.tracker.onEvent(event, replicas.world().world(session), replicas.entities().entities(session),
                replicas.inventory().inventory(session));
        if (tick != null && tick.outcome() == MovementTracker.Outcome.UNEXPLAINED) {
            report(session, state, tick);
        }
    }

    private void report(PlayerSession session, State state, MovementTracker.Tick tick) {
        if (state.lastTagsDiffer >= tick.since()) {
            return; // the client may have held other tags than the engine's since the claim's anchor
        }
        state.unexplainedSinceReport++;
        if (state.lastReportNanos != Long.MIN_VALUE && tick.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = state.admissibility.evaluate(tick.since());
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("clientTick", Long.toString(tick.number()));
        details.put("unexplainedTicks", Long.toString(state.unexplainedSinceReport));
        details.put("reason", tick.reason());
        if (tick.reason() != null && (tick.reason().startsWith(MovementTracker.VEHICLE_ON_FOOT)
                || tick.reason().startsWith(MovementTracker.VEHICLE_UNCONTROLLED))) {
            ServerboundPacket.MoveVehicle vehicle = tick.sentVehicle();
            if (vehicle != null) {
                details.put("sent", "MoveVehicle " + format(vehicle.position().x()) + "," + format(vehicle.position().y()) + ","
                        + format(vehicle.position().z()));
            }
            sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, tick.since(), tick.sequence(), tick.nanoTime(),
                    tick.nanoTime(), tick.reason() + " (tick " + tick.number() + ")", details, admissibility));
            state.lastReportNanos = tick.nanoTime();
            state.unexplainedSinceReport = 0;
            return;
        }
        if (tick.riding() && tick.horse()) {
            ServerboundPacket.MoveVehicle vehicle = tick.sentVehicle();
            details.put("sent", vehicle == null ? "nothing" : "MoveVehicle " + format(vehicle.position().x()) + "," + format(vehicle.position().y()) + ","
                    + format(vehicle.position().z()) + " rot " + vehicle.yaw() + "," + vehicle.pitch() + " onGround=" + vehicle.onGround());
            HorseState horse = tick.closestHorse();
            if (horse != null) {
                details.put("predicted", format(horse.x()) + "," + format(horse.y()) + "," + format(horse.z()) + " rot " + horse.yRot() + ","
                        + horse.xRot() + " onGround=" + horse.onGround());
                if (vehicle != null) {
                    details.put("offset", format(vehicle.position().x() - horse.x()) + "," + format(vehicle.position().y() - horse.y()) + ","
                            + format(vehicle.position().z() - horse.z()));
                }
            }
            sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, tick.since(), tick.sequence(), tick.nanoTime(),
                    tick.nanoTime(), (tick.reason() == null ? "rode a horse" : tick.reason().contains("pig") ? "rode a pig"
                            : tick.reason().contains("strider") ? "rode a strider" : "rode a horse")
                    + " as no vanilla client could (tick " + tick.number() + ")", details, admissibility));
            state.lastReportNanos = tick.nanoTime();
            state.unexplainedSinceReport = 0;
            return;
        }
        if (tick.riding()) {
            ServerboundPacket.MoveVehicle vehicle = tick.sentVehicle();
            details.put("sent", vehicle == null ? "nothing" : "MoveVehicle " + format(vehicle.position().x()) + "," + format(vehicle.position().y()) + ","
                    + format(vehicle.position().z()) + " rot " + vehicle.yaw() + "," + vehicle.pitch() + " onGround=" + vehicle.onGround());
            BoatState boat = tick.closestBoat();
            if (boat != null) {
                details.put("predicted", format(boat.x()) + "," + format(boat.y()) + "," + format(boat.z()) + " rot " + boat.yRot() + ","
                        + boat.xRot() + " onGround=" + boat.onGround() + " status=" + boat.status());
                if (vehicle != null) {
                    details.put("offset", format(vehicle.position().x() - boat.x()) + "," + format(vehicle.position().y() - boat.y()) + ","
                            + format(vehicle.position().z() - boat.z()));
                }
            }
            sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, tick.since(), tick.sequence(), tick.nanoTime(),
                    tick.nanoTime(), "steered a boat as no vanilla client could (tick " + tick.number() + ")", details, admissibility));
            state.lastReportNanos = tick.nanoTime();
            state.unexplainedSinceReport = 0;
            return;
        }
        ServerboundPacket.MovePlayer sent = tick.sent();
        details.put("sent", sent == null ? "nothing" : describe(sent));
        PlayerState closest = tick.closest();
        if (closest != null) {
            details.put("predicted", format(closest.x()) + "," + format(closest.y()) + "," + format(closest.z()) + " onGround=" + closest.onGround()
                    + " horizontalCollision=" + closest.horizontalCollision() + " sprinting=" + closest.sprinting());
            if (sent != null && sent.hasPosition()) {
                double dx = sent.x() - closest.x();
                double dy = sent.y() - closest.y();
                double dz = sent.z() - closest.z();
                details.put("offset", format(dx) + "," + format(dy) + "," + format(dz));
            }
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, tick.since(), tick.sequence(), tick.nanoTime(), tick.nanoTime(),
                (MovementTracker.ACTED_WHILE_USING.equals(tick.reason()) || MovementTracker.ABILITIES_WITHOUT_FLIGHT.equals(tick.reason()) ? tick.reason()
                        : "moved on foot as no vanilla client could") + " (tick "
                        + tick.number() + ")", details, admissibility));
        state.lastReportNanos = tick.nanoTime();
        state.unexplainedSinceReport = 0;
    }

    private static String describe(ServerboundPacket.MovePlayer m) {
        StringBuilder b = new StringBuilder(m.shape().name());
        if (m.hasPosition()) {
            b.append(' ').append(format(m.x())).append(',').append(format(m.y())).append(',').append(format(m.z()));
        }
        if (m.hasRotation()) {
            b.append(" rot ").append(m.yaw()).append(',').append(m.pitch());
        }
        return b.append(" onGround=").append(m.onGround()).append(" horizontalCollision=").append(m.horizontalCollision()).toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
