package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.analysis.movement.ClientBorder;
import dev.whynotac.physics.Border;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.VoxelCollisions;
import dev.whynotac.physics.Voxels;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.world.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The world border the movement check follows ({@code ClientBorder}, the engine's {@code Border}) against the game's own
 * {@code WorldBorder}, the class the client ticks: the same changes applied to both, then the bounds the collision reads,
 * the collision shape cell by cell, the distance and bounds tests, whether an entity is close to it, and the collision and
 * overlap of player boxes with it,
 * bit for bit, after every tick of a lerp. The class is the server jar's: Paper's patches to it (the events, a guard that
 * ticks it once a server tick) do not change what is compared.
 */
class WorldBorderConformanceTest {

    private static final double[][] POINTS = {{0.0, 0.0}, {9.7, -3.2}, {-10.0, 4.0}, {10.4, 10.4}, {2.5e7, 0.0}, {-31.3, 17.9}};

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** One change, to the game's border and to the replica. */
    private record Change(Consumer<WorldBorder> game, UnaryOperator<ClientBorder> ours) {}

    private static Change center(double x, double z) {
        return new Change(b -> b.setCenter(x, z), b -> b.withCenter(x, z));
    }

    private static Change size(double size) {
        return new Change(b -> b.setSize(size), b -> b.withSize(size));
    }

    private static Change lerp(double from, double to, long time) {
        return new Change(b -> b.lerpSizeBetween(from, to, time, 0L), b -> b.lerp(from, to, time));
    }

    /** {@code ClientPacketListener.handleInitializeBorder}, as the client runs it. */
    private static Change initialize(ClientboundPacket.InitializeBorder p) {
        return new Change(b -> {
            b.setCenter(p.centerX(), p.centerZ());
            if (p.lerpTime() > 0L) {
                b.lerpSizeBetween(p.oldSize(), p.newSize(), p.lerpTime(), 0L);
            } else {
                b.setSize(p.newSize());
            }
            b.setAbsoluteMaxSize(p.absoluteMaxSize());
        }, b -> b.initialized(p));
    }

    @Test
    void staticBordersAreTheGamesOwn() throws ReflectiveOperationException {
        check(List.of());
        check(List.of(center(12.5, -7.25), size(20.0)));
        check(List.of(center(0.3, 0.6), size(0.5))); // the shape's bounds rounded out to whole blocks
        check(List.of(center(4.0, -2.0), size(0.0))); // nothing inside: the shape is all of space
        check(List.of(initialize(new ClientboundPacket.InitializeBorder(-3.0, 8.5, 64.0, 64.0, 0L, 10)))); // clamped to the absolute maximum
        check(List.of(size(7.0), center(-100.75, 3.125)));
    }

    @Test
    void lerpsStepAsTheGamesOwnTickByTick() throws ReflectiveOperationException {
        check(List.of(center(1.5, -2.5), lerp(50.0, 10.0, 7L)), 9);
        check(List.of(lerp(10.0, 30.0, 3L)), 5);
        check(List.of(lerp(10.0, 30.0, 0L)), 2); // no time: the target at once (the part done is NaN)
        check(List.of(lerp(20.0, 20.0, 40L)), 1); // the same size: static
        check(List.of(initialize(new ClientboundPacket.InitializeBorder(0.5, 0.5, 33.0, 5.0, 11L, 29999984))), 13);
        check(List.of(lerp(40.0, 4.0, 20L), center(3.0, 3.0)), 6); // the centre moves under a lerp
    }

    private static void check(List<Change> changes) throws ReflectiveOperationException {
        check(changes, 0);
    }

    /** Applies the changes to both, compares, then ticks both {@code ticks} times comparing after each. */
    private static void check(List<Change> changes, int ticks) throws ReflectiveOperationException {
        WorldBorder game = new WorldBorder();
        ClientBorder ours = ClientBorder.fresh("minecraft:overworld");
        for (Change c : changes) {
            c.game().accept(game);
            ours = c.ours().apply(ours);
        }
        compare(game, ours, "after the changes");
        for (int t = 1; t <= ticks; t++) {
            MinecraftServer.currentTick++; // Paper's WorldBorder.tick runs once a server tick (the client's has no such guard)
            game.tick();
            ours = ours.ticked(Boolean.TRUE);
            compare(game, ours, "tick " + t);
        }
    }

    private static void compare(WorldBorder game, ClientBorder ours, String when) throws ReflectiveOperationException {
        Border border = ours.collision();
        String what = when + " " + ours;
        assertEquals(bits(game.getMinX()), bits(border.minX()), what);
        assertEquals(bits(game.getMinZ()), bits(border.minZ()), what);
        assertEquals(bits(game.getMaxX()), bits(border.maxX()), what);
        assertEquals(bits(game.getMaxZ()), bits(border.maxZ()), what);
        VoxelShape shape = game.getCollisionShape();
        assertEquals(shape(shape), border.shape().shape(), what);
        for (double[] p : POINTS) {
            assertEquals(bits(game.getDistanceToBorder(p[0], p[1])), bits(border.distance(p[0], p[1])), what);
            for (double margin : new double[] {0.0, 1.0, 1.2}) {
                assertEquals(game.isWithinBounds(p[0], p[1], margin), border.within(p[0], p[1], margin), what);
            }
        }
        // player boxes across each side of the border and well inside, moved along every axis both ways
        List<AABB> boxes = new ArrayList<>();
        for (double[] at : new double[][] {{border.minX(), border.minZ()}, {border.maxX(), border.maxZ()}, {(border.minX() + border.maxX()) / 2.0,
                (border.minZ() + border.maxZ()) / 2.0}}) {
            for (double off : new double[] {-1.3, -0.29, 0.0, 0.31, 2.0}) {
                double x = at[0] + off;
                double z = at[1] - off;
                boxes.add(new AABB(x - 0.3, 64.0, z - 0.3, x + 0.3, 65.8, z + 0.3));
            }
        }
        Voxels ourShape = border.shape();
        // isInsideCloseToBorder(entity, box): the entity where the box is, the box grown as a move's sweep grows it
        Entity entity = GameEntities.allocate(EntityType.MARKER, Marker.class).entity();
        for (AABB b : boxes) {
            for (double grow : new double[] {0.0, 0.35, 1.4}) {
                AABB swept = b.inflate(grow, 0.0, grow * 0.5);
                double x = b.getCenter().x;
                double z = b.getCenter().z;
                GameEntities.set(Entity.class, "position", entity, new Vec3(x, b.minY, z));
                assertEquals(game.isInsideCloseToBorder(entity, swept),
                        border.closeTo(x, z, new Box(swept.minX, swept.minY, swept.minZ, swept.maxX, swept.maxY, swept.maxZ)), what + " " + swept);
            }
        }
        for (AABB b : boxes) {
            Box box = new Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
            assertEquals(Shapes.joinIsNotEmpty(shape, Shapes.create(b), BooleanOp.AND), VoxelCollisions.overlaps(ourShape, VoxelCollisions.create(box)),
                    what + " " + b);
            for (Direction.Axis axis : Direction.Axis.values()) {
                for (double distance : new double[] {-3.7, -0.4, 0.25, 1.9}) {
                    double expected = Shapes.collide(axis, b, List.of(shape), distance);
                    double actual = VoxelCollisions.collide(dev.whynotac.physics.Axis.values()[axis.ordinal()], box, List.of(ourShape), distance);
                    assertEquals(bits(expected), bits(actual), what + " " + b + " " + axis + " " + distance);
                }
            }
        }
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    /** The game's shape as whynotac-world holds one: its coordinates, cells and boxes. */
    private static Shape shape(VoxelShape shape) {
        var discrete = shape.shape;
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
        return new Shape(shape instanceof net.minecraft.world.phys.shapes.CubeVoxelShape ? Shape.Kind.CUBE : Shape.Kind.ARRAY,
                shape.getCoords(Direction.Axis.X).toDoubleArray(), shape.getCoords(Direction.Axis.Y).toDoubleArray(),
                shape.getCoords(Direction.Axis.Z).toDoubleArray(), full, boxes);
    }
}
