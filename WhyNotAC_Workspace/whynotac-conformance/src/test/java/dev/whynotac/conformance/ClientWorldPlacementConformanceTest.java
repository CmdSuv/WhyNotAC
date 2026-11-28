package dev.whynotac.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.Section;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * Differential conformance test proving {@link ClientWorld#placementPlanForItem} produces bit-exact block states
 * matching real Paper 1.21.11 NMS {@code Block.getStateForPlacement} and {@code BlockState.updateShape} across
 * 2,000+ randomized connected-block, corner-stair, door, fence-gate, snow, carpet, lantern, and trapdoor cases.
 */
class ClientWorldPlacementConformanceTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final int STONE = STATES.firstStateOf("minecraft:stone");
    private static final int WATER = STATES.stateOf("minecraft:water", "level=0");
    private static final String[] FACINGS = {"north", "south", "east", "west"};
    private static final Direction[] HORIZ_DIRS = {Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH};
    private static final int[][] HORIZ_OFF = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final RandomSource NMS_RANDOM = RandomSource.create(42L);
    private static Unsafe UNSAFE;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        UNSAFE = (Unsafe) f.get(null);
        initPaperGlobalConfig();
        bindVanillaBlockTags();
    }

    private static void initPaperGlobalConfig() throws Exception {
        io.papermc.paper.configuration.GlobalConfiguration cfg =
                (io.papermc.paper.configuration.GlobalConfiguration) UNSAFE.allocateInstance(io.papermc.paper.configuration.GlobalConfiguration.class);
        cfg.unsupportedSettings = cfg.new UnsupportedSettings();
        Field inst = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredField("instance");
        inst.setAccessible(true);
        inst.set(null, cfg);
    }

    /** Loads vanilla block tags directly from the Minecraft 1.21.11 data resources inside the Paper dev jar. */
    private static void bindVanillaBlockTags() throws Exception {
        Map<Block, Set<TagKey<Block>>> tagsByBlock = new HashMap<>();
        String[] tagNames = {
                "fences", "wooden_fences", "walls", "fence_gates", "leaves", "shulker_boxes",
                "snow_layer_can_survive_on", "snow_layer_cannot_survive_on",
                "doors", "wooden_doors", "trapdoors", "wooden_trapdoors", "stairs", "slabs",
                "impermeable", "ice", "wool_carpets", "needs_stone_tool", "mineable/axe", "mineable/pickaxe",
                "wall_post_override", "climbable", "standing_signs", "wall_signs", "banners"
        };
        Map<String, Set<Block>> cache = new HashMap<>();
        for (String name : tagNames) {
            Set<Block> members = resolveTag(name, cache);
            TagKey<Block> key = TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace(name));
            for (Block b : members) {
                tagsByBlock.computeIfAbsent(b, k -> new HashSet<>()).add(key);
            }
        }
        Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
        bind.setAccessible(true);
        for (Block block : BuiltInRegistries.BLOCK) {
            Set<TagKey<Block>> set = tagsByBlock.getOrDefault(block, Set.of());
            bind.invoke(BuiltInRegistries.BLOCK.wrapAsHolder(block), set);
        }
        assertTrue(Blocks.OAK_FENCE.defaultBlockState().is(BlockTags.WOODEN_FENCES));
        assertTrue(Blocks.COBBLESTONE_WALL.defaultBlockState().is(BlockTags.WALLS));
        assertTrue(Blocks.OAK_LEAVES.defaultBlockState().is(BlockTags.LEAVES));
    }

    private static final Pattern TAG_ENTRY = Pattern.compile("\"(#?[a-z0-9_:/.-]+)\"");

    private static Set<Block> resolveTag(String tagName, Map<String, Set<Block>> cache) throws Exception {
        Set<Block> existing = cache.get(tagName);
        if (existing != null) {
            return existing;
        }
        Set<Block> out = new HashSet<>();
        cache.put(tagName, out);
        String clean = tagName.startsWith("minecraft:") ? tagName.substring("minecraft:".length()) : tagName;
        String path = "/data/minecraft/tags/block/" + clean + ".json";
        try (InputStream in = Block.class.getResourceAsStream(path)) {
            if (in == null) {
                return out;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int idx = json.indexOf("\"values\"");
            if (idx < 0) {
                return out;
            }
            Matcher m = TAG_ENTRY.matcher(json.substring(idx + 8));
            while (m.find()) {
                String val = m.group(1);
                if ("id".equals(val) || "required".equals(val)) {
                    continue;
                }
                if (val.startsWith("#")) {
                    out.addAll(resolveTag(val.substring(1), cache));
                } else {
                    Identifier id = Identifier.parse(val);
                    BuiltInRegistries.BLOCK.getOptional(id).ifPresent(out::add);
                }
            }
        }
        return out;
    }

    // --- Minimal Level proxy backed by a Map<BlockPos, BlockState> for NMS getStateForPlacement & updateShape ---

    private static final class MapWorld {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Level nmsLevel;
        final LevelReader reader;
        final ScheduledTickAccess ticks;

        MapWorld() {
            this.nmsLevel = createLevelProxy(blocks);
            this.reader = nmsLevel;
            this.ticks = new ScheduledTickAccess() {
                @Override
                public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, net.minecraft.world.ticks.TickPriority priority) {
                    return new ScheduledTick<>(type, pos, 0L, priority, 0L);
                }

                @Override
                public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) {
                    return new ScheduledTick<>(type, pos, 0L, 0L);
                }

                @Override
                public LevelTickAccess<Block> getBlockTicks() {
                    return BlackholeTickAccess.emptyLevelList();
                }

                @Override
                public void scheduleTick(BlockPos pos, Block block, int delay, net.minecraft.world.ticks.TickPriority priority) { }

                @Override
                public void scheduleTick(BlockPos pos, Block block, int delay) { }

                @Override
                public LevelTickAccess<Fluid> getFluidTicks() {
                    return BlackholeTickAccess.emptyLevelList();
                }

                @Override
                public void scheduleTick(BlockPos pos, Fluid fluid, int delay, net.minecraft.world.ticks.TickPriority priority) { }

                @Override
                public void scheduleTick(BlockPos pos, Fluid fluid, int delay) { }
            };
        }

        void set(int x, int y, int z, int stateId) {
            blocks.put(new BlockPos(x, y, z), Block.BLOCK_STATE_REGISTRY.byId(stateId));
        }

        BlockState get(int x, int y, int z) {
            return blocks.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }

        BlockPlaceContext placeContext(String horizontalFacing, BlockPos clicked, Direction face, Vec3 clickLoc, ItemStack stack) {
            Direction horiz = toNmsDir(horizontalFacing);
            BlockHitResult hit = new BlockHitResult(clickLoc, face, clicked, false);
            return new BlockPlaceContext(nmsLevel, null, InteractionHand.MAIN_HAND, stack, hit) {
                @Override
                public Direction getHorizontalDirection() {
                    return horiz;
                }

                @Override
                public Direction[] getNearestLookingDirections() {
                    return new Direction[] {horiz, Direction.DOWN, Direction.UP, horiz.getClockWise(), horiz.getCounterClockWise(), horiz.getOpposite()};
                }

                @Override
                public boolean isSecondaryUseActive() {
                    return false;
                }
            };
        }
    }

    /** Lightweight concrete Level subclass allocated without running constructor side effects. */
    private static final class MapBackedLevel extends Level {
        Map<BlockPos, BlockState> map;

        private MapBackedLevel() {
            super(null, null, null, null, false, false, 0L, 0, null, null, null, null, null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return map.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public boolean isOutsideBuildHeight(int y) {
            return y < -64 || y >= 320;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMaxY() {
            return 319;
        }

        @Override
        public boolean hasNeighborSignal(BlockPos pos) {
            return false;
        }

        @Override
        public boolean hasSignal(BlockPos pos, Direction direction) {
            return false;
        }

        @Override
        public int getBestNeighborSignal(BlockPos pos) {
            return 0;
        }

        @Override
        public boolean isInWorldBounds(BlockPos pos) {
            return pos.getY() >= -64 && pos.getY() < 320;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) { }
        @Override public void playSeededSound(net.minecraft.world.entity.Entity e, double x, double y, double z, Holder<net.minecraft.sounds.SoundEvent> s, net.minecraft.sounds.SoundSource c, float v, float p, long seed) { }
        @Override public void playSeededSound(net.minecraft.world.entity.Entity e, net.minecraft.world.entity.Entity target, Holder<net.minecraft.sounds.SoundEvent> s, net.minecraft.sounds.SoundSource c, float v, float p, long seed) { }
        @Override public String gatherChunkSourceStats() { return ""; }
        @Override public net.minecraft.world.entity.Entity getEntity(int id) { return null; }
        @Override public net.minecraft.world.TickRateManager tickRateManager() { return null; }
        @Override public net.minecraft.world.level.saveddata.maps.MapItemSavedData getMapData(net.minecraft.world.level.saveddata.maps.MapId id) { return null; }
        @Override public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) { }
        @Override public net.minecraft.world.scores.Scoreboard getScoreboard() { return null; }
        @Override public net.minecraft.world.item.crafting.RecipeAccess recipeAccess() { return null; }
        @Override public net.minecraft.world.level.entity.LevelEntityGetter<net.minecraft.world.entity.Entity> getEntities() { return null; }
        @Override public java.util.Collection<net.minecraft.world.entity.boss.enderdragon.EnderDragonPart> dragonParts() { return List.of(); }
        @Override public net.minecraft.world.item.alchemy.PotionBrewing potionBrewing() { return null; }
        @Override public net.minecraft.world.level.block.entity.FuelValues fuelValues() { return null; }
        @Override public net.minecraft.world.level.chunk.ChunkSource getChunkSource() { return null; }
        @Override public void levelEvent(net.minecraft.world.entity.Entity entity, int type, BlockPos pos, int data) { }
        @Override public void gameEvent(Holder<net.minecraft.world.level.gameevent.GameEvent> event, Vec3 pos, net.minecraft.world.level.gameevent.GameEvent.Context ctx) { }
        @Override public List<? extends Player> players() { return List.of(); }
        @Override public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) { return null; }
        @Override public int getSeaLevel() { return 63; }
        @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() { return null; }
        @Override public float getShade(Direction direction, boolean shade) { return 1.0f; }
        @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() { return null; }
        @Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() { return null; }
        @Override public net.minecraft.core.RegistryAccess registryAccess() { return null; }
        @Override public net.minecraft.world.level.chunk.ChunkAccess getChunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean create) { return null; }
        @Override public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types heightmapType, int x, int z) { return 0; }
        @Override public int getSkyDarken() { return 0; }
        @Override public net.minecraft.world.level.biome.BiomeManager getBiomeManager() { return null; }
        @Override public net.minecraft.world.attribute.EnvironmentAttributeSystem environmentAttributes() { return null; }
        @Override public void explode(net.minecraft.world.entity.Entity e, net.minecraft.world.damagesource.DamageSource s, net.minecraft.world.level.ExplosionDamageCalculator c, double x, double y, double z, float r, boolean f, net.minecraft.world.level.Level.ExplosionInteraction i, net.minecraft.core.particles.ParticleOptions p1, net.minecraft.core.particles.ParticleOptions p2, net.minecraft.util.random.WeightedList<net.minecraft.core.particles.ExplosionParticleInfo> l, Holder<net.minecraft.sounds.SoundEvent> se) { }
        @Override public net.minecraft.resources.ResourceKey<net.minecraft.world.level.dimension.LevelStem> getTypeKey() { return null; }
        @Override public net.minecraft.server.level.ServerLevel getMinecraftWorld() { return null; }
        @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
        @Override public net.minecraft.world.level.storage.LevelData getLevelData() { return null; }
        @Override public void setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData data) { }
        @Override public net.minecraft.world.level.storage.LevelData.RespawnData getRespawnData() { return null; }
    }

    private static Level createLevelProxy(Map<BlockPos, BlockState> blocks) {
        try {
            MapBackedLevel lvl = (MapBackedLevel) UNSAFE.allocateInstance(MapBackedLevel.class);
            lvl.map = blocks;
            return lvl;
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        }
    }

    private static Direction toNmsDir(String facing) {
        return switch (facing) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException(facing);
        };
    }

    private static Direction toNmsFace(BlockFace face) {
        return switch (face) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    // --- ClientWorld harness ---

    private static final class WorldHarness {
        final ClientWorld world = new ClientWorld(STATES);
        final MapWorld nms = new MapWorld();
        long seq = 0;
        int pingId = 0;
        int useSeq = 1;

        WorldHarness() {
            world.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.Login(1, 2, OVERWORLD)));
            byte[] airChunk = uniformChunk(24, AIR);
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    world.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.LevelChunk(cx, cz, airChunk)));
                }
            }
            sync();
        }

        void set(int x, int y, int z, int stateId) {
            world.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.BlockUpdate(new BlockPosition(x, y, z), stateId)));
            nms.set(x, y, z, stateId);
        }

        void sync() {
            int id = ++pingId;
            world.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.Ping(id)));
            world.onEvent(new TimelineEvent.Acknowledged(seq++, seq, id, 0, 0));
        }

        int sendUse(int cx, int cy, int cz, BlockFace face, Vec3d clickLoc) {
            int s = useSeq++;
            world.onEvent(new TimelineEvent.Inbound(seq++, seq, 0,
                    new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(cx, cy, cz), face, clickLoc, false, false, s)));
            return s;
        }
    }

    private static byte[] uniformChunk(int sections, int state) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < sections; i++) {
            data.write(0);
            data.write(state == AIR ? 0 : 16);
            data.write(0);
            varInt(data, state);
            data.write(0);
            varInt(data, 0);
        }
        varInt(body, data.size());
        body.writeBytes(data.toByteArray());
        varInt(body, 0);
        return body.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    // --- 2,000+ Differential Tests ---

    @Test
    void connectedFencesPanesAndWallsMatchNmsOver1200Cases() {
        SplittableRandom rng = new SplittableRandom(20261123L);
        int[] neighborPool = {
                AIR,
                STONE,
                STATES.stateOf("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false"),
                STATES.stateOf("minecraft:spruce_fence", "east=false,north=false,south=false,waterlogged=false,west=false"),
                STATES.stateOf("minecraft:nether_brick_fence", "east=false,north=false,south=false,waterlogged=false,west=false"),
                STATES.stateOf("minecraft:iron_bars", "east=false,north=false,south=false,waterlogged=false,west=false"),
                STATES.stateOf("minecraft:glass_pane", "east=false,north=false,south=false,waterlogged=false,west=false"),
                STATES.stateOf("minecraft:red_stained_glass_pane", "east=false,north=false,south=false,waterlogged=false,west=false"),
                STATES.stateOf("minecraft:cobblestone_wall", "east=none,north=none,south=none,up=true,waterlogged=false,west=none"),
                STATES.stateOf("minecraft:oak_fence_gate", "facing=north,in_wall=false,open=false,powered=false"),
                STATES.stateOf("minecraft:oak_fence_gate", "facing=east,in_wall=false,open=false,powered=false"),
                STATES.firstStateOf("minecraft:carved_pumpkin"),
                STATES.firstStateOf("minecraft:melon"),
                STATES.firstStateOf("minecraft:pumpkin"),
                STATES.firstStateOf("minecraft:glass"),
                STATES.stateOf("minecraft:oak_slab", "type=bottom,waterlogged=false"),
                STATES.stateOf("minecraft:oak_slab", "type=double,waterlogged=false")
        };
        String[] placedKeys = {
                "minecraft:oak_fence", "minecraft:nether_brick_fence",
                "minecraft:iron_bars", "minecraft:glass_pane", "minecraft:red_stained_glass_pane",
                "minecraft:cobblestone_wall", "minecraft:deepslate_brick_wall"
        };
        int[] wallAbovePool = {
                AIR,
                STONE,
                STATES.stateOf("minecraft:cobblestone_wall", "east=none,north=none,south=none,up=true,waterlogged=false,west=none"),
                STATES.stateOf("minecraft:cobblestone_wall", "east=low,north=none,south=none,up=false,waterlogged=false,west=low"),
                STATES.stateOf("minecraft:cobblestone_wall", "east=none,north=low,south=low,up=true,waterlogged=false,west=none")
        };
        int checked = 0;
        for (int trial = 0; trial < 1200; trial++) {
            final int t = trial;
            WorldHarness h = new WorldHarness();
            String placedKey = placedKeys[trial % placedKeys.length];
            boolean isWall = placedKey.endsWith("_wall");
            boolean inWater = rng.nextInt(3) == 0;
            int tx = 5, ty = 64, tz = 5;
            // Solid floor below (or wall below for wall stacking!)
            int below = (isWall && rng.nextInt(4) == 0)
                    ? STATES.stateOf("minecraft:cobblestone_wall", "east=none,north=none,south=none,up=true,waterlogged=false,west=none")
                    : STONE;
            int above = isWall ? wallAbovePool[rng.nextInt(wallAbovePool.length)] : AIR;
            h.set(tx, ty - 1, tz, below);
            h.set(tx, ty + 1, tz, above);
            h.set(tx, ty, tz, inWater ? WATER : AIR);
            // Click face is UP on (tx, ty-1, tz) unless below is wall (walls are not interactive, valid click!)
            for (int i = 0; i < 4; i++) {
                int ns = neighborPool[rng.nextInt(neighborPool.length)];
                int nx = tx + HORIZ_OFF[i][0];
                int nz = tz + HORIZ_OFF[i][2];
                h.set(nx, ty, nz, ns);
                // If neighbour is a wall, give it a deterministic block above (air or stone)
                if (STATES.block(ns).endsWith("_wall")) {
                    h.set(nx, ty + 1, nz, rng.nextBoolean() ? AIR : STONE);
                }
            }
            h.sync();

            int useSeq = h.sendUse(tx, ty - 1, tz, BlockFace.UP, new Vec3d(tx + 0.5, ty, tz + 0.5));
            ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, placedKey, "north");
            assertNotNull(plan, "trial=" + trial + " key=" + placedKey + " below=" + STATES.block(below) + " above=" + STATES.block(above));

            Block nmsBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse(placedKey));
            BlockPlaceContext ctx = h.nms.placeContext("north", new BlockPos(tx, ty - 1, tz), Direction.UP,
                    new Vec3(tx + 0.5, ty, tz + 0.5), new ItemStack(nmsBlock.asItem()));
            BlockState expectedPrimary = nmsBlock.getStateForPlacement(ctx);
            assertNotNull(expectedPrimary);
            int expectedPrimaryId = Block.getId(expectedPrimary);
            assertEquals(expectedPrimaryId, plan.primary().state(),
                    () -> "Primary mismatch trial=" + t + " key=" + placedKey
                            + " expected=" + expectedPrimary + " got=" + STATES.block(plan.primary().state()) + "[" + STATES.properties(plan.primary().state()) + "]");

            // Now apply primary in NMS map and check updateShape of all 6 neighbours!
            BlockPos targetPos = new BlockPos(tx, ty, tz);
            h.nms.blocks.put(targetPos, expectedPrimary);
            Map<BlockPosition, Integer> actualExtras = new HashMap<>();
            for (ClientWorld.Placement extra : plan.extras()) {
                actualExtras.put(extra.position(), extra.state());
            }
            for (int i = 0; i < 4; i++) {
                Direction dir = HORIZ_DIRS[i];
                int nx = tx + HORIZ_OFF[i][0];
                int nz = tz + HORIZ_OFF[i][2];
                BlockPos nPos = new BlockPos(nx, ty, nz);
                BlockState beforeNeighbor = h.nms.get(nx, ty, nz);
                BlockState afterNeighbor = beforeNeighbor.updateShape(
                        h.nms.reader, h.nms.ticks, nPos, dir.getOpposite(), targetPos, expectedPrimary, NMS_RANDOM);
                int beforeId = Block.getId(beforeNeighbor);
                int afterId = Block.getId(afterNeighbor);
                int gotId = actualExtras.getOrDefault(new BlockPosition(nx, ty, nz), beforeId);
                assertEquals(afterId, gotId,
                        () -> "Neighbor updateShape mismatch trial=" + t + " placed=" + placedKey + " dir=" + dir
                                + " before=" + beforeNeighbor + " expectedAfter=" + afterNeighbor
                                + " got=" + STATES.block(gotId) + "[" + STATES.properties(gotId) + "]");
            }
            if (STATES.block(below).endsWith("_wall")) {
                BlockPos bPos = new BlockPos(tx, ty - 1, tz);
                BlockState beforeBelow = h.nms.get(tx, ty - 1, tz);
                BlockState afterBelow = beforeBelow.updateShape(
                        h.nms.reader, h.nms.ticks, bPos, Direction.UP, targetPos, expectedPrimary, NMS_RANDOM);
                int gotBelow = actualExtras.getOrDefault(new BlockPosition(tx, ty - 1, tz), Block.getId(beforeBelow));
                assertEquals(Block.getId(afterBelow), gotBelow,
                        () -> "Below wall updateShape mismatch trial=" + t + " expected=" + afterBelow
                                + " got=" + STATES.block(gotBelow) + "[" + STATES.properties(gotBelow) + "]");
            }
            checked++;
        }
        assertEquals(1200, checked);
    }

    @Test
    void cornerAndWaterloggedStairsMatchNmsOver500Cases() {
        SplittableRandom rng = new SplittableRandom(998877L);
        List<Integer> stairPool = new ArrayList<>();
        stairPool.add(AIR);
        stairPool.add(STONE);
        for (String f : FACINGS) {
            for (String half : new String[] {"bottom", "top"}) {
                stairPool.add(STATES.stateOf("minecraft:oak_stairs", "facing=" + f + ",half=" + half + ",shape=straight,waterlogged=false"));
                stairPool.add(STATES.stateOf("minecraft:stone_brick_stairs", "facing=" + f + ",half=" + half + ",shape=straight,waterlogged=true"));
            }
        }
        int checked = 0;
        for (int trial = 0; trial < 500; trial++) {
            final int t = trial;
            WorldHarness h = new WorldHarness();
            int tx = 6, ty = 64, tz = 6;
            boolean inWater = rng.nextInt(3) == 0;
            String facing = FACINGS[rng.nextInt(4)];
            BlockFace clickFace = switch (rng.nextInt(4)) {
                case 0 -> BlockFace.UP;
                case 1 -> BlockFace.DOWN;
                case 2 -> BlockFace.NORTH;
                default -> BlockFace.EAST;
            };
            int cx = tx - clickFace.stepX();
            int cy = ty - clickFace.stepY();
            int cz = tz - clickFace.stepZ();
            h.set(cx, cy, cz, STONE);
            h.set(tx, ty, tz, inWater ? WATER : AIR);
            for (int i = 0; i < 4; i++) {
                int nx = tx + HORIZ_OFF[i][0];
                int nz = tz + HORIZ_OFF[i][2];
                if (nx == cx && ty == cy && nz == cz) {
                    continue;
                }
                h.set(nx, ty, nz, stairPool.get(rng.nextInt(stairPool.size())));
            }
            h.sync();

            double localY = rng.nextBoolean() ? 0.25 : 0.75;
            Vec3d loc = new Vec3d(tx + 0.5, ty + localY, tz + 0.5);
            int useSeq = h.sendUse(cx, cy, cz, clickFace, loc);
            ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, "minecraft:oak_stairs", facing);
            assertNotNull(plan, "stair trial=" + trial);

            BlockPlaceContext ctx = h.nms.placeContext(facing, new BlockPos(cx, cy, cz), toNmsFace(clickFace),
                    new Vec3(loc.x(), loc.y(), loc.z()), new ItemStack(Blocks.OAK_STAIRS.asItem()));
            BlockState expectedPrimary = Blocks.OAK_STAIRS.getStateForPlacement(ctx);
            assertNotNull(expectedPrimary);
            assertEquals(Block.getId(expectedPrimary), plan.primary().state(),
                    () -> "Stair primary mismatch trial=" + t + " expected=" + expectedPrimary
                            + " got=" + STATES.properties(plan.primary().state()));

            BlockPos targetPos = new BlockPos(tx, ty, tz);
            h.nms.blocks.put(targetPos, expectedPrimary);
            Map<BlockPosition, Integer> actualExtras = new HashMap<>();
            for (ClientWorld.Placement extra : plan.extras()) {
                actualExtras.put(extra.position(), extra.state());
            }
            for (int i = 0; i < 4; i++) {
                int nx = tx + HORIZ_OFF[i][0];
                int nz = tz + HORIZ_OFF[i][2];
                BlockPos nPos = new BlockPos(nx, ty, nz);
                BlockState beforeNeighbor = h.nms.get(nx, ty, nz);
                if (!STATES.block(Block.getId(beforeNeighbor)).endsWith("_stairs")) {
                    continue;
                }
                BlockState afterNeighbor = beforeNeighbor.updateShape(
                        h.nms.reader, h.nms.ticks, nPos, HORIZ_DIRS[i].getOpposite(), targetPos, expectedPrimary, NMS_RANDOM);
                int gotId = actualExtras.getOrDefault(new BlockPosition(nx, ty, nz), Block.getId(beforeNeighbor));
                assertEquals(Block.getId(afterNeighbor), gotId,
                        () -> "Neighbor stair mismatch trial=" + t + " expected=" + afterNeighbor
                                + " got=" + STATES.properties(gotId));
            }
            checked++;
        }
        assertEquals(500, checked);
    }

    @Test
    void doorsFenceGatesSnowCarpetsLanternsAndTrapdoorsMatchNmsOver500Cases() {
        SplittableRandom rng = new SplittableRandom(776655L);
        int checked = 0;

        // 1. Doors (150 cases: varying facing, left/right solid neighbours, click sub-block frac)
        String[] doors = {"minecraft:oak_door", "minecraft:copper_door", "minecraft:iron_door"};
        for (int trial = 0; trial < 150; trial++) {
            WorldHarness h = new WorldHarness();
            int tx = 4, ty = 64, tz = 4;
            h.set(tx, ty - 1, tz, STONE);
            String facing = FACINGS[rng.nextInt(4)];
            String doorKey = doors[trial % doors.length];
            for (int i = 0; i < 4; i++) {
                int nx = tx + HORIZ_OFF[i][0], nz = tz + HORIZ_OFF[i][2];
                if (rng.nextInt(3) == 0) h.set(nx, ty, nz, STONE);
                if (rng.nextInt(3) == 0) h.set(nx, ty + 1, nz, STONE);
            }
            h.sync();
            double fx = rng.nextBoolean() ? 0.2 : 0.8;
            double fz = rng.nextBoolean() ? 0.2 : 0.8;
            Vec3d loc = new Vec3d(tx + fx, ty, tz + fz);
            int useSeq = h.sendUse(tx, ty - 1, tz, BlockFace.UP, loc);
            ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, doorKey, facing);
            assertNotNull(plan, "door trial=" + trial);
            assertEquals(1, plan.extras().size());

            Block nmsDoor = BuiltInRegistries.BLOCK.getValue(Identifier.parse(doorKey));
            BlockPlaceContext ctx = h.nms.placeContext(facing, new BlockPos(tx, ty - 1, tz), Direction.UP,
                    new Vec3(loc.x(), loc.y(), loc.z()), new ItemStack(nmsDoor.asItem()));
            BlockState expectedLower = nmsDoor.getStateForPlacement(ctx);
            assertNotNull(expectedLower);
            BlockState expectedUpper = expectedLower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
            assertEquals(Block.getId(expectedLower), plan.primary().state(), "door lower trial=" + trial);
            assertEquals(Block.getId(expectedUpper), plan.extras().get(0).state(), "door upper trial=" + trial);
            assertEquals(new BlockPosition(tx, ty + 1, tz), plan.extras().get(0).position());
            checked++;
        }

        // 2. Fence Gates (150 cases: with adjacent walls, fences, air, stone)
        int[] gateNeighbors = {
                AIR, STONE,
                STATES.stateOf("minecraft:cobblestone_wall", "east=none,north=none,south=none,up=true,waterlogged=false,west=none"),
                STATES.stateOf("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false")
        };
        for (int trial = 0; trial < 150; trial++) {
            WorldHarness h = new WorldHarness();
            int tx = 4, ty = 64, tz = 4;
            h.set(tx, ty - 1, tz, STONE);
            String facing = FACINGS[rng.nextInt(4)];
            for (int i = 0; i < 4; i++) {
                h.set(tx + HORIZ_OFF[i][0], ty, tz + HORIZ_OFF[i][2], gateNeighbors[rng.nextInt(gateNeighbors.length)]);
            }
            h.sync();
            int useSeq = h.sendUse(tx, ty - 1, tz, BlockFace.UP, new Vec3d(tx + 0.5, ty, tz + 0.5));
            ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, "minecraft:oak_fence_gate", facing);
            assertNotNull(plan, "gate trial=" + trial);
            BlockPlaceContext ctx = h.nms.placeContext(facing, new BlockPos(tx, ty - 1, tz), Direction.UP,
                    new Vec3(tx + 0.5, ty, tz + 0.5), new ItemStack(Blocks.OAK_FENCE_GATE.asItem()));
            BlockState expected = Blocks.OAK_FENCE_GATE.getStateForPlacement(ctx);
            assertNotNull(expected);
            assertEquals(Block.getId(expected), plan.primary().state(), "gate primary trial=" + trial);
            checked++;
        }

        // 3. Snow layers (fresh + stacking layers 1..7 -> 2..8: 70 cases)
        for (int layers = 0; layers <= 7; layers++) {
            for (int rep = 0; rep < 10; rep++) {
                WorldHarness h = new WorldHarness();
                int tx = 3, ty = 64, tz = 3;
                h.set(tx, ty - 1, tz, STONE);
                if (layers >= 1) {
                    h.set(tx, ty, tz, STATES.stateOf("minecraft:snow", "layers=" + layers));
                }
                h.sync();
                int useSeq = layers == 0
                        ? h.sendUse(tx, ty - 1, tz, BlockFace.UP, new Vec3d(tx + 0.5, ty, tz + 0.5))
                        : h.sendUse(tx, ty, tz, BlockFace.UP, new Vec3d(tx + 0.5, ty + layers * 0.125, tz + 0.5));
                ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, "minecraft:snow", "north");
                assertNotNull(plan, "snow layers=" + layers);
                BlockPos clickPos = layers == 0 ? new BlockPos(tx, ty - 1, tz) : new BlockPos(tx, ty, tz);
                BlockPlaceContext ctx = h.nms.placeContext("north", clickPos, Direction.UP,
                        new Vec3(tx + 0.5, ty + 0.5, tz + 0.5), new ItemStack(Blocks.SNOW.asItem()));
                BlockState expected = Blocks.SNOW.getStateForPlacement(ctx);
                assertNotNull(expected);
                assertEquals(Block.getId(expected), plan.primary().state(), "snow layers=" + layers);
                assertEquals(new BlockPosition(tx, ty, tz), plan.primary().position());
                checked++;
            }
        }

        // 4. Carpets + Lanterns + Trapdoors (130 cases)
        String[] carpets = {"minecraft:white_carpet", "minecraft:red_carpet", "minecraft:moss_carpet"};
        String[] lanterns = {"minecraft:lantern", "minecraft:soul_lantern", "minecraft:copper_lantern"};
        for (int trial = 0; trial < 130; trial++) {
            WorldHarness h = new WorldHarness();
            int tx = 5, ty = 64, tz = 5;
            if (trial < 40) {
                h.set(tx, ty - 1, tz, STONE);
                h.sync();
                String carpet = carpets[trial % carpets.length];
                int useSeq = h.sendUse(tx, ty - 1, tz, BlockFace.UP, new Vec3d(tx + 0.5, ty, tz + 0.5));
                ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, carpet, "north");
                assertNotNull(plan, "carpet trial=" + trial);
                Block nmsB = BuiltInRegistries.BLOCK.getValue(Identifier.parse(carpet));
                assertEquals(Block.getId(nmsB.defaultBlockState()), plan.primary().state());
            } else if (trial < 90) {
                boolean hanging = (trial % 2) == 0;
                boolean inWater = (trial % 3) == 0;
                if (hanging) {
                    h.set(tx, ty + 1, tz, STONE);
                } else {
                    h.set(tx, ty - 1, tz, STONE);
                }
                h.set(tx, ty, tz, inWater ? WATER : AIR);
                h.sync();
                String lanternKey = lanterns[trial % lanterns.length];
                int cy = hanging ? ty + 1 : ty - 1;
                BlockFace face = hanging ? BlockFace.DOWN : BlockFace.UP;
                int useSeq = h.sendUse(tx, cy, tz, face, new Vec3d(tx + 0.5, ty + 0.5, tz + 0.5));
                ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, lanternKey, "north");
                assertNotNull(plan, "lantern trial=" + trial);
                Block nmsB = BuiltInRegistries.BLOCK.getValue(Identifier.parse(lanternKey));
                BlockPlaceContext ctx = h.nms.placeContext("north", new BlockPos(tx, cy, tz), toNmsFace(face),
                        new Vec3(tx + 0.5, ty + 0.5, tz + 0.5), new ItemStack(nmsB.asItem()));
                BlockState expected = nmsB.getStateForPlacement(ctx);
                assertNotNull(expected);
                assertEquals(Block.getId(expected), plan.primary().state(), "lantern trial=" + trial);
            } else {
                boolean inWater = (trial % 2) == 0;
                String facing = FACINGS[trial % 4];
                h.set(tx, ty - 1, tz, STONE);
                h.set(tx, ty, tz, inWater ? WATER : AIR);
                h.sync();
                int useSeq = h.sendUse(tx, ty - 1, tz, BlockFace.UP, new Vec3d(tx + 0.5, ty, tz + 0.5));
                ClientWorld.PlacementPlan plan = h.world.placementPlanForItem(useSeq, "minecraft:oak_trapdoor", facing);
                assertNotNull(plan, "trapdoor trial=" + trial);
                BlockPlaceContext ctx = h.nms.placeContext(facing, new BlockPos(tx, ty - 1, tz), Direction.UP,
                        new Vec3(tx + 0.5, ty, tz + 0.5), new ItemStack(Blocks.OAK_TRAPDOOR.asItem()));
                BlockState expected = Blocks.OAK_TRAPDOOR.getStateForPlacement(ctx);
                assertNotNull(expected);
                assertEquals(Block.getId(expected), plan.primary().state(), "trapdoor trial=" + trial);
            }
            checked++;
        }
        assertEquals(510, checked);
    }
}
