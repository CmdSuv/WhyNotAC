package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ClientboundPacket.UpdateAttributes.Modifier;
import dev.whynotac.protocol.packet.ClientboundPacket.UpdateAttributes.Operation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AttributeValuesTest {

    private static ClientboundPacket.UpdateAttributes.Attribute attribute(double base, Modifier... modifiers) {
        return new ClientboundPacket.UpdateAttributes.Attribute("minecraft:entity_interaction_range", base, List.of(modifiers));
    }

    @Test
    void theCreativeReachIsExact() {
        // ServerPlayer.updatePlayerAttributes: +2 (ADD_VALUE) in creative
        assertArrayEquals(new double[] {5.0, 5.0}, AttributeValues.range(attribute(3.0, new Modifier("minecraft:creative_mode_entity_range", 2.0,
                Operation.ADD_VALUE)), 0, 64));
        assertArrayEquals(new double[] {3.0, 3.0}, AttributeValues.range(attribute(3.0), 0, 64));
        assertArrayEquals(new double[] {64.0, 64.0}, AttributeValues.range(attribute(3.0, new Modifier("a", 100, Operation.ADD_VALUE)), 0, 64));
        assertArrayEquals(new double[] {0.0, 0.0}, AttributeValues.range(attribute(Double.NaN), 0, 64));
    }

    /** Any order the client may sum several modifiers of one operation in lies inside the interval. */
    @Test
    void severalModifiersGiveAnIntervalHoldingEveryOrder() {
        Random random = new Random(3);
        for (int round = 0; round < 2000; round++) {
            List<Modifier> modifiers = new ArrayList<>();
            int n = 2 + random.nextInt(6);
            for (int i = 0; i < n; i++) {
                Operation op = Operation.values()[random.nextInt(3)];
                modifiers.add(new Modifier("m" + i, (random.nextDouble() - 0.4) * (op == Operation.ADD_VALUE ? 10 : 1), op));
            }
            double base = random.nextDouble() * 8;
            double[] range = AttributeValues.range(new ClientboundPacket.UpdateAttributes.Attribute("x", base, modifiers), 0, 64);
            for (int shuffle = 0; shuffle < 10; shuffle++) {
                Collections.shuffle(modifiers, random);
                double value = clientValue(base, modifiers);
                assertTrue(range[0] <= value && value <= range[1], value + " outside " + range[0] + ".." + range[1]);
            }
        }
    }

    /** AttributeInstance.calculateValue in this order, then the range. */
    private static double clientValue(double base, List<Modifier> modifiers) {
        double v = base;
        for (Modifier m : modifiers) {
            if (m.operation() == Operation.ADD_VALUE) {
                v += m.amount();
            }
        }
        double total = v;
        for (Modifier m : modifiers) {
            if (m.operation() == Operation.ADD_MULTIPLIED_BASE) {
                total += v * m.amount();
            }
        }
        for (Modifier m : modifiers) {
            if (m.operation() == Operation.ADD_MULTIPLIED_TOTAL) {
                total *= 1.0 + m.amount();
            }
        }
        return Double.isNaN(total) ? 0 : Math.clamp(total, 0, 64);
    }
}
