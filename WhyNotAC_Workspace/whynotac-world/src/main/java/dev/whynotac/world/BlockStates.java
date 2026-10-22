package dev.whynotac.world;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Every block state of Minecraft 1.21.11, indexed by its global id (the id chunks and block updates
 * carry): block, properties, collision and outline shapes and the properties movement and interaction read.
 *
 * <p>Generated from the official client's own registry ({@code BlockDataDump} of the real-client run, which fails when
 * the resource no longer matches it), and compared with the server's ({@code BlockStatesDataTest} in whynotac-paper,
 * which fails the build), so the data cannot drift from the game. The collision shape is the one for an empty collision context; {@link #CONTEXTUAL_COLLISION}
 * and {@link #DYNAMIC_SHAPE} mark the states where the game computes it differently.
 *
 * <p>The outline shape ({@code getShape}: what the crosshair's ray clips, {@code ClipContext.Block.OUTLINE}) is the one
 * for an empty world and context too. Where the client's may differ it is never larger (decompiled 1.21.11): a light
 * block's is empty unless the player holds light, scaffolding's its own unless the player holds scaffolding, a
 * shulker box's grows as it opens (out of its cell: {@link #outlineInCell}); only states with {@link #MOVING_OUTLINE} are
 * moved by their position, and no other block reads the world or the position for it.
 *
 * <h2>Text format (inside the gzip resource)</h2>
 * <pre>
 * whynotac-block-states 3
 * minecraft &lt;version&gt;
 * shapes &lt;n&gt;
 * &lt;kind&gt; TAB &lt;xs&gt; TAB &lt;ys&gt; TAB &lt;zs&gt; TAB &lt;filled cells, hex longs&gt; TAB &lt;boxes&gt;   (one line per shape)
 * states &lt;n&gt;
 * &lt;block&gt; TAB &lt;properties&gt; TAB &lt;shape&gt; TAB &lt;outline&gt; TAB &lt;flags&gt; TAB &lt;friction&gt; TAB &lt;speed factor&gt; TAB &lt;jump factor&gt; TAB &lt;destroy speed&gt; TAB &lt;fluid&gt;
 * </pre>
 * One state per line, in id order. Numbers are written so they read back to the same bits; lists are
 * comma-separated, empty as {@code -}. The fluid is {@code -} or {@code key/amount/falling}.
 */
public final class BlockStates {

    public static final String RESOURCE = "/dev/whynotac/world/block-states.txt.gz";
    static final String HEADER = "whynotac-block-states 3";

    /** {@code isAir()}. */
    public static final int AIR = 1;
    /** {@code hasDynamicShape()}: the game does not cache the collision shape; it may depend on the world (moving pistons, shulker boxes, offsets). */
    public static final int DYNAMIC_SHAPE = 1 << 1;
    /** The collision shape depends on the entity: liquids, powder snow, scaffolding. */
    public static final int CONTEXTUAL_COLLISION = 1 << 2;
    /** {@code hasOffsetFunction()}: shapes are moved by a position-dependent offset (bamboo, dripstone, flowers). */
    public static final int OFFSET = 1 << 3;
    /** {@code canBeReplaced()} (without a placement context). */
    public static final int REPLACEABLE = 1 << 4;
    /** {@code blocksMotion()}. */
    public static final int BLOCKS_MOTION = 1 << 5;
    /** {@code requiresCorrectToolForDrops()}. */
    public static final int TOOL_REQUIRED = 1 << 6;
    /**
     * The collision shape is the game's full-block object itself ({@code Shapes.block()}). Block collision
     * keeps such a block when its cube intersects the box, every other shape when it overlaps the box's
     * shape ({@code BlockCollisions.computeNext}).
     */
    public static final int FULL_BLOCK = 1 << 7;
    /**
     * {@code isSuffocating(level, pos)} for an empty world: what {@code collidesWithSuffocatingBlock} keeps (the client's
     * push out of a block, {@code LocalPlayer.moveTowardsClosestSpace}). The default is a full-block shape that blocks
     * motion; glass, leaves and others never suffocate, mud and soul sand always do.
     */
    public static final int SUFFOCATING = 1 << 8;
    /**
     * {@code isFaceSturdy(level, pos, direction)} ({@code SupportType.FULL}) for an empty world, one flag per horizontal
     * side: what a falling fluid next to the block reads ({@code FlowingFluid.isSolidFace}). For a block whose shape
     * depends on the world ({@link #DYNAMIC_SHAPE}) only the empty world's answer.
     */
    public static final int STURDY_NORTH = 1 << 9;
    public static final int STURDY_SOUTH = 1 << 10;
    public static final int STURDY_WEST = 1 << 11;
    public static final int STURDY_EAST = 1 << 12;
    /**
     * The outline ({@code getShape} for an empty world and context) is not the same at every position: the state's
     * offset moves it ({@code FlowerBlock}, bamboo, pointed dripstone, mangrove propagules). Only some states with
     * {@link #OFFSET} (short grass and ferns have an offset their outline ignores). Measured: the outline at a spread of
     * positions differs from the one at the origin.
     */
    public static final int MOVING_OUTLINE = 1 << 13;
    private static final String FLAG_LETTERS = "adcormtfsNSWEp";

    private static volatile BlockStates vanilla;

    private final String minecraft;
    private final Shape[] shapes;
    private final String[] block;
    private final String[] properties;
    private final int[] shape;
    private final int[] outline;
    /** Per shape: every box within the block's own cell ({@code [0, 1]} on each axis). */
    private final boolean[] withinCell;
    private final int[] flags;
    private final float[] friction;
    private final float[] speedFactor;
    private final float[] jumpFactor;
    private final float[] destroySpeed;
    private final String[] fluid;
    /** {@link #fluid} read once: the fluid's kind, amount and falling flag ({@link #FLUID_WATER}...). */
    private final byte[] fluidKind;
    private final byte[] fluidAmount;
    private final boolean[] fluidFalling;
    private final Map<String, Integer> firstState;

    private BlockStates(String minecraft, List<Shape> shapes, List<State> states) {
        this.minecraft = minecraft;
        this.shapes = shapes.toArray(Shape[]::new);
        withinCell = new boolean[this.shapes.length];
        for (int i = 0; i < this.shapes.length; i++) {
            Shape shape = this.shapes[i];
            boolean within = true;
            for (int b = 0; b < shape.boxCount() && within; b++) {
                within = shape.box(b, 0) >= 0.0 && shape.box(b, 1) >= 0.0 && shape.box(b, 2) >= 0.0 && shape.box(b, 3) <= 1.0
                        && shape.box(b, 4) <= 1.0 && shape.box(b, 5) <= 1.0;
            }
            withinCell[i] = within;
        }
        int n = states.size();
        block = new String[n];
        properties = new String[n];
        shape = new int[n];
        outline = new int[n];
        flags = new int[n];
        friction = new float[n];
        speedFactor = new float[n];
        jumpFactor = new float[n];
        destroySpeed = new float[n];
        fluid = new String[n];
        fluidKind = new byte[n];
        fluidAmount = new byte[n];
        fluidFalling = new boolean[n];
        firstState = new HashMap<>();
        for (int id = 0; id < n; id++) {
            State s = states.get(id);
            if (s.shape < 0 || s.shape >= this.shapes.length || s.outline < 0 || s.outline >= this.shapes.length) {
                throw new IllegalArgumentException("state " + id + ": shapes " + s.shape + ", " + s.outline + " of " + this.shapes.length);
            }
            block[id] = s.block;
            properties[id] = s.properties;
            shape[id] = s.shape;
            outline[id] = s.outline;
            flags[id] = s.flags;
            friction[id] = s.friction;
            speedFactor[id] = s.speedFactor;
            jumpFactor[id] = s.jumpFactor;
            destroySpeed[id] = s.destroySpeed;
            fluid[id] = s.fluid;
            if (!s.fluid.isEmpty()) {
                String[] f = s.fluid.split("/");
                String key = f[0];
                fluidKind[id] = key.equals("minecraft:water") || key.equals("minecraft:flowing_water") ? FLUID_WATER
                        : key.equals("minecraft:lava") || key.equals("minecraft:flowing_lava") ? FLUID_LAVA : FLUID_OTHER;
                fluidAmount[id] = Byte.parseByte(f[1]);
                fluidFalling[id] = f[2].equals("1");
            }
            firstState.putIfAbsent(s.block, id); // the lowest id; not always the block's default state
        }
    }

    /** The data for the Minecraft version WhyNotAC supports, loaded once from the resource. */
    public static BlockStates vanilla() {
        BlockStates loaded = vanilla;
        if (loaded == null) {
            synchronized (BlockStates.class) {
                loaded = vanilla;
                if (loaded == null) {
                    try (InputStream in = BlockStates.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) {
                            throw new IllegalStateException("missing resource " + RESOURCE);
                        }
                        loaded = vanilla = read(new GZIPInputStream(in));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }
        return loaded;
    }

    /** Reads the text format (uncompressed). */
    public static BlockStates read(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        expect(reader.readLine(), HEADER);
        String minecraft = field(reader.readLine(), "minecraft");
        int shapeCount = Integer.parseInt(field(reader.readLine(), "shapes"));
        List<Shape> shapes = new ArrayList<>(shapeCount);
        for (int i = 0; i < shapeCount; i++) {
            String[] f = reader.readLine().split("\t", -1);
            if (f.length != 6) {
                throw new IOException("shape line " + i + ": " + f.length + " fields");
            }
            shapes.add(new Shape(Shape.Kind.valueOf(f[0]), doubles(f[1]), doubles(f[2]), doubles(f[3]), hexLongs(f[4]), doubles(f[5])));
        }
        int stateCount = Integer.parseInt(field(reader.readLine(), "states"));
        List<State> states = new ArrayList<>(stateCount);
        for (int i = 0; i < stateCount; i++) {
            String[] f = reader.readLine().split("\t", -1);
            if (f.length != 10) {
                throw new IOException("state line " + i + ": " + f.length + " fields");
            }
            states.add(new State(f[0].intern(), f[1].equals("-") ? "" : f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), parseFlags(f[4]),
                    Float.parseFloat(f[5]), Float.parseFloat(f[6]), Float.parseFloat(f[7]), Float.parseFloat(f[8]), f[9].equals("-") ? "" : f[9].intern()));
        }
        if (reader.readLine() != null) {
            throw new IOException("trailing data after " + stateCount + " states");
        }
        return new BlockStates(minecraft, shapes, states);
    }

    /** Writes the text format (uncompressed); {@link #read} reads it back to equal data. */
    public void write(Writer out) throws IOException {
        out.write(HEADER + "\n");
        out.write("minecraft " + minecraft + "\n");
        out.write("shapes " + shapes.length + "\n");
        for (Shape s : shapes) {
            out.write(s.kind() + "\t" + doubles(s.xs()) + "\t" + doubles(s.ys()) + "\t" + doubles(s.zs()) + "\t" + hexLongs(s.full()) + "\t"
                    + doubles(s.boxes()) + "\n");
        }
        out.write("states " + block.length + "\n");
        for (int id = 0; id < block.length; id++) {
            out.write(block[id] + "\t" + (properties[id].isEmpty() ? "-" : properties[id]) + "\t" + shape[id] + "\t" + outline[id] + "\t"
                    + flagLetters(flags[id]) + "\t"
                    + friction[id] + "\t" + speedFactor[id] + "\t" + jumpFactor[id] + "\t" + destroySpeed[id] + "\t"
                    + (fluid[id].isEmpty() ? "-" : fluid[id]) + "\n");
        }
    }

    // --- queries -------------------------------------------------------------------------------------

    public String minecraftVersion() {
        return minecraft;
    }

    /** Number of block states (the global palette's size). */
    public int size() {
        return block.length;
    }

    /** Bits per entry of the global palette ({@code Mth.ceillog2(size())}): what a chunk section uses beyond 8 bits. */
    public int globalPaletteBits() {
        return size() <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(size() - 1);
    }

    public boolean isValid(int state) {
        return state >= 0 && state < block.length;
    }

    /** Block id, e.g. {@code minecraft:oak_stairs}. */
    public String block(int state) {
        return block[state];
    }

    /** Properties as {@code name=value,...} in the game's order, or empty. */
    public String properties(int state) {
        return properties[state];
    }

    public Shape collisionShape(int state) {
        return shapes[shape[state]];
    }

    /** The outline shape (see the class comment), in the block's own coordinates. */
    public Shape outlineShape(int state) {
        return shapes[outline[state]];
    }

    /**
     * Whether the state's outline stays in its block's own cell wherever the block is and whatever the client holds: no
     * box reaches out of it, it is not moved by its position ({@link #MOVING_OUTLINE}), and it is not a shulker box (whose block
     * entity grows it out of the cell as it opens). Then a ray meets it only inside the cell.
     */
    public boolean outlineInCell(int state) {
        return withinCell[outline[state]] && !has(state, MOVING_OUTLINE) && !block[state].endsWith("shulker_box");
    }

    /**
     * Whether the client's outline of the state is {@link #outlineShape} exactly, wherever the block is and whatever the
     * player holds: not moved by its position ({@link #MOVING_OUTLINE}), not a shulker box's (its block entity's box), not light's
     * or scaffolding's (a full block while the player holds one).
     */
    public boolean outlineExact(int state) {
        return !has(state, MOVING_OUTLINE) && !block[state].endsWith("shulker_box") && !block[state].equals("minecraft:light")
                && !block[state].equals("minecraft:scaffolding");
    }

    public int flags(int state) {
        return flags[state];
    }

    public boolean has(int state, int flag) {
        return (flags[state] & flag) != 0;
    }

    public boolean isAir(int state) {
        return has(state, AIR);
    }

    public float friction(int state) {
        return friction[state];
    }

    public float speedFactor(int state) {
        return speedFactor[state];
    }

    public float jumpFactor(int state) {
        return jumpFactor[state];
    }

    public float destroySpeed(int state) {
        return destroySpeed[state];
    }

    /** {@code fluidKey/amount/falling}, or empty for no fluid. */
    public String fluid(int state) {
        return fluid[state];
    }

    /** No fluid; {@code FluidTags.WATER} (water, flowing water); {@code FluidTags.LAVA}; another fluid. */
    public static final byte FLUID_NONE = 0;
    public static final byte FLUID_WATER = 1;
    public static final byte FLUID_LAVA = 2;
    public static final byte FLUID_OTHER = 3;

    /** The state's fluid by its tag ({@link #FLUID_NONE}...). */
    public byte fluidKind(int state) {
        return fluidKind[state];
    }

    /** {@code FluidState.getAmount()}: 8 for a source or a falling fluid, 1..7 flowing; 0 for none. */
    public int fluidAmount(int state) {
        return fluidAmount[state];
    }

    /** The fluid state's {@code FALLING} property. */
    public boolean fluidFalling(int state) {
        return fluidFalling[state];
    }

    /** The lowest state id of {@code block} (for blocks with one state, its only state), or -1. */
    public int firstStateOf(String block) {
        return firstState.getOrDefault(block, -1);
    }

    public int shapeCount() {
        return shapes.length;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BlockStates o)) {
            return false;
        }
        return minecraft.equals(o.minecraft) && java.util.Arrays.equals(shapes, o.shapes) && java.util.Arrays.equals(block, o.block)
                && java.util.Arrays.equals(properties, o.properties) && java.util.Arrays.equals(shape, o.shape)
                && java.util.Arrays.equals(outline, o.outline)
                && java.util.Arrays.equals(flags, o.flags) && java.util.Arrays.equals(friction, o.friction)
                && java.util.Arrays.equals(speedFactor, o.speedFactor) && java.util.Arrays.equals(jumpFactor, o.jumpFactor)
                && java.util.Arrays.equals(destroySpeed, o.destroySpeed) && java.util.Arrays.equals(fluid, o.fluid);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(block) * 31 + shapes.length;
    }

    // --- building (the generator) ----------------------------------------------------------------------

    /** Collects states in id order; shapes are deduplicated. */
    public static final class Builder {
        private final String minecraft;
        private final List<Shape> shapes = new ArrayList<>();
        private final Map<Shape, Integer> shapeIndex = new HashMap<>();
        private final List<State> states = new ArrayList<>();

        public Builder(String minecraft) {
            this.minecraft = minecraft;
        }

        /** Adds the state with the next id. */
        public Builder add(String block, String properties, Shape collision, Shape outline, int flags, float friction, float speedFactor,
                float jumpFactor, float destroySpeed, String fluid) {
            states.add(new State(block, properties, index(collision), index(outline), flags, friction, speedFactor, jumpFactor, destroySpeed, fluid));
            return this;
        }

        private int index(Shape shape) {
            return shapeIndex.computeIfAbsent(shape, s -> {
                shapes.add(s);
                return shapes.size() - 1;
            });
        }

        public BlockStates build() {
            return new BlockStates(minecraft, shapes, states);
        }
    }

    private record State(String block, String properties, int shape, int outline, int flags, float friction, float speedFactor,
            float jumpFactor, float destroySpeed, String fluid) {}

    // --- text helpers ------------------------------------------------------------------------------------

    private static void expect(String line, String expected) throws IOException {
        if (!expected.equals(line)) {
            throw new IOException("expected '" + expected + "', found '" + line + "'");
        }
    }

    private static String field(String line, String name) throws IOException {
        if (line == null || !line.startsWith(name + " ")) {
            throw new IOException("expected '" + name + " ...', found '" + line + "'");
        }
        return line.substring(name.length() + 1);
    }

    private static String doubles(double[] values) {
        if (values.length == 0) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (double v : values) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(v); // Double.toString reads back to the same double
        }
        return sb.toString();
    }

    private static double[] doubles(String field) {
        if (field.equals("-")) {
            return new double[0];
        }
        String[] parts = field.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Double.parseDouble(parts[i]);
        }
        return values;
    }

    private static String hexLongs(long[] values) {
        if (values.length == 0) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (long v : values) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(Long.toHexString(v));
        }
        return sb.toString();
    }

    private static long[] hexLongs(String field) {
        if (field.equals("-")) {
            return new long[0];
        }
        String[] parts = field.split(",");
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Long.parseUnsignedLong(parts[i], 16);
        }
        return values;
    }

    private static String flagLetters(int flags) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FLAG_LETTERS.length(); i++) {
            if ((flags & 1 << i) != 0) {
                sb.append(FLAG_LETTERS.charAt(i));
            }
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    private static int parseFlags(String letters) throws IOException {
        if (letters.equals("-")) {
            return 0;
        }
        int flags = 0;
        for (char c : letters.toCharArray()) {
            int i = FLAG_LETTERS.indexOf(c);
            if (i < 0) {
                throw new IOException("unknown flag '" + c + "'");
            }
            flags |= 1 << i;
        }
        return flags;
    }
}
