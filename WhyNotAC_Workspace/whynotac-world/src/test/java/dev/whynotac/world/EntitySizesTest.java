package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.whynotac.protocol.packet.ClientboundPacket.EntityPose;
import org.junit.jupiter.api.Test;

/** The size rules over the shipped tables, with values worked out by hand from the decompiled 1.21.11 client. */
class EntitySizesTest {

    private static final EntitySizes SIZES = EntitySizes.vanilla();
    private static final int STANDING = EntityPose.STANDING.ordinal();

    @Test
    void everyTypeIsInTheTable() {
        assertEquals(EntityTypes.vanilla().size(), SIZES.size());
    }

    @Test
    void playersArePosedAndScaled() {
        assertEquals(new EntitySizes.Size(0.6F, 1.8F, 1.62F, false), SIZES.size("minecraft:player", STANDING, false, 1.0));
        assertEquals(new EntitySizes.Size(0.6F, 1.5F, 1.27F, false), SIZES.size("minecraft:player", EntityPose.CROUCHING.ordinal(), false, 1.0));
        assertEquals(new EntitySizes.Size(0.6F, 0.6F, 0.4F, false), SIZES.size("minecraft:player", EntityPose.SWIMMING.ordinal(), false, 1.0));
        // A pose without its own size is the standing one (POSES.getOrDefault); the scale multiplies floats.
        assertEquals(new EntitySizes.Size(0.6F * 2.0F, 1.8F * 2.0F, 1.62F * 2.0F, false),
                SIZES.size("minecraft:player", EntityPose.SITTING.ordinal(), false, 2.0));
        // Fixed sizes ignore the scale: dying, and sleeping for every living entity.
        assertEquals(new EntitySizes.Size(0.2F, 0.2F, 1.62F, true), SIZES.size("minecraft:player", EntityPose.DYING.ordinal(), false, 3.0));
        assertEquals(new EntitySizes.Size(0.2F, 0.2F, 0.2F, true), SIZES.size("minecraft:villager", EntityPose.SLEEPING.ordinal(), true, 2.0));
    }

    @Test
    void babiesUseTheirAgeScaleOrTheirOwnSize() {
        // Villager: AgeableMob, LivingEntity's 0.5.
        assertEquals(new EntitySizes.Size(0.6F * 0.5F, 1.95F * 0.5F, 1.62F * 0.5F, false), SIZES.size("minecraft:villager", STANDING, true, 1.0));
        // Dolphin: its own age scale.
        assertEquals(new EntitySizes.Size(0.9F * 0.65F, 0.6F * 0.65F, 0.3F * 0.65F, false), SIZES.size("minecraft:dolphin", STANDING, true, 1.0));
        // Zombie and its subclasses: Zombie.BABY_DIMENSIONS (the zombie's size halved, eye 0.93), then the scale.
        assertEquals(new EntitySizes.Size(0.3F, 0.975F, 0.93F, false), SIZES.size("minecraft:husk", STANDING, true, 1.0));
        assertEquals(new EntitySizes.Size(0.3F * 1.5F, 0.975F * 1.5F, 0.93F * 1.5F, false), SIZES.size("minecraft:husk", STANDING, true, 1.5));
        // A type that is never a baby ignores the flag.
        assertEquals(new EntitySizes.Size(0.5F, 0.9F, 0.45F, false), SIZES.size("minecraft:bat", STANDING, true, 1.0));
    }

    @Test
    void onePoseCanHaveItsOwnSize() {
        EntitySizes.Size sitting = SIZES.size("minecraft:camel", EntityPose.SITTING.ordinal(), false, 1.0);
        assertEquals(new EntitySizes.Size(1.7F, 2.375F - 1.43F, sitting.eyeHeight(), false), sitting);
        // A sitting baby camel: the sitting size times the camel's age scale.
        assertEquals(sitting.scale(0.45F), SIZES.size("minecraft:camel", EntityPose.SITTING.ordinal(), true, 1.0));
        assertEquals(new EntitySizes.Size(1.7F, 2.375F, 2.275F, false), SIZES.size("minecraft:camel", STANDING, false, 1.0));
    }

    @Test
    void scaleCapsFollowTheType() {
        // Ender dragon: always 1; happy ghast: at most 1; shulker's size depends on its peek (state).
        assertSame(SIZES.entry("minecraft:ender_dragon").size(), SIZES.size("minecraft:ender_dragon", STANDING, false, 4.0));
        assertEquals(new EntitySizes.Size(4.0F * 0.5F, 4.0F * 0.5F, 2.6F * 0.5F, false), SIZES.size("minecraft:happy_ghast", STANDING, false, 0.5));
        assertSame(SIZES.entry("minecraft:happy_ghast").size(), SIZES.size("minecraft:happy_ghast", STANDING, false, 2.0));
        assertNull(SIZES.size("minecraft:shulker", STANDING, false, 1.0));
    }

    @Test
    void nonLivingTypesKeepTheirSizeAndStateTypesClaimNone() {
        assertEquals(new EntitySizes.Size(2.0F, 2.0F, 1.7F, false), SIZES.size("minecraft:end_crystal", EntityPose.SLEEPING.ordinal(), true, 9.0));
        assertNull(SIZES.size("minecraft:item_frame", STANDING, false, 1.0));
        assertNull(SIZES.size("minecraft:wind_charge", STANDING, false, 1.0));
        assertNull(SIZES.size("minecraft:not_a_type", STANDING, false, 1.0));
    }

    @Test
    void aPolarBearGrowsWithItsStandingAnimation() {
        // PolarBear.getDefaultDimensions: height and eye scaled by 1 + stand / 6, before the scale; a baby too
        EntitySizes.Size bear = SIZES.entry("minecraft:polar_bear").size();
        assertEquals(bear, SIZES.size("minecraft:polar_bear", STANDING, false, 1.0, 1, 0, 0), "the flag alone does nothing");
        assertEquals(bear.scale(1.0F, 1.0F + 3 / 6.0F), SIZES.size("minecraft:polar_bear", STANDING, false, 1.0, 1, 0, 3));
        assertEquals(new EntitySizes.Size(1.4F, 1.4F * 2.0F, 1.19F * 2.0F, false), SIZES.size("minecraft:polar_bear", STANDING, false, 1.0, 1, 0, 6));
        assertEquals(bear.scale(0.5F).scale(1.0F, 2.0F).scale(2.0F), SIZES.size("minecraft:polar_bear", STANDING, true, 2.0, 1, 0, 6));
        assertEquals(SIZES.size("minecraft:husk", EntityPose.SLEEPING.ordinal(), false, 1.0),
                SIZES.size("minecraft:polar_bear", EntityPose.SLEEPING.ordinal(), false, 1.0, 1, 0, 6), "sleeping: fixed");
    }

    @Test
    void dataScalesOrReplacesTheDefault() {
        EntitySizes.Size slime = SIZES.entry("minecraft:slime").size();
        assertEquals(slime, SIZES.size("minecraft:slime", STANDING, false, 1.0), "size 1 at first");
        assertEquals(slime.scale(4.0F).scale(1.5F), SIZES.size("minecraft:slime", STANDING, false, 1.5, 4, 0));
        EntitySizes.Size phantom = SIZES.entry("minecraft:phantom").size();
        assertEquals(phantom.scale(1.0F + 0.15F * 5), SIZES.size("minecraft:phantom", STANDING, false, 1.0, 5, 0));
        // Puff state 0 and 1 have their own factor, every other state the last; the fish starts deflated.
        EntitySizes.Size puffer = SIZES.entry("minecraft:pufferfish").size();
        assertEquals(puffer.scale(0.5F), SIZES.size("minecraft:pufferfish", STANDING, false, 1.0));
        assertEquals(puffer.scale(0.7F), SIZES.size("minecraft:pufferfish", STANDING, false, 1.0, 1, 0));
        assertEquals(puffer, SIZES.size("minecraft:pufferfish", STANDING, false, 1.0, -3, 0));
        // Salmon variants clamp their id.
        EntitySizes.Size salmon = SIZES.entry("minecraft:salmon").size();
        assertEquals(salmon.scale(1.5F), SIZES.size("minecraft:salmon", STANDING, false, 1.0, 9, 0));
        assertEquals(salmon.scale(0.5F), SIZES.size("minecraft:salmon", STANDING, false, 1.0, -1, 0));
        // A digging or emerging warden: fixed, 1 high, the eye at 0.85; scales do not change it.
        assertEquals(new EntitySizes.Size(0.9F, 1.0F, 0.85F, true), SIZES.size("minecraft:warden", EntityPose.EMERGING.ordinal(), false, 2.0));
        // A digging sniffer, a baby one at half.
        EntitySizes.Entry sniffer = SIZES.entry("minecraft:sniffer");
        assertEquals(sniffer.alt().scale(0.5F), SIZES.size("minecraft:sniffer", STANDING, true, 1.0, sniffer.select(), 0));
        // An area effect cloud: twice the radius wide.
        assertEquals(new EntitySizes.Size(6.0F, 0.5F, 0.425F, false), SIZES.size("minecraft:area_effect_cloud", STANDING, false, 1.0));
    }

    @Test
    void pickabilityFollowsTheType() {
        assertEquals(1, SIZES.pickable("minecraft:husk", 0, -1));
        assertEquals(0, SIZES.pickable("minecraft:item", 0, -1));
        assertEquals(0, SIZES.pickable("minecraft:armor_stand", EntitySizes.ARMOR_STAND_MARKER | EntitySizes.ARMOR_STAND_SMALL, -1));
        assertEquals(1, SIZES.pickable("minecraft:armor_stand", EntitySizes.ARMOR_STAND_SMALL, -1));
        assertEquals(0, SIZES.pickable("minecraft:player", 0, 1), "a spectator");
        assertEquals(1, SIZES.pickable("minecraft:player", 0, 0));
        assertEquals(-1, SIZES.pickable("minecraft:player", 0, -1));
        assertEquals(1, SIZES.pickable("minecraft:mannequin", 0, -1), "not a player: no game mode");
        assertEquals(-1, SIZES.pickable("minecraft:snowball", 0, -1), "a projectile: by a tag the server sends");
        assertEquals(1, SIZES.pickable("minecraft:interaction", 0, -1));
        assertEquals(-1, SIZES.pickable("minecraft:not_a_type", 0, -1));
        assertEquals(0.0F, SIZES.entry("minecraft:husk").pickRadius());
        assertEquals(1.0F, SIZES.entry("minecraft:snowball").pickRadius());
    }

    @Test
    void theBoxIsHalfTheWidthEachSide() {
        EntitySizes.Box box = new EntitySizes.Size(0.6F, 1.8F, 1.62F, false).box(10.5, 64.0, -3.25);
        float half = 0.6F / 2.0F;
        assertEquals(new EntitySizes.Box(10.5 - half, 64.0, -3.25 - half, 10.5 + half, 64.0 + 1.8F, -3.25 + half), box);
    }

    @Test
    void theScaleAttributeIsClampedLikeTheGame() {
        assertEquals(0.0625, EntitySizes.clampScale(Double.NaN));
        assertEquals(0.0625, EntitySizes.clampScale(-5.0));
        assertEquals(16.0, EntitySizes.clampScale(100.0));
        assertEquals(1.25, EntitySizes.clampScale(1.25));
    }
}
