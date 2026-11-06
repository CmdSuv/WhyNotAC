package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A strider its rider steers ({@code Strider}, decompiled 1.21.11): lava's stable shape holds it from above a source
 * with no lava over it ({@code LiquidBlock.getCollisionShape}, {@code canStandOnFluid}), on lava it travels as on land,
 * in lava it floats up ({@code floatStrider}), and its client sets its suffocation each tick from the blocks at and under
 * it and the lava its last tick found, which picks its ridden speed.
 */
class StriderTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final Horse.Rider AHEAD = new Horse.Rider(0.0F, 1.0F, 0.0F, 0.0F);
    private static final Set<String> WARM = Set.of("minecraft:lava");
    private static final int LAVA = lava("level=0");
    private static final int FLOWING = lava("level=1");

    /** Stone up to y 61; over x 0 to 8 a lava pool at y 62 (two deep at x 6 and 7, flowing at x 5), stone at y 62 elsewhere; air above. */
    private final Colliders.World world = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            boolean pool = x >= 0 && x < 8 && z >= -8 && z < 16;
            if (y < 62 || y == 62 && !pool) {
                return STATES.firstStateOf("minecraft:stone");
            }
            if (pool && y == 62 && x == 5) {
                return FLOWING;
            }
            if (pool && (y == 62 || y == 63 && x >= 6)) {
                return LAVA;
            }
            return STATES.firstStateOf("minecraft:air");
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }

        @Override
        public Boolean fastLava() {
            return Boolean.FALSE;
        }
    };

    private static int lava(String properties) {
        for (int s = STATES.firstStateOf("minecraft:lava"); STATES.block(s).equals("minecraft:lava"); s++) {
            if (STATES.properties(s).equals(properties)) {
                return s;
            }
        }
        throw new IllegalStateException(properties);
    }

    private static Horse.Conditions strider(Set<String> warm) {
        return new Horse.Conditions(0.0F, 0.0, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, false, 0.9F, 1.7F, null, List.of(),
                new Horse.Strider(0.09625F, 0.040424998F, warm));
    }

    private static HorseState at(double x, double y, double z, boolean onGround, BlockPos support, double lavaHeight) {
        return new HorseState(x, y, z, Vec3d.ZERO, 0.0F, 0.0F, onGround, false, false, false, 0.0, support, false, Vec3d.ZERO, false, 0, 0.0F,
                false, false, 0, null, lavaHeight, false);
    }

    @Test
    void lavasStableShapeHoldsAStriderFromAboveASourceWithNoLavaOverIt() throws Colliders.Unknown {
        Colliders.Context standing = new Colliders.Context(62.5, false, 2.5, 2.5, null, 0.0, Boolean.FALSE, true);
        Colliders.Context player = new Colliders.Context(62.5, false, 2.5, 2.5, null, 0.0, Boolean.FALSE);
        Box feet = new Box(2.2, 62.4, 2.2, 2.8, 62.6, 2.8);
        assertFalse(Colliders.noCollision(world, standing, feet), "the stable shape, 0.5 high");
        assertTrue(Colliders.noCollision(world, player, feet), "never a player's");
        Colliders.Context below = new Colliders.Context(62.49, false, 2.5, 2.5, null, 0.0, Boolean.FALSE, true);
        assertTrue(Colliders.noCollision(world, below, feet), "not above it");
        Box deep = new Box(6.2, 62.4, 2.2, 6.8, 62.6, 2.8);
        assertTrue(Colliders.noCollision(world, standing, deep), "lava over it");
        Box flowing = new Box(5.2, 62.4, 2.2, 5.8, 62.6, 2.8);
        assertTrue(Colliders.noCollision(world, standing, flowing), "not a source");
    }

    @Test
    void aStriderStandsOnLavaWhereAHorseSinks() throws Colliders.Unknown {
        HorseState s = at(2.5, 62.5, 2.5, true, new BlockPos(2, 62, 2), 0.38888883590698242);
        HorseState after = s;
        for (int i = 0; i < 10; i++) {
            after = Horse.tick(after, AHEAD, world, strider(WARM));
            assertEquals(62.5, after.y(), "tick " + i);
            assertTrue(after.onGround());
            assertFalse(after.suffocating(), "in lava");
        }
        assertTrue(after.z() > 3.0, "ahead, as on land: " + after.z());
        BlockPos support = after.support();
        assertEquals(LAVA, world.blockState(support.x(), support.y(), support.z()), "the lava it stands on: " + support);
        HorseState horse = s;
        for (int i = 0; i < 3; i++) {
            horse = Horse.tick(horse, AHEAD, world, new Horse.Conditions(0.225F, 0.7, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, true, 0.9F, 1.7F,
                    null, List.of()));
        }
        assertTrue(horse.y() < 62.5, "a horse is in lava: " + horse.y());
    }

    @Test
    void inLavaAStriderFloatsUpToItsSurface() throws Colliders.Unknown {
        HorseState s = at(6.5, 62.0, 2.5, false, null, 1.0);
        HorseState after = Horse.tick(s, AHEAD, world, strider(WARM));
        // on lava it travels in the air (gravity, drag), then floatStrider: not above the stable shape, lava over it: half its
        // motion and 0.05 up
        assertEquals(-0.08 * 0.98F * 0.5 + 0.05, after.deltaMovement().y());
        assertFalse(after.onGround());
        // Strider.checkFallDamage: in lava the fall is forgotten, not counted
        HorseState sinking = new HorseState(6.5, 62.4, 2.5, new Vec3d(0.0, -0.2, 0.0), 0.0F, 0.0F, false, false, false, false, 0.0, null, false,
                Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 1.0, false);
        HorseState sunk = Horse.tick(sinking, AHEAD, world, strider(WARM));
        assertTrue(sunk.y() < 62.4, "it sank: " + sunk.y());
        assertEquals(0.0, sunk.fallDistance());
        HorseState pool = at(2.5, 62.0, 2.5, false, null, 1.0);
        for (int i = 0; i < 40; i++) {
            pool = Horse.tick(pool, new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F), world, strider(WARM));
        }
        assertEquals(62.5, pool.y(), "on its surface");
        assertTrue(pool.onGround());
    }

    @Test
    void outOfTheWarmAStriderSuffocatesAndGoesSlower() throws Colliders.Unknown {
        HorseState land = at(-2.5, 63.0, 2.5, true, new BlockPos(-3, 62, 2), 0.0);
        HorseState cold = Horse.tick(land, AHEAD, world, strider(WARM));
        assertTrue(cold.suffocating());
        HorseState warm = Horse.tick(land, AHEAD, world, strider(Set.of("minecraft:lava", "minecraft:stone")));
        assertFalse(warm.suffocating());
        assertTrue(cold.z() - 2.5 < warm.z() - 2.5, "0.35 rather than 0.55: " + cold.z() + " " + warm.z());
        // the lava its last tick found keeps it warm one tick more
        HorseState left = Horse.tick(at(-2.5, 63.0, 2.5, true, new BlockPos(-3, 62, 2), 0.1), AHEAD, world, strider(WARM));
        assertFalse(left.suffocating());
        assertEquals(warm.z(), left.z());
    }

    @Test
    void aNoAiStridersClientLeavesItsSuffocationAlone() throws Colliders.Unknown {
        HorseState land = at(-2.5, 63.0, 2.5, true, new BlockPos(-3, 62, 2), 0.0);
        assertFalse(Horse.tick(land, AHEAD, world, strider(null)).suffocating());
        HorseState suffocating = new HorseState(-2.5, 63.0, 2.5, Vec3d.ZERO, 0.0F, 0.0F, true, false, false, false, 0.0, new BlockPos(-3, 62, 2),
                false, Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 1.0, true);
        assertTrue(Horse.tick(suffocating, AHEAD, world, strider(null)).suffocating());
    }
}
