package dev.whynotac.analysis.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link ModifierTable} against the client's own fastutil {@code Object2ObjectOpenHashMap}, keyed as
 * {@code Identifier} hashes: after every random put and remove, the same iteration order.
 */
class ModifierTableTest {

    /** {@code Identifier}'s equality and hash: what the map sees of a key. */
    private record Id(String namespace, String path) {
        @Override
        public int hashCode() {
            return 31 * namespace.hashCode() + path.hashCode();
        }

        String text() {
            return namespace + ":" + path;
        }
    }

    private static final List<Id> IDS = new ArrayList<>(List.of(new Id("minecraft", "sprinting"), new Id("minecraft", "effect.speed"),
            new Id("minecraft", "effect.slowness"), new Id("minecraft", "powder_snow"), new Id("minecraft", "armor.boots"),
            new Id("minecraft", "enchantment.soul_speed"), new Id("minecraft", "enchantment.swift_sneak"), new Id("minecraft", "base_attack_speed"),
            new Id("myplugin", "speed_boost"), new Id("myplugin", "slow")));

    static {
        Random r = new Random(1);
        for (int i = 0; i < 60; i++) {
            IDS.add(new Id(r.nextBoolean() ? "minecraft" : "x" + r.nextInt(5), "m" + Integer.toString(r.nextInt(100_000), 36)));
        }
    }

    @Test
    void theOrderIsTheClientsOwnAfterEveryPutAndRemove() {
        Random random = new Random(42);
        int probed = 0;
        for (int round = 0; round < 2_000; round++) {
            Object2ObjectOpenHashMap<Id, ClientboundPacket.UpdateAttributes.Modifier> real = new Object2ObjectOpenHashMap<>();
            ModifierTable mine = ModifierTable.EMPTY;
            for (int op = 0; op < 40; op++) {
                Id id = IDS.get(random.nextInt(random.nextBoolean() ? 12 : IDS.size()));
                ClientboundPacket.UpdateAttributes.Modifier m = new ClientboundPacket.UpdateAttributes.Modifier(id.text(), random.nextDouble(),
                        ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL);
                if (random.nextInt(3) == 0 || real.size() >= ModifierTable.MAX_ENTRIES) {
                    real.remove(id);
                    mine = mine.remove(id.text());
                } else {
                    real.put(id, m);
                    mine = mine.put(m);
                }
                assertEquals(new ArrayList<>(real.values()), mine.inOrder(), "round " + round + " step " + op);
                assertEquals(real.size(), mine.size());
            }
            probed += probed(real);
        }
        assertTrue(probed > 100, "the random sets exercise probing: " + probed);
    }

    /** Keys that sit away from their home slot (a collision moved them). */
    private static int probed(Object2ObjectOpenHashMap<Id, ?> map) {
        int n = 0;
        List<Id> order = new ArrayList<>(map.keySet());
        for (int i = 1; i < order.size(); i++) {
            // keys iterate from the last slot down; a key whose home is above its predecessor's was probed past it
            int home = ModifierTable.mix(order.get(i).hashCode()) & (ModifierTable.SLOTS - 1);
            int before = ModifierTable.mix(order.get(i - 1).hashCode()) & (ModifierTable.SLOTS - 1);
            n += home >= before ? 1 : 0;
        }
        return n;
    }

    @Test
    void sprintingWithASpeedEffectIsSummedInTheTablesOrder() {
        ClientboundPacket.UpdateAttributes.Modifier speed = new ClientboundPacket.UpdateAttributes.Modifier("minecraft:effect.speed", 0.4000000059604645,
                ClientboundPacket.UpdateAttributes.Operation.ADD_MULTIPLIED_TOTAL);
        SpeedAttribute attribute = SpeedAttribute.of(new ClientboundPacket.UpdateAttributes.Attribute(SelfState.MOVEMENT_SPEED, 0.1F, List.of(speed)));
        Object2ObjectOpenHashMap<Id, Double> real = new Object2ObjectOpenHashMap<>();
        real.put(new Id("minecraft", "effect.speed"), speed.amount());
        real.put(new Id("minecraft", "sprinting"), (double) 0.3F);
        double expected = 0.1F;
        for (Map.Entry<Id, Double> e : real.entrySet()) {
            expected *= 1.0 + e.getValue();
        }
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(attribute.value(true)));
        assertEquals(Double.doubleToLongBits(0.1F * (1.0 + speed.amount())), Double.doubleToLongBits(attribute.value(false)));
        assertTrue(attribute.sprintLayoutCanonical());
    }
}
