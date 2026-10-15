package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.world.ItemPrototypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.UseRemainder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The item prototypes whynotac-world ships are the game's own: every registered item's default attack range and use remainder. */
class ItemPrototypesDataTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The methods through which a use changes the held stack on the client side (MultiPlayerGameMode.useItem, useItemOn, interact, releaseUsingItem, completeUsingItem). */
    private static final List<String> USE_METHODS = List.of("use", "useOn", "interactLivingEntity", "finishUsingItem", "releaseUsing", "onUseTick");

    /**
     * Item classes whose overrides of those methods change nothing in the player's inventory on the client
     * (decompiled 1.21.11, every path read: the changes are inside isClientSide / ServerLevel guards, or are
     * durability, which is ServerLevel-only, or only start using the item).
     */
    private static final java.util.Set<String> CLIENT_STABLE_CLASSES = java.util.Set.of("AxeItem", "HoeItem", "ShovelItem", "ShearsItem", "TridentItem",
            "BowItem", "CrossbowItem", "FishingRodItem", "SpyglassItem", "InstrumentItem", "BrushItem", "FlintAndSteelItem", "MapItem",
            "FoodOnAStickItem", "DebugStickItem", "WrittenBookItem", "WritableBookItem", "LeadItem", "NameTagItem", "DyeItem", "SpawnEggItem",
            "BoatItem", "FireworkRocketItem", "EmptyMapItem", "EnderEyeItem");

    /** True if no class between the item's and Item declares one of the use methods, other than the client-stable ones. */
    private static boolean stable(Item item) {
        for (Class<?> k = item.getClass(); k != Item.class; k = k.getSuperclass()) {
            if (CLIENT_STABLE_CLASSES.contains(k.getSimpleName())) {
                continue;
            }
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (USE_METHODS.contains(m.getName())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    void theShippedPrototypesAreTheGamesOwn() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            AttackRange r = item.components().get(DataComponents.ATTACK_RANGE);
            UseRemainder remainder = item.components().get(DataComponents.USE_REMAINDER);
            String range = r == null ? "-"
                    : r.minRange() + ":" + r.maxRange() + ":" + r.minCreativeRange() + ":" + r.maxCreativeRange() + ":" + r.hitboxMargin() + ":" + r.mobFactor();
            if (remainder != null) {
                // The remainder's own components are its prototype's: the table names the item only.
                assertEquals(true, remainder.convertInto().getComponentsPatch().isEmpty(), BuiltInRegistries.ITEM.getKey(item) + "'s remainder has a patch");
            }
            String into = remainder == null ? "-" : BuiltInRegistries.ITEM.getKey(remainder.convertInto().getItem()).toString();
            net.minecraft.world.item.equipment.Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
            // Item.use swaps a swappable equippable a player may wear with the player's slot (Equippable.swapWithEquipmentSlot)
            String swap = equippable == null || !equippable.swappable() || !equippable.canBeEquippedBy(net.minecraft.world.entity.EntityType.PLAYER) ? "-"
                    : equippable.slot().getSerializedName();
            // Minecraft.startAttack: a piercing weapon in the main hand stabs (ServerboundPlayerAction STAB), it never attacks an entity
            String piercing = item.components().has(DataComponents.PIERCING_WEAPON) ? "piercing" : "-";
            String stable = stable(item) && !item.components().has(DataComponents.CONSUMABLE) && remainder == null && swap.equals("-") ? "stable" : "-";
            lines.add(BuiltInRegistries.ITEM.getKey(item) + "\t" + range + "\t" + into + "\t" + swap + "\t" + piercing + "\t" + stable);
        }
        lines.sort(null);
        String table = String.join("\n", lines) + "\n";
        if (!table.equals(ItemPrototypes.vanillaTable())) {
            Path out = Path.of("build/item-prototypes.tsv");
            Files.writeString(out, table, StandardCharsets.UTF_8);
            assertEquals(table, ItemPrototypes.vanillaTable(), "the shipped item-prototypes.tsv differs; the game's is in " + out.toAbsolutePath());
        }
    }
}
