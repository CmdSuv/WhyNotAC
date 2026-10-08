package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Shape;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The block-state data whynotac-world ships ({@link BlockStates#RESOURCE}) comes from the official client
 * ({@code tools/realclient/agent/.../BlockDataDump}; the real-client CI job regenerates it and fails on
 * any difference). This test checks it against the server WhyNotAC runs on: every id, block, property,
 * flag, friction and fluid must be equal, and every collision shape must have the same geometry. The
 * one allowed difference is the shape's kind: Paper's collision patches build as {@code ArrayVoxelShape}
 * what vanilla builds as {@code CubeVoxelShape} (equivalent on the power-of-two grids cube shapes use).
 */
class BlockStatesDataTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Builds the data from the live registry, in global-id order. */
    @SuppressWarnings("deprecation") // blocksMotion(): deprecated for new code, still what movement reads
    static BlockStates generate() throws ReflectiveOperationException {
        Field discreteField = VoxelShape.class.getDeclaredField("shape");
        discreteField.setAccessible(true);
        BlockStates.Builder builder = new BlockStates.Builder(SharedConstants.getCurrentVersion().name());
        for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) {
                fail("no block state with id " + id);
            }
            Block block = state.getBlock();
            VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
            int flags = 0;
            if (state.isAir()) {
                flags |= BlockStates.AIR;
            }
            if (block.hasDynamicShape()) {
                flags |= BlockStates.DYNAMIC_SHAPE;
            }
            if (block instanceof LiquidBlock || block instanceof PowderSnowBlock || block instanceof ScaffoldingBlock) {
                flags |= BlockStates.CONTEXTUAL_COLLISION;
            }
            if (state.hasOffsetFunction()) {
                flags |= BlockStates.OFFSET;
            }
            if (state.canBeReplaced()) {
                flags |= BlockStates.REPLACEABLE;
            }
            if (state.blocksMotion()) {
                flags |= BlockStates.BLOCKS_MOTION;
            }
            if (state.requiresCorrectToolForDrops()) {
                flags |= BlockStates.TOOL_REQUIRED;
            }
            builder.add(BuiltInRegistries.BLOCK.getKey(block).toString(), properties(state),
                    shape(collision, (DiscreteVoxelShape) discreteField.get(collision)), flags, block.getFriction(), block.getSpeedFactor(),
                    block.getJumpFactor(), state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), fluid(state.getFluidState()));
        }
        return builder.build();
    }

    private static String properties(BlockState state) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(entry.getKey().getName()).append('=').append(valueName(entry.getKey(), entry.getValue()));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Property<T> property, Comparable<?> value) {
        return property.getName((T) value);
    }

    private static Shape shape(VoxelShape shape, DiscreteVoxelShape discrete) {
        int sx = discrete.getXSize();
        int sy = discrete.getYSize();
        int sz = discrete.getZSize();
        long[] full = new long[(sx * sy * sz + 63) >>> 6];
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    if (discrete.isFull(x, y, z)) {
                        int bit = (x * sy + y) * sz + z;
                        full[bit >>> 6] |= 1L << bit;
                    }
                }
            }
        }
        List<AABB> aabbs = shape.toAabbs();
        double[] boxes = new double[aabbs.size() * 6];
        for (int i = 0; i < aabbs.size(); i++) {
            AABB b = aabbs.get(i);
            System.arraycopy(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ}, 0, boxes, i * 6, 6);
        }
        return new Shape(shape instanceof CubeVoxelShape ? Shape.Kind.CUBE : Shape.Kind.ARRAY, shape.getCoords(Direction.Axis.X).toDoubleArray(),
                shape.getCoords(Direction.Axis.Y).toDoubleArray(), shape.getCoords(Direction.Axis.Z).toDoubleArray(), full, boxes);
    }

    private static String fluid(FluidState fluid) {
        if (fluid.isEmpty()) {
            return "";
        }
        boolean falling = fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
        return BuiltInRegistries.FLUID.getKey(fluid.getType()) + "/" + fluid.getAmount() + "/" + (falling ? 1 : 0);
    }

    @Test
    void shippedDataMatchesTheServerRegistry() throws Exception {
        BlockStates server = generate();
        BlockStates shipped = BlockStates.vanilla();
        assertEquals(server.minecraftVersion(), shipped.minecraftVersion());
        assertEquals(server.size(), shipped.size(), "block state count");
        int kindOnly = 0;
        for (int id = 0; id < server.size(); id++) {
            String where = "state " + id + " (" + server.block(id) + "[" + server.properties(id) + "])";
            assertEquals(server.block(id), shipped.block(id), where);
            assertEquals(server.properties(id), shipped.properties(id), where);
            assertEquals(server.flags(id), shipped.flags(id), where);
            assertEquals(server.friction(id), shipped.friction(id), where);
            assertEquals(server.speedFactor(id), shipped.speedFactor(id), where);
            assertEquals(server.jumpFactor(id), shipped.jumpFactor(id), where);
            assertEquals(server.destroySpeed(id), shipped.destroySpeed(id), where);
            assertEquals(server.fluid(id), shipped.fluid(id), where);
            Shape ours = shipped.collisionShape(id);
            Shape theirs = server.collisionShape(id);
            assertTrue(ours.sameGeometry(theirs), where + ": collision geometry differs");
            if (ours.kind() != theirs.kind()) {
                assertEquals(Shape.Kind.CUBE, ours.kind(), where);
                assertEquals(Shape.Kind.ARRAY, theirs.kind(), where);
                for (int size : new int[] {ours.sizeX(), ours.sizeY(), ours.sizeZ()}) {
                    assertEquals(0, size & size - 1, where + ": a cube shape on a grid of " + size);
                }
                kindOnly++;
            }
        }
        assertTrue(kindOnly > 0, "Paper builds cube shapes as array shapes; if that changed, update this test and its note");
    }

    @Test
    void theTextFormatReadsBackToEqualData() throws Exception {
        BlockStates generated = generate();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer out = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            generated.write(out);
        }
        assertEquals(generated, BlockStates.read(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    void knownStatesHaveTheGameValues() throws Exception {
        BlockStates data = generate();
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        assertEquals("minecraft:stone", data.block(stone));
        assertEquals(1, data.collisionShape(stone).boxCount());
        assertEquals(Shape.Kind.CUBE, BlockStates.vanilla().collisionShape(stone).kind()); // vanilla; Paper: ARRAY
        int ice = Block.getId(Blocks.ICE.defaultBlockState());
        assertEquals(0.98f, data.friction(ice));
        int soulSand = Block.getId(Blocks.SOUL_SAND.defaultBlockState());
        assertEquals(0.4f, data.speedFactor(soulSand));
        int honey = Block.getId(Blocks.HONEY_BLOCK.defaultBlockState());
        assertEquals(0.5f, data.jumpFactor(honey));
        int air = Block.getId(Blocks.AIR.defaultBlockState());
        assertTrue(data.isAir(air) && data.collisionShape(air).isEmpty());
        int water = Block.getId(Blocks.WATER.defaultBlockState());
        assertEquals("minecraft:water/8/0", data.fluid(water));
        assertTrue(data.has(water, BlockStates.CONTEXTUAL_COLLISION));
        int bamboo = Block.getId(Blocks.BAMBOO.defaultBlockState());
        assertTrue(data.has(bamboo, BlockStates.OFFSET) && data.has(bamboo, BlockStates.DYNAMIC_SHAPE));
        assertEquals(Mth.ceillog2(Block.BLOCK_STATE_REGISTRY.size()), data.globalPaletteBits());
    }
}
