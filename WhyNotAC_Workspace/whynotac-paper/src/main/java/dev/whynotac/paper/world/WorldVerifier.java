package dev.whynotac.paper.world;

import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.ClientWorldTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /whynotac world <player>}: compares the world WhyNotAC rebuilt from what the client received
 * with the server's world, section by section (a 64-bit hash of the 4096 block-state ids each).
 *
 * <p>Equal means the client holds exactly the server's blocks, so the replica is right. Expected
 * differences: blocks changed in the last moments (the replica follows the network, the server is
 * ahead), the client's unconfirmed predictions, and anti-xray (the client deliberately receives other
 * blocks). The server side is hashed on the main thread: a diagnostic, it costs a few milliseconds per
 * hundred chunks.
 */
public final class WorldVerifier {

    /** One verification's outcome, also printed as one line. */
    public record Result(String player, String dimension, boolean dimensionMatches, int chunks, int sections, int equal, int different,
            int stale, int missingOnServer, int unsettled, ClientWorld.Stats stats, List<String> differences) {
        public String line() {
            return "WhyNotAC world " + player + ": dimension=" + dimension + (dimensionMatches ? "" : " (server: other)") + " chunks=" + chunks
                    + " sections=" + sections + " equal=" + equal + " different=" + different + " stale=" + stale + " missingOnServer="
                    + missingOnServer + " unsettled=" + unsettled + " blockUpdates=" + stats.blockUpdates() + " chunksReceived="
                    + stats.chunksReceived() + " chunksIgnored=" + stats.chunksIgnored() + " unreadable=" + stats.chunksUnreadable()
                    + " retainedKiB=" + stats.retainedBytes() / 1024;
        }
    }

    private record Snapshot(ClientWorld.Digest digest, ClientWorld.Stats stats) {}

    private final PacketInterceptor interceptor;
    private final ClientWorldTracker tracker;
    private final Plugin plugin;

    public WorldVerifier(PacketInterceptor interceptor, ClientWorldTracker tracker, Plugin plugin) {
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
        tracker.query(session.get(), world -> new Snapshot(world.digest(), world.stats()))
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        failed.accept("no answer from " + target.getName() + "'s session: " + error);
                    } else if (!target.isOnline()) {
                        failed.accept(target.getName() + " left");
                    } else {
                        done.accept(compare(target, snapshot));
                    }
                }));
    }

    private static Result compare(Player target, Snapshot snapshot) {
        ServerLevel level = ((CraftWorld) target.getWorld()).getHandle();
        ClientWorld.Digest digest = snapshot.digest();
        String serverDimension = level.dimension().identifier().toString();
        boolean dimensionMatches = serverDimension.equals(digest.dimension()) && level.getMinY() == digest.minY();
        int sections = 0;
        int equal = 0;
        int different = 0;
        int stale = 0;
        int missing = 0;
        List<String> differences = new ArrayList<>();
        for (Map.Entry<Long, long[]> entry : digest.chunks().entrySet()) {
            int cx = ClientWorld.chunkX(entry.getKey());
            int cz = ClientWorld.chunkZ(entry.getKey());
            long[] ours = entry.getValue();
            LevelChunk chunk = dimensionMatches ? level.getChunkSource().getChunkNow(cx, cz) : null;
            if (chunk == null) {
                missing++;
                continue;
            }
            LevelChunkSection[] theirs = chunk.getSections();
            for (int i = 0; i < ours.length && i < theirs.length; i++) {
                sections++;
                if (ours[i] == 0) {
                    stale++;
                    continue;
                }
                if (ours[i] == hash(theirs[i])) {
                    equal++;
                } else {
                    different++;
                    if (differences.size() < 10) {
                        differences.add("chunk " + cx + "," + cz + " section y=" + (level.getMinSectionY() + i));
                    }
                }
            }
        }
        return new Result(target.getName(), digest.dimension(), dimensionMatches, digest.chunks().size(), sections, equal, different, stale, missing,
                digest.unsettled(), snapshot.stats(), differences);
    }

    /** {@link ClientWorld#sectionHash} of a server section, in the game's index order. */
    static long hash(LevelChunkSection section) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < 4096; index++) {
            hash = ClientWorld.sectionHashStep(hash, Block.getId(section.getBlockState(index & 15, index >>> 8, index >>> 4 & 15)));
        }
        return hash;
    }
}
