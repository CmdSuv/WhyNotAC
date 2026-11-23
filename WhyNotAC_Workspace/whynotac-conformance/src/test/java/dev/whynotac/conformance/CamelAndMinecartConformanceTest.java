package dev.whynotac.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.Colliders;
import dev.whynotac.physics.Horse;
import dev.whynotac.physics.HorseState;
import dev.whynotac.physics.Minecart;
import dev.whynotac.world.BlockStates;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.camel.Camel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Conformance checks for 1.21.11 {@link Horse.Camel} dash physics (55-tick cooldown, horizontal & vertical
 * launch factors, entity dimensions) and {@link Minecart} dimensions and vanilla rail constants against
 * Paper 1.21.11 NMS.
 */
class CamelAndMinecartConformanceTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final int STONE = STATES.firstStateOf("minecraft:stone");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void camelDashCooldownDimensionsAndLaunchMatchVanilla12111() throws Exception {
        assertEquals(Camel.DASH_COOLDOWN_TICKS, 55);
        assertEquals(EntityType.CAMEL.getWidth(), 1.7F, 1.0e-6F);
        assertEquals(EntityType.CAMEL.getHeight(), 2.375F, 1.0e-6F);

        Colliders.World flatStone = new Colliders.World() {
            @Override public BlockStates states() { return STATES; }
            @Override public int blockState(int x, int y, int z) { return y <= 63 ? STONE : AIR; }
            @Override public boolean isLoaded(int chunkX, int chunkZ) { return true; }
        };
        double baseSpeed = 0.09;
        Horse.Conditions cond = new Horse.Conditions(
                (float) baseSpeed, 0.42, 1.5F, 0.08, Horse.Effects.NONE, 0.8F, true,
                EntityType.CAMEL.getWidth(), EntityType.CAMEL.getHeight(),
                null, List.of(), null, null, null, new Horse.Camel(baseSpeed, false));
        HorseState start = new HorseState(
                0.5, 64.0, 0.5, Vec3d.ZERO, 0.0F, 0.0F,
                true, false, true, true, 0.0,
                null, false, Vec3d.ZERO, false,
                0, Horse.jumpPendingScale(100), false, false, 0,
                null, 0.0, false, 0, false);

        HorseState afterDash = Horse.tick(start, new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F), flatStone, cond);
        assertTrue(afterDash.dashing(), "Camel should enter dashing state on full-power riding jump");
        // Camel.tick runs super.tick() (executeRidersJump sets dashCooldown=55) then decrements dashCooldown -> 54 at end of dash tick
        assertEquals(Camel.DASH_COOLDOWN_TICKS - 1, afterDash.dashCooldown(), "Camel dash cooldown must end dash tick at 54");
        assertTrue(afterDash.deltaMovement().z() > 1.0, "Camel dash horizontal impulse along yaw=0 (+Z): " + afterDash.deltaMovement());
        assertTrue(afterDash.y() > 64.4, "Camel dash vertical lift: " + afterDash.y());

        // Subsequent ticks decrement dashCooldown cleanly by 1 per tick
        HorseState tick2 = Horse.tick(afterDash, new Horse.Rider(0.0F, 0.0F, 0.0F, 0.0F), flatStone, cond);
        assertEquals(Camel.DASH_COOLDOWN_TICKS - 2, tick2.dashCooldown());
    }

    @Test
    void minecartDimensionsAndDefaultSpeedMatchVanilla12111() {
        assertEquals(EntityType.MINECART.getWidth(), Minecart.WIDTH, 1.0e-6F);
        assertEquals(EntityType.MINECART.getHeight(), Minecart.HEIGHT, 1.0e-6F);
        assertEquals(0.4, Minecart.DEFAULT_MAX_RAIL_SPEED, 1.0e-12);
        assertEquals(0.2, Minecart.WATER_MAX_RAIL_SPEED, 1.0e-12);
    }
}
