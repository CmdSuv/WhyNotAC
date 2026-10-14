package dev.whynotac.paper.world;

import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.ClientWorldTracker;
import java.util.ArrayList;
import java.util.HashMap;
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
 * <p>Equal means the client holds exactly the server's blocks, so the replica is right. A difference
 * counts only once it stood still: the server's section unchanged since the previous round (two ticks
 * earlier, long enough for its packets to have passed the edge) and the replica's still different. A
 * block the server changed in the last moments (an animal eating grass, a fluid) is not sent until the
 * tick's end; such sections are re-checked, and reported as {@code changing} if the server kept changing
 * them until the last round. Other expected differences: the client's unconfirmed predictions, and
 * anti-xray (the client deliberately receives other blocks). The server side is hashed on the main thread:
 * a diagnostic, it costs a few milliseconds per hundred chunks.
 */
public final class WorldVerifier {

    /** One verification's outcome, also printed as one line. */
    public record Result(String player, String dimension, boolean dimensionMatches, int chunks, int sections, int equal, int different,
            int changing, int stale, int missingOnServer, int unsettled, ClientWorld.Stats stats, long allWorldsBytes, int sharedSections,
            int rounds, List<String> differences) {
        public String line() {
            return "WhyNotAC world " + player + ": dimension=" + dimension + (dimensionMatches ? "" : " (server: other)") + " chunks=" + chunks
                    + " sections=" + sections + " equal=" + equal + " different=" + different + " changing=" + changing + " rounds=" + rounds
                    + " stale=" + stale + " missingOnServer="
                    + missingOnServer + " unsettled=" + unsettled + " blockUpdates=" + stats.blockUpdates() + " chunksReceived="
                    + stats.chunksReceived() + " chunksIgnored=" + stats.chunksIgnored() + " unreadable=" + stats.chunksUnreadable()
                    + " allWorldsKiB=" + allWorldsBytes / 1024 + " sharedSections=" + sharedSections;
        }
    }

    private record Snapshot(ClientWorld.Digest digest, ClientWorld.Stats stats) {}

    /** A section: its chunk's key and its index from the bottom. */
    private record SectionKey(long chunk, int index) {}

    /** A round's result and the server's hash of each section that differed in it. */
    private record Comparison(Result result, Map<SectionKey, Long> differing) {}

    private static final int ROUNDS = 6;
    private static final long ROUND_TICKS = 2;

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
        round(target, session.get(), Map.of(), 1, done, failed);
    }

    private void round(Player target, PlayerSession session, Map<SectionKey, Long> previous, int round, Consumer<Result> done,
            Consumer<String> failed) {
        tracker.query(session, world -> new Snapshot(world.digest(), world.stats()))
                .orTimeout(5, TimeUnit.SECONDS)
                .whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        failed.accept("no answer from " + target.getName() + "'s session: " + error);
                    } else if (!target.isOnline()) {
                        failed.accept(target.getName() + " left");
                    } else {
                        Comparison comparison = compare(target, snapshot, tracker, previous, round);
                        if (comparison.result().changing() == 0 || round >= ROUNDS) {
                            done.accept(comparison.result());
                        } else {
                            Bukkit.getScheduler().runTaskLater(plugin,
                                    () -> round(target, session, comparison.differing(), round + 1, done, failed), ROUND_TICKS);
                        }
                    }
                }));
    }

    private static Comparison compare(Player target, Snapshot snapshot, ClientWorldTracker tracker, Map<SectionKey, Long> previous, int round) {
        ServerLevel level = ((CraftWorld) target.getWorld()).getHandle();
        ClientWorld.Digest digest = snapshot.digest();
        String serverDimension = level.dimension().identifier().toString();
        boolean dimensionMatches = serverDimension.equals(digest.dimension()) && level.getMinY() == digest.minY();
        int sections = 0;
        int equal = 0;
        int different = 0;
        int changing = 0;
        int stale = 0;
        int missing = 0;
        List<String> differences = new ArrayList<>();
        Map<SectionKey, Long> differing = new HashMap<>();
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
                long hash = hash(theirs[i]);
                if (ours[i] == hash) {
                    equal++;
                    continue;
                }
                SectionKey key = new SectionKey(entry.getKey(), i);
                differing.put(key, hash);
                if (Long.valueOf(hash).equals(previous.get(key))) {
                    different++; // the server's side stood still for a round and the replica still differs
                    if (differences.size() < 10) {
                        differences.add("chunk " + cx + "," + cz + " section y=" + (level.getMinSectionY() + i));
                    }
                } else {
                    changing++;
                }
            }
        }
        Result result = new Result(target.getName(), digest.dimension(), dimensionMatches, digest.chunks().size(), sections, equal, different,
                changing, stale, missing, digest.unsettled(), snapshot.stats(), tracker.retainedBytes(), tracker.sharedSections(), round,
                differences);
        return new Comparison(result, differing);
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
