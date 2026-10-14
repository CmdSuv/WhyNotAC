package dev.whynotac.world;

import dev.whynotac.protocol.packet.ClientboundPacket.EntityPose;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The size the 1.21.11 client gives each entity, and so its bounding box around its position. Both tables
 * are the game's own: {@code EntitySizesDataTest} rebuilds them from the game's classes and checks which
 * class each type's size comes from.
 *
 * <p>The rules ({@code Entity.getDimensions}, {@code LivingEntity.getDimensions}):
 * <ul>
 *   <li>a non-living entity has its type's size in every pose;</li>
 *   <li>a sleeping living entity is 0.2 x 0.2 (fixed);</li>
 *   <li>otherwise the default size for the pose, scaled by the scale attribute through the type's cap.
 *       The default is the type's size scaled by the age scale (a baby's constant, else 1); some types
 *       have a constant baby size or a constant size in one pose; players have one size per pose;</li>
 *   <li>a fixed size ignores every scale.</li>
 * </ul>
 * Types whose size depends on other data (slime and phantom size, puff state, armor stand flags, digging,
 * an animation, data-sized and hanging entities) have rule {@link Rule#STATE}: no size is claimed.
 */
public final class EntitySizes {

    private static final String SIZES = "entity-sizes.tsv";
    private static final String PLAYER_POSES = "player-poses.tsv";

    /** {@code EntityDimensions}: a fixed size ignores every scale. */
    public record Size(float width, float height, float eyeHeight, boolean fixed) {
        /** {@code EntityDimensions.scale(f)}: float products, unchanged when fixed or {@code f == 1}. */
        public Size scale(float factor) {
            return !fixed && factor != 1.0F ? new Size(width * factor, height * factor, eyeHeight * factor, false) : this;
        }

        /** {@code EntityDimensions.makeBoundingBox}: half the width (a float) each side, the height upwards. */
        public Box box(double x, double y, double z) {
            float half = width / 2.0F;
            return new Box(x - half, y, z - half, x + half, y + height, z + half);
        }
    }

    /** An axis-aligned box, as {@code AABB}. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

    /** Where a type's size comes from. */
    public enum Rule {
        /** Non-living: the type's size. */
        TYPE,
        /** Living: the type's size scaled by the age scale. */
        LIVING,
        /** Living with a constant baby size. */
        BABY,
        /** Living with a constant size (scaled by the age scale) in one pose. */
        POSE,
        /** Players: one size per pose. */
        PLAYER,
        /** Depends on data this table does not follow. */
        STATE
    }

    /** {@code LivingEntity.sanitizeScale}. */
    public enum ScaleCap {
        NONE, ANY, MAX_1, MAX_3, ONE;

        float apply(float scale) {
            return switch (this) {
                case NONE, ONE -> 1.0F;
                case ANY -> scale;
                case MAX_1 -> Math.min(scale, 1.0F);
                case MAX_3 -> Math.min(scale, 3.0F);
            };
        }

        static ScaleCap parse(String cell) {
            return switch (cell) {
                case "-" -> NONE;
                case "any" -> ANY;
                case "max:1.0" -> MAX_1;
                case "max:3.0" -> MAX_3;
                case "one" -> ONE;
                default -> throw new IllegalArgumentException("scale cap " + cell);
            };
        }
    }

    /**
     * One type. {@code babyAgeScale}: {@code getAgeScale()} of a baby (living types); {@code babyIndex}: the
     * data index of the flag {@code isBaby()} reads, or -1 if the type is never a baby; {@code altPose} and
     * {@code alt}: the constant baby size (rule BABY, pose -1) or the pose and its size (rule POSE).
     */
    public record Entry(String key, Rule rule, Size size, float babyAgeScale, int babyIndex, ScaleCap scaleCap, int altPose, Size alt) {}

    /** The scale attribute's range (RangedAttribute). */
    static final double MIN_SCALE = 0.0625;
    static final double MAX_SCALE = 16.0;

    private static volatile EntitySizes vanilla;

    private final Map<String, Entry> entries;
    private final Size[] playerPoses;

    private EntitySizes(Map<String, Entry> entries, Size[] playerPoses) {
        this.entries = entries;
        this.playerPoses = playerPoses;
    }

    /** The 1.21.11 tables. */
    public static EntitySizes vanilla() {
        EntitySizes sizes = vanilla;
        if (sizes == null) {
            Map<String, Entry> entries = new HashMap<>();
            for (String line : vanillaTable().split("\n")) {
                if (!line.isEmpty()) {
                    Entry entry = parse(line.split("\t"));
                    entries.put(entry.key(), entry);
                }
            }
            Size[] poses = new Size[EntityPose.values().length];
            for (String line : playerPosesTable().split("\n")) {
                if (!line.isEmpty()) {
                    String[] cells = line.split("\t");
                    poses[EntityPose.valueOf(cells[0]).ordinal()] = size(cells, 1);
                }
            }
            vanilla = sizes = new EntitySizes(Map.copyOf(entries), poses);
        }
        return sizes;
    }

    private static Entry parse(String[] cells) {
        Rule rule = Rule.valueOf(cells[1].toUpperCase(java.util.Locale.ROOT));
        float age = cells[6].equals("-") ? 1.0F : Float.parseFloat(cells[6]);
        int altPose = -1;
        Size alt = null;
        if (cells.length > 10) {
            altPose = cells[9].equals("-") ? -1 : EntityPose.valueOf(cells[9]).ordinal();
            alt = size(cells, 10);
        }
        return new Entry(cells[0], rule, size(cells, 2), age, Integer.parseInt(cells[7]), ScaleCap.parse(cells[8]), altPose, alt);
    }

    private static Size size(String[] cells, int from) {
        return new Size(Float.parseFloat(cells[from]), Float.parseFloat(cells[from + 1]), Float.parseFloat(cells[from + 2]),
                cells[from + 3].equals("1"));
    }

    /** The type's entry, or null for a type this table does not know (a newer protocol). */
    public Entry entry(String type) {
        return entries.get(type);
    }

    public int size() {
        return entries.size();
    }

    /**
     * The size the client gives an entity of {@code type} in {@code pose} (an {@link EntityPose} ordinal),
     * {@code baby} as its baby flag says, with {@code scaleAttribute} the value of its scale attribute
     * (ignored for non-living types; NaN when it is not known). Null when the type is unknown, its size
     * depends on other data, or it depends on a scale that is not known.
     */
    public Size size(String type, int pose, boolean baby, double scaleAttribute) {
        Entry e = entries.get(type);
        if (e == null || e.rule() == Rule.STATE) {
            return null;
        }
        if (e.rule() == Rule.TYPE) {
            return e.size();
        }
        if (pose == EntityPose.SLEEPING.ordinal()) {
            return playerPoses[EntityPose.SLEEPING.ordinal()]; // LivingEntity.SLEEPING_DIMENSIONS, fixed
        }
        boolean isBaby = baby && e.babyIndex() >= 0;
        float ageScale = isBaby ? e.babyAgeScale() : 1.0F;
        Size defaults = switch (e.rule()) {
            case PLAYER -> {
                Size posed = pose >= 0 && pose < playerPoses.length ? playerPoses[pose] : null;
                yield posed != null ? posed : playerPoses[EntityPose.STANDING.ordinal()];
            }
            case BABY -> isBaby ? e.alt() : e.size().scale(ageScale);
            case POSE -> (pose == e.altPose() ? e.alt() : e.size()).scale(ageScale);
            default -> e.size().scale(ageScale);
        };
        if (defaults.fixed() || e.scaleCap() == ScaleCap.ONE) {
            return defaults; // no scale changes it
        }
        return Double.isNaN(scaleAttribute) ? null : defaults.scale(scale(e.scaleCap(), scaleAttribute));
    }

    /** {@code LivingEntity.getScale()}: the attribute's value (already clamped by the attribute) as a float, through the cap. */
    static float scale(ScaleCap cap, double scaleAttribute) {
        return cap.apply((float) scaleAttribute);
    }

    /** {@code RangedAttribute.sanitizeValue} for the scale attribute: NaN is the minimum, the rest clamped. */
    public static double clampScale(double value) {
        return Double.isNaN(value) ? MIN_SCALE : Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    /** The shipped type table: {@code key rule width height eye fixed babyAgeScale babyIndex scaleCap [altPose width height eye fixed]}. */
    public static String vanillaTable() {
        return resource(SIZES);
    }

    /** The shipped player pose table: {@code pose width height eye fixed}. */
    public static String playerPosesTable() {
        return resource(PLAYER_POSES);
    }

    private static String resource(String name) {
        try (InputStream in = EntitySizes.class.getResourceAsStream(name)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
