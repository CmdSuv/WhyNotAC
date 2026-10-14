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
            lines.add(BuiltInRegistries.ITEM.getKey(item) + "\t" + range + "\t" + into);
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
