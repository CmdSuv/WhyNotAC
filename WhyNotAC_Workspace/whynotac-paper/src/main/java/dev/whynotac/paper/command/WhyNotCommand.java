package dev.whynotac.paper.command;

import dev.whynotac.paper.cases.CaseStore;
import dev.whynotac.paper.diagnostics.Diagnostics;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.paper.world.EntityVerifier;
import dev.whynotac.paper.world.WorldVerifier;
import dev.whynotac.protocol.latency.LatencySnapshot;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /whynotac inspect <player>} and {@code /whynotac sessions}: raw interceptor state for
 * operators. Values are printed with full precision ({@link Double#toString}) on purpose.
 */
public final class WhyNotCommand implements BasicCommand {

    public static final String PERMISSION = "whynotac.inspect";

    private final PacketInterceptor interceptor;
    private final SessionRegistry registry;
    private final Diagnostics diagnostics;
    private final Supplier<String> stats;
    private final CaseStore cases;
    private final WorldVerifier world;
    private final EntityVerifier entities;
    private final org.bukkit.plugin.Plugin plugin;

    public WhyNotCommand(PacketInterceptor interceptor, SessionRegistry registry, Diagnostics diagnostics, Supplier<String> stats,
            CaseStore cases, WorldVerifier world, EntityVerifier entities, org.bukkit.plugin.Plugin plugin) {
        this.interceptor = interceptor;
        this.registry = registry;
        this.diagnostics = diagnostics;
        this.stats = stats;
        this.cases = cases;
        this.world = world;
        this.entities = entities;
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("No online player named " + args[1], NamedTextColor.RED));
                return;
            }
            inspect(sender, target);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pipeline")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("No online player named " + args[1], NamedTextColor.RED));
                return;
            }
            List<String> names = interceptor.pipeline(target).orElse(List.of());
            sender.sendMessage(Component.text("Pipeline of " + target.getName() + " (network -> game):", NamedTextColor.GOLD));
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                boolean ours = name.equals(PacketInterceptor.HANDLER_NAME) || name.equals(dev.whynotac.paper.wire.WireTaps.INBOUND)
                        || name.equals(dev.whynotac.paper.wire.WireTaps.OUTBOUND);
                sender.sendMessage(Component.text(String.format(Locale.ROOT, " %2d %s%s", i, name, ours ? "   <== WhyNotAC" : ""),
                        ours ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("cases")) {
            cases.recent(10, list -> Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(Component.text("WhyNotAC cases (newest first): " + list.size(), NamedTextColor.GOLD));
                for (CaseStore.StoredCase c : list) {
                    sender.sendMessage(Component.text("  " + c.id().substring(0, 8) + " " + c.playerName() + " " + c.check() + " "
                            + c.evidenceClass() + " " + c.lifecycle() + " " + java.time.Instant.ofEpochMilli(c.createdEpochMillis())
                            + " " + c.summary(), NamedTextColor.GRAY));
                }
            }));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("case")) {
            cases.find(args[1], found -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (found.isEmpty()) {
                    sender.sendMessage(Component.text("No single case starts with " + args[1], NamedTextColor.RED));
                    return;
                }
                CaseStore.StoredCase c = found.get();
                for (String line : List.of("== WhyNotAC case " + c.id(), "Player " + c.playerName() + " (" + c.playerId() + ", " + c.identity() + ")",
                        "Check " + c.check() + " v" + c.checkVersion() + " " + c.evidenceClass() + " " + c.lifecycle()
                                + " at " + java.time.Instant.ofEpochMilli(c.createdEpochMillis()),
                        "Finding " + c.summary(), "Window sequences " + c.fromSequence() + ".." + c.toSequence(),
                        "Recording " + c.recording(), "Dossier " + c.dossier() + " (re-verify: java -jar WhyNotAC.jar verify <dossier>)")) {
                    sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                }
            }));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("No online player named " + args[1], NamedTextColor.RED));
                return;
            }
            world.verify(target, result -> {
                sender.sendMessage(Component.text(result.line(), result.different() == 0 && result.dimensionMatches()
                        ? NamedTextColor.GREEN : NamedTextColor.RED));
                for (String difference : result.differences()) {
                    sender.sendMessage(Component.text("  differs: " + difference, NamedTextColor.GRAY));
                }
            }, error -> sender.sendMessage(Component.text("WhyNotAC world " + target.getName() + ": " + error, NamedTextColor.RED)));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("entities")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("No online player named " + args[1], NamedTextColor.RED));
                return;
            }
            entities.verify(target, result -> {
                sender.sendMessage(Component.text(result.line(), result.clean() ? NamedTextColor.GREEN : NamedTextColor.RED));
                for (String note : result.differences()) {
                    sender.sendMessage(Component.text("  " + note, NamedTextColor.GRAY));
                }
            }, error -> sender.sendMessage(Component.text("WhyNotAC entities " + target.getName() + ": " + error, NamedTextColor.RED)));
        } else if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
            sender.sendMessage(Component.text("WhyNotAC stats " + stats.get(), NamedTextColor.GRAY));
        } else if (args.length == 1 && args[0].equalsIgnoreCase("sessions")) {
            sender.sendMessage(Component.text("WhyNotAC sessions: " + registry.sessions().size(), NamedTextColor.GOLD));
            for (PlayerSession session : registry.sessions()) {
                sender.sendMessage(Component.text(" " + session + " ticks=" + session.clientTicks(), NamedTextColor.GRAY));
            }
        } else {
            sender.sendMessage(Component.text("/whynotac inspect <player> | /whynotac pipeline <player> | /whynotac world <player> | /whynotac entities <player> | /whynotac sessions | /whynotac stats | /whynotac cases | /whynotac case <id>",
                    NamedTextColor.YELLOW));
        }
    }

    private void inspect(CommandSender sender, Player target) {
        Optional<PlayerSession> found = interceptor.session(target);
        if (found.isEmpty()) {
            sender.sendMessage(Component.text(target.getName() + " has no WhyNotAC session (interceptor not attached)", NamedTextColor.RED));
            return;
        }
        PlayerSession session = found.get();
        LatencySnapshot latency = session.latency(System.nanoTime());
        PlayerSession.Counters counters = session.counters();
        List<String> lines = new ArrayList<>();
        lines.add("== WhyNotAC: " + target.getName() + " (" + session.remoteAddress() + ", connection #" + session.connectionId() + ")");
        lines.add(String.format(Locale.ROOT, "RTT last %.3f ms | smoothed %.3f | jitter %.3f | min %.3f | max %.3f",
                latency.lastRttMillis(), latency.smoothedRttMillis(), latency.jitterMillis(), latency.minRttMillis(), latency.maxRttMillis()));
        lines.add("Transactions sent " + latency.sent() + " acked " + latency.acknowledged() + " pending " + latency.pending()
                + String.format(Locale.ROOT, " (oldest %.1f ms)", latency.oldestPendingMillis())
                + " skipped " + latency.skipped() + " duplicate " + latency.duplicates() + " lost-before-wire " + latency.lostBeforeWire()
                + (latency.withheld() ? " WITHHELD" : ""));
        interceptor.edge(target).ifPresent(edge -> lines.add("View " + (edge.source() == null ? "pending" : edge.source())
                + " | client protocol " + edge.clientProtocol() + " | divergences in wire-only " + edge.inboundWireOnly()
                + " game-only " + edge.inboundGameOnly() + " | out wire-only " + edge.outboundWireOnly() + " game-only "
                + edge.outboundGameOnly() + " | edge transactions " + edge.edgeTransactions() + " | flush transactions " + edge.flushTransactions()
                + " | placement repairs " + edge.placementRepairs()));
        lines.add("Packets in " + counters.inbound() + " | out modelled " + counters.outboundModeled() + " unmodelled "
                + counters.outboundUnmodeled() + " | queued " + counters.queued() + " dropped " + counters.dropped()
                + " | client ticks " + session.clientTicks());
        Diagnostics.View view = diagnostics.view(session);
        if (view != null) {
            ServerboundPacket.MovePlayer move = view.lastMove();
            if (move != null) {
                lines.add("Last move " + move.shape() + " x=" + move.x() + " y=" + move.y() + " z=" + move.z()
                        + " yaw=" + move.yaw() + " pitch=" + move.pitch() + " ground=" + move.onGround() + " hcol=" + move.horizontalCollision());
            }
            Diagnostics.Rotation rotation = view.lastRotation();
            if (rotation != null) {
                lines.add("Last rotation yaw=" + rotation.move().yaw() + " pitch=" + rotation.move().pitch()
                        + " exact dYaw=" + rotation.yawDelta() + " dPitch=" + rotation.pitchDelta()
                        + " (float resolution of this yaw: +/-" + rotation.yawInformationLimit() + ")");
            }
            ServerboundPacket.PlayerInput input = view.lastInput();
            lines.add("Keys W" + flag(input.forward()) + " S" + flag(input.backward()) + " A" + flag(input.left()) + " D" + flag(input.right())
                    + " jump" + flag(input.jump()) + " sneak" + flag(input.shift()) + " sprint" + flag(input.sprint()));
            lines.add("Anomalies " + view.anomalies() + (view.lastAnomaly() == null ? "" : " (last: " + view.lastAnomaly().kind() + ")")
                    + " | gaps " + view.gaps());
        }
        for (String line : lines) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
    }

    private static String flag(boolean pressed) {
        return pressed ? "+" : "-";
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("inspect", "pipeline", "world", "entities", "sessions", "stats", "cases", "case");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("inspect") || args[0].equalsIgnoreCase("pipeline") || args[0].equalsIgnoreCase("world")
                || args[0].equalsIgnoreCase("entities"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
