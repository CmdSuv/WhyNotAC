package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.whynotac.world.EntitySizes;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The entity sizes whynotac-world ships equal what the game's own classes give (the client and the server
 * share them). Sizes, baby sizes, pose sizes and the data index of each baby flag are read from the
 * classes; which method each type's size comes from is found by reflection, so a game update that adds
 * an override fails here instead of going unnoticed.
 *
 * <p>What reflection cannot read is written out below from the decompiled 1.21.11 sources, and every set
 * of overriding classes is checked against them:
 * <ul>
 *   <li>{@code LivingEntity.getDimensions(pose)}: {@code SLEEPING_DIMENSIONS} when sleeping, else
 *       {@code getDefaultDimensions(pose).scale(getScale())}; the default is the type's size scaled by
 *       {@code getAgeScale()} (0.5 for a baby, other constants in {@link #AGE_SCALES});</li>
 *   <li>{@code getScale()} is the scale attribute (clamped to 0.0625..16) through {@code sanitizeScale}
 *       ({@link #SCALE_CAPS});</li>
 *   <li>classes in {@link #BABY_SIZE} return a constant {@code BABY_DIMENSIONS} for a baby; classes in
 *       {@link #POSE_SIZE} a constant size in one pose; classes in {@link #STATE} depend on other data
 *       (size, puff state, digging, an animation, flags) and are not followed yet.</li>
 * </ul>
 */
class EntitySizesDataTest {

    /** Classes declaring getDefaultDimensions(Pose) that return a constant for a baby, and where it is. */
    private static final Map<String, String> BABY_SIZE = Map.ofEntries(
            Map.entry("net.minecraft.world.entity.animal.chicken.Chicken", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.equine.Horse", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.equine.SkeletonHorse", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.equine.Llama", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.equine.ZombieHorse", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.fox.Fox", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.cow.AbstractCow", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.panda.Panda", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.animal.turtle.Turtle", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.monster.piglin.Piglin", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.monster.zombie.Zombie", "BABY_DIMENSIONS"),
            Map.entry("net.minecraft.world.entity.monster.zombie.ZombifiedPiglin", "BABY_DIMENSIONS"),
            // an instance field: the type's size with other attachments, scaled by 0.5 (constructor)
            Map.entry("net.minecraft.world.entity.animal.equine.AbstractChestedHorse", "*0.5"));

    /** Classes declaring getDefaultDimensions(Pose) with a constant size (scaled by the age scale) in one pose. */
    private static final Map<String, String[]> POSE_SIZE = Map.of(
            "net.minecraft.world.entity.animal.camel.Camel", new String[] {"SITTING", "SITTING_DIMENSIONS"},
            "net.minecraft.world.entity.animal.goat.Goat", new String[] {"LONG_JUMPING", "LONG_JUMPING_DIMENSIONS"});

    /** Classes whose size depends on data this table does not follow. */
    private static final Set<String> STATE = Set.of(
            "net.minecraft.world.entity.decoration.ArmorStand",      // marker and small flags
            "net.minecraft.world.entity.animal.polarbear.PolarBear", // a client-side standing animation
            "net.minecraft.world.entity.animal.sniffer.Sniffer",     // digging state
            "net.minecraft.world.entity.animal.fish.Salmon",         // variant
            "net.minecraft.world.entity.animal.fish.Pufferfish",     // puff state
            "net.minecraft.world.entity.monster.Phantom",            // size
            "net.minecraft.world.entity.monster.Slime",              // size
            "net.minecraft.world.entity.monster.warden.Warden");     // digging or emerging

    /** Every class declaring getDefaultDimensions(Pose) besides the ones above. */
    private static final Set<String> DEFAULT_SIZE = Set.of(LivingEntity.class.getName(), Avatar.class.getName());

    /** Classes declaring getDimensions(Pose): the base, LivingEntity (final) and non-living types sized by data. */
    private static final Set<String> GET_DIMENSIONS = Set.of(Entity.class.getName(), LivingEntity.class.getName(),
            "net.minecraft.world.entity.AreaEffectCloud", "net.minecraft.world.entity.Interaction");

    /** Classes declaring makeBoundingBox(Vec3): their box is not their size around the position. */
    private static final Set<String> BOX = Set.of(Entity.class.getName(), "net.minecraft.world.entity.Interaction",
            "net.minecraft.world.entity.monster.Shulker", "net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge");

    /** getAgeScale() for a baby, per declaring class (an adult is 1). */
    private static final Map<String, Float> AGE_SCALES = Map.of(
            LivingEntity.class.getName(), 0.5F,
            "net.minecraft.world.entity.animal.dolphin.Dolphin", 0.65F,
            "net.minecraft.world.entity.animal.camel.Camel", 0.45F,
            "net.minecraft.world.entity.animal.armadillo.Armadillo", 0.6F,
            "net.minecraft.world.entity.animal.happyghast.HappyGhast", 0.2375F,
            "net.minecraft.world.entity.animal.turtle.Turtle", 0.3F);

    /** sanitizeScale(float), per declaring class, in the table's notation. */
    private static final Map<String, String> SCALE_CAPS = Map.of(
            LivingEntity.class.getName(), "any",
            "net.minecraft.world.entity.animal.happyghast.HappyGhast", "max:1.0",
            "net.minecraft.world.entity.boss.enderdragon.EnderDragon", "one",
            "net.minecraft.world.entity.monster.Shulker", "max:3.0");

    /** isBaby(), per declaring class: the class holding the DATA_BABY_ID it reads, or "" for never a baby. */
    private static final Map<String, String> BABY_FLAG = Map.of(
            LivingEntity.class.getName(), "",
            "net.minecraft.world.entity.AgeableMob", "net.minecraft.world.entity.AgeableMob",
            "net.minecraft.world.entity.monster.zombie.Zombie", "net.minecraft.world.entity.monster.zombie.Zombie",
            "net.minecraft.world.entity.monster.piglin.Piglin", "net.minecraft.world.entity.monster.piglin.Piglin",
            "net.minecraft.world.entity.monster.Zoglin", "net.minecraft.world.entity.monster.Zoglin",
            "net.minecraft.world.entity.animal.frog.Frog", "",
            "net.minecraft.world.entity.animal.parrot.Parrot", "",
            "net.minecraft.world.entity.animal.nautilus.ZombieNautilus", "",
            "net.minecraft.world.entity.decoration.ArmorStand", ""); // small flag; its size is STATE anyway

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theShippedSizesAreTheGamesOwn() throws IOException, ReflectiveOperationException {
        List<String> lines = new ArrayList<>();
        Map<String, Set<String>> declaring = new TreeMap<>();
        for (Field field : EntityType.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != EntityType.class) {
                continue;
            }
            EntityType<?> type = (EntityType<?>) field.get(null);
            Class<?> entityClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            boolean living = LivingEntity.class.isAssignableFrom(entityClass);
            String getDimensions = declaring(entityClass, "getDimensions", declaring, Pose.class);
            String box = declaring(entityClass, "makeBoundingBox", declaring, Vec3.class);
            String rule;
            String age = "-";
            int babyIndex = -1;
            String alt = "-";
            String scale = "-";
            if (!living) {
                boolean custom = !getDimensions.equals(Entity.class.getName()) || !box.equals(Entity.class.getName())
                        || BlockAttachedEntity.class.isAssignableFrom(entityClass); // hanging: a box from its block and facing
                rule = custom ? "state" : "type";
            } else {
                String defaults = declaring(entityClass, "getDefaultDimensions", declaring, Pose.class);
                String ageScale = declaring(entityClass, "getAgeScale", declaring);
                String baby = declaring(entityClass, "isBaby", declaring);
                scale = known(SCALE_CAPS, declaring(entityClass, "sanitizeScale", declaring, float.class), "sanitizeScale");
                age = Float.toString(known(AGE_SCALES, ageScale, "getAgeScale"));
                String flagClass = known(BABY_FLAG, baby, "isBaby");
                if (!flagClass.isEmpty()) {
                    Field flag = Class.forName(flagClass).getDeclaredField("DATA_BABY_ID");
                    flag.setAccessible(true);
                    babyIndex = ((EntityDataAccessor<?>) flag.get(null)).id();
                }
                if (!box.equals(Entity.class.getName()) || STATE.contains(defaults)) {
                    rule = "state";
                } else if (defaults.equals(Avatar.class.getName())) {
                    rule = "player";
                } else if (BABY_SIZE.containsKey(defaults)) {
                    rule = "baby";
                    String where = BABY_SIZE.get(defaults);
                    alt = "-\t" + size(where.equals("*0.5") ? type.getDimensions().scale(0.5F) : constant(defaults, where));
                } else if (POSE_SIZE.containsKey(defaults)) {
                    rule = "pose";
                    String[] pose = POSE_SIZE.get(defaults);
                    alt = Pose.valueOf(pose[0]).name() + "\t" + size(constant(defaults, pose[1]));
                } else {
                    rule = "living";
                }
            }
            lines.add(String.join("\t", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), rule, size(type.getDimensions()), age,
                    Integer.toString(babyIndex), scale, alt));
        }
        Set<String> knownDefaults = new TreeSet<>(DEFAULT_SIZE);
        knownDefaults.addAll(BABY_SIZE.keySet());
        knownDefaults.addAll(POSE_SIZE.keySet());
        knownDefaults.addAll(STATE);
        assertEquals(knownDefaults, declaring.get("getDefaultDimensions"), "classes with their own getDefaultDimensions");
        assertEquals(new TreeSet<>(GET_DIMENSIONS), declaring.get("getDimensions"), "classes with their own getDimensions");
        assertEquals(new TreeSet<>(BOX), declaring.get("makeBoundingBox"), "classes with their own makeBoundingBox");
        assertEquals(new TreeSet<>(AGE_SCALES.keySet()), declaring.get("getAgeScale"), "classes with their own getAgeScale");
        assertEquals(new TreeSet<>(SCALE_CAPS.keySet()), declaring.get("sanitizeScale"), "classes with their own sanitizeScale");
        assertEquals(new TreeSet<>(BABY_FLAG.keySet()), declaring.get("isBaby"), "classes with their own isBaby");
        lines.sort(null);
        assertEquals(BuiltInRegistries.ENTITY_TYPE.size(), lines.size(), "one field per registered type");
        compare("entity-sizes.tsv", String.join("\n", lines) + "\n", EntitySizes.vanillaTable());
    }

    @Test
    void thePlayerPosesAreTheGamesOwn() throws IOException, ReflectiveOperationException {
        Field poses = Avatar.class.getDeclaredField("POSES");
        poses.setAccessible(true);
        Field sleeping = LivingEntity.class.getDeclaredField("SLEEPING_DIMENSIONS");
        sleeping.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Pose, EntityDimensions> table = (Map<Pose, EntityDimensions>) poses.get(null);
        assertSame(sleeping.get(null), table.get(Pose.SLEEPING), "every living entity sleeps in the players' sleeping size");
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Pose, EntityDimensions> entry : table.entrySet()) {
            lines.add(entry.getKey().name() + "\t" + size(entry.getValue()));
        }
        lines.sort(null);
        compare("player-poses.tsv", String.join("\n", lines) + "\n", EntitySizes.playerPosesTable());
    }

    /** The most derived class at or above {@code c} declaring the method; every such class is noted under the method's name. */
    private static String declaring(Class<?> c, String name, Map<String, Set<String>> declaring, Class<?>... parameters) {
        String found = null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method method = k.getDeclaredMethod(name, parameters);
                if (!method.isBridge() && !method.isSynthetic()) {
                    declaring.computeIfAbsent(name, n -> new TreeSet<>()).add(k.getName());
                    if (found == null) {
                        found = k.getName();
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // not declared here
            }
        }
        return found;
    }

    /** The value written out for {@code className}, failing with the class's name if a game update added one. */
    private static <V> V known(Map<String, V> table, String className, String method) {
        V value = table.get(className);
        if (value == null) {
            throw new AssertionError(className + " has its own " + method + "(): read it and add it to the table here");
        }
        return value;
    }

    private static EntityDimensions constant(String className, String fieldName) throws ReflectiveOperationException {
        Field field = Class.forName(className).getDeclaredField(fieldName);
        field.setAccessible(true);
        return (EntityDimensions) field.get(null);
    }

    /** width, height, eye height, fixed: floats as Float.toString (exact round trip). */
    private static String size(EntityDimensions d) {
        return d.width() + "\t" + d.height() + "\t" + d.eyeHeight() + "\t" + (d.fixed() ? 1 : 0);
    }

    private static void compare(String name, String table, String shipped) throws IOException {
        if (!table.equals(shipped)) {
            Path out = Path.of("build/" + name);
            Files.writeString(out, table, StandardCharsets.UTF_8);
            assertEquals(table, shipped, "the shipped " + name + " differs; the game's is in " + out.toAbsolutePath());
        }
    }
}
