import dev.whynotac.analysis.movement.Hypothesis;
import dev.whynotac.analysis.movement.MovementTracker;
import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.BlockPos;
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
 * block. Ticks outside its scope are counted by reason.
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
 * villagers) must be more than none.
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
        Map<String, Object> tracker = track(Path.of(args[0]), comparison.sentTicks());
        result.put("tracker", tracker);
        System.out.println(Json.write(result));
        boolean clean = Long.valueOf(0).equals(result.get("mismatched")) && ((Number) result.get("matched")).longValue() > 0
                && ((Number) result.get("gliding")).longValue() > 0
                && Long.valueOf(0).equals(tracker.get("unexplained")) && Long.valueOf(0).equals(tracker.get("truthMissing"))
                && ((Number) tracker.get("truthFound")).longValue() > 0 && ((Number) tracker.get("pushesFollowed")).longValue() > 0;
        System.exit(clean ? 0 : 1);
    }

    /**
     * The movement check's tracker over the recording: outcomes by reason, and at every explained tick that
     * sent a movement packet whether the client's own state after it (the k-th such tick of the truth) is a hypothesis.
     */
    /** The client ran on this machine: its Math.cos is this JVM's (null: none of the platforms the engine knows). */
    private static final PlatformCos CLIENT_COSINE = java.util.Objects.requireNonNull(PlatformCos.current(), "this JVM's Math.cos");

    private static Map<String, Object> track(Path recording, List<PlayerState> sentTicks) throws Exception {
        Replicas replicas = Replicas.vanilla();
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
            MovementTracker.Tick tick = tracker.onEvent(event, replicas.world().world(session), replicas.entities().entities(session),
                    replicas.inventory().inventory(session));
            if (tick == null) {
                return;
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
        out.put("examples", examples);
        return out;
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
        private int next;
        private boolean firstLoaded = true;
        long matched;
        long mismatched;
        long effectTicks;
        long useTicks;
        long glideTicks;
        final Map<String, Long> unknown = new TreeMap<>();
        final Map<String, Long> differingFields = new TreeMap<>();
        final List<String> examples = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Comparison(List<Map<String, Object>> truth) {
            for (int i = 0; i + 1 < truth.size(); i++) {
                Map<String, Object> pre = truth.get(i);
                Map<String, Object> post = truth.get(i + 1);
                if ("pre".equals(pre.get("hook")) && "post".equals(post.get("hook")) && pre.get("tickCount").equals(post.get("tickCount"))
                        && (Boolean) pre.get("loaded")) {
                    if ((Boolean) pre.get("passenger")) {
                        throw new IllegalStateException("the session rode a vehicle");
                    }
                    ticks.add(new Map[] {pre, post});
                    // sendPosition: a position (reminder reset), a rotation (last sent changed) or the flags changed
                    boolean position = ((Number) post.get("positionReminder")).longValue() == 0;
                    boolean rotation = !post.get("yRotLast").equals(pre.get("yRotLast")) || !post.get("xRotLast").equals(pre.get("xRotLast"));
                    boolean status = !pre.get("lastOnGround").equals(post.get("onGround"))
                            || !pre.get("lastHorizontalCollision").equals(post.get("horizontalCollision"));
                    sent.add(position || rotation || status);
                }
            }
        }

        /** The client's own state after each tick that sent a movement packet, in order. */
        List<PlayerState> sentTicks() {
            List<PlayerState> out = new ArrayList<>();
            for (int i = 0; i < ticks.size(); i++) {
                if (sent.get(i)) {
                    out.add(state(ticks.get(i)[1]));
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
                compare(tick[0], tick[1], sentHere ? sending : before);
                if (sentHere) {
                    return;
                }
            }
        }

        private void compare(Map<String, Object> pre, Map<String, Object> post, Colliders.World world) {
            // LocalPlayer.autoJumpEnabled: true until a new player's first sendPosition, then the option (older truth files: off)
            boolean autoJump = firstLoaded || Boolean.TRUE.equals(pre.get("autoJumpEnabled"));
            firstLoaded = false;
            PlayerState before = state(pre);
            PlayerState expected = state(post);
            float speed = f32(pre.get("speed"));
            if (speed != (float) OnFoot.Speed.VANILLA.value(true) && speed != (float) OnFoot.Speed.VANILLA.value(false)) {
                unknown.merge("a speed attribute other than the vanilla walk or sprint", 1L, Long::sum);
                return;
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
                PlayerState predicted = OnFoot.tick(before, keys((String) post.get("keys")), world,
                        new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, autoJump, OnFoot.Speed.VANILLA, effects, use, glider, CLIENT_COSINE));
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

        Map<String, Object> result() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ticks", (long) ticks.size());
            out.put("compared", (long) next);
            out.put("matched", matched);
            out.put("mismatched", mismatched);
            out.put("withEffects", effectTicks);
            out.put("usingItem", useTicks);
            out.put("gliding", glideTicks);
            out.put("unknown", unknown);
            out.put("differingFields", differingFields);
            out.put("examples", examples);
            return out;
        }
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

    @SuppressWarnings("unchecked")
    private static PlayerState state(Map<String, Object> r) {
        List<Object> pos = (List<Object>) r.get("pos");
        List<Object> support = (List<Object>) r.get("support");
        return new PlayerState(f64(pos.get(0)), f64(pos.get(1)), f64(pos.get(2)), vec(r.get("deltaMovement")), f32(r.get("yRot")), f32(r.get("xRot")),
                (Boolean) r.get("onGround"), (Boolean) r.get("horizontalCollision"), (Boolean) r.get("minorHorizontalCollision"),
                (Boolean) r.get("verticalCollision"), (Boolean) r.get("verticalCollisionBelow"), f64(r.get("fallDistance")), (Boolean) r.get("sprinting"),
                f32(r.get("speed")) == (float) OnFoot.Speed.VANILLA.value(true), (Boolean) r.get("crouching"), PlayerState.Pose.valueOf((String) r.get("pose")), keys((String) r.get("keys")),
                ((Number) r.get("noJumpDelay")).intValue(), ((Number) r.get("sprintTriggerTime")).intValue(), ((Number) r.get("autoJumpTime")).intValue(),
                support == null ? null : new BlockPos(((Number) support.get(0)).intValue(), ((Number) support.get(1)).intValue(),
                        ((Number) support.get(2)).intValue()),
                (Boolean) r.get("onGroundNoBlocks"), vec(r.get("stuckSpeedMultiplier")), Boolean.TRUE.equals(r.get("swimming")),
                Boolean.TRUE.equals(r.get("eyeInWater")), Boolean.TRUE.equals(r.get("fallFlying")));
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
