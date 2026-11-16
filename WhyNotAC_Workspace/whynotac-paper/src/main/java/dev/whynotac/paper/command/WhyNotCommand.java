package dev.whynotac.paper.command;

import dev.whynotac.ai.AccountBaselineModel;
import dev.whynotac.ai.AiAdvisor;
import dev.whynotac.ai.BehavioralFeatureExtractor;
import dev.whynotac.ai.EValueProcess;
import dev.whynotac.paper.cases.CaseStore;
import dev.whynotac.paper.diagnostics.Diagnostics;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.paper.privacy.SubjectRequests;
import dev.whynotac.paper.world.EntityVerifier;
import dev.whynotac.paper.world.InventoryVerifier;
import dev.whynotac.paper.world.SelfVerifier;
import dev.whynotac.paper.world.WorldVerifier;
import dev.whynotac.protocol.latency.LatencySnapshot;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.storage.AuditLedger;
import dev.whynotac.storage.ReviewWorkflow;
import dev.whynotac.storage.StorageManager;
import dev.whynotac.verdict.CircuitBreaker;
import dev.whynotac.verdict.FalseBanBudget;
import dev.whynotac.verdict.VerdictConfig;
import dev.whynotac.verdict.VerdictEngine;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
    private final InventoryVerifier inventory;
    private final SelfVerifier self;
    private final SubjectRequests privacy;
    private final VerdictEngine verdict;
    private final StorageManager storage;
    private final AiAdvisor aiAdvisor;
    private final org.bukkit.plugin.Plugin plugin;

    public WhyNotCommand(PacketInterceptor interceptor, SessionRegistry registry, Diagnostics diagnostics, Supplier<String> stats,
            CaseStore cases, WorldVerifier world, EntityVerifier entities, InventoryVerifier inventory, SelfVerifier self, SubjectRequests privacy,
            VerdictEngine verdict, StorageManager storage, AiAdvisor aiAdvisor, org.bukkit.plugin.Plugin plugin) {
        this.interceptor = interceptor;
        this.registry = registry;
        this.diagnostics = diagnostics;
        this.stats = stats;
        this.cases = cases;
        this.world = world;
        this.entities = entities;
        this.inventory = inventory;
        this.self = self;
        this.privacy = privacy;
        this.verdict = verdict;
        this.storage = storage;
        this.aiAdvisor = aiAdvisor;
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
                    AiAdvisor.CasePriority priority = aiAdvisor == null ? null : aiAdvisor.priorityOf(c.id());
                    String prioTag = priority == null ? "" : String.format(Locale.ROOT, " [ai=%.1f]", priority.score0To100());
                    sender.sendMessage(Component.text("  " + c.id().substring(0, 8) + " " + c.playerName() + " " + c.check() + " "
                            + c.evidenceClass() + " " + c.lifecycle() + " " + java.time.Instant.ofEpochMilli(c.createdEpochMillis())
                            + prioTag + " " + c.summary(), NamedTextColor.GRAY));
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
        } else if (args.length == 2 && args[0].equalsIgnoreCase("inventory")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("No online player named " + args[1], NamedTextColor.RED));
                return;
            }
            inventory.verify(target, result -> {
                sender.sendMessage(Component.text(result.line(), result.clean() ? NamedTextColor.GREEN : NamedTextColor.RED));
                for (String note : result.differences()) {
                    sender.sendMessage(Component.text("  " + note, NamedTextColor.GRAY));
                }
            }, error -> sender.sendMessage(Component.text("WhyNotAC inventory " + target.getName() + ": " + error, NamedTextColor.RED)));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("self")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("No online player named " + args[1], NamedTextColor.RED));
                return;
            }
            self.verify(target, result -> {
                sender.sendMessage(Component.text(result.line(), result.clean() ? NamedTextColor.GREEN : NamedTextColor.RED));
                for (String note : result.differences()) {
                    sender.sendMessage(Component.text("  " + note, NamedTextColor.GRAY));
                }
            }, error -> sender.sendMessage(Component.text("WhyNotAC self " + target.getName() + ": " + error, NamedTextColor.RED)));
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("privacy")) {
            privacy.handle(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
        } else if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
            sender.sendMessage(Component.text("WhyNotAC stats " + stats.get(), NamedTextColor.GRAY));
        } else if (args.length == 1 && args[0].equalsIgnoreCase("sessions")) {
            sender.sendMessage(Component.text("WhyNotAC sessions: " + registry.sessions().size(), NamedTextColor.GOLD));
            for (PlayerSession session : registry.sessions()) {
                sender.sendMessage(Component.text(" " + session + " ticks=" + session.clientTicks(), NamedTextColor.GRAY));
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("verdict")) {
            VerdictConfig cfg = verdict.config();
            CircuitBreaker.Status cb = verdict.circuitBreaker().status(System.currentTimeMillis());
            FalseBanBudget.Snapshot fb = verdict.falseBanBudget().snapshot(System.currentTimeMillis());
            sender.sendMessage(Component.text("WhyNotAC verdict:", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  armedChecks=" + cfg.armedChecks() + " mitigations=" + cfg.mitigationsEnabled()
                    + " autoKick=" + cfg.autoKickEnabled() + " autoBan=" + cfg.autoBanEnabled()
                    + " secondVerifier=" + cfg.secondVerifierEnabled(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  circuitBreaker killSwitch=" + cb.killSwitchActive() + " tripped=" + cb.tripped()
                    + " recentEnforced=" + cb.recentEnforcedSubjects()
                    + (cb.reason().isEmpty() ? "" : " reason=" + cb.reason()), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  falseBanBudget issued=" + fb.totalAutoBansIssued() + " incidents=" + fb.recentIncidents()
                    + "/" + fb.budgetLimit() + " locked=" + fb.locked()
                    + (fb.lockReason().isEmpty() ? "" : " reason=" + fb.lockReason()), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  accepted=" + verdict.acceptedCount() + " rejected=" + verdict.rejectedCount(), NamedTextColor.GRAY));
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("killswitch")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("on")) {
                verdict.circuitBreaker().setKillSwitch(true, "operator command");
                sender.sendMessage(Component.text("WhyNotAC kill switch ON (all enforcement suppressed to SHADOW)", NamedTextColor.YELLOW));
            } else if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
                verdict.circuitBreaker().setKillSwitch(false, "");
                sender.sendMessage(Component.text("WhyNotAC kill switch OFF", NamedTextColor.GREEN));
            } else {
                CircuitBreaker.Status cb = verdict.circuitBreaker().status(System.currentTimeMillis());
                sender.sendMessage(Component.text("WhyNotAC killSwitch=" + cb.killSwitchActive() + " tripped=" + cb.tripped(), NamedTextColor.GRAY));
            }
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("circuit")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                verdict.circuitBreaker().resetAutomaticTrip();
                verdict.falseBanBudget().reset();
                sender.sendMessage(Component.text("WhyNotAC circuit breaker and false-ban budget reset", NamedTextColor.GREEN));
            } else {
                CircuitBreaker.Status cb = verdict.circuitBreaker().status(System.currentTimeMillis());
                sender.sendMessage(Component.text("WhyNotAC circuit tripped=" + cb.tripped() + " killSwitch=" + cb.killSwitchActive()
                        + " recentEnforced=" + cb.recentEnforcedSubjects(), NamedTextColor.GRAY));
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("audit")) {
            if (storage == null) {
                sender.sendMessage(Component.text("WhyNotAC storage ledger unavailable", NamedTextColor.RED));
                return;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    AuditLedger.ChainVerification v = storage.ledger().verify();
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(
                            "WhyNotAC audit ledger valid=" + v.valid() + " entries=" + v.entryCount()
                                    + " lastSeq=" + v.lastSequence() + " head=" + v.headHash().substring(0, 12)
                                    + (v.valid() ? "" : " error=" + v.failureReason()),
                            v.valid() ? NamedTextColor.GREEN : NamedTextColor.RED)));
                } catch (java.io.IOException e) {
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(
                            "WhyNotAC audit verify error: " + e.getMessage(), NamedTextColor.RED)));
                }
            });
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("review")) {
            if (storage == null) {
                sender.sendMessage(Component.text("WhyNotAC storage workflow unavailable", NamedTextColor.RED));
                return;
            }
            String casePrefix = args[1];
            String reviewer = sender.getName();
            String sub = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "status";
            String notes = args.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : "";
            cases.find(casePrefix, found -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (found.isEmpty()) {
                    sender.sendMessage(Component.text("No single case starts with " + casePrefix, NamedTextColor.RED));
                    return;
                }
                String caseId = found.get().id();
                long now = System.currentTimeMillis();
                try {
                    ReviewWorkflow.ReviewRecord rec = switch (sub) {
                        case "pass1" -> storage.reviews().recordFirstReview(caseId, reviewer, notes, now);
                        case "confirm" -> storage.reviews().confirmSecondReview(caseId, reviewer, notes, now);
                        case "appeal" -> storage.reviews().submitAppeal(caseId, notes, now);
                        case "uphold" -> storage.reviews().resolveAppeal(caseId, reviewer, true, notes, now);
                        case "overturn" -> {
                            ReviewWorkflow.ReviewRecord r = storage.reviews().resolveAppeal(caseId, reviewer, false, notes, now);
                            verdict.falseBanBudget().recordIncident(caseId, "overturned by " + reviewer + ": " + notes, now);
                            if (found.get().playerId() != null) {
                                try {
                                    storage.profiles().recordFalseBanIncident(UUID.fromString(found.get().playerId()), now);
                                } catch (Exception ignored) { }
                            }
                            yield r;
                        }
                        default -> storage.reviews().status(caseId).orElseGet(() -> storage.reviews().open(caseId, now));
                    };
                    sender.sendMessage(Component.text("WhyNotAC review " + caseId.substring(0, 8) + " status=" + rec.status()
                            + " primary=" + rec.primaryReviewer() + " secondary=" + rec.secondaryReviewer()
                            + (rec.notes().isEmpty() ? "" : " notes=" + rec.notes()), NamedTextColor.GRAY));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    sender.sendMessage(Component.text("Review rejected: " + e.getMessage(), NamedTextColor.RED));
                }
            }));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ai")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("No online player named " + args[1], NamedTextColor.RED));
                return;
            }
            inspectAi(sender, target);
        } else {
            sender.sendMessage(Component.text("/whynotac inspect <player> | /whynotac ai <player> | /whynotac pipeline <player> | /whynotac world <player> | /whynotac entities <player> | /whynotac inventory <player> | /whynotac self <player> | /whynotac sessions | /whynotac stats | /whynotac cases | /whynotac case <id> | /whynotac review <id> [pass1|confirm|appeal|uphold|overturn] | /whynotac audit | /whynotac verdict | /whynotac killswitch [on|off] | /whynotac circuit [status|reset] | /whynotac privacy find|forget <uuid> [--yes]",
                    NamedTextColor.YELLOW));
        }
    }

    private void inspectAi(CommandSender sender, Player target) {
        if (aiAdvisor == null) {
            sender.sendMessage(Component.text("WhyNotAC AI advisor unavailable", NamedTextColor.RED));
            return;
        }
        Optional<PlayerSession> found = interceptor.session(target);
        if (found.isEmpty()) {
            sender.sendMessage(Component.text(target.getName() + " has no WhyNotAC session", NamedTextColor.RED));
            return;
        }
        BehavioralFeatureExtractor.Snapshot snap = aiAdvisor.extractor().snapshot(found.get());
        AccountBaselineModel.ShiftAssessment shift = aiAdvisor.baselineModel().assess(target.getUniqueId(), snap);
        EValueProcess probe = aiAdvisor.probeScheduler().processFor(target.getUniqueId());
        sender.sendMessage(Component.text("WhyNotAC AI (non-punitive) for " + target.getName() + ":", NamedTextColor.GOLD));
        sender.sendMessage(Component.text(String.format(Locale.ROOT,
                "  baselineShift coldStart=%b rmsZ=%.3f top=%s (z=%.3f) | probeE=%.3f%s",
                shift.coldStart(), shift.rmsZScore(),
                shift.topFeature().isEmpty() ? "none" : shift.topFeature(), shift.topZScore(),
                probe == null ? 1.0 : probe.wealth(),
                probe != null && probe.rejectedNull() ? " [H0_REJECTED]" : ""), NamedTextColor.GRAY));
        snap.features().forEach((name, moments) -> sender.sendMessage(Component.text(String.format(Locale.ROOT,
                "  %s n=%d mean=%.4f std=%.4f",
                name, moments.count(), moments.mean(), moments.sampleStdDev()), NamedTextColor.GRAY)));
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
        lines.add("== WhyNotAC: " + target.getName() + " (connection #" + session.connectionId() + ")");
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
            return List.of("inspect", "ai", "pipeline", "world", "entities", "inventory", "self", "sessions", "stats", "cases", "case",
                    "review", "audit", "verdict", "killswitch", "circuit");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("review")) {
            return List.of("status", "pass1", "confirm", "appeal", "uphold", "overturn");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("inspect") || args[0].equalsIgnoreCase("ai")
                || args[0].equalsIgnoreCase("pipeline") || args[0].equalsIgnoreCase("world")
                || args[0].equalsIgnoreCase("entities") || args[0].equalsIgnoreCase("inventory") || args[0].equalsIgnoreCase("self"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("killswitch")) {
            return List.of("on", "off", "status");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("circuit")) {
            return List.of("status", "reset");
        }
        return List.of();
    }
}
