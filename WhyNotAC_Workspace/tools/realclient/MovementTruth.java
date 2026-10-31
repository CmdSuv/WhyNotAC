import dev.whynotac.analysis.movement.Hypothesis;
import dev.whynotac.analysis.movement.MovementTracker;
import dev.whynotac.analysis.movement.RotationReplies;
import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.BlockPos;
import dev.whynotac.physics.Boat;
import dev.whynotac.physics.BoatState;
import dev.whynotac.physics.Border;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.Horse;
import dev.whynotac.physics.HorseState;
import dev.whynotac.physics.ItemSteering;
import dev.whynotac.physics.OnFoot;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.replay.Replayer;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Replicas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The physics engine against the real client: for every tick the truth agent recorded (the client's own
 * state before and after {@code LocalPlayer.tick()}), {@code OnFoot.tick} is run from the state before,
 * with the keys the client polled, in the world WhyNotAC's replica holds from the server's recording.
 * Every tick the engine models must come out equal to the state after, bit for bit: position, velocity,
 * collision flags, fall distance, sprint, crouch, pose, the jump and sprint counters and the supporting
 * block. The client's world border at the move is the one the agent recorded; the ticks next to it
 * ({@code nearBorder}: the session walks into a static border and one that moves) must be more than none, and so must the
 * ticks in powder snow ({@code inPowderSnow}: with and without leather boots, which the agent records too). Ticks
 * outside its scope are counted by reason. While the player rides, its own tick is not compared ({@code riding}); the
 * boat it steers is ({@code boat}): {@code Boat.tick} from the boat's state before its tick, steered by the keys the
 * boat holds, must give its state after it, bit for bit (position, motion, rotation and turn, status, fall, collision
 * flags, supporting block), in each status it can be in and on landing on water from the air.
 *
 * <p>A riptide (the course's trident, riptide III, let go in a pool) is in the record before its tick already: the
 * tick from the record after the tick before it, with the engine's own release, must give the same state
 * ({@code riptides}, none mismatched); the spin attack's count is compared every tick, and its pose follows the
 * server's flag the agent records ({@code spinAttackPose}: more than none).
 *
 * <p>Each tick is evaluated in the world the replica holds when the client's movement packet of that tick (or of
 * the next tick that sent one) arrives in the replay: the k-th packet the client sent, rebuilt from its own
 * tick states as verify_truth.py does, is the k-th one the server recorded (teleport replies aside). Only what a
 * transaction proves the client had handled is read: at the tick that sent the packet, a block the client may not
 * hold yet is unknown unless every state it may hold moves the player alike (as the tracker reads it); at the
 * ticks before it, which ran before the transactions answered since the previous packet, so is any block, chunk
 * or view the server changed after the last transaction answered by then.
 *
 * <p>Then the movement check's tracker ({@code MovementTracker}) runs over the same recording as the server
 * would: at every tick whose movement packet it explained, the client's own state after the tick must be one of
 * its hypotheses as they stood when the client sent it ({@code truthMissing} counts those where it is not), no tick
 * may be unexplained, and the pushes of living entities it followed ({@code pushesFollowed}; the session walks into
 * villagers), the firework rocket boosts ({@code rocketBoosts}; it glides with one), the riptides' releases and the
 * spin attack's later ticks it explained ({@code riptidesExplained}, {@code spinAttackExplained}) must each be more
 * than none, and so must the spear lunges (the server's motion for the player, more than a block a tick on the level) one
 * of the five ticks after it explained with the client's own state ({@code lungesFollowed}), and the rotations the
 * server set ({@code rotationsFollowed}, likewise; the client's reply to each kept out of its ticks), none of them left
 * unanswered as the tracker sees it ({@code rotationsUnanswered}: a reply it would not take for the client's), and the
 * random walks' ticks explained with the client's own state ({@code walkTruthFound}: the walks run under the session's
 * first fire resistance, from its effect to its removal; later legs have their own).
 *
 * <p>usage: java -cp WhyNotAC.jar MovementTruth.java RECORDING TRUTH.jsonl; one JSON line, exit code 1 on any difference
 */
public class MovementTruth {

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> truth = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            if (!line.isBlank()) {
                truth.add(Json.object(line));
            }
        }
        Replicas replicas = Replicas.vanilla();
        Comparison comparison = new Comparison(truth);
        long[] blockChanges = {0};
        boolean[] played = {false};
        boolean[] afterAccept = {false};
        RotationReplies rotationReplies = new RotationReplies(); // the replies to the rotations the server set: not a tick's
        Handled handled = new Handled();
        List<TimelineListener> chain = new ArrayList<>();
        // First in the chain: the replicas hold the world as it was before this event.
        chain.add((session, event) -> {
            handled.onEvent(event);
            comparison.entities = replicas.entities().entities(session);
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.Login login) {
                comparison.selfId = login.entityId();
            }
            if (event instanceof TimelineEvent.Outbound out) {
                rotationReplies.onOutbound(out.sequence(), out.packet());
            }
            if (event instanceof TimelineEvent.Acknowledged ack) {
                rotationReplies.onAcknowledged(ack.transactionId());
            }
            if (event instanceof TimelineEvent.Inbound in) {
                boolean reply = afterAccept[0] && in.packet() instanceof ServerboundPacket.MovePlayer;
                afterAccept[0] = in.packet() instanceof ServerboundPacket.AcceptTeleportation;
                if (in.packet() instanceof ServerboundPacket.ClientTickEnd) {
                    rotationReplies.atTickEnd();
                } else if (rotationReplies.onInbound(in.packet())) {
                    reply = true;
                }
                if (in.packet() instanceof ServerboundPacket.MovePlayer && !reply) {
                    played[0] = true;
                    ClientWorld world = replicas.world().world(session);
                    comparison.packetArrived(view(world, handled, handled.previous), view(world, handled, Long.MAX_VALUE));
                    handled.previous = handled.answered;
                }
            }
            if (played[0] && event instanceof TimelineEvent.Outbound out
                    && (out.packet() instanceof ClientboundPacket.BlockUpdate || out.packet() instanceof ClientboundPacket.SectionBlocksUpdate)) {
                blockChanges[0]++;
            }
            if (event instanceof TimelineEvent.Disconnected) {
                ClientWorld world = replicas.world().world(session);
                comparison.packetArrived(view(world, handled, handled.previous), view(world, handled, handled.previous)); // the ticks after the last packet
            }
        });
        chain.addAll(replicas.listeners());
        // Last: the replica's boat at each mount, for the ticks that interpolate toward where the server put it.
        java.util.Set<Integer> mounting = new java.util.HashSet<>();
        chain.add((session, event) -> {
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.SetPassengers set
                    && set.passengerIds().length > 0) {
                mounting.add(set.vehicleId()); // every boat of the session carries the player
            }
            for (java.util.Iterator<Integer> it = mounting.iterator(); it.hasNext(); ) {
                int id = it.next();
                List<ClientEntities.HorseMounted> horse = replicas.entities().entities(session).horseMounted(id);
                if (horse != null) {
                    it.remove();
                    if (horse.size() == 1 && horse.getFirst().exact()) {
                        comparison.horseMounted.put(id, horse.getFirst());
                    }
                    continue;
                }
                List<ClientEntities.Mounted> mounted = replicas.entities().entities(session).mounted(id);
                if (mounted != null) {
                    it.remove();
                    if (mounted.size() == 1 && mounted.getFirst().exact()) {
                        comparison.mounted.put(id, mounted.getFirst());
                    }
                }
            }
        });
        Replayer.replay(RecordingReader.open(Path.of(args[0])), chain);
        Map<String, Object> result = comparison.result();
        result.put("blockChangesDuringPlay", blockChanges[0]);
        Map<String, Object> tracker = track(Path.of(args[0]), comparison.sentTicks(), truth);
        result.put("tracker", tracker);
        System.out.println(Json.write(result));
        boolean clean = Long.valueOf(0).equals(result.get("mismatched")) && ((Number) result.get("matched")).longValue() > 0
                && ((Number) result.get("gliding")).longValue() > 0 && ((Number) result.get("nearBorder")).longValue() > 0
                && ((Number) result.get("inPowderSnow")).longValue() > 0 && comparison.boatsClean() && comparison.horsesClean()
                && Long.valueOf(0).equals(tracker.get("unexplained")) && Long.valueOf(0).equals(tracker.get("truthMissing"))
                && ((Number) tracker.get("truthFound")).longValue() > 0 && ((Number) tracker.get("pushesFollowed")).longValue() > 0
                && ((Number) tracker.get("rocketBoosts")).longValue() > 0
                && ((Number) tracker.get("mounts")).longValue() > ((Number) tracker.get("mountsAmbiguous")).longValue()
                && Long.valueOf(0).equals(tracker.get("mountsDiffering"))
                && ((Number) tracker.get("rideExplained")).longValue() > 0 && Long.valueOf(0).equals(tracker.get("rideUnexplained"))
                && ((Number) tracker.get("ridingNothing")).longValue() > 0 && Long.valueOf(0).equals(tracker.get("vehicleOnFoot"))
                && Long.valueOf(0).equals(tracker.get("rideTruthMissing"))
                && ((Number) tracker.get("horseExplained")).longValue() > 0 && Long.valueOf(0).equals(tracker.get("horseUnexplained"))
                && Long.valueOf(0).equals(tracker.get("horseTruthMissing")) && ((Number) tracker.get("pigTruthCompared")).longValue() > 0
                && ((Number) tracker.get("striderTruthCompared")).longValue() > 0 && ((Number) tracker.get("ghastTruthCompared")).longValue() > 0
                && ((Number) tracker.get("nautilusTruthCompared")).longValue() > 0 && ((Number) tracker.get("camelTruthCompared")).longValue() > 0
                && Long.valueOf(0).equals(tracker.get("vehicleUncontrolled")) && ((Number) tracker.get("ridingUncontrolled")).longValue() > 0
                && ((Number) tracker.get("ridingUnsaddled")).longValue() > 0
                && ((Number) result.get("riptides")).longValue() > 0 && Long.valueOf(0).equals(result.get("riptidesMismatched"))
                && ((Number) result.get("spinAttackPose")).longValue() > 0 && ((Number) tracker.get("riptidesExplained")).longValue() > 0
                && ((Number) tracker.get("spinAttackExplained")).longValue() > 0
                && ((Number) tracker.get("lungesFollowed")).longValue() > 0
                && ((Number) tracker.get("rotationsFollowed")).longValue() > 0 && Long.valueOf(0).equals(tracker.get("rotationsUnanswered"))
                && ((Number) tracker.get("walkTruthFound")).longValue() > 0;
        System.exit(clean ? 0 : 1);
    }

    /**
     * The movement check's tracker over the recording: outcomes by reason, and at every explained tick that
     * sent a movement packet whether the client's own state after it (the k-th such tick of the truth) is a hypothesis.
     */
    /** The client ran on this machine: its Math.cos is this JVM's (null: none of the platforms the engine knows). */
    private static final PlatformCos CLIENT_COSINE = java.util.Objects.requireNonNull(PlatformCos.current(), "this JVM's Math.cos");

    private static Map<String, Object> track(Path recording, List<PlayerState> sentTicks, List<Map<String, Object>> truthRecords) throws Exception {
        Replicas replicas = Replicas.vanilla();
        // The replica's boat at each mount (ClientEntities.mounted) must be the client's at the start of the boat's first tick.
        Map<Integer, Map<String, Object>> firstBoatTick = new HashMap<>();
        for (Map<String, Object> r : truthRecords) {
            if ("boatPre".equals(r.get("hook"))) {
                firstBoatTick.putIfAbsent(((Number) r.get("id")).intValue(), r);
            }
        }
        java.util.Set<Integer> mounting = new java.util.LinkedHashSet<>();
        long[] mounts = new long[3]; // compared, differing, several states
        List<String> mountExamples = new ArrayList<>();
        // Each vehicle move the client sent is its boat's state after a tick (verify_truth): at an explained ride tick, that
        // state must be one of the ride's.
        List<Map<String, Object>> boatPosts = new ArrayList<>(); // every vehicle's, in order: a horse's too
        for (Map<String, Object> r : truthRecords) {
            if ("boatPost".equals(r.get("hook")) || "horsePost".equals(r.get("hook"))) {
                boatPosts.add(r);
            }
        }
        int[] boatIndex = {0};
        long[] rides = new long[2]; // explained ride ticks compared, the client's boat not a state
        // explained horse ticks compared, the client's horse not a state, the pig's among them, those boosting, the strider's,
        // the happy ghast's, the nautilus's, the camel's
        long[] horses = new long[8];
        MovementTracker tracker = new MovementTracker();
        Map<String, Long> outcomes = new TreeMap<>();
        Map<String, Long> reasons = new TreeMap<>();
        long[] counts = new long[4]; // explained ticks compared, truth found, truth missing, unexplained
        long[] spins = new long[2]; // of those found: a riptide's release, a spin attack's later tick
        // a spear's lunge: the server's motion for the player along its look (more than a block a tick on the level), and
        // whether one of the five ticks that sent a movement packet after it was explained with the client's own state
        long[] lunges = new long[2]; // seen, followed
        int[] lungeWindow = {0};
        long[] rotations = new long[2]; // set by the server, followed
        int[] rotationWindow = {0};
        long[] walks = new long[2]; // the random walks' explained sent ticks, their truth found
        Map<String, Long> walkOutcomes = new TreeMap<>(); // the walks' ticks, by outcome and reason
        boolean[] walking = {false, false}; // in the walks, the walks over
        int[] self = {-1};
        List<String> examples = new ArrayList<>();
        int[] sentIndex = {0};
        List<TimelineListener> chain = new ArrayList<>(replicas.listeners());
        chain.add((session, event) -> {
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.SetPassengers set
                    && set.passengerIds().length > 0) {
                mounting.add(set.vehicleId()); // every boat of the session carries the player
            }
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.Login login) {
                self[0] = login.entityId();
            }
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.SetEntityMotion m && m.entityId() == self[0]
                    && Math.hypot(m.clientMotion().x(), m.clientMotion().z()) > 1.0) {
                lunges[0]++;
                lungeWindow[0] = 5;
            }
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.MobEffect e && e.entityId() == self[0]
                    && e.effectKey().equals("minecraft:fire_resistance") && !walking[1]) {
                walking[0] = true;
            }
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.RemoveMobEffect e && e.entityId() == self[0]
                    && e.effectKey().equals("minecraft:fire_resistance") && walking[0]) {
                walking[0] = false;
                walking[1] = true;
            }
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.PlayerRotation) {
                rotations[0]++;
                rotationWindow[0] = 5;
            }
            MovementTracker.Tick tick = tracker.onEvent(event, replicas.world().world(session), replicas.entities().entities(session),
                    replicas.inventory().inventory(session));
            if (tick == null) {
                return;
            }
            for (java.util.Iterator<Integer> it = mounting.iterator(); it.hasNext(); ) {
                int id = it.next();
                List<ClientEntities.Mounted> mounted = replicas.entities().entities(session).mounted(id);
                if (mounted == null) {
                    continue;
                }
                it.remove();
                Map<String, Object> pre = firstBoatTick.get(id);
                mounts[0]++;
                String d = pre == null ? "no boat tick in the truth" : mountDifference(mounted, pre);
                if (mounted.size() != 1) {
                    mounts[2]++; // the tracker has no claim on this ride (the boat's state at the mount not known exactly)
                }
                if (d != null) {
                    mounts[1]++;
                    if (mountExamples.size() < 8) {
                        mountExamples.add("boat " + id + ": " + d);
                    }
                }
            }
            outcomes.merge(tick.outcome().name(), 1L, Long::sum);
            if (walking[0]) {
                walkOutcomes.merge(tick.outcome().name() + (tick.reason() == null ? "" : ": " + tick.reason().replaceAll(" at -?\\d+ -?\\d+ -?\\d+| at BlockPos\\[.*\\]|-?\\d+\\.\\d+", "")),
                        1L, Long::sum);
            }
            if (tick.sentVehicle() != null) {
                Map<String, Object> post = boatIndex[0] < boatPosts.size() ? boatPosts.get(boatIndex[0]) : null;
                boatIndex[0]++;
                if (tick.outcome() == MovementTracker.Outcome.EXPLAINED && post != null && "boatPost".equals(post.get("hook"))) {
                    rides[0]++;
                    BoatState truth = boatState(post);
                    boolean found = false;
                    List<String> nearest = null;
                    for (BoatState b : tracker.rideStates()) {
                        List<String> d = boatDifferences(b, truth);
                        found |= d.isEmpty();
                        if (nearest == null || d.size() < nearest.size()) {
                            nearest = d;
                        }
                    }
                    if (!found) {
                        rides[1]++;
                        if (examples.size() < 12) {
                            examples.add("ride tick " + tick.number() + ": the client's boat is no state of " + tracker.rideStates().size()
                                    + "; nearest differs in " + nearest);
                        }
                    }
                }
                if (tick.horse() && tick.outcome() == MovementTracker.Outcome.EXPLAINED && post != null && "horsePost".equals(post.get("hook"))) {
                    horses[0]++;
                    if (String.valueOf(post.get("type")).endsWith("pig")) {
                        horses[2]++;
                        if (Boolean.TRUE.equals(post.get("boosting"))) {
                            horses[3]++;
                        }
                    } else if (String.valueOf(post.get("type")).endsWith("strider")) {
                        horses[4]++;
                    } else if (String.valueOf(post.get("type")).endsWith("happy_ghast")) {
                        horses[5]++;
                    } else if (String.valueOf(post.get("type")).endsWith("nautilus")) {
                        horses[6]++;
                    } else if (String.valueOf(post.get("type")).contains("camel")) {
                        horses[7]++;
                    }
                    HorseState truth = horseState(post);
                    boolean found = false;
                    List<String> nearest = null;
                    for (HorseState h : tracker.horseStates()) {
                        List<String> d = horseDifferences(h, truth);
                        found |= d.isEmpty();
                        if (nearest == null || d.size() < nearest.size()) {
                            nearest = d;
                        }
                    }
                    if (!found) {
                        horses[1]++;
                        if (examples.size() < 12) {
                            examples.add("horse tick " + tick.number() + ": the client's horse is no state of " + tracker.horseStates().size()
                                    + "; nearest differs in " + nearest);
                        }
                    }
                }
            }
            if (tick.outcome() == MovementTracker.Outcome.UNEXPLAINED && tick.riding() && examples.size() < 12) {
                examples.add("unexplained ride tick " + tick.number() + ": " + tick.reason() + ", sent " + tick.sentVehicle() + ", e.g. predicted "
                        + (tick.horse() ? tick.closestHorse() : tick.closestBoat()));
            }
            if (tick.outcome() == MovementTracker.Outcome.NO_CLAIM && examples.size() < 40) {
                examples.add("no claim at tick " + tick.number() + ": " + tick.reason());
            }
            if (tick.reason() != null) {
                reasons.merge(tick.reason().replaceAll(" at -?\\d+ -?\\d+ -?\\d+| at BlockPos\\[.*\\]|-?\\d+\\.\\d+", ""), 1L, Long::sum);
            }
            if (tick.outcome() == MovementTracker.Outcome.UNEXPLAINED) {
                counts[3]++;
                if (examples.size() < 12) {
                    examples.add("unexplained tick " + tick.number() + ": sent " + tick.sent() + ", e.g. predicted " + tick.closest());
                }
            }
            if (tracker.lastTickMoved()) { // the tick's own packet: a teleport's or a rotation's reply is not
                PlayerState truth = sentIndex[0] < sentTicks.size() ? sentTicks.get(sentIndex[0]) : null;
                sentIndex[0]++;
                if (tick.outcome() == MovementTracker.Outcome.EXPLAINED && truth != null && !tick.riding()) { // a ride's: above
                    counts[0]++;
                    boolean found = false;
                    List<String> nearest = null;
                    for (Hypothesis h : tracker.sentHypotheses()) {
                        List<String> d = differences(h.player(), truth);
                        found |= d.isEmpty();
                        if (nearest == null || d.size() < nearest.size()) {
                            nearest = d;
                        }
                    }
                    counts[found ? 1 : 2]++;
                    if (walking[0]) {
                        walks[0]++;
                        walks[1] += found ? 1 : 0;
                    }
                    if (found && lungeWindow[0] > 0 && Math.hypot(truth.deltaMovement().x(), truth.deltaMovement().z()) > 0.5) {
                        lunges[1]++; // the client lunging, its state a hypothesis
                        lungeWindow[0] = 0;
                    }
                    if (found && rotationWindow[0] > 0) {
                        rotations[1]++; // the client's state after the rotation the server set a hypothesis
                        rotationWindow[0] = 0;
                    }
                    if (found && truth.spinAttackTicks() > 0) {
                        spins[truth.spinAttackTicks() == OnFoot.SPIN_ATTACK_TICKS - 1 ? 0 : 1]++; // the release's tick, or a spin's
                    }
                    if (!found && examples.size() < 12) {
                        examples.add("tick " + tick.number() + " (sent tick " + (sentIndex[0] - 1) + "): the client's state is no hypothesis of "
                                + tracker.sentHypotheses().size() + "; nearest differs in " + nearest);
                    }
                }
                lungeWindow[0] = Math.max(lungeWindow[0] - 1, 0);
                rotationWindow[0] = Math.max(rotationWindow[0] - 1, 0);
            }
        });
        Replayer.replay(RecordingReader.open(recording), chain);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcomes", outcomes);
        out.put("reasons", reasons);
        out.put("explainedSentTicks", counts[0]);
        out.put("truthFound", counts[1]);
        out.put("lunges", lunges[0]);
        out.put("lungesFollowed", lunges[1]);
        out.put("walkOutcomes", walkOutcomes);
        out.put("walkExplainedSentTicks", walks[0]);
        out.put("walkTruthFound", walks[1]);
        out.put("rotations", rotations[0]);
        out.put("rotationsFollowed", rotations[1]);
        out.put("rotationsUnanswered", reasons.getOrDefault("a rotation set by the server the client did not answer", 0L));
        out.put("riptidesExplained", spins[0]);
        out.put("spinAttackExplained", spins[1]);
        out.put("truthMissing", counts[2]);
        out.put("unexplained", counts[3]);
        out.put("pushesFollowed", tracker.pushesFollowed());
        out.put("rocketBoosts", tracker.rocketBoosts());
        out.put("rideExplained", tracker.rideExplained());
        out.put("rideUnexplained", tracker.rideUnexplained());
        out.put("vehicleOnFoot", tracker.vehicleOnFoot());
        out.put("vehicleUncontrolled", tracker.vehicleUncontrolled());
        out.put("ridingUncontrolled", tracker.ridingUncontrolled());
        out.put("ridingUnsaddled", tracker.ridingUnsaddled());
        out.put("ridingNothing", tracker.ridingNothing());
        out.put("rideNoClaim", tracker.rideNoClaim());
        out.put("rideTruthCompared", rides[0]);
        out.put("rideTruthMissing", rides[1]);
        out.put("horseExplained", tracker.horseExplained());
        out.put("horseUnexplained", tracker.horseUnexplained());
        out.put("horseNoClaim", tracker.horseNoClaim());
        out.put("horseTruthCompared", horses[0]);
        out.put("pigTruthCompared", horses[2]);
        out.put("pigBoostedTruthCompared", horses[3]);
        out.put("striderTruthCompared", horses[4]);
        out.put("ghastTruthCompared", horses[5]);
        out.put("nautilusTruthCompared", horses[6]);
        out.put("camelTruthCompared", horses[7]);
        out.put("horseTruthMissing", horses[1]);
        out.put("mounts", mounts[0]);
        out.put("mountsDiffering", mounts[1]);
        out.put("mountsAmbiguous", mounts[2]);
        out.put("mountExamples", mountExamples);
        out.put("examples", examples);
        return out;
    }

    /** Null if the replica's only state for the boat at the mount is exactly the client's before its first tick, else how not. */
    @SuppressWarnings("unchecked")
    /**
     * Null when the client's boat at its first tick is one of the replica's states at the mount, each of them exact.
     * Several states are where the client may or may not have processed a packet before the mount: the client's own
     * must then be among them.
     */
    private static String mountDifference(List<ClientEntities.Mounted> mounted, Map<String, Object> pre) {
        if (!mounted.stream().allMatch(ClientEntities.Mounted::exact)) {
            return mounted.size() + " states, exact " + mounted.stream().map(ClientEntities.Mounted::exact).toList();
        }
        List<String> each = new ArrayList<>();
        for (ClientEntities.Mounted m : mounted) {
            String d = stateDifference(m, pre);
            if (d == null) {
                return null;
            }
            each.add(d);
        }
        return mounted.size() + " states, none the client's: " + each;
    }

    private static String stateDifference(ClientEntities.Mounted m, Map<String, Object> pre) {
        List<Object> pos = (List<Object>) pre.get("pos");
        List<String> d = new ArrayList<>();
        if (Double.compare(m.position().x(), f64(pos.get(0))) != 0 || Double.compare(m.position().y(), f64(pos.get(1))) != 0
                || Double.compare(m.position().z(), f64(pos.get(2))) != 0) {
            d.add("position " + m.position() + " vs client " + f64(pos.get(0)) + " " + f64(pos.get(1)) + " " + f64(pos.get(2)));
        }
        if (Float.compare(m.yRot(), f32(pre.get("yRot"))) != 0 || Float.compare(m.xRot(), f32(pre.get("xRot"))) != 0) {
            d.add("rotation " + m.yRot() + " " + m.xRot() + " vs client " + f32(pre.get("yRot")) + " " + f32(pre.get("xRot")));
        }
        if (m.steps() > 0 != Boolean.TRUE.equals(pre.get("interpolating"))) {
            d.add("interpolating " + (m.steps() > 0) + " vs client " + pre.get("interpolating"));
        }
        if ((m.onGround() == 1) != Boolean.TRUE.equals(pre.get("onGround")) || m.onGround() < 0) {
            d.add("onGround " + m.onGround() + " vs client " + pre.get("onGround"));
        }
        if (m.noGravity() != Boolean.TRUE.equals(pre.get("noGravity"))) {
            d.add("noGravity " + m.noGravity() + " vs client " + pre.get("noGravity"));
        }
        if (m.everTicked() == Boolean.TRUE.equals(pre.get("firstTick"))) {
            d.add("ticked " + m.everTicked() + " vs client firstTick " + pre.get("firstTick"));
        }
        return d.isEmpty() ? null : String.join("; ", d);
    }

    /**
     * The replica's world as the client certainly held it: a block that may still change reads as it is only where
     * every state it may hold moves the player alike, and a chunk in flight as not loaded (the step then has no claim).
     * Below {@code Long.MAX_VALUE}, {@code handledBefore} is the last transaction the client had answered before the
     * tick: what the server changed after it reads as unknown too.
     */
    private static Colliders.World view(ClientWorld world, Handled handled, long handledBefore) {
        return new Colliders.World() {
            @Override
            public BlockStates states() {
                return world.states();
            }

            @Override
            public int blockState(int x, int y, int z) {
                if (handledBefore != Long.MAX_VALUE && handled.changedAfter(x, y, z, handledBefore)) {
                    return ClientWorld.UNKNOWN;
                }
                if (world.settled(x, y, z)) {
                    return world.blockState(x, y, z);
                }
                int[] possible = world.possibleStates(x, y, z);
                return possible != null && OnFoot.sameForMovement(world.states(), possible) ? world.blockState(x, y, z) : ClientWorld.UNKNOWN;
            }

            @Override
            public boolean isLoaded(int chunkX, int chunkZ) {
                if (handledBefore != Long.MAX_VALUE && handled.chunkChangedAfter(chunkX, chunkZ, handledBefore)) {
                    return false;
                }
                return world.isLoaded(chunkX, chunkZ) && world.settled(chunkX << 4, world.minY(), chunkZ << 4);
            }

            @Override
            public Boolean fastLava() {
                return world.dimension() == null ? null : world.dimension().fastLava();
            }
        };
    }

    /**
     * When the server last changed each block and chunk, how it moved the view, and the newest transaction the client
     * answered (by the sequence the server sent it at): the client handled everything sent before that transaction.
     */
    private static final class Handled {
        private final Map<Long, Long> blocks = new HashMap<>();
        private final Map<Long, Long> chunks = new HashMap<>();
        private final Map<Integer, Long> pings = new HashMap<>();
        /** Each view change: its sequence, then the range's centre and radius before and after it ({@code ClientChunkCache.Storage}). */
        private final List<long[]> views = new ArrayList<>();
        private int centerX;
        private int centerZ;
        private int radius;
        /** False after a respawn, which keeps the old range or not (a new dimension's starts at 0, 0): its next move changes all. */
        private boolean centerKnown = true;
        /** The last change that made the whole world new (a login, a respawn, an unreadable world packet, lost events). */
        private long world = Long.MIN_VALUE;
        long answered = Long.MIN_VALUE;
        /** {@link #answered} when the previous movement packet arrived. */
        long previous = Long.MIN_VALUE;

        void onEvent(TimelineEvent event) {
            if (event instanceof TimelineEvent.Acknowledged ack) {
                Long sent = pings.remove(ack.transactionId());
                if (sent != null) {
                    answered = Math.max(answered, sent);
                }
            } else if (event instanceof TimelineEvent.Outbound out) {
                long seq = out.sequence();
                switch (out.packet()) {
                    case ClientboundPacket.Ping ping -> pings.put(ping.id(), seq);
                    case ClientboundPacket.BlockUpdate update -> blocks.put(key(update.position().x(), update.position().y(), update.position().z()), seq);
                    case ClientboundPacket.SectionBlocksUpdate update -> {
                        for (int i = 0; i < update.size(); i++) {
                            int local = (int) (update.entry(i) & 0xFFF); // x << 8 | z << 4 | y on the wire
                            blocks.put(key(update.sectionX() << 4 | local >>> 8 & 15, update.sectionY() << 4 | local & 15,
                                    update.sectionZ() << 4 | local >>> 4 & 15), seq);
                        }
                    }
                    case ClientboundPacket.LevelChunk chunk -> chunks.put(ClientWorld.chunkKey(chunk.x(), chunk.z()), seq);
                    case ClientboundPacket.ForgetLevelChunk forget -> chunks.put(ClientWorld.chunkKey(forget.x(), forget.z()), seq);
                    case ClientboundPacket.ChunkCacheCenter center -> moveView(seq, center.x(), center.z(), radius);
                    case ClientboundPacket.ChunkCacheRadius r -> moveView(seq, centerX, centerZ, Math.max(2, r.radius()) + 3);
                    case ClientboundPacket.Login login -> {
                        world = seq;
                        centerX = 0;
                        centerZ = 0;
                        radius = Math.max(2, login.chunkRadius()) + 3;
                        centerKnown = true;
                    }
                    case ClientboundPacket.Respawn ignored -> {
                        world = seq;
                        centerKnown = false;
                    }
                    case ClientboundPacket.Untranslatable u when u.type().contains("chunk") || u.type().contains("block") || u.type().equals("minecraft:login")
                            || u.type().equals("minecraft:respawn") -> world = seq;
                    default -> { }
                }
            } else if (event instanceof TimelineEvent.Gap) {
                world = Long.MAX_VALUE; // nothing is known for certain any more
            }
        }

        private void moveView(long seq, int x, int z, int r) {
            if (!centerKnown) {
                world = seq;
                centerKnown = true;
            }
            views.add(new long[] {seq, centerX, centerZ, radius, x, z, r});
            centerX = x;
            centerZ = z;
            radius = r;
        }

        /** The chunk changed, or a view change since moved it into or out of the client's range. */
        boolean chunkChangedAfter(int chunkX, int chunkZ, long handledBefore) {
            if (world >= handledBefore || chunks.getOrDefault(ClientWorld.chunkKey(chunkX, chunkZ), Long.MIN_VALUE) >= handledBefore) {
                return true;
            }
            for (int i = views.size() - 1; i >= 0 && views.get(i)[0] >= handledBefore; i--) {
                long[] v = views.get(i);
                boolean before = Math.abs(chunkX - v[1]) <= v[3] && Math.abs(chunkZ - v[2]) <= v[3];
                boolean after = Math.abs(chunkX - v[4]) <= v[6] && Math.abs(chunkZ - v[5]) <= v[6];
                if (before != after) {
                    return true;
                }
            }
            return false;
        }

        boolean changedAfter(int x, int y, int z, long handledBefore) {
            return chunkChangedAfter(x >> 4, z >> 4, handledBefore) || blocks.getOrDefault(key(x, y, z), Long.MIN_VALUE) >= handledBefore;
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y & 0xFFF;
        }
    }

    /** The recorded ticks in order, each compared when its movement packet (or the next one) arrives. */
    private static final class Comparison {
        private final List<Map<String, Object>[]> ticks = new ArrayList<>();
        private final List<Boolean> sent = new ArrayList<>();
        /** Per player tick: it rode a vehicle (its own state is not compared), and the boat ticks it steered that client tick. */
        private final List<Boolean> riding = new ArrayList<>();
        private final List<List<Map<String, Object>[]>> boats = new ArrayList<>();
        long ridingTicks;
        long boatTicks;
        long boatMatched;
        long boatMismatched;
        long boatLandings;
        long boatInterpolating;
        /** The replica's boat at its player's mount (ClientEntities.mounted), by id. */
        final Map<Integer, ClientEntities.Mounted> mounted = new HashMap<>();
        /** The interpolation each boat's last compared tick left (none: absent). */
        private final Map<Integer, BoatState.Interpolation> interpolations = new HashMap<>();
        /** Boats whose last tick was not run: an interpolation then is not followed. */
        private final java.util.Set<Integer> skipped = new java.util.HashSet<>();
        /** The client's entities as the replica holds them before the event being handled, and its player's id. */
        ClientEntities entities;
        int selfId = -1;
        private final java.util.Set<Integer> boatsSeen = new java.util.HashSet<>();
        final Map<String, Long> boatStatuses = new TreeMap<>();
        final Map<String, Long> boatUnknown = new TreeMap<>();
        final Map<String, Long> boatDifferingFields = new TreeMap<>();
        final List<String> boatExamples = new ArrayList<>();
        private int next;
        private boolean firstLoaded = true;
        long matched;
        long mismatched;
        long effectTicks;
        long useTicks;
        long glideTicks;
        long borderTicks;
        long powderSnowTicks;
        long frostTicks;
        long spinTicks;
        long spinPoseTicks;
        long riptides;
        long riptidesMatched;
        long riptidesMismatched;
        /** The last on-foot tick compared, its post record: a riptide's release is compared from it (null after a tick not). */
        private Map<String, Object> lastPost;
        final Map<String, Long> unknown = new TreeMap<>();
        final Map<String, Long> differingFields = new TreeMap<>();
        final List<String> examples = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Comparison(List<Map<String, Object>> truth) {
            List<Map<String, Object>[]> steered = new ArrayList<>(); // the boat's tick runs before its passenger's
            for (int i = 0; i + 1 < truth.size(); i++) {
                Map<String, Object> pre = truth.get(i);
                Map<String, Object> post = truth.get(i + 1);
                if ("boatPre".equals(pre.get("hook")) && "boatPost".equals(post.get("hook")) && pre.get("tickCount").equals(post.get("tickCount"))
                        && pre.get("id").equals(post.get("id"))) {
                    steered.add(new Map[] {pre, post});
                }
                if ("horsePre".equals(pre.get("hook")) && "horsePost".equals(post.get("hook")) && pre.get("tickCount").equals(post.get("tickCount"))
                        && pre.get("id").equals(post.get("id"))) {
                    steered.add(new Map[] {pre, post}); // a horse's tick runs before its rider's too
                }
                if ("pre".equals(pre.get("hook")) && "post".equals(post.get("hook")) && pre.get("tickCount").equals(post.get("tickCount"))
                        && (Boolean) pre.get("loaded")) {
                    ticks.add(new Map[] {pre, post});
                    boats.add(steered);
                    steered = new ArrayList<>();
                    // a passenger at the send sends its rotation every tick (and the vehicle's move), not sendPosition's packet
                    boolean passenger = (Boolean) post.get("passenger");
                    riding.add(passenger || (Boolean) pre.get("passenger"));
                    // sendPosition: a position (reminder reset), a rotation (last sent changed) or the flags changed
                    boolean position = ((Number) post.get("positionReminder")).longValue() == 0;
                    boolean rotation = !post.get("yRotLast").equals(pre.get("yRotLast")) || !post.get("xRotLast").equals(pre.get("xRotLast"));
                    boolean status = !pre.get("lastOnGround").equals(post.get("onGround"))
                            || !pre.get("lastHorizontalCollision").equals(post.get("horizontalCollision"));
                    sent.add(passenger || position || rotation || status);
                }
            }
        }

        /** Boat ticks were compared, all equal, in water, under it, on land, in the air, and landing on water from the air. */
        /** Every horse tick in scope matched, with a released jump and a tick in water among them. */
        boolean horsesClean() {
            return horseMatched > 0 && horseMismatched == 0 && horseJumps > 0 && horseInWater > 0 && pigTicks > 0 && pigBoosted > 0
                    && striderBoosted > 0 && striderSuffocating > 0 && striderOnLava > 0 && striderInLava > 0 && ghastRising > 0 && ghastPitched > 0
                    && nautilusDashed > 0 && nautilusDashedOnLand > 0 && nautilusInWater > 0 && nautilusOnLand > 0 && nautilusPitched > 0
                    && camelSprinting > 0 && camelDashed > 0 && camelCooling > 0 && camelDashingCleared > 0;
        }

        boolean boatsClean() {
            return boatMatched > 0 && boatMismatched == 0 && boatLandings > 0
                    && java.util.stream.Stream.of("IN_WATER", "UNDER_WATER", "ON_LAND", "IN_AIR").allMatch(st -> boatStatuses.getOrDefault(st, 0L) > 0);
        }

        /** The client's own state after each tick that sent a movement packet, in order. */
        List<PlayerState> sentTicks() {
            List<PlayerState> out = new ArrayList<>();
            for (int i = 0; i < ticks.size(); i++) {
                if (sent.get(i)) {
                    float speed = f32(ticks.get(i)[1].get("speed"));
                    RecordedSpeed s = RecordedSpeed.of(speed, (Boolean) ticks.get(i)[1].get("sprinting"));
                    out.add(state(ticks.get(i)[1], s != null ? s.sprintModifier() : speed == (float) OnFoot.Speed.VANILLA.value(true)));
                }
            }
            return out;
        }

        /**
         * A movement packet arrived: the ticks up to the one that sent it are compared, those before it in
         * {@code before} and that one in {@code sending}.
         */
        void packetArrived(Colliders.World before, Colliders.World sending) {
            while (next < ticks.size()) {
                Map<String, Object>[] tick = ticks.get(next);
                boolean sentHere = sent.get(next);
                next++;
                Colliders.World world = sentHere ? sending : before;
                for (Map<String, Object>[] boat : boats.get(next - 1)) {
                    if ("horsePre".equals(boat[0].get("hook"))) {
                        compareHorse(boat[0], boat[1], tick[0], world);
                    } else {
                        compareBoat(boat[0], boat[1], tick[0], world);
                    }
                }
                if (riding.get(next - 1)) {
                    ridingTicks++;
                    lastPost = null;
                } else if (nearLastBoat(tick[0])) {
                    unknown.merge("near the boat it just left, which the client still holds (a solid entity)", 1L, Long::sum);
                    lastPost = null;
                } else {
                    compare(tick[0], tick[1], world);
                }
                if (sentHere) {
                    return;
                }
            }
        }

        private void compare(Map<String, Object> pre, Map<String, Object> post, Colliders.World world) {
            Map<String, Object> previous = lastPost;
            lastPost = post;
            // LocalPlayer.autoJumpEnabled: true until a new player's first sendPosition, then the option (older truth files: off)
            boolean autoJump = firstLoaded || Boolean.TRUE.equals(pre.get("autoJumpEnabled"));
            firstLoaded = false;
            RecordedSpeed speedBefore = RecordedSpeed.of(f32(pre.get("speed")), (Boolean) pre.get("sprinting"));
            RecordedSpeed speedAfter = RecordedSpeed.of(f32(post.get("speed")), (Boolean) post.get("sprinting"));
            if (speedBefore == null || speedAfter == null) {
                unknown.merge("a speed attribute other than the base's, frost's and sprinting's", 1L, Long::sum);
                return;
            }
            PlayerState before = state(pre, speedBefore.sprintModifier());
            PlayerState expected = state(post, speedAfter.sprintModifier());
            if (speedAfter.frost() != 0.0) {
                frostTicks++;
            }
            OnFoot.Effects effects = effects(pre);
            if (effects == null) {
                unknown.merge("an effect not recorded by name", 1L, Long::sum);
                return;
            }
            if (!effects.equals(OnFoot.Effects.NONE)) {
                effectTicks++;
            }
            try {
                OnFoot.ItemUse use = use(pre);
                if (use != null) {
                    useTicks++;
                }
                if (before.fallFlying() || expected.fallFlying()) {
                    glideTicks++;
                }
                // an equipped item that can glide (older truth files: none)
                boolean glider = Boolean.TRUE.equals(pre.get("glider"));
                Border border = border(pre);
                if (border != null && border.distance(before.x(), before.z()) < 2.0) {
                    borderTicks++; // close enough for its shape to collide (isInsideCloseToBorder)
                }
                if (before.inPowderSnow() || expected.inPowderSnow()) {
                    powderSnowTicks++;
                }
                Boolean boots = (Boolean) pre.get("leatherBoots"); // older truth files: not recorded, not known
                // the server's spin attack flag gives the pose (older truth files: not recorded, none)
                boolean spinFlag = Boolean.TRUE.equals(pre.get("autoSpinAttack"));
                if (before.spinAttackTicks() > 0) {
                    spinTicks++;
                }
                if (spinFlag) {
                    spinPoseTicks++;
                }
                OnFoot.Conditions conditions = new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, autoJump, speedAfter, effects, use, glider,
                        CLIENT_COSINE, border, boots).withSpinAttack(spinFlag);
                PlayerState predicted = OnFoot.tick(before, keys((String) post.get("keys")), world, conditions);
                if (before.spinAttackTicks() == OnFoot.SPIN_ATTACK_TICKS && previous != null
                        && ((Number) previous.get("tickCount")).longValue() + 1 == ((Number) pre.get("tickCount")).longValue()) {
                    // TridentItem.releaseUsing in this tick's keybinds (the record before the tick holds its push and lift): the
                    // whole tick from the one before, the release the engine's own (the course's trident: riptide III)
                    riptides++;
                    RecordedSpeed speedPrevious = RecordedSpeed.of(f32(previous.get("speed")), (Boolean) previous.get("sprinting"));
                    PlayerState from = state(previous, speedPrevious != null && speedPrevious.sprintModifier());
                    from = new PlayerState(from.x(), from.y(), from.z(), from.deltaMovement(), before.yRot(), before.xRot(), from.onGround(),
                            from.horizontalCollision(), from.minorHorizontalCollision(), from.verticalCollision(), from.verticalCollisionBelow(),
                            from.fallDistance(), from.sprinting(), from.sprintModifier(), from.crouching(), from.pose(), from.keys(), from.noJumpDelay(),
                            from.sprintTriggerTime(), from.autoJumpTime(), from.support(), from.onGroundNoBlocks(), from.stuckSpeedMultiplier(),
                            from.swimming(), from.eyeInWater(), from.fallFlying(), from.inPowderSnow(), from.inWater(), from.spinAttackTicks());
                    List<String> d = differences(OnFoot.tick(from, keys((String) post.get("keys")), world, conditions.withRiptide(COURSE_RIPTIDE)),
                            expected);
                    if (d.isEmpty()) {
                        riptidesMatched++;
                    } else {
                        riptidesMismatched++;
                        if (examples.size() < 12) {
                            examples.add("riptide at tick " + pre.get("tickCount") + ": " + String.join("; ", d));
                        }
                    }
                }
                List<String> differences = differences(predicted, expected);
                if (differences.isEmpty()) {
                    matched++;
                } else {
                    mismatched++;
                    for (String d : differences) {
                        differingFields.merge(d.substring(0, d.indexOf(':')), 1L, Long::sum);
                    }
                    if (examples.size() < 12) {
                        examples.add("tick " + pre.get("tickCount") + " keys " + post.get("keys") + ": " + String.join("; ", differences));
                    }
                }
            } catch (Colliders.Unknown e) {
                unknown.merge(e.getMessage().replaceAll(" at -?\\d+ -?\\d+ -?\\d+| at BlockPos\\[.*\\]", ""), 1L, Long::sum);
            }
        }

        /** The box of the last boat ticked, after its tick: the client holds it for some ticks after its passenger got off. */
        private Box lastBoat;

        /**
         * The player's feet are on the top of the last boat ticked (inside its footprint, within a step of its top): the
         * client's player collides with that entity, a solid one the on-foot engine does not model.
         */
        @SuppressWarnings("unchecked")
        /**
         * The boat the player just left may still be in the client's world (its removal not processed yet): the engine
         * collides with blocks only, so a tick whose player stands on it, walks into it or auto-jumps at it (the search
         * reaches about a block and a half ahead, MovementTracker's ENTITY_MARGIN) is outside its scope.
         */
        private boolean nearLastBoat(Map<String, Object> pre) {
            if (lastBoat == null) {
                return false;
            }
            List<Object> pos = (List<Object>) pre.get("pos");
            double x = f64(pos.get(0));
            double y = f64(pos.get(1));
            double z = f64(pos.get(2));
            double reach = 0.3 + 1.5; // the half width and MovementTracker.ENTITY_MARGIN
            return x + reach > lastBoat.minX() && x - reach < lastBoat.maxX() && z + reach > lastBoat.minZ() && z - reach < lastBoat.maxZ()
                    && y > lastBoat.minY() - 1.8 && y < lastBoat.maxY() + 0.5;
        }

        /** The boat's tick from {@code pre} to {@code post}, in the client tick whose player record starts at {@code player}. */
        private void compareBoat(Map<String, Object> pre, Map<String, Object> post, Map<String, Object> player, Colliders.World world) {
            BoatState last = boatState(post);
            lastBoat = BoatState.box(last.x(), last.y(), last.z());
            int id = ((Number) pre.get("id")).intValue();
            boolean first = boatsSeen.add(id);
            BoatState.Interpolation interpolation = interpolations.remove(id);
            if (first) {
                ClientEntities.Mounted m = mounted.get(id);
                interpolation = m == null || m.steps() == 0 ? null : new BoatState.Interpolation(m.steps(), m.target(), m.targetYRot(),
                        m.targetXRot(), m.previousPosition(), m.previousYRot(), m.previousXRot());
                if (m == null && Boolean.TRUE.equals(pre.get("interpolating"))) {
                    boatUnknown.merge("interpolating toward a position the server sent, the replica's state at the mount not known", 1L, Long::sum);
                    skipped.add(id);
                    return;
                }
            }
            if (skipped.contains(id) && Boolean.TRUE.equals(pre.get("interpolating"))) {
                boatUnknown.merge("interpolating after a tick that was not compared", 1L, Long::sum);
                return;
            }
            skipped.remove(id);
            if (Boolean.TRUE.equals(pre.get("interpolating")) != (interpolation != null)) {
                throw new IllegalStateException("the boat's interpolation at tick " + pre.get("tickCount") + " is not the client's: "
                        + pre.get("interpolating") + " vs " + interpolation);
            }
            if (Boolean.TRUE.equals(pre.get("firstTick"))) {
                boatUnknown.merge("the boat's first tick", 1L, Long::sum);
                skipped.add(id);
                return;
            }
            BoatState t = boatState(pre);
            BoatState before = new BoatState(t.x(), t.y(), t.z(), t.deltaMovement(), t.yRot(), t.xRot(), t.deltaRotation(), t.status(), t.lastYd(),
                    t.fallDistance(), t.onGround(), t.horizontalCollision(), t.verticalCollision(), t.verticalCollisionBelow(), t.support(),
                    t.onGroundNoBlocks(), t.stuckSpeedMultiplier(), interpolation);
            if (entities != null && mayMeetEntity(before, id)) {
                boatUnknown.merge("an entity the boat may meet is near (canVehicleCollide)", 1L, Long::sum);
                skipped.add(id);
                return;
            }
            if (interpolation != null) {
                boatInterpolating++;
            }
            BoatState expected = boatState(post);
            if (!java.util.Objects.equals(post.get("oldStatus"), pre.get("status"))) {
                throw new IllegalStateException("the boat's oldStatus is not its last status at tick " + pre.get("tickCount"));
            }
            boatTicks++;
            boatStatuses.merge(String.valueOf(expected.status()), 1L, Long::sum);
            if (before.status() == BoatState.Status.IN_AIR && expected.status() == BoatState.Status.IN_WATER) {
                boatLandings++;
            }
            String k = (String) pre.get("input");
            Boat.Input input = new Boat.Input(k.charAt(0) == 'L', k.charAt(1) == 'R', k.charAt(2) == 'U', k.charAt(3) == 'D');
            try {
                BoatState predicted = Boat.tick(before, input, world, new Boat.Conditions((Boolean) pre.get("noGravity"), border(player), List.of()));
                if (predicted.interpolation() != null) {
                    interpolations.put(id, predicted.interpolation());
                }
                List<String> d = boatDifferences(predicted, expected);
                if (d.isEmpty()) {
                    boatMatched++;
                } else {
                    boatMismatched++;
                    for (String f : d) {
                        boatDifferingFields.merge(f.substring(0, f.indexOf(':')), 1L, Long::sum);
                    }
                    if (boatExamples.size() < 12) {
                        boatExamples.add("boat tick " + pre.get("tickCount") + " input " + k + " " + before.status() + "->" + expected.status() + ": "
                                + String.join("; ", d));
                    }
                }
            } catch (Colliders.Unknown e) {
                boatUnknown.merge(e.getMessage().replaceAll(" at -?\\d+ -?\\d+ -?\\d+| at BlockPos\\[.*\\]", ""), 1L, Long::sum);
                skipped.add(id);
            }
        }

        long horseTicks;
        /** The pig's ticks among the horse's ({@code Pig}, recorded in the horse's form), and those boosting. */
        long pigTicks;
        long pigBoosted;
        /** The strider's ({@code Strider}): boosting, suffocating, standing on lava, and in it otherwise (floated up). */
        long striderTicks;
        long striderBoosted;
        long striderSuffocating;
        long striderOnLava;
        long striderInLava;
        /** The happy ghast's ({@code HappyGhast}): rising on its rider's jump key, and steered along a pitch. */
        long ghastTicks;
        long ghastRising;
        long ghastPitched;
        /** The nautilus's ({@code AbstractNautilus}): dashing (in water and on land), in water, on land, steered along a pitch. */
        long nautilusTicks;
        long nautilusDashed;
        long nautilusDashedOnLand;
        long nautilusInWater;
        long nautilusOnLand;
        long nautilusPitched;
        /** The camel's ({@code Camel}): its rider's sprint taken, a dash, sprinting while its dash cools down, its dashing flag cleared. */
        long camelTicks;
        long camelSprinting;
        long camelDashed;
        long camelCooling;
        long camelDashingCleared;
        long horseMatched;
        long horseMismatched;
        long horseJumps;
        long horseInWater;
        final Map<String, Long> horseUnknown = new TreeMap<>();
        final Map<String, Long> horseDifferingFields = new TreeMap<>();
        final List<String> horseExamples = new ArrayList<>();
        private final java.util.Set<Integer> horsesSkipped = new java.util.HashSet<>();
        /** The replica's horse at each mount, the interpolation each horse's last compared tick left, and the horses seen. */
        final Map<Integer, ClientEntities.HorseMounted> horseMounted = new HashMap<>();
        private final Map<Integer, BoatState.Interpolation> horseInterpolations = new HashMap<>();
        private final java.util.Set<Integer> horsesSeen = new java.util.HashSet<>();
        long horseInterpolating;

        /**
         * The horse's tick from {@code pre} to {@code post} ({@code Horse.tick}), under its rider as {@code pre} records it,
         * with the attributes and effects the client held for it then.
         */
        @SuppressWarnings("unchecked")
        private void compareHorse(Map<String, Object> pre, Map<String, Object> post, Map<String, Object> player, Colliders.World world) {
            int id = ((Number) pre.get("id")).intValue();
            boolean interpolating = Boolean.TRUE.equals(pre.get("interpolating"));
            // the interpolation it runs toward where the server put it: the replica's at the mount, then what each tick leaves
            boolean first = horsesSeen.add(id);
            BoatState.Interpolation interpolation = horseInterpolations.remove(id);
            if (first) {
                ClientEntities.HorseMounted m = horseMounted.get(id);
                interpolation = m == null || m.steps() == 0 ? null : new BoatState.Interpolation(m.steps(), m.target(), m.targetYRot(),
                        m.targetXRot(), m.previousPosition(), m.previousYRot(), m.previousXRot());
                if (m == null && interpolating) {
                    horseUnknown.merge("interpolating toward a position the server sent, the replica's state at the mount not known", 1L, Long::sum);
                    horsesSkipped.add(id);
                    return;
                }
            }
            if (horsesSkipped.contains(id) && interpolating) {
                horseUnknown.merge("interpolating after a tick that was not compared", 1L, Long::sum);
                return;
            }
            horsesSkipped.remove(id);
            if (interpolating != (interpolation != null)) {
                throw new IllegalStateException("the horse's interpolation at tick " + pre.get("tickCount") + " is not the client's: " + interpolating
                        + " vs " + interpolation);
            }
            if (Boolean.TRUE.equals(pre.get("firstTick"))) {
                horseUnknown.merge("the horse's first tick", 1L, Long::sum);
                horsesSkipped.add(id);
                return;
            }
            HorseState t = horseState(pre);
            HorseState before = new HorseState(t.x(), t.y(), t.z(), t.deltaMovement(), t.yRot(), t.xRot(), t.onGround(), t.horizontalCollision(),
                    t.verticalCollision(), t.verticalCollisionBelow(), t.fallDistance(), t.support(), t.onGroundNoBlocks(), t.stuckSpeedMultiplier(),
                    t.inPowderSnow(), t.noJumpDelay(), t.jumpPendingScale(), t.allowStandSliding(), t.standing(), t.standCounter(), interpolation,
                    t.lavaHeight(), t.suffocating(), t.dashCooldown(), t.dashing());
            double reach = interpolation == null ? 0.0 : Math.abs(interpolation.target().x() - t.x()) + Math.abs(interpolation.target().y() - t.y())
                    + Math.abs(interpolation.target().z() - t.z());
            if (entities != null && entities.mayHaveVehicleColliderNear(selfId, id, before.x() - 3.0 - reach, before.y() - 3.0 - reach,
                    before.z() - 3.0 - reach, before.x() + 3.0 + reach, before.y() + 4.6 + reach, before.z() + 3.0 + reach, 64.0)) {
                horseUnknown.merge("an entity the horse may meet is near", 1L, Long::sum);
                horsesSkipped.add(id);
                return;
            }
            OnFoot.Effects e = effects(pre);
            if (e == null) {
                horseUnknown.merge("an effect not recorded by name", 1L, Long::sum);
                horsesSkipped.add(id);
                return;
            }
            String type = String.valueOf(pre.get("type")).replace("entity.minecraft.", "minecraft:");
            // a pig (Pig): 0.9 each way, its ridden speed the attribute times 0.225 and the factor of the boost it counts first
            // (tickRidden: tickBoost, then getRiddenSpeed), ahead whatever its rider's keys; a strider (Strider) likewise, 0.9 by
            // 1.7, at the attribute times 0.55, or 0.35 suffocating, and the factor: the attribute as its tick left it (post: its
            // suffocating modifier as the tick set it), its suffocation the engine's own
            boolean strider = type.equals("minecraft:strider");
            boolean pig = type.equals("minecraft:pig") || strider;
            // a happy ghast (HappyGhast): 4 each way, flying where its rider steers and looks, up while the rider jumps
            boolean ghast = type.equals("minecraft:happy_ghast");
            // a nautilus (AbstractNautilus): 0.875 by 0.95, swimming where its rider steers and looks, dashing at its jump
            boolean nautilus = type.equals("minecraft:nautilus") || type.equals("minecraft:zombie_nautilus");
            // a camel (Camel): 1.7 by 2.375 standing, ridden as a horse, faster while its rider sprints, dashing at its jump
            boolean camel = type.equals("minecraft:camel") || type.equals("minecraft:camel_husk");
            float height = ghast ? 4.0F : nautilus ? 0.95F : camel ? 2.375F : strider ? 1.7F : pig ? 0.9F : type.equals("minecraft:donkey") ? 1.5F : 1.6F;
            float speed = (float) f64(pre.get("movementSpeed"));
            ItemSteering steered = null;
            Horse.Strider striding = null;
            if (pig) {
                int total = ((Number) pre.get("boostTimeTotal")).intValue();
                steered = new ItemSteering(Boolean.TRUE.equals(pre.get("boosting")), ((Number) pre.get("boostTime")).intValue()).tick(total);
                if (strider) {
                    double attribute = f64(post.get("movementSpeed"));
                    float factor = steered.factor(total);
                    striding = new Horse.Strider((float) (attribute * 0.55F * factor), (float) (attribute * 0.35F * factor),
                            Boolean.TRUE.equals(pre.get("noAi")) ? null : java.util.Set.of("minecraft:lava"));
                    speed = (float) (attribute * (Boolean.TRUE.equals(post.get("suffocating")) ? 0.35F : 0.55F) * factor);
                } else {
                    speed = (float) (f64(pre.get("movementSpeed")) * 0.225 * steered.factor(total));
                }
            }
            Horse.Conditions conditions = new Horse.Conditions(speed, f64(pre.get("jumpStrength")), f32(pre.get("maxUpStep")),
                    f64(pre.get("gravity")), new Horse.Effects(e.jumpBoost(), e.slowFalling(), e.levitation(), e.dolphinsGrace()),
                    type.equals("minecraft:skeleton_horse") ? 0.96F : 0.8F, Horse.FLOATS_WHILE_RIDDEN.contains(type),
                    ghast ? 4.0F : nautilus ? 0.875F : camel ? 1.7F : pig ? 0.9F : 1.3964844F, height, border(player), List.of(), striding,
                    ghast ? Horse.Flying.of(f64(pre.get("flyingSpeed"))) : null, nautilus ? new Horse.Nautilus(f64(pre.get("movementSpeed"))) : null,
                    camel ? new Horse.Camel(f64(pre.get("movementSpeed")), Boolean.TRUE.equals(pre.get("riderSprinting"))) : null);
            if (e.slowFalling()) {
                horseUnknown.merge("slow falling (the recorded gravity is the effective one)", 1L, Long::sum);
                horsesSkipped.add(id);
                return;
            }
            if (interpolation != null) {
                horseInterpolating++;
            }
            Horse.Rider rider = pig ? new Horse.Rider(0.0F, 1.0F, f32(pre.get("riderYRot")), f32(pre.get("riderXRot")))
                    : new Horse.Rider(f32(pre.get("riderXxa")), f32(pre.get("riderZza")), f32(pre.get("riderYRot")), f32(pre.get("riderXRot")),
                            ghast && Boolean.TRUE.equals(pre.get("riderJumping")));
            if (ghast) {
                ghastTicks++;
                if (Boolean.TRUE.equals(pre.get("riderJumping"))) {
                    ghastRising++;
                }
                if (f32(pre.get("riderXRot")) != 0.0F && f32(pre.get("riderZza")) != 0.0F) {
                    ghastPitched++;
                }
            }
            // a camel's speed (getRiddenSpeed, after tickRidden): 0.1 more while its rider sprints and its dash has cooled down
            boolean dashes = camel && before.jumpPendingScale() > 0.0F && before.onGround();
            boolean bonus = camel && Boolean.TRUE.equals(pre.get("riderSprinting")) && !dashes && before.dashCooldown() == 0;
            if (camel) {
                camelTicks++;
                if (bonus) {
                    camelSprinting++;
                }
                if (dashes) {
                    camelDashed++;
                }
                if (Boolean.TRUE.equals(pre.get("riderSprinting")) && before.dashCooldown() > 0) {
                    camelCooling++;
                }
                if (before.dashing() && !Boolean.TRUE.equals(post.get("dashing"))) {
                    camelDashingCleared++;
                }
            }
            if (nautilus) {
                nautilusTicks++;
                boolean water = Boolean.TRUE.equals(post.get("wasTouchingWater"));
                if (before.jumpPendingScale() > 0.0F) {
                    nautilusDashed++;
                    if (!water) {
                        nautilusDashedOnLand++;
                    }
                }
                if (water) {
                    nautilusInWater++;
                } else {
                    nautilusOnLand++;
                }
                if (f32(pre.get("riderXRot")) != 0.0F && f32(pre.get("riderZza")) != 0.0F) {
                    nautilusPitched++;
                }
            }
            if (strider) {
                striderTicks++;
                if (steered.boosting()) {
                    striderBoosted++;
                }
                if (Boolean.TRUE.equals(post.get("suffocating"))) {
                    striderSuffocating++;
                }
                if (f64(post.get("lavaHeight")) > 0.0) {
                    if (f64(post.get("lavaHeight")) < 0.5 && Boolean.TRUE.equals(post.get("onGround"))) {
                        striderOnLava++;
                    } else {
                        striderInLava++;
                    }
                }
            } else if (pig) {
                pigTicks++;
                if (steered.boosting()) {
                    pigBoosted++;
                }
            }
            HorseState expected = horseState(post);
            horseTicks++;
            if (before.jumpPendingScale() > 0.0F && before.onGround()) {
                horseJumps++;
            }
            if (Boolean.TRUE.equals(pre.get("wasTouchingWater"))) {
                horseInWater++;
            }
            try {
                HorseState predicted = Horse.tick(before, rider, world, conditions);
                if (predicted.interpolation() != null) {
                    horseInterpolations.put(id, predicted.interpolation());
                }
                List<String> d = horseDifferences(predicted, expected);
                if (nautilus && pre.get("wasTouchingWater").equals(post.get("wasTouchingWater"))) {
                    // the speed it set (setSpeed(getRiddenSpeed): 0.0325 of the attribute in water, else 0.02), where the tick
                    // did not go into or out of the water (its baseTick's flag, which picks it, is recorded nowhere; a move into
                    // water sets it again, LivingEntity.checkFallDamage)
                    float attribute = (float) f64(pre.get("movementSpeed"));
                    bits(d, "speed", Boolean.TRUE.equals(post.get("wasTouchingWater")) ? 0.0325F * attribute : 0.02F * attribute, f32(post.get("speed")));
                }
                if (camel) {
                    bits(d, "speed", (float) f64(pre.get("movementSpeed")) + (bonus ? 0.1F : 0.0F), f32(post.get("speed")));
                }
                if (pig) { // the boost it counted and the speed it set (setSpeed(getRiddenSpeed))
                    same(d, "boosting", steered.boosting(), Boolean.TRUE.equals(post.get("boosting")));
                    same(d, "boostTime", steered.boostTime(), ((Number) post.get("boostTime")).intValue());
                    bits(d, "speed", speed, f32(post.get("speed")));
                }
                if (d.isEmpty()) {
                    horseMatched++;
                } else {
                    horseMismatched++;
                    for (String f : d) {
                        horseDifferingFields.merge(f.substring(0, f.indexOf(':')), 1L, Long::sum);
                    }
                    if (horseExamples.size() < 12) {
                        horseExamples.add("horse tick " + pre.get("tickCount") + " rider " + rider + ": " + String.join("; ", d));
                    }
                }
            } catch (Colliders.Unknown x) {
                horseUnknown.merge(x.getMessage().replaceAll(" at -?\\d+ -?\\d+ -?\\d+| at BlockPos\\[.*\\]", ""), 1L, Long::sum);
                horsesSkipped.add(id);
            }
        }

        /**
         * Whether an entity the boat collides with ({@code canVehicleCollide}, which {@link Boat} is not given here) may be
         * where its tick could take it: its box widened by how far the motion, an interpolation and a margin reach.
         */
        private boolean mayMeetEntity(BoatState before, int boat) {
            Box b = before.box();
            Vec3d d = before.deltaMovement();
            double reach = Math.abs(d.x()) + Math.abs(d.y()) + Math.abs(d.z()) + 1.5;
            if (before.interpolation() != null) {
                Vec3d t = before.interpolation().target();
                reach += Math.abs(t.x() - before.x()) + Math.abs(t.y() - before.y()) + Math.abs(t.z() - before.z());
            }
            return entities.mayHaveVehicleColliderNear(selfId, boat, b.minX() - reach, b.minY() - reach, b.minZ() - reach, b.maxX() + reach,
                    b.maxY() + reach, b.maxZ() + reach, 64.0);
        }

        Map<String, Object> result() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ticks", (long) ticks.size());
            out.put("compared", (long) next);
            out.put("matched", matched);
            out.put("mismatched", mismatched);
            out.put("withEffects", effectTicks);
            out.put("usingItem", useTicks);
            out.put("gliding", glideTicks);
            out.put("nearBorder", borderTicks);
            out.put("inPowderSnow", powderSnowTicks);
            out.put("spinAttack", spinTicks);
            out.put("spinAttackPose", spinPoseTicks);
            out.put("riptides", riptides);
            out.put("riptidesMatched", riptidesMatched);
            out.put("riptidesMismatched", riptidesMismatched);
            out.put("frozen", frostTicks);
            out.put("unknown", unknown);
            out.put("differingFields", differingFields);
            out.put("examples", examples);
            out.put("riding", ridingTicks);
            Map<String, Object> horse = new LinkedHashMap<>();
            horse.put("ticks", horseTicks);
            horse.put("matched", horseMatched);
            horse.put("mismatched", horseMismatched);
            horse.put("jumps", horseJumps);
            horse.put("inWater", horseInWater);
            horse.put("interpolating", horseInterpolating);
            horse.put("pigTicks", pigTicks);
            horse.put("pigBoosted", pigBoosted);
            horse.put("striderTicks", striderTicks);
            horse.put("striderBoosted", striderBoosted);
            horse.put("striderSuffocating", striderSuffocating);
            horse.put("striderOnLava", striderOnLava);
            horse.put("striderInLava", striderInLava);
            horse.put("ghastTicks", ghastTicks);
            horse.put("ghastRising", ghastRising);
            horse.put("ghastPitched", ghastPitched);
            horse.put("nautilusTicks", nautilusTicks);
            horse.put("nautilusDashed", nautilusDashed);
            horse.put("nautilusDashedOnLand", nautilusDashedOnLand);
            horse.put("nautilusInWater", nautilusInWater);
            horse.put("nautilusOnLand", nautilusOnLand);
            horse.put("nautilusPitched", nautilusPitched);
            horse.put("camelTicks", camelTicks);
            horse.put("camelSprinting", camelSprinting);
            horse.put("camelDashed", camelDashed);
            horse.put("camelCooling", camelCooling);
            horse.put("camelDashingCleared", camelDashingCleared);
            horse.put("unknown", horseUnknown);
            horse.put("differingFields", horseDifferingFields);
            horse.put("examples", horseExamples);
            out.put("horse", horse);
            Map<String, Object> boat = new LinkedHashMap<>();
            boat.put("ticks", boatTicks);
            boat.put("matched", boatMatched);
            boat.put("mismatched", boatMismatched);
            boat.put("statuses", boatStatuses);
            boat.put("landings", boatLandings);
            boat.put("interpolating", boatInterpolating);
            boat.put("unknown", boatUnknown);
            boat.put("differingFields", boatDifferingFields);
            boat.put("examples", boatExamples);
            out.put("boat", boat);
            return out;
        }
    }

    private static List<String> boatDifferences(BoatState p, BoatState e) {
        List<String> d = new ArrayList<>();
        bits(d, "x", p.x(), e.x());
        bits(d, "y", p.y(), e.y());
        bits(d, "z", p.z(), e.z());
        bits(d, "dx", p.deltaMovement().x(), e.deltaMovement().x());
        bits(d, "dy", p.deltaMovement().y(), e.deltaMovement().y());
        bits(d, "dz", p.deltaMovement().z(), e.deltaMovement().z());
        bits(d, "yRot", p.yRot(), e.yRot());
        bits(d, "xRot", p.xRot(), e.xRot());
        bits(d, "deltaRotation", p.deltaRotation(), e.deltaRotation());
        same(d, "status", p.status(), e.status());
        bits(d, "lastYd", p.lastYd(), e.lastYd());
        bits(d, "fallDistance", p.fallDistance(), e.fallDistance());
        same(d, "onGround", p.onGround(), e.onGround());
        same(d, "horizontalCollision", p.horizontalCollision(), e.horizontalCollision());
        same(d, "verticalCollision", p.verticalCollision(), e.verticalCollision());
        same(d, "verticalCollisionBelow", p.verticalCollisionBelow(), e.verticalCollisionBelow());
        same(d, "support", p.support(), e.support());
        same(d, "onGroundNoBlocks", p.onGroundNoBlocks(), e.onGroundNoBlocks());
        bits(d, "stuckX", p.stuckSpeedMultiplier().x(), e.stuckSpeedMultiplier().x());
        bits(d, "stuckY", p.stuckSpeedMultiplier().y(), e.stuckSpeedMultiplier().y());
        bits(d, "stuckZ", p.stuckSpeedMultiplier().z(), e.stuckSpeedMultiplier().z());
        return d;
    }

    /** A boat record of the truth agent (status by its ordinal, -1: none). */
    @SuppressWarnings("unchecked")
    private static BoatState boatState(Map<String, Object> r) {
        List<Object> pos = (List<Object>) r.get("pos");
        List<Object> support = (List<Object>) r.get("support");
        int status = ((Number) r.get("status")).intValue();
        return new BoatState(f64(pos.get(0)), f64(pos.get(1)), f64(pos.get(2)), vec(r.get("deltaMovement")), f32(r.get("yRot")), f32(r.get("xRot")),
                f32(r.get("deltaRotation")), status < 0 ? null : BoatState.Status.values()[status], f64(r.get("lastYd")), f64(r.get("fallDistance")),
                (Boolean) r.get("onGround"), (Boolean) r.get("horizontalCollision"), (Boolean) r.get("verticalCollision"),
                (Boolean) r.get("verticalCollisionBelow"),
                support == null ? null : new BlockPos(((Number) support.get(0)).intValue(), ((Number) support.get(1)).intValue(),
                        ((Number) support.get(2)).intValue()),
                (Boolean) r.get("onGroundNoBlocks"), vec(r.get("stuckSpeedMultiplier")));
    }

    @SuppressWarnings("unchecked")
    private static HorseState horseState(Map<String, Object> r) {
        List<Object> pos = (List<Object>) r.get("pos");
        List<Object> support = (List<Object>) r.get("support");
        return new HorseState(f64(pos.get(0)), f64(pos.get(1)), f64(pos.get(2)), vec(r.get("deltaMovement")), f32(r.get("yRot")), f32(r.get("xRot")),
                (Boolean) r.get("onGround"), (Boolean) r.get("horizontalCollision"), (Boolean) r.get("verticalCollision"),
                (Boolean) r.get("verticalCollisionBelow"), f64(r.get("fallDistance")),
                support == null ? null : new BlockPos(((Number) support.get(0)).intValue(), ((Number) support.get(1)).intValue(),
                        ((Number) support.get(2)).intValue()),
                (Boolean) r.get("onGroundNoBlocks"), vec(r.get("stuckSpeedMultiplier")), (Boolean) r.get("inPowderSnow"),
                ((Number) r.get("noJumpDelay")).intValue(), f32(r.get("jumpPendingScale")), (Boolean) r.get("allowStandSliding"),
                (Boolean) r.get("standing"), ((Number) r.get("standCounter")).intValue(), null, f64(r.get("lavaHeight")),
                Boolean.TRUE.equals(r.get("suffocating")), r.get("dashCooldown") == null ? 0 : ((Number) r.get("dashCooldown")).intValue(),
                Boolean.TRUE.equals(r.get("dashing")) && String.valueOf(r.get("type")).contains("camel"));
    }

    private static List<String> horseDifferences(HorseState p, HorseState e) {
        List<String> d = new ArrayList<>();
        bits(d, "x", p.x(), e.x());
        bits(d, "y", p.y(), e.y());
        bits(d, "z", p.z(), e.z());
        bits(d, "dx", p.deltaMovement().x(), e.deltaMovement().x());
        bits(d, "dy", p.deltaMovement().y(), e.deltaMovement().y());
        bits(d, "dz", p.deltaMovement().z(), e.deltaMovement().z());
        bits(d, "yRot", p.yRot(), e.yRot());
        bits(d, "xRot", p.xRot(), e.xRot());
        bits(d, "fallDistance", p.fallDistance(), e.fallDistance());
        same(d, "onGround", p.onGround(), e.onGround());
        same(d, "horizontalCollision", p.horizontalCollision(), e.horizontalCollision());
        same(d, "verticalCollision", p.verticalCollision(), e.verticalCollision());
        same(d, "verticalCollisionBelow", p.verticalCollisionBelow(), e.verticalCollisionBelow());
        same(d, "support", p.support(), e.support());
        same(d, "onGroundNoBlocks", p.onGroundNoBlocks(), e.onGroundNoBlocks());
        bits(d, "stuckX", p.stuckSpeedMultiplier().x(), e.stuckSpeedMultiplier().x());
        bits(d, "stuckY", p.stuckSpeedMultiplier().y(), e.stuckSpeedMultiplier().y());
        bits(d, "stuckZ", p.stuckSpeedMultiplier().z(), e.stuckSpeedMultiplier().z());
        same(d, "inPowderSnow", p.inPowderSnow(), e.inPowderSnow());
        same(d, "noJumpDelay", p.noJumpDelay(), e.noJumpDelay());
        bits(d, "jumpPendingScale", p.jumpPendingScale(), e.jumpPendingScale());
        same(d, "allowStandSliding", p.allowStandSliding(), e.allowStandSliding());
        same(d, "standing", p.standing(), e.standing());
        same(d, "standCounter", p.standCounter(), e.standCounter());
        bits(d, "lavaHeight", p.lavaHeight(), e.lavaHeight());
        if (p.dashCooldown() >= 0) { // a nautilus's or camel's, once known (the tracker's is not before its first dash)
            same(d, "dashCooldown", p.dashCooldown(), e.dashCooldown());
        }
        same(d, "dashing", p.dashing(), e.dashing()); // a camel's
        same(d, "suffocating", p.suffocating(), e.suffocating());
        return d;
    }

    /** The spin attack strength of the course's trident (riptide III: 1.5 and 0.75 for each level above I). */
    static final float COURSE_RIPTIDE = 3.0F;

    private static List<String> differences(PlayerState p, PlayerState e) {
        List<String> d = new ArrayList<>();
        bits(d, "x", p.x(), e.x());
        bits(d, "y", p.y(), e.y());
        bits(d, "z", p.z(), e.z());
        bits(d, "dx", p.deltaMovement().x(), e.deltaMovement().x());
        bits(d, "dy", p.deltaMovement().y(), e.deltaMovement().y());
        bits(d, "dz", p.deltaMovement().z(), e.deltaMovement().z());
        bits(d, "fallDistance", p.fallDistance(), e.fallDistance());
        same(d, "onGround", p.onGround(), e.onGround());
        same(d, "horizontalCollision", p.horizontalCollision(), e.horizontalCollision());
        same(d, "minorHorizontalCollision", p.minorHorizontalCollision(), e.minorHorizontalCollision());
        same(d, "verticalCollision", p.verticalCollision(), e.verticalCollision());
        same(d, "verticalCollisionBelow", p.verticalCollisionBelow(), e.verticalCollisionBelow());
        same(d, "sprinting", p.sprinting(), e.sprinting());
        same(d, "sprintModifier", p.sprintModifier(), e.sprintModifier());
        same(d, "crouching", p.crouching(), e.crouching());
        same(d, "pose", p.pose(), e.pose());
        same(d, "noJumpDelay", p.noJumpDelay(), e.noJumpDelay());
        same(d, "sprintTriggerTime", p.sprintTriggerTime(), e.sprintTriggerTime());
        same(d, "autoJumpTime", p.autoJumpTime(), e.autoJumpTime());
        same(d, "support", p.support(), e.support());
        same(d, "onGroundNoBlocks", p.onGroundNoBlocks(), e.onGroundNoBlocks());
        same(d, "swimming", p.swimming(), e.swimming());
        same(d, "eyeInWater", p.eyeInWater(), e.eyeInWater());
        same(d, "fallFlying", p.fallFlying(), e.fallFlying());
        same(d, "inPowderSnow", p.inPowderSnow(), e.inPowderSnow());
        if (p.inWater() != null && e.inWater() != null) {
            same(d, "inWater", p.inWater(), e.inWater());
        }
        same(d, "spinAttackTicks", p.spinAttackTicks(), e.spinAttackTicks());
        return d;
    }

    private static void bits(List<String> d, String name, double predicted, double expected) {
        if (Double.doubleToRawLongBits(predicted) != Double.doubleToRawLongBits(expected)) {
            d.add(name + ": " + predicted + " vs client " + expected);
        }
    }

    private static void same(List<String> d, String name, Object predicted, Object expected) {
        if (!java.util.Objects.equals(predicted, expected)) {
            d.add(name + ": " + predicted + " vs client " + expected);
        }
    }

    /**
     * The movement speed attribute a recorded {@code LivingEntity.speed} shows (the attribute as a float, set in
     * {@code Player.aiStep} after the tick's sprint changes): the base {@code 0.1F}, powder snow's frost added (the
     * server's {@code tryAddFrost}: {@code -0.05F} times the part frozen, {@code ticksFrozen / 140}), and the sprinting
     * modifier or not. {@link #of} is null where none gives the float, or two that move the player differently do.
     */
    record RecordedSpeed(double frost, boolean sprintModifier) implements OnFoot.Speed {
        @Override
        public double value(boolean sprint) {
            double base = 0.1F + frost; // calculateValue: the base, plus the added
            return sprint ? base * (1.0 + 0.3F) : base;
        }

        /** Where frost and sprinting both give the float (72 ticks frozen walking, 120 sprinting), the sprinting flag decides. */
        static RecordedSpeed of(float recorded, boolean sprinting) {
            List<RecordedSpeed> matches = new ArrayList<>();
            for (int ticks = 0; ticks <= 140; ticks++) {
                double frost = -0.05F * ((float) ticks / 140);
                for (boolean sprint : new boolean[] {false, true}) {
                    RecordedSpeed s = new RecordedSpeed(frost, sprint);
                    if ((float) s.value(sprint) == recorded) {
                        matches.add(s);
                    }
                }
            }
            if (matches.stream().anyMatch(m -> m.sprintModifier() == sprinting)) {
                matches.removeIf(m -> m.sprintModifier() != sprinting);
            }
            if (matches.isEmpty() || matches.stream().map(m -> m.sprintModifier()).distinct().count() > 1) {
                return null;
            }
            RecordedSpeed first = matches.getFirst();
            boolean other = !first.sprintModifier();
            // the tick may toggle the modifier: every match must give the same float there too
            return matches.stream().allMatch(m -> (float) m.value(other) == (float) first.value(other)) ? first : null;
        }
    }

    @SuppressWarnings("unchecked")
    private static PlayerState state(Map<String, Object> r, boolean sprintModifier) {
        List<Object> pos = (List<Object>) r.get("pos");
        List<Object> support = (List<Object>) r.get("support");
        return new PlayerState(f64(pos.get(0)), f64(pos.get(1)), f64(pos.get(2)), vec(r.get("deltaMovement")), f32(r.get("yRot")), f32(r.get("xRot")),
                (Boolean) r.get("onGround"), (Boolean) r.get("horizontalCollision"), (Boolean) r.get("minorHorizontalCollision"),
                (Boolean) r.get("verticalCollision"), (Boolean) r.get("verticalCollisionBelow"), f64(r.get("fallDistance")), (Boolean) r.get("sprinting"),
                sprintModifier, (Boolean) r.get("crouching"), PlayerState.Pose.valueOf((String) r.get("pose")), keys((String) r.get("keys")),
                ((Number) r.get("noJumpDelay")).intValue(), ((Number) r.get("sprintTriggerTime")).intValue(), ((Number) r.get("autoJumpTime")).intValue(),
                support == null ? null : new BlockPos(((Number) support.get(0)).intValue(), ((Number) support.get(1)).intValue(),
                        ((Number) support.get(2)).intValue()),
                (Boolean) r.get("onGroundNoBlocks"), vec(r.get("stuckSpeedMultiplier")), Boolean.TRUE.equals(r.get("swimming")),
                Boolean.TRUE.equals(r.get("eyeInWater")), Boolean.TRUE.equals(r.get("fallFlying")), Boolean.TRUE.equals(r.get("inPowderSnow")),
                (Boolean) r.get("wasTouchingWater"), r.get("autoSpinAttackTicks") == null ? 0 : ((Number) r.get("autoSpinAttackTicks")).intValue());
    }

    /** The recorded effects the on-foot step reads (older truth files: none); null if one was not recorded by name. */
    @SuppressWarnings("unchecked")
    private static OnFoot.Effects effects(Map<String, Object> r) {
        Map<String, Object> active = (Map<String, Object>) r.getOrDefault("effects", Map.of());
        int jumpBoost = 0;
        int levitation = 0;
        boolean slowFalling = false;
        boolean blindness = false;
        boolean dolphinsGrace = false;
        boolean weaving = false;
        for (Map.Entry<String, Object> e : active.entrySet()) {
            int level = ((Number) e.getValue()).intValue() + 1;
            switch (e.getKey()) {
                case "minecraft:jump_boost" -> jumpBoost = level;
                case "minecraft:levitation" -> levitation = level;
                case "minecraft:slow_falling" -> slowFalling = true;
                case "minecraft:blindness" -> blindness = true;
                case "minecraft:dolphins_grace" -> dolphinsGrace = true;
                case "minecraft:weaving" -> weaving = true;
                default -> {
                    if (e.getKey().startsWith("unparsed:")) {
                        return null;
                    }
                }
            }
        }
        return new OnFoot.Effects(jumpBoost, levitation, slowFalling, blindness, dolphinsGrace, weaving);
    }

    /**
     * The item in use at the tick's start ({@code startedUsingItem}, after the tick's own keybinds), its prototype's
     * {@code use_effects} (the session's items have no patch); null when none (older truth files: none).
     */
    private static OnFoot.ItemUse use(Map<String, Object> r) {
        if (!Boolean.TRUE.equals(r.get("usingItem"))) {
            return null;
        }
        String stack = (String) r.get("useItem");
        String item = stack.substring(stack.lastIndexOf(' ') + 1);
        dev.whynotac.world.ItemPrototypes.Prototype p = dev.whynotac.world.ItemPrototypes.vanilla().get(item);
        if (p == null) {
            throw new IllegalStateException("an item in use the table does not know: " + stack);
        }
        return new OnFoot.ItemUse(p.useCanSprint(), p.useSpeed());
    }

    /** The recorder's key string: W S A D J H R or '-' per key, in {@code Input}'s order. */
    private static PlayerState.Keys keys(String k) {
        return new PlayerState.Keys(k.charAt(0) == 'W', k.charAt(1) == 'S', k.charAt(2) == 'A', k.charAt(3) == 'D', k.charAt(4) == 'J', k.charAt(5) == 'H',
                k.charAt(6) == 'R');
    }

    /** The client's world border at the move (older truth files: none recorded, taken as far). */
    @SuppressWarnings("unchecked")
    private static Border border(Map<String, Object> pre) {
        List<Object> b = (List<Object>) pre.get("border");
        return b == null ? null : new Border(f64(b.get(0)), f64(b.get(1)), f64(b.get(2)), f64(b.get(3)));
    }

    @SuppressWarnings("unchecked")
    private static Vec3d vec(Object o) {
        List<Object> v = (List<Object>) o;
        return new Vec3d(f64(v.get(0)), f64(v.get(1)), f64(v.get(2)));
    }

    private static double f64(Object hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong((String) hex, 16));
    }

    private static float f32(Object hex) {
        return Float.intBitsToFloat(Integer.parseUnsignedInt((String) hex, 16));
    }

    /** Just enough JSON for the recorder's lines and this tool's output. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> object(String text) {
            return (Map<String, Object>) new Json(text).value();
        }

        private Object value() {
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                while (s.charAt(i) != '}') {
                    String key = (String) value();
                    i++; // ':'
                    m.put(key, value());
                    if (s.charAt(i) == ',') {
                        i++;
                    }
                }
                i++;
                return m;
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                while (s.charAt(i) != ']') {
                    l.add(value());
                    if (s.charAt(i) == ',') {
                        i++;
                    }
                }
                i++;
                return l;
            }
            if (c == '"') {
                int end = s.indexOf('"', i + 1);
                String v = s.substring(i + 1, end);
                i = end + 1;
                return v;
            }
            int start = i;
            while (i < s.length() && ",}]".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            String token = s.substring(start, i);
            return switch (token) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> Long.parseLong(token);
            };
        }

        static String write(Object o) {
            if (o instanceof Map<?, ?> m) {
                StringBuilder sb = new StringBuilder("{");
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    sb.append(sb.length() > 1 ? "," : "").append(write(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
                }
                return sb.append('}').toString();
            }
            if (o instanceof List<?> l) {
                StringBuilder sb = new StringBuilder("[");
                for (Object v : l) {
                    sb.append(sb.length() > 1 ? "," : "").append(write(v));
                }
                return sb.append(']').toString();
            }
            if (o instanceof String str) {
                return '"' + str.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
            }
            return String.valueOf(o);
        }
    }
}
