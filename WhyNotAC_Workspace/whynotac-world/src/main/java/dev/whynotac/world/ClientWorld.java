package dev.whynotac.world;

import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks one client holds, rebuilt from the packets it received, in the order it received them.
 *
 * <h2>Exactly the client's chunk storage</h2>
 * The 1.21.11 client ({@code ClientChunkCache}) keeps chunks in a square ring buffer of
 * {@code (2r + 1)^2} slots with {@code r = max(2, serverViewDistance) + 3}, indexed by
 * {@code floorMod} of the chunk coordinates, around a view centre the server moves. It ignores a chunk
 * outside {@code r} of the centre, a new chunk replaces whatever occupied its slot, and a lookup only
 * answers for chunks within {@code r} of the <em>current</em> centre. A login creates an empty world; a
 * respawn does so only when the dimension changes. This class reproduces all of that, because at a
 * chunk border "loaded or not" decides whether there is a wall.
 *
 * <h2>What the client may not have applied yet</h2>
 * A packet takes effect when the client processes it, which is only known once the client answers a
 * transaction sent after it. Until then a changed block is <em>unsettled</em>: the client has the old
 * or the new state. The same holds for the client's own predictions: it places or breaks a block
 * itself the moment it acts, ignores the server's states for those positions, and only takes them once
 * the server's {@code block_changed_ack} reaches it. {@link #settled} tells a check whether the client
 * certainly holds {@link #blockState}; a check must never treat an unsettled block as known.
 *
 * <p>Lost events (a {@code Gap}) or a world packet WhyNotAC could not read make every loaded chunk
 * <em>stale</em> (unsettled) until the server sends it again.
 *
 * <p>Confined to the session's analysis thread.
 */
public final class ClientWorld {

    /** {@link #blockState} when WhyNotAC does not know the block (no dimension yet, stale or unreadable data). */
    public static final int UNKNOWN = -1;
    /** Chunks are not given to analysis without a dimension; this many sections is the fallback for old recordings. */
    private static final int DEFAULT_SECTIONS = 24;
    private static final int DEFAULT_MIN_Y = -64;
    private static final int MAX_TRACKED_PINGS = 4096;
    /** A block action can change the clicked block, the block next to it and that one's neighbours (doors, beds, tall plants). */
    private static final int[][] PREDICTION_NEIGHBOURS = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] PREDICTION_SELF = {{0, 0, 0}};

    private final BlockStates states;
    private final int voidAir;

    // --- the client's level ---
    private ClientboundPacket.Dimension dimension;
    private int minY = DEFAULT_MIN_Y;
    private int sections = DEFAULT_SECTIONS;
    private int serverChunkRadius = -1;
    private Storage storage;

    // --- what the client may not have applied yet ---
    private final Map<Integer, Long> pingSequence = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Long> eldest) {
            return size() > MAX_TRACKED_PINGS;
        }
    };
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Map<Long, Integer> unsettledBlocks = new HashMap<>();
    private final Map<Long, Integer> unsettledChunks = new HashMap<>();
    private final List<View> pendingViews = new ArrayList<>();
    /** Client predictions by position: the highest block-action sequence that touched it. */
    private final Map<Long, Integer> predictions = new HashMap<>();
    /** Block-action sequences of item uses whose block changes (if any) WhyNotAC cannot locate. */
    private final ArrayDeque<Integer> unlocatedPredictions = new ArrayDeque<>();
    /** block_changed_ack values the client may not have processed yet: [outbound sequence, acked sequence]. */
    private final ArrayDeque<long[]> pendingBlockAcks = new ArrayDeque<>();
    private int confirmedBlockAck = Integer.MIN_VALUE;

    private long chunksLoaded;
    private long chunksIgnored;
    private long chunksUnreadable;
    private long blockUpdates;
    private long staleEvents;

    public ClientWorld(BlockStates states) {
        this.states = states;
        this.voidAir = states.firstStateOf("minecraft:void_air");
    }

    // --- timeline ---------------------------------------------------------------------------------------

    /** Applies one timeline event, in sequence order. */
    public void onEvent(TimelineEvent event) {
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(out.sequence(), out.packet());
            case TimelineEvent.Inbound in -> onInbound(in.packet());
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.transactionId());
            case TimelineEvent.Gap ignored -> markAllStale();
            default -> { }
        }
    }

    private void onOutbound(long sequence, ClientboundPacket packet) {
        switch (packet) {
            case ClientboundPacket.Ping ping -> pingSequence.put(ping.id(), sequence);
            case ClientboundPacket.Login login -> newLevel(login.dimension(), login.chunkRadius());
            case ClientboundPacket.Respawn respawn -> {
                // The client keeps its world unless the dimension key changes (ClientPacketListener.handleRespawn).
                if (respawn.dimension() == null || dimension == null || !respawn.dimension().key().equals(dimension.key())) {
                    newLevel(respawn.dimension(), serverChunkRadius);
                }
            }
            case ClientboundPacket.ChunkCacheCenter center -> {
                if (storage != null) {
                    viewChanged(sequence);
                    storage.centerX = center.x();
                    storage.centerZ = center.z();
                }
            }
            case ClientboundPacket.ChunkCacheRadius radius -> {
                serverChunkRadius = radius.radius();
                if (storage != null) {
                    viewChanged(sequence);
                    storage = storage.resized(Storage.range(radius.radius()));
                }
            }
            case ClientboundPacket.LevelChunk chunk -> loadChunk(sequence, chunk);
            case ClientboundPacket.ForgetLevelChunk forget -> {
                if (storage != null && storage.drop(forget.x(), forget.z())) {
                    pend(sequence, Pending.CHUNK, chunkKey(forget.x(), forget.z()));
                }
            }
            case ClientboundPacket.BlockUpdate update -> setBlock(sequence, update.position().x(), update.position().y(), update.position().z(),
                    update.state());
            case ClientboundPacket.SectionBlocksUpdate update -> {
                for (int i = 0; i < update.size(); i++) {
                    long entry = update.entry(i);
                    int local = (int) (entry & 0xFFF);
                    setBlock(sequence, update.sectionX() << 4 | local >>> 8 & 15, update.sectionY() << 4 | local & 15,
                            update.sectionZ() << 4 | local >>> 4 & 15, (int) (entry >>> 12));
                }
            }
            case ClientboundPacket.BlockChangedAck ack -> pendingBlockAcks.add(new long[] {sequence, ack.sequence()});
            case ClientboundPacket.Untranslatable untranslatable when isWorldPacket(untranslatable.type()) -> markAllStale();
            default -> { }
        }
    }

    private void onInbound(ServerboundPacket packet) {
        switch (packet) {
            case ServerboundPacket.UseItemOn use -> {
                BlockPosition p = use.position();
                predict(p.x(), p.y(), p.z(), use.sequence(), false);
                BlockFace face = use.face();
                predict(p.x() + face.stepX(), p.y() + face.stepY(), p.z() + face.stepZ(), use.sequence(), true);
            }
            case ServerboundPacket.PlayerAction action when action.action() == ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK
                    || action.action() == ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK
                    || action.action() == ServerboundPacket.PlayerAction.Action.ABORT_DESTROY_BLOCK ->
                    predict(action.position().x(), action.position().y(), action.position().z(), action.sequence(), false);
            // A plain item use (buckets, lily pads...) may place a block where the client's ray hits, which only physics can locate.
            case ServerboundPacket.UseItem use -> unlocatedPredictions.add(use.sequence());
            default -> { }
        }
    }

    private void onAcknowledged(int transactionId) {
        Long pingSeq = pingSequence.remove(transactionId);
        if (pingSeq == null) {
            return;
        }
        // The client processed everything sent before that ping.
        while (!pending.isEmpty() && pending.peekFirst().sequence < pingSeq) {
            Pending p = pending.pollFirst();
            Map<Long, Integer> counts = p.kind == Pending.BLOCK ? unsettledBlocks : unsettledChunks;
            counts.computeIfPresent(p.key, (k, n) -> n > 1 ? n - 1 : null);
        }
        pendingViews.removeIf(view -> view.sequence < pingSeq);
        while (!pendingBlockAcks.isEmpty() && pendingBlockAcks.peekFirst()[0] < pingSeq) {
            confirmedBlockAck = (int) Math.max(confirmedBlockAck, pendingBlockAcks.pollFirst()[1]);
        }
        if (confirmedBlockAck != Integer.MIN_VALUE) {
            int acked = confirmedBlockAck;
            predictions.values().removeIf(sequence -> sequence <= acked);
            while (!unlocatedPredictions.isEmpty() && unlocatedPredictions.peekFirst() <= acked) {
                unlocatedPredictions.pollFirst();
            }
        }
    }

    // --- world changes ------------------------------------------------------------------------------------

    private void newLevel(ClientboundPacket.Dimension newDimension, int chunkRadius) {
        dimension = newDimension;
        if (newDimension != null) {
            minY = newDimension.minY();
            sections = newDimension.sections();
        } else {
            minY = DEFAULT_MIN_Y;
            sections = DEFAULT_SECTIONS;
        }
        serverChunkRadius = chunkRadius;
        storage = new Storage(Storage.range(chunkRadius));
        // Everything known about the old world is gone; what is in flight belongs to the new one.
        unsettledBlocks.clear();
        unsettledChunks.clear();
        pending.clear();
        pendingViews.clear();
        // A new ClientLevel has a new BlockStatePredictionHandler: its block-action sequence starts again.
        predictions.clear();
        unlocatedPredictions.clear();
        pendingBlockAcks.clear();
        confirmedBlockAck = Integer.MIN_VALUE;
    }

    private void loadChunk(long sequence, ClientboundPacket.LevelChunk chunk) {
        if (storage == null) {
            chunksIgnored++;
            return; // no level: the client cannot have processed it either
        }
        if (!storage.inRange(chunk.x(), chunk.z())) {
            chunksIgnored++; // the client logs "Ignoring chunk since it's not in the view range"
            return;
        }
        Column column = new Column(chunk.x(), chunk.z());
        if (chunk.body() == null) {
            column.stale = true;
            chunksUnreadable++;
        } else {
            try {
                column.sections = ChunkDecoder.decode(chunk.body(), sections, states);
                chunksLoaded++;
            } catch (ChunkDecoder.FormatException e) {
                column.stale = true; // the client fails on such a chunk; WhyNotAC knows nothing about it
                chunksUnreadable++;
            }
        }
        storage.replace(column);
        pend(sequence, Pending.CHUNK, chunkKey(chunk.x(), chunk.z()));
    }

    private void setBlock(long sequence, int x, int y, int z, int state) {
        if (storage == null || y < minY || y >= minY + sections * 16) {
            return; // the client ignores a block outside its build height
        }
        Column column = storage.get(x >> 4, z >> 4);
        if (column == null) {
            return; // the client ignores a block in a chunk it does not hold
        }
        blockUpdates++;
        if (column.sections != null) {
            column.sections[(y - minY) >> 4].set(Section.index(x, y, z), state);
        }
        pend(sequence, Pending.BLOCK, blockKey(x, y, z));
    }

    private void predict(int x, int y, int z, int sequence, boolean withNeighbours) {
        int[][] offsets = withNeighbours ? PREDICTION_NEIGHBOURS : PREDICTION_SELF;
        for (int[] o : offsets) {
            predictions.merge(blockKey(x + o[0], y + o[1], z + o[2]), sequence, Math::max);
        }
    }

    private void viewChanged(long sequence) {
        pendingViews.add(new View(sequence, storage.centerX, storage.centerZ, storage.radius));
    }

    private void pend(long sequence, int kind, long key) {
        pending.add(new Pending(sequence, kind, key));
        (kind == Pending.BLOCK ? unsettledBlocks : unsettledChunks).merge(key, 1, Integer::sum);
    }

    private void markAllStale() {
        staleEvents++;
        if (storage != null) {
            storage.forEach(column -> column.stale = true);
        }
    }

    private static boolean isWorldPacket(String type) {
        return switch (type) {
            case "minecraft:level_chunk_with_light", "minecraft:forget_level_chunk", "minecraft:block_update", "minecraft:section_blocks_update",
                    "minecraft:block_changed_ack", "minecraft:set_chunk_cache_center", "minecraft:set_chunk_cache_radius", "minecraft:login",
                    "minecraft:respawn" -> true;
            default -> false;
        };
    }

    // --- queries ------------------------------------------------------------------------------------------

    /**
     * The block state the client gets from {@code level.getBlockState} once it processed everything sent
     * so far: {@code minecraft:void_air} outside the build height and in chunks it does not hold (as the
     * client's empty chunk), {@link #UNKNOWN} where WhyNotAC does not know.
     */
    public int blockState(int x, int y, int z) {
        if (storage == null) {
            return UNKNOWN;
        }
        if (y < minY || y >= minY + sections * 16) {
            return voidAir;
        }
        Column column = storage.get(x >> 4, z >> 4);
        if (column == null) {
            return voidAir;
        }
        if (column.stale || column.sections == null) {
            return UNKNOWN;
        }
        int state = column.sections[(y - minY) >> 4].get(Section.index(x, y, z));
        return state == Section.INVALID ? UNKNOWN : state;
    }

    /** True if the client holds this chunk (within its view range) once it processed everything sent so far. */
    public boolean isLoaded(int chunkX, int chunkZ) {
        return storage != null && storage.get(chunkX, chunkZ) != null;
    }

    /**
     * True if the client certainly holds {@link #blockState} at this position <em>now</em>: no change to it,
     * its chunk or the view is waiting for the client, it is not one of the client's own unconfirmed
     * predictions, and the data is not stale. Item uses WhyNotAC cannot locate are reported separately by
     * {@link #hasUnlocatedPrediction}.
     */
    public boolean settled(int x, int y, int z) {
        if (storage == null || blockState(x, y, z) == UNKNOWN) {
            return false;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (unsettledChunks.containsKey(chunkKey(cx, cz))) {
            return false;
        }
        for (View view : pendingViews) {
            if (view.inRange(cx, cz) != storage.inRange(cx, cz)) {
                return false;
            }
        }
        long key = blockKey(x, y, z);
        return !unsettledBlocks.containsKey(key) && !predictions.containsKey(key);
    }

    /** True while an item use the client may have predicted a block change for is not confirmed. */
    public boolean hasUnlocatedPrediction() {
        return !unlocatedPredictions.isEmpty();
    }

    /** Null before the first login (or in recordings older than dimensions). */
    public ClientboundPacket.Dimension dimension() {
        return dimension;
    }

    public int minY() {
        return minY;
    }

    public int height() {
        return sections * 16;
    }

    public BlockStates states() {
        return states;
    }

    /** Counters for diagnostics. */
    public Stats stats() {
        long[] loaded = {0};
        long[] stale = {0};
        long[] bytes = {0};
        if (storage != null) {
            storage.forEach(column -> {
                loaded[0]++;
                if (column.stale) {
                    stale[0]++;
                }
                if (column.sections != null) {
                    for (Section s : column.sections) {
                        bytes[0] += s.retainedBytes();
                    }
                }
            });
        }
        return new Stats(loaded[0], stale[0], chunksLoaded, chunksIgnored, chunksUnreadable, blockUpdates, unsettledBlocks.size(),
                unsettledChunks.size(), predictions.size(), staleEvents, bytes[0]);
    }

    public record Stats(long chunksHeld, long chunksStale, long chunksReceived, long chunksIgnored, long chunksUnreadable, long blockUpdates,
            int unsettledBlocks, int unsettledChunks, int predictions, long staleEvents, long retainedBytes) {}

    /**
     * A digest of the blocks of every chunk the client holds, one 64-bit hash per section, to compare with
     * the server's world without copying it ({@link #sectionHash}).
     */
    public Digest digest() {
        Map<Long, long[]> chunks = new HashMap<>();
        int[] unsettled = {0};
        if (storage != null) {
            storage.forEach(column -> {
                long[] hashes = new long[sections];
                for (int i = 0; i < sections; i++) {
                    if (column.stale || column.sections == null) {
                        hashes[i] = 0;
                        continue;
                    }
                    Section section = column.sections[i];
                    long hash = FNV_OFFSET;
                    for (int index = 0; index < 4096; index++) {
                        hash = sectionHashStep(hash, section.get(index));
                    }
                    hashes[i] = hash;
                }
                chunks.put(chunkKey(column.x, column.z), hashes);
            });
            unsettled[0] = unsettledBlocks.size() + unsettledChunks.size() + predictions.size();
        }
        return new Digest(dimension == null ? null : dimension.key(), minY, chunks, unsettled[0]);
    }

    /** Per chunk (key {@link #chunkKey}) one hash per section, bottom to top; 0 for stale chunks. */
    public record Digest(String dimension, int minY, Map<Long, long[]> chunks, int unsettled) {}

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** One step of the section hash: FNV-1a over each state id's 4 bytes, little-endian, in index order. */
    public static long sectionHashStep(long hash, int state) {
        for (int i = 0; i < 4; i++) {
            hash ^= state >>> 8 * i & 0xFF;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /** The hash of 4096 state ids in the game's index order ({@code y << 8 | z << 4 | x}). */
    public static long sectionHash(int[] statesInIndexOrder) {
        long hash = FNV_OFFSET;
        for (int state : statesInIndexOrder) {
            hash = sectionHashStep(hash, state);
        }
        return hash;
    }

    public static long chunkKey(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    public static int chunkX(long key) {
        return (int) key;
    }

    public static int chunkZ(long key) {
        return (int) (key >>> 32);
    }

    /** {@code BlockPos.asLong}: x 26 bits, z 26 bits, y 12 bits. */
    static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) y & 0xFFF;
    }

    // --- the client's chunk storage -----------------------------------------------------------------------

    private static final class Column {
        final int x;
        final int z;
        Section[] sections;
        boolean stale;

        Column(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    /** {@code ClientChunkCache.Storage}. */
    private static final class Storage {
        final int radius;
        final int viewRange;
        final Column[] slots;
        int centerX;
        int centerZ;

        Storage(int radius) {
            this.radius = radius;
            this.viewRange = radius * 2 + 1;
            this.slots = new Column[viewRange * viewRange];
        }

        /** {@code ClientChunkCache.calculateStorageRange}. */
        static int range(int serverViewDistance) {
            return Math.max(2, serverViewDistance) + 3;
        }

        int index(int x, int z) {
            return Math.floorMod(z, viewRange) * viewRange + Math.floorMod(x, viewRange);
        }

        boolean inRange(int x, int z) {
            return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
        }

        /** {@code getChunk}: only for chunks within range of the current centre, in their own slot. */
        Column get(int x, int z) {
            if (!inRange(x, z)) {
                return null;
            }
            Column column = slots[index(x, z)];
            return column != null && column.x == x && column.z == z ? column : null;
        }

        void replace(Column column) {
            slots[index(column.x, column.z)] = column;
        }

        /** {@code drop}: true if a chunk was dropped. */
        boolean drop(int x, int z) {
            if (!inRange(x, z)) {
                return false;
            }
            int index = index(x, z);
            Column column = slots[index];
            if (column != null && column.x == x && column.z == z) {
                slots[index] = null;
                return true;
            }
            return false;
        }

        /** {@code updateViewRadius}: a new storage keeping the chunks within the new range. */
        Storage resized(int newRadius) {
            if (newRadius == radius) {
                return this;
            }
            Storage resized = new Storage(newRadius);
            resized.centerX = centerX;
            resized.centerZ = centerZ;
            for (Column column : slots) {
                if (column != null && resized.inRange(column.x, column.z)) {
                    resized.replace(column);
                }
            }
            return resized;
        }

        void forEach(java.util.function.Consumer<Column> action) {
            for (Column column : slots) {
                if (column != null && inRange(column.x, column.z)) {
                    action.accept(column);
                }
            }
        }
    }

    private record Pending(long sequence, int kind, long key) {
        static final int BLOCK = 0;
        static final int CHUNK = 1;
    }

    private record View(long sequence, int centerX, int centerZ, int radius) {
        boolean inRange(int x, int z) {
            return Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius;
        }
    }
}
