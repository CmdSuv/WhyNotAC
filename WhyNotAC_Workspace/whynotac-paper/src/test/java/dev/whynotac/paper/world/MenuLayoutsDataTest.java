package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.world.MenuLayouts;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.NautilusInventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The menu layouts whynotac-world ships are the game's own: every menu type's client menu (the one
 * {@code MenuType.create} builds, as {@code MenuScreens.create} does), the player's {@code InventoryMenu},
 * and the mount menus {@code ClientPacketListener.handleMountScreenOpen} builds, read slot by slot for the
 * ones that are the player's inventory.
 */
class MenuLayoutsDataTest {

    private static Inventory inventory;

    @BeforeAll
    static void bootstrap() throws ReflectiveOperationException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ServerPlayer player = (ServerPlayer) GameEntities.allocate(EntityType.PLAYER, ServerPlayer.class).entity();
        // SmithingMenu reads the level's recipes (their property sets: empty here, which only the slot filters read).
        ServerLevel level = GameEntities.allocateObject(ServerLevel.class);
        GameEntities.set(net.minecraft.world.level.Level.class, "isClientSide", level, true);
        MinecraftServer server = GameEntities.allocateObject(net.minecraft.server.dedicated.DedicatedServer.class);
        ReloadableServerResources managers = GameEntities.allocateObject(ReloadableServerResources.class);
        GameEntities.set(ReloadableServerResources.class, "recipes", managers, new RecipeManager(null));
        Class<?> resources = Class.forName("net.minecraft.server.MinecraftServer$ReloadableResources");
        Constructor<?> make = resources.getDeclaredConstructors()[0];
        make.setAccessible(true);
        GameEntities.set(MinecraftServer.class, "resources", server, make.newInstance(null, managers));
        GameEntities.set(ServerLevel.class, "server", level, server);
        // LoomMenu looks the banner patterns up (only its pattern list reads them).
        net.minecraft.core.MappedRegistry<net.minecraft.world.level.block.entity.BannerPattern> patterns =
                new net.minecraft.core.MappedRegistry<>(net.minecraft.core.registries.Registries.BANNER_PATTERN, com.mojang.serialization.Lifecycle.stable());
        patterns.freeze();
        GameEntities.set(net.minecraft.world.level.Level.class, "registryAccess", level,
                new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(List.of(patterns)));
        GameEntities.set(net.minecraft.world.entity.Entity.class, "level", player, level);
        // Paper's menus keep the player's Bukkit object (EnchantmentMenu); one without a server stands in.
        GameEntities.set(net.minecraft.world.entity.Entity.class, "bukkitEntity", player,
                GameEntities.allocateObject(org.bukkit.craftbukkit.entity.CraftPlayer.class));
        inventory = new Inventory(player, new EntityEquipment());
    }

    @Test
    void theShippedLayoutsAreTheGamesOwn() throws IOException, ReflectiveOperationException {
        List<String> lines = new ArrayList<>();
        for (MenuType<?> type : BuiltInRegistries.MENU) {
            lines.add(row(BuiltInRegistries.MENU.getKey(type).toString(), type.create(1, inventory)));
        }
        lines.add(row(MenuLayouts.INVENTORY, new InventoryMenu(inventory, false, inventory.player)));
        lines.add(row(MenuLayouts.HORSE, horse(0)));
        lines.add(row(MenuLayouts.NAUTILUS, nautilus(0)));
        lines.sort(null);
        String table = String.join("\n", lines) + "\n";
        if (!table.equals(MenuLayouts.vanillaTable())) {
            Path out = Path.of("build/menu-layouts.tsv");
            Files.writeString(out, table, StandardCharsets.UTF_8);
            assertEquals(table, MenuLayouts.vanillaTable(), "the shipped menu-layouts.tsv differs; the game's is in " + out.toAbsolutePath());
        }
    }

    @Test
    void mountMenusFollowTheirColumns() throws ReflectiveOperationException {
        MenuLayouts layouts = MenuLayouts.vanilla();
        for (int columns = 0; columns <= 16; columns++) {
            String horse = row(MenuLayouts.HORSE, horse(columns));
            MenuLayouts.Layout claimed = layouts.horse(columns);
            assertEquals(horse.substring(horse.indexOf('\t')), line(claimed).substring(line(claimed).indexOf('\t')), "horse, " + columns + " columns");
            String nautilus = row(MenuLayouts.NAUTILUS, nautilus(columns));
            assertEquals(nautilus, line(layouts.nautilus()), "nautilus, " + columns + " columns");
        }
    }

    private static AbstractContainerMenu horse(int columns) throws ReflectiveOperationException {
        AbstractHorse horse = (AbstractHorse) GameEntities.allocate(EntityType.HORSE, net.minecraft.world.entity.animal.equine.Horse.class).entity();
        return new HorseInventoryMenu(2, inventory, new SimpleContainer(AbstractMountInventoryMenu.getInventorySize(columns)), horse, columns);
    }

    private static AbstractContainerMenu nautilus(int columns) throws ReflectiveOperationException {
        AbstractNautilus nautilus = (AbstractNautilus) GameEntities.allocate(EntityType.NAUTILUS, net.minecraft.world.entity.animal.nautilus.Nautilus.class)
                .entity();
        return new NautilusInventoryMenu(2, inventory, new SimpleContainer(AbstractMountInventoryMenu.getInventorySize(columns)), nautilus, columns);
    }

    /** {@code key size hotbar(9 menu slots, -1 none) offhand feet}. */
    private static String row(String key, AbstractContainerMenu menu) {
        int[] hotbar = new int[9];
        Arrays.fill(hotbar, -1);
        int offhand = -1;
        int feet = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inventory) {
                int index = slot.getContainerSlot();
                if (index < 9) {
                    assertEquals(-1, hotbar[index], key + ": hotbar slot " + index + " twice");
                    hotbar[index] = i;
                } else if (index == 40) {
                    offhand = i;
                } else if (Inventory.EQUIPMENT_SLOT_MAPPING.get(index) == net.minecraft.world.entity.EquipmentSlot.FEET) {
                    assertEquals(-1, feet, key + ": the feet twice");
                    feet = i;
                }
            }
        }
        StringBuilder b = new StringBuilder(key).append('\t').append(menu.slots.size()).append('\t');
        for (int k = 0; k < 9; k++) {
            b.append(k == 0 ? "" : ",").append(hotbar[k]);
        }
        return b.append('\t').append(offhand).append('\t').append(feet).toString();
    }

    private static String line(MenuLayouts.Layout layout) {
        StringBuilder b = new StringBuilder(layout.key()).append('\t').append(layout.size()).append('\t');
        for (int k = 0; k < 9; k++) {
            b.append(k == 0 ? "" : ",").append(layout.hotbar().get(k));
        }
        return b.append('\t').append(layout.offhand()).append('\t').append(layout.feet()).toString();
    }
}
