package dev.whynotac.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.world.BlockStates;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A happy ghast its rider steers ({@code HappyGhast}, decompiled 1.21.11): ahead along the rider's pitch, up while the
 * rider jumps, no gravity, 0.91 of its motion kept in air and 0.8 in water, turning 8% of the way to the rider's yaw.
 */
class HappyGhastTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final Horse.Flying FLYING = Horse.Flying.of(0.05);

    /** Stone up to y 61, water from y 62 to 65 over x below 0, air elsewhere. */
    private final Colliders.World world = new Colliders.World() {
        @Override
        public BlockStates states() {
            return STATES;
        }

        @Override
        public int blockState(int x, int y, int z) {
            if (y < 62) {
                return STATES.firstStateOf("minecraft:stone");
            }
            return STATES.firstStateOf(x < 0 && y <= 65 ? "minecraft:water" : "minecraft:air");
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return true;
        }
    };

    private static Horse.Conditions ghast() {
        return new Horse.Conditions(0.0F, 0.0, 1.0F, 0.08, Horse.Effects.NONE, 0.8F, false, 4.0F, 4.0F, null, List.of(), null, FLYING);
    }

    private static HorseState at(double x, double y, double z, float yRot) {
        return new HorseState(x, y, z, Vec3d.ZERO, yRot, 0.0F, false, false, false, false, 0.0, null, false, Vec3d.ZERO, false, 0, 0.0F, false,
                false, 0, null, 0.0, false);
    }

    @Test
    void aheadAlongTheRidersPitchWithNoGravity() throws Colliders.Unknown {
        HorseState s = Horse.tick(at(10.5, 70.0, 0.5, 0.0F), new Horse.Rider(0.0F, 1.0F, 0.0F, 0.0F), world, ghast());
        assertEquals(70.0, s.y(), "no gravity");
        double pushed = 3.9F * 0.05 * ((float) 0.05 * 5.0F / 3.0F); // the input times the speed, not normalized (under 1)
        assertEquals(0.5 + pushed, s.z(), 1.0e-15);
        assertEquals(pushed * 0.91F, s.deltaMovement().z(), 1.0e-15);
        HorseState down = Horse.tick(at(10.5, 70.0, 0.5, 0.0F), new Horse.Rider(0.0F, 1.0F, 0.0F, 90.0F), world, ghast());
        assertTrue(down.y() < 70.0 && Math.abs(down.z() - 0.5) < 1.0e-6, "looking down: down: " + down);
        assertEquals(45.0F, down.xRot(), "half the rider's pitch");
        HorseState back = Horse.tick(at(10.5, 70.0, 0.5, 0.0F), new Horse.Rider(0.0F, -1.0F, 0.0F, 0.0F), world, ghast());
        assertEquals(0.5 - pushed * 0.5, back.z(), 1.0e-15);
    }

    @Test
    void upWhileTheRiderJumps() throws Colliders.Unknown {
        HorseState s = Horse.tick(at(10.5, 70.0, 0.5, 0.0F), new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F, true), world, ghast());
        assertEquals(70.0 + 0.5F * 3.9F * 0.05 * ((float) 0.05 * 5.0F / 3.0F), s.y(), 1.0e-15);
        HorseState still = Horse.tick(at(10.5, 70.0, 0.5, 0.0F), new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F), world, ghast());
        assertEquals(new Vec3d(10.5, 70.0, 0.5), new Vec3d(still.x(), still.y(), still.z()), "nothing pressed: it stays");
    }

    @Test
    void itTurnsAPartOfTheWayToTheRidersYaw() throws Colliders.Unknown {
        HorseState s = Horse.tick(at(10.5, 70.0, 0.5, 170.0F), new Horse.Rider(0.0F, 0.0F, -170.0F, 0.0F), world, ghast());
        assertEquals(170.0F + VanillaMath.wrapDegrees(-340.0F) * 0.08F, s.yRot(), "the short way round");
        assertEquals(171.6F, s.yRot(), 1.0e-4F);
    }

    @Test
    void inWaterItKeepsLessOfItsMotionAndNeverCountsAFall() throws Colliders.Unknown {
        HorseState water = Horse.tick(at(-10.5, 62.0, 0.5, 0.0F), new Horse.Rider(0.0F, 1.0F, 0.0F, 0.0F), world, ghast());
        double pushed = 3.9F * 0.05 * ((float) 0.05 * 5.0F / 3.0F);
        assertEquals(pushed * 0.8F, water.deltaMovement().z(), 1.0e-15);
        HorseState falling = new HorseState(10.5, 63.0, 0.5, new Vec3d(0.0, -0.5, 0.0), 0.0F, 0.0F, false, false, false, false, 0.0, null, false,
                Vec3d.ZERO, false, 0, 0.0F, false, false, 0, null, 0.0, false);
        HorseState landed = Horse.tick(falling, new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F), world, ghast());
        assertEquals(62.5, landed.y());
        assertEquals(0.0, landed.fallDistance(), "checkFallDamage does nothing");
    }
}
