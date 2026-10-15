package dev.whynotac.paper.world;

import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.paper.network.PacketTranslator;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.ClientInventoryTracker;
import dev.whynotac.world.ItemPrototypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /whynotac inventory <player>}: compares the hands WhyNotAC rebuilt from what the client received
 * and sent with the server's inventory: the nine hotbar stacks, the off hand, the selected slot and the
 * open menu's id.
 *
 * <p>A replica state matches when every slot it knows holds the server's stack (item, count, the patch's
 * component types and, unless the replica only has the client's hashed copy, its attack range), or, for a
 * slot it marks as maybe used up, a stack without an attack range; and its selected slot and menu are the
 * server's where it knows them. The replica follows the network and the server is ahead of it, so a
 * mismatch counts only once the server's side stood still for a round (two ticks). Expected differences:
 * a client prediction the server did not make and did not correct, and another plugin's inventory
 * packets the replica cannot read.
 */
public final class InventoryVerifier {

    /** One verification's outcome, also printed as one line. */
    public record Result(String player, int views, boolean matches, boolean changing, int slotsKnown, int slotsEqual, int slotsUsedUp,
            int slotsUnknown, boolean selectedKnown, boolean menuKnown, boolean reachKnown, boolean reachContainsServer, String mainHand,
            int candidates, int pendingOps, long overflows, int rounds, List<String> differences) {
        public String line() {
            return "WhyNotAC inventory " + player + ": matches=" + matches + " changing=" + changing + " views=" + views + " slotsKnown="
                    + slotsKnown + " slotsEqual=" + slotsEqual + " slotsUsedUp=" + slotsUsedUp + " slotsUnknown=" + slotsUnknown + " selectedKnown="
                    + selectedKnown + " menuKnown=" + menuKnown + " reachKnown=" + reachKnown + " reachContainsServer=" + reachContainsServer
                    + " mainHand=" + mainHand + " candidates=" + candidates + " pendingOps=" + pendingOps + " overflows=" + overflows + " rounds=" + rounds;
        }

        /** The replica holds the server's state, and its main-hand reach allows the server's. */
        public boolean clean() {
            return matches && reachContainsServer;
        }
    }

    private record Snapshot(List<ClientInventory.View> views, Set<ClientInventory.Reach> mainHand, int candidates, int pendingOps, long overflows) {}

    /** The server's side: hotbar 0..8, the off hand, the selected slot, the open menu. */
    private record ServerSide(List<Stack> hotbar, Stack offhand, int selected, int menuId) {}

    private static final int ROUNDS = 6;
    private static final long ROUND_TICKS = 2;

    private final PacketInterceptor interceptor;
    private final ClientInventoryTracker tracker;
    private final ItemPrototypes prototypes;
    private final Plugin plugin;

    public InventoryVerifier(PacketInterceptor interceptor, ClientInventoryTracker tracker, ItemPrototypes prototypes, Plugin plugin) {
        this.interceptor = interceptor;
        this.tracker = tracker;
        this.prototypes = prototypes;
        this.plugin = plugin;
    }

    /** Main thread. {@code done} runs on the main thread with the result, or an error message. */
    public void verify(Player target, Consumer<Result> done, Consumer<String> failed) {
        Optional<PlayerSession> session = interceptor.session(target);
        if (session.isEmpty()) {
            failed.accept(target.getName() + " has no WhyNotAC session");
            return;
        }
        round(target, session.get(), null, 1, done, failed);
    }

    private void round(Player target, PlayerSession session, ServerSide previous, int round, Consumer<Result> done, Consumer<String> failed) {
        tracker.query(session, inventory -> new Snapshot(inventory.views(), inventory.mainHandReach(), inventory.candidates(), inventory.pendingOps(),
                        inventory.overflows()))
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        failed.accept("no answer from " + target.getName() + "'s session: " + error);
                    } else if (!target.isOnline()) {
                        failed.accept(target.getName() + " left");
                    } else {
                        ServerSide server = server(((CraftPlayer) target).getHandle());
                        Result result = compare(target.getName(), snapshot, server, previous, round);
                        if (result.matches() || !result.changing() || round >= ROUNDS) {
                            done.accept(result);
                        } else {
                            Bukkit.getScheduler().runTaskLater(plugin, () -> round(target, session, server, round + 1, done, failed), ROUND_TICKS);
                        }
                    }
                }));
    }

    private static ServerSide server(ServerPlayer player) {
        List<Stack> hotbar = new ArrayList<>(ClientInventory.HOTBAR);
        for (int k = 0; k < ClientInventory.HOTBAR; k++) {
            hotbar.add(PacketTranslator.stack(player.getInventory().getItem(k)));
        }
        return new ServerSide(hotbar, PacketTranslator.stack(player.getInventory().getItem(40)), player.getInventory().getSelectedSlot(),
                player.containerMenu.containerId);
    }

    private Result compare(String name, Snapshot snapshot, ServerSide server, ServerSide previous, int round) {
        ClientInventory.View best = null;
        int bestEqual = -1;
        boolean matches = false;
        List<String> differences = new ArrayList<>();
        for (ClientInventory.View view : snapshot.views()) {
            int equal = 0;
            boolean all = true;
            for (int k = 0; k <= ClientInventory.HOTBAR; k++) {
                Stack ours = k < ClientInventory.HOTBAR ? view.hotbar().get(k) : view.offhand();
                Stack theirs = k < ClientInventory.HOTBAR ? server.hotbar().get(k) : server.offhand();
                if (ours == null) {
                    continue;
                }
                if (same(ours, theirs) || view.mayBeNone()[k] && ClientInventory.reach(prototypes, theirs).equals(ClientInventory.Reach.NONE)) {
                    equal++;
                } else {
                    all = false;
                }
            }
            all &= view.selected() < 0 || view.selected() == server.selected();
            all &= view.menuId() < 0 || view.menuId() == server.menuId();
            if (all && !matches) {
                matches = true;
                best = view;
            } else if (!matches && equal > bestEqual) {
                best = view;
                bestEqual = equal;
            }
        }
        int known = 0;
        int equal = 0;
        int usedUp = 0;
        int unknown = 0;
        if (best != null) {
            for (int k = 0; k <= ClientInventory.HOTBAR; k++) {
                Stack ours = k < ClientInventory.HOTBAR ? best.hotbar().get(k) : best.offhand();
                Stack theirs = k < ClientInventory.HOTBAR ? server.hotbar().get(k) : server.offhand();
                String slot = k < ClientInventory.HOTBAR ? "hotbar " + k : "off hand";
                if (ours == null) {
                    unknown++;
                } else {
                    known++;
                    if (same(ours, theirs)) {
                        equal++;
                    } else if (best.mayBeNone()[k] && ClientInventory.reach(prototypes, theirs).equals(ClientInventory.Reach.NONE)) {
                        usedUp++;
                    } else if (differences.size() < 10) {
                        differences.add(slot + ": replica " + describe(ours) + ", server " + describe(theirs));
                    }
                }
            }
            if (best.selected() >= 0 && best.selected() != server.selected() && differences.size() < 10) {
                differences.add("selected: replica " + best.selected() + ", server " + server.selected());
            }
            if (best.menuId() >= 0 && best.menuId() != server.menuId() && differences.size() < 10) {
                differences.add("menu: replica " + best.menuId() + ", server " + server.menuId());
            }
        }
        ClientInventory.Reach serverReach = ClientInventory.reach(prototypes, server.hotbar().get(server.selected()));
        // the replica's claim: the server's main-hand reach is one it allows (or it claims nothing)
        boolean reachKnown = !snapshot.mainHand().contains(ClientInventory.Reach.UNKNOWN);
        boolean reachContains = !reachKnown || snapshot.mainHand().contains(serverReach);
        boolean changing = !matches && !server.equals(previous);
        return new Result(name, snapshot.views().size(), matches, changing, known, equal, usedUp, unknown, best != null && best.selected() >= 0,
                best != null && best.menuId() >= 0, reachKnown, reachContains, describe(snapshot.mainHand()), snapshot.candidates(), snapshot.pendingOps(),
                snapshot.overflows(), round, differences);
    }

    /** The server's stack as the replica holds it: equal, or the client's hashed copy of it (no component values). */
    static boolean same(Stack ours, Stack theirs) {
        if (ours.isEmpty() || theirs.isEmpty()) {
            return ours.isEmpty() && theirs.isEmpty();
        }
        return ours.item().equals(theirs.item()) && ours.count() == theirs.count() && ours.added().equals(theirs.added())
                && ours.removed().equals(theirs.removed()) && (ours.hashed() || Objects.equals(ours.attackRange(), theirs.attackRange()));
    }

    /** A reach set: {@code none}, {@code ?} (unknown) or {@code max+margin}, comma separated. */
    private static String describe(Set<ClientInventory.Reach> reaches) {
        List<String> out = new ArrayList<>();
        for (ClientInventory.Reach reach : reaches) {
            out.add(!reach.known() ? "?" : reach.range() == null ? "none" : reach.range().maxRange() + "+" + reach.range().hitboxMargin());
        }
        out.sort(null);
        return String.join(",", out);
    }

    private static String describe(Stack stack) {
        return stack.isEmpty() ? "empty" : stack.count() + "x" + stack.item() + (stack.added().isEmpty() ? "" : " +" + stack.added())
                + (stack.removed().isEmpty() ? "" : " -" + stack.removed());
    }
}
