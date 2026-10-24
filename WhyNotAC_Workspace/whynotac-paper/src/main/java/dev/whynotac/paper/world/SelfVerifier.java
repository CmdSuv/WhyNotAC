package dev.whynotac.paper.world;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.ClientSelfTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /whynotac self <player>}: compares WhyNotAC's replica of the client's own player with the
 * server's: the position the eye is based on (the server accepts the client's position), the
 * {@code entity_interaction_range} and scale values (the replica's intervals must contain the server's),
 * the game mode (creative or spectator on the server must be possible in the replica) and the vehicle and
 * camera (on the server, the replica must not claim an eye). It also says whether the client certainly holds the
 * tags the movement engine takes as vanilla ({@code engineTags=vanilla}, else the first that may differ).
 *
 * <p>The replica follows the network and the server is ahead of it, so a mismatch counts only once the
 * server's side stood still for a round (two ticks).
 */
public final class SelfVerifier {

    /** One verification's outcome, also printed as one line. */
    public record Result(String player, boolean matches, boolean changing, String eye, boolean positionEqual, String range, double serverRange,
            boolean rangeContains, String scale, double serverScale, boolean scaleContains, int serverMode, boolean modeClaim,
            boolean modeConsistent, int rounds, String engineTags, List<String> differences) {
        public String line() {
            return "WhyNotAC self " + player + ": matches=" + matches + " changing=" + changing + " eye=" + eye.replace(' ', '_') + " positionEqual="
                    + positionEqual + " range=" + range + " serverRange=" + format(serverRange) + " rangeContains=" + rangeContains + " scale=" + scale
                    + " serverScale=" + format(serverScale) + " scaleContains=" + scaleContains + " serverMode=" + serverMode + " mayBeCreativeOrSpectator="
                    + modeClaim + " modeConsistent=" + modeConsistent + " rounds=" + rounds + " engineTags=" + engineTags;
        }

        public boolean clean() {
            return matches;
        }
    }

    private record Snapshot(String eye, Vec3d sent, double[] range, double[] scale, boolean modeClaim, String engineTags) {}

    private record ServerSide(Vec3d position, double range, double scale, int mode, boolean passenger, boolean camera) {}

    private static final int ROUNDS = 6;
    private static final long ROUND_TICKS = 2;
    private static final double SLACK = 1e-9;

    private final PacketInterceptor interceptor;
    private static final dev.whynotac.analysis.movement.EngineTags ENGINE_TAGS =
            new dev.whynotac.analysis.movement.EngineTags(dev.whynotac.world.BlockStates.vanilla());

    private final ClientSelfTracker tracker;
    private final dev.whynotac.world.ClientTagsTracker tags;
    private final Plugin plugin;

    public SelfVerifier(PacketInterceptor interceptor, ClientSelfTracker tracker, dev.whynotac.world.ClientTagsTracker tags, Plugin plugin) {
        this.interceptor = interceptor;
        this.tracker = tracker;
        this.tags = tags;
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
        // the query runs on the session's analysis thread, where the tags replica is too
        tracker.query(session, self -> new Snapshot(self.eyeState(), self.sentPosition(), self.interactionRangeBounds(), self.scaleBounds(),
                        self.mayBeCreativeOrSpectator(), java.util.Objects.requireNonNullElse(ENGINE_TAGS.mayDiffer(tags.tags(session)), "vanilla")))
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
        return new ServerSide(new Vec3d(player.getX(), player.getY(), player.getZ()), player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
                player.getAttributeValue(Attributes.SCALE), player.gameMode.getGameModeForPlayer().getId(), player.isPassenger(),
                player.getCamera() != player);
    }

    private static Result compare(String name, Snapshot snapshot, ServerSide server, ServerSide previous, int round) {
        List<String> differences = new ArrayList<>();
        boolean eyeKnown = snapshot.eye().equals("known");
        boolean positionEqual = snapshot.sent() != null && snapshot.sent().equals(server.position());
        if (eyeKnown && !positionEqual) {
            differences.add("position: replica " + snapshot.sent() + ", server " + server.position());
        }
        if (eyeKnown && (server.passenger() || server.camera())) {
            differences.add("the replica claims an eye, the server's player " + (server.passenger() ? "rides" : "looks through another entity"));
        }
        boolean rangeContains = snapshot.range()[0] - SLACK <= server.range() && server.range() <= snapshot.range()[1] + SLACK;
        if (!rangeContains) {
            differences.add("entity_interaction_range: replica " + interval(snapshot.range()) + ", server " + server.range());
        }
        boolean scaleContains = snapshot.scale()[0] - SLACK <= server.scale() && server.scale() <= snapshot.scale()[1] + SLACK;
        if (!scaleContains) {
            differences.add("scale: replica " + interval(snapshot.scale()) + ", server " + server.scale());
        }
        boolean creativeOrSpectator = server.mode() == 1 || server.mode() == 3;
        boolean modeConsistent = !creativeOrSpectator || snapshot.modeClaim();
        if (!modeConsistent) {
            differences.add("game mode: the server's is " + server.mode() + ", the replica rules out creative and spectator");
        }
        boolean matches = differences.isEmpty();
        boolean changing = !matches && !server.equals(previous);
        return new Result(name, matches, changing, snapshot.eye(), positionEqual, interval(snapshot.range()), server.range(), rangeContains,
                interval(snapshot.scale()), server.scale(), scaleContains, server.mode(), snapshot.modeClaim(), modeConsistent, round,
                snapshot.engineTags(), differences);
    }

    private static String interval(double[] bounds) {
        return format(bounds[0]) + ".." + format(bounds[1]);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
