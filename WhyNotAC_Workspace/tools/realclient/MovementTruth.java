import dev.whynotac.analysis.movement.Hypothesis;
import dev.whynotac.analysis.movement.MovementTracker;
import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.BlockPos;
import dev.whynotac.physics.Boat;
import dev.whynotac.physics.BoatState;
import dev.whynotac.physics.Border;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
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
 * villagers) and the firework rocket boosts ({@code rocketBoosts}; it glides with one) must each be more than none.
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
        Handled handled = new Handled();
        List<TimelineListener> chain = new ArrayList<>();
        // First in the chain: the replicas hold the world as it was before this event.
        chain.add((session, event) -> {
            handled.onEvent(event);
            if (event instanceof TimelineEvent.Inbound in) {
                boolean reply = afterAccept[0] && in.packet() instanceof ServerboundPacket.MovePlayer;
                afterAccept[0] = in.packet() instanceof ServerboundPacket.AcceptTeleportation;
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
        Replayer.replay(RecordingReader.open(Path.of(args[0])), chain);
        Map<String, Object> result = comparison.result();
        result.put("blockChangesDuringPlay", blockChanges[0]);
        Map<String, Object> tracker = track(Path.of(args[0]), comparison.sentTicks(), truth);
        result.put("tracker", tracker);
        System.out.println(Json.write(result));
        boolean clean = Long.valueOf(0).equals(result.get("mismatched")) && ((Number) result.get("matched")).longValue() > 0
                && ((Number) result.get("gliding")).longValue() > 0 && ((Number) result.get("nearBorder")).longValue() > 0
                && ((Number) result.get("inPowderSnow")).longValue() > 0 && comparison.boatsClean()
                && Long.valueOf(0).equals(tracker.get("unexplained")) && Long.valueOf(0).equals(tracker.get("truthMissing"))
                && ((Number) tracker.get("truthFound")).longValue() > 0 && ((Number) tracker.get("pushesFollowed")).longValue() > 0
                && ((Number) tracker.get("rocketBoosts")).longValue() > 0
                && ((Number) tracker.get("mounts")).longValue() > 0 && Long.valueOf(0).equals(tracker.get("mountsDiffering"));
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
        long[] mounts = new long[2]; // compared, differing
        List<String> mountExamples = new ArrayList<>();
        MovementTracker tracker = new MovementTracker();
        Map<String, Long> outcomes = new TreeMap<>();
        Map<String, Long> reasons = new TreeMap<>();
        long[] counts = new long[4]; // explained ticks compared, truth found, truth missing, unexplained
        List<String> examples = new ArrayList<>();
        int[] sentIndex = {0};
        boolean[] moved = {false};
        boolean[] afterAccept = {false};
        List<TimelineListener> chain = new ArrayList<>(replicas.listeners());
        chain.add((session, event) -> {
            if (event instanceof TimelineEvent.Inbound in && !(in.packet() instanceof ServerboundPacket.ClientTickEnd)) {
                boolean reply = afterAccept[0] && in.packet() instanceof ServerboundPacket.MovePlayer;
                afterAccept[0] = in.packet() instanceof ServerboundPacket.AcceptTeleportation;
                moved[0] |= in.packet() instanceof ServerboundPacket.MovePlayer && !reply;
            }
            if (event instanceof TimelineEvent.Outbound out && out.packet() instanceof ClientboundPacket.SetPassengers set
                    && set.passengerIds().length > 0) {
                mounting.add(set.vehicleId()); // every boat of the session carries the player
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
                if (d != null) {
                    mounts[1]++;
                    if (mountExamples.size() < 8) {
                        mountExamples.add("boat " + id + ": " + d);
                    }
                }
            }
            outcomes.merge(tick.outcome().name(), 1L, Long::sum);
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
            if (moved[0]) {
                moved[0] = false;
                PlayerState truth = sentIndex[0] < sentTicks.size() ? sentTicks.get(sentIndex[0]) : null;
                sentIndex[0]++;
                if (tick.outcome() == MovementTracker.Outcome.EXPLAINED && truth != null) {
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
                    if (!found && examples.size() < 12) {
                        examples.add("tick " + tick.number() + " (sent tick " + (sentIndex[0] - 1) + "): the client's state is no hypothesis of "
                                + tracker.sentHypotheses().size() + "; nearest differs in " + nearest);
                    }
                }
            }
        });
        Replayer.replay(RecordingReader.open(recording), chain);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcomes", outcomes);
        out.put("reasons", reasons);
        out.put("explainedSentTicks", counts[0]);
        out.put("truthFound", counts[1]);
        out.put("truthMissing", counts[2]);
        out.put("unexplained", counts[3]);
        out.put("pushesFollowed", tracker.pushesFollowed());
        out.put("rocketBoosts", tracker.rocketBoosts());
        out.put("mounts", mounts[0]);
        out.put("mountsDiffering", mounts[1]);
        out.put("mountExamples", mountExamples);
        out.put("examples", examples);
        return out;
    }

    /** Null if the replica's only state for the boat at the mount is exactly the client's before its first tick, else how not. */
    @SuppressWarnings("unchecked")
    private static String mountDifference(List<ClientEntities.Mounted> mounted, Map<String, Object> pre) {
        if (mounted.size() != 1 || !mounted.getFirst().exact()) {
            return mounted.size() + " states, exact " + mounted.stream().map(ClientEntities.Mounted::exact).toList();
        }
        ClientEntities.Mounted m = mounted.getFirst();
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
                    compareBoat(boat[0], boat[1], tick[0], world);
                }
                if (riding.get(next - 1)) {
                    ridingTicks++;
                } else if (onLastBoat(tick[0])) {
                    unknown.merge("standing on the boat it just left, which the client still holds (a solid entity)", 1L, Long::sum);
                } else {
                    compare(tick[0], tick[1], world);
                }
                if (sentHere) {
                    return;
                }
            }
        }

        private void compare(Map<String, Object> pre, Map<String, Object> post, Colliders.World world) {
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
                PlayerState predicted = OnFoot.tick(before, keys((String) post.get("keys")), world,
                        new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, autoJump, speedAfter, effects, use, glider, CLIENT_COSINE,
                                border, boots));
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
        private boolean onLastBoat(Map<String, Object> pre) {
            if (lastBoat == null) {
                return false;
            }
            List<Object> pos = (List<Object>) pre.get("pos");
            double x = f64(pos.get(0));
            double y = f64(pos.get(1));
            double z = f64(pos.get(2));
            return x + 0.3 > lastBoat.minX() && x - 0.3 < lastBoat.maxX() && z + 0.3 > lastBoat.minZ() && z - 0.3 < lastBoat.maxZ()
                    && y > lastBoat.maxY() - 1.0E-3 && y < lastBoat.maxY() + 0.5;
        }

        /** The boat's tick from {@code pre} to {@code post}, in the client tick whose player record starts at {@code player}. */
        private void compareBoat(Map<String, Object> pre, Map<String, Object> post, Map<String, Object> player, Colliders.World world) {
            BoatState last = boatState(post);
            lastBoat = BoatState.box(last.x(), last.y(), last.z());
            if (Boolean.TRUE.equals(pre.get("interpolating"))) {
                boatUnknown.merge("interpolating toward a position the server sent", 1L, Long::sum);
                return;
            }
            if (Boolean.TRUE.equals(pre.get("firstTick"))) {
                boatUnknown.merge("the boat's first tick", 1L, Long::sum);
                return;
            }
            BoatState before = boatState(pre);
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
            }
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
            out.put("frozen", frostTicks);
            out.put("unknown", unknown);
            out.put("differingFields", differingFields);
            out.put("examples", examples);
            out.put("riding", ridingTicks);
            Map<String, Object> boat = new LinkedHashMap<>();
            boat.put("ticks", boatTicks);
            boat.put("matched", boatMatched);
            boat.put("mismatched", boatMismatched);
            boat.put("statuses", boatStatuses);
            boat.put("landings", boatLandings);
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
                Boolean.TRUE.equals(r.get("eyeInWater")), Boolean.TRUE.equals(r.get("fallFlying")), Boolean.TRUE.equals(r.get("inPowderSnow")));
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
