package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.world.BlockStates;
import dev.whynotac.world.Shape;
import org.junit.jupiter.api.Test;

/** {@code ScaffoldingBlock.getCollisionShape} for the player, from its context (the feet's height, the shift key). */
class ScaffoldingTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    /** A block of a tower standing on the ground, and one of a bridge hanging off it. */
    private static final int SUPPORTED = state("bottom=false,distance=0,waterlogged=false");
    private static final int HANGING = state("bottom=true,distance=2,waterlogged=false");

    private static int state(String properties) {
        for (int s = STATES.firstStateOf("minecraft:scaffolding"); STATES.block(s).equals("minecraft:scaffolding"); s++) {
            if (STATES.properties(s).equals(properties)) {
                return s;
            }
        }
        throw new AssertionError(properties);
    }

    private static Shape shape(int state, double feet, boolean shift) throws Colliders.Unknown {
        return Colliders.playerShape(STATES, state, new Colliders.Context(feet, shift), 0, 10, 0);
    }

    @Test
    void aPlayerAboveTheTopNotDescendingStandsOnTheStableShape() throws Colliders.Unknown {
        // the empty context counts as above: the table's shape is the stable one (a top slab on four legs)
        Shape stable = STATES.collisionShape(SUPPORTED);
        assertSame(stable, shape(SUPPORTED, 11.0, false));
        Voxels v = Voxels.of(stable);
        assertEquals(0.0, v.min(Axis.Y));
        assertEquals(1.0, v.max(Axis.Y));
        assertSame(STATES.collisionShape(HANGING), shape(HANGING, 11.0, false));
    }

    @Test
    void descendingOrBelowTheTopASupportedBlockHoldsNothing() throws Colliders.Unknown {
        assertTrue(shape(SUPPORTED, 11.0, true).isEmpty());
        assertTrue(shape(SUPPORTED, 10.5, false).isEmpty());
        // isAbove: strictly more than the top less 1.0E-5F
        assertTrue(shape(SUPPORTED, 11.0 - 1.0E-5F, false).isEmpty());
        assertSame(STATES.collisionShape(SUPPORTED), shape(SUPPORTED, Math.nextUp(11.0 - 1.0E-5F), false));
    }

    @Test
    void insideAHangingBlockItsFloorsSlabHoldsThePlayerShiftOrNot() throws Colliders.Unknown {
        Shape slab = shape(HANGING, 10.5, false);
        assertSame(slab, shape(HANGING, 10.5, true));
        assertSame(slab, shape(HANGING, 11.0, true)); // descending above: the slab, not the top
        Voxels v = Voxels.of(slab);
        for (Axis axis : new Axis[] {Axis.X, Axis.Z}) {
            assertEquals(0.0, v.min(axis));
            assertEquals(1.0, v.max(axis));
        }
        assertEquals(0.0, v.min(Axis.Y));
        assertEquals(0.125, v.max(Axis.Y)); // Block.column(16, 0, 2)
        assertTrue(shape(HANGING, 10.0 - 1.0E-5F, false).isEmpty()); // from below: nothing
    }

    @Test
    void withoutTheContextTheShapeIsNotKnown() {
        assertThrows(Colliders.Unknown.class, () -> Colliders.playerShape(STATES, SUPPORTED, null, 0, 10, 0));
    }
}
