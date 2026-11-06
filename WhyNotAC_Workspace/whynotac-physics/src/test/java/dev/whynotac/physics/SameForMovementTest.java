package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.world.BlockStates;
import org.junit.jupiter.api.Test;

/** Which block states the step reads alike: those a block may hold while a change to it is in flight and still be followed. */
class SameForMovementTest {

    private static final BlockStates STATES = BlockStates.vanilla();

    private static int state(String block, String properties) {
        int first = STATES.firstStateOf(block);
        for (int id = first; id < STATES.size() && STATES.block(id).equals(block); id++) {
            if (STATES.properties(id).equals(properties)) {
                return id;
            }
        }
        throw new AssertionError(block + "[" + properties + "]");
    }

    private static boolean alike(int... ids) {
        return OnFoot.sameForMovement(STATES, ids);
    }

    @Test
    void randomTicksThatKeepTheShapeAndFactorsAreAlike() {
        int dirt = STATES.firstStateOf("minecraft:dirt");
        int grass = state("minecraft:grass_block", "snowy=false");
        assertTrue(alike(dirt, grass));
        assertTrue(alike(dirt, grass, dirt));
        assertTrue(alike(state("minecraft:farmland", "moisture=0"), state("minecraft:farmland", "moisture=7")));
        assertTrue(alike(state("minecraft:oak_leaves", "distance=1,persistent=false,waterlogged=false"),
                state("minecraft:oak_leaves", "distance=7,persistent=false,waterlogged=false")));
        assertTrue(alike(grass));
    }

    @Test
    void changesThatMoveThePlayerOtherwiseAreNot() {
        int dirt = STATES.firstStateOf("minecraft:dirt");
        assertFalse(alike(dirt, STATES.firstStateOf("minecraft:air")), "a hole");
        assertFalse(alike(STATES.firstStateOf("minecraft:dirt_path"), dirt), "a lower top");
        assertFalse(alike(state("minecraft:farmland", "moisture=7"), dirt), "trampled farmland");
        assertFalse(alike(STATES.firstStateOf("minecraft:ice"), STATES.firstStateOf("minecraft:stone")), "friction");
        assertFalse(alike(STATES.firstStateOf("minecraft:ice"), state("minecraft:water", "level=0")), "melted ice");
        assertFalse(alike(STATES.firstStateOf("minecraft:air"), state("minecraft:vine", "east=false,north=true,south=false,up=false,west=false")),
                "a vine grown into the air");
        assertFalse(alike(state("minecraft:vine", "east=false,north=true,south=false,up=false,west=false"),
                state("minecraft:vine", "east=true,north=true,south=false,up=false,west=false")), "a block looked up by name");
        assertFalse(alike(state("minecraft:oak_leaves", "distance=7,persistent=false,waterlogged=false"),
                state("minecraft:oak_leaves", "distance=7,persistent=false,waterlogged=true")), "a fluid");
    }
}
