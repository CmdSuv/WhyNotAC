package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket.EntityPose;
import dev.whynotac.protocol.packet.ClientboundPacket.SetEntityData;
import dev.whynotac.world.EntitySizes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every size {@link EntitySizes} claims is the one the game's own {@code getDimensions(pose)} gives, for
 * every type, every pose, a baby and an adult, scales across and beyond the attribute's range, and the
 * values each type's size data can take (every armor stand flag byte, every sniffer state, slime and
 * phantom sizes, puff states and salmon variants out of range too, interaction and cloud floats). The
 * entities are the game's classes, allocated without a level ({@link GameEntities}); the pose is set in
 * their data as well, as the client's {@code refreshDimensions} reads {@code getPose()}.
 */
class EntitySizesGameTest {

    private static final double[] SCALES = {1.0, 0.0625, 0.5, 0.75, 1.5, 2.0, 3.0, 3.5, 16.0, 0.01, 20.0};
    private static final int[] SLIME_SIZES = {-2, 0, 1, 2, 3, 4, 10, 127, 128, 255, 1000};
    private static final int[] PHANTOM_SIZES = {-5, -1, 0, 1, 2, 5, 32, 64, 65, 200};
    private static final int[] SMALL_INTS = {-1, 0, 1, 2, 3, 7, 100};
    private static final float[] FLOATS = {0.0F, 0.5F, 1.0F, 2.75F, 0.001F, 100.5F, -1.0F, 32.0F};

    private static final EntitySizes SIZES = EntitySizes.vanilla();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyClaimedSizeIsTheGamesOwn() throws ReflectiveOperationException {
        int claimed = 0;
        Map<String, Integer> unclaimed = new TreeMap<>();
        for (Field field : EntityType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityType.class) {
                continue;
            }
            EntityType<?> type = (EntityType<?>) field.get(null);
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            Class<?> entityClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            if (type == EntityType.PLAYER) {
                entityClass = ServerPlayer.class; // Player is abstract
            }
            EntitySizes.Entry entry = SIZES.entry(key);
            Entity entity = GameEntities.allocate(type, entityClass).entity();
            EntityDataAccessor<Boolean> babyFlag = babyFlag(entity);
            for (int[] data : dataValues(entry)) {
                set(entity, entry, data);
                for (boolean baby : new boolean[] {false, true}) {
                    if (babyFlag != null) {
                        GameEntities.set(entity, babyFlag, baby);
                    }
                    for (double scale : entity instanceof LivingEntity ? SCALES : new double[] {1.0}) {
                        double attribute = scale;
                        if (entity instanceof LivingEntity living) {
                            AttributeInstance instance = living.getAttribute(Attributes.SCALE);
                            instance.setBaseValue(scale);
                            attribute = EntitySizes.clampScale(scale);
                            assertEquals(instance.getValue(), attribute, "the scale attribute's range");
                        }
                        for (int animation : animations(entry))
                        for (Pose pose : Pose.values()) {
                            if (entry.rule() == EntitySizes.Rule.POLAR_BEAR) {
                                GameEntities.set(PolarBear.class, "clientSideStandAnimation", entity, (float) animation);
                            }
                            GameEntities.set(entity, GameEntities.accessor(Entity.class, "DATA_POSE"), pose);
                            EntitySizes.Size ours = SIZES.size(key, EntityPose.valueOf(pose.name()).ordinal(), baby, attribute, data[0], data[1],
                                    animation);
                            String where = key + " pose " + pose + " baby " + baby + " scale " + scale + " data " + data[0] + "," + data[1]
                                    + " animation " + animation;
                            if (ours == null) {
                                assertEquals(EntitySizes.Rule.STATE, entry.rule(), where + ": no size claimed");
                                unclaimed.merge(key, 1, Integer::sum);
                                continue;
                            }
                            EntityDimensions game = entity.getDimensions(pose);
                            assertEquals(Float.floatToRawIntBits(game.width()), Float.floatToRawIntBits(ours.width()), where + ": width " + game);
                            assertEquals(Float.floatToRawIntBits(game.height()), Float.floatToRawIntBits(ours.height()), where + ": height " + game);
                            assertEquals(Float.floatToRawIntBits(game.eyeHeight()), Float.floatToRawIntBits(ours.eyeHeight()), where + ": eye " + game);
                            assertEquals(game.fixed(), ours.fixed(), where + ": fixed " + game);
                            claimed++;
                        }
                    }
                }
            }
        }
        assertTrue(claimed > 100_000, claimed + " sizes compared");
        assertEquals(List.of("minecraft:breeze_wind_charge", "minecraft:glow_item_frame", "minecraft:item_frame", "minecraft:leash_knot",
                "minecraft:painting", "minecraft:shulker", "minecraft:wind_charge"), List.copyOf(unclaimed.keySet()),
                "the types whose size is not claimed: boxes not their size around the position");
    }

    /** The client's standing animation, for the type whose size reads it. */
    private static int[] animations(EntitySizes.Entry entry) {
        return entry.rule() == EntitySizes.Rule.POLAR_BEAR ? new int[] {0, 1, 2, 3, 4, 5, 6} : new int[] {0};
    }

    @Test
    void anEntityStartsWithTheSizeOfItsInitialData() throws ReflectiveOperationException {
        int compared = 0;
        for (Field field : EntityType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityType.class) {
                continue;
            }
            EntityType<?> type = (EntityType<?>) field.get(null);
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            Class<?> entityClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            if (type == EntityType.PLAYER) {
                entityClass = ServerPlayer.class;
            }
            GameEntities.Allocated allocated = GameEntities.allocate(type, entityClass);
            EntitySizes.Size ours = SIZES.size(key, EntityPose.STANDING.ordinal(), false, 1.0);
            if (!allocated.initial() || ours == null) {
                continue;
            }
            EntityDimensions game = allocated.entity().getDimensions(Pose.STANDING);
            assertEquals(new EntitySizes.Size(game.width(), game.height(), game.eyeHeight(), game.fixed()), ours, key);
            compared++;
        }
        assertTrue(compared > 140, compared + " types compared");
    }

    @Test
    void pickabilityIsTheGamesOwn() throws ReflectiveOperationException {
        int compared = 0;
        for (Field field : EntityType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityType.class) {
                continue;
            }
            EntityType<?> type = (EntityType<?>) field.get(null);
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
            Class<?> entityClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            if (type == EntityType.PLAYER) {
                entityClass = ServerPlayer.class;
            }
            EntitySizes.Entry entry = SIZES.entry(key);
            Entity entity = GameEntities.allocate(type, entityClass).entity();
            if (entry.pick() == EntitySizes.Pick.TAG) {
                // Projectile.isPickable reads a tag the server sends the client; this test has no tags bound.
                assertEquals(-1, SIZES.pickable(key, 0, -1), key);
                continue;
            }
            int[] flags = entry.pick() == EntitySizes.Pick.ARMOR_STAND ? new int[256] : new int[1];
            for (int v = 0; v < flags.length; v++) {
                flags[v] = v;
            }
            for (int v : flags) {
                set(entity, entry, new int[] {v, 0});
                // A player is a spectator or not by its game mode (the client's player list); here, not one.
                int ours = SIZES.pickable(key, v, 0);
                assertEquals(entity.isPickable() ? 1 : 0, ours, key + " flags " + v);
                assertEquals(Float.floatToRawIntBits(entity.getPickRadius()), Float.floatToRawIntBits(ours == 1 ? entry.pickRadius() : 0.0F),
                        key + " pick radius");
                compared++;
            }
        }
        assertTrue(compared > 300, compared + " compared");
    }

    /** The data values to try for the type's size data: two per row, as {@link EntitySizes#size} takes them. */
    private static List<int[]> dataValues(EntitySizes.Entry entry) {
        List<int[]> values = new ArrayList<>();
        switch (entry.rule()) {
            case SLIME -> {
                for (int v : SLIME_SIZES) {
                    values.add(new int[] {v, 0});
                }
            }
            case PHANTOM -> {
                for (int v : PHANTOM_SIZES) {
                    values.add(new int[] {v, 0});
                }
            }
            case PUFFERFISH, SALMON -> {
                for (int v : SMALL_INTS) {
                    values.add(new int[] {v, 0});
                }
            }
            case ARMOR_STAND -> {
                for (int v = 0; v < 256; v++) {
                    values.add(new int[] {v, 0});
                }
            }
            case SNIFFER -> {
                for (Sniffer.State state : Sniffer.State.values()) {
                    values.add(new int[] {state.id(), 0});
                }
            }
            case INTERACTION -> {
                for (float w : FLOATS) {
                    for (float h : FLOATS) {
                        values.add(new int[] {Float.floatToRawIntBits(w), Float.floatToRawIntBits(h)});
                    }
                }
            }
            case CLOUD -> {
                for (float r : FLOATS) {
                    values.add(new int[] {Float.floatToRawIntBits(r), 0});
                }
            }
            case POLAR_BEAR -> {
                values.add(new int[] {0, 0});
                values.add(new int[] {1, 0});
            }
            default -> values.add(new int[] {entry.initial(0), entry.initial(1)});
        }
        return values;
    }

    /** Puts {@code data} into the entity's fields the entry names. */
    @SuppressWarnings("unchecked")
    private static void set(Entity entity, EntitySizes.Entry entry, int[] data) throws ReflectiveOperationException {
        for (int i = 0; i < entry.data().size(); i++) {
            EntitySizes.DataField f = entry.data().get(i);
            EntityDataAccessor<Object> accessor = (EntityDataAccessor<Object>) GameEntities.accessor(entity, f.index());
            Object value = switch (f.kind()) {
                case INT -> data[i];
                case BYTE -> (byte) data[i];
                case FLOAT -> Float.intBitsToFloat(data[i]);
                case SNIFFER_STATE -> Sniffer.State.BY_ID.apply(data[i]);
                case BOOLEAN -> data[i] != 0;
                default -> throw new AssertionError(f);
            };
            GameEntities.set(entity, accessor, value);
        }
    }

    /** The {@code DATA_BABY_ID} the entity's class hierarchy defines (whether or not its isBaby() reads it), or null. */
    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Boolean> babyFlag(Entity entity) throws ReflectiveOperationException {
        for (Class<?> k = entity.getClass(); k != Object.class; k = k.getSuperclass()) {
            try {
                Field field = k.getDeclaredField("DATA_BABY_ID");
                field.setAccessible(true);
                return (EntityDataAccessor<Boolean>) field.get(null);
            } catch (NoSuchFieldException ignored) {
                // above
            }
        }
        return null;
    }

    static {
        // Kinds the size data uses; any other would need a branch in set().
        assertTrue(SetEntityData.Kind.values().length >= 11);
    }
}
