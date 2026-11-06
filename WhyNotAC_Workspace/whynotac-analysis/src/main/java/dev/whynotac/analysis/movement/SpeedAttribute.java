package dev.whynotac.analysis.movement;

import dev.whynotac.physics.OnFoot;
import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.Arrays;

/**
 * The movement speed attribute as the client holds it ({@code AttributeInstance}): the base and, per operation,
 * the modifiers in the client's own hash table ({@link ModifierTable}), the sprinting modifier among them while
 * it is there. The value is summed in the table's order, as {@code calculateValue} does, so it is the client's
 * to the bit whatever the modifiers. Immutable.
 */
public final class SpeedAttribute implements OnFoot.Speed {

    static final String SPRINTING = "minecraft:sprinting";
    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING}: {@code 0.3F}, multiplying the total. */
    static final ClientboundPacket.UpdateAttributes.Modifier SPRINT_MODIFIER = new ClientboundPacket.UpdateAttributes.Modifier(SPRINTING, 0.3F,
            ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL);
    /** {@code Player.createAttributes}: base {@code 0.1F}, no modifier. */
    static final SpeedAttribute DEFAULT = new SpeedAttribute(0.1F, new ModifierTable[] {ModifierTable.EMPTY, ModifierTable.EMPTY, ModifierTable.EMPTY});
    /** {@code RangedAttribute("movement_speed", 0.7, 0.0, 1024.0)}. */
    static final double MIN = 0.0;
    static final double MAX = 1024.0;
    private static final int TOTAL = ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL.ordinal();

    private final double base;
    /** By {@code Operation} ordinal: add, multiply the base, multiply the total. */
    private final ModifierTable[] tables;

    private SpeedAttribute(double base, ModifierTable[] tables) {
        this.base = base;
        this.tables = tables;
    }

    /**
     * {@code handleUpdateAttributes}: the base set, every modifier removed (the tables empty), then each of the
     * update's added in its order. Null when an operation is not known or a table would grow past its size.
     */
    static SpeedAttribute of(ClientboundPacket.UpdateAttributes.Attribute attribute) {
        ModifierTable[] next = DEFAULT.tables.clone();
        for (ClientboundPacket.UpdateAttributes.Modifier m : attribute.modifiers()) {
            if (m.operation() == ClientboundPacket.UpdateAttributes.Operation.UNKNOWN) {
                return null;
            }
            ModifierTable t = next[m.operation().ordinal()].put(m);
            if (t == null) {
                return null;
            }
            next[m.operation().ordinal()] = t;
        }
        return new SpeedAttribute(attribute.base(), next);
    }

    /** Whether the sprinting modifier is in the attribute. */
    boolean holdsSprinting() {
        return tables[TOTAL].contains(SPRINTING);
    }

    /**
     * Whether the table with and without the sprinting modifier is the same whatever order the modifier came and
     * went in (no other modifier shares its probe chain), this one being that form: then a state whose history of
     * {@code setSprinting} calls is not known can still hold it exactly.
     */
    boolean sprintLayoutCanonical() {
        SpeedAttribute on = setSprinting(true);
        SpeedAttribute off = setSprinting(false);
        if (on == null || off == null) {
            return false;
        }
        SpeedAttribute onAgain = off.setSprinting(true);
        SpeedAttribute offAgain = on.setSprinting(false);
        return on.equals(onAgain) && off.equals(offAgain) && equals(holdsSprinting() ? on : off);
    }

    /** {@code AttributeMap.assignBaseValues}: the base stays, no modifier. */
    SpeedAttribute baseOnly() {
        return new SpeedAttribute(base, DEFAULT.tables);
    }

    /** {@code AttributeInstance.replaceFrom} (a respawn keeping the modifiers): each table refilled in the old one's order. */
    SpeedAttribute copied() {
        ModifierTable[] next = DEFAULT.tables.clone();
        for (int op = 0; op < next.length; op++) {
            for (ClientboundPacket.UpdateAttributes.Modifier m : tables[op].inOrder()) {
                next[op] = next[op].put(m);
            }
        }
        return new SpeedAttribute(base, next);
    }

    /** {@code LivingEntity.setSprinting}: the modifier removed, then added back when sprinting. Null if the table overflows. */
    @Override
    public SpeedAttribute setSprinting(boolean sprinting) {
        ModifierTable total = tables[TOTAL].remove(SPRINTING);
        if (sprinting) {
            total = total.put(SPRINT_MODIFIER);
            if (total == null) {
                return null;
            }
        }
        ModifierTable[] next = tables.clone();
        next[TOTAL] = total;
        return new SpeedAttribute(base, next);
    }

    /**
     * {@code AttributeInstance.calculateValue}, then {@code RangedAttribute.sanitizeValue}, with the sprinting modifier
     * there or not ({@code sprintModifier}; the attribute follows the player's own record of it).
     */
    @Override
    public double value(boolean sprintModifier) {
        SpeedAttribute a = sprintModifier == holdsSprinting() ? this : setSprinting(sprintModifier);
        if (a == null) {
            return Double.NaN;
        }
        double value = a.base;
        for (ClientboundPacket.UpdateAttributes.Modifier m : a.tables[0].inOrder()) {
            value += m.amount();
        }
        double total = value;
        for (ClientboundPacket.UpdateAttributes.Modifier m : a.tables[1].inOrder()) {
            total += value * m.amount();
        }
        for (ClientboundPacket.UpdateAttributes.Modifier m : a.tables[2].inOrder()) {
            total *= 1.0 + m.amount();
        }
        return Double.isNaN(total) ? MIN : Math.clamp(total, MIN, MAX);
    }

    double base() {
        return base;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SpeedAttribute s && Double.compare(s.base, base) == 0 && Arrays.equals(s.tables, tables);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(base) * 31 + Arrays.hashCode(tables);
    }

    @Override
    public String toString() {
        return "SpeedAttribute[base=" + base + ", modifiers=" + Arrays.toString(tables) + "]";
    }
}
