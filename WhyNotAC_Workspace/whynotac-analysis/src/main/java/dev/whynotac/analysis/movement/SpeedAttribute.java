package dev.whynotac.analysis.movement;

import dev.whynotac.physics.OnFoot;
import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.ArrayList;
import java.util.List;

/**
 * The movement speed attribute as the client holds it, without the sprinting modifier (which the client
 * adds and removes itself, {@code LivingEntity.setSprinting}): the base and the other modifiers, at most one
 * per operation so the client's sum is computed in the client's own order ({@code AttributeInstance.calculateValue}).
 */
public record SpeedAttribute(double base, List<ClientboundPacket.UpdateAttributes.Modifier> modifiers) implements OnFoot.Speed {

    static final String SPRINTING = "minecraft:sprinting";
    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING}: {@code 0.3F}, multiplying the total. */
    static final ClientboundPacket.UpdateAttributes.Modifier SPRINT_MODIFIER = new ClientboundPacket.UpdateAttributes.Modifier(SPRINTING, 0.3F,
            ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL);
    /** {@code Player.createAttributes}: base {@code 0.1F}, no modifier. */
    static final SpeedAttribute DEFAULT = new SpeedAttribute(0.1F, List.of());
    /** {@code RangedAttribute("movement_speed", 0.7, 0.0, 1024.0)}. */
    static final double MIN = 0.0;
    static final double MAX = 1024.0;

    public SpeedAttribute {
        modifiers = List.copyOf(modifiers);
    }

    /**
     * The attribute from an update, and whether that update holds the sprinting modifier; null when some
     * operation has several modifiers (counting sprinting's) or one this build does not know.
     */
    static SpeedAttribute of(ClientboundPacket.UpdateAttributes.Attribute attribute) {
        List<ClientboundPacket.UpdateAttributes.Modifier> others = new ArrayList<>();
        for (ClientboundPacket.UpdateAttributes.Modifier m : attribute.modifiers()) {
            if (!m.id().equals(SPRINTING)) {
                others.add(m);
            }
        }
        SpeedAttribute speed = new SpeedAttribute(attribute.base(), others);
        return speed.exact(true) && speed.exact(false) ? speed : null;
    }

    /** Whether an update holds the sprinting modifier. */
    static boolean holdsSprinting(ClientboundPacket.UpdateAttributes.Attribute attribute) {
        for (ClientboundPacket.UpdateAttributes.Modifier m : attribute.modifiers()) {
            if (m.id().equals(SPRINTING)) {
                return true;
            }
        }
        return false;
    }

    private boolean exact(boolean sprint) {
        int[] count = new int[3];
        for (ClientboundPacket.UpdateAttributes.Modifier m : with(sprint)) {
            if (m.operation() == ClientboundPacket.UpdateAttributes.Operation.UNKNOWN || ++count[m.operation().ordinal()] > 1) {
                return false;
            }
        }
        return true;
    }

    private List<ClientboundPacket.UpdateAttributes.Modifier> with(boolean sprint) {
        if (!sprint) {
            return modifiers;
        }
        List<ClientboundPacket.UpdateAttributes.Modifier> all = new ArrayList<>(modifiers);
        all.add(SPRINT_MODIFIER);
        return all;
    }

    /** {@code AttributeInstance.calculateValue}, then {@code RangedAttribute.sanitizeValue}. */
    @Override
    public double value(boolean sprintModifier) {
        List<ClientboundPacket.UpdateAttributes.Modifier> all = with(sprintModifier);
        double value = base;
        for (ClientboundPacket.UpdateAttributes.Modifier m : all) {
            if (m.operation() == ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE) {
                value += m.amount();
            }
        }
        double total = value;
        for (ClientboundPacket.UpdateAttributes.Modifier m : all) {
            if (m.operation() == ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_BASE) {
                total += value * m.amount();
            }
        }
        for (ClientboundPacket.UpdateAttributes.Modifier m : all) {
            if (m.operation() == ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL) {
                total *= 1.0 + m.amount();
            }
        }
        return Double.isNaN(total) ? MIN : Math.clamp(total, MIN, MAX);
    }
}
