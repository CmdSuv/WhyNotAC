package dev.whynotac.paper.world;

import dev.whynotac.math.vanilla.PositionCodec;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientEntitiesTracker;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /whynotac entities <player>}: compares the entities WhyNotAC rebuilt from what the client received
 * with the server's own tracking of that player: which entities the server shows it
 * ({@code ChunkMap.TrackedEntity.seenBy}) and, per entity, the position base both sides keep for relative
 * moves ({@code ServerEntity.positionCodec}, {@code VecDeltaCodec} on the client).
 *
 * <p>The bases are compared on the 1/4096 grid the codec works on: the server keeps an entity's exact
 * position as its base and the client the decoded one, and both encode to the same grid point. Passengers
 * are skipped: the server moves a passenger's base without telling the client (the client places it by its
 * vehicle). Expected differences: entities that moved in the last moments (the replica follows the network,
 * the server is ahead), and entities other plugins show the client with their own packets.
 */
public final class EntityVerifier {

    /** One verification's outcome, also printed as one line. */
    public record Result(String player, boolean complete, int server, int replica, int compared, int equal, int different, int moving,
            int passengers, int missingInReplica, int extraInReplica, int rounds, ClientEntities.Stats stats, List<String> differences) {
        public String line() {
            return "WhyNotAC entities " + player + ": complete=" + complete + " server=" + server + " replica=" + replica + " compared=" + compared
                    + " equal=" + equal + " different=" + different + " moving=" + moving + " passengers=" + passengers + " missingInReplica="
                    + missingInReplica + " extraInReplica=" + extraInReplica + " rounds=" + rounds + " candidates=" + stats.candidates()
                    + " exact=" + stats.exact() + " lost=" + stats.lost() + " pendingOps=" + stats.pendingOps() + " ticks=" + stats.ticks()
                    + " branches=" + stats.branches() + " merges=" + stats.merges() + " overflows=" + stats.overflows();
        }

        public boolean clean() {
            return different == 0 && missingInReplica == 0 && extraInReplica == 0;
        }
    }

    /**
     * A difference counts only once it stood still: the server's side unchanged since the previous round
     * (two ticks earlier, long enough for its packets to have passed the edge) and the replica still
     * different. Entities that keep moving are reported as {@code moving} after the last round.
     */
    private static final int ROUNDS = 6;
    private static final long ROUND_TICKS = 2;

    /** What the server shows the player: per entity id its codec base, type and whether it is a passenger. */
    private record ServerView(Map<Integer, Vec3> bases, Map<Integer, String> types, java.util.Set<Integer> passengers) {}

    private final PacketInterceptor interceptor;
    private final ClientEntitiesTracker tracker;
    private final Plugin plugin;

    public EntityVerifier(PacketInterceptor interceptor, ClientEntitiesTracker tracker, Plugin plugin) {
        this.interceptor = interceptor;
        this.tracker = tracker;
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

    private void round(Player target, PlayerSession session, ServerView previous, int round, Consumer<Result> done, Consumer<String> failed) {
        tracker.query(session, ClientEntities::serverOrder)
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        failed.accept("no answer from " + target.getName() + "'s session: " + error);
                        return;
                    }
                    if (!target.isOnline()) {
                        failed.accept(target.getName() + " left");
                        return;
                    }
                    ServerView now = serverView(target);
                    Result result = compare(target.getName(), now, previous, snapshot, round);
                    if (result.moving() == 0 && (previous != null || result.clean()) || round >= ROUNDS) {
                        done.accept(result);
                    } else {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> round(target, session, now, round + 1, done, failed), ROUND_TICKS);
                    }
                }));
    }

    private static ServerView serverView(Player target) {
        ServerPlayer player = ((CraftPlayer) target).getHandle();
        ServerLevel level = player.level();
        Map<Integer, Vec3> bases = new HashMap<>();
        Map<Integer, String> types = new HashMap<>();
        java.util.Set<Integer> passengers = new java.util.HashSet<>();
        for (Int2ObjectMap.Entry<ChunkMap.TrackedEntity> entry : level.getChunkSource().chunkMap.entityMap.int2ObjectEntrySet()) {
            ChunkMap.TrackedEntity tracked = entry.getValue();
            if (tracked.seenBy.contains(player.connection)) {
                int id = entry.getIntKey();
                Entity entity = level.getEntity(id);
                bases.put(id, tracked.serverEntity.getPositionBase());
                types.put(id, typeOf(entity));
                if (entity != null && entity.isPassenger()) {
                    passengers.add(id);
                }
            }
        }
        return new ServerView(bases, types, passengers);
    }

    private static Result compare(String player, ServerView server, ServerView previous, ClientEntities.ServerOrder snapshot, int round) {
        int compared = 0;
        int equal = 0;
        int different = 0;
        int moving = 0;
        int passengers = 0;
        int missing = 0;
        int extra = 0;
        List<String> differences = new ArrayList<>();
        Map<Integer, ClientEntities.ServerOrder.Entity> replica = snapshot.entities();
        for (Map.Entry<Integer, Vec3> entry : server.bases().entrySet()) {
            int id = entry.getKey();
            boolean still = previous != null && entry.getValue().equals(previous.bases().get(id));
            ClientEntities.ServerOrder.Entity ours = replica.get(id);
            String type = server.types().get(id);
            if (ours == null) {
                if (still) {
                    missing++;
                    note(differences, "missing " + id + " " + type);
                } else {
                    moving++;
                }
                continue;
            }
            if (server.passengers().contains(id)) {
                passengers++;
                continue;
            }
            compared++;
            Vec3 theirs = entry.getValue();
            Vec3d base = ours.base();
            boolean sameType = type.equals(ours.type());
            if (base != null && sameType && PositionCodec.encode(base.x()) == PositionCodec.encode(theirs.x)
                    && PositionCodec.encode(base.y()) == PositionCodec.encode(theirs.y) && PositionCodec.encode(base.z()) == PositionCodec.encode(theirs.z)) {
                equal++;
            } else if (still) {
                different++;
                note(differences, id + " " + type + (sameType ? "" : " (replica: " + ours.type() + ")") + " server base " + theirs + " replica " + base);
            } else {
                moving++;
            }
        }
        for (Map.Entry<Integer, ClientEntities.ServerOrder.Entity> entry : replica.entrySet()) {
            if (!entry.getValue().knowledge().equals("exact")) {
                note(differences, "client state of " + entry.getKey() + " " + entry.getValue().type() + ": " + entry.getValue().knowledge());
            }
            if (!server.bases().containsKey(entry.getKey())) {
                if (previous != null && !previous.bases().containsKey(entry.getKey())) {
                    extra++;
                    note(differences, "extra " + entry.getKey() + " " + entry.getValue().type());
                } else {
                    moving++;
                }
            }
        }
        return new Result(player, snapshot.complete(), server.bases().size(), replica.size(), compared, equal, different, moving, passengers,
                missing, extra, round, snapshot.stats(), differences);
    }

    private static void note(List<String> differences, String line) {
        if (differences.size() < 20) {
            differences.add(line);
        }
    }

    private static String typeOf(Entity entity) {
        return entity == null ? "?" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
