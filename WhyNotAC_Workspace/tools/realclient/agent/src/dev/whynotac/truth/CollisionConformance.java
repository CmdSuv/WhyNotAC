package dev.whynotac.truth;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Axis;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Collide;
import dev.whynotac.physics.VoxelCollisions;
import dev.whynotac.physics.Voxels;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Shape;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Differential test of whynotac-physics' collision kernel against the official client's own code, run
 * with the client jar on the class path (names through Mojang's mappings): {@code VoxelShape.collide},
 * {@code Shapes.collide}, {@code Shapes.joinIsNotEmpty(…, AND)} with {@code Shapes.create},
 * {@code Entity.collideWithShapes} and {@code Entity.collectCandidateStepUpHeights}. Every block-state
 * shape is used, moved to random positions; boxes and movements are random, and often sit exactly on, or
 * within the tolerance of, a shape's faces. Every result must be equal, bit for bit.
 *
 * <p>usage: {@code CollisionConformance <mappings> <trials> [seed]}; prints one JSON line, exit code 1 on
 * any difference.
 */
public final class CollisionConformance {

    private static final String VOXEL = "net.minecraft.world.phys.shapes.VoxelShape";
    private static final String SHAPES = "net.minecraft.world.phys.shapes.Shapes";
    private static final String AABB = "net.minecraft.world.phys.AABB";
    private static final String VEC3 = "net.minecraft.world.phys.Vec3";
    private static final String AXIS = "net.minecraft.core.Direction$Axis";
    private static final String OP = "net.minecraft.world.phys.shapes.BooleanOp";
    private static final String ENTITY = "net.minecraft.world.entity.Entity";
    private static final String BLOCK = "net.minecraft.world.level.block.Block";
    private static final String STATE = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase";
    private static final String GETTER = "net.minecraft.world.level.BlockGetter";
    private static final String POS = "net.minecraft.core.BlockPos";
    private static final String CONTEXT = "net.minecraft.world.phys.shapes.CollisionContext";
    private static final Set<String> CLASSES = Set.of(VOXEL, SHAPES, AABB, VEC3, AXIS, OP, ENTITY, BLOCK, STATE, GETTER, POS, CONTEXT,
            "net.minecraft.SharedConstants", "net.minecraft.server.Bootstrap", "net.minecraft.core.IdMapper",
            "net.minecraft.world.level.EmptyBlockGetter");

    private final Mappings m;
    private final Random random;
    private final Object[] axes;
    private final Constructor<?> aabb;
    private final Constructor<?> vec3;
    private final Method move;
    private final Method shapeCollide;
    private final Method shapesCollide;
    private final Method join;
    private final Method create;
    private final Method collideWithShapes;
    private final Method stepHeights;
    private final Field vx;
    private final Field vy;
    private final Field vz;
    private final Object and;
    private final List<Object> clientShapes = new ArrayList<>();
    private final List<Shape> ourShapes = new ArrayList<>();
    private long comparisons;
    private long differences;
    private final List<String> examples = new ArrayList<>();

    private CollisionConformance(Mappings m, long seed) throws ReflectiveOperationException {
        this.m = m;
        this.random = new Random(seed);
        method("net.minecraft.SharedConstants", "tryDetectVersion()").invoke(null);
        method("net.minecraft.server.Bootstrap", "bootStrap()").invoke(null);
        axes = cls(AXIS).getEnumConstants();
        aabb = cls(AABB).getConstructor(double.class, double.class, double.class, double.class, double.class, double.class);
        vec3 = cls(VEC3).getConstructor(double.class, double.class, double.class);
        move = method(VOXEL, "move(double,double,double)");
        shapeCollide = method(VOXEL, "collide(" + AXIS + "," + AABB + ",double)");
        shapesCollide = method(SHAPES, "collide(" + AXIS + "," + AABB + ",java.lang.Iterable,double)");
        join = method(SHAPES, "joinIsNotEmpty(" + VOXEL + "," + VOXEL + "," + OP + ")");
        create = method(SHAPES, "create(" + AABB + ")");
        collideWithShapes = method(ENTITY, "collideWithShapes(" + VEC3 + "," + AABB + ",java.util.List)");
        stepHeights = method(ENTITY, "collectCandidateStepUpHeights(" + AABB + ",java.util.List,float,float)");
        vx = field(VEC3, "x");
        vy = field(VEC3, "y");
        vz = field(VEC3, "z");
        and = field(OP, "AND").get(null);

        // Every distinct non-empty collision shape, the client's object and the table's.
        BlockStates states = BlockStates.vanilla();
        Object registry = field(BLOCK, "BLOCK_STATE_REGISTRY").get(null);
        Method byId = method("net.minecraft.core.IdMapper", "byId(int)");
        Method collision = method(STATE, "getCollisionShape(" + GETTER + "," + POS + "," + CONTEXT + ")");
        Object empty = field("net.minecraft.world.level.EmptyBlockGetter", "INSTANCE").get(null);
        Object zero = field(POS, "ZERO").get(null);
        Object context = method(CONTEXT, "empty()").invoke(null);
        java.util.Set<Shape> seen = new java.util.HashSet<>();
        for (int id = 0; id < states.size(); id++) {
            Shape shape = states.collisionShape(id);
            if (!shape.isEmpty() && seen.add(shape)) {
                clientShapes.add(collision.invoke(byId.invoke(registry, id), empty, zero, context));
                ourShapes.add(shape);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        CollisionConformance c = new CollisionConformance(Mappings.read(Path.of(args[0]), CLASSES), args.length > 2 ? Long.parseLong(args[2]) : 1L);
        int trials = Integer.parseInt(args[1]);
        for (int i = 0; i < trials; i++) {
            c.trial();
        }
        StringBuilder out = new StringBuilder("{\"shapes\":").append(c.ourShapes.size()).append(",\"trials\":").append(trials)
                .append(",\"comparisons\":").append(c.comparisons).append(",\"differences\":").append(c.differences).append(",\"examples\":[");
        for (int i = 0; i < c.examples.size(); i++) {
            out.append(i == 0 ? "" : ",").append('"').append(c.examples.get(i).replace("\"", "'")).append('"');
        }
        System.out.println(out.append("]}"));
        System.exit(c.differences == 0 ? 0 : 1);
    }

    private void trial() throws ReflectiveOperationException {
        // One to four shapes around a base position: near the origin, or far out where offsets round.
        int bx = random.nextInt(4) == 0 ? 29_999_000 + random.nextInt(1000) : random.nextInt(21) - 10;
        int by = random.nextInt(64) - 64;
        int bz = random.nextInt(4) == 0 ? -29_999_000 - random.nextInt(1000) : random.nextInt(21) - 10;
        int count = 1 + random.nextInt(4);
        List<Object> client = new ArrayList<>();
        List<Voxels> ours = new ArrayList<>();
        List<double[]> placed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int s = random.nextInt(ourShapes.size());
            int x = bx + random.nextInt(3) - 1;
            int y = by + random.nextInt(3) - 1;
            int z = bz + random.nextInt(3) - 1;
            client.add(move.invoke(clientShapes.get(s), (double) x, (double) y, (double) z));
            Voxels v = Voxels.moved(ourShapes.get(s), x, y, z);
            ours.add(v);
            placed.add(new double[] {x, y, z, s});
        }
        Box box = box(bx, by, bz, ours);
        Object clientBox = aabb.newInstance(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        String where = "shapes " + placedText(placed) + " box " + box;

        for (int i = 0; i < count; i++) {
            for (Axis axis : Axis.values()) {
                double d = distance();
                compare((double) shapeCollide.invoke(client.get(i), axes[axis.ordinal()], clientBox, d),
                        VoxelCollisions.collide(ours.get(i), axis, box, d), where + " VoxelShape.collide " + axis + " " + d + " shape " + i);
            }
            Object entityShape = create.invoke(null, clientBox);
            compare((boolean) join.invoke(null, client.get(i), entityShape, and) ? 1 : 0,
                    VoxelCollisions.overlaps(ours.get(i), VoxelCollisions.create(box)) ? 1 : 0, where + " joinIsNotEmpty shape " + i);
        }
        for (Axis axis : Axis.values()) {
            double d = distance();
            compare((double) shapesCollide.invoke(null, axes[axis.ordinal()], clientBox, client, d), VoxelCollisions.collide(axis, box, ours, d),
                    where + " Shapes.collide " + axis + " " + d);
        }
        Vec3d movement = new Vec3d(distance(), distance(), distance());
        Object result = collideWithShapes.invoke(null, vec3.newInstance(movement.x(), movement.y(), movement.z()), clientBox, client);
        Vec3d ourResult = Collide.collideWithShapes(movement, box, ours);
        compare(vx.getDouble(result), ourResult.x(), where + " collideWithShapes x " + movement);
        compare(vy.getDouble(result), ourResult.y(), where + " collideWithShapes y " + movement);
        compare(vz.getDouble(result), ourResult.z(), where + " collideWithShapes z " + movement);
        float step = random.nextBoolean() ? 0.6F : random.nextFloat() * 1.5F;
        float collidedY = random.nextBoolean() ? 0.0F : (float) distance();
        float[] clientHeights = (float[]) stepHeights.invoke(null, clientBox, client, step, collidedY);
        float[] ourHeights = Collide.candidateStepUpHeights(box, ours, step, collidedY);
        comparisons++;
        if (!Arrays.equals(clientHeights, ourHeights)) {
            fail(where + " step heights " + step + " " + collidedY + ": client " + Arrays.toString(clientHeights) + ", ours " + Arrays.toString(ourHeights));
        }
    }

    /** A player-sized or random box near the shapes, often exactly on a face or within the tolerance of one. */
    private Box box(int bx, int by, int bz, List<Voxels> shapes) {
        double w = random.nextInt(3) == 0 ? 0.1 + random.nextDouble() * 2 : 0.6;
        double h = random.nextInt(3) == 0 ? 0.1 + random.nextDouble() * 2 : (random.nextBoolean() ? 1.8 : 1.5);
        double x = bx - 1 + random.nextDouble() * 3;
        double y = by - 1 + random.nextDouble() * 3;
        double z = bz - 1 + random.nextDouble() * 3;
        if (random.nextInt(3) > 0) {
            // snap one side of the box to a coordinate of a shape, plus a tiny offset
            Voxels shape = shapes.get(random.nextInt(shapes.size()));
            Axis axis = Axis.values()[random.nextInt(3)];
            double c = shape.coord(axis, random.nextInt(shape.size(axis) + 1)) + tiny();
            boolean low = random.nextBoolean();
            double extent = axis == Axis.Y ? h : w;
            double start = low ? c : c - extent;
            switch (axis) {
                case X -> x = start;
                case Y -> y = start;
                case Z -> z = start;
            }
        }
        double[] min = {x, y, z};
        return Box.of(min[0], min[1], min[2], min[0] + w, min[1] + h, min[2] + w);
    }

    private double tiny() {
        return switch (random.nextInt(8)) {
            case 0 -> 1.0E-7;
            case 1 -> -1.0E-7;
            case 2 -> 5.0E-8;
            case 3 -> -5.0E-8;
            case 4 -> 1.5E-7;
            case 5 -> -1.5E-7;
            default -> 0.0;
        };
    }

    private double distance() {
        return switch (random.nextInt(10)) {
            case 0 -> 0.0;
            case 1 -> tiny();
            case 2 -> -0.0784000015258789;
            case 3 -> (random.nextDouble() - 0.5) * 0.2;
            case 4 -> random.nextBoolean() ? 1.0E-7 : -1.0E-7;
            default -> (random.nextDouble() - 0.5) * 2;
        };
    }

    private void compare(double client, double ours, String what) {
        comparisons++;
        if (Double.doubleToRawLongBits(client) != Double.doubleToRawLongBits(ours)) {
            fail(what + ": client " + client + ", ours " + ours);
        }
    }

    private void fail(String what) {
        differences++;
        if (examples.size() < 20) {
            examples.add(what);
        }
    }

    private static String placedText(List<double[]> placed) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : placed) {
            sb.append('[').append((int) p[3]).append('@').append((int) p[0]).append(',').append((int) p[1]).append(',').append((int) p[2]).append(']');
        }
        return sb.toString();
    }

    // --- reflection through the mappings -------------------------------------------------------------------

    private Class<?> cls(String mojang) throws ClassNotFoundException {
        return Class.forName(m.get(mojang).obf(), false, CollisionConformance.class.getClassLoader());
    }

    private Field field(String mojangClass, String name) throws ReflectiveOperationException {
        Field f = cls(mojangClass).getDeclaredField(m.field(mojangClass, name));
        f.setAccessible(true);
        return f;
    }

    private Method method(String mojangClass, String signature) throws ReflectiveOperationException {
        String params = signature.substring(signature.indexOf('(') + 1, signature.length() - 1);
        String[] names = params.isEmpty() ? new String[0] : params.split(",");
        Class<?>[] types = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            types[i] = switch (names[i]) {
                case "int" -> int.class;
                case "double" -> double.class;
                case "float" -> float.class;
                case "java.lang.Iterable" -> Iterable.class;
                case "java.util.List" -> List.class;
                default -> cls(names[i]);
            };
        }
        Method method = cls(mojangClass).getDeclaredMethod(m.method(mojangClass, signature), types);
        method.setAccessible(true);
        return method;
    }
}
