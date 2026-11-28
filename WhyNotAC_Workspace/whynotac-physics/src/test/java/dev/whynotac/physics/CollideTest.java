package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientWorld;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hand-checked cases for the collision kernel. The kernel's real proof is {@code CollisionConformance}
 * (tools/realclient), which compares it with the official client's own code on random cases.
 */
class CollideTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final int STONE = STATES.firstStateOf("minecraft:stone");
    private static final int SLAB = state("minecraft:stone_slab", "type=bottom");
    private static final int WATER = STATES.firstStateOf("minecraft:water");

    private static int state(String block, String property) {
        for (int id = 0; id < STATES.size(); id++) {
            if (STATES.block(id).equals(block) && STATES.properties(id).contains(property)) {
                return id;
            }
        }
        throw new AssertionError(block + " " + property);
    }

    /** A world of air with the given blocks. */
    private static final class World implements Colliders.World {
        final Map<List<Integer>, Integer> blocks = new HashMap<>();

        World set(int x, int y, int z, int state) {
            blocks.put(List.of(x, y, z), state);
            return this;
        }

        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            return blocks.getOrDefault(List.of(x, y, z), AIR);
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }
    }

    private static Box player(double x, double y, double z) {
        return Box.of(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void standingOnABlockStopsTheFallExactly() throws Exception {
        World world = new World().set(0, 0, 0, STONE);
        assertEquals(new Vec3d(0.0, 0.0, 0.0), Collide.collide(world, new Colliders.Context(1.0, false), player(0.5, 1.0, 0.5), new Vec3d(0.0, -0.0784000015258789, 0.0), true, 0.6F, List.of()));
        // from above: the box lands on the block's top face
        assertEquals(-0.25, Collide.collide(world, new Colliders.Context(1.25, false), player(0.5, 1.25, 0.5), new Vec3d(0.0, -0.5, 0.0), false, 0.6F, List.of()).y());
    }

    @Test
    void aWallStopsTheBoxAtItsFace() throws Exception {
        World world = new World().set(1, 1, 0, STONE).set(1, 2, 0, STONE).set(0, 0, 0, STONE).set(1, 0, 0, STONE);
        Vec3d moved = Collide.collide(world, new Colliders.Context(1.0, false), player(0.4, 1.0, 0.5), new Vec3d(0.5, 0.0, 0.0), true, 0.6F, List.of());
        assertEquals(1.0 - 0.7, moved.x()); // the box's maxX (0.4 + 0.3) meets x = 1
        assertEquals(0.0, moved.y());
    }

    @Test
    void aBottomSlabIsSteppedOnFromTheGround() throws Exception {
        World world = new World().set(0, 0, 0, STONE).set(1, 0, 0, STONE).set(1, 1, 0, SLAB);
        Vec3d moved = Collide.collide(world, new Colliders.Context(1.0, false), player(0.5, 1.0, 0.5), new Vec3d(0.3, 0.0, 0.0), true, 0.6F, List.of());
        assertEquals(0.3, moved.x());
        assertEquals(0.5, moved.y());
        // in the air (not on the ground, not landing) there is no step
        assertEquals(0.0, Collide.collide(world, new Colliders.Context(1.0, false), player(0.5, 1.0, 0.5), new Vec3d(0.3, 0.0, 0.0), false, 0.6F, List.of()).y());
    }

    @Test
    void stepHeightsAreTheCollidersYCoordinatesUpToTheStep() throws Exception {
        World world = new World().set(1, 1, 0, SLAB).set(1, 0, 0, STONE);
        Box box = player(0.5, 1.0, 0.5);
        List<Voxels> colliders = Colliders.collect(world, new Colliders.Context(box.minY(), false), List.of(), box.expandTowards(0.3, 0.6F, 0.0));
        assertArrayEquals(new float[] {0.5F}, Collide.candidateStepUpHeights(box, colliders, 0.6F, 0.0F));
    }

    @Test
    void touchingFacesDoNotOverlap() {
        Voxels block = Voxels.moved(STATES.collisionShape(STONE), 1, 0, 0);
        assertFalse(VoxelCollisions.overlaps(block, VoxelCollisions.create(Box.of(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))));
        assertTrue(VoxelCollisions.overlaps(block, VoxelCollisions.create(Box.of(0.5, 0.0, 0.0, 1.5, 1.0, 1.0))));
    }

    @Test
    void waterIsNotSolidAndUnknownBlocksAreNoClaim() throws Exception {
        World water = new World().set(0, 0, 0, WATER);
        assertEquals(-0.5, Collide.collide(water, new Colliders.Context(1.0, false), player(0.5, 1.0, 0.5), new Vec3d(0.0, -0.5, 0.0), false, 0.6F, List.of()).y());
        World unknown = new World().set(0, 0, 0, ClientWorld.UNKNOWN);
        assertThrows(Colliders.Unknown.class, () -> Collide.collide(unknown, new Colliders.Context(1.0, false), player(0.5, 1.0, 0.5), new Vec3d(0.0, -0.5, 0.0), false, 0.6F, List.of()));
    }
}
