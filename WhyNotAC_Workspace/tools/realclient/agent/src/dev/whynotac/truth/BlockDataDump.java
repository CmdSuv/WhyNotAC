package dev.whynotac.truth;

import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Shape;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Writes whynotac-world's block-state data ({@link BlockStates}) from the <em>official, unmodified
 * client</em>: run on the client's own classpath, it bootstraps the game's registries and reads every
 * block state through Mojang's mappings. This is the authoritative source: Paper changes how shapes are
 * built (its collision patches make a full block an {@code ArrayVoxelShape} where vanilla has a
 * {@code CubeVoxelShape}), and the player's client is vanilla.
 *
 * <p>usage: {@code java -cp <client classpath>:<agent classes>:<whynotac-world jar> dev.whynotac.truth.BlockDataDump
 * <client mappings.txt> <out .txt.gz>}
 */
public final class BlockDataDump {

    private static final String BLOCK = "net.minecraft.world.level.block.Block";
    private static final String STATE = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase";
    private static final String HOLDER = "net.minecraft.world.level.block.state.StateHolder";
    private static final String PROPERTY = "net.minecraft.world.level.block.state.properties.Property";
    private static final String VOXEL = "net.minecraft.world.phys.shapes.VoxelShape";
    private static final String DISCRETE = "net.minecraft.world.phys.shapes.DiscreteVoxelShape";
    private static final String AABB = "net.minecraft.world.phys.AABB";
    private static final String FLUID_STATE = "net.minecraft.world.level.material.FluidState";
    private static final String GETTER = "net.minecraft.world.level.BlockGetter";
    private static final String POS = "net.minecraft.core.BlockPos";
    private static final String CONTEXT = "net.minecraft.world.phys.shapes.CollisionContext";
    private static final String BEHAVIOUR = "net.minecraft.world.level.block.state.BlockBehaviour";
    private static final String BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState";
    private static final Set<String> CLASSES = Set.of(BLOCK, STATE, HOLDER, PROPERTY, VOXEL, DISCRETE, AABB, FLUID_STATE, GETTER, POS, CONTEXT,
            "net.minecraft.SharedConstants", "net.minecraft.WorldVersion", "net.minecraft.server.Bootstrap", "net.minecraft.core.IdMapper",
            "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.Registry", "net.minecraft.world.level.EmptyBlockGetter",
            "net.minecraft.core.Direction$Axis", "net.minecraft.core.Direction", "net.minecraft.world.level.material.FlowingFluid", "net.minecraft.world.level.block.LiquidBlock",
            "net.minecraft.world.level.block.PowderSnowBlock", "net.minecraft.world.level.block.ScaffoldingBlock",
            "net.minecraft.world.phys.shapes.CubeVoxelShape", "net.minecraft.world.phys.shapes.Shapes", BEHAVIOUR, BLOCK_STATE,
            "net.minecraft.world.item.ItemStack", "net.minecraft.world.level.Level", "net.minecraft.world.entity.player.Player",
            "net.minecraft.world.InteractionHand", "net.minecraft.world.phys.BlockHitResult", "net.minecraft.world.item.context.BlockPlaceContext",
            "net.minecraft.world.level.LevelReader", "net.minecraft.world.level.ScheduledTickAccess", "net.minecraft.util.RandomSource",
            "net.minecraft.world.level.redstone.Orientation", "net.minecraft.world.level.LevelAccessor", "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.level.block.SnowyDirtBlock", "net.minecraft.world.level.block.FallingBlock");

    /**
     * Where the outline is compared with the origin's ({@link BlockStates#MOVING_OUTLINE}): an offset is a hash of the
     * position's x and z ({@code Mth.getSeed(x, 0, z)}), so these give it many values; y varies too.
     */
    static final int[][] MOVED_AT = movedAt();

    private final Mappings m;

    private BlockDataDump(Mappings m) {
        this.m = m;
    }

    public static void main(String[] args) throws Exception {
        BlockStates data = new BlockDataDump(Mappings.read(Path.of(args[0]), CLASSES)).dump();
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(Path.of(args[1]))), StandardCharsets.UTF_8)) {
            data.write(out);
        }
        System.out.println("{\"minecraft\":\"" + data.minecraftVersion() + "\",\"states\":" + data.size() + ",\"shapes\":" + data.shapeCount() + "}");
    }

    private BlockStates dump() throws ReflectiveOperationException {
        invokeStatic("net.minecraft.SharedConstants", "tryDetectVersion()");
        invokeStatic("net.minecraft.server.Bootstrap", "bootStrap()");
        Object version = invokeStatic("net.minecraft.SharedConstants", "getCurrentVersion()");
        String minecraft = (String) method("net.minecraft.WorldVersion", "name()").invoke(version);

        Object registry = staticField(BLOCK, "BLOCK_STATE_REGISTRY");
        Method size = method("net.minecraft.core.IdMapper", "size()");
        Method byId = method("net.minecraft.core.IdMapper", "byId(int)");
        Object blocks = staticField("net.minecraft.core.registries.BuiltInRegistries", "BLOCK");
        Object fluids = staticField("net.minecraft.core.registries.BuiltInRegistries", "FLUID");
        Method getKey = method("net.minecraft.core.Registry", "getKey(java.lang.Object)");
        Object emptyGetter = staticField("net.minecraft.world.level.EmptyBlockGetter", "INSTANCE");
        Object zero = staticField(POS, "ZERO");
        Object emptyContext = invokeStatic(CONTEXT, "empty()");
        Object fullBlock = invokeStatic("net.minecraft.world.phys.shapes.Shapes", "block()");
        Object falling = staticField("net.minecraft.world.level.material.FlowingFluid", "FALLING");
        Object[] axes = cls("net.minecraft.core.Direction$Axis").getEnumConstants(); // X, Y, Z
        Object[] directions = cls("net.minecraft.core.Direction").getEnumConstants(); // DOWN, UP, NORTH, SOUTH, WEST, EAST

        Method getBlock = method(STATE, "getBlock()");
        Method collision = method(STATE, "getCollisionShape(" + GETTER + "," + POS + "," + CONTEXT + ")");
        Method outline = method(STATE, "getShape(" + GETTER + "," + POS + "," + CONTEXT + ")");
        Method isAir = method(STATE, "isAir()");
        Method offset = method(STATE, "hasOffsetFunction()");
        Method replaceable = method(STATE, "canBeReplaced()");
        Method blocksMotion = method(STATE, "blocksMotion()");
        Method tool = method(STATE, "requiresCorrectToolForDrops()");
        Method suffocating = method(STATE, "isSuffocating(" + GETTER + "," + POS + ")");
        Method sturdy = method(STATE, "isFaceSturdy(" + GETTER + "," + POS + ",net.minecraft.core.Direction)");
        Method destroySpeed = method(STATE, "getDestroySpeed(" + GETTER + "," + POS + ")");
        Method fluidState = method(STATE, "getFluidState()");
        Method values = method(HOLDER, "getValues()");
        Method propertyName = method(PROPERTY, "getName()");
        Method valueName = method(PROPERTY, "getName(java.lang.Comparable)");
        Method dynamic = method(BLOCK, "hasDynamicShape()");
        Method friction = method(BLOCK, "getFriction()");
        Method speed = method(BLOCK, "getSpeedFactor()");
        Method jump = method(BLOCK, "getJumpFactor()");
        Method fluidEmpty = method(FLUID_STATE, "isEmpty()");
        Method fluidType = method(FLUID_STATE, "getType()");
        Method fluidAmount = method(FLUID_STATE, "getAmount()");
        Class<?> liquid = cls("net.minecraft.world.level.block.LiquidBlock");
        Class<?> powderSnow = cls("net.minecraft.world.level.block.PowderSnowBlock");
        Class<?> scaffolding = cls("net.minecraft.world.level.block.ScaffoldingBlock");
        // BlockStates.INTERACTIVE, CUSTOM_REPLACING, CUSTOM_PLACEMENT, NEIGHBOUR_REACTIVE: the methods a class may declare
        String level = "net.minecraft.world.level.Level";
        Method[] interactive = {
                method(BEHAVIOUR, "useItemOn(net.minecraft.world.item.ItemStack," + BLOCK_STATE + "," + level + "," + POS + ",net.minecraft.world.entity.player.Player,"
                        + "net.minecraft.world.InteractionHand,net.minecraft.world.phys.BlockHitResult)"),
                method(BEHAVIOUR, "useWithoutItem(" + BLOCK_STATE + "," + level + "," + POS + ",net.minecraft.world.entity.player.Player,"
                        + "net.minecraft.world.phys.BlockHitResult)")};
        Method[] replacing = {method(BEHAVIOUR, "canBeReplaced(" + BLOCK_STATE + ",net.minecraft.world.item.context.BlockPlaceContext)")};
        Method[] placement = {
                method(BLOCK, "getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext)"),
                method(BEHAVIOUR, "canSurvive(" + BLOCK_STATE + ",net.minecraft.world.level.LevelReader," + POS + ")"),
                method(BLOCK, "setPlacedBy(" + level + "," + POS + "," + BLOCK_STATE + ",net.minecraft.world.entity.LivingEntity,net.minecraft.world.item.ItemStack)")};
        Method[] reactive = {
                method(BEHAVIOUR, "updateShape(" + BLOCK_STATE + ",net.minecraft.world.level.LevelReader,net.minecraft.world.level.ScheduledTickAccess," + POS
                        + ",net.minecraft.core.Direction," + POS + "," + BLOCK_STATE + ",net.minecraft.util.RandomSource)"),
                method(BEHAVIOUR, "neighborChanged(" + BLOCK_STATE + "," + level + "," + POS + "," + BLOCK + ",net.minecraft.world.level.redstone.Orientation,boolean)"),
                method(BEHAVIOUR, "updateIndirectNeighbourShapes(" + BLOCK_STATE + ",net.minecraft.world.level.LevelAccessor," + POS + ",int,int)")};
        // BlockStates.DIG_HOOKED: what the client's own dig calls besides setBlock
        Method[] digHooks = {
                method(BEHAVIOUR, "attack(" + BLOCK_STATE + "," + level + "," + POS + ",net.minecraft.world.entity.player.Player)"),
                method(BLOCK, "playerWillDestroy(" + level + "," + POS + "," + BLOCK_STATE + ",net.minecraft.world.entity.player.Player)"),
                method(BLOCK, "destroy(net.minecraft.world.level.LevelAccessor," + POS + "," + BLOCK_STATE + ")")};
        Class<?> blockClass = cls(BLOCK);
        // BlockStates.UPDATE_KEEPS_BLOCK: the updateShape overrides read to keep the block (reactive[0] is updateShape)
        java.util.Set<Class<?>> keepingUpdateShape = Set.of(cls("net.minecraft.world.level.block.SnowyDirtBlock"),
                cls("net.minecraft.world.level.block.FallingBlock"));
        ShapeReader shapes = new ShapeReader(axes);
        Method toAabbs = method(VOXEL, "toAabbs()");
        java.lang.reflect.Constructor<?> newPos = cls(POS).getConstructor(int.class, int.class, int.class);
        Object[] spread = new Object[MOVED_AT.length];
        for (int i = 0; i < spread.length; i++) {
            spread[i] = newPos.newInstance(MOVED_AT[i][0], MOVED_AT[i][1], MOVED_AT[i][2]);
        }

        BlockStates.Builder builder = new BlockStates.Builder(minecraft);
        int count = (int) size.invoke(registry);
        for (int id = 0; id < count; id++) {
            Object state = byId.invoke(registry, id);
            Object block = getBlock.invoke(state);
            int flags = 0;
            flags |= (boolean) isAir.invoke(state) ? BlockStates.AIR : 0;
            flags |= (boolean) dynamic.invoke(block) ? BlockStates.DYNAMIC_SHAPE : 0;
            flags |= liquid.isInstance(block) || powderSnow.isInstance(block) || scaffolding.isInstance(block) ? BlockStates.CONTEXTUAL_COLLISION : 0;
            flags |= (boolean) offset.invoke(state) ? BlockStates.OFFSET : 0;
            flags |= (boolean) replaceable.invoke(state) ? BlockStates.REPLACEABLE : 0;
            flags |= (boolean) blocksMotion.invoke(state) ? BlockStates.BLOCKS_MOTION : 0;
            flags |= (boolean) tool.invoke(state) ? BlockStates.TOOL_REQUIRED : 0;
            Object collisionShape = collision.invoke(state, emptyGetter, zero, emptyContext);
            flags |= collisionShape == fullBlock ? BlockStates.FULL_BLOCK : 0;
            flags |= (boolean) suffocating.invoke(state, emptyGetter, zero) ? BlockStates.SUFFOCATING : 0;
            flags |= (boolean) sturdy.invoke(state, emptyGetter, zero, directions[2]) ? BlockStates.STURDY_NORTH : 0;
            flags |= (boolean) sturdy.invoke(state, emptyGetter, zero, directions[3]) ? BlockStates.STURDY_SOUTH : 0;
            flags |= (boolean) sturdy.invoke(state, emptyGetter, zero, directions[4]) ? BlockStates.STURDY_WEST : 0;
            flags |= (boolean) sturdy.invoke(state, emptyGetter, zero, directions[5]) ? BlockStates.STURDY_EAST : 0;
            Object outlineShape = outline.invoke(state, emptyGetter, zero, emptyContext);
            Object outlineBoxes = toAabbs.invoke(outlineShape);
            for (Object at : spread) {
                if (!outlineBoxes.equals(toAabbs.invoke(outline.invoke(state, emptyGetter, at, emptyContext)))) {
                    flags |= BlockStates.MOVING_OUTLINE;
                    break;
                }
            }

            flags |= declares(block.getClass(), blockClass, interactive) ? BlockStates.INTERACTIVE : 0;
            flags |= declares(block.getClass(), blockClass, replacing) ? BlockStates.CUSTOM_REPLACING : 0;
            flags |= declares(block.getClass(), blockClass, placement) ? BlockStates.CUSTOM_PLACEMENT : 0;
            flags |= declares(block.getClass(), blockClass, reactive) ? BlockStates.NEIGHBOUR_REACTIVE : 0;
            flags |= keepsBlock(block.getClass(), blockClass, reactive, keepingUpdateShape) ? BlockStates.UPDATE_KEEPS_BLOCK : 0;
            flags |= declares(block.getClass(), blockClass, digHooks) ? BlockStates.DIG_HOOKED : 0;

            StringBuilder properties = new StringBuilder();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) values.invoke(state)).entrySet()) {
                if (!properties.isEmpty()) {
                    properties.append(',');
                }
                properties.append(propertyName.invoke(entry.getKey())).append('=').append(valueName.invoke(entry.getKey(), entry.getValue()));
            }
            Object fluid = fluidState.invoke(state);
            String fluidText = "";
            if (!(boolean) fluidEmpty.invoke(fluid)) {
                Object fallingValue = ((Map<?, ?>) values.invoke(fluid)).get(falling);
                fluidText = getKey.invoke(fluids, fluidType.invoke(fluid)) + "/" + fluidAmount.invoke(fluid) + "/"
                        + (Boolean.TRUE.equals(fallingValue) ? 1 : 0);
            }
            builder.add(getKey.invoke(blocks, block).toString(), properties.toString(),
                    shapes.read(collisionShape), shapes.read(outlineShape), flags, (float) friction.invoke(block),
                    (float) speed.invoke(block), (float) jump.invoke(block), (float) destroySpeed.invoke(state, emptyGetter, zero), fluidText);
        }
        return builder.build();
    }

    /**
     * True if a class from {@code k} up to {@code Block}'s subclass declares one of the methods (same obfuscated name and
     * parameter types: what overrides it, and possibly more, which only sets a flag more).
     */
    static boolean declares(Class<?> k, Class<?> block, Method[] methods) {
        for (Class<?> c = k; c != block && c != null; c = c.getSuperclass()) {
            for (Method m : methods) {
                try {
                    if (!java.lang.reflect.Modifier.isStatic(c.getDeclaredMethod(m.getName(), m.getParameterTypes()).getModifiers())) {
                        return true;
                    }
                } catch (NoSuchMethodException e) {
                    // not declared here
                }
            }
        }
        return false;
    }

    /**
     * True if the class declares one of the reactive methods ({@code reactive[0]}: updateShape) and every declaration from
     * {@code k} up to Block's subclass is updateShape in one of {@code keeping}.
     */
    static boolean keepsBlock(Class<?> k, Class<?> block, Method[] reactive, java.util.Set<Class<?>> keeping) {
        boolean any = false;
        for (Class<?> c = k; c != block && c != null; c = c.getSuperclass()) {
            for (int i = 0; i < reactive.length; i++) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(c.getDeclaredMethod(reactive[i].getName(), reactive[i].getParameterTypes()).getModifiers())) {
                        continue;
                    }
                } catch (NoSuchMethodException e) {
                    continue;
                }
                if (i != 0 || !keeping.contains(c)) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    private static int[][] movedAt() {
        int[][] at = new int[64][];
        for (int i = 0; i < at.length; i++) {
            at[i] = new int[] {i * 37 - 1000, i % 7 * 9 - 30, 1000 - i * 53};
        }
        return at;
    }

    /** VoxelShape -> {@link Shape}, through the mappings. */
    private final class ShapeReader {
        private final Object[] axes;
        private final Method coords = method(VOXEL, "getCoords(net.minecraft.core.Direction$Axis)");
        private final Method toAabbs = method(VOXEL, "toAabbs()");
        private final Field discrete = field(VOXEL, "shape");
        private final Method xSize = method(DISCRETE, "getXSize()");
        private final Method ySize = method(DISCRETE, "getYSize()");
        private final Method zSize = method(DISCRETE, "getZSize()");
        private final Method isFull = method(DISCRETE, "isFull(int,int,int)");
        private final Field[] bounds = {field(AABB, "minX"), field(AABB, "minY"), field(AABB, "minZ"), field(AABB, "maxX"), field(AABB, "maxY"),
                field(AABB, "maxZ")};
        private final Class<?> cube = cls("net.minecraft.world.phys.shapes.CubeVoxelShape");

        ShapeReader(Object[] axes) throws ReflectiveOperationException {
            this.axes = axes;
        }

        Shape read(Object shape) throws ReflectiveOperationException {
            Object grid = discrete.get(shape);
            int sx = (int) xSize.invoke(grid);
            int sy = (int) ySize.invoke(grid);
            int sz = (int) zSize.invoke(grid);
            long[] full = new long[(sx * sy * sz + 63) >>> 6];
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        if ((boolean) isFull.invoke(grid, x, y, z)) {
                            int bit = (x * sy + y) * sz + z;
                            full[bit >>> 6] |= 1L << bit;
                        }
                    }
                }
            }
            List<?> aabbs = (List<?>) toAabbs.invoke(shape);
            double[] boxes = new double[aabbs.size() * 6];
            for (int i = 0; i < aabbs.size(); i++) {
                for (int c = 0; c < 6; c++) {
                    boxes[i * 6 + c] = bounds[c].getDouble(aabbs.get(i));
                }
            }
            return new Shape(cube.isInstance(shape) ? Shape.Kind.CUBE : Shape.Kind.ARRAY, doubles(coords.invoke(shape, axes[0])),
                    doubles(coords.invoke(shape, axes[1])), doubles(coords.invoke(shape, axes[2])), full, boxes);
        }

        /** A fastutil DoubleList (not obfuscated) to an array. */
        private double[] doubles(Object list) throws ReflectiveOperationException {
            return (double[]) list.getClass().getMethod("toDoubleArray").invoke(list);
        }
    }

    // --- reflection through the mappings -------------------------------------------------------------------

    private Class<?> cls(String mojang) throws ClassNotFoundException {
        return Class.forName(m.get(mojang).obf(), false, BlockDataDump.class.getClassLoader());
    }

    private Object staticField(String mojangClass, String name) throws ReflectiveOperationException {
        return field(mojangClass, name).get(null);
    }

    private Field field(String mojangClass, String name) throws ReflectiveOperationException {
        Field f = cls(mojangClass).getDeclaredField(m.field(mojangClass, name));
        f.setAccessible(true);
        return f;
    }

    private Object invokeStatic(String mojangClass, String signature) throws ReflectiveOperationException {
        return method(mojangClass, signature).invoke(null);
    }

    /** {@code signature}: Mojang name and Mojang parameter types, e.g. {@code getDestroySpeed(net.minecraft.world.level.BlockGetter,net.minecraft.core.BlockPos)}. */
    private Method method(String mojangClass, String signature) throws ReflectiveOperationException {
        String params = signature.substring(signature.indexOf('(') + 1, signature.length() - 1);
        String[] names = params.isEmpty() ? new String[0] : params.split(",");
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = switch (names[i]) {
                case "int" -> int.class;
                case "boolean" -> boolean.class;
                case "java.lang.Object" -> Object.class;
                case "java.lang.Comparable" -> Comparable.class;
                default -> cls(names[i]);
            };
        }
        Method method = cls(mojangClass).getDeclaredMethod(m.method(mojangClass, signature), types);
        method.setAccessible(true);
        return method;
    }
}
