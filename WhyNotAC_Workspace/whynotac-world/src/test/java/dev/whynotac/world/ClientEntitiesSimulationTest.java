package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Soundness against a simulated client: a server sends entity packets every tick, the network delivers
 * them with latency and arbitrary splits, and a client processes them frame by frame exactly as the
 * 1.21.11 client does (a drain of what has arrived, then up to 10 ticks), answering pings. The client
 * here is written independently of {@link ClientEntities}, straight from the decompiled code. Fed only
 * what the server sees, the replica must, at every ClientTickEnd, include the client's true state of
 * every entity among its possible states: bit-exactly where it claims radius 0.
 */
class ClientEntitiesSimulationTest {

    private static final int SELF = 1;
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final int LOADED = 2; // chunks -2..2 are loaded

    @Test
    void theReplicaAlwaysCoversTheClient() {
        long[] exact = new long[4];
        long[] checks = new long[4];
        long[] overflows = new long[4];
        for (int seed = 0; seed < 1000; seed++) {
            Run run = new Run(seed);
            run.simulate(300);
            exact[run.pingMode] += run.exact;
            checks[run.pingMode] += run.checks;
            overflows[run.pingMode] += run.replica.stats().overflows();
        }
        String[] modes = {"ping before and after each flush", "ping at flush start", "ping at flush end", "random pings"};
        for (int mode = 0; mode < 4; mode++) {
            System.out.printf("%s: %d checks, exact %.1f%%, overflows %d%n", modes[mode], checks[mode], 100.0 * exact[mode] / checks[mode],
                    overflows[mode]);
        }
        assertTrue(java.util.Arrays.stream(checks).sum() > 500_000, "enough coverage");
    }

    @Test
    void bracketedFlushesWithRareSplitsStayMostlyExact() {
        long exact = 0;
        long precise = 0;
        long several = 0;
        long lost = 0;
        long checks = 0;
        long overflows = 0;
        for (int seed = 0; seed < 200; seed++) {
            Run run = new Run(seed);
            run.pingMode = 0;
            run.splitChance = 0.01;
            run.farSyncs = false;
            run.bound = 28;
            run.simulate(300);
            exact += run.exact;
            precise += run.precise;
            several += run.several;
            lost += run.lost;
            checks += run.checks;
            overflows += run.replica.stats().overflows();
        }
        System.out.printf("bracketed, 1%% of packets delayed: %d checks, exact %.2f%%, within 1e-6 %.2f%%, several candidates %.2f%%, "
                + "position lost %d, overflows %d%n", checks, 100.0 * exact / checks, 100.0 * precise / checks, 100.0 * several / checks, lost,
                overflows);
        assertTrue(precise > 0.94 * checks, "within 1e-6: " + precise + " of " + checks); // the rest: a radius still shrinking after a delay
        assertTrue(overflows == 0, "overflows: " + overflows);
    }

    @Test
    void bracketedFlushesAreExact() {
        long exact = 0;
        long checks = 0;
        for (int seed = 0; seed < 100; seed++) {
            Run run = new Run(seed);
            run.pingMode = 0;
            run.splitChance = 0.0;
            run.farSyncs = false; // whether the client's collision test moved an interpolation target is not followed
            run.bound = 28; // nor would it after a mob outside the loaded chunks (not ticking) is snapped
            run.simulate(300);
            exact += run.exact;
            checks += run.checks;
        }
        assertTrue(exact == checks, "every flush bracketed and never split: exact " + exact + " of " + checks);
    }

    /** One simulated connection. */
    private static final class Run {
        final Random random;
        final ClientEntities replica = new ClientEntities(EntityTypes.vanilla());
        final int seed;
        /** 0: a ping before and after every flush; 1: one at the start; 2: one at the end; 3: random. */
        int pingMode;
        double splitChance;
        /** Absolute positions more than 64 blocks away (the client snaps, and an interpolation in progress may follow). */
        boolean farSyncs = true;
        /** How far from the origin entities go (chunks -2..2 are loaded: within 28, mobs always tick). */
        double bound = 200;
        long exact;
        long precise;
        long several;
        long checks;
        long lost;

        // server
        final Map<Integer, ServerEntity> server = new HashMap<>();
        int nextPing = 1;
        // network: packets in send order with send and arrival time
        final List<Sent> wire = new ArrayList<>();
        // events the server sees, by time: outbound at send time, inbound at arrival
        final TreeMap<Long, List<Object>> timeline = new TreeMap<>();
        long latencyIn;
        // client
        final Client client = new Client();

        Run(int seed) {
            this.seed = seed;
            this.random = new Random(seed * 7919L + 13);
            this.pingMode = random.nextInt(4);
            this.splitChance = random.nextDouble() * 0.5;
        }

        void simulate(int serverTicks) {
            long latencyOut = random.nextInt(80);
            latencyIn = 1 + random.nextInt(80);
            // Login and the world first, processed before anything else.
            List<ClientboundPacket> setup = new ArrayList<>();
            setup.add(new ClientboundPacket.Login(SELF, 8, OVERWORLD));
            setup.add(new ClientboundPacket.ChunkCacheCenter(0, 0));
            for (int x = -LOADED; x <= LOADED; x++) {
                for (int z = -LOADED; z <= LOADED; z++) {
                    setup.add(new ClientboundPacket.LevelChunk(x, z, null));
                }
            }
            for (int id = 2; id <= 4; id++) {
                setup.add(new ClientboundPacket.PlayerInfoUpdate(true, false, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(new UUID(9, id), -1))));
            }
            send(0, setup, true);
            long nextServerTick = 50;
            long clientTime = 0;
            int serverTick = 0;
            long lastFrame = 0;
            double residual = 0;
            int delivered = 0;
            while (serverTick < serverTicks) {
                // the next frame of the client
                long frame = clientTime + (random.nextInt(20) == 0 ? 100 + random.nextInt(300) : 1 + random.nextInt(60));
                while (nextServerTick <= frame) {
                    serverTick(nextServerTick, latencyOut);
                    nextServerTick += 50;
                    serverTick++;
                }
                clientTime = frame;
                // drain: everything that has arrived (packets of one flush may arrive apart)
                while (delivered < wire.size() && wire.get(delivered).arrival <= frame) {
                    client.process(wire.get(delivered).packet, frame, this);
                    delivered++;
                }
                residual += (frame - lastFrame) / 50.0;
                lastFrame = frame;
                int ticks = (int) Math.min(10, Math.floor(residual));
                residual -= Math.floor(residual);
                for (int t = 0; t < ticks; t++) {
                    client.tick(random);
                    Map<Integer, Snapshot> truth = client.snapshot();
                    at(frame + latencyIn, new TickEnd(truth));
                }
            }
            for (Map.Entry<Long, List<Object>> entry : timeline.entrySet()) {
                for (Object event : entry.getValue()) {
                    feed(event);
                }
            }
        }

        long sequence;
        final Map<Integer, java.util.ArrayDeque<String>> logs = new HashMap<>();

        void log(int id, String line) {
            java.util.ArrayDeque<String> log = logs.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
            log.add(sequence + " " + line);
            if (log.size() > 80) {
                log.pollFirst();
            }
        }

        static int entityOf(ClientboundPacket p) {
            return switch (p) {
                case ClientboundPacket.MoveEntity m -> m.entityId();
                case ClientboundPacket.EntityPositionSync m -> m.entityId();
                case ClientboundPacket.RotateHead m -> m.entityId();
                case ClientboundPacket.AddEntity m -> m.entityId();
                case ClientboundPacket.RemoveEntities m -> m.entityIds()[0];
                default -> -1;
            };
        }

        void feed(Object event) {
            if (event instanceof ClientboundPacket p && entityOf(p) >= 0) {
                log(entityOf(p), p.toString());
            } else if (event instanceof Pong pong) {
                for (int id : logs.keySet()) {
                    log(id, "pong " + pong.id);
                }
            } else if (event instanceof ClientboundPacket.Ping ping) {
                for (int id : logs.keySet()) {
                    log(id, "ping " + ping.id());
                }
            }
            switch (event) {
                case ClientboundPacket packet -> replica.onEvent(new TimelineEvent.Outbound(sequence++, sequence, packet));
                case Pong pong -> replica.onEvent(new TimelineEvent.Acknowledged(sequence++, sequence, pong.id, 0, 0));
                case TickEnd tickEnd -> {
                    replica.onEvent(new TimelineEvent.Inbound(sequence++, sequence, 0, new ServerboundPacket.ClientTickEnd()));
                    for (int id : logs.keySet()) {
                        List<ClientEntities.PossibleState> states = new ArrayList<>();
                        replica.forEachPossibleState(id, states::add);
                        log(id, "tick: client " + tickEnd.truth.get(id) + "\n      replica " + states);
                    }
                    check(tickEnd.truth);
                }
                default -> throw new IllegalStateException();
            }
        }

        void check(Map<Integer, Snapshot> truth) {
            Set<Integer> ids = new HashSet<>(truth.keySet());
            ids.addAll(server.keySet());
            for (int id : ids) {
                Snapshot t = truth.get(id);
                List<ClientEntities.PossibleState> states = new ArrayList<>();
                replica.forEachPossibleState(id, states::add);
                checks++;
                boolean covered = false;
                boolean allExact = !states.isEmpty();
                for (ClientEntities.PossibleState s : states) {
                    allExact &= s.known() && (!s.present() || s.positionKnown() && s.positionRadius() == 0 && s.rotationRadius() == 0 && s.headRadius() == 0);
                    if (!s.known()) {
                        covered = true;
                        continue;
                    }
                    if (t == null) {
                        covered |= !s.present();
                        continue;
                    }
                    if (!s.present() || !Objects.equals(s.type(), t.type)) {
                        continue;
                    }
                    if (!s.positionKnown()) {
                        covered = true;
                        continue;
                    }
                    double r = s.positionRadius();
                    float rr = s.rotationRadius();
                    boolean position = r == 0
                            ? s.x() == t.x && s.y() == t.y && s.z() == t.z
                            : Math.abs(s.x() - t.x) <= r && Math.abs(s.y() - t.y) <= r && Math.abs(s.z() - t.z) <= r;
                    float rh = s.headRadius();
                    boolean rotation = rr >= 360 || (rr == 0
                            ? s.yRot() == t.yRot && s.xRot() == t.xRot
                            : angle(s.yRot(), t.yRot) <= rr && Math.abs(s.xRot() - t.xRot) <= rr);
                    rotation &= rh >= 360 || (rh == 0 ? s.yHeadRot() == t.yHeadRot : angle(s.yHeadRot(), t.yHeadRot) <= rh);
                    covered |= position && rotation;
                }
                if (allExact && states.size() == 1) {
                    exact++;
                }
                if (states.size() == 1 && states.getFirst().known() && (!states.getFirst().present() || states.getFirst().positionKnown()
                        && states.getFirst().positionRadius() <= 1e-6 && states.getFirst().rotationRadius() <= 1e-3F
                        && states.getFirst().headRadius() <= 1e-3F)) {
                    precise++;
                }
                if (states.size() > 1) {
                    several++;
                }
                for (ClientEntities.PossibleState s : states) {
                    if (s.present() && !s.positionKnown()) {
                        lost++;
                        break;
                    }
                }
                if (!covered) {
                    fail("seed " + seed + " (ping mode " + pingMode + "): entity " + id + " is " + t + " on the client; the replica has " + states
                            + "\n" + String.join("\n", logs.getOrDefault(id, new java.util.ArrayDeque<>())));
                }
            }
        }

        static float angle(float a, float b) {
            float d = Math.abs((a - b) % 360.0F);
            return Math.min(d, 360.0F - d);
        }

        void at(long time, Object event) {
            timeline.computeIfAbsent(time, k -> new ArrayList<>()).add(event);
        }

        /** One flush: optional pings around it, each packet with its arrival time. */
        void send(long time, List<ClientboundPacket> packets, boolean bracket) {
            List<ClientboundPacket> flush = new ArrayList<>();
            boolean pre = bracket || pingMode == 0 || pingMode == 1;
            boolean post = bracket || pingMode == 0 || pingMode == 2;
            if (pre) {
                flush.add(new ClientboundPacket.Ping(nextPing++));
            }
            for (ClientboundPacket p : packets) {
                flush.add(p);
                if (pingMode == 3 && random.nextInt(3) == 0) {
                    flush.add(new ClientboundPacket.Ping(nextPing++));
                }
            }
            if (post) {
                flush.add(new ClientboundPacket.Ping(nextPing++));
            }
            long arrival = Math.max(wire.isEmpty() ? 0 : wire.getLast().arrival, time + latencyOutFor());
            for (ClientboundPacket p : flush) {
                if (random.nextDouble() < splitChance) {
                    arrival += random.nextInt(30); // the rest of the flush arrives later
                }
                wire.add(new Sent(p, arrival));
                at(time, p);
            }
        }

        long latencyOut = -1;

        long latencyOutFor() {
            if (latencyOut < 0) {
                latencyOut = random.nextInt(80);
            }
            return latencyOut;
        }

        void serverTick(long time, long ignored) {
            List<ClientboundPacket> packets = new ArrayList<>();
            if (server.size() < 3 && random.nextInt(10) == 0) {
                int id = 2 + random.nextInt(3);
                if (!server.containsKey(id)) {
                    String type = random.nextInt(3) == 0 ? EntityTypes.PLAYER : random.nextInt(2) == 0 ? "minecraft:zombie" : "minecraft:end_crystal";
                    ServerEntity e = new ServerEntity(type, new Vec3d(clampWorld(random.nextInt(60) - 30 + 0.5), 64, clampWorld(random.nextInt(60) - 30 + 0.5)));
                    server.put(id, e);
                    UUID uuid = type.equals(EntityTypes.PLAYER) ? new UUID(9, id) : new UUID(0, id);
                    packets.add(ClientboundPacket.AddEntity.received(id, uuid, type, e.position.x(), e.position.y(), e.position.z(), Vec3d.ZERO,
                            (byte) random.nextInt(), (byte) random.nextInt(), (byte) random.nextInt(), 0));
                }
            }
            for (Map.Entry<Integer, ServerEntity> entry : new ArrayList<>(server.entrySet())) {
                int id = entry.getKey();
                ServerEntity e = entry.getValue();
                int roll = random.nextInt(100);
                if (roll < 45) {
                    // a short move, maybe with rotation
                    Vec3d step = e.position.add((random.nextInt(4001) - 2000) / 4096.0 * random.nextDouble(), random.nextInt(3) == 0 ? (random.nextInt(801) - 400) / 4096.0 : 0,
                            (random.nextInt(4001) - 2000) / 4096.0 * random.nextDouble());
                    Vec3d target = new Vec3d(clampWorld(step.x()), step.y(), clampWorld(step.z()));
                    boolean rotation = random.nextBoolean();
                    packets.add(new ClientboundPacket.MoveEntity(id, (short) delta(e.base.x(), target.x()), (short) delta(e.base.y(), target.y()),
                            (short) delta(e.base.z(), target.z()), rotation ? (byte) random.nextInt() : 0, rotation ? (byte) random.nextInt() : 0,
                            random.nextBoolean(), true, rotation));
                    e.base = target;
                    e.position = target;
                } else if (roll < 52) {
                    packets.add(new ClientboundPacket.MoveEntity(id, (short) 0, (short) 0, (short) 0, (byte) random.nextInt(), (byte) random.nextInt(),
                            random.nextBoolean(), false, true));
                } else if (roll < 58) {
                    // an absolute position: near, or far away (the client snaps)
                    double jump = farSyncs && random.nextInt(4) == 0 ? 70 + random.nextInt(40) : random.nextDouble() * 3;
                    Vec3d target = new Vec3d(clampWorld(e.position.x() + (random.nextBoolean() ? jump : -jump)), 64 + random.nextDouble(),
                            clampWorld(e.position.z() + random.nextDouble()));
                    packets.add(new ClientboundPacket.EntityPositionSync(id, target, Vec3d.ZERO, random.nextFloat() * 720 - 360,
                            random.nextFloat() * 180 - 90, random.nextBoolean()));
                    e.base = target;
                    e.position = target;
                } else if (roll < 66) {
                    packets.add(new ClientboundPacket.RotateHead(id, (byte) random.nextInt()));
                } else if (roll < 67) {
                    packets.add(new ClientboundPacket.RemoveEntities(new int[] {id}));
                    server.remove(id);
                }
            }
            if (!packets.isEmpty() || pingMode != 3) {
                send(time, packets, false);
            }
        }

        double clampWorld(double v) {
            return Math.max(-bound, Math.min(bound, v));
        }

        static long delta(double base, double target) {
            return Math.round(target * 4096.0) - Math.round(base * 4096.0);
        }
    }

    private record Sent(ClientboundPacket packet, long arrival) {}

    private record Pong(int id) {}

    private record TickEnd(Map<Integer, Snapshot> truth) {}

    private record Snapshot(String type, double x, double y, double z, float yRot, float xRot, float yHeadRot) {}

    private static final class ServerEntity {
        Vec3d position;
        Vec3d base;

        ServerEntity(String type, Vec3d position) {
            this.position = position;
            this.base = position;
        }
    }

    /**
     * The client: the parts of ClientPacketListener, Entity, LivingEntity and InterpolationHandler
     * (1.21.11) that place other entities, written out plainly.
     */
    private static final class Client {
        final Map<Integer, RefEntity> level = new HashMap<>();
        final Set<UUID> profiles = new HashSet<>();

        void process(ClientboundPacket packet, long time, Run run) {
            switch (packet) {
                case ClientboundPacket.Ping ping -> run.at(time + run.latencyIn, new Pong(ping.id()));
                case ClientboundPacket.PlayerInfoUpdate info -> info.entries().forEach(e -> profiles.add(e.profileId()));
                case ClientboundPacket.AddEntity add -> {
                    if (add.type().equals(EntityTypes.PLAYER) && !profiles.contains(add.uuid())) {
                        return;
                    }
                    level.remove(add.entityId());
                    level.put(add.entityId(), new RefEntity(add));
                }
                case ClientboundPacket.RemoveEntities remove -> {
                    for (int id : remove.entityIds()) {
                        level.remove(id);
                    }
                }
                case ClientboundPacket.MoveEntity move -> {
                    RefEntity e = level.get(move.entityId());
                    if (e == null) {
                        return;
                    }
                    if (move.hasPosition()) {
                        Vec3d p = e.decode(move.xa(), move.ya(), move.za());
                        e.base = p;
                        if (move.hasRotation()) {
                            e.moveOrInterpolateTo(p, unpack(move.yRot()), unpack(move.xRot()));
                        } else {
                            e.moveOrInterpolateTo(p, null, null);
                        }
                    } else if (move.hasRotation()) {
                        e.moveOrInterpolateTo(null, unpack(move.yRot()), unpack(move.xRot()));
                    }
                }
                case ClientboundPacket.EntityPositionSync sync -> {
                    RefEntity e = level.get(sync.entityId());
                    if (e == null) {
                        return;
                    }
                    e.base = sync.position();
                    boolean far = distanceSqr(e.x, e.y, e.z, sync.position()) > 4096.0;
                    if (e.ticking() && !far) {
                        e.moveOrInterpolateTo(sync.position(), sync.yRot(), sync.xRot());
                    } else {
                        e.x = sync.position().x();
                        e.y = sync.position().y();
                        e.z = sync.position().z();
                        e.setYRot(sync.yRot());
                        e.setXRot(sync.xRot());
                    }
                }
                case ClientboundPacket.RotateHead head -> {
                    RefEntity e = level.get(head.entityId());
                    if (e != null && e.living) {
                        e.lerpYHeadRot = unpack(head.yHeadRot());
                        e.lerpHeadSteps = 3;
                    }
                }
                default -> { }
            }
        }

        void tick(Random random) {
            for (RefEntity e : level.values()) {
                if (!e.ticking()) {
                    continue;
                }
                if (e.living) {
                    if (e.steps > 0) {
                        e.interpolate(random);
                    }
                    if (e.lerpHeadSteps > 0) {
                        e.yHeadRot = (float) rotLerp(1.0 / e.lerpHeadSteps, e.yHeadRot, e.lerpYHeadRot);
                        e.lerpHeadSteps--;
                    }
                }
            }
        }

        Map<Integer, Snapshot> snapshot() {
            Map<Integer, Snapshot> snapshot = new HashMap<>();
            level.forEach((id, e) -> snapshot.put(id, new Snapshot(e.type, e.x, e.y, e.z, e.yRot, e.xRot, e.yHeadRot)));
            return snapshot;
        }

        static float unpack(byte b) {
            return b * 360 / 256.0F;
        }

        static double distanceSqr(double x, double y, double z, Vec3d p) {
            double dx = x - p.x();
            double dy = y - p.y();
            double dz = z - p.z();
            return dx * dx + dy * dy + dz * dz;
        }

        static double wrapDegrees(double d) {
            double r = d % 360.0;
            if (r >= 180.0) {
                r -= 360.0;
            }
            if (r < -180.0) {
                r += 360.0;
            }
            return r;
        }

        static double rotLerp(double delta, double start, double end) {
            return start + delta * wrapDegrees(end - start);
        }

        static double lerp(double delta, double start, double end) {
            return start + delta * (end - start);
        }
    }

    private static final class RefEntity {
        final String type;
        final boolean living;
        double x;
        double y;
        double z;
        float yRot;
        float xRot;
        float yHeadRot;
        Vec3d base;
        // InterpolationHandler (living entities: 3 steps)
        int steps;
        Vec3d target = Vec3d.ZERO;
        float targetYRot;
        float targetXRot;
        Vec3d previousTickPosition;
        float[] previousTickRot; // {xRot, yRot}
        double lerpYHeadRot;
        int lerpHeadSteps;

        RefEntity(ClientboundPacket.AddEntity add) {
            type = add.type();
            living = !type.equals("minecraft:end_crystal");
            base = new Vec3d(add.x(), add.y(), add.z());
            float yRot = Client.unpack(add.yRot());
            float xRot = Client.unpack(add.xRot());
            if (living) {
                yHeadRot = Client.unpack(add.yHeadRot());
                x = Math.clamp(add.x(), -3.0E7, 3.0E7);
                y = add.y();
                z = Math.clamp(add.z(), -3.0E7, 3.0E7);
                setYRot(yRot % 360.0F);
                setXRot(Math.clamp(xRot, -90.0F, 90.0F) % 360.0F);
            } else {
                x = add.x();
                y = add.y();
                z = add.z();
                setYRot(yRot);
                setXRot(xRot);
            }
        }

        boolean ticking() {
            if (type.equals(EntityTypes.PLAYER)) {
                return true;
            }
            int cx = (int) Math.floor(x) >> 4;
            int cz = (int) Math.floor(z) >> 4;
            return Math.abs(cx) <= LOADED && Math.abs(cz) <= LOADED;
        }

        void setYRot(float v) {
            if (Float.isFinite(v)) {
                yRot = v;
            }
        }

        void setXRot(float v) {
            if (Float.isFinite(v)) {
                xRot = Math.clamp(v % 360.0F, -90.0F, 90.0F);
            }
        }

        Vec3d decode(long xa, long ya, long za) {
            if (xa == 0 && ya == 0 && za == 0) {
                return base;
            }
            double nx = xa == 0 ? base.x() : (Math.round(base.x() * 4096.0) + xa) / 4096.0;
            double ny = ya == 0 ? base.y() : (Math.round(base.y() * 4096.0) + ya) / 4096.0;
            double nz = za == 0 ? base.z() : (Math.round(base.z() * 4096.0) + za) / 4096.0;
            return new Vec3d(nx, ny, nz);
        }

        Vec3d handlerPosition() {
            return steps > 0 ? target : new Vec3d(x, y, z);
        }

        float handlerYRot() {
            return steps > 0 ? targetYRot : yRot;
        }

        float handlerXRot() {
            return steps > 0 ? targetXRot : xRot;
        }

        void moveOrInterpolateTo(Vec3d position, Float yRotation, Float xRotation) {
            if (!living) {
                if (position != null) {
                    x = position.x();
                    y = position.y();
                    z = position.z();
                }
                if (yRotation != null) {
                    setYRot(yRotation % 360.0F);
                    setXRot(xRotation % 360.0F);
                }
                return;
            }
            Vec3d p = position != null ? position : handlerPosition();
            Float yr = yRotation != null ? yRotation : handlerYRot();
            Float xr = xRotation != null ? xRotation : handlerXRot();
            if (steps <= 0 || !Objects.equals(handlerYRot(), yr) || !Objects.equals(handlerXRot(), xr) || !Objects.equals(handlerPosition(), p)) {
                steps = 3;
                target = p;
                targetYRot = yr;
                targetXRot = xr;
                previousTickPosition = new Vec3d(x, y, z);
                previousTickRot = new float[] {xRot, yRot};
            }
        }

        void interpolate(Random random) {
            double d = 1.0 / steps;
            if (previousTickPosition != null) {
                Vec3d moved = new Vec3d(x, y, z).subtract(previousTickPosition);
                if (random.nextBoolean()) { // level.noCollision(...): unknown to WhyNotAC, so either
                    target = target.add(moved);
                }
            }
            if (previousTickRot != null) {
                targetYRot += yRot - previousTickRot[1];
                targetXRot += xRot - previousTickRot[0];
            }
            double nx = Client.lerp(d, x, target.x());
            double ny = Client.lerp(d, y, target.y());
            double nz = Client.lerp(d, z, target.z());
            float ny2 = (float) Client.rotLerp(d, yRot, targetYRot);
            float nx2 = (float) Client.lerp(d, xRot, targetXRot);
            x = nx;
            y = ny;
            z = nz;
            setYRot(ny2 % 360.0F);
            setXRot(nx2 % 360.0F);
            steps--;
            previousTickPosition = new Vec3d(x, y, z);
            previousTickRot = new float[] {xRot, yRot};
        }
    }
}
