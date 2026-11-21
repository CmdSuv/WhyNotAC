package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.world.EntityTypes;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The entity-type table whynotac-world ships equals what the game's own classes give. The client and the
 * server share these classes, so the server jar is an exact source: each type's entity class is the
 * type argument of its {@code EntityType} field.
 *
 * <p>Interpolation (decompiled 1.21.11): {@code Entity.getInterpolation()} returns null (the client sets
 * the position at once) unless overridden. {@code LivingEntity}, {@code AbstractBoat},
 * {@code FishingHook} and {@code ExperienceOrb} build an {@code InterpolationHandler} of 3 steps;
 * {@code AbstractMinecart} delegates to its behaviour, whose default ({@code OldMinecartBehavior}, the new
 * one is an experimental feature) also has 3; {@code Display} has 0 steps (it snaps); {@code Shulker}
 * overrides it back to null.
 */
class EntityTypesDataTest {

    /** Every class that declares getInterpolation(): if a game update adds one, this table must learn it. */
    private static final Set<String> DECLARE_INTERPOLATION = Set.of(Entity.class.getName(), LivingEntity.class.getName(),
            AbstractBoat.class.getName(), AbstractMinecart.class.getName(), FishingHook.class.getName(), ExperienceOrb.class.getName(),
            Display.class.getName(), Shulker.class.getName());

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theShippedTableIsTheGamesOwn() throws IOException, ReflectiveOperationException {
        List<String> expected = new ArrayList<>();
        Set<String> declaring = new TreeSet<>();
        for (Field field : EntityType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityType.class) {
                continue;
            }
            EntityType<?> type = (EntityType<?>) field.get(null);
            Class<?> entityClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            for (Class<?> c = entityClass; c != Object.class; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod("getInterpolation");
                    declaring.add(c.getName());
                } catch (NoSuchMethodException ignored) {
                    // not this one
                }
            }
            expected.add(BuiltInRegistries.ENTITY_TYPE.getKey(type) + "\t" + (LivingEntity.class.isAssignableFrom(entityClass) ? 1 : 0)
                    + "\t" + interpolationSteps(entityClass));
        }
        assertEquals(new TreeSet<>(DECLARE_INTERPOLATION), declaring, "classes with their own interpolation");
        expected.sort(null);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.size(), expected.size(), "one field per registered type");
        String table = String.join("\n", expected) + "\n";
        String shipped = EntityTypes.vanillaTable();
        if (!table.equals(shipped)) {
            Path out = Path.of("build/entity-types.tsv");
            Files.writeString(out, table, StandardCharsets.UTF_8);
            assertEquals(table, shipped, "the shipped table differs; the game's is in " + out.toAbsolutePath());
        }
    }

    private static int interpolationSteps(Class<?> c) {
        if (Shulker.class.isAssignableFrom(c)) {
            return EntityTypes.NO_INTERPOLATION;
        }
        if (Display.class.isAssignableFrom(c)) {
            return 0;
        }
        if (LivingEntity.class.isAssignableFrom(c) || AbstractBoat.class.isAssignableFrom(c) || AbstractMinecart.class.isAssignableFrom(c)
                || FishingHook.class.isAssignableFrom(c) || ExperienceOrb.class.isAssignableFrom(c)) {
            return 3;
        }
        return EntityTypes.NO_INTERPOLATION;
    }
}
