package dev.whynotac.world;

import dev.whynotac.protocol.packet.ClientboundPacket;

/**
 * The value the client computes for an attribute from {@code ClientboundUpdateAttributesPacket}'s base and
 * modifiers ({@code AttributeInstance.calculateValue}): the base plus every ADD_VALUE modifier; that plus
 * the sum times every ADD_MULTIPLIED_BASE amount; that times one plus every ADD_MULTIPLIED_TOTAL amount;
 * then the attribute's range ({@code RangedAttribute.sanitizeValue}, NaN to its minimum).
 *
 * <p>The client holds each operation's modifiers in a hash map and sums them in its order. With at most one
 * modifier per operation the arithmetic is the client's own, in its order, and the value is exact. With
 * more, the order changes the last bits: the result is an interval wide enough for any order.
 */
public final class AttributeValues {

    /** Relative slack for a sum in an unknown order (far above any double rounding of a few terms). */
    private static final double SLACK = 1e-9;

    private AttributeValues() {}

    /** {@code [low, high]} of the client's value for this attribute, clamped to {@code [min, max]}. */
    public static double[] range(ClientboundPacket.UpdateAttributes.Attribute attribute, double min, double max) {
        double add = 0;
        double multiplyBase = 0;
        double multiplyTotal = 1;
        double magnitude = Math.abs(attribute.base());
        int adds = 0;
        int bases = 0;
        int totals = 0;
        for (ClientboundPacket.UpdateAttributes.Modifier modifier : attribute.modifiers()) {
            switch (modifier.operation()) {
                case ADD_VALUE -> {
                    add += modifier.amount();
                    adds++;
                }
                case ADD_MULTIPLIED_BASE -> {
                    multiplyBase += modifier.amount();
                    bases++;
                }
                case ADD_MULTIPLIED_TOTAL -> {
                    multiplyTotal *= 1.0 + modifier.amount();
                    totals++;
                }
                default -> {
                    return new double[] {min, max}; // an operation this build does not know
                }
            }
            magnitude += Math.abs(modifier.amount());
        }
        double value;
        if (adds <= 1 && bases <= 1 && totals <= 1) {
            // the client's own arithmetic, in its order
            double base = attribute.base();
            base += add;
            value = base;
            if (bases == 1) {
                value += base * multiplyBase;
            }
            if (totals == 1) {
                value *= multiplyTotal;
            }
            double v = Double.isNaN(value) ? min : Math.clamp(value, min, max);
            return new double[] {v, v};
        }
        double base = attribute.base() + add;
        value = (base + base * multiplyBase) * multiplyTotal;
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return new double[] {min, max};
        }
        double slack = SLACK * Math.max(1.0, Math.abs(value) + magnitude * Math.max(1.0, Math.abs(multiplyTotal)) * (1.0 + Math.abs(multiplyBase)));
        return new double[] {Math.clamp(value - slack, min, max), Math.clamp(value + slack, min, max)};
    }
}
