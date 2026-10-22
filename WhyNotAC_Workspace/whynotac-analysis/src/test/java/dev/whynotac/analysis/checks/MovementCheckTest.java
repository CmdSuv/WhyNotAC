package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.movement.MovementTracker;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.OnFoot;
import dev.whynotac.physics.PlayerState;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Replicas;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The check over a client that runs the same on-foot step and sends what {@code LocalPlayer} sends: its own
 * ticks are explained, and a client that moves, lands or sends otherwise is not. (The step itself is held to
 * the real client by the physics module's truth tests; this holds the tracking around it.)
 */
class MovementCheckTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final UUID ME = new UUID(7, 7);
    private static final int SELF = 1;
    private static final long SECOND = 1_000_000_000L;
    private static final int STONE = STATES.firstStateOf("minecraft:stone");
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final PlayerState.Keys NONE = PlayerState.Keys.NONE;
    private static final PlayerState.Keys FORWARD = new PlayerState.Keys(true, false, false, false, false, false, false);
    private static final PlayerState.Keys SPRINT = new PlayerState.Keys(true, false, false, false, false, false, true);
    private static final PlayerState.Keys SPRINT_JUMP = new PlayerState.Keys(true, false, false, false, true, false, true);
    private static final PlayerState.Keys STRAFE_SNEAK = new PlayerState.Keys(false, false, true, false, false, true, false);

    /** Blocks placed on the ground ({@link #place}), as the synthetic client holds them. */
    private final Map<List<Integer>, Integer> placed = new java.util.HashMap<>();

    /** Stone below y 64, air above, and what was placed: the replica's world, as the synthetic client sees it. */
    private final Colliders.World GROUND = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            Integer p = placed.get(List.of(x, y, z));
            return p != null ? p : y < 64 ? STONE : AIR;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return chunkX >= -2 && chunkX <= 2 && chunkZ >= -2 && chunkZ <= 2;
        }
    };

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private final Map<MovementTracker.Outcome, Integer> outcomes = new EnumMap<>(MovementTracker.Outcome.class);
    private final List<MovementTracker.Tick> ticks = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now;
    private int pingId;
    private int teleportId;

    // the synthetic client
    private PlayerState player;
    private boolean firstTick = true;
    private double sentX;
    private double sentY;
    private double sentZ;
    private float sentYRot;
    private float sentXRot;
    private boolean sentOnGround;
    private boolean sentCollision;
    private int reminder;
    private boolean sentSprinting;
    private PlayerState.Keys sentInput = NONE;
    private final ClientSpeed speed = new ClientSpeed();
    private OnFoot.Effects effects = OnFoot.Effects.NONE;
    /** The item the synthetic client uses (null: none), and what it sends from its keybinds at the start of the next tick. */
    private OnFoot.ItemUse use;
    /** Villagers without AI (they stay where they were added; added after the player, they tick after it): {x, y, z}. */
    private final List<double[]> villagers = new ArrayList<>();
    /** Whether their pushes reach the player (teams); {@code ignorePushes}: the client drops them. */
    private boolean pushedByVillagers = true;
    private boolean ignorePushes;
    private final List<ServerboundPacket> keybinds = new ArrayList<>();
    // the server: its movement speed modifiers in its own order (an Object2ObjectArrayMap, insertion order), and
    // whether it sends them again when sprinting starts or stops (as it does: the modifier set changed)
    private final List<ClientboundPacket.UpdateAttributes.Modifier> serverModifiers = new ArrayList<>();
    private boolean serverSendsSpeed;

    /** {@code Identifier}'s equality and hash: what the client's modifier table sees of an id. */
    private record Key(String namespace, String path) {
        static Key of(String id) {
            int colon = id.indexOf(':');
            return new Key(id.substring(0, colon), id.substring(colon + 1));
        }

        @Override
        public int hashCode() {
            return 31 * namespace.hashCode() + path.hashCode();
        }
    }

    /**
     * The client's movement speed attribute: base {@code 0.1F}, the modifiers multiplying the total in fastutil's
     * own table ({@code AttributeInstance}), summed in its iteration order.
     */
    private static final class ClientSpeed implements OnFoot.Speed {
        private static final String SPRINTING = "minecraft:sprinting";
        private final Object2ObjectOpenHashMap<Key, Double> total = new Object2ObjectOpenHashMap<>();

        boolean holdsSprinting() {
            return total.containsKey(Key.of(SPRINTING));
        }

        @Override
        public double value(boolean sprintModifier) {
            assertEquals(holdsSprinting(), sprintModifier, "the synthetic client's state follows its own attribute");
            double value = 0.1F;
            for (double amount : total.values()) {
                value *= 1.0 + amount;
            }
            return value;
        }

        @Override
        public OnFoot.Speed setSprinting(boolean sprinting) {
            total.remove(Key.of(SPRINTING));
            if (sprinting) {
                total.put(Key.of(SPRINTING), (double) 0.3F);
            }
            return this;
        }

        /** {@code handleUpdateAttributes}: every modifier removed, then the update's added in its order. */
        void replace(List<ClientboundPacket.UpdateAttributes.Modifier> modifiers) {
            total.clear();
            for (ClientboundPacket.UpdateAttributes.Modifier m : modifiers) {
                assertEquals(ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL, m.operation());
                total.put(Key.of(m.id()), m.amount());
            }
        }
    }

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new MovementCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas));
        MovementTracker tracker = new MovementTracker();
        registry.addListener((s, event) -> {
            MovementTracker.Tick tick = tracker.onEvent(event, replicas.world().world(s), replicas.entities().entities(s), replicas.inventory().inventory(s));
            if (tick != null) {
                ticks.add(tick);
                outcomes.merge(tick.outcome(), 1, Integer::sum);
            }
        });
        session = registry.openReplay("test");
        deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        deliver(new TimelineEvent.Bound(seq++, now, ME, "me", SELF));
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD, 0));
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                out(new ClientboundPacket.LevelChunk(x, z, chunk(OVERWORLD.sections())));
            }
        }
        // a new LocalPlayer: the client's defaults
        player = new PlayerState(0.0, 0.0, 0.0, Vec3d.ZERO, -180.0F, 0.0F, false, false, false, false, false, 0.0, false, false, false,
                PlayerState.Pose.STANDING, NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, false);
        teleport(0.5, 64.0, 0.5, 30.0F, 0.0F);
        in(new ServerboundPacket.PlayerLoaded());
    }

    // --- the synthetic client ---

    /** The server teleports the player; the client handles it before its next tick and replies. */
    private void teleport(double x, double y, double z, float yaw, float pitch) {
        int id = ++teleportId;
        out(new ClientboundPacket.PlayerPosition(id, new Vec3d(x, y, z), Vec3d.ZERO, yaw, pitch, Set.of()));
        sync();
        player = new PlayerState(x, y, z, Vec3d.ZERO, yaw, pitch, player.onGround(), player.horizontalCollision(), player.minorHorizontalCollision(),
                player.verticalCollision(), player.verticalCollisionBelow(), player.fallDistance(), player.sprinting(), player.sprintModifier(),
                player.crouching(), player.pose(), player.keys(), player.noJumpDelay(), player.sprintTriggerTime(), player.autoJumpTime(), player.support(),
                player.onGroundNoBlocks(), player.stuckSpeedMultiplier(), player.swimming(), player.eyeInWater());
        in(new ServerboundPacket.AcceptTeleportation(id));
        in(new ServerboundPacket.MovePlayer(x, y, z, yaw, pitch, true, true, false, false));
    }

    /** One client tick with these keys and this rotation; {@code cheat} moves the result before it is sent. */
    private void tick(PlayerState.Keys keys, float yRot, float xRot, Vec3d cheat) throws Colliders.Unknown {
        keybinds.forEach(this::in); // Minecraft.handleKeybinds, before the player's tick
        keybinds.clear();
        PlayerState pre = rotate(player, yRot, xRot);
        PlayerState post = OnFoot.tick(pre, keys, GROUND, new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, firstTick, speed, effects, use));
        firstTick = false;
        if (!cheat.equals(Vec3d.ZERO)) {
            post = move(post, cheat);
        }
        player = post;
        if (!keys.equals(sentInput)) {
            in(new ServerboundPacket.PlayerInput(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint()));
            sentInput = keys;
        }
        boolean sprintSent = post.sprinting() != sentSprinting;
        if (sprintSent) {
            in(new ServerboundPacket.PlayerCommand(SELF, post.sprinting() ? ServerboundPacket.PlayerCommand.Action.START_SPRINTING
                    : ServerboundPacket.PlayerCommand.Action.STOP_SPRINTING, 0));
            sentSprinting = post.sprinting();
        }
        reminder++;
        boolean position = VanillaMath.lengthSquared(post.x() - sentX, post.y() - sentY, post.z() - sentZ) > VanillaMath.square(2.0E-4) || reminder >= 20;
        boolean rotation = yRot != sentYRot || xRot != sentXRot;
        boolean flags = post.onGround() != sentOnGround || post.horizontalCollision() != sentCollision;
        if (position || rotation || flags) {
            in(new ServerboundPacket.MovePlayer(position ? post.x() : Double.NaN, position ? post.y() : Double.NaN, position ? post.z() : Double.NaN,
                    rotation ? yRot : Float.NaN, rotation ? xRot : Float.NaN, position, rotation, post.onGround(), post.horizontalCollision()));
        }
        if (position) {
            sentX = post.x();
            sentY = post.y();
            sentZ = post.z();
            reminder = 0;
        }
        if (rotation) {
            sentYRot = yRot;
            sentXRot = xRot;
        }
        sentOnGround = post.onGround();
        sentCollision = post.horizontalCollision();
        villagersPush();
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
        if (sprintSent && serverSendsSpeed) {
            // the server's setSprinting: its modifier removed, then added at the end of its set when sprinting
            serverModifiers.removeIf(m -> m.id().equals(ClientSpeed.SPRINTING));
            if (post.sprinting()) {
                serverModifiers.add(new ClientboundPacket.UpdateAttributes.Modifier(ClientSpeed.SPRINTING, 0.3F,
                        ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL));
            }
            sendSpeed(true);
        }
    }

    /** The server's movement speed update, handled before the client's next tick ({@code handled} false: ignored). */
    private void sendSpeed(boolean handled) {
        out(new ClientboundPacket.UpdateAttributes(SELF, List.of(new ClientboundPacket.UpdateAttributes.Attribute("minecraft:movement_speed", 0.1F,
                serverModifiers))));
        sync();
        if (handled) {
            speed.replace(serverModifiers);
            player = withSprintModifier(player, speed.holdsSprinting());
        }
    }

    /**
     * The server adds ({@code amplifier} 0 or more) or removes ({@code -1}) an effect the step reads; the client
     * handles it before its next tick ({@code handled} false: ignores it).
     */
    private void potion(String effect, int amplifier, boolean handled) {
        out(amplifier < 0 ? new ClientboundPacket.RemoveMobEffect(SELF, effect) : new ClientboundPacket.MobEffect(SELF, effect, amplifier, 600, 0));
        sync();
        if (handled) {
            int level = amplifier + 1;
            OnFoot.Effects e = effects;
            effects = switch (effect) {
                case "minecraft:jump_boost" -> new OnFoot.Effects(level, e.levitation(), e.slowFalling(), e.blindness(), e.dolphinsGrace(), e.weaving());
                case "minecraft:levitation" -> new OnFoot.Effects(e.jumpBoost(), level, e.slowFalling(), e.blindness(), e.dolphinsGrace(), e.weaving());
                case "minecraft:slow_falling" -> new OnFoot.Effects(e.jumpBoost(), e.levitation(), level > 0, e.blindness(), e.dolphinsGrace(), e.weaving());
                case "minecraft:blindness" -> new OnFoot.Effects(e.jumpBoost(), e.levitation(), e.slowFalling(), level > 0, e.dolphinsGrace(), e.weaving());
                case "minecraft:weaving" -> new OnFoot.Effects(e.jumpBoost(), e.levitation(), e.slowFalling(), e.blindness(), e.dolphinsGrace(), level > 0);
                default -> throw new AssertionError(effect);
            };
        }
    }

    /** The server fills the first hotbar slot (selected) and the off hand: the player's InventoryMenu, hotbar 36..44, off hand 45. */
    private void hold(String mainHand, String offHand) {
        List<Stack> items = new ArrayList<>();
        for (int i = 0; i < 46; i++) {
            items.add(Stack.EMPTY);
        }
        if (mainHand != null) {
            items.set(36, new Stack(mainHand, 1, List.of(), List.of(), null, false));
        }
        if (offHand != null) {
            items.set(45, new Stack(offHand, 1, List.of(), List.of(), null, false));
        }
        out(new ClientboundPacket.ContainerSetContent(0, 1, items, Stack.EMPTY));
        sync();
    }

    /** The server's living-entity flags (1: using, 2: the off hand), handled before the client's next tick. */
    private void livingFlags(int flags) {
        out(new ClientboundPacket.SetEntityData(SELF, List.of(new ClientboundPacket.SetEntityData.DataEntry(8, ClientboundPacket.SetEntityData.Kind.BYTE,
                flags)), 0));
        sync();
    }

    private static ServerboundPacket.UseItem useItem(Hand hand) {
        return new ServerboundPacket.UseItem(hand, 1, 30.0F, 0.0F);
    }

    private static final ServerboundPacket.PlayerAction RELEASE = new ServerboundPacket.PlayerAction(
            ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM, new BlockPosition(0, 0, 0), BlockFace.DOWN, 0);

    /** A potion effect whose attribute modifier multiplies the speed's total, as the server adds it. */
    private void effect(String effect, String modifier, double amount, boolean handled) {
        out(new ClientboundPacket.MobEffect(SELF, effect, 0, 600, 0));
        serverModifiers.add(new ClientboundPacket.UpdateAttributes.Modifier(modifier, amount, ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL));
        sendSpeed(handled);
    }

    private void villager(int id, double x, double y, double z) {
        out(new ClientboundPacket.AddEntity(id, new UUID(1, id), "minecraft:villager", x, y, z, Vec3d.ZERO, null, (byte) 0, (byte) 0, (byte) 0, 0));
        sync();
        villagers.add(new double[] {x, y, z});
    }

    /**
     * The villagers' ticks after the player's: {@code LivingEntity.pushEntities} finds the player where its box meets
     * theirs ({@code EntityType.VILLAGER}: 0.6 by 1.95), then {@code Entity.push(Entity)} adds to its motion.
     */
    private void villagersPush() {
        if (!pushedByVillagers || ignorePushes) {
            return;
        }
        for (double[] v : villagers) {
            float half = 0.6F / 2.0F;
            float height = 1.95F;
            Box b = player.box();
            if (!(b.minX() < v[0] + half && b.maxX() > v[0] - half && b.minY() < v[1] + height && b.maxY() > v[1] && b.minZ() < v[2] + half
                    && b.maxZ() > v[2] - half)) {
                continue;
            }
            double d = v[0] - player.x();
            double e = v[2] - player.z();
            double f = Math.max(Math.abs(d), Math.abs(e));
            if (f >= 0.01F) {
                f = Math.sqrt(f);
                d /= f;
                e /= f;
                double g = 1.0 / f;
                if (g > 1.0) {
                    g = 1.0;
                }
                d *= g;
                e *= g;
                d *= 0.05F;
                e *= 0.05F;
                Vec3d m = player.deltaMovement();
                player = withDelta(player, new Vec3d(m.x() + -d, m.y() + 0.0, m.z() + -e));
            }
        }
    }

    private void ticks(int count, PlayerState.Keys keys, float yRot) throws Colliders.Unknown {
        for (int i = 0; i < count; i++) {
            tick(keys, yRot, 0.0F, Vec3d.ZERO);
        }
    }

    private static PlayerState rotate(PlayerState p, float yRot, float xRot) {
        return new PlayerState(p.x(), p.y(), p.z(), p.deltaMovement(), yRot, xRot, p.onGround(), p.horizontalCollision(), p.minorHorizontalCollision(),
                p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), p.sprintModifier(), p.crouching(), p.pose(), p.keys(),
                p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(), p.onGroundNoBlocks(), p.stuckSpeedMultiplier(),
                p.swimming(), p.eyeInWater());
    }

    private static PlayerState move(PlayerState p, Vec3d by) {
        return new PlayerState(p.x() + by.x(), p.y() + by.y(), p.z() + by.z(), p.deltaMovement(), p.yRot(), p.xRot(), p.onGround(),
                p.horizontalCollision(), p.minorHorizontalCollision(), p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(),
                p.sprintModifier(), p.crouching(), p.pose(), p.keys(), p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(),
                p.onGroundNoBlocks(), p.stuckSpeedMultiplier(), p.swimming(), p.eyeInWater());
    }

    private static PlayerState withDelta(PlayerState p, Vec3d delta) {
        return new PlayerState(p.x(), p.y(), p.z(), delta, p.yRot(), p.xRot(), p.onGround(), p.horizontalCollision(), p.minorHorizontalCollision(),
                p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), p.sprintModifier(), p.crouching(), p.pose(), p.keys(),
                p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(), p.onGroundNoBlocks(), p.stuckSpeedMultiplier(),
                p.swimming(), p.eyeInWater());
    }

    private static PlayerState withSprintModifier(PlayerState p, boolean sprintModifier) {
        return new PlayerState(p.x(), p.y(), p.z(), p.deltaMovement(), p.yRot(), p.xRot(), p.onGround(), p.horizontalCollision(), p.minorHorizontalCollision(),
                p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), sprintModifier, p.crouching(), p.pose(), p.keys(),
                p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(), p.onGroundNoBlocks(), p.stuckSpeedMultiplier(),
                p.swimming(), p.eyeInWater());
    }

    /** The server sets a block; the client handles it before its next tick. */
    private void place(int x, int y, int z, int state) {
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(x, y, z), state));
        sync();
        placed.put(List.of(x, y, z), state);
    }

    // --- timeline helpers ---

    private void deliver(TimelineEvent event) {
        session.deliver(event);
    }

    private void out(ClientboundPacket packet) {
        deliver(new TimelineEvent.Outbound(seq++, now, packet));
    }

    private void in(ServerboundPacket packet) {
        deliver(new TimelineEvent.Inbound(seq++, now, 0, packet));
    }

    /** A transaction the client answers at once: everything sent before it was handled before its next tick. */
    private void sync() {
        int id = ping();
        acknowledge(id);
    }

    private int ping() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        return id;
    }

    private void acknowledge(int id) {
        in(new ServerboundPacket.Pong(id));
        deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
    }

    /** A chunk of {@code sections} sections: stone up to y 64, air above. */
    private static byte[] chunk(int sections) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0); // no heightmaps
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < sections; i++) {
            boolean solid = -64 + i * 16 < 64;
            data.write(solid ? 0x10 : 0);
            data.write(0); // non-empty count (short)
            data.write(0); // single-valued palette
            varInt(data, solid ? STONE : AIR);
            data.write(0); // biomes: one value
            varInt(data, 0);
        }
        varInt(body, data.size());
        body.writeBytes(data.toByteArray());
        varInt(body, 0); // block entities
        return body.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private int count(MovementTracker.Outcome outcome) {
        return outcomes.getOrDefault(outcome, 0);
    }

    private void walkAround() throws Colliders.Unknown {
        ticks(5, NONE, 30.0F);
        ticks(30, FORWARD, 30.0F);
        ticks(10, SPRINT, 31.5F);
        ticks(25, SPRINT_JUMP, 33.0F);
        ticks(15, NONE, 33.0F);
        ticks(20, STRAFE_SNEAK, 120.0F);
        ticks(20, NONE, 120.0F);
    }

    // --- tests ---

    @Test
    void aVanillaClientsTicksAreExplained() throws Exception {
        walkAround();
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED), String.valueOf(ticks));
        assertEquals(0, count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
        assertEquals(125, count(MovementTracker.Outcome.EXPLAINED));
    }

    @Test
    void movingAHundredthOfABlockFurtherIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        ticks(20, FORWARD, 30.0F);
        tick(FORWARD, 30.0F, 0.0F, new Vec3d(0.0, 0.0, 0.01));
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        Evidence e = evidence.get(0);
        assertEquals(MovementCheck.ID, e.check());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertTrue(e.details().get("offset").startsWith("0.000000,0.000000,0.01"), e.details().toString());
    }

    @Test
    void hoveringInTheAirIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        // jump, then stay at the top: the client cancels gravity
        tick(SPRINT_JUMP, 30.0F, 0.0F, Vec3d.ZERO);
        for (int i = 0; i < 6; i++) {
            tick(FORWARD, 30.0F, 0.0F, new Vec3d(0.0, -player.deltaMovement().y() / 0.98, 0.0));
        }
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void knockbackTheClientHandledBeforeItsTickIsFollowed() throws Exception {
        ticks(10, FORWARD, 30.0F);
        Vec3d knockback = new Vec3d(0.4, 0.36, -0.2);
        out(ClientboundPacket.SetEntityMotion.received(SELF, knockback));
        sync();
        player = withDelta(player, knockback);
        ticks(30, FORWARD, 30.0F);
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void knockbackInATickTheTransactionsCannotPlaceIsTriedInEach() throws Exception {
        ticks(10, FORWARD, 30.0F);
        Vec3d knockback = new Vec3d(-0.3, 0.2, 0.5);
        int before = ping();
        out(ClientboundPacket.SetEntityMotion.received(SELF, knockback));
        int after = ping();
        acknowledge(before);
        // the client handles the knockback, then ticks before it handles the second transaction
        player = withDelta(player, knockback);
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        acknowledge(after);
        ticks(30, FORWARD, 30.0F);
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void knockbackTheClientDidNotApplyIsAFinding() throws Exception {
        ticks(10, FORWARD, 30.0F);
        out(ClientboundPacket.SetEntityMotion.received(SELF, new Vec3d(0.0, 0.42, 0.0)));
        sync();
        ticks(5, FORWARD, 30.0F); // the client ignores it
        assertEquals(1, evidence.size(), String.valueOf(ticks));
    }

    @Test
    void aPlayerStandingStillIsTrackedAgainAfterAnUnmodelledTick() throws Exception {
        ticks(10, FORWARD, 30.0F);
        // a block used far away: not modelled, no claim
        in(new ServerboundPacket.UseItemOn(dev.whynotac.protocol.packet.Hand.MAIN_HAND, new dev.whynotac.protocol.packet.BlockPosition(-40, 63, -40),
                dev.whynotac.protocol.packet.BlockFace.UP, new Vec3d(-39.5, 64.0, -39.5), false, false, 1));
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(1, count(MovementTracker.Outcome.NO_CLAIM));
        ticks(50, NONE, 30.0F); // standing still: two identical position reminders, 20 ticks apart
        int anchoredAt = ticks.size();
        ticks(40, SPRINT_JUMP, 60.0F);
        assertEquals(List.of(), evidence, String.valueOf(ticks.subList(10, 45)));
        assertTrue(ticks.subList(anchoredAt, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(anchoredAt - 30, ticks.size())));
        // and a cheat is caught again
        tick(SPRINT_JUMP, 60.0F, 0.0F, new Vec3d(0.05, 0.0, 0.0));
        assertEquals(1, evidence.size());
    }

    @Test
    void speedEffectsWithSprintingAreSummedInTheClientsOwnOrder() throws Exception {
        serverSendsSpeed = true;
        ticks(5, NONE, 30.0F);
        // speed II and slowness I: with sprinting, three modifiers multiplying the total, in the client's hash order
        effect("minecraft:speed", "minecraft:effect.speed", 0.4000000059604645, true);
        effect("minecraft:slowness", "minecraft:effect.slowness", -0.15000000596046448, true);
        walkAround();
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED), String.valueOf(ticks));
        assertEquals(0, count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
        assertEquals(130, count(MovementTracker.Outcome.EXPLAINED));
    }

    @Test
    void movingAsIfASlownessModifierWereNotThereIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        effect("minecraft:slowness", "minecraft:effect.slowness", -0.15000000596046448, false); // the client keeps its old speed
        ticks(20, FORWARD, 30.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void jumpBoostSlowFallingAndLevitationAreFollowed() throws Exception {
        ticks(5, NONE, 30.0F);
        potion("minecraft:jump_boost", 1, true);
        ticks(25, SPRINT_JUMP, 30.0F);
        ticks(15, NONE, 30.0F);
        potion("minecraft:slow_falling", 0, true);
        ticks(30, SPRINT_JUMP, 45.0F);
        ticks(20, NONE, 45.0F);
        potion("minecraft:jump_boost", -1, true);
        ticks(20, FORWARD, 45.0F);
        potion("minecraft:slow_falling", -1, true);
        potion("minecraft:levitation", 1, true);
        ticks(20, NONE, 45.0F);
        potion("minecraft:levitation", -1, true);
        ticks(40, FORWARD, 45.0F);
        assertEquals(List.of(), evidence);
        assertEquals(175, count(MovementTracker.Outcome.EXPLAINED), String.valueOf(ticks));
        assertTrue(player.onGround());
    }

    @Test
    void jumpingHigherThanTheEffectsAllowIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        effects = new OnFoot.Effects(3, 0, false, false); // jump boost the server never sent
        tick(SPRINT_JUMP, 30.0F, 0.0F, Vec3d.ZERO);
        ticks(3, FORWARD, 30.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void sprintingWhileBlindIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        potion("minecraft:blindness", 0, true);
        ticks(20, SPRINT, 30.0F); // the client cannot sprint
        assertTrue(!player.sprinting());
        assertEquals(List.of(), evidence, String.valueOf(ticks));
        effects = OnFoot.Effects.NONE; // the client drops the effect on its own
        ticks(10, SPRINT, 30.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void eatingSlowsThePlayerAndIsFollowedToItsEnd() throws Exception {
        hold("minecraft:golden_apple", "minecraft:shield");
        ticks(5, NONE, 30.0F);
        ticks(15, SPRINT, 30.0F);
        keybinds.add(useItem(Hand.MAIN_HAND));
        use = OnFoot.ItemUse.DEFAULT; // the client starts eating: slowed, and the sprint goes on (shouldStopRunSprinting does not ask)
        tick(SPRINT, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(1); // the server's use, echoed
        ticks(31, SPRINT, 30.0F);
        assertTrue(player.sprinting());
        // the server finishes it: the entity event (completeUsingItem on the client), then the flags
        out(new ClientboundPacket.EntityEvent(SELF, ClientboundPacket.EntityEvent.FINISH_USING_ITEM));
        use = null;
        livingFlags(0);
        ticks(20, SPRINT, 30.0F);
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
        assertEquals(72, count(MovementTracker.Outcome.EXPLAINED));
    }

    @Test
    void walkingAtFullSpeedWhileEatingIsAFinding() throws Exception {
        hold("minecraft:golden_apple", null);
        ticks(5, NONE, 30.0F);
        ticks(10, FORWARD, 30.0F);
        keybinds.add(useItem(Hand.MAIN_HAND)); // the client uses the apple but moves as if it did not
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(1);
        ticks(10, FORWARD, 30.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void blockingWithTheShieldAfterTheSwordPassesIsFollowedAndReleased() throws Exception {
        hold("minecraft:diamond_sword", "minecraft:shield");
        ticks(5, NONE, 30.0F);
        ticks(10, FORWARD, 30.0F);
        // Minecraft.startUseItem: the sword has no use (PASS), so the off hand's shield is used
        keybinds.add(useItem(Hand.MAIN_HAND));
        keybinds.add(useItem(Hand.OFF_HAND));
        use = OnFoot.ItemUse.DEFAULT;
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(3);
        ticks(25, STRAFE_SNEAK, 40.0F);
        ticks(10, FORWARD, 40.0F);
        keybinds.add(RELEASE);
        use = null;
        tick(FORWARD, 40.0F, 0.0F, Vec3d.ZERO);
        livingFlags(0);
        ticks(20, SPRINT, 40.0F);
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
        assertEquals(72, count(MovementTracker.Outcome.EXPLAINED));
    }

    @Test
    void aSpearInUseLetsThePlayerSprintAtFullSpeed() throws Exception {
        hold("minecraft:iron_spear", null);
        ticks(5, NONE, 30.0F);
        keybinds.add(useItem(Hand.MAIN_HAND));
        use = new OnFoot.ItemUse(true, 1.0F); // a spear's use_effects: sprinting allowed, no slowdown
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(1);
        ticks(10, FORWARD, 30.0F);
        ticks(20, SPRINT_JUMP, 30.0F);
        assertTrue(player.sprinting());
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void aPlayerBlockingSinceBeforeTheAnchorIsSlowedWhenItWalks() throws Exception {
        hold("minecraft:diamond_sword", "minecraft:shield");
        ticks(10, FORWARD, 30.0F);
        // a block used far away: no claim, the set is dropped
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(-40, 63, -40), BlockFace.UP, new Vec3d(-39.5, 64.0, -39.5), false, false, 1));
        tick(NONE, 30.0F, 0.0F, Vec3d.ZERO);
        // untracked, the client raises its shield and stands; the anchor cannot see the use
        keybinds.add(useItem(Hand.OFF_HAND));
        use = OnFoot.ItemUse.DEFAULT;
        tick(NONE, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(3);
        ticks(50, NONE, 30.0F);
        int anchoredAt = ticks.size();
        ticks(30, FORWARD, 30.0F); // still blocking
        assertEquals(List.of(), evidence, String.valueOf(ticks.subList(anchoredAt - 5, ticks.size())));
        assertTrue(ticks.subList(anchoredAt, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(anchoredAt - 30, ticks.size())));
    }

    @Test
    void aDoubleTappedSprintIsExplainedWhateverTheWindowOption() throws Exception {
        ticks(5, NONE, 30.0F);
        ticks(1, FORWARD, 30.0F);
        ticks(2, NONE, 30.0F);
        ticks(30, FORWARD, 30.0F); // the second press within the default window starts sprinting
        assertTrue(player.sprinting());
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void aVillagerPushingThePlayerIsFollowed() throws Exception {
        ticks(5, NONE, 30.0F);
        villager(100, 0.9, 64.0, 0.6); // its box meets the player's: it pushes the player away, tick by tick
        ticks(20, NONE, 30.0F);
        assertTrue(player.x() + 0.3 <= 0.6, "pushed clear of the villager: " + player);
        villager(101, player.x() - 0.5, 64.0, player.z() - 0.3); // a second one on the other side, in the way
        ticks(10, FORWARD, 200.0F);
        walkAround();
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void standingAgainstAPushIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        ignorePushes = true; // the client drops the villager's push
        villager(100, 0.9, 64.0, 0.6);
        ticks(5, NONE, 30.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
        assertEquals(MovementCheck.ID, evidence.get(0).check());
    }

    @Test
    void aTeamThatNeverCollidesKeepsThePlayerFromBeingPushed() throws Exception {
        out(new ClientboundPacket.SetPlayerTeam("calm", ClientboundPacket.SetPlayerTeam.ADD, "NEVER", List.of("me")));
        sync();
        pushedByVillagers = false; // EntitySelector.pushableBy: the player's rule is NEVER
        ticks(5, NONE, 30.0F);
        villager(100, 0.9, 64.0, 0.6);
        ticks(20, NONE, 30.0F);
        walkAround();
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void aTeamChangeTheClientMayNotHaveHandledEndsTheClaimOnlyWhereItDecides() throws Exception {
        ticks(5, NONE, 30.0F);
        out(new ClientboundPacket.SetPlayerTeam("calm", ClientboundPacket.SetPlayerTeam.ADD, "NEVER", List.of("me")));
        int unanswered = ping(); // not answered yet: the client may have handled the team or not
        ticks(3, NONE, 30.0F);
        assertEquals(0, count(MovementTracker.Outcome.NO_CLAIM), "no entity near: the teams decide nothing");
        acknowledge(unanswered);
        out(new ClientboundPacket.SetPlayerTeam("calm", ClientboundPacket.SetPlayerTeam.LEAVE, null, List.of("me")));
        sync();
        villager(100, 0.9, 64.0, 0.6);
        ticks(2, NONE, 30.0F); // pushed
        out(new ClientboundPacket.SetPlayerTeam("calm", ClientboundPacket.SetPlayerTeam.JOIN, null, List.of("me")));
        unanswered = ping();
        pushedByVillagers = false; // the client did handle it
        ticks(2, NONE, 30.0F);
        // the push at the end of the player's tick may or may not land: the next tick starts untracked
        assertTrue(ticks.stream().anyMatch(t -> t.outcome() == MovementTracker.Outcome.UNTRACKED && t.reason().contains("teams")), String.valueOf(ticks));
        acknowledge(unanswered);
        assertEquals(List.of(), evidence);
    }

    @Test
    void aBlockBelowThatMayStillChangeIsFollowedWhereEachStateMovesThePlayerAlike() throws Exception {
        BlockPosition below = new BlockPosition(0, 63, 0);
        out(new ClientboundPacket.BlockUpdate(below, STATES.firstStateOf("minecraft:dirt")));
        sync();
        ticks(5, NONE, 30.0F);
        int from = ticks.size();
        // grass spreads to the dirt below the player: the client may hold either while the change is in flight
        out(new ClientboundPacket.BlockUpdate(below, STATES.firstStateOf("minecraft:grass_block")));
        int unanswered = ping();
        ticks(10, NONE, 30.0F);
        acknowledge(unanswered);
        ticks(5, NONE, 30.0F);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
        // a change that would let the player fall: no claim while the client may not hold it
        out(new ClientboundPacket.BlockUpdate(below, AIR));
        ping();
        ticks(2, NONE, 30.0F);
        assertTrue(ticks.subList(ticks.size() - 2, ticks.size()).stream()
                .allMatch(t -> t.outcome() != MovementTracker.Outcome.EXPLAINED && t.reason().contains("unknown block at 0 63 0")), String.valueOf(ticks));
        assertEquals(List.of(), evidence);
    }

    @Test
    void wadingSwimmingAndSinkingInAPoolAreFollowed() throws Exception {
        int water = STATES.firstStateOf("minecraft:water"); // level=0: a source
        for (int x = 1; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 61; y <= 63; y++) {
                    place(x, y, z, water);
                }
            }
        }
        ticks(5, NONE, -90.0F);
        int from = ticks.size();
        ticks(20, FORWARD, -90.0F); // walks east into the pool and sinks
        ticks(15, SPRINT, -90.0F); // under water: swims
        ticks(10, new PlayerState.Keys(false, false, false, false, true, false, false), -90.0F); // rises
        ticks(10, new PlayerState.Keys(false, false, false, false, false, true, false), -90.0F); // sinks with shift
        assertTrue(player.eyeInWater() || player.y() < 64.0, String.valueOf(player));
        assertEquals(List.of(), evidence, String.valueOf(ticks.subList(from, ticks.size())));
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
        // floating in place without the water's pull
        tick(NONE, -90.0F, 0.0F, new Vec3d(0.0, 0.03, 0.0));
        assertEquals(1, evidence.size(), String.valueOf(ticks.subList(ticks.size() - 3, ticks.size())));
    }

    @Test
    void cobwebsBerryBushesAndABubbleColumnAreFollowed() throws Exception {
        int web = STATES.firstStateOf("minecraft:cobweb");
        int bush = STATES.firstStateOf("minecraft:sweet_berry_bush"); // age=0
        int column = -1;
        for (int s = STATES.firstStateOf("minecraft:bubble_column"); STATES.block(s).equals("minecraft:bubble_column"); s++) {
            column = STATES.properties(s).equals("drag=false") ? s : column;
        }
        for (int z = -1; z <= 1; z++) {
            place(3, 64, z, web);
            place(3, 65, z, web);
            place(7, 64, z, bush);
        }
        // a shaft four deep in the ground (x 12, z 0), rising over soul sand
        place(12, 59, 0, STATES.firstStateOf("minecraft:soul_sand"));
        for (int y = 60; y <= 63; y++) {
            place(12, y, 0, column);
        }
        ticks(5, NONE, -90.0F);
        int from = ticks.size();
        boolean held = false;
        for (int i = 0; i < 120; i++) { // walks east through the cobwebs, then the bushes
            tick(FORWARD, -90.0F, 0.0F, Vec3d.ZERO);
            held |= player.stuckSpeedMultiplier().lengthSqr() > 0.0;
        }
        assertTrue(held && player.x() > 8.0, String.valueOf(player));
        teleport(12.5, 60.0, 0.5, -90.0F, 0.0F);
        double top = 0.0;
        for (int i = 0; i < 40; i++) { // the column lifts the player out of its open top; it falls back in
            tick(NONE, -90.0F, 0.0F, Vec3d.ZERO);
            top = Math.max(top, player.y());
        }
        assertTrue(top > 64.5, String.valueOf(top));
        assertEquals(List.of(), evidence, String.valueOf(ticks.subList(from, ticks.size())));
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
        // walking through a cobweb as if it were not there
        teleport(2.5, 64.0, 0.5, -90.0F, 0.0F);
        ticks(5, FORWARD, -90.0F);
        tick(FORWARD, -90.0F, 0.0F, new Vec3d(0.1, 0.0, 0.0));
        assertEquals(1, evidence.size(), String.valueOf(ticks.subList(ticks.size() - 3, ticks.size())));
    }
}
