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
import net.minecraft.network.syncher.EntityDataSerializers;
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
 *       {@link #POSE_SIZE} a constant size in one pose; classes in {@link #DATA_SIZE} and
 *       {@link #DATA_DIMENSIONS} a size from their data (the indices, initial values and constants are
 *       read here, the formulas checked by {@code EntitySizesGameTest}); classes making a box that is not
 *       their size around the position have no rule.</li>
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

    /** Classes declaring getDefaultDimensions(Pose) from their data: the rule EntitySizes applies (its tail is read below). */
    private static final Map<String, String> DATA_SIZE = Map.of(
            "net.minecraft.world.entity.decoration.ArmorStand", "armor_stand", // marker and small flags
            "net.minecraft.world.entity.animal.sniffer.Sniffer", "sniffer",     // digging state
            "net.minecraft.world.entity.animal.fish.Salmon", "salmon",          // variant
            "net.minecraft.world.entity.animal.fish.Pufferfish", "pufferfish",  // puff state
            "net.minecraft.world.entity.monster.Phantom", "phantom",            // size
            "net.minecraft.world.entity.monster.Slime", "slime",                // size
            "net.minecraft.world.entity.monster.warden.Warden", "warden",       // digging or emerging pose
            "net.minecraft.world.entity.animal.polarbear.PolarBear", "polar_bear"); // the client's standing animation

    /** Non-living classes declaring getDimensions(Pose) from their data: the rule EntitySizes applies. */
    private static final Map<String, String> DATA_DIMENSIONS = Map.of(
            "net.minecraft.world.entity.AreaEffectCloud", "cloud",
            "net.minecraft.world.entity.Interaction", "interaction");

    /** Classes declaring makeBoundingBox(Vec3) as their size around the position ({@code getDimensions().makeBoundingBox(pos)}). */
    private static final Set<String> BOX_OF_SIZE = Set.of("net.minecraft.world.entity.Interaction");

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
            "net.minecraft.world.entity.decoration.ArmorStand", ""); // the small flag, which its size rule reads itself

    /** isPickable(), per declaring class, as EntitySizes.Pick. */
    private static final Map<String, String> PICK = Map.ofEntries(
            Map.entry(Entity.class.getName(), "never"),
            Map.entry(LivingEntity.class.getName(), "always"), // !isRemoved()
            Map.entry("net.minecraft.world.entity.decoration.ArmorStand", "armor_stand"),
            Map.entry("net.minecraft.world.entity.boss.enderdragon.EnderDragon", "never"), // its parts are picked
            Map.entry("net.minecraft.world.entity.projectile.Projectile", "tag"),
            Map.entry("net.minecraft.world.entity.projectile.arrow.AbstractArrow", "tag"), // the tag, and not in the ground
            Map.entry("net.minecraft.world.entity.projectile.ShulkerBullet", "always"),
            Map.entry("net.minecraft.world.entity.item.FallingBlockEntity", "always"),
            Map.entry("net.minecraft.world.entity.item.PrimedTnt", "always"),
            Map.entry("net.minecraft.world.entity.Interaction", "always"),
            Map.entry("net.minecraft.world.entity.decoration.BlockAttachedEntity", "always"),
            Map.entry("net.minecraft.world.entity.boss.enderdragon.EndCrystal", "always"),
            Map.entry("net.minecraft.world.entity.vehicle.boat.AbstractBoat", "always"),
            Map.entry("net.minecraft.world.entity.vehicle.minecart.AbstractMinecart", "always"));

    /** getPickRadius(), per declaring class: a constant, or "1 if pickable" (Projectile). */
    private static final Map<String, String> PICK_RADIUS = Map.of(
            Entity.class.getName(), "0",
            "net.minecraft.world.entity.projectile.Projectile", "pickable:1.0");

    /** isSpectator(), per declaring class: players read their game mode from the client's player list. */
    private static final Map<String, Boolean> SPECTATOR = Map.of(
            Entity.class.getName(), false,
            "net.minecraft.world.entity.player.Player", true);

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
            String alt = "";
            String scale = "-";
            Data allocated = new Data(type, entityClass);
            if (!living) {
                boolean ownBox = !box.equals(Entity.class.getName()) && !BOX_OF_SIZE.contains(box);
                if (DATA_DIMENSIONS.containsKey(getDimensions) && !ownBox) {
                    rule = DATA_DIMENSIONS.get(getDimensions);
                    alt = rule.equals("cloud") ? data(allocated, getDimensions, "DATA_RADIUS")
                            : data(allocated, getDimensions, "DATA_WIDTH_ID", "DATA_HEIGHT_ID");
                } else {
                    boolean custom = !getDimensions.equals(Entity.class.getName()) || ownBox
                            || BlockAttachedEntity.class.isAssignableFrom(entityClass); // hanging: a box from its block and facing
                    rule = custom ? "state" : "type";
                }
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
                if (!box.equals(Entity.class.getName())) {
                    rule = "state";
                } else if (defaults.equals(Avatar.class.getName())) {
                    rule = "player";
                } else if (BABY_SIZE.containsKey(defaults)) {
                    rule = "baby";
                    String where = BABY_SIZE.get(defaults);
                    alt = "alt=-:" + cell(where.equals("*0.5") ? type.getDimensions().scale(0.5F) : constant(defaults, where));
                } else if (POSE_SIZE.containsKey(defaults)) {
                    rule = "pose";
                    String[] pose = POSE_SIZE.get(defaults);
                    alt = "alt=" + Pose.valueOf(pose[0]).name() + ":" + cell(constant(defaults, pose[1]));
                } else if (DATA_SIZE.containsKey(defaults)) {
                    rule = DATA_SIZE.get(defaults);
                    alt = dataTail(rule, allocated, defaults);
                } else {
                    rule = "living";
                }
            }
            String line = String.join("\t", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), rule, size(type.getDimensions()), age,
                    Integer.toString(babyIndex), scale);
            if (!alt.isEmpty()) {
                line += "\t" + alt;
            }
            String pick = known(PICK, declaring(entityClass, "isPickable", declaring), "isPickable");
            if (known(SPECTATOR, declaring(entityClass, "isSpectator", declaring), "isSpectator")) {
                assertEquals("always", pick, "a player is pickable while present");
                pick = "player";
            }
            String radius = known(PICK_RADIUS, declaring(entityClass, "getPickRadius", declaring), "getPickRadius");
            if (!pick.equals("never")) {
                line += "\tpick=" + pick;
                if (radius.startsWith("pickable:")) {
                    line += "\tpickRadius=" + radius.substring("pickable:".length());
                }
            }
            lines.add(line);
        }
        Set<String> knownDefaults = new TreeSet<>(DEFAULT_SIZE);
        knownDefaults.addAll(BABY_SIZE.keySet());
        knownDefaults.addAll(POSE_SIZE.keySet());
        knownDefaults.addAll(DATA_SIZE.keySet());
        assertEquals(knownDefaults, declaring.get("getDefaultDimensions"), "classes with their own getDefaultDimensions");
        assertEquals(new TreeSet<>(GET_DIMENSIONS), declaring.get("getDimensions"), "classes with their own getDimensions");
        assertEquals(new TreeSet<>(BOX), declaring.get("makeBoundingBox"), "classes with their own makeBoundingBox");
        assertEquals(new TreeSet<>(AGE_SCALES.keySet()), declaring.get("getAgeScale"), "classes with their own getAgeScale");
        assertEquals(new TreeSet<>(SCALE_CAPS.keySet()), declaring.get("sanitizeScale"), "classes with their own sanitizeScale");
        assertEquals(new TreeSet<>(BABY_FLAG.keySet()), declaring.get("isBaby"), "classes with their own isBaby");
        assertEquals(new TreeSet<>(PICK.keySet()), declaring.get("isPickable"), "classes with their own isPickable");
        assertEquals(new TreeSet<>(PICK_RADIUS.keySet()), declaring.get("getPickRadius"), "classes with their own getPickRadius");
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

    /** The tail of a rule sized by data: the data fields it reads and the constants it applies, read from the game. */
    /** The type, allocated when a rule reads its data. */
    private record Data(EntityType<?> type, Class<?> entityClass) {
        GameEntities.Allocated allocate() throws ReflectiveOperationException {
            return GameEntities.allocate(type, entityClass);
        }
    }

    private static String dataTail(String rule, Data allocated, String owner) throws ReflectiveOperationException {
        Class<?> c = Class.forName(owner);
        return switch (rule) {
            case "slime", "phantom" -> data(allocated, owner, "ID_SIZE");
            case "pufferfish" -> {
                Method scale = c.getDeclaredMethod("getScale", int.class);
                scale.setAccessible(true);
                yield data(allocated, owner, "PUFF_STATE") + "\tfactors=" + scale.invoke(null, 0) + "," + scale.invoke(null, 1) + "," + scale.invoke(null, 2);
            }
            case "salmon" -> {
                // Variant.BY_ID is ByIdMap.continuous over the ids: they must be the ordinals.
                Class<?> variant = Class.forName(owner + "$Variant");
                Field id = variant.getDeclaredField("id");
                Field factor = variant.getDeclaredField("boundingBoxScale");
                id.setAccessible(true);
                factor.setAccessible(true);
                List<String> factors = new ArrayList<>();
                for (Object v : variant.getEnumConstants()) {
                    assertEquals(((Enum<?>) v).ordinal(), id.getInt(v), "salmon variant ids are their ordinals");
                    factors.add(Float.toString(factor.getFloat(v)));
                }
                yield data(allocated, owner, "DATA_TYPE") + "\tfactors=" + String.join(",", factors);
            }
            case "armor_stand" -> {
                assertEquals(EntitySizes.ARMOR_STAND_SMALL, c.getField("CLIENT_FLAG_SMALL").getInt(null), "the armor stand's small flag");
                assertEquals(EntitySizes.ARMOR_STAND_MARKER, c.getField("CLIENT_FLAG_MARKER").getInt(null), "the armor stand's marker flag");
                yield data(allocated, owner, "DATA_CLIENT_FLAGS") + "\talt=-:" + cell(constant(owner, "BABY_DIMENSIONS"))
                        + "\tmarker=" + cell(constant(owner, "MARKER_DIMENSIONS"));
            }
            case "sniffer" -> {
                int select = net.minecraft.world.entity.animal.sniffer.Sniffer.State.DIGGING.id();
                yield data(allocated, owner, "DATA_STATE") + "\talt=-:" + cell(constant(owner, "DIGGING_DIMENSIONS")) + "\tselect=" + select;
            }
            case "warden" -> "";
            case "polar_bear" -> {
                Field ticks = c.getDeclaredField("STAND_ANIMATION_TICKS");
                ticks.setAccessible(true);
                assertEquals((float) EntitySizes.POLAR_BEAR_STAND_TICKS, ticks.getFloat(null), "the polar bear's standing animation");
                yield data(allocated, owner, "DATA_STANDING_ID");
            }
            default -> throw new AssertionError(rule);
        };
    }

    /** {@code data=index:KIND:initial,...}: the fields of {@code owner}, with the values the entity starts with. */
    private static String data(Data type, String owner, String... fields) throws ReflectiveOperationException {
        GameEntities.Allocated allocated = type.allocate();
        assertEquals(true, allocated.initial(), owner + " defines its data without a level");
        List<String> cells = new ArrayList<>();
        for (String name : fields) {
            EntityDataAccessor<?> accessor = GameEntities.accessor(Class.forName(owner), name);
            Object value = allocated.entity().getEntityData().get(accessor);
            String kind;
            String initial;
            if (accessor.serializer() == EntityDataSerializers.INT) {
                kind = "INT";
                initial = value.toString();
            } else if (accessor.serializer() == EntityDataSerializers.BYTE) {
                kind = "BYTE";
                initial = Integer.toString((Byte) value & 0xFF);
            } else if (accessor.serializer() == EntityDataSerializers.FLOAT) {
                kind = "FLOAT";
                initial = value.toString();
            } else if (accessor.serializer() == EntityDataSerializers.BOOLEAN) {
                kind = "BOOLEAN";
                initial = (Boolean) value ? "1" : "0";
            } else if (accessor.serializer() == EntityDataSerializers.SNIFFER_STATE) {
                kind = "SNIFFER_STATE";
                initial = Integer.toString(((net.minecraft.world.entity.animal.sniffer.Sniffer.State) value).id());
            } else {
                throw new AssertionError(owner + "." + name + ": a serializer the table does not read");
            }
            cells.add(accessor.id() + ":" + kind + ":" + initial);
        }
        return "data=" + String.join(",", cells);
    }

    /** {@link #size} joined by colons, for a name=value cell. */
    private static String cell(EntityDimensions d) {
        return size(d).replace('\t', ':');
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
