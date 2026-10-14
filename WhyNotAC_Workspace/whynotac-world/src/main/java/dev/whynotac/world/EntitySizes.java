package dev.whynotac.world;

import dev.whynotac.protocol.packet.ClientboundPacket.EntityPose;
import dev.whynotac.protocol.packet.ClientboundPacket.SetEntityData;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The size the 1.21.11 client gives each entity, and so its bounding box around its position. Both tables
 * are the game's own: {@code EntitySizesDataTest} rebuilds them from the game's classes and checks which
 * class each type's size comes from; {@code EntitySizesGameTest} compares every rule with the game's own
 * {@code getDimensions} over the values its data can take.
 *
 * <p>The rules ({@code Entity.getDimensions}, {@code LivingEntity.getDimensions}):
 * <ul>
 *   <li>a non-living entity has its type's size in every pose, or one its data gives (interaction, area
 *       effect cloud);</li>
 *   <li>a sleeping living entity is 0.2 x 0.2 (fixed);</li>
 *   <li>otherwise the default size for the pose, scaled by the scale attribute through the type's cap.
 *       The default is the type's size scaled by the age scale (a baby's constant, else 1); some types
 *       have a constant baby size or a constant size in one pose; players have one size per pose; some
 *       types scale it or replace it by their data (slime and phantom size, puff state, salmon variant,
 *       armor stand flags, warden and sniffer digging);</li>
 *   <li>a fixed size ignores every scale.</li>
 * </ul>
 * Types whose size is not followed yet (a client-side animation, a box that is not the size around the
 * position, hanging entities) have rule {@link Rule#STATE}: no size is claimed.
 */
public final class EntitySizes {

    private static final String SIZES = "entity-sizes.tsv";
    private static final String PLAYER_POSES = "player-poses.tsv";

    /** {@code ArmorStand.CLIENT_FLAG_SMALL} and {@code CLIENT_FLAG_MARKER} (checked against the game by EntitySizesDataTest). */
    public static final int ARMOR_STAND_SMALL = 1;
    public static final int ARMOR_STAND_MARKER = 16;

    /** {@code EntityDimensions}: a fixed size ignores every scale. */
    public record Size(float width, float height, float eyeHeight, boolean fixed) {
        /** {@code EntityDimensions.scalable(w, h)} or {@code fixed(w, h)}: the eye at 0.85 of the height. */
        static Size of(float width, float height, boolean fixed) {
            return new Size(width, height, height * 0.85F, fixed);
        }

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
        /** Living with a constant baby size ({@code alt}). */
        BABY,
        /** Living with a constant size ({@code alt}, scaled by the age scale) in one pose. */
        POSE,
        /** Players: one size per pose. */
        PLAYER,
        /** {@code Slime}: the default scaled by its size (data 0, an int). */
        SLIME,
        /** {@code Phantom}: the default scaled by {@code 1 + 0.15 * size} (data 0, an int). */
        PHANTOM,
        /** {@code Pufferfish}: the default scaled by {@code factors[puff]} for puff 0 and 1, else the last factor. */
        PUFFERFISH,
        /** {@code Salmon}: the default scaled by its variant's factor (data 0, the variant id, clamped). */
        SALMON,
        /** {@code ArmorStand}: by its flags (data 0, a byte): marker {@code marker} (fixed), small {@code alt}, else the type's size. */
        ARMOR_STAND,
        /** {@code Warden}: digging or emerging, fixed at the default's width and 1 high. */
        WARDEN,
        /** {@code Sniffer}: in state {@code select} (data 0) its digging size {@code alt}, scaled by the age scale. */
        SNIFFER,
        /** {@code Interaction}: width and height from its data (two floats), the eye at 0.85. */
        INTERACTION,
        /** {@code AreaEffectCloud}: twice its radius (data 0, a float) wide, 0.5 high. */
        CLOUD,
        /** Depends on what this table does not follow. */
        STATE
    }

    /**
     * Whether the client's crosshair can pick an entity of the type ({@code isPickable()}, and not a
     * spectator: {@code GameRenderer.pick} through {@code ProjectileUtil.getEntityHitResult}).
     */
    public enum Pick {
        /** {@code Entity.isPickable}: never (and the ender dragon: only its parts). */
        NEVER,
        /** While present ({@code !isRemoved()}): living entities, boats, minecarts, tnt, falling blocks, and always-pickable types. */
        ALWAYS,
        /** {@code ArmorStand}: unless a marker (data 0, its flags). */
        ARMOR_STAND,
        /** Players: unless a spectator in the client's player list. */
        PLAYER,
        /** {@code Projectile}: by an entity type tag the server sends ({@code redirectable_projectile}), not followed: unknown. */
        TAG;

        static Pick parse(String cell) {
            return valueOf(cell.toUpperCase(Locale.ROOT));
        }
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
     * A synched-data value the size reads: its index, its serializer and the value the entity starts with
     * ({@code defineSynchedData}), as {@link SetEntityData.DataEntry} carries it (a float by its raw bits,
     * a byte unsigned, a sniffer state by its id).
     */
    public record DataField(int index, SetEntityData.Kind kind, int initial) {}

    /**
     * One type. {@code babyAgeScale}: {@code getAgeScale()} of a baby (living types); {@code babyIndex}: the
     * data index of the flag {@code isBaby()} reads, or -1 if the type is never a baby; {@code altPose} and
     * {@code alt}: the constant baby size (rule BABY, pose -1), the pose and its size (rule POSE), the small
     * size (ARMOR_STAND) or the digging size (SNIFFER); {@code marker}: the armor stand's marker size;
     * {@code data}: the values the size reads (at most two); {@code factors}: the pufferfish's and the
     * salmon's; {@code select}: the sniffer's digging state; {@code pick} and {@code pickRadius}: whether
     * the crosshair can pick it and how far its box is inflated for that ({@code getPickRadius()}, when pickable).
     */
    public record Entry(String key, Rule rule, Size size, float babyAgeScale, int babyIndex, ScaleCap scaleCap, int altPose, Size alt,
                        Size marker, List<DataField> data, List<Float> factors, int select, Pick pick, float pickRadius) {
        public Entry {
            data = List.copyOf(data);
            factors = List.copyOf(factors);
        }

        /** The initial value of data field {@code i}, or 0 when the type has none. */
        public int initial(int i) {
            return i < data.size() ? data.get(i).initial() : 0;
        }
    }

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

    /** {@code key rule width height eye fixed babyAgeScale babyIndex scaleCap [name=value...]}. */
    private static Entry parse(String[] cells) {
        Rule rule = Rule.valueOf(cells[1].toUpperCase(Locale.ROOT));
        float age = cells[6].equals("-") ? 1.0F : Float.parseFloat(cells[6]);
        int altPose = -1;
        Size alt = null;
        Size marker = null;
        List<DataField> data = new ArrayList<>();
        List<Float> factors = new ArrayList<>();
        int select = -1;
        Pick pick = Pick.NEVER;
        float pickRadius = 0.0F;
        for (int i = 9; i < cells.length; i++) {
            int eq = cells[i].indexOf('=');
            String name = cells[i].substring(0, eq);
            String[] value = cells[i].substring(eq + 1).split(",");
            switch (name) {
                case "alt" -> {
                    String[] parts = value[0].split(":");
                    altPose = parts[0].equals("-") ? -1 : EntityPose.valueOf(parts[0]).ordinal();
                    alt = size(parts, 1);
                }
                case "marker" -> marker = size(value[0].split(":"), 0);
                case "data" -> {
                    for (String field : value) {
                        String[] parts = field.split(":");
                        SetEntityData.Kind kind = SetEntityData.Kind.valueOf(parts[1]);
                        int initial = kind == SetEntityData.Kind.FLOAT ? Float.floatToRawIntBits(Float.parseFloat(parts[2])) : Integer.parseInt(parts[2]);
                        data.add(new DataField(Integer.parseInt(parts[0]), kind, initial));
                    }
                }
                case "factors" -> {
                    for (String factor : value) {
                        factors.add(Float.parseFloat(factor));
                    }
                }
                case "select" -> select = Integer.parseInt(value[0]);
                case "pick" -> pick = Pick.parse(value[0]);
                case "pickRadius" -> pickRadius = Float.parseFloat(value[0]);
                default -> throw new IllegalArgumentException("entity size cell " + cells[i]);
            }
        }
        return new Entry(cells[0], rule, size(cells, 2), age, Integer.parseInt(cells[7]), ScaleCap.parse(cells[8]), altPose, alt, marker, data,
                factors, select, pick, pickRadius);
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

    /** {@link #size(String, int, boolean, double, int, int)} with the type's data as the entity starts with it. */
    public Size size(String type, int pose, boolean baby, double scaleAttribute) {
        Entry e = entries.get(type);
        return e == null ? null : size(e, pose, baby, scaleAttribute, e.initial(0), e.initial(1));
    }

    /**
     * The size the client gives an entity of {@code type} in {@code pose} (an {@link EntityPose} ordinal),
     * {@code baby} as its baby flag says, with {@code scaleAttribute} the value of its scale attribute
     * (ignored for non-living types; NaN when it is not known) and {@code data0}, {@code data1} the values
     * of the type's {@link Entry#data} fields. Null when the type is unknown, its size depends on what this
     * table does not follow, or on a scale that is not known.
     */
    public Size size(String type, int pose, boolean baby, double scaleAttribute, int data0, int data1) {
        Entry e = entries.get(type);
        return e == null ? null : size(e, pose, baby, scaleAttribute, data0, data1);
    }

    private Size size(Entry e, int pose, boolean baby, double scaleAttribute, int data0, int data1) {
        switch (e.rule()) {
            case STATE -> {
                return null;
            }
            case TYPE -> {
                return e.size();
            }
            case INTERACTION -> {
                return Size.of(Float.intBitsToFloat(data0), Float.intBitsToFloat(data1), false);
            }
            case CLOUD -> {
                return Size.of(Float.intBitsToFloat(data0) * 2.0F, 0.5F, false);
            }
            default -> {
                // living
            }
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
            case SLIME -> e.size().scale(ageScale).scale((float) data0);
            case PHANTOM -> e.size().scale(ageScale).scale(1.0F + 0.15F * data0);
            case PUFFERFISH -> e.size().scale(ageScale).scale(e.factors().get(data0 == 0 ? 0 : data0 == 1 ? 1 : 2));
            case SALMON -> e.size().scale(ageScale).scale(e.factors().get(Math.max(0, Math.min(e.factors().size() - 1, data0))));
            case ARMOR_STAND -> (data0 & ARMOR_STAND_MARKER) != 0 ? e.marker() : (data0 & ARMOR_STAND_SMALL) != 0 ? e.alt() : e.size();
            case WARDEN -> {
                Size d = e.size().scale(ageScale);
                yield pose == EntityPose.DIGGING.ordinal() || pose == EntityPose.EMERGING.ordinal() ? Size.of(d.width(), 1.0F, true) : d;
            }
            case SNIFFER -> (data0 == e.select() ? e.alt() : e.size()).scale(ageScale);
            default -> e.size().scale(ageScale);
        };
        if (defaults.fixed() || e.scaleCap() == ScaleCap.ONE) {
            return defaults; // no scale changes it
        }
        return Double.isNaN(scaleAttribute) ? null : defaults.scale(scale(e.scaleCap(), scaleAttribute));
    }

    /**
     * Whether the client can pick a present entity of {@code type}: 1 yes, 0 no, -1 unknown. {@code data0}
     * is its first size data value (an armor stand's flags); {@code spectator} for players: 1 a spectator,
     * 0 not, -1 unknown.
     */
    public int pickable(String type, int data0, int spectator) {
        Entry e = entries.get(type);
        if (e == null) {
            return -1;
        }
        return switch (e.pick()) {
            case NEVER -> 0;
            case ALWAYS -> 1;
            case ARMOR_STAND -> (data0 & ARMOR_STAND_MARKER) != 0 ? 0 : 1;
            case PLAYER -> spectator < 0 ? -1 : 1 - spectator;
            case TAG -> -1;
        };
    }

    /** {@code LivingEntity.getScale()}: the attribute's value (already clamped by the attribute) as a float, through the cap. */
    static float scale(ScaleCap cap, double scaleAttribute) {
        return cap.apply((float) scaleAttribute);
    }

    /** {@code RangedAttribute.sanitizeValue} for the scale attribute: NaN is the minimum, the rest clamped. */
    public static double clampScale(double value) {
        return Double.isNaN(value) ? MIN_SCALE : Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
    }

    /** The shipped type table: {@code key rule width height eye fixed babyAgeScale babyIndex scaleCap [name=value...]}. */
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
