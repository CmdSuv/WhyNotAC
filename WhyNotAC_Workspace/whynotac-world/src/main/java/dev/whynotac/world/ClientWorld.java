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
    /** Identical sections, shared with every other world. */
    private final SectionPool pool;

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
    /** For each unsettled block, the state before each of its pending changes, oldest first (INVALID: not known). */
    private final Map<Long, ArrayDeque<Integer>> previousStates = new HashMap<>();
    private final Map<Long, Integer> unsettledChunks = new HashMap<>();
    private final List<View> pendingViews = new ArrayList<>();
    /** Client predictions by position: the highest block-action sequence that touched it. */
    private final Map<Long, Integer> predictions = new HashMap<>();
    /** The lowest block-action sequence that may still hold each position in {@link #predictions}. */
    private final Map<Long, Integer> earliestPredictions = new HashMap<>();
    /** Block-action sequences of item uses whose block changes (if any) WhyNotAC cannot locate. */
    private final ArrayDeque<Integer> unlocatedPredictions = new ArrayDeque<>();
    /** block_changed_ack values the client may not have processed yet: [outbound sequence, acked sequence]. */
    private final ArrayDeque<long[]> pendingBlockAcks = new ArrayDeque<>();
    private int confirmedBlockAck = Integer.MIN_VALUE;

    // --- blocks that move entities on the client -----------------------------------------------------------
    /** Per state: a block whose client-side block entity or event moves entities ({@link #MOVER_BLOCKS}, shulker boxes). */
    private final boolean[] movers;
    /** Changes to or from such a block, and replaced chunks: {sequence, minX, minY, minZ, maxX, maxY, maxZ}, ascending. */
    private final ArrayDeque<long[]> moverChanges = new ArrayDeque<>();
    /** Changes before and at this sequence were dropped. */
    private long moverChangesDropped = Long.MIN_VALUE;
    /**
     * Every change to the blocks the client holds, as it processes them: a block or section update, a chunk loaded or
     * forgotten, a change of the view (everywhere), the client's own predictions and their confirmation (at the event
     * that confirmed them, which is no earlier): {sequence, minX, minY, minZ, maxX, maxY, maxZ}, ascending.
     */
    private final ArrayDeque<long[]> changes = new ArrayDeque<>();
    /** Changes before and at this sequence are not kept (a new level, lost events, too many). */
    private long changesDropped = Long.MIN_VALUE;
    /**
     * Changes of the view, which hide or show the chunks whose distance from the centre crosses the radius:
     * {sequence, centre x and z and radius before, the same after}, ascending.
     */
    private final ArrayDeque<long[]> viewChanges = new ArrayDeque<>();
    private static final int MAX_CHANGES = 4096;

    private long chunksLoaded;
    private long chunksIgnored;
    private long chunksUnreadable;
    private long blockUpdates;
    private long staleEvents;

    public ClientWorld(BlockStates states) {
        this(states, new SectionPool());
    }

    /** {@code pool} is shared with the other worlds, so identical sections are stored once. */
    public ClientWorld(BlockStates states, SectionPool pool) {
        this.states = states;
        this.voidAir = states.firstStateOf("minecraft:void_air");
        this.pool = pool;
        this.movers = movers(states);
    }

    /**
     * Blocks that move entities on the client after they ticked: a piston's moving block
     * ({@code PistonMovingBlockEntity.moveCollidedEntities}, also honey), the pistons that start one through a
     * block event, their heads, and shulker boxes opening ({@code ShulkerBoxBlockEntity.moveCollidedEntities}).
     */
    static final java.util.Set<String> MOVER_BLOCKS = java.util.Set.of("minecraft:moving_piston", "minecraft:piston",
            "minecraft:sticky_piston", "minecraft:piston_head");
    private static final int MAX_MOVER_CHANGES = 4096;

    private static boolean[] movers(BlockStates states) {
        boolean[] movers = new boolean[states.size()];
        for (int state = 0; state < movers.length; state++) {
            String block = states.block(state);
            movers[state] = block != null && (MOVER_BLOCKS.contains(block) || block.endsWith("shulker_box"));
        }
        return movers;
    }

    private boolean mover(int state) {
        return state >= 0 && state < movers.length && movers[state];
    }

    private void change(long sequence, long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        changes.add(new long[] {sequence, minX, minY, minZ, maxX, maxY, maxZ});
        if (changes.size() > MAX_CHANGES) {
            changesDropped = Math.max(changesDropped, changes.pollFirst()[0]);
        }
    }

    private void changeEverywhere(long sequence) {
        change(sequence, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    private void viewChange(long sequence, int oldX, int oldZ, int oldRadius) {
        viewChanges.add(new long[] {sequence, oldX, oldZ, oldRadius, storage.centerX, storage.centerZ, storage.radius});
        if (viewChanges.size() > MAX_CHANGES) {
            changesDropped = Math.max(changesDropped, viewChanges.pollFirst()[0]);
        }
    }

    private void dropChanges(long sequence) {
        changes.clear();
        viewChanges.clear();
        changesDropped = Math.max(changesDropped, sequence);
    }

    private void moverChange(long sequence, long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        moverChanges.add(new long[] {sequence, minX, minY, minZ, maxX, maxY, maxZ});
        if (moverChanges.size() > MAX_MOVER_CHANGES) {
            moverChangesDropped = Math.max(moverChangesDropped, moverChanges.pollFirst()[0]);
        }
    }

    // --- timeline ---------------------------------------------------------------------------------------

    /** Applies one timeline event, in sequence order. */
    public void onEvent(TimelineEvent event) {
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(out.sequence(), out.packet());
            case TimelineEvent.Inbound in -> onInbound(in.sequence(), in.packet());
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.sequence(), ack.transactionId());
            case TimelineEvent.Gap gap -> {
                markAllStale();
                dropChanges(gap.sequence());
            }
            case TimelineEvent.Disconnected ignored -> release(); // the session ends: nothing will read this world again
            default -> { }
        }
    }

    private void release() {
        dropAll();
        storage = null;
    }

    /** Gives back every column's sections (also columns outside the view kept in their slots). */
    private void dropAll() {
        if (storage != null) {
            for (Column column : storage.slots) {
                if (column != null) {
                    drop(column);
                }
            }
        }
    }

    /** A column leaves this world: its sections are released to the pool. */
    private void drop(Column column) {
        if (column.sections == null) {
            return;
        }
        for (Section section : column.sections) {
            pool.release(section);
        }
        column.sections = null;
    }

    private void onOutbound(long sequence, ClientboundPacket packet) {
        switch (packet) {
            case ClientboundPacket.Ping ping -> pingSequence.put(ping.id(), sequence);
            case ClientboundPacket.Login login -> {
                newLevel(login.dimension(), login.chunkRadius());
                dropChanges(sequence);
            }
            case ClientboundPacket.Respawn respawn -> {
                // The client keeps its world unless the dimension key changes (ClientPacketListener.handleRespawn).
                if (respawn.dimension() == null || dimension == null || !respawn.dimension().key().equals(dimension.key())) {
                    newLevel(respawn.dimension(), serverChunkRadius);
                    dropChanges(sequence);
                }
            }
            case ClientboundPacket.ChunkCacheCenter center -> {
                if (storage != null) {
                    viewChanged(sequence);
                    int oldX = storage.centerX;
                    int oldZ = storage.centerZ;
                    storage.centerX = center.x();
                    storage.centerZ = center.z();
                    viewChange(sequence, oldX, oldZ, storage.radius);
                }
            }
            case ClientboundPacket.ChunkCacheRadius radius -> {
                serverChunkRadius = radius.radius();
                if (storage != null) {
                    viewChanged(sequence);
                    int oldX = storage.centerX;
                    int oldZ = storage.centerZ;
                    int oldRadius = storage.radius;
                    Storage resized = storage.resized(Storage.range(radius.radius()));
                    for (Column column : storage.slots) {
                        if (column != null && resized.slots[resized.index(column.x, column.z)] != column) {
                            drop(column); // not kept by the resize
                        }
                    }
                    storage = resized;
                    viewChange(sequence, oldX, oldZ, oldRadius);
                }
            }
            case ClientboundPacket.LevelChunk chunk -> loadChunk(sequence, chunk);
            case ClientboundPacket.ForgetLevelChunk forget -> {
                Column dropped = storage == null ? null : storage.drop(forget.x(), forget.z());
                change(sequence, (long) forget.x() << 4, Long.MIN_VALUE, (long) forget.z() << 4, ((long) forget.x() << 4) + 15, Long.MAX_VALUE,
                        ((long) forget.z() << 4) + 15);
                if (dropped != null) {
                    drop(dropped);
                    pend(sequence, Pending.CHUNK, chunkKey(forget.x(), forget.z()));
                }
            }
            case ClientboundPacket.BlockUpdate update -> {
                BlockPosition p = update.position();
                setBlocks(sequence, p.x() >> 4, p.y() >> 4, p.z() >> 4, new int[] {Section.index(p.x(), p.y(), p.z())}, new int[] {update.state()});
            }
            case ClientboundPacket.SectionBlocksUpdate update -> {
                int[] indices = new int[update.size()];
                int[] values = new int[update.size()];
                for (int i = 0; i < update.size(); i++) {
                    long entry = update.entry(i);
                    int local = (int) (entry & 0xFFF); // x << 8 | z << 4 | y on the wire
                    indices[i] = Section.index(local >>> 8 & 15, local & 15, local >>> 4 & 15);
                    values[i] = (int) (entry >>> 12);
                }
                setBlocks(sequence, update.sectionX(), update.sectionY(), update.sectionZ(), indices, values);
            }
            case ClientboundPacket.BlockChangedAck ack -> pendingBlockAcks.add(new long[] {sequence, ack.sequence()});
            case ClientboundPacket.Untranslatable untranslatable when isWorldPacket(untranslatable.type()) -> {
                markAllStale();
                dropChanges(sequence);
            }
            default -> { }
        }
    }

    private void onInbound(long eventSequence, ServerboundPacket packet) {
        switch (packet) {
            case ServerboundPacket.UseItemOn use -> {
                BlockPosition p = use.position();
                predict(eventSequence, p.x(), p.y(), p.z(), use.sequence(), false);
                BlockFace face = use.face();
                predict(eventSequence, p.x() + face.stepX(), p.y() + face.stepY(), p.z() + face.stepZ(), use.sequence(), true);
            }
            // ABORT_DESTROY_BLOCK predicts nothing: MultiPlayerGameMode sends it outside startPrediction and changes no block
            // (only the crack overlay), and the server's handleBlockBreakAction changes none either.
            case ServerboundPacket.PlayerAction action when action.action() == ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK
                    || action.action() == ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK ->
                    predict(eventSequence, action.position().x(), action.position().y(), action.position().z(), action.sequence(), false);
            // A plain item use (buckets, lily pads...) may place a block where the client's ray hits, which only physics can locate.
            case ServerboundPacket.UseItem use -> {
                unlocatedPredictions.add(use.sequence());
                changeEverywhere(eventSequence);
            }
            default -> { }
        }
    }

    private void onAcknowledged(long eventSequence, int transactionId) {
        Long pingSeq = pingSequence.remove(transactionId);
        if (pingSeq == null) {
            return;
        }
        // The client processed everything sent before that ping.
        while (!pending.isEmpty() && pending.peekFirst().sequence < pingSeq) {
            Pending p = pending.pollFirst();
            Map<Long, Integer> counts = p.kind == Pending.BLOCK ? unsettledBlocks : unsettledChunks;
            counts.computeIfPresent(p.key, (k, n) -> n > 1 ? n - 1 : null);
            if (p.kind == Pending.BLOCK) {
                previousStates.computeIfPresent(p.key, (k, before) -> {
                    before.pollFirst();
                    return before.isEmpty() ? null : before;
                });
            }
        }
        pendingViews.removeIf(view -> view.sequence < pingSeq);
        while (!pendingBlockAcks.isEmpty() && pendingBlockAcks.peekFirst()[0] < pingSeq) {
            confirmedBlockAck = (int) Math.max(confirmedBlockAck, pendingBlockAcks.pollFirst()[1]);
        }
        if (confirmedBlockAck != Integer.MIN_VALUE) {
            int acked = confirmedBlockAck;
            for (Map.Entry<Long, Integer> prediction : predictions.entrySet()) {
                if (prediction.getValue() <= acked) {
                    // the client puts the server's state back where it predicted: a change, recorded no earlier than it happened
                    long key = prediction.getKey();
                    change(eventSequence, blockX(key), blockY(key), blockZ(key), blockX(key), blockY(key), blockZ(key));
                }
            }
            predictions.values().removeIf(sequence -> sequence <= acked);
            earliestPredictions.keySet().retainAll(predictions.keySet());
            earliestPredictions.replaceAll((key, sequence) -> sequence <= acked ? acked + 1 : sequence); // some later one is left
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
        dropAll();
        storage = new Storage(Storage.range(chunkRadius));
        // Everything known about the old world is gone; what is in flight belongs to the new one.
        unsettledBlocks.clear();
        previousStates.clear();
        unsettledChunks.clear();
        pending.clear();
        pendingViews.clear();
        // A new ClientLevel has a new BlockStatePredictionHandler: its block-action sequence starts again.
        predictions.clear();
        earliestPredictions.clear();
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
                Section[] decoded = ChunkDecoder.decode(chunk.body(), sections, states);
                for (int i = 0; i < decoded.length; i++) {
                    decoded[i] = pool.intern(decoded[i]);
                }
                column.sections = decoded;
                chunksLoaded++;
            } catch (ChunkDecoder.FormatException e) {
                column.stale = true; // the client fails on such a chunk; WhyNotAC knows nothing about it
                chunksUnreadable++;
            }
        }
        change(sequence, (long) chunk.x() << 4, Long.MIN_VALUE, (long) chunk.z() << 4, ((long) chunk.x() << 4) + 15, Long.MAX_VALUE,
                ((long) chunk.z() << 4) + 15);
        Column replaced = storage.replace(column);
        if (replaced != null) {
            drop(replaced);
            // whatever moved in the old chunk stops when the client processes this one
            moverChange(sequence, (long) chunk.x() << 4, minY, (long) chunk.z() << 4, ((long) chunk.x() << 4) + 15, minY + sections * 16L - 1,
                    ((long) chunk.z() << 4) + 15);
        }
        pend(sequence, Pending.CHUNK, chunkKey(chunk.x(), chunk.z()));
    }

    /**
     * Applies block changes within one section, as {@code setServerVerifiedBlockState} would: ignored
     * outside the build height and in chunks the client does not hold.
     */
    private void setBlocks(long sequence, int sectionX, int sectionY, int sectionZ, int[] indices, int[] values) {
        if (storage == null || indices.length == 0) {
            return;
        }
        int sectionIndex = sectionY - (minY >> 4);
        if (sectionIndex < 0 || sectionIndex >= sections) {
            return; // the client ignores a block outside its build height
        }
        Column column = storage.get(sectionX, sectionZ);
        if (column == null) {
            return; // the client ignores a block in a chunk it does not hold
        }
        blockUpdates += indices.length;
        int[] befores = new int[indices.length];
        long[] box = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        for (int i = 0; i < indices.length; i++) {
            long bx = sectionX << 4 | indices[i] & 15;
            long by = sectionY << 4 | indices[i] >>> 8;
            long bz = sectionZ << 4 | indices[i] >>> 4 & 15;
            box[0] = Math.min(box[0], bx);
            box[1] = Math.min(box[1], by);
            box[2] = Math.min(box[2], bz);
            box[3] = Math.max(box[3], bx);
            box[4] = Math.max(box[4], by);
            box[5] = Math.max(box[5], bz);
            int before = column.sections != null ? column.sections[sectionIndex].get(indices[i]) : Section.INVALID;
            befores[i] = before;
            if (mover(before) || mover(values[i])) {
                long x = sectionX << 4 | indices[i] & 15;
                long y = sectionY << 4 | indices[i] >>> 8;
                long z = sectionZ << 4 | indices[i] >>> 4 & 15;
                moverChange(sequence, x, y, z, x, y, z);
            }
        }
        change(sequence, box[0], box[1], box[2], box[3], box[4], box[5]);
        if (column.sections != null) {
            // A new section, shared again: every player near the change applies it to the same section.
            Section old = column.sections[sectionIndex];
            Section changed = pool.intern(old.with(indices, values, states.globalPaletteBits(), states.size()));
            pool.release(old);
            column.sections[sectionIndex] = changed;
        }
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            long key = blockKey(sectionX << 4 | index & 15, sectionY << 4 | index >>> 8, sectionZ << 4 | index >>> 4 & 15);
            pend(sequence, Pending.BLOCK, key);
            previousStates.computeIfAbsent(key, k -> new ArrayDeque<>(1)).add(befores[i]);
        }
    }

    private void predict(long eventSequence, int x, int y, int z, int sequence, boolean withNeighbours) {
        int[][] offsets = withNeighbours ? PREDICTION_NEIGHBOURS : PREDICTION_SELF;
        for (int[] o : offsets) {
            change(eventSequence, x + o[0], y + o[1], z + o[2], x + o[0], y + o[1], z + o[2]);
            predictions.merge(blockKey(x + o[0], y + o[1], z + o[2]), sequence, Math::max);
            earliestPredictions.merge(blockKey(x + o[0], y + o[1], z + o[2]), sequence, Math::min);
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

    /**
     * Every state the client may hold at the block now: the one {@link #blockState} gives and, for each change sent that
     * it may not have processed yet, the one before it. Null where not known that way: the chunk may still change, the
     * client's own prediction may hold it, or a state is not known.
     */
    public int[] possibleStates(int x, int y, int z) {
        return possibleStatesBefore(x, y, z, Integer.MAX_VALUE);
    }

    /**
     * As {@link #possibleStates}, for the client before its block action {@code sequence} and the later ones: what its
     * pick saw when it made that action (the client predicts its own action's changes after the pick). Null also where an
     * earlier own prediction may hold the block.
     */
    public int[] possibleStatesBefore(int x, int y, int z, int sequence) {
        int now = blockState(x, y, z);
        if (now == UNKNOWN) {
            return null;
        }
        long key = blockKey(x, y, z);
        Integer earliest = earliestPredictions.get(key);
        if (earliest != null && earliest < sequence) {
            return null;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (unsettledChunks.containsKey(chunkKey(cx, cz))) {
            return null;
        }
        for (View view : pendingViews) {
            if (view.inRange(cx, cz) != storage.inRange(cx, cz)) {
                return null;
            }
        }
        if (!unsettledBlocks.containsKey(key)) {
            return new int[] {now};
        }
        ArrayDeque<Integer> before = previousStates.get(key);
        if (before == null) {
            return null;
        }
        int[] out = new int[before.size() + 1];
        int i = 0;
        for (int state : before) {
            if (state == Section.INVALID || state == UNKNOWN) {
                return null;
            }
            out[i++] = state;
        }
        out[i] = now;
        return out;
    }

    /** Whether the client may hold blocks of an item use before {@code sequence} that WhyNotAC cannot locate. */
    public boolean hasUnlocatedPredictionBefore(int sequence) {
        return !unlocatedPredictions.isEmpty() && unlocatedPredictions.peekFirst() < sequence;
    }

    /**
     * The smallest {@code t} below {@code maxT} at which the crosshair's ray along {@code (dx, dy, dz)} from any point of
     * the box ({@code min} to {@code max}) is certainly stopped by a block of the client's world; positive infinity if
     * none certainly stops it before {@code maxT}.
     *
     * <p>The client ({@code GameRenderer.pick}: {@code Level.clip} with {@code ClipContext.Block.OUTLINE},
     * {@code BlockGetter.traverseBlocks}, decompiled 1.21.11) walks the cells the ray passes through in order, and stops
     * at the first whose outline shape the ray meets anywhere on its segment ({@code AABB.clip}: where it enters a box
     * from outside). A cell stops every ray here at {@code t} when every state the client may hold there has an outline
     * box that the ray from the box's centre enters at {@code t} once the outline box is shrunk by the box's half size
     * plus {@link #OUTLINE_MARGIN} (every ray is then that deep in the outline box at {@code t}) and that no ray starts in;
     * and no cell some ray comes near before can hold a state that is not known or whose outline may leave its cell
     * ({@link BlockStates#outlineInCell}): where the client meets an outline inside its cell, it is before this one. The
     * outline of an empty world and context is never larger than the client's (see {@link BlockStates}). Nothing while
     * an item use may have changed blocks WhyNotAC cannot locate ({@link #hasUnlocatedPrediction}).
     */
    public double certainOutlineHit(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double dx, double dy, double dz,
            double maxT) {
        return certainOutlineHitBefore(minX, minY, minZ, maxX, maxY, maxZ, dx, dy, dz, maxT, Integer.MAX_VALUE);
    }

    /** As {@link #certainOutlineHit}, in the world the client's pick saw before its block action {@code sequence} ({@link #possibleStatesBefore}). */
    public double certainOutlineHitBefore(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double dx, double dy,
            double dz, double maxT, int sequence) {
        if (storage == null || !(maxT > 0.0) || hasUnlocatedPredictionBefore(sequence)) {
            return Double.POSITIVE_INFINITY;
        }
        double x = (minX + maxX) / 2.0;
        double y = (minY + maxY) / 2.0;
        double z = (minZ + maxZ) / 2.0;
        double padX = (maxX - minX) / 2.0 + OUTLINE_MARGIN;
        double padY = (maxY - minY) / 2.0 + OUTLINE_MARGIN;
        double padZ = (maxZ - minZ) / 2.0 + OUTLINE_MARGIN;
        double ex = x + dx * maxT;
        double ey = y + dy * maxT;
        double ez = z + dz * maxT;
        int fromX = (int) Math.floor(Math.min(x, ex) - padX);
        int fromY = (int) Math.floor(Math.min(y, ey) - padY);
        int fromZ = (int) Math.floor(Math.min(z, ez) - padZ);
        int toX = (int) Math.floor(Math.max(x, ex) + padX);
        int toY = (int) Math.floor(Math.max(y, ey) + padY);
        int toZ = (int) Math.floor(Math.max(z, ez) + padZ);
        if ((long) (toX - fromX + 1) * (toY - fromY + 1) * (toZ - fromZ + 1) > MAX_OUTLINE_CELLS) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        double unsure = Double.POSITIVE_INFINITY; // where a ray first comes near a cell that may not stop it inside the cell
        for (int cx = fromX; cx <= toX; cx++) {
            for (int cy = fromY; cy <= toY; cy++) {
                for (int cz = fromZ; cz <= toZ; cz++) {
                    double near = enter(x, y, z, dx, dy, dz, cx - padX, cy - padY, cz - padZ, cx + 1 + padX, cy + 1 + padY, cz + 1 + padZ);
                    if (!(near < maxT) || near >= Math.min(best, unsure)) {
                        continue;
                    }
                    int[] possible = possibleStatesBefore(cx, cy, cz, sequence);
                    double cell = possible == null ? Double.NaN : Double.NEGATIVE_INFINITY;
                    for (int i = 0; possible != null && i < possible.length; i++) {
                        int state = possible[i];
                        if (!states.isValid(state) || !states.outlineInCell(state)) {
                            cell = Double.NaN;
                            break;
                        }
                        Shape outline = states.outlineShape(state);
                        double hit = Double.POSITIVE_INFINITY;
                        for (int b = 0; b < outline.boxCount(); b++) {
                            double x0 = cx + outline.box(b, 0);
                            double y0 = cy + outline.box(b, 1);
                            double z0 = cz + outline.box(b, 2);
                            double x1 = cx + outline.box(b, 3);
                            double y1 = cy + outline.box(b, 4);
                            double z1 = cz + outline.box(b, 5);
                            if (x0 - padX <= x && x <= x1 + padX && y0 - padY <= y && y <= y1 + padY && z0 - padZ <= z && z <= z1 + padZ) {
                                continue; // a ray may start in it: the client's clip does not meet a box from inside
                            }
                            hit = Math.min(hit, enter(x, y, z, dx, dy, dz, x0 + padX, y0 + padY, z0 + padZ, x1 - padX, y1 - padY, z1 - padZ));
                        }
                        cell = Math.max(cell, hit); // every state the client may hold must stop it
                    }
                    if (Double.isNaN(cell)) {
                        unsure = Math.min(unsure, near);
                    } else if (cell < maxT) {
                        best = Math.min(best, cell);
                    }
                }
            }
        }
        return best < unsure ? best : Double.POSITIVE_INFINITY;
    }

    /** Outline boxes are shrunk and cells grown by this beyond the rays' spread: the client's own rounding. */
    static final double OUTLINE_MARGIN = 1.0E-6;
    /** Cells {@link #certainOutlineHit} looks at, at most (a crosshair ray is a few blocks long). */
    static final int MAX_OUTLINE_CELLS = 4096;

    /**
     * Where the ray from {@code (x, y, z)} along {@code (dx, dy, dz)} enters the box ({@code t >= 0}, 0 if it starts
     * inside); positive infinity if it never does, or the box is empty.
     */
    static double enter(double x, double y, double z, double dx, double dy, double dz, double minX, double minY, double minZ, double maxX,
            double maxY, double maxZ) {
        if (!(minX < maxX && minY < maxY && minZ < maxZ)) {
            return Double.POSITIVE_INFINITY;
        }
        double[] range = {0.0, Double.POSITIVE_INFINITY};
        return slab(x, dx, minX, maxX, range) && slab(y, dy, minY, maxY, range) && slab(z, dz, minZ, maxZ, range) ? range[0]
                : Double.POSITIVE_INFINITY;
    }

    private static boolean slab(double origin, double d, double min, double max, double[] range) {
        if (d == 0.0) {
            return min <= origin && origin <= max;
        }
        double t1 = (min - origin) / d;
        double t2 = (max - origin) / d;
        range[0] = Math.max(range[0], Math.min(t1, t2));
        range[1] = Math.min(range[1], Math.max(t1, t2));
        return range[0] <= range[1];
    }

    /**
     * Whether a block that moves entities on the client may be in the box (block coordinates, inclusive):
     * one is there once the client processed everything sent so far, or a block there is not known, or one
     * changed to or from such a block (or its chunk was replaced) at or after {@code since}, so the client
     * may have had it then.
     */
    public boolean mayMoveEntitiesIn(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long since) {
        if (storage == null || since <= moverChangesDropped) {
            return true;
        }
        for (java.util.Iterator<long[]> it = moverChanges.descendingIterator(); it.hasNext(); ) {
            long[] c = it.next();
            if (c[0] < since) {
                break;
            }
            if (c[1] <= maxX && c[4] >= minX && c[2] <= maxY && c[5] >= minY && c[3] <= maxZ && c[6] >= minZ) {
                return true;
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    int state = blockState(x, y, z);
                    if (state == UNKNOWN || mover(state)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether the blocks the client holds in the box (block coordinates, inclusive) may have changed at or after
     * {@code since}: a change was sent or predicted there since (see {@link #changes}), or a block there is not settled
     * now ({@link #settled}). True where that is not known: changes dropped since, no level.
     */
    public boolean mayHaveChangedIn(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long since) {
        if (storage == null || since <= changesDropped || hasUnlocatedPrediction()) {
            return true;
        }
        for (java.util.Iterator<long[]> it = changes.descendingIterator(); it.hasNext(); ) {
            long[] c = it.next();
            if (c[0] < since) {
                break;
            }
            if (c[1] <= maxX && c[4] >= minX && c[2] <= maxY && c[5] >= minY && c[3] <= maxZ && c[6] >= minZ) {
                return true;
            }
        }
        for (java.util.Iterator<long[]> it = viewChanges.descendingIterator(); it.hasNext(); ) {
            long[] v = it.next();
            if (v[0] < since) {
                break;
            }
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    boolean before = Math.abs(cx - v[1]) <= v[3] && Math.abs(cz - v[2]) <= v[3];
                    boolean after = Math.abs(cx - v[4]) <= v[6] && Math.abs(cz - v[5]) <= v[6];
                    if (before != after) {
                        return true; // the chunk was hidden or shown
                    }
                }
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (!settled(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        if (storage != null) {
            storage.forEach(column -> {
                loaded[0]++;
                if (column.stale) {
                    stale[0]++;
                }
            });
        }
        return new Stats(loaded[0], stale[0], chunksLoaded, chunksIgnored, chunksUnreadable, blockUpdates, unsettledBlocks.size(),
                unsettledChunks.size(), predictions.size(), staleEvents);
    }

    public record Stats(long chunksHeld, long chunksStale, long chunksReceived, long chunksIgnored, long chunksUnreadable, long blockUpdates,
            int unsettledBlocks, int unsettledChunks, int predictions, long staleEvents) {}

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

    /** {@code BlockPos.getX}, {@code getY}, {@code getZ} of a {@link #blockKey}. */
    static int blockX(long key) {
        return (int) (key >> 38);
    }

    static int blockY(long key) {
        return (int) (key << 52 >> 52);
    }

    static int blockZ(long key) {
        return (int) (key << 26 >> 38);
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

        /** Puts the column in its slot; returns what occupied it. */
        Column replace(Column column) {
            int index = index(column.x, column.z);
            Column previous = slots[index];
            slots[index] = column;
            return previous;
        }

        /** {@code drop}: the dropped chunk, or null. */
        Column drop(int x, int z) {
            if (!inRange(x, z)) {
                return null;
            }
            int index = index(x, z);
            Column column = slots[index];
            if (column != null && column.x == x && column.z == z) {
                slots[index] = null;
                return column;
            }
            return null;
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
