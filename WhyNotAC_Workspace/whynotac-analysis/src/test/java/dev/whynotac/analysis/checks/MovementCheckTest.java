package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.movement.MovementTracker;
import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The check over a client that runs the same on-foot step and sends what {@code LocalPlayer} sends: its own
 * ticks are explained, and a client that moves, lands or sends otherwise is not. (The step itself is held to
 * the real client by the physics module's truth tests; this holds the tracking around it.)
 */
class MovementCheckTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);
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
    private static final PlayerState.Keys JUMP = new PlayerState.Keys(false, false, false, false, true, false, false);

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

        @Override
        public Boolean fastLava() {
            return OVERWORLD.fastLava();
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
    /** The synthetic client wears an elytra that can glide, and whether it tells the server when it starts. */
    private boolean elytra;
    private boolean sendsGlideStart = true;
    /** The synthetic client's CPU: its {@code Math.cos}. */
    private PlatformCos platform = PlatformCos.X86_64;
    /** The item the synthetic client uses (null: none), and what it sends from its keybinds at the start of the next tick. */
    private OnFoot.ItemUse use;
    /**
     * Villagers without AI (they stay where they were added; added after the player, they tick after it): {x, y, z}, or
     * {x, y, z, width, height} for another living entity that stands still so (a horse before its mount).
     */
    private final List<double[]> villagers = new ArrayList<>();
    /** Firework rockets attached to the player, as the client holds them (added after it: they tick after it). */
    private int rockets;
    /**
     * The synthetic client's world border at its move (null: far), and a lerp it follows ({centre x, centre z, from, to,
     * ticks}) with the steps its level took since.
     */
    private Border border;
    private double[] borderLerp;
    private int borderSteps;
    /** The client collides with the size after its level's last step, not before it. */
    private boolean borderAhead;
    /** The synthetic client wears leather boots (its feet slot, as the server set it unless the client lies). */
    private boolean leatherBoots;
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
     * The client's movement speed attribute: base {@code 0.1F}, the modifiers adding to it and those multiplying the
     * total each in fastutil's own table ({@code AttributeInstance}), summed in its iteration order.
     */
    private static final class ClientSpeed implements OnFoot.Speed {
        private static final String SPRINTING = "minecraft:sprinting";
        private final Object2ObjectOpenHashMap<Key, Double> added = new Object2ObjectOpenHashMap<>();
        private final Object2ObjectOpenHashMap<Key, Double> total = new Object2ObjectOpenHashMap<>();

        boolean holdsSprinting() {
            return total.containsKey(Key.of(SPRINTING));
        }

        @Override
        public double value(boolean sprintModifier) {
            assertEquals(holdsSprinting(), sprintModifier, "the synthetic client's state follows its own attribute");
            double value = 0.1F;
            for (double amount : added.values()) {
                value += amount;
            }
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
            added.clear();
            total.clear();
            for (ClientboundPacket.UpdateAttributes.Modifier m : modifiers) {
                switch (m.operation()) {
                    case ADD_VALUE -> added.put(Key.of(m.id()), m.amount());
                    case ADD_MULTIPLIED_TOTAL -> total.put(Key.of(m.id()), m.amount());
                    default -> throw new AssertionError(m);
                }
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
        out(VanillaTagsPacket.packet()); // the configuration phase
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
                player.onGroundNoBlocks(), player.stuckSpeedMultiplier(), player.swimming(), player.eyeInWater(), player.fallFlying(),
                player.inPowderSnow());
        in(new ServerboundPacket.AcceptTeleportation(id));
        in(new ServerboundPacket.MovePlayer(x, y, z, yaw, pitch, true, true, false, false));
    }

    /** One client tick with these keys and this rotation; {@code cheat} moves the result before it is sent. */
    private void tick(PlayerState.Keys keys, float yRot, float xRot, Vec3d cheat) throws Colliders.Unknown {
        keybinds.forEach(this::in); // Minecraft.handleKeybinds, before the player's tick
        keybinds.clear();
        PlayerState pre = rotate(player, yRot, xRot);
        OnFoot.Step step = OnFoot.step(pre, keys, GROUND, new OnFoot.Conditions(true, OnFoot.DEFAULT_SPRINT_WINDOW, firstTick, speed, effects, use,
                elytra, platform, border, leatherBoots));
        PlayerState post = step.state();
        firstTick = false;
        if (step.startedGliding() && sendsGlideStart) {
            in(new ServerboundPacket.PlayerCommand(SELF, ServerboundPacket.PlayerCommand.Action.START_FALL_FLYING, 0)); // LocalPlayer.aiStep
        }
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
        rocketsBoost();
        borderStep();
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

    /** The server sets the feet armor slot (the InventoryMenu's slot 8), which the client handles before its next tick. */
    private void wear(String feet) {
        out(new ClientboundPacket.ContainerSetSlot(0, 1, 8, feet == null ? Stack.EMPTY : new Stack(feet, 1, List.of(), List.of(), null, false)));
        sync();
        leatherBoots = "minecraft:leather_boots".equals(feet);
    }

    /** The server's living-entity flags (1: using, 2: the off hand), handled before the client's next tick. */
    private void livingFlags(int flags) {
        out(new ClientboundPacket.SetEntityData(SELF, List.of(new ClientboundPacket.SetEntityData.DataEntry(8, ClientboundPacket.SetEntityData.Kind.BYTE,
                flags)), 0));
        sync();
    }

    /** The server's shared flags (sprinting, gliding) as the client holds them, handled before its next tick. */
    private void sharedFlags(boolean gliding) {
        out(new ClientboundPacket.SetEntityData(SELF, List.of(new ClientboundPacket.SetEntityData.DataEntry(0, ClientboundPacket.SetEntityData.Kind.BYTE,
                (player.sprinting() ? 1 << 3 : 0) | (gliding ? 1 << 7 : 0))), 0));
        sync();
        player = player.withFallFlying(gliding);
    }

    /**
     * From high up, a jump press starts a glide that turns {@code turn} degrees a tick, looking {@code pitch(i)} down in
     * its tick {@code i} (up when negative), until the player lands; the server echoes the start, then ends the glide the
     * tick after the landing.
     */
    private void glide(float turn, java.util.function.IntToDoubleFunction pitch) throws Colliders.Unknown {
        startGlide();
        float yaw = -90.0F;
        for (int i = 0; i < 400 && !player.onGround(); i++) {
            tick(NONE, yaw -= turn, (float) pitch.applyAsDouble(i), Vec3d.ZERO);
        }
        assertTrue(player.onGround() && player.fallFlying(), String.valueOf(player));
        land(yaw);
    }

    /** A jump press in the air over the ground, facing east, starts the glide; the server echoes it a tick later. */
    private void startGlide() throws Colliders.Unknown {
        elytra = true;
        teleport(0.5, 100.0, 16.5, -90.0F, 0.0F);
        ticks(5, NONE, -90.0F);
        tick(JUMP, -90.0F, 10.0F, Vec3d.ZERO);
        assertTrue(player.fallFlying(), String.valueOf(player));
        tick(NONE, -90.0F, 10.0F, Vec3d.ZERO);
        sharedFlags(true); // the server's echo of tryToStartFallFlying
    }

    /** On the ground, gliding still: a tick sliding on, then the server ends the glide and the player stands. */
    private void land(float yaw) throws Colliders.Unknown {
        tick(NONE, yaw, 0.0F, Vec3d.ZERO);
        sharedFlags(false); // LivingEntity.updateFallFlying on the server: it cannot glide on the ground
        ticks(20, NONE, yaw);
        assertFalse(player.fallFlying());
        assertEquals(PlayerState.Pose.STANDING, player.pose());
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
            float half = (v.length > 3 ? (float) v[3] : 0.6F) / 2.0F;
            float height = v.length > 3 ? (float) v[4] : 1.95F;
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

    /**
     * {@code FireworkRocketEntity.tick} for each rocket attached to the player, after its tick: a gliding player is
     * boosted toward its look ({@code getLookAngle}).
     */
    private void rocketsBoost() {
        for (int i = 0; i < rockets && player.fallFlying(); i++) {
            Vec3d look = Vec3d.viewVector(player.xRot(), player.yRot());
            Vec3d d = player.deltaMovement();
            player = withDelta(player, new Vec3d(d.x() + (look.x() * 0.1 + (look.x() * 1.5 - d.x()) * 0.5),
                    d.y() + (look.y() * 0.1 + (look.y() * 1.5 - d.y()) * 0.5), d.z() + (look.z() * 0.1 + (look.z() * 1.5 - d.z()) * 0.5)));
        }
    }

    /** The server sets a static world border, which the client processes before its next tick. */
    private void staticBorder(double centerX, double centerZ, double size) {
        out(new ClientboundPacket.InitializeBorder(centerX, centerZ, size, size, 0L, 29999984));
        sync();
        border = bounds(centerX, centerZ, size);
        borderLerp = null;
    }

    /** The server sets a border moving from {@code from} to {@code to} over {@code time} ticks. */
    private void lerpingBorder(double centerX, double centerZ, double from, double to, long time) {
        out(new ClientboundPacket.InitializeBorder(centerX, centerZ, from, to, time, 29999984));
        sync();
        border = bounds(centerX, centerZ, from);
        borderLerp = new double[] {centerX, centerZ, from, to, time};
        borderSteps = 0;
    }

    private static Border bounds(double centerX, double centerZ, double size) {
        return new Border(centerX - size / 2.0, centerZ - size / 2.0, centerX + size / 2.0, centerZ + size / 2.0);
    }

    /**
     * {@code ClientLevel.tick}, after the entities: a lerp's step. The collision reads the size before the last step (the
     * start's for the first), the target once all steps are taken; step {@code k}'s size is {@code from + k / ticks * (to - from)}.
     */
    private void borderStep() {
        if (borderLerp == null) {
            return;
        }
        borderSteps++;
        double from = borderLerp[2];
        double to = borderLerp[3];
        double time = borderLerp[4];
        int step = borderAhead ? borderSteps : borderSteps - 1;
        border = bounds(borderLerp[0], borderLerp[1], borderSteps >= time ? to : from + step / time * (to - from));
    }

    /** The server adds a firework rocket at the player, attached to {@code target} (the server's pairing: entity and data). */
    private void rocket(int id, int target) {
        out(new ClientboundPacket.AddEntity(id, new UUID(2, id), "minecraft:firework_rocket", player.x(), player.y(), player.z(), Vec3d.ZERO, null,
                (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.SetEntityData(id, List.of(new ClientboundPacket.SetEntityData.DataEntry(9,
                ClientboundPacket.SetEntityData.Kind.OPTIONAL_UNSIGNED_INT, target)), 0));
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
                p.swimming(), p.eyeInWater(), p.fallFlying(), p.inPowderSnow());
    }

    private static PlayerState move(PlayerState p, Vec3d by) {
        return new PlayerState(p.x() + by.x(), p.y() + by.y(), p.z() + by.z(), p.deltaMovement(), p.yRot(), p.xRot(), p.onGround(),
                p.horizontalCollision(), p.minorHorizontalCollision(), p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(),
                p.sprintModifier(), p.crouching(), p.pose(), p.keys(), p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(),
                p.onGroundNoBlocks(), p.stuckSpeedMultiplier(), p.swimming(), p.eyeInWater(), p.fallFlying(), p.inPowderSnow());
    }

    private static PlayerState withDelta(PlayerState p, Vec3d delta) {
        return new PlayerState(p.x(), p.y(), p.z(), delta, p.yRot(), p.xRot(), p.onGround(), p.horizontalCollision(), p.minorHorizontalCollision(),
                p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), p.sprintModifier(), p.crouching(), p.pose(), p.keys(),
                p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(), p.onGroundNoBlocks(), p.stuckSpeedMultiplier(),
                p.swimming(), p.eyeInWater(), p.fallFlying(), p.inPowderSnow());
    }

    private static PlayerState withSprintModifier(PlayerState p, boolean sprintModifier) {
        return new PlayerState(p.x(), p.y(), p.z(), p.deltaMovement(), p.yRot(), p.xRot(), p.onGround(), p.horizontalCollision(), p.minorHorizontalCollision(),
                p.verticalCollision(), p.verticalCollisionBelow(), p.fallDistance(), p.sprinting(), sprintModifier, p.crouching(), p.pose(), p.keys(),
                p.noJumpDelay(), p.sprintTriggerTime(), p.autoJumpTime(), p.support(), p.onGroundNoBlocks(), p.stuckSpeedMultiplier(),
                p.swimming(), p.eyeInWater(), p.fallFlying(), p.inPowderSnow());
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

    // --- a boat the player steers ---

    private static final int BOAT = 40;
    /** The synthetic client's boat, and the keys its next tick steers by (AbstractBoat.setInput). */
    private BoatState boat;
    private Boat.Input boatInput = Boat.Input.NONE;

    /** An oak boat put on the stone at (x, 64, z), its ground flag set by a sync; the player ticks a few times on foot. */
    private void boatOnGround(double x, double z) throws Colliders.Unknown {
        out(ClientboundPacket.AddEntity.received(BOAT, new UUID(4, 4), "minecraft:oak_boat", x, 64.0, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.EntityPositionSync(BOAT, new Vec3d(x, 64.0, z), Vec3d.ZERO, 0.0F, 0.0F, true));
        sync();
        ticks(4, NONE, 30.0F);
    }

    /** The server puts the player on the boat; the client handles it before its next tick, the boat then as it held it. */
    private void mount(double x, double z) throws Colliders.Unknown {
        out(new ClientboundPacket.SetPassengers(BOAT, new int[] {SELF}));
        sync();
        boat = new BoatState(x, 64.0, z, Vec3d.ZERO, 0.0F, 0.0F, 0.0F, Boat.status(x, 64.0, z, GROUND), 0.0, 0.0, true, false, false, false,
                Boat.supportingBlock(x, 64.0, z, GROUND), false, Vec3d.ZERO);
    }

    /** One ride tick: the boat's (PaddleBoat), then the passenger's (its input, its rotation, the vehicle's move moved by {@code cheat}). */
    private void rideTick(PlayerState.Keys keys, Vec3d cheat) throws Colliders.Unknown {
        boat = Boat.tick(boat, boatInput, GROUND, new Boat.Conditions(false, null, List.of()));
        boolean[] paddles = Boat.paddles(boatInput);
        in(new ServerboundPacket.PaddleBoat(paddles[0], paddles[1]));
        if (!keys.equals(sentInput)) {
            in(new ServerboundPacket.PlayerInput(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint()));
            sentInput = keys;
        }
        in(new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 30.0F, 0.0F, false, true, false, false));
        BoatState.Interpolation left = boat.interpolation(); // ServerboundMoveVehiclePacket.fromEntity: its target while it interpolates
        in(new ServerboundPacket.MoveVehicle((left != null ? left.target() : new Vec3d(boat.x(), boat.y(), boat.z())).add(cheat),
                left != null ? left.targetYRot() : boat.yRot(), left != null ? left.targetXRot() : boat.xRot(), boat.onGround()));
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
        boatInput = new Boat.Input(keys.left(), keys.right(), keys.forward(), keys.backward()); // LocalPlayer.rideTick
    }

    // --- a horse the player rides ---

    private static final int HORSE = 41;
    private static final Horse.Conditions HORSE_CONDITIONS = new Horse.Conditions(0.3F, 0.8, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, true, 1.3964844F,
            1.6F, null, List.of());
    /** The synthetic client's horse, the keys its rider's last tick had, and a riding jump released in that tick (its power, or -1). */
    private HorseState horse;
    private PlayerState.Keys riderKeys = NONE;
    private int released = -1;

    /** A tame, saddled horse (speed 0.3, jump strength 0.8) put on the stone at (x, 64, z); the player ticks a few times on foot. */
    private void horseOnGround(double x, double z) throws Colliders.Unknown {
        out(ClientboundPacket.AddEntity.received(HORSE, new UUID(5, 5), "minecraft:horse", x, 64.0, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.SetEntityData(HORSE, List.of(new ClientboundPacket.SetEntityData.DataEntry(17,
                ClientboundPacket.SetEntityData.Kind.BYTE, 2)), 0)); // tame
        out(new ClientboundPacket.SetEquipment(HORSE, List.of(new ClientboundPacket.SetEquipment.Slot("saddle",
                new Stack("minecraft:saddle", 1, List.of(), List.of(), null, false)))));
        out(new ClientboundPacket.UpdateAttributes(HORSE, List.of(
                new ClientboundPacket.UpdateAttributes.Attribute("minecraft:movement_speed", 0.3F, List.of()),
                new ClientboundPacket.UpdateAttributes.Attribute("minecraft:jump_strength", 0.8, List.of()))));
        out(new ClientboundPacket.EntityPositionSync(HORSE, new Vec3d(x, 64.0, z), Vec3d.ZERO, 0.0F, 0.0F, true));
        sync();
        villagers.add(new double[] {x, 64.0, z, 1.3964844F, 1.6F}); // its pushEntities pushes the player where they meet
        ticks(4, NONE, 30.0F);
    }

    /** The server puts the player on the horse; the client handles it before its next tick, the horse then as it held it. */
    private void mountHorse(double x, double z) throws Colliders.Unknown {
        out(new ClientboundPacket.SetPassengers(HORSE, new int[] {SELF}));
        sync();
        villagers.removeIf(v -> v.length > 3); // a vehicle pushes nothing (isPushable), its rider least of all
        horse = new HorseState(x, 64.0, z, Vec3d.ZERO, 0.0F, 0.0F, true, false, false, false, 0.0, Horse.supportingBlock(x, 64.0, z, GROUND,
                HORSE_CONDITIONS), false, Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 0.0, false);
        riderKeys = NONE;
        released = -1;
    }

    /**
     * One ride tick: the horse's (the input its rider's last tick left, the rotation this tick sends, the jump released
     * then), then the passenger's: its riding jump when the jump key was let go ({@code power}: the one sent, -1 for
     * the key's), its input, its rotation and the horse's move moved by {@code cheat}.
     */
    private void horseRideTick(PlayerState.Keys keys, Vec3d cheat, int power) throws Colliders.Unknown {
        if (released >= 0) {
            horse = Horse.onPlayerJump(horse, released, true);
        }
        float[] input = Horse.riderInput(riderKeys);
        horse = Horse.tick(horse, new Horse.Rider(input[0], input[1], 30.0F, 0.0F), GROUND, HORSE_CONDITIONS);
        released = -1;
        if (riderKeys.jump() && !keys.jump()) {
            released = power >= 0 ? power : heldJumpPower;
            in(new ServerboundPacket.PlayerCommand(SELF, ServerboundPacket.PlayerCommand.Action.START_RIDING_JUMP, released));
        }
        heldJumpPower = !keys.jump() ? 0 : !riderKeys.jump() ? 0 : Math.min(heldJumpPower + 10, 90); // held 1 to 9 ticks: 10 a tick
        if (!keys.equals(sentInput)) {
            in(new ServerboundPacket.PlayerInput(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint()));
            sentInput = keys;
        }
        in(new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 30.0F, 0.0F, false, true, false, false));
        BoatState.Interpolation left = horse.interpolation(); // ServerboundMoveVehiclePacket.fromEntity: its target while it interpolates
        in(new ServerboundPacket.MoveVehicle((left != null ? left.target() : new Vec3d(horse.x(), horse.y(), horse.z())).add(cheat),
                left != null ? left.targetYRot() : horse.yRot(), left != null ? left.targetXRot() : horse.xRot(), horse.onGround()));
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
        riderKeys = keys;
    }

    // --- a pig the player steers ---

    private static final int PIG = 42;
    /** The synthetic client's pig's boost ({@code ItemBasedSteering}) and the boost time it was sent. */
    private ItemSteering pigSteering = ItemSteering.NONE;
    private int pigBoostTotal;

    /** A pig's ridden tick: the default speed (0.25) times 0.225 and the boost's factor, a step of 1 under a player, no float. */
    private static Horse.Conditions pigConditions(float speed) {
        return new Horse.Conditions(speed, 0.0, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, false, 0.9F, 0.9F, null, List.of());
    }

    /** A saddled pig put on the stone at (x, 64, z), the player holding a carrot on a stick; the player ticks a few times on foot. */
    private void pigOnGround(double x, double z, String held) throws Colliders.Unknown {
        hold(held, null);
        out(ClientboundPacket.AddEntity.received(PIG, new UUID(6, 6), "minecraft:pig", x, 64.0, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.SetEquipment(PIG, List.of(new ClientboundPacket.SetEquipment.Slot("saddle",
                new Stack("minecraft:saddle", 1, List.of(), List.of(), null, false)))));
        out(new ClientboundPacket.EntityPositionSync(PIG, new Vec3d(x, 64.0, z), Vec3d.ZERO, 0.0F, 0.0F, true));
        sync();
        ticks(4, NONE, 30.0F);
    }

    /** The server puts the player on the pig; the client handles it before its next tick, the pig then as it held it. */
    private void mountPig(double x, double z) throws Colliders.Unknown {
        out(new ClientboundPacket.SetPassengers(PIG, new int[] {SELF}));
        sync();
        horse = new HorseState(x, 64.0, z, Vec3d.ZERO, 0.0F, 0.0F, true, false, false, false, 0.0, Horse.supportingBlock(x, 64.0, z, GROUND,
                pigConditions(0.05625F)), false, Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 0.0, false);
        pigSteering = ItemSteering.NONE;
    }

    /** The server's boost time reaches the client ({@code onSynced}): the boost starts from 0. */
    private void boostPig(int total) {
        out(new ClientboundPacket.SetEntityData(PIG, List.of(new ClientboundPacket.SetEntityData.DataEntry(17,
                ClientboundPacket.SetEntityData.Kind.INT, total)), 0));
        sync();
        pigSteering = ItemSteering.synced();
        pigBoostTotal = total;
    }

    /** One ride tick: the pig's (its boost counted, then ahead where the rider looks), then the rider's rotation and the pig's move moved by {@code cheat}. */
    private void pigRideTick(Vec3d cheat) throws Colliders.Unknown {
        pigSteering = pigSteering.tick(pigBoostTotal);
        float speed = (float) (0.25 * 0.225 * pigSteering.factor(pigBoostTotal));
        horse = Horse.tick(horse, new Horse.Rider(0.0F, 1.0F, 30.0F, 0.0F), GROUND, pigConditions(speed));
        in(new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 30.0F, 0.0F, false, true, false, false));
        in(new ServerboundPacket.MoveVehicle(new Vec3d(horse.x(), horse.y(), horse.z()).add(cheat), horse.yRot(), horse.xRot(), horse.onGround()));
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    // --- a strider the player steers ---

    private static final int STRIDER = 43;
    private static final java.util.Set<String> WARM = java.util.Set.of("minecraft:lava");

    /** A lava pool (sources) in the stone's top layer, x -4 to 8 and z 8 to 20, beside where the strider is put. */
    private void lavaPool() {
        int lava = STATES.firstStateOf("minecraft:lava"); // level=0: a source
        for (int x = -4; x <= 8; x++) {
            for (int z = 8; z <= 20; z++) {
                place(x, 63, z, lava);
            }
        }
    }

    /** A saddled strider put on the stone at (x, 64, z), the player holding {@code held}; the player ticks a few times on foot. */
    private void striderOnGround(double x, double z, String held, boolean noAi) throws Colliders.Unknown {
        hold(held, null);
        out(ClientboundPacket.AddEntity.received(STRIDER, new UUID(7, 7), "minecraft:strider", x, 64.0, z, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        out(new ClientboundPacket.SetEquipment(STRIDER, List.of(new ClientboundPacket.SetEquipment.Slot("saddle",
                new Stack("minecraft:saddle", 1, List.of(), List.of(), null, false)))));
        if (noAi) {
            out(new ClientboundPacket.SetEntityData(STRIDER, List.of(new ClientboundPacket.SetEntityData.DataEntry(15,
                    ClientboundPacket.SetEntityData.Kind.BYTE, 1)), 0));
        }
        out(new ClientboundPacket.EntityPositionSync(STRIDER, new Vec3d(x, 64.0, z), Vec3d.ZERO, 0.0F, 0.0F, true));
        sync();
        ticks(4, NONE, 30.0F);
    }

    /** A strider's ridden tick: the default speed (0.175) times 0.55, or with its suffocating modifier times 0.35, and the boost's factor. */
    private Horse.Conditions striderConditions(java.util.Set<String> warm) {
        float factor = pigSteering.factor(pigBoostTotal);
        double suffocating = 0.175F + 0.175F * (double) -0.34F;
        return new Horse.Conditions(0.0F, 0.0, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, false, 0.9F, 1.7F, null, List.of(),
                new Horse.Strider((float) ((double) 0.175F * 0.55F * factor), (float) (suffocating * 0.35F * factor), warm));
    }

    /** The server puts the player on the strider; the client handles it before its next tick, the strider then as it held it. */
    private void mountStrider(double x, double z) throws Colliders.Unknown {
        out(new ClientboundPacket.SetPassengers(STRIDER, new int[] {SELF}));
        sync();
        pigSteering = ItemSteering.NONE;
        horse = new HorseState(x, 64.0, z, Vec3d.ZERO, 0.0F, 0.0F, true, false, false, false, 0.0, Horse.supportingBlock(x, 64.0, z, GROUND,
                striderConditions(WARM)), false, Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 0.0, false);
    }

    /**
     * One ride tick: the strider's (its boost counted, its suffocation set from where it is, ahead where the rider looks),
     * then the rider's rotation and its move; {@code warm}: the blocks the client takes to keep it from suffocating.
     */
    private void striderRideTick(java.util.Set<String> warm) throws Colliders.Unknown {
        pigSteering = pigSteering.tick(pigBoostTotal);
        horse = Horse.tick(horse, new Horse.Rider(0.0F, 1.0F, 30.0F, 0.0F), GROUND, striderConditions(warm));
        in(new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 30.0F, 0.0F, false, true, false, false));
        in(new ServerboundPacket.MoveVehicle(new Vec3d(horse.x(), horse.y(), horse.z()), horse.yRot(), horse.xRot(), horse.onGround()));
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    /** The power of the jump key held so far (jumpRidingScale * 100 for up to 9 ticks held). */
    private int heldJumpPower;

    private List<MovementTracker.Tick> horseTicks() {
        return ticks.stream().filter(MovementTracker.Tick::horse).toList();
    }

    private List<MovementTracker.Tick> rideTicks() {
        return ticks.stream().filter(t -> t.sentVehicle() != null || t.reason() != null && t.reason().startsWith("riding")).toList();
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
    void aBoatSteeredByAVanillaClientIsExplainedFromItsMount() throws Exception {
        ticks(5, NONE, 30.0F);
        boatOnGround(6.5, 6.5);
        mount(6.5, 6.5);
        PlayerState.Keys left = new PlayerState.Keys(true, false, true, false, false, false, false);
        for (int i = 0; i < 40; i++) {
            rideTick(i < 3 ? NONE : i < 25 ? FORWARD : left, Vec3d.ZERO);
        }
        List<MovementTracker.Tick> rides = rideTicks();
        assertEquals(40, rides.size(), String.valueOf(rides));
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED), String.valueOf(rides));
        assertTrue(boat.x() > 6.6 || boat.z() > 6.6 || boat.x() < 6.4 || boat.z() < 6.4, "it moved: " + boat);
        assertEquals(List.of(), evidence);
    }

    @Test
    void aHorseMountedAgainIsNotJudgedFromItsFirstMount() throws Exception {
        ticks(5, NONE, 30.0F);
        horseOnGround(6.5, 6.5);
        mountHorse(6.5, 6.5);
        for (int i = 0; i < 40; i++) {
            horseRideTick(i < 30 ? FORWARD : NONE, Vec3d.ZERO, -1); // the keys let go before the dismount: they move nothing at the next mount
        }
        assertTrue(Math.abs(horse.x() - 6.5) + Math.abs(horse.z() - 6.5) > 0.5, "it moved: " + horse);
        int first = horseTicks().size();
        // off it and on again where the player left it: the replica followed none of that ride, so no state at this mount
        out(new ClientboundPacket.SetPassengers(HORSE, new int[] {}));
        out(new ClientboundPacket.SetPassengers(HORSE, new int[] {SELF}));
        sync();
        for (int i = 0; i < 10; i++) {
            horseRideTick(FORWARD, Vec3d.ZERO, -1);
        }
        List<MovementTracker.Tick> again = horseTicks().subList(first, horseTicks().size());
        assertEquals(10, again.size(), String.valueOf(again));
        assertTrue(again.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.NO_CLAIM), String.valueOf(again));
        assertEquals(List.of(), evidence);
    }

    @Test
    void aBoatMountedAgainIsNotJudgedFromItsFirstMount() throws Exception {
        ticks(5, NONE, 30.0F);
        boatOnGround(6.5, 6.5);
        mount(6.5, 6.5);
        for (int i = 0; i < 30; i++) {
            rideTick(FORWARD, Vec3d.ZERO);
        }
        assertTrue(Math.abs(boat.x() - 6.5) + Math.abs(boat.z() - 6.5) > 0.5, "it moved: " + boat);
        int first = rideTicks().size();
        // off it and on again where the player left it: the replica followed none of that ride, so no state at this mount
        out(new ClientboundPacket.SetPassengers(BOAT, new int[] {}));
        out(new ClientboundPacket.SetPassengers(BOAT, new int[] {SELF}));
        sync();
        for (int i = 0; i < 10; i++) {
            rideTick(FORWARD, Vec3d.ZERO);
        }
        List<MovementTracker.Tick> again = rideTicks().subList(first, rideTicks().size());
        assertEquals(10, again.size(), String.valueOf(again));
        assertTrue(again.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.NO_CLAIM), String.valueOf(again));
        assertEquals(List.of(), evidence);
    }

    @Test
    void aBoatMountedWhileItInterpolatesIsExplained() throws Exception {
        ticks(5, NONE, 30.0F);
        boatOnGround(6.5, 6.5);
        // the server moves it a little: the client's boat eases toward there over 3 ticks (AbstractBoat's InterpolationHandler)
        out(new ClientboundPacket.EntityPositionSync(BOAT, new Vec3d(6.5, 64.0, 6.6), Vec3d.ZERO, 0.0F, 0.0F, true));
        sync();
        mount(6.5, 6.5);
        boat = new BoatState(boat.x(), boat.y(), boat.z(), boat.deltaMovement(), boat.yRot(), boat.xRot(), boat.deltaRotation(), boat.status(),
                boat.lastYd(), boat.fallDistance(), boat.onGround(), boat.horizontalCollision(), boat.verticalCollision(), boat.verticalCollisionBelow(),
                boat.support(), boat.onGroundNoBlocks(), boat.stuckSpeedMultiplier(),
                new BoatState.Interpolation(3, new Vec3d(6.5, 64.0, 6.6), 0.0F, 0.0F, new Vec3d(6.5, 64.0, 6.5), 0.0F, 0.0F));
        for (int i = 0; i < 10; i++) {
            rideTick(FORWARD, Vec3d.ZERO);
        }
        List<MovementTracker.Tick> rides = rideTicks();
        assertEquals(10, rides.size(), String.valueOf(rides));
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED), String.valueOf(rides));
        assertEquals(List.of(), evidence);
    }

    @Test
    void aBoatMovedHalfABlockUpIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        boatOnGround(6.5, 6.5);
        mount(6.5, 6.5);
        for (int i = 0; i < 10; i++) {
            rideTick(FORWARD, Vec3d.ZERO);
        }
        rideTick(FORWARD, new Vec3d(0.0, 0.5, 0.0));
        assertEquals(1, evidence.size(), String.valueOf(rideTicks()));
        Evidence e = evidence.get(0);
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertTrue(e.summary().startsWith("steered a boat as no vanilla client could"), e.summary());
        assertTrue(e.details().get("offset").startsWith("0.000000,0.500000,0.000000"), e.details().toString());
    }

    @Test
    void noBoatClaimWhereThePlayerMayHavePushedItBeforeTheMount() throws Exception {
        ticks(5, NONE, 30.0F);
        boatOnGround(1.4, 0.5); // its box (0.7125 to 2.0875 across) within 0.2 of the player's (0.2 to 0.8)
        mount(1.4, 0.5);
        for (int i = 0; i < 5; i++) {
            rideTick(FORWARD, new Vec3d(0.0, i == 4 ? 0.5 : 0.0, 0.0));
        }
        List<MovementTracker.Tick> rides = rideTicks();
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.NO_CLAIM && t.reason().contains("pushed")), String.valueOf(rides));
        assertEquals(List.of(), evidence);
    }

    /** The player in a minecart ({@code getControllingPassenger} null: the server moves it); a passenger's ticks, {@code move} adding a MoveVehicle. */
    private void minecartRide(boolean move) throws Colliders.Unknown {
        vehicleRide("minecraft:minecart", false, move);
    }

    /** The player put on a {@code type} (saddled or not) and ridden ten ticks, sending a vehicle move in the last seven when {@code move}. */
    private void vehicleRide(String type, boolean saddled, boolean move) throws Colliders.Unknown {
        ticks(5, NONE, 30.0F);
        out(ClientboundPacket.AddEntity.received(43, new UUID(7, 7), type, 6.5, 64.0, 6.5, Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0));
        if (saddled) {
            out(new ClientboundPacket.SetEquipment(43, List.of(new ClientboundPacket.SetEquipment.Slot("saddle",
                    new Stack("minecraft:saddle", 1, List.of(), List.of(), null, false)))));
        }
        sync();
        ticks(2, NONE, 30.0F);
        out(new ClientboundPacket.SetPassengers(43, new int[] {SELF}));
        sync();
        for (int i = 0; i < 10; i++) {
            in(new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN, 30.0F, 0.0F, false, true, false, false));
            if (move && i >= 3) {
                in(new ServerboundPacket.MoveVehicle(new Vec3d(6.5 + 0.2 * i, 64.0, 6.5), 30.0F, 0.0F, true)); // minecart speed
            }
            in(new ServerboundPacket.ClientTickEnd());
            now += SECOND / 20;
        }
    }

    @Test
    void aVehicleMoveForAHorseWithNoSaddleIsAFindingAndForASaddledOneNot() throws Exception {
        vehicleRide("minecraft:horse", false, true);
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        assertTrue(evidence.getFirst().summary().startsWith(MovementTracker.VEHICLE_UNCONTROLLED + " (MoveVehicle on a minecraft:horse with no saddle)"),
                evidence.getFirst().summary());
        evidence.clear();
        out(new ClientboundPacket.SetPassengers(43, new int[] {}));
        out(new ClientboundPacket.RemoveEntities(new int[] {43}));
        sync();
        now += 2 * SECOND;
        vehicleRide("minecraft:llama", true, true);
        assertEquals(List.of(), evidence, "a saddled llama may be its rider's to control");
    }

    @Test
    void aMinecartsPassengerSendsNoVehicleMove() throws Exception {
        minecartRide(false);
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED));
    }

    @Test
    void aVehicleMoveForAMinecartIsAFinding() throws Exception {
        minecartRide(true);
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        assertTrue(evidence.getFirst().summary().startsWith(MovementTracker.VEHICLE_UNCONTROLLED + " (MoveVehicle on a minecraft:minecart)"),
                evidence.getFirst().summary());
    }

    @Test
    void aPigSteeredByAVanillaClientIsExplainedFromItsMount() throws Exception {
        ticks(5, NONE, 30.0F);
        pigOnGround(6.5, 6.5, "minecraft:carrot_on_a_stick");
        mountPig(6.5, 6.5);
        for (int i = 0; i < 40; i++) {
            if (i == 10) {
                boostPig(200);
            }
            pigRideTick(Vec3d.ZERO);
        }
        List<MovementTracker.Tick> rides = horseTicks();
        assertEquals(40, rides.size(), String.valueOf(rides));
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED), String.valueOf(rides));
        assertTrue(Math.abs(horse.x() - 6.5) + Math.abs(horse.z() - 6.5) > 1.0, "it moved: " + horse);
        assertTrue(pigSteering.boosting() && pigSteering.boostTime() == 30, String.valueOf(pigSteering));
        assertEquals(List.of(), evidence);
    }

    @Test
    void aPigFasterThanItsBoostIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        pigOnGround(6.5, 6.5, "minecraft:carrot_on_a_stick");
        mountPig(6.5, 6.5);
        for (int i = 0; i < 20; i++) {
            pigRideTick(i == 12 ? new Vec3d(-0.05, 0.0, 0.09) : Vec3d.ZERO); // pig speed: a little further ahead than it goes
        }
        assertEquals(1, evidence.size(), String.valueOf(horseTicks()));
        assertTrue(evidence.getFirst().summary().startsWith("rode a pig as no vanilla client could"), evidence.getFirst().summary());
    }

    @Test
    void aStriderSteeredFromStoneOntoLavaByAVanillaClientIsExplained() throws Exception {
        lavaPool();
        ticks(5, NONE, 30.0F);
        striderOnGround(6.5, 6.5, "minecraft:warped_fungus_on_a_stick", false);
        mountStrider(6.5, 6.5);
        boolean suffocated = false;
        boolean onLava = false;
        for (int i = 0; i < 70; i++) {
            striderRideTick(WARM);
            suffocated |= horse.suffocating();
            onLava |= horse.y() == 63.5 && horse.onGround() && !horse.suffocating();
        }
        List<MovementTracker.Tick> rides = horseTicks();
        assertEquals(70, rides.size(), String.valueOf(rides));
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED), String.valueOf(rides));
        assertTrue(suffocated && onLava, "it went from the stone, suffocating, onto the lava: " + horse);
        assertEquals(List.of(), evidence);
    }

    @Test
    void aStriderAsFastOnStoneAsOnLavaIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        striderOnGround(6.5, 6.5, "minecraft:warped_fungus_on_a_stick", false);
        mountStrider(6.5, 6.5);
        for (int i = 0; i < 10; i++) {
            striderRideTick(i < 4 ? WARM : java.util.Set.of("minecraft:lava", "minecraft:stone")); // strider speed: never slowed out of the warm
        }
        assertEquals(1, evidence.size(), String.valueOf(horseTicks()));
        assertTrue(evidence.getFirst().summary().startsWith("rode a strider as no vanilla client could"), evidence.getFirst().summary());
    }

    @Test
    void noStriderClaimWithoutAWarpedFungusOnAStick() throws Exception {
        ticks(5, NONE, 30.0F);
        striderOnGround(6.5, 6.5, "minecraft:carrot_on_a_stick", false);
        mountStrider(6.5, 6.5);
        for (int i = 0; i < 5; i++) {
            striderRideTick(WARM);
        }
        assertTrue(horseTicks().stream().allMatch(t -> "riding: the rider holds no warped fungus on a stick".equals(t.reason())),
                String.valueOf(horseTicks()));
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClaimForANoAiStrider() throws Exception {
        ticks(5, NONE, 30.0F);
        striderOnGround(6.5, 6.5, "minecraft:warped_fungus_on_a_stick", true);
        mountStrider(6.5, 6.5);
        for (int i = 0; i < 5; i++) {
            striderRideTick(java.util.Set.of("minecraft:stone"));
        }
        assertTrue(horseTicks().stream().allMatch(t -> "riding: a NoAI strider (its suffocation is the server's)".equals(t.reason())),
                String.valueOf(horseTicks()));
        assertEquals(List.of(), evidence);
    }

    @Test
    void noPigClaimWithoutACarrotOnAStick() throws Exception {
        ticks(5, NONE, 30.0F);
        pigOnGround(6.5, 6.5, null);
        mountPig(6.5, 6.5);
        for (int i = 0; i < 10; i++) {
            pigRideTick(Vec3d.ZERO);
        }
        List<MovementTracker.Tick> rides = horseTicks();
        assertEquals(10, rides.size(), String.valueOf(rides));
        assertTrue(rides.stream().allMatch(t -> "riding: the rider holds no carrot on a stick".equals(t.reason())), String.valueOf(rides));
        assertEquals(List.of(), evidence);
    }

    @Test
    void aHorseRiddenByAVanillaClientIsExplainedFromItsMount() throws Exception {
        ticks(5, NONE, 30.0F);
        horseOnGround(6.5, 6.5);
        mountHorse(6.5, 6.5);
        PlayerState.Keys left = new PlayerState.Keys(true, false, true, false, false, false, false);
        PlayerState.Keys forwardJump = new PlayerState.Keys(true, false, false, false, true, false, false);
        for (int i = 0; i < 50; i++) {
            horseRideTick(i < 3 ? NONE : i < 20 ? FORWARD : i < 25 ? forwardJump : i < 40 ? FORWARD : left, Vec3d.ZERO, -1);
        }
        List<MovementTracker.Tick> rides = horseTicks();
        assertEquals(50, rides.size(), String.valueOf(rides));
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED), String.valueOf(rides));
        assertTrue(horse.x() > 7.5 || horse.z() > 7.5 || horse.x() < 5.5 || horse.z() < 5.5, "it moved: " + horse);
        assertEquals(List.of(), evidence);
    }

    @Test
    void aHorseMountedWhileItInterpolatesIsExplained() throws Exception {
        ticks(5, NONE, 30.0F);
        horseOnGround(6.5, 6.5);
        // the server moves it a little: the client's horse eases toward there over 3 ticks (LivingEntity's InterpolationHandler)
        out(new ClientboundPacket.EntityPositionSync(HORSE, new Vec3d(6.5, 64.0, 6.6), Vec3d.ZERO, 0.0F, 0.0F, true));
        sync();
        mountHorse(6.5, 6.5);
        horse = new HorseState(horse.x(), horse.y(), horse.z(), horse.deltaMovement(), horse.yRot(), horse.xRot(), horse.onGround(),
                horse.horizontalCollision(), horse.verticalCollision(), horse.verticalCollisionBelow(), horse.fallDistance(), horse.support(),
                horse.onGroundNoBlocks(), horse.stuckSpeedMultiplier(), horse.inPowderSnow(), horse.noJumpDelay(), horse.jumpPendingScale(),
                horse.allowStandSliding(), horse.standing(), horse.standCounter(),
                new BoatState.Interpolation(3, new Vec3d(6.5, 64.0, 6.6), 0.0F, 0.0F, new Vec3d(6.5, 64.0, 6.5), 0.0F, 0.0F), horse.lavaHeight(),
                horse.suffocating());
        for (int i = 0; i < 10; i++) {
            horseRideTick(FORWARD, Vec3d.ZERO, -1);
        }
        List<MovementTracker.Tick> rides = horseTicks();
        assertEquals(10, rides.size(), String.valueOf(rides));
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED), String.valueOf(rides));
        assertEquals(List.of(), evidence);
    }

    @Test
    void aHorseMovedHalfABlockUpIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        horseOnGround(6.5, 6.5);
        mountHorse(6.5, 6.5);
        for (int i = 0; i < 10; i++) {
            horseRideTick(FORWARD, Vec3d.ZERO, -1);
        }
        horseRideTick(FORWARD, new Vec3d(0.0, 0.5, 0.0), -1);
        assertEquals(1, evidence.size(), String.valueOf(horseTicks()));
        Evidence e = evidence.get(0);
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertTrue(e.summary().startsWith("rode a horse as no vanilla client could"), e.summary());
        assertTrue(e.details().get("offset").startsWith("0.000000,0.500000,0.000000"), e.details().toString());
    }

    @Test
    void aRidingJumpOfMorePowerThanItsKeyHeldIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        horseOnGround(6.5, 6.5);
        mountHorse(6.5, 6.5);
        PlayerState.Keys jump = new PlayerState.Keys(false, false, false, false, true, false, false);
        horseRideTick(NONE, Vec3d.ZERO, -1);
        for (int i = 0; i < 4; i++) {
            horseRideTick(jump, Vec3d.ZERO, -1); // pressed, then held three ticks: 30
        }
        horseRideTick(NONE, Vec3d.ZERO, 100); // a full jump instead
        assertEquals(1, evidence.size(), String.valueOf(horseTicks()));
        assertTrue(evidence.get(0).details().get("reason").contains("another power than its jump key held (100, not 30)"),
                evidence.get(0).details().toString());
    }

    @Test
    void noHorseClaimWhereThePlayerMayHavePushedItBeforeTheMount() throws Exception {
        ticks(5, NONE, 30.0F);
        horseOnGround(1.0, 0.5); // its box (0.30 to 1.70 across) meets the player's (0.2 to 0.8)
        mountHorse(1.0, 0.5);
        for (int i = 0; i < 5; i++) {
            horseRideTick(FORWARD, new Vec3d(0.0, i == 4 ? 0.5 : 0.0, 0.0), -1);
        }
        List<MovementTracker.Tick> rides = horseTicks();
        assertEquals(5, rides.size());
        assertTrue(rides.stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.NO_CLAIM && t.reason().contains("pushed")), String.valueOf(rides));
        assertEquals(List.of(), evidence);
    }

    @Test
    void aVehicleMoveWhileRidingNothingIsAFinding() throws Exception {
        ticks(10, FORWARD, 30.0F);
        // on foot it would only end the claim: here it covers a step half a block too far
        keybinds.add(new ServerboundPacket.MoveVehicle(new Vec3d(0.5, 64.0, 0.5), 0.0F, 0.0F, true));
        tick(FORWARD, 30.0F, 0.0F, new Vec3d(0.0, 0.0, 0.5));
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        Evidence e = evidence.get(0);
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertTrue(e.summary().startsWith(MovementTracker.VEHICLE_ON_FOOT), e.summary());
        now += 2 * SECOND;
        keybinds.add(new ServerboundPacket.PaddleBoat(true, false));
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(2, evidence.size(), String.valueOf(ticks));
        assertTrue(evidence.get(1).summary().contains("(PaddleBoat)"), evidence.get(1).summary());
        now += 2 * SECOND;
        keybinds.add(new ServerboundPacket.PlayerCommand(SELF, ServerboundPacket.PlayerCommand.Action.START_RIDING_JUMP, 90));
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(3, evidence.size(), String.valueOf(ticks));
        assertTrue(evidence.get(2).summary().contains("(PlayerCommand START_RIDING_JUMP)"), evidence.get(2).summary());
        now += 2 * SECOND;
        keybinds.add(new ServerboundPacket.PlayerCommand(SELF, ServerboundPacket.PlayerCommand.Action.OPEN_INVENTORY, 0));
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(4, evidence.size(), String.valueOf(ticks));
        assertTrue(evidence.get(3).summary().contains("(PlayerCommand OPEN_INVENTORY)"), evidence.get(3).summary());
    }

    @Test
    void aVehicleMoveRightAfterADismountIsNoFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        boatOnGround(6.5, 6.5);
        mount(6.5, 6.5);
        for (int i = 0; i < 5; i++) {
            rideTick(FORWARD, Vec3d.ZERO);
        }
        out(new ClientboundPacket.SetPassengers(BOAT, new int[0]));
        sync();
        // sent before the client took the player off (an answer to the server's vehicle move, say): it still rode
        keybinds.add(new ServerboundPacket.MoveVehicle(new Vec3d(boat.x(), boat.y(), boat.z()), boat.yRot(), boat.xRot(), boat.onGround()));
        tick(NONE, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(List.of(), evidence);
        now += 2 * SECOND;
        keybinds.add(new ServerboundPacket.MoveVehicle(new Vec3d(boat.x(), boat.y(), boat.z()), boat.yRot(), boat.xRot(), boat.onGround()));
        tick(NONE, 30.0F, 0.0F, Vec3d.ZERO); // a whole tick off it
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        assertTrue(evidence.get(0).summary().startsWith(MovementTracker.VEHICLE_ON_FOOT), evidence.get(0).summary());
    }

    @Test
    void togglingFlightWhileNotAllowedToFlyIsAFinding() throws Exception {
        ticks(10, FORWARD, 30.0F);
        // a survival client never sends its abilities: here they would cover a step half a block up
        keybinds.add(new ServerboundPacket.PlayerAbilities(true));
        tick(FORWARD, 30.0F, 0.0F, new Vec3d(0.0, 0.5, 0.0));
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.get(0).evidenceClass());
        assertTrue(evidence.get(0).summary().startsWith(MovementTracker.ABILITIES_WITHOUT_FLIGHT), evidence.get(0).summary());
    }

    @Test
    void noFindingForFlightTheServerAllows() throws Exception {
        ticks(10, FORWARD, 30.0F);
        out(new ClientboundPacket.PlayerAbilities(false, false, true, false, 0.05F, 0.1F));
        sync();
        keybinds.add(new ServerboundPacket.PlayerAbilities(true));
        tick(FORWARD, 30.0F, 0.0F, new Vec3d(0.0, 0.5, 0.0));
        assertEquals(List.of(), evidence);
        assertEquals(MovementTracker.Outcome.NO_CLAIM, ticks.getLast().outcome(), String.valueOf(ticks.getLast()));
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
    void noFindingWhileTheClientMayHoldOtherTagsThanTheEngines() throws Exception {
        // a datapack makes stone climbable: the engine's step is not the client's
        ClientboundPacket.UpdateTags vanilla = VanillaTagsPacket.packet();
        List<ClientboundPacket.UpdateTags.RegistryTags> registries = new ArrayList<>(vanilla.registries());
        ClientboundPacket.UpdateTags.RegistryTags blocks = registries.getFirst();
        List<ClientboundPacket.UpdateTags.Tag> tags = new ArrayList<>(blocks.tags());
        int[] climbable = tags.getFirst().ids();
        int[] more = java.util.Arrays.copyOf(climbable, climbable.length + 1);
        more[climbable.length] = STATES.blockId(STATES.firstStateOf("minecraft:stone"));
        tags.set(0, new ClientboundPacket.UpdateTags.Tag("minecraft:climbable", more));
        registries.set(0, new ClientboundPacket.UpdateTags.RegistryTags("minecraft:block", tags));
        out(new ClientboundPacket.UpdateTags(registries)); // a reload in play
        out(vanilla); // and back, both processed: the claim's span (from the login) still reaches back to the other tags
        sync();
        ticks(5, NONE, 30.0F);
        ticks(20, FORWARD, 30.0F);
        tick(FORWARD, 30.0F, 0.0F, new Vec3d(0.0, 0.0, 0.01));
        assertEquals(List.of(), evidence, "the client may have climbed what the engine does not");
        assertEquals(1, count(MovementTracker.Outcome.UNEXPLAINED), "the tick is still unexplained, only not claimed");
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

    private static final ServerboundPacket.InteractEntity ATTACK = new ServerboundPacket.InteractEntity(99, ServerboundPacket.InteractEntity.Kind.ATTACK,
            null, null, false);

    /** Blocking with the shield behind the sword, the server's flags echoed: the client is using an item. */
    private void block() throws Exception {
        hold("minecraft:diamond_sword", "minecraft:shield");
        ticks(5, NONE, 30.0F);
        ticks(10, FORWARD, 30.0F);
        keybinds.add(useItem(Hand.MAIN_HAND));
        keybinds.add(useItem(Hand.OFF_HAND));
        use = OnFoot.ItemUse.DEFAULT;
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(3);
        ticks(10, FORWARD, 30.0F);
    }

    @Test
    void attackingWhileBlockingIsAFinding() throws Exception {
        block();
        assertEquals(List.of(), evidence);
        keybinds.add(ATTACK); // handleKeybinds consumes the click while the shield is up: no vanilla client sends this
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        assertEquals(MovementTracker.ACTED_WHILE_USING + " (tick " + evidence.getFirst().details().get("clientTick") + ")", evidence.getFirst().summary());
        assertEquals(MovementTracker.ACTED_WHILE_USING, evidence.getFirst().details().get("reason"));
    }

    @Test
    void startingAnotherUseWhileBlockingIsAFinding() throws Exception {
        block();
        keybinds.add(useItem(Hand.MAIN_HAND)); // startUseItem only runs while no item is in use
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(1, evidence.size(), String.valueOf(ticks));
        assertEquals(MovementTracker.ACTED_WHILE_USING, evidence.getFirst().details().get("reason"));
    }

    @Test
    void releasingAndAttackingInOneTickIsAFindingToo() throws Exception {
        block();
        keybinds.add(RELEASE); // the use key up: handleKeybinds releases, and still consumes the attack click
        keybinds.add(ATTACK);
        use = null;
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(1, evidence.size(), String.valueOf(ticks));
    }

    @Test
    void attackingTheTickAfterTheReleaseIsVanilla() throws Exception {
        block();
        keybinds.add(RELEASE);
        use = null;
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(0);
        keybinds.add(ATTACK);
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        ticks(10, FORWARD, 30.0F);
        keybinds.add(ATTACK);
        keybinds.add(useItem(Hand.MAIN_HAND)); // an attack, then a use, in the same tick: startAttack, then startUseItem
        keybinds.add(useItem(Hand.OFF_HAND));
        use = OnFoot.ItemUse.DEFAULT;
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        ticks(5, FORWARD, 30.0F);
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    /** A trident in the first hotbar slot whose enchantments give {@code spinAttack} (0: no riptide), as the server sends it. */
    private void holdTrident(float spinAttack) {
        List<Stack> items = new ArrayList<>();
        for (int i = 0; i < 46; i++) {
            items.add(Stack.EMPTY);
        }
        items.set(36, new Stack("minecraft:trident", 1, spinAttack > 0.0F ? List.of("minecraft:enchantments") : List.of(), List.of(), null, false,
                null, spinAttack));
        out(new ClientboundPacket.ContainerSetContent(0, 1, items, Stack.EMPTY));
        sync();
    }

    /** Charging a trident for 15 ticks and letting it go (TridentItem.releaseUsing after 10 or more), walking on after. */
    private void throwTrident() throws Colliders.Unknown {
        ticks(5, NONE, 30.0F);
        keybinds.add(useItem(Hand.MAIN_HAND));
        use = OnFoot.ItemUse.DEFAULT;
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(1);
        ticks(15, FORWARD, 30.0F);
        keybinds.add(RELEASE);
        use = null;
        tick(FORWARD, 30.0F, 0.0F, Vec3d.ZERO);
        livingFlags(0);
        ticks(20, FORWARD, 30.0F);
    }

    @Test
    void aTridentWithoutRiptideLetGoMovesNothingAndIsFollowed() throws Exception {
        holdTrident(0.0F);
        throwTrident();
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED) + count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void aRiptideTridentCannotBeUsedOutOfWaterAndRainSoItsPushIsAFinding() throws Exception {
        holdTrident(3.0F);
        ticks(5, NONE, 30.0F); // no rain since the login (a new level), stone and air all around
        keybinds.add(useItem(Hand.MAIN_HAND)); // TridentItem.use: FAIL, the client uses nothing
        tick(NONE, 30.0F, 0.0F, Vec3d.ZERO);
        ticks(15, NONE, 30.0F); // standing, a use or none moves it alike: only the trident's rule tells them apart
        assertEquals(List.of(), evidence);
        keybinds.add(RELEASE); // a modified client lets go of a use it could not start, and riptides away
        tick(NONE, 30.0F, 0.0F, new Vec3d(-1.5, 0.0, 2.6));
        assertEquals(1, evidence.size(), String.valueOf(ticks));
    }

    @Test
    void inTheRainARiptideTridentMayBeUsedAndItsReleaseEndsTheClaim() throws Exception {
        holdTrident(3.0F); // riptide III: 1.5 + 2 * 0.75
        out(new ClientboundPacket.GameEvent(1, 0.0F)); // START_RAINING
        out(new ClientboundPacket.GameEvent(7, 1.0F)); // RAIN_LEVEL_CHANGE
        sync();
        throwTrident();
        assertEquals(List.of(), evidence);
        assertTrue(ticks.stream().anyMatch(t -> t.reason() != null && t.reason().startsWith("a trident released")), String.valueOf(ticks));
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
    void walkingAgainstALavaCurrentAndStandingInItAreFollowed() throws Exception {
        // a channel one block wide in the ground (z 0), its source at x 2 flooding three blocks east, as the slow lava of
        // the overworld does
        int[] lava = new int[4];
        for (int s = STATES.firstStateOf("minecraft:lava"); STATES.block(s).equals("minecraft:lava"); s++) {
            for (int i = 0; i < 4; i++) {
                lava[i] = STATES.properties(s).equals("level=" + 2 * i) ? s : lava[i];
            }
        }
        for (int i = 0; i < 4; i++) {
            place(2 + i, 63, 0, lava[i]);
        }
        place(6, 63, 0, AIR);
        teleport(7.5, 64.0, 0.5, 90.0F, 0.0F);
        ticks(5, NONE, 90.0F);
        int from = ticks.size();
        ticks(40, FORWARD, 90.0F); // west into the channel, against the current
        double reached = player.x();
        ticks(20, NONE, 90.0F); // carried back east
        assertTrue(player.y() < 64.0 && reached < 5.0 && player.x() > reached, String.valueOf(player));
        assertEquals(List.of(), evidence, String.valueOf(ticks.subList(from, ticks.size())));
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
        // standing in the current as if it did not push
        teleport(3.5, 63.0, 0.5, 90.0F, 0.0F);
        ticks(5, NONE, 90.0F);
        tick(NONE, 90.0F, 0.0F, new Vec3d(-0.002, 0.0, 0.0));
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

    @Test
    void aGlideFromHighUpIsFollowedToItsLanding() throws Exception {
        int from = ticks.size();
        glide(4.0F, i -> 10.0F);
        glide(-6.5F, i -> i % 50 < 30 ? 40.0F : -25.0F); // diving and pulling up
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }

    @Test
    void glidingFurtherThanTheGlideCarriesIsAFinding() throws Exception {
        startGlide();
        ticks(20, NONE, -90.0F);
        tick(NONE, -90.0F, 10.0F, new Vec3d(0.03, 0.0, 0.0));
        assertEquals(1, evidence.size(), String.valueOf(ticks.subList(ticks.size() - 3, ticks.size())));
    }

    @Test
    void aGlideStartTheClientCouldNotMakeIsAFinding() throws Exception {
        ticks(5, NONE, 30.0F);
        keybinds.add(new ServerboundPacket.PlayerCommand(SELF, ServerboundPacket.PlayerCommand.Action.START_FALL_FLYING, 0)); // on the ground
        tick(NONE, 30.0F, 0.0F, Vec3d.ZERO);
        assertEquals(1, evidence.size(), String.valueOf(ticks.subList(ticks.size() - 2, ticks.size())));
    }

    @Test
    void aGlideWithoutItsStartIsAFinding() throws Exception {
        elytra = true;
        teleport(0.5, 100.0, 16.5, -90.0F, 0.0F);
        ticks(5, NONE, -90.0F);
        sendsGlideStart = false; // it glides, but the server is not told
        tick(JUMP, -90.0F, 10.0F, Vec3d.ZERO);
        ticks(3, NONE, -90.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks.subList(ticks.size() - 4, ticks.size())));
    }

    /**
     * The server adds a firework rocket attached to the gliding player and the client ticks with it before the ping
     * after it is answered (whether it already holds it is not known), then without it; the glide turns down to the
     * ground. Returns the yaw.
     */
    private float boostedGlide() throws Colliders.Unknown {
        rocket(50, SELF); // not answered for yet
        float yaw = -90.0F;
        tick(NONE, yaw -= 9.0F, 10.0F, Vec3d.ZERO); // the client had not processed it: no boost
        sync();
        rockets = 1;
        for (int i = 0; i < 6; i++) {
            tick(NONE, yaw -= 9.0F, 10.0F, Vec3d.ZERO); // a tight turn keeps the fast glide over the loaded chunks
        }
        out(new ClientboundPacket.RemoveEntities(new int[] {50}));
        sync();
        rockets = 0;
        for (int i = 0; i < 400 && !player.onGround(); i++) {
            tick(NONE, yaw -= 9.0F, 10.0F, Vec3d.ZERO);
        }
        assertTrue(player.onGround() && player.fallFlying(), String.valueOf(player));
        return yaw;
    }

    @Test
    void aFireworkRocketTheClientMayNotHoldYetEndsTheClaimUntilThePlayerStandsAgain() throws Exception {
        startGlide();
        ticks(10, NONE, -90.0F);
        int boosted = ticks.size();
        float yaw = boostedGlide();
        land(yaw);
        assertEquals(List.of(), evidence, String.valueOf(ticks.subList(boosted, boosted + 12)));
        // the player's own tick is explained; what the rocket did after it is not known, and the next ticks are not followed
        assertEquals(MovementTracker.Outcome.EXPLAINED, ticks.get(boosted).outcome(), String.valueOf(ticks.get(boosted)));
        assertEquals(0, ticks.get(boosted).hypotheses());
        assertTrue(ticks.subList(boosted + 1, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.UNTRACKED
                && t.reason().startsWith("a firework rocket may boost the gliding player")), String.valueOf(ticks.subList(boosted, ticks.size())));
        // standing still after the server ended the glide, it is tracked again
        int anchoredAt = ticks.size();
        ticks(40, NONE, yaw);
        ticks(10, SPRINT_JUMP, yaw);
        assertTrue(ticks.subList(ticks.size() - 10, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(anchoredAt, ticks.size())));
        tick(SPRINT_JUMP, yaw, 0.0F, new Vec3d(0.05, 0.0, 0.0));
        assertEquals(1, evidence.size());
    }

    @Test
    void fireworkRocketBoostsAreFollowedOneByOne() throws Exception {
        startGlide();
        ticks(5, NONE, -90.0F);
        int from = ticks.size();
        rocket(50, SELF);
        sync();
        rockets = 1;
        float yaw = -90.0F;
        for (int i = 0; i < 6; i++) {
            tick(NONE, yaw -= 9.0F, -10.0F, Vec3d.ZERO); // boosted upward along the look, turning tight
        }
        rocket(51, SELF); // a second one: both boost each tick
        sync();
        rockets = 2;
        for (int i = 0; i < 4; i++) {
            tick(NONE, yaw -= 9.0F, 5.0F, Vec3d.ZERO);
        }
        out(new ClientboundPacket.RemoveEntities(new int[] {51}));
        sync();
        rockets = 1;
        for (int i = 0; i < 400 && !player.onGround(); i++) {
            tick(NONE, yaw -= 9.0F, 25.0F, Vec3d.ZERO); // the other one boosts it down to the ground
        }
        land(yaw); // once the server ended the glide, the rocket still attached boosts nothing
        out(new ClientboundPacket.RemoveEntities(new int[] {50}));
        sync();
        rockets = 0;
        ticks(5, FORWARD, yaw);
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }

    @Test
    void aBoostWithoutARocketIsAFinding() throws Exception {
        startGlide();
        ticks(5, NONE, -90.0F);
        rockets = 1; // the client boosts itself: no rocket was added
        ticks(2, NONE, -90.0F);
        rockets = 0;
        ticks(2, NONE, -90.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks.subList(ticks.size() - 4, ticks.size())));
    }

    @Test
    void aRocketAttachedToAnotherEntityDoesNotBoostThePlayer() throws Exception {
        startGlide();
        ticks(5, NONE, -90.0F);
        int from = ticks.size();
        villager(60, 30.0, 64.0, 30.0);
        rocket(50, 60); // boosting another entity, flying alongside
        rocket(51, -1); // and one shot by a crossbow (not attached)
        sync();
        for (int i = 0; i < 400 && !player.onGround(); i++) {
            tick(NONE, -90.0F + i * 3.0F, 10.0F, Vec3d.ZERO);
        }
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
        rockets = 1; // the client boosts itself by another's rocket
        tick(NONE, 0.0F, 10.0F, Vec3d.ZERO);
        tick(NONE, 0.0F, 10.0F, Vec3d.ZERO);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks.subList(ticks.size() - 2, ticks.size())));
    }

    /** A strip of powder snow in the ground's top layer (y 63), x 2 to 5, z -1 to 1: stone below, stone east of it. */
    private void powderSnowStrip() {
        int snow = STATES.firstStateOf("minecraft:powder_snow");
        for (int x = 2; x <= 5; x++) {
            for (int z = -1; z <= 1; z++) {
                place(x, 63, z, snow);
            }
        }
    }

    @Test
    void withoutBootsThePlayerSinksIntoPowderSnowAndStaysIn() throws Exception {
        powderSnowStrip();
        ticks(5, NONE, -90.0F);
        int from = ticks.size();
        boolean in = false;
        double lowest = Double.MAX_VALUE;
        for (int i = 0; i < 70; i++) { // walks east into it, held back, against the stone at x 6
            tick(FORWARD, -90.0F, 0.0F, Vec3d.ZERO);
            in |= player.inPowderSnow();
            lowest = Math.min(lowest, player.y());
        }
        assertTrue(in && lowest < 63.5 && player.horizontalCollision(), String.valueOf(player));
        double highest = 0.0;
        for (int i = 0; i < 30; i++) { // jumping against the stone: held, no step up the whole block, no climbing without boots
            tick(SPRINT_JUMP, -90.0F, 0.0F, Vec3d.ZERO);
            highest = Math.max(highest, player.y());
        }
        assertTrue(player.x() < 6.0 && highest > 63.5 && highest < 64.0, player + " " + highest);
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }

    @Test
    void leatherBootsWalkOnPowderSnowSinkWhenDescendingAndClimbOut() throws Exception {
        powderSnowStrip();
        wear("minecraft:leather_boots");
        ticks(5, NONE, -90.0F);
        int from = ticks.size();
        for (int i = 0; i < 15; i++) { // on top of it: the full block holds the player up
            tick(FORWARD, -90.0F, 0.0F, Vec3d.ZERO);
            assertEquals(64.0, player.y(), String.valueOf(player));
        }
        assertTrue(player.x() > 3.0 && player.x() < 4.5 && !player.inPowderSnow(), String.valueOf(player));
        PlayerState.Keys sneak = new PlayerState.Keys(false, false, false, false, false, true, false);
        ticks(20, sneak, -90.0F); // descending: no shape, in it
        assertTrue(player.y() < 63.5 && player.inPowderSnow(), String.valueOf(player));
        boolean climbed = false;
        for (int i = 0; i < 60 && player.y() < 64.0; i++) { // against the stone at x 6: climbing out, 0.2 a tick
            tick(FORWARD, -90.0F, 0.0F, Vec3d.ZERO);
            climbed |= player.deltaMovement().y() > 0.0 && !player.onGround();
        }
        ticks(10, FORWARD, -90.0F);
        assertTrue(climbed && player.x() > 6.0 && player.y() == 64.0, String.valueOf(player));
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }

    @Test
    void aFallOfMoreThan2Point5LandsOnPowderSnowsSlabThenSinks() throws Exception {
        powderSnowStrip();
        teleport(3.5, 70.0, 0.5, -90.0F, 0.0F);
        int from = ticks.size();
        boolean slab = false;
        for (int i = 0; i < 40; i++) {
            tick(NONE, -90.0F, 0.0F, Vec3d.ZERO);
            slab |= player.onGround() && player.y() == 63.0 + (double) 0.9F;
        }
        assertTrue(slab, "it landed on the falling shape");
        assertTrue(player.y() < 63.5 && player.inPowderSnow(), String.valueOf(player));
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }

    @Test
    void freezingInPowderSnowIsFollowedThroughItsSpeed() throws Exception {
        powderSnowStrip();
        teleport(2.5, 63.0, 0.5, -90.0F, 0.0F);
        ticks(5, NONE, -90.0F);
        int from = ticks.size();
        for (int frozen = 1; frozen <= 60; frozen++) {
            // the server's tick: FREEZE counts the ticks frozen (synched data), then tryAddFrost's modifier, -0.05F of the part frozen
            out(new ClientboundPacket.SetEntityData(SELF, List.of(new ClientboundPacket.SetEntityData.DataEntry(7,
                    ClientboundPacket.SetEntityData.Kind.INT, frozen)), 0));
            serverModifiers.removeIf(m -> m.id().equals("minecraft:powder_snow"));
            serverModifiers.add(new ClientboundPacket.UpdateAttributes.Modifier("minecraft:powder_snow", -0.05F * ((float) frozen / 140.0F),
                    ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE));
            sendSpeed(true);
            tick(frozen < 30 ? NONE : FORWARD, -90.0F, 0.0F, Vec3d.ZERO);
        }
        assertTrue(player.inPowderSnow() && player.x() > 2.6, String.valueOf(player));
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }

    @Test
    void walkingOnPowderSnowWithoutBootsIsAFinding() throws Exception {
        powderSnowStrip();
        leatherBoots = true; // the client walks on it as if it wore boots: it wears none
        ticks(5, NONE, -90.0F);
        ticks(12, FORWARD, -90.0F);
        assertEquals(64.0, player.y(), String.valueOf(player));
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void movingThroughPowderSnowAtFullSpeedIsAFinding() throws Exception {
        powderSnowStrip();
        teleport(2.5, 63.0, 0.5, -90.0F, 0.0F); // in it
        ticks(5, NONE, -90.0F);
        assertTrue(player.inPowderSnow(), String.valueOf(player));
        ticks(3, FORWARD, -90.0F);
        assertEquals(List.of(), evidence);
        tick(FORWARD, -90.0F, 0.0F, new Vec3d(0.03, 0.0, 0.0)); // not held back
        assertEquals(1, evidence.size(), String.valueOf(ticks.subList(ticks.size() - 3, ticks.size())));
    }

    @Test
    void bootsNotKnownEndTheClaimOnlyOnPowderSnow() throws Exception {
        powderSnowStrip();
        hold("minecraft:leather_boots", null);
        ticks(5, NONE, -90.0F);
        keybinds.add(useItem(Hand.MAIN_HAND)); // Equippable.swapWithEquipmentSlot: on, but the replica does not follow the swap
        leatherBoots = true;
        tick(NONE, -90.0F, 0.0F, Vec3d.ZERO);
        out(new ClientboundPacket.ContainerSetSlot(0, 2, 36, Stack.EMPTY)); // the server resends the hand, not the feet
        sync();
        ticks(45, NONE, 90.0F); // anchored again where it stands (at a position reminder like the last)
        ticks(5, FORWARD, 90.0F); // west, away from the snow: claimed
        assertTrue(ticks.subList(ticks.size() - 4, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks));
        teleport(3.5, 64.0, 0.5, -90.0F, 0.0F); // standing over it: the boots decide
        ticks(5, NONE, -90.0F);
        assertEquals(List.of(), evidence);
        assertTrue(ticks.stream().anyMatch(t -> t.reason() != null && t.reason().contains("boots")), String.valueOf(ticks));
    }

    @Test
    void walkingIntoTheWorldBorderStopsAtItAndIsFollowed() throws Exception {
        staticBorder(-7.0, 0.5, 20.0); // its east side at x 3
        ticks(5, NONE, -90.0F);
        ticks(30, FORWARD, -90.0F);
        assertTrue(player.horizontalCollision() && player.x() > 2.69 && player.x() < 2.7 + 1.0E-9, String.valueOf(player));
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED), String.valueOf(ticks));
        assertEquals(0, count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void walkingThroughTheWorldBorderIsAFinding() throws Exception {
        staticBorder(-7.0, 0.5, 20.0);
        border = null; // the client ignores it
        ticks(5, NONE, -90.0F);
        ticks(30, FORWARD, -90.0F);
        assertTrue(player.x() > 3.0, String.valueOf(player));
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void aShrinkingWorldBorderIsFollowedStepByStep() throws Exception {
        lerpingBorder(-7.0, 0.5, 30.0, 4.0, 40L); // its east side from x 8 to -5 over two seconds, through the player
        ticks(60, FORWARD, -90.0F); // walking into it as it comes, then out of it once it passed
        assertTrue(player.x() > 3.0, String.valueOf(player));
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED), String.valueOf(ticks));
        assertEquals(0, count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void aGrowingWorldBorderTheClientStopsAtIsFollowed() throws Exception {
        lerpingBorder(-7.0, 0.5, 18.0, 22.0, 100L); // its east side from x 2 to 4 over five seconds, ahead of the player
        ticks(80, FORWARD, -90.0F); // pressed against it as it moves: its shape's side is the whole block beyond, 2, 3, then 4
        assertTrue(player.horizontalCollision() && Math.abs(player.x() - 3.7) < 1.0E-6, String.valueOf(player)); // half of 0.6F short of x 4
        assertEquals(List.of(), evidence);
        assertEquals(0, count(MovementTracker.Outcome.UNEXPLAINED), String.valueOf(ticks));
        assertEquals(0, count(MovementTracker.Outcome.NO_CLAIM), String.valueOf(ticks));
    }

    @Test
    void aBorderOneStepAheadOfTheClientsIsAFinding() throws Exception {
        borderAhead = true; // its side passes x 2 after the first step, a tick before the client's does
        lerpingBorder(-7.0, 0.5, 18.0, 22.0, 100L);
        ticks(80, FORWARD, -90.0F);
        assertTrue(!evidence.isEmpty(), String.valueOf(ticks));
    }

    @Test
    void aBorderPassingThroughThePlayerKeepsItCrouching() throws Exception {
        PlayerState.Keys sneak = new PlayerState.Keys(false, false, false, false, false, true, false);
        lerpingBorder(-7.0, 0.5, 30.0, 4.0, 400L); // its east side from x 8 to -5 over 20 seconds
        ticks(252, sneak, 90.0F); // crouching until the side's whole block (0) is inside the box, the player still close
        int from = ticks.size();
        boolean keptCrouching = false;
        for (int i = 0; i < 20; i++) {
            tick(FORWARD, 90.0F, 0.0F, Vec3d.ZERO); // walking west without shift: standing needs room the border takes
            keptCrouching |= player.pose() == PlayerState.Pose.CROUCHING;
        }
        assertTrue(keptCrouching, "the border kept the client crouching");
        assertEquals(PlayerState.Pose.STANDING, player.pose());
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }

    @Test
    void aLerpingBorderWhileTheLevelMayBeFrozenEndsTheClaimNearIt() throws Exception {
        out(new ClientboundPacket.TickingState(20.0F, true));
        out(new ClientboundPacket.TickingStep(100)); // stepping: the level may or may not run normally in a tick
        sync();
        lerpingBorder(-7.0, 0.5, 30.0, 4.0, 40L);
        ticks(10, NONE, -90.0F);
        assertEquals(List.of(), evidence);
        assertTrue(ticks.stream().anyMatch(t -> "near a world border not known exactly".equals(t.reason())), String.valueOf(ticks));
    }

    @Test
    void aPlayerThatMayStillGlideIsNotAnchoredWhereItStandsStill() throws Exception {
        startGlide();
        ticks(10, NONE, -90.0F);
        float yaw = boostedGlide();
        teleport(0.5, 64.0, 0.5, yaw, 90.0F); // the server stops the slide; the glide goes on
        assertTrue(player.fallFlying());
        int still = ticks.size();
        for (int i = 0; i < 40; i++) {
            tick(NONE, yaw, 90.0F, Vec3d.ZERO); // looking straight down, the glide does not move it
        }
        for (int i = 0; i < 10; i++) {
            tick(NONE, yaw, 0.0F, Vec3d.ZERO); // looking ahead, it slides on
        }
        assertTrue(player.fallFlying() && Math.abs(player.x() - 0.5) + Math.abs(player.z() - 0.5) > 0.01, String.valueOf(player));
        assertEquals(List.of(), evidence, String.valueOf(ticks.subList(still, ticks.size())));
        assertTrue(ticks.subList(still, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.UNTRACKED),
                String.valueOf(ticks.subList(still, ticks.size())));
        land(yaw);
        ticks(40, NONE, yaw);
        ticks(10, SPRINT_JUMP, yaw);
        assertTrue(ticks.subList(ticks.size() - 10, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(ticks.size() - 60, ticks.size())));
        assertEquals(List.of(), evidence);
    }

    @ParameterizedTest
    @EnumSource(PlatformCos.class)
    void aGlideOnEveryPlatformIsFollowed(PlatformCos cpu) throws Exception {
        platform = cpu; // Math.cos is the client's CPU's intrinsic, the same all session
        int from = ticks.size();
        glide(4.0F, i -> 34.2F); // x86_64's cosine is not the others' here
        glide(-6.5F, i -> i % 50 < 30 ? 62.7F : -25.05F); // aarch64's is not here
        assertEquals(List.of(), evidence);
        assertTrue(ticks.subList(from, ticks.size()).stream().allMatch(t -> t.outcome() == MovementTracker.Outcome.EXPLAINED),
                String.valueOf(ticks.subList(from, ticks.size())));
    }
}
