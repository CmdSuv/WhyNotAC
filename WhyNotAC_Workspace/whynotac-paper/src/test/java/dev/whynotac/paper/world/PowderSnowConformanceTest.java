package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Box;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.Voxels;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Shape;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Powder snow as the movement engine takes it ({@code Colliders.playerShape}, {@code Colliders.powderSnowEntered})
 * against the game's {@code PowderSnowBlock}, for a player of the game's own class: its collision shape by the fall, the
 * feet's height, the shift key and the boots, cell by cell and box by box, whether it is {@code Shapes.block()} itself
 * (what {@code BlockCollisions} and {@code checkInsideBlocks} test), and whether a move meets it from inside
 * ({@code getEntityInsideCollisionShape}, {@code collidedWithShapeMovingFrom}), bit for bit, at blocks far from the origin.
 */
class PowderSnowConformanceTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final int[][] BLOCKS = {{0, 10, 0}, {-3, 63, 7}, {29999, -64, -29999}, {123456, 319, 98765}};
    private static ServerPlayer player;
    private static EntityEquipment equipment;

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        player = (ServerPlayer) GameEntities.allocate(EntityType.PLAYER, ServerPlayer.class).entity();
        equipment = new EntityEquipment();
        GameEntities.set(LivingEntity.class, "equipment", player, equipment);
        GameEntities.set(Entity.class, "dimensions", player, EntityType.PLAYER.getDimensions()); // what makeBoundingBox reads
        // canEntityWalkOnPowderSnow reads the type's tags: a vanilla server's leave the player out of powder_snow_walkable_mobs
        Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
        bind.setAccessible(true);
        bind.invoke(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(EntityType.PLAYER), List.of());
    }

    private record Case(double feet, double fall, boolean shift, ItemStack boots) {}

    private static List<Case> cases(int y) {
        List<Case> out = new ArrayList<>();
        double top = y + 1.0 - 1.0E-5F;
        for (double feet : new double[] {y - 1.8, y + 0.2, y + 0.9F, top, Math.nextUp(top), y + 1.0, y + 1.3}) {
            for (double fall : new double[] {0.0, 1.0, 2.5, Math.nextUp(2.5), 7.0}) {
                for (boolean shift : new boolean[] {false, true}) {
                    for (ItemStack boots : new ItemStack[] {ItemStack.EMPTY, new ItemStack(Items.LEATHER_BOOTS), new ItemStack(Items.IRON_BOOTS)}) {
                        out.add(new Case(feet, fall, shift, boots));
                    }
                }
            }
        }
        return out;
    }

    private static void put(Case c, double x, double z) throws ReflectiveOperationException {
        GameEntities.set(Entity.class, "position", player, new Vec3(x, c.feet(), z));
        player.fallDistance = c.fall();
        GameEntities.set(player, GameEntities.<Byte>accessor(Entity.class, "DATA_SHARED_FLAGS_ID"), (byte) (c.shift() ? 1 << 1 : 0));
        equipment.set(EquipmentSlot.FEET, c.boots());
    }

    private static Colliders.Context context(Case c, double x, double z) {
        return new Colliders.Context(c.feet(), c.shift(), x, z, null, c.fall(), c.boots().is(Items.LEATHER_BOOTS));
    }

    @Test
    void theShapeIsTheGamesByFallFeetShiftAndBoots() throws Exception {
        BlockState snow = Blocks.POWDER_SNOW.defaultBlockState();
        int state = STATES.firstStateOf("minecraft:powder_snow");
        int counted = 0;
        for (int[] b : BLOCKS) {
            BlockPos pos = new BlockPos(b[0], b[1], b[2]);
            for (Case c : cases(b[1])) {
                put(c, b[0] + 0.5, b[2] + 0.5);
                assertTrue(player.isDescending() == c.shift());
                VoxelShape game = snow.getCollisionShape(EmptyBlockGetter.INSTANCE, pos, CollisionContext.of(player));
                Shape ours = Colliders.playerShape(STATES, state, context(c, b[0] + 0.5, b[2] + 0.5), b[0], b[1], b[2]);
                String what = c + " at " + pos;
                assertEquals(shape(game), ours, what);
                // Shapes.block() itself, the one full cube it gives: BlockCollisions meets it as a box, checkInsideBlocks at once
                assertEquals(game == Shapes.block(), ours.equals(shape(Shapes.block())), what);
                if (!game.isEmpty()) {
                    // the moved shape's boxes, as BlockCollisions and checkInsideBlocks take them
                    List<AABB> boxes = game.move(new Vec3(pos)).toAabbs();
                    Voxels moved = Voxels.moved(ours, b[0], b[1], b[2]);
                    assertEquals(boxes.size(), moved.boxCount(), what);
                    for (int i = 0; i < boxes.size(); i++) {
                        AABB a = boxes.get(i);
                        Box o = moved.box(i);
                        assertEquals(List.of(a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ), List.of(o.minX(), o.minY(), o.minZ(), o.maxX(), o.maxY(), o.maxZ()),
                                what);
                    }
                    counted++;
                }
            }
        }
        assertTrue(counted > 100, "cases with a shape: " + counted);
    }

    @Test
    void aMoveMeetsItFromInsideAsTheGamesDoes() throws Exception {
        BlockState snow = Blocks.POWDER_SNOW.defaultBlockState();
        int met = 0;
        int missed = 0;
        for (int[] b : BLOCKS) {
            BlockPos pos = new BlockPos(b[0], b[1], b[2]);
            for (Case c : cases(b[1])) {
                for (double[] m : new double[][] {{0.0, -0.4, 0.0}, {0.0, -1.2, 0.0}, {0.7, -0.05, 0.0}, {-0.31, 0.2, 0.9}, {0.0, -3.9, 0.0}}) {
                    for (double dx : new double[] {0.5, -0.3, 1.31}) {
                        double x = b[0] + dx;
                        double z = b[2] + 0.5;
                        put(c, x, z);
                        Vec3 from = new Vec3(x, c.feet(), z);
                        Vec3 to = from.add(m[0], m[1], m[2]);
                        VoxelShape inside = snow.getEntityInsideCollisionShape(EmptyBlockGetter.INSTANCE, pos, player);
                        boolean game = inside == Shapes.block() || player.collidedWithShapeMovingFrom(from, to, inside.move(new Vec3(pos)).toAabbs());
                        AABB box = EntityType.PLAYER.getDimensions().makeBoundingBox(from);
                        boolean ours = Colliders.powderSnowEntered(context(c, x, z), new Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
                                new Vec3d(m[0], m[1], m[2]), b[0], b[1], b[2]);
                        assertEquals(game, ours, c + " " + from + " -> " + to + " at " + pos);
                        if (inside != Shapes.block()) {
                            if (game) {
                                met++;
                            } else {
                                missed++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(met > 20 && missed > 20, "the falling slab met " + met + ", missed " + missed);
    }

    /**
     * The game's shape as whynotac-world holds one: its coordinates, cells and boxes. {@code Shapes.block()} is the
     * client's {@code CubeVoxelShape}; Paper's (Moonrise) is an {@code ArrayVoxelShape} of the same cell, coordinates and
     * box, still the one object {@code block()} returns.
     */
    private static Shape shape(VoxelShape shape) {
        if (shape.isEmpty()) {
            return Shape.EMPTY;
        }
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
        boolean cube = shape instanceof net.minecraft.world.phys.shapes.CubeVoxelShape || shape == Shapes.block();
        return new Shape(cube ? Shape.Kind.CUBE : Shape.Kind.ARRAY,
                shape.getCoords(Direction.Axis.X).toDoubleArray(), shape.getCoords(Direction.Axis.Y).toDoubleArray(),
                shape.getCoords(Direction.Axis.Z).toDoubleArray(), full, boxes);
    }
}
