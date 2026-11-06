package dev.whynotac.interop;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/**
 * Test-only plugin for WhyNotAC's live E2E. It produces exactly the situations the edge view exists
 * for, with a real server and a real client connection:
 *
 * <ul>
 *   <li>{@code game}: knockback through the game's normal path ({@code setVelocity}).
 *   <li>{@code inject}: knockback written below the game's send path (PacketEvents'
 *       {@code sendPacketSilently} if PacketEvents is installed, otherwise the same thing with raw Netty).
 *   <li>{@code cancel}: knockback through the game's path that a plugin cancels below WhyNotAC
 *       (a PacketEvents listener, otherwise a raw Netty handler), so the client never receives it.
 * </ul>
 * Every action is logged with a {@code [wnprobe]} line naming the path used.
 */
public final class InteropProbePlugin extends JavaPlugin {

    private Edge edge;

    @Override
    public void onEnable() {
        boolean packetEvents = Bukkit.getPluginManager().isPluginEnabled("packetevents");
        edge = packetEvents ? new PacketEventsEdge(getLogger()) : new RawEdge(getLogger());
        getLogger().info("[wnprobe] ready, below-game path: " + edge.name());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 5) {
            sender.sendMessage("usage: wnprobe game|inject|cancel <player> <vx> <vy> <vz>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("no such player: " + args[1]);
            return true;
        }
        Vector velocity = new Vector(Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]));
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "game" -> {
                target.setVelocity(velocity);
                getLogger().info("[wnprobe] game " + velocity);
            }
            case "inject" -> {
                edge.inject(target, velocity);
                getLogger().info("[wnprobe] inject via " + edge.name() + " " + velocity);
            }
            case "cancel" -> {
                edge.cancelNextVelocity(target);
                target.setVelocity(velocity);
                getLogger().info("[wnprobe] cancel via " + edge.name() + " " + velocity);
            }
            default -> sender.sendMessage("unknown action " + args[0]);
        }
        return true;
    }

    /** One way of acting below WhyNotAC's game-side handler. */
    interface Edge {
        String name();

        void inject(Player player, Vector velocity);

        void cancelNextVelocity(Player player);
    }
}
