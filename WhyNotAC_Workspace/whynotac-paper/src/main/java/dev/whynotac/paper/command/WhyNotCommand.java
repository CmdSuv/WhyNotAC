package dev.whynotac.paper.command;

import dev.whynotac.paper.diagnostics.Diagnostics;
import dev.whynotac.paper.network.PacketInterceptor;
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

    public WhyNotCommand(PacketInterceptor interceptor, SessionRegistry registry, Diagnostics diagnostics) {
        this.interceptor = interceptor;
        this.registry = registry;
        this.diagnostics = diagnostics;
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
        } else if (args.length == 1 && args[0].equalsIgnoreCase("sessions")) {
            sender.sendMessage(Component.text("WhyNotAC sessions: " + registry.sessions().size(), NamedTextColor.GOLD));
            for (PlayerSession session : registry.sessions()) {
                sender.sendMessage(Component.text(" " + session + " ticks=" + session.clientTicks(), NamedTextColor.GRAY));
            }
        } else {
            sender.sendMessage(Component.text("/whynotac inspect <player> | /whynotac sessions", NamedTextColor.YELLOW));
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
                + " skipped " + latency.skipped() + " unknown " + latency.unknown());
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
            lines.add("Exact dYaw=" + view.lastYawDelta() + " dPitch=" + view.lastPitchDelta()
                    + " (float resolution of this yaw: +/-" + view.yawInformationLimit() + ")");
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
            return List.of("inspect", "sessions");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
