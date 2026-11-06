package dev.whynotac.paper.privacy;

import dev.whynotac.paper.cases.CaseStore;
import dev.whynotac.recording.cli.RecordingTool;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /whynotac privacy find|forget <uuid> [--yes]}, from the server console only: one data-subject
 * request over everything WhyNotAC stores about a player. The recordings ({@link RecordingTool}: the
 * player's own, and the mentions in other players' recordings) and the cases ({@link CaseStore#subject}:
 * rows and dossiers). {@code forget} without {@code --yes} lists what it would do; with it, it deletes.
 * It refuses while the player is online, since their session is still being recorded.
 */
public final class SubjectRequests {

    private final Path recordings;
    private final Path secret;
    private final CaseStore cases;
    private final Plugin plugin;
    private final AtomicBoolean busy = new AtomicBoolean();

    public SubjectRequests(Path recordings, Path secret, CaseStore cases, Plugin plugin) {
        this.recordings = recordings;
        this.secret = secret;
        this.cases = cases;
        this.plugin = plugin;
    }

    /** Main thread; {@code args} after {@code privacy}. */
    public void handle(CommandSender sender, String[] args) {
        if (sender instanceof Player) {
            sender.sendMessage(Component.text("WhyNotAC privacy requests run from the server console only", NamedTextColor.RED));
            return;
        }
        boolean find = args.length == 2 && args[0].equalsIgnoreCase("find");
        boolean forget = (args.length == 2 || args.length == 3 && args[2].equals("--yes")) && args[0].equalsIgnoreCase("forget");
        UUID player;
        try {
            player = find || forget ? UUID.fromString(args[1]) : null;
        } catch (IllegalArgumentException e) {
            player = null;
        }
        if (player == null) {
            sender.sendMessage(Component.text("/whynotac privacy find <uuid> | /whynotac privacy forget <uuid> [--yes]", NamedTextColor.YELLOW));
            return;
        }
        boolean delete = forget && args.length == 3;
        if (delete && Bukkit.getPlayer(player) != null) {
            sender.sendMessage(Component.text("That player is online and still being recorded: kick them first", NamedTextColor.RED));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            sender.sendMessage(Component.text("Another WhyNotAC privacy request is still running", NamedTextColor.RED));
            return;
        }
        UUID subject = player;
        String action = forget ? "forget" : "find";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> lines = new ArrayList<>();
            boolean clean = true;
            try {
                if (Files.isDirectory(recordings) && Files.isRegularFile(secret)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ByteArrayOutputStream err = new ByteArrayOutputStream();
                    List<String> command = new ArrayList<>(List.of(action, recordings.toString(), secret.toString(), subject.toString()));
                    if (delete) {
                        command.add("--yes");
                    }
                    int exit = RecordingTool.run(command.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8));
                    clean = exit == 0;
                    lines.add("recordings: " + out.toString(StandardCharsets.UTF_8).strip() + err.toString(StandardCharsets.UTF_8).strip());
                } else {
                    lines.add("recordings: none (no recordings directory or no secret key)");
                }
            } catch (RuntimeException e) {
                clean = false;
                lines.add("recordings: failed: " + e);
            }
            boolean recordingsClean = clean;
            cases.subject(subject, delete, result -> {
                lines.add("cases: " + result.cases().size() + " " + result.cases() + ", dossiers " + result.dossiers().size()
                        + (delete ? ", deleted rows " + result.deletedRows() + ", deleted dossiers " + result.deletedDossiers()
                                : forget ? " (dry run: nothing deleted)" : "")
                        + (result.failures().isEmpty() ? "" : ", for manual review " + result.failures()));
                boolean ok = recordingsClean && result.failures().isEmpty();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.set(false);
                    sender.sendMessage(Component.text("WhyNotAC privacy " + action + " " + subject + (ok ? "" : " (incomplete: see below)"),
                            ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                    for (String line : lines) {
                        sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
                    }
                });
            });
        });
    }
}
