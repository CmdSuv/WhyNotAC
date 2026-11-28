package dev.whynotac.world;

import dev.whynotac.math.vanilla.VanillaMath;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PingLedger;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
    /** In {@link #resolvedUses}: the use set no block there. */
    private static final int KEPT = Integer.MIN_VALUE;
    /** In {@link #resolvedUses}: a shape update may have given the block there any of its own states ({@link BlockStates#UPDATE_KEEPS_BLOCK}). */
    private static final int SAME_BLOCK = Integer.MIN_VALUE + 1;
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
    private final PingLedger ledger;
    private final boolean sharedLedger;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final LongCounterMap unsettledBlocks = new LongCounterMap();
    /** For each unsettled block, the state before each of its pending changes, oldest first (INVALID: not known). */
    private final Map<Long, ArrayDeque<Integer>> previousStates = new HashMap<>();
    private final LongCounterMap unsettledChunks = new LongCounterMap();
    private final List<View> pendingViews = new ArrayList<>();
    /** Client predictions by position: the highest block-action sequence that touched it. */
    private final Map<Long, Integer> predictions = new HashMap<>();
    /** The lowest block-action sequence that may still hold each position in {@link #predictions}. */
    private final Map<Long, Integer> earliestPredictions = new HashMap<>();
    /** Per position in {@link #predictions}: the block-action sequence of each prediction still outstanding there, in the order made. */
    private final Map<Long, int[]> predictionSequences = new HashMap<>();
    /** The block uses whose predictions {@link #resolveUse} may resolve, by block-action sequence. */
    private final Map<Integer, ServerboundPacket.UseItemOn> uses = new HashMap<>();
    /**
     * Per such use, what the client may hold before it, taken when it came ({@link #possibleStatesBefore} then: nothing the
     * server sent after it can have reached the client before it): the clicked position, the one on the clicked face, and
     * that one's neighbours ({@link #PREDICTION_NEIGHBOURS} from the second); null where not known.
     */
    private final Map<Integer, int[][]> useBefore = new HashMap<>();
    /**
     * Resolved uses ({@link #resolveUse}): block-action sequence -> position -> the state the client set there,
     * {@link #KEPT} where it set none.
     */
    private final Map<Integer, Map<Long, Integer>> resolvedUses = new HashMap<>();
    /**
     * The digs ({@code START_DESTROY_BLOCK}, {@code STOP_DESTROY_BLOCK}) not yet seen acknowledged, by block-action
     * sequence: whether it started one, and what the client may have held at the dug position before it, taken when it
     * came (null: not known).
     */
    private final Map<Integer, Dig> digs = new HashMap<>();

    private record Dig(boolean start, BlockPosition position, int[] before) {}
    /** Block-action sequences of item uses whose block changes (if any) WhyNotAC cannot locate. */
    private final ArrayDeque<Integer> unlocatedPredictions = new ArrayDeque<>();
    /** Changes-queue marker added for each unlocated {@link ServerboundPacket.UseItem}, by its block-action sequence. */
    private final Map<Integer, long[]> unlocatedUseChanges = new HashMap<>();
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
    private long lastEventSequence = Long.MIN_VALUE;

    public ClientWorld(BlockStates states) {
        this(states, new SectionPool());
    }

    /** {@code pool} is shared with the other worlds, so identical sections are stored once. */
    public ClientWorld(BlockStates states, SectionPool pool) {
        this(states, pool, null);
    }

    ClientWorld(BlockStates states, SectionPool pool, PingLedger sharedLedger) {
        this.states = states;
        this.voidAir = states.firstStateOf("minecraft:void_air");
        this.pool = pool;
        this.movers = movers(states);
        this.sharedLedger = sharedLedger != null;
        this.ledger = sharedLedger != null ? sharedLedger : new PingLedger();
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

    private long[] changeEverywhere(long sequence) {
        long[] entry = new long[] {sequence, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        changes.add(entry);
        if (changes.size() > MAX_CHANGES) {
            changesDropped = Math.max(changesDropped, changes.pollFirst()[0]);
        }
        return entry;
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
        unlocatedUseChanges.clear();
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
        if (event.sequence() > lastEventSequence) {
            lastEventSequence = event.sequence();
        }
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
        if (!sharedLedger) {
            ledger.clear();
        }
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
            case ClientboundPacket.Ping ping -> {
                if (!sharedLedger) {
                    ledger.onPing(ping.id(), sequence);
                }
            }
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
                forgetResolvedIn(forget.x(), forget.z());
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
                uses.put(use.sequence(), use);
                useBefore.put(use.sequence(), before(use));
                BlockPosition p = use.position();
                predict(eventSequence, p.x(), p.y(), p.z(), use.sequence(), false);
                BlockFace face = use.face();
                predict(eventSequence, p.x() + face.stepX(), p.y() + face.stepY(), p.z() + face.stepZ(), use.sequence(), true);
            }
            // ABORT_DESTROY_BLOCK predicts nothing: MultiPlayerGameMode sends it outside startPrediction and changes no block
            // (only the crack overlay), and the server's handleBlockBreakAction changes none either. A dig that breaks the block
            // (destroyBlock: setBlock(pos, fluid.createLegacyBlock(), 11)) runs the neighbour and shape updates on the client
            // too, inside the prediction: what that changes next to it the client holds until the ack, like the block itself.
            case ServerboundPacket.PlayerAction action when action.action() == ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK
                    || action.action() == ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK -> {
                BlockPosition p = action.position();
                int[][] before = around(p.x(), p.y(), p.z(), action.sequence());
                predict(eventSequence, p.x(), p.y(), p.z(), action.sequence(), true);
                digs.put(action.sequence(), new Dig(action.action() == ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, p,
                        before == null ? null : before[0]));
                Map<Long, Integer> cells = digNeighbours(p, action.sequence(), before);
                if (cells != null) {
                    resolvedUses.put(action.sequence(), cells);
                }
            }
            // A plain item use (buckets, lily pads...) may place a block where the client's ray hits, which only physics can locate.
            case ServerboundPacket.UseItem use -> {
                unlocatedPredictions.add(use.sequence());
                unlocatedUseChanges.put(use.sequence(), changeEverywhere(eventSequence));
            }
            default -> { }
        }
    }

    private void onAcknowledged(long eventSequence, int transactionId) {
        long pingSeq = sharedLedger ? ledger.lastMatched() : ledger.onAcknowledged(transactionId);
        if (pingSeq == Long.MIN_VALUE) {
            return;
        }
        // The client processed everything sent before that ping.
        while (!pending.isEmpty() && pending.peekFirst().sequence < pingSeq) {
            Pending p = pending.pollFirst();
            LongCounterMap counts = p.kind == Pending.BLOCK ? unsettledBlocks : unsettledChunks;
            counts.decrement(p.key);
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
            predictionSequences.keySet().retainAll(predictions.keySet());
            predictionSequences.replaceAll((key, sequences) -> after(sequences, acked));
            uses.keySet().removeIf(sequence -> sequence <= acked);
            useBefore.keySet().removeIf(sequence -> sequence <= acked);
            resolvedUses.keySet().removeIf(sequence -> sequence <= acked);
            digs.keySet().removeIf(sequence -> sequence <= acked);
            earliestPredictions.replaceAll((key, sequence) -> sequence <= acked ? acked + 1 : sequence); // some later one is left
            while (!unlocatedPredictions.isEmpty() && unlocatedPredictions.peekFirst() <= acked) {
                unlocatedPredictions.pollFirst();
            }
            unlocatedUseChanges.keySet().removeIf(seq -> seq <= acked);
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
        predictionSequences.clear();
        uses.clear();
        useBefore.clear();
        resolvedUses.clear();
        digs.clear();
        unlocatedPredictions.clear();
        unlocatedUseChanges.clear();
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
        forgetResolvedIn(chunk.x(), chunk.z());
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
            int px = x + o[0];
            int py = y + o[1];
            int pz = z + o[2];
            change(eventSequence, px, py, pz, px, py, pz);
            addPredictionCell(px, py, pz, sequence);
        }
    }

    private void addPredictionCell(int x, int y, int z, int sequence) {
        long key = blockKey(x, y, z);
        predictions.merge(key, sequence, Math::max);
        earliestPredictions.merge(key, sequence, Math::min);
        int[] sequences = predictionSequences.get(key);
        if (sequences == null) {
            predictionSequences.put(key, new int[] {sequence});
        } else if (sequences[sequences.length - 1] != sequence) {
            int[] more = Arrays.copyOf(sequences, sequences.length + 1);
            more[sequences.length] = sequence;
            predictionSequences.put(key, more);
        }
    }

    /** {@link #useBefore} for a use as it comes. */
    private int[][] before(ServerboundPacket.UseItemOn use) {
        if (storage == null) {
            return null;
        }
        BlockPosition c = use.position();
        BlockFace face = use.face();
        int[][] out = new int[PREDICTION_NEIGHBOURS.length + 1][];
        out[0] = possibleStatesBefore(c.x(), c.y(), c.z(), use.sequence());
        for (int i = 0; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] o = PREDICTION_NEIGHBOURS[i];
            out[i + 1] = possibleStatesBefore(c.x() + face.stepX() + o[0], c.y() + face.stepY() + o[1], c.z() + face.stepZ() + o[2], use.sequence());
        }
        return out;
    }

    /** What the client may hold before block action {@code sequence} at a position and its neighbours ({@link #PREDICTION_NEIGHBOURS}). */
    private int[][] around(int x, int y, int z, int sequence) {
        if (storage == null) {
            return null;
        }
        int[][] out = new int[PREDICTION_NEIGHBOURS.length][];
        for (int i = 0; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] o = PREDICTION_NEIGHBOURS[i];
            out[i] = possibleStatesBefore(x + o[0], y + o[1], z + o[2], sequence);
        }
        return out;
    }

    /**
     * What a dig at {@code p} did next to it, as far as the blocks there decide it: the dug block's own {@code setBlock}
     * updates its neighbours ({@code updateNeighborsAt}, {@code updateNeighbourShapes}), so one keeps its state
     * ({@link #KEPT}) unless a state it may hold reacts ({@link #reacts}: not resolved there) or keeps its block
     * ({@link #SAME_BLOCK}). The dug position itself is not resolved here ({@link #resolveDigStartedOnly}). Null where the
     * dug block may run code of its own around the break ({@link BlockStates#DIG_HOOKED}: a door's other half, a bed's), is
     * not known, or a use the replica cannot locate comes before.
     */
    private Map<Long, Integer> digNeighbours(BlockPosition p, int sequence, int[][] before) {
        if (before == null || before[0] == null || hasUnlocatedPredictionBefore(sequence)) {
            return null;
        }
        for (int s : before[0]) {
            if (states.has(s, BlockStates.DIG_HOOKED)) {
                return null;
            }
        }
        Map<Long, Integer> cells = new HashMap<>();
        neighbours:
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] beside = before[i];
            if (beside == null) {
                continue;
            }
            int set = KEPT;
            for (int s : beside) {
                if (reacts(s)) {
                    continue neighbours;
                }
                if (states.has(s, BlockStates.NEIGHBOUR_REACTIVE)) {
                    set = SAME_BLOCK;
                }
            }
            int[] o = PREDICTION_NEIGHBOURS[i];
            cells.put(blockKey(p.x() + o[0], p.y() + o[1], p.z() + o[2]), set);
        }
        return cells;
    }

    /**
     * What the client may have held at the position of its dig {@code sequence} before it, as the replica held it when
     * the dig came ({@link #possibleStatesBefore} then); null where that is not known, or for a dig the replica did not
     * see or has seen acknowledged.
     */
    public int[] digBefore(int sequence) {
        Dig dig = digs.get(sequence);
        return dig == null ? null : dig.before;
    }

    /**
     * Resolves the client's dig {@code sequence} as one that set no block anywhere: a {@code START_DESTROY_BLOCK} whose
     * {@code startDestroyBlock} only started the dig ({@link Digging#certainlyStartsOnly}; whether the client was in
     * creative, which breaks at once, is the caller's to know). Ignored for a {@code STOP_DESTROY_BLOCK} and for a dig the
     * replica did not see or has seen acknowledged.
     */
    public void resolveDigStartedOnly(int sequence) {
        Dig dig = digs.get(sequence);
        if (dig == null || !dig.start || dig.before == null) {
            return;
        }
        // the dug position and every neighbour: nothing was set, so no update came (a reacting neighbour kept its state too)
        Map<Long, Integer> cells = new HashMap<>();
        BlockPosition p = dig.position;
        for (int[] o : PREDICTION_NEIGHBOURS) {
            cells.put(blockKey(p.x() + o[0], p.y() + o[1], p.z() + o[2]), KEPT);
        }
        resolvedUses.put(sequence, cells);
    }

    /**
     * Resolves the dug position of the client's {@code STOP_DESTROY_BLOCK} {@code sequence}: its {@code destroyBlock} set
     * {@code state} there ({@link Digging#brokenTo}; the game mode and instabuild are the caller's to know). The client
     * holds it until it processes an ack of the dig, the server's states after. Ignored for a start, for a dig the replica
     * did not see or has seen acknowledged, and where its neighbours are not resolved ({@link #digNeighbours}).
     */
    public void resolveDigBroken(int sequence, int state) {
        Dig dig = digs.get(sequence);
        Map<Long, Integer> cells = resolvedUses.get(sequence);
        if (dig == null || dig.start || dig.before == null || cells == null || !states.isValid(state)) {
            return;
        }
        BlockPosition p = dig.position;
        cells.put(blockKey(p.x(), p.y(), p.z()), state);
    }

    /**
     * Resolves the dug position of a survival {@code START_DESTROY_BLOCK} {@code sequence} whose zero-hardness block
     * broke immediately on start ({@link Digging#instantBrokenTo}): its {@code destroyBlock} set {@code state} there,
     * while {@link #digNeighbours} already bounded the six neighbours.
     */
    public void resolveDigInstantBroken(int sequence, int state) {
        Dig dig = digs.get(sequence);
        Map<Long, Integer> cells = resolvedUses.get(sequence);
        if (dig == null || !dig.start || dig.before == null || cells == null || !states.isValid(state)) {
            return;
        }
        BlockPosition p = dig.position;
        cells.put(blockKey(p.x(), p.y(), p.z()), state);
    }

    /** The sequences above {@code acked}, in their order (some are: the position still has a prediction). */
    private static int[] after(int[] sequences, int acked) {
        return Arrays.stream(sequences).filter(q -> q > acked).toArray();
    }

    /** A chunk the client replaced or dropped holds no state a use set in it any more: those positions are not resolved. */
    private void forgetResolvedIn(int chunkX, int chunkZ) {
        for (Map<Long, Integer> cells : resolvedUses.values()) {
            cells.keySet().removeIf(key -> blockX(key) >> 4 == chunkX && blockZ(key) >> 4 == chunkZ);
        }
    }

    private void viewChanged(long sequence) {
        pendingViews.add(new View(sequence, storage.centerX, storage.centerZ, storage.radius));
    }

    private void pend(long sequence, int kind, long key) {
        pending.add(new Pending(sequence, kind, key));
        (kind == Pending.BLOCK ? unsettledBlocks : unsettledChunks).increment(key);
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
        if (unsettledChunks.isEmpty() && pendingViews.isEmpty() && unsettledBlocks.isEmpty() && predictions.isEmpty()) {
            return true;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (!unsettledChunks.isEmpty() && unsettledChunks.containsKey(chunkKey(cx, cz))) {
            return false;
        }
        for (View view : pendingViews) {
            if (view.inRange(cx, cz) != storage.inRange(cx, cz)) {
                return false;
            }
        }
        if (unsettledBlocks.isEmpty() && predictions.isEmpty()) {
            return true;
        }
        long key = blockKey(x, y, z);
        return (unsettledBlocks.isEmpty() || !unsettledBlocks.containsKey(key))
                && (predictions.isEmpty() || !predictions.containsKey(key));
    }

    /**
     * Returns {@link #blockState(int, int, int)} when {@link #settled(int, int, int)} holds, else {@link #UNKNOWN},
     * performing a single chunk lookup and avoiding {@code Long} boxing on unsettled maps when empty.
     */
    public int settledBlockState(int x, int y, int z) {
        int state = blockState(x, y, z);
        if (state == UNKNOWN) {
            return UNKNOWN;
        }
        if (unsettledChunks.isEmpty() && pendingViews.isEmpty() && unsettledBlocks.isEmpty() && predictions.isEmpty()) {
            return state;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (!unsettledChunks.isEmpty() && unsettledChunks.containsKey(chunkKey(cx, cz))) {
            return UNKNOWN;
        }
        for (View view : pendingViews) {
            if (view.inRange(cx, cz) != storage.inRange(cx, cz)) {
                return UNKNOWN;
            }
        }
        if (unsettledBlocks.isEmpty() && predictions.isEmpty()) {
            return state;
        }
        long key = blockKey(x, y, z);
        if ((!unsettledBlocks.isEmpty() && unsettledBlocks.containsKey(key))
                || (!predictions.isEmpty() && predictions.containsKey(key))) {
            return UNKNOWN;
        }
        return state;
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
            return predictedBefore(x, y, z, key, sequence);
        }
        return serverStates(x, y, z, key, now);
    }

    /**
     * {@link #possibleStatesBefore} where predictions before {@code sequence} hold the position: known only where each of
     * them is resolved ({@link #resolveUse}). Where none set a state there the client holds the server's (a position the
     * client set nothing at is not held back: {@code BlockStatePredictionHandler} keeps only those it set); where one set a
     * state the client holds it until it processes an ack of that use, and the server's states after.
     */
    private int[] predictedBefore(int x, int y, int z, long key, int sequence) {
        int[] sequences = predictionSequences.get(key);
        if (sequences == null) {
            return null;
        }
        int placed = KEPT;
        int placedBy = 0;
        boolean sameBlock = false;
        for (int q : sequences) {
            if (q >= sequence) {
                continue; // made after the pick of that action (a vanilla client's sequences only grow)
            }
            Map<Long, Integer> cells = resolvedUses.get(q);
            Integer set = cells == null ? null : cells.get(key);
            if (set == null) {
                return null;
            }
            if (set == SAME_BLOCK) {
                sameBlock = true;
            } else if (set != KEPT) {
                if (placed != KEPT) {
                    return null; // set twice: the second may come before or after the ack of the first
                }
                placed = set;
                placedBy = q;
            }
        }
        int[] out = placedStates(x, y, z, key, placed, placedBy);
        return out == null || !sameBlock ? out : everyStateOfTheirBlocks(out);
    }

    /** Each state given, and every other of its block. */
    private int[] everyStateOfTheirBlocks(int[] given) {
        java.util.TreeSet<Integer> all = new java.util.TreeSet<>();
        for (int s : given) {
            for (int t : states.statesOfBlock(s)) {
                all.add(t);
            }
        }
        return all.stream().mapToInt(Integer::intValue).toArray();
    }

    /** {@link #predictedBefore} for the state a resolved use set ({@link #KEPT}: none). */
    private int[] placedStates(int x, int y, int z, long key, int placed, int placedBy) {
        int now = blockState(x, y, z);
        if (placed == KEPT) {
            return serverStates(x, y, z, key, now);
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
        for (long[] ack : pendingBlockAcks) {
            if (ack[1] >= placedBy) {
                // the client may have processed it: then it holds the server's state again, as of that ack or later
                int[] server = serverStates(x, y, z, key, now, ack[0]);
                if (server == null) {
                    return null;
                }
                int[] out = Arrays.copyOf(server, server.length + 1);
                out[server.length] = placed;
                return out;
            }
        }
        return new int[] {placed};
    }

    /** {@link #possibleStatesBefore} where no prediction of the client's holds the position. */
    private int[] serverStates(int x, int y, int z, long key, int now) {
        return serverStates(x, y, z, key, now, Long.MIN_VALUE);
    }

    /** As {@link #serverStates(int, int, int, long, int)} once the client processed everything sent before {@code processed}. */
    private int[] serverStates(int x, int y, int z, long key, int now, long processed) {
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
        int skip = 0; // the states before changes the client processed: no longer possible
        if (processed != Long.MIN_VALUE) {
            for (Pending p : pending) {
                if (p.sequence >= processed) {
                    break;
                }
                if (p.kind == Pending.BLOCK && p.key == key) {
                    skip++;
                }
            }
        }
        int[] out = new int[before.size() - skip + 1];
        int i = 0;
        for (int state : before) {
            if (skip > 0) {
                skip--;
                continue;
            }
            if (state == Section.INVALID || state == UNKNOWN) {
                return null;
            }
            out[i++] = state;
        }
        out[i] = now;
        return out;
    }

    /**
     * Where the client's block use {@code sequence} places {@code state}, if its hand holds a block item that places
     * nothing but that state ({@code ItemPrototypes.Prototype#places}, a block of one state) and the blocks alone decide
     * it (decompiled 1.21.11: {@code MultiPlayerGameMode.performUseItemOn}, {@code BlockItem.place}, {@code Level.setBlock}).
     * In every state the client may hold before the use (as the replica held it when the use came): the clicked block takes no part ({@link BlockStates#INTERACTIVE}:
     * its {@code useItemOn} and {@code useWithoutItem} are the defaults, which pass) and is not replaced (neither
     * {@link BlockStates#REPLACEABLE} nor {@link BlockStates#CUSTOM_REPLACING}); the block on the clicked face is (it is
     * REPLACEABLE without a rule of its own and is not of the placed block); the placed state is placed as it is
     * (not {@link BlockStates#CUSTOM_PLACEMENT}); neither it nor the replaced one changes the blocks diagonal to it, and
     * no block next to it reacts ({@link BlockStates#NEIGHBOUR_REACTIVE}) unless its update keeps the block
     * ({@link BlockStates#UPDATE_KEEPS_BLOCK}: grass, sand): {@code setBlock} then changes the one position, and those
     * neighbours at most within their block.
     * The position is within the level's height, no use the replica cannot locate comes before, and the use is one this
     * replica saw and has not seen acknowledged. Null where any of that is not known. What else stops the placement (an
     * entity or the player in the way: {@code Level.isUnobstructed}) is the caller's to know; it says what happened
     * with {@link #resolveUse}.
     */
    public record Placement(BlockPosition position, int state) {}

    /**
     * Exact multi-cell outcome of a resolved block use: the primary placed/toggled cell plus any secondary cells
     * atomically updated by the same client prediction (connected fence/wall/pane/stair neighbours, door upper half,
     * or toggled door partner half).
     */
    public record PlacementPlan(Placement primary, List<Placement> extras) {
        public PlacementPlan {
            if (primary == null) {
                throw new IllegalArgumentException("primary placement must not be null");
            }
            extras = extras == null || extras.isEmpty() ? List.of() : List.copyOf(extras);
        }

        public static PlacementPlan single(BlockPosition position, int state) {
            return new PlacementPlan(new Placement(position, state), List.of());
        }
    }

    /**
     * Resolves exact placement position and block state for single-state full cubes, directional
     * pillars ({@code axis=x|y|z}), slabs, stairs, connected fences/walls/panes, doors, fence gates, snow layers,
     * carpets, lanterns, and trapdoors.
     */
    public Placement placementForItem(int sequence, String placedBlockKey) {
        return placementForItem(sequence, placedBlockKey, null);
    }

    /**
     * Resolves exact primary placement position and block state including directional blocks when the
     * caller supplies the client's unanimous horizontal facing ({@code "north"}, {@code "south"}, {@code "east"},
     * {@code "west"}) strictly clear of 45-degree diagonal corners.
     */
    public Placement placementForItem(int sequence, String placedBlockKey, String horizontalFacing) {
        PlacementPlan plan = placementPlanForItem(sequence, placedBlockKey, horizontalFacing);
        return plan == null ? null : plan.primary();
    }

    /**
     * Resolves exact multi-cell placement plan (primary cell + exact secondary neighbour / upper-half updates)
     * for {@link ServerboundPacket.UseItemOn} {@code sequence}, returning {@code null} whenever any neighbour,
     * fluid, redstone source, or placement rule leaves ambiguity.
     */
    public PlacementPlan placementPlanForItem(int sequence, String placedBlockKey, String horizontalFacing) {
        if (placedBlockKey == null || storage == null || hasUnlocatedPredictionBefore(sequence)) {
            return null;
        }
        int single = states.onlyStateOf(placedBlockKey);
        if (single >= 0) {
            BlockPosition pos = placement(sequence, single);
            if (pos != null) {
                return PlacementPlan.single(pos, single);
            }
        }
        ServerboundPacket.UseItemOn use = uses.get(sequence);
        if (use == null) {
            return null;
        }
        BlockPosition c = use.position();
        BlockFace face = use.face();
        int tx = c.x() + face.stepX();
        int ty = c.y() + face.stepY();
        int tz = c.z() + face.stepZ();
        int[][] before = useBefore.get(sequence);
        int[] clicked = before == null ? null : before[0];
        int[] target = before == null ? null : before[1];
        if (clicked == null || target == null) {
            return null;
        }
        // Variant B2: Merging same-type non-waterlogged SlabBlock into type=double at the clicked block itself
        if (placedBlockKey.endsWith("_slab") && !use.inside()
                && (face == BlockFace.UP || face == BlockFace.DOWN)
                && c.y() >= minY() && c.y() < minY() + height()) {
            String requiredHalf = face == BlockFace.UP ? "type=bottom,waterlogged=false" : "type=top,waterlogged=false";
            boolean matchesHalf = true;
            for (int s : clicked) {
                if (!placedBlockKey.equals(states.block(s)) || !requiredHalf.equals(states.properties(s))) {
                    matchesHalf = false;
                    break;
                }
            }
            if (matchesHalf) {
                int doubleState = states.stateOf(placedBlockKey, "type=double,waterlogged=false");
                if (doubleState >= 0 && !states.has(doubleState, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)
                        && clickedNeighboursNonReactive(c, sequence)) {
                    return PlacementPlan.single(c, doubleState);
                }
                return null;
            }
        }
        // Variant K1: Stacking minecraft:snow[layers=1..7] onto an existing snow layer block at clicked
        if ("minecraft:snow".equals(placedBlockKey) && clicked.length == 1
                && "minecraft:snow".equals(states.block(clicked[0]))
                && c.y() >= minY() && c.y() < minY() + height()) {
            int layers = parsePositiveIntProp(states.properties(clicked[0]), "layers");
            if (layers >= 1 && layers < 8 && (layers == 1 || face == BlockFace.UP)) {
                int nextState = states.stateOf("minecraft:snow", "layers=" + (layers + 1));
                if (nextState >= 0 && clickedNeighboursNonReactive(c, sequence)) {
                    return PlacementPlan.single(c, nextState);
                }
                return null;
            }
        }
        if (ty < minY() || ty >= minY() + height()) {
            return null;
        }
        if (!clickedAllowsPlacement(clicked, placedBlockKey)) {
            return null;
        }
        // Variant L1: Single-state CarpetBlock (*_carpet except pale_moss_carpet) into pure AIR over any non-air block
        if (single >= 0 && placedBlockKey.endsWith("_carpet") && !"minecraft:pale_moss_carpet".equals(placedBlockKey)) {
            int[] below = before[5];
            if (pureAir(target) && below != null && allNonAir(below) && neighboursNonReactive(before)) {
                return PlacementPlan.single(new BlockPosition(tx, ty, tz), single);
            }
            return null;
        }
        if (single >= 0) {
            return null;
        }
        int first = states.firstStateOf(placedBlockKey);
        if (first < 0) {
            return null;
        }
        int[] all = states.statesOfBlock(first);
        // Variant A: RotatedPillarBlock (exact 3 states axis=x/y/z, all FULL_BLOCK, non-reactive)
        if (all.length == 3 && isPurePillarStates(all)) {
            String axisProp = switch (face) {
                case UP, DOWN -> "axis=y";
                case NORTH, SOUTH -> "axis=z";
                case EAST, WEST -> "axis=x";
                default -> null;
            };
            int chosen = axisProp == null ? -1 : states.stateOf(placedBlockKey, axisProp);
            if (chosen < 0 || !targetReplaceableNonReactive(target, placedBlockKey) || !neighboursNonReactive(before)) {
                return null;
            }
            return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
        }
        boolean inAir = pureAir(target);
        boolean inWater = !inAir && pureWaterSource(target);
        // Variant B: SlabBlock into pure AIR or settled water source
        if (placedBlockKey.endsWith("_slab") && use.location() != null) {
            if (!inAir && !inWater) {
                return null;
            }
            for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
                int[] beside = before[i + 1];
                if (beside == null) {
                    return null;
                }
                for (int s : beside) {
                    if (reacts(s) && !states.block(s).endsWith("_slab")) {
                        return null;
                    }
                }
            }
            boolean bottom = face == BlockFace.UP
                    || (face != BlockFace.DOWN && (use.location().y() - ty) <= 0.5);
            String wl = inWater ? "true" : "false";
            String props = (bottom ? "type=bottom,waterlogged=" : "type=top,waterlogged=") + wl;
            int chosen = states.stateOf(placedBlockKey, props);
            if (chosen >= 0 && !states.has(chosen, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
                return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
            }
            return null;
        }
        // Variant C: Straight + Corner (inner/outer) + Waterlogged StairBlock with exact neighbour stair updates
        if (placedBlockKey.endsWith("_stairs") && horizontalFacing != null && use.location() != null) {
            return resolveStairPlacement(sequence, use, placedBlockKey, horizontalFacing, tx, ty, tz, before, inAir, inWater);
        }
        // Variant D: 4-way HorizontalDirectionalBlock full cubes (glazed terracotta, carved_pumpkin, jack_o_lantern)
        if (all.length == 4 && horizontalFacing != null && isPureHorizontalFacingFullBlock(all)) {
            if (!targetReplaceableNonReactive(target, placedBlockKey) || !neighboursNonReactive(before)) {
                return null;
            }
            if ("minecraft:carved_pumpkin".equals(placedBlockKey) || "minecraft:jack_o_lantern".equals(placedBlockKey)) {
                int[] below = before[5];
                if (below == null) {
                    return null;
                }
                for (int s : below) {
                    String b = states.block(s);
                    if ("minecraft:snow_block".equals(b) || "minecraft:iron_block".equals(b)) {
                        return null; // avoid golem spawn side effects
                    }
                }
            }
            String opposite = oppositeHorizontalFacing(horizontalFacing);
            int chosen = opposite == null ? -1 : states.stateOf(placedBlockKey, "facing=" + opposite);
            if (chosen >= 0) {
                return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
            }
            return null;
        }
        // Variant E: Connected FenceBlock (_fence), WallBlock (_wall), IronBarsBlock / GlassPaneBlock in AIR or water source
        if ((placedBlockKey.endsWith("_fence") || isWall(placedBlockKey) || isPaneOrBars(placedBlockKey))
                && (inAir || inWater)) {
            return resolveConnectedPlacement(sequence, placedBlockKey, tx, ty, tz, before, inWater);
        }
        // Variant F: ChainBlock (*_chain: axis=x|y|z,waterlogged=true|false) into pure AIR or settled water source
        if (placedBlockKey.endsWith("_chain") && (inAir || inWater) && neighboursNonReactive(before)) {
            String wl = inWater ? "true" : "false";
            String axisProp = switch (face) {
                case UP, DOWN -> "axis=y,waterlogged=" + wl;
                case NORTH, SOUTH -> "axis=z,waterlogged=" + wl;
                case EAST, WEST -> "axis=x,waterlogged=" + wl;
                default -> null;
            };
            int chosen = axisProp == null ? -1 : states.stateOf(placedBlockKey, axisProp);
            if (chosen >= 0 && !states.has(chosen, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
                return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
            }
            return null;
        }
        // Variant G: EndRodBlock (minecraft:end_rod) into pure AIR against a non-end_rod clicked block
        if ("minecraft:end_rod".equals(placedBlockKey) && inAir && neighboursNonReactive(before)) {
            boolean clickedIsEndRod = false;
            for (int s : clicked) {
                if ("minecraft:end_rod".equals(states.block(s))) {
                    clickedIsEndRod = true;
                    break;
                }
            }
            if (!clickedIsEndRod) {
                String dirProp = switch (face) {
                    case DOWN -> "facing=down";
                    case UP -> "facing=up";
                    case NORTH -> "facing=north";
                    case SOUTH -> "facing=south";
                    case WEST -> "facing=west";
                    case EAST -> "facing=east";
                };
                int chosen = states.stateOf(placedBlockKey, dirProp);
                if (chosen >= 0 && !states.has(chosen, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
                    return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
                }
            }
            return null;
        }
        // Variant H: Non-iron TrapDoorBlock (*_trapdoor) into pure AIR or settled water source with non-reactive neighbours
        if (placedBlockKey.endsWith("_trapdoor") && !"minecraft:iron_trapdoor".equals(placedBlockKey)
                && (inAir || inWater) && neighboursNonReactive(before)) {
            if (hasNearbyRedstoneSource(tx, ty, tz, sequence)) {
                return null;
            }
            String trapFacing = null;
            String trapHalf = null;
            if (face == BlockFace.UP || face == BlockFace.DOWN) {
                trapFacing = horizontalFacing == null ? null : oppositeHorizontalFacing(horizontalFacing);
                trapHalf = face == BlockFace.UP ? "bottom" : "top";
            } else if (use.location() != null) {
                double localY = use.location().y() - ty;
                if (Math.abs(localY - 0.5) > 1.0E-5) {
                    trapFacing = face.name().toLowerCase(java.util.Locale.ROOT);
                    trapHalf = localY > 0.5 ? "top" : "bottom";
                }
            }
            if (trapFacing != null && trapHalf != null) {
                String props = "facing=" + trapFacing + ",half=" + trapHalf
                        + ",open=false,powered=false,waterlogged=" + (inWater ? "true" : "false");
                int chosen = states.stateOf(placedBlockKey, props);
                if (chosen >= 0 && !states.has(chosen, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
                    return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
                }
            }
            return null;
        }
        // Variant I: Two-block DoorBlock (*_door) into pure AIR at (tx,ty,tz) and (tx,ty+1,tz)
        if (placedBlockKey.endsWith("_door") && horizontalFacing != null && inAir) {
            return resolveDoorPlacement(sequence, use, placedBlockKey, horizontalFacing, tx, ty, tz, before);
        }
        // Variant J: FenceGateBlock (*_fence_gate) into pure AIR with in_wall + adjacent fence/wall updates
        if (isFenceGate(placedBlockKey) && horizontalFacing != null && inAir) {
            return resolveFenceGatePlacement(sequence, placedBlockKey, horizontalFacing, tx, ty, tz, before);
        }
        // Variant K2: Fresh minecraft:snow[layers=1] into pure AIR on a snow-supporting block
        if ("minecraft:snow".equals(placedBlockKey) && inAir && neighboursNonReactive(before)) {
            int[] below = before[5];
            if (below != null && canSupportSnow(below)) {
                int chosen = states.stateOf("minecraft:snow", "layers=1");
                if (chosen >= 0) {
                    return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
                }
            }
            return null;
        }
        // Variant L2: LanternBlock (lantern, soul_lantern, copper lanterns) when exactly one vertical support holds
        if (all.length == 4 && (inAir || inWater) && placedBlockKey.endsWith("lantern")
                && states.stateOf(placedBlockKey, "hanging=false,waterlogged=false") >= 0
                && neighboursNonReactive(before)) {
            int[] above = before[4];
            int[] below = before[5];
            if (above == null || below == null) {
                return null;
            }
            boolean belowFull = allFullSturdyBlock(below);
            boolean aboveFull = allFullSturdyBlock(above);
            boolean aboveAir = pureAir(above);
            boolean belowAir = pureAir(below);
            String wl = inWater ? "true" : "false";
            if (belowFull && aboveAir) {
                int chosen = states.stateOf(placedBlockKey, "hanging=false,waterlogged=" + wl);
                if (chosen >= 0) {
                    return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
                }
            } else if (aboveFull && belowAir) {
                int chosen = states.stateOf(placedBlockKey, "hanging=true,waterlogged=" + wl);
                if (chosen >= 0) {
                    return PlacementPlan.single(new BlockPosition(tx, ty, tz), chosen);
                }
            }
            return null;
        }
        return null;
    }

    /**
     * Resolves an interactive non-sneaking right-click open/close toggle ({@code useWithoutItem}) on a wooden or
     * copper {@code _trapdoor}, {@code _fence_gate}, or two-block {@code _door}. Returns {@code null} for iron
     * doors/trapdoors, copper doors/trapdoors when holding an axe or honeycomb, unknown neighbours, or any ambiguity.
     */
    public PlacementPlan blockToggleForUse(int sequence, String horizontalFacing, String heldItemKey) {
        if (storage == null || hasUnlocatedPredictionBefore(sequence)) {
            return null;
        }
        ServerboundPacket.UseItemOn use = uses.get(sequence);
        int[][] before = useBefore.get(sequence);
        if (use == null || before == null || before[0] == null || before[0].length != 1) {
            return null;
        }
        BlockPosition c = use.position();
        int clickedState = before[0][0];
        String block = states.block(clickedState);
        if ("minecraft:iron_door".equals(block) || "minecraft:iron_trapdoor".equals(block)) {
            return null;
        }
        boolean isCopper = block.contains("copper_");
        if (isCopper && (heldItemKey == null || heldItemKey.endsWith("_axe") || "minecraft:honeycomb".equals(heldItemKey))) {
            return null;
        }
        String props = states.properties(clickedState);
        if (block.endsWith("_trapdoor")) {
            if (!clickedNeighboursNonReactive(c, sequence)) {
                return null;
            }
            String toggledProps = toggleOpenProperty(props);
            int toggled = toggledProps == null ? -1 : states.stateOf(block, toggledProps);
            return toggled >= 0 ? PlacementPlan.single(c, toggled) : null;
        }
        if (isFenceGate(block)) {
            if (!clickedNeighboursNonReactive(c, sequence)) {
                return null;
            }
            String openVal = propValue(props, "open");
            String curFacing = propValue(props, "facing");
            String inWall = propValue(props, "in_wall");
            String powered = propValue(props, "powered");
            if (openVal == null || curFacing == null || inWall == null || powered == null) {
                return null;
            }
            String newOpen;
            String newFacing = curFacing;
            if ("true".equals(openVal)) {
                newOpen = "false";
            } else {
                if (horizontalFacing == null) {
                    return null;
                }
                newOpen = "true";
                if (curFacing.equals(oppositeHorizontalFacing(horizontalFacing))) {
                    newFacing = horizontalFacing;
                }
            }
            String newProps = "facing=" + newFacing + ",in_wall=" + inWall + ",open=" + newOpen + ",powered=" + powered;
            int toggled = states.stateOf(block, newProps);
            return toggled >= 0 ? PlacementPlan.single(c, toggled) : null;
        }
        if (block.endsWith("_door")) {
            String half = propValue(props, "half");
            if (!"lower".equals(half) && !"upper".equals(half)) {
                return null;
            }
            int dy = "lower".equals(half) ? 1 : -1;
            BlockPosition otherPos = new BlockPosition(c.x(), c.y() + dy, c.z());
            if (otherPos.y() < minY() || otherPos.y() >= minY() + height()) {
                return null;
            }
            int[] otherStates = possibleStatesBefore(otherPos.x(), otherPos.y(), otherPos.z(), sequence);
            if (otherStates == null || otherStates.length != 1 || !block.equals(states.block(otherStates[0]))) {
                return null;
            }
            String otherProps = states.properties(otherStates[0]);
            String expectedOtherHalf = "lower".equals(half) ? "upper" : "lower";
            if (!expectedOtherHalf.equals(propValue(otherProps, "half"))
                    || !java.util.Objects.equals(propValue(props, "facing"), propValue(otherProps, "facing"))
                    || !java.util.Objects.equals(propValue(props, "hinge"), propValue(otherProps, "hinge"))
                    || !java.util.Objects.equals(propValue(props, "open"), propValue(otherProps, "open"))) {
                return null;
            }
            if (!doorNeighboursNonReactive(c, otherPos, sequence)) {
                return null;
            }
            String toggledClickedProps = toggleOpenProperty(props);
            String toggledOtherProps = toggleOpenProperty(otherProps);
            int toggledClicked = toggledClickedProps == null ? -1 : states.stateOf(block, toggledClickedProps);
            int toggledOther = toggledOtherProps == null ? -1 : states.stateOf(block, toggledOtherProps);
            if (toggledClicked < 0 || toggledOther < 0) {
                return null;
            }
            return new PlacementPlan(new Placement(c, toggledClicked), List.of(new Placement(otherPos, toggledOther)));
        }
        return null;
    }

    private PlacementPlan resolveStairPlacement(int sequence, ServerboundPacket.UseItemOn use, String placedBlockKey,
            String horizontalFacing, int tx, int ty, int tz, int[][] before, boolean inAir, boolean inWater) {
        if (!inAir && !inWater) {
            return null;
        }
        double localY = use.location().y() - ty;
        BlockFace face = use.face();
        if (face != BlockFace.UP && face != BlockFace.DOWN && Math.abs(localY - 0.5) <= 1.0E-5) {
            return null;
        }
        // Vertical neighbours must not react; horizontal neighbours must be settled and either non-reactive or stairs.
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] beside = before[i + 1];
            if (beside == null) {
                return null;
            }
            boolean horizontal = PREDICTION_NEIGHBOURS[i][1] == 0;
            if (horizontal) {
                if (beside.length != 1) {
                    return null;
                }
                int ns = beside[0];
                if (reacts(ns) && !states.block(ns).endsWith("_stairs")) {
                    return null;
                }
            } else {
                for (int s : beside) {
                    if (reacts(s)) {
                        return null;
                    }
                }
            }
        }
        String half = (face == BlockFace.UP || (face != BlockFace.DOWN && localY <= 0.5)) ? "bottom" : "top";
        int eastState = before[2][0];
        int westState = before[3][0];
        int southState = before[6][0];
        int northState = before[7][0];
        String shape = computeStairsShape(horizontalFacing, half, eastState, westState, southState, northState);
        String props = "facing=" + horizontalFacing + ",half=" + half + ",shape=" + shape
                + ",waterlogged=" + (inWater ? "true" : "false");
        int chosen = states.stateOf(placedBlockKey, props);
        if (chosen < 0 || states.has(chosen, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
            return null;
        }
        // Recompute any adjacent horizontal stair whose shape updates in response to our newly placed stair.
        List<Placement> extras = new ArrayList<>();
        int[] horizIdx = {1, 2, 5, 6}; // EAST, WEST, SOUTH, NORTH in PREDICTION_NEIGHBOURS
        for (int idx : horizIdx) {
            int ns = before[idx + 1][0];
            String nb = states.block(ns);
            if (!nb.endsWith("_stairs")) {
                continue;
            }
            int nx = tx + PREDICTION_NEIGHBOURS[idx][0];
            int ny = ty;
            int nz = tz + PREDICTION_NEIGHBOURS[idx][2];
            int updated = recomputeNeighborStair(nx, ny, nz, ns, tx, ty, tz, chosen, sequence);
            if (updated < 0) {
                return null;
            }
            if (updated != ns) {
                extras.add(new Placement(new BlockPosition(nx, ny, nz), updated));
            }
        }
        return new PlacementPlan(new Placement(new BlockPosition(tx, ty, tz), chosen), extras);
    }

    private int recomputeNeighborStair(int nx, int ny, int nz, int currentState,
            int placedX, int placedY, int placedZ, int placedState, int sequence) {
        int e = settledStateWithOverride(nx + 1, ny, nz, placedX, placedY, placedZ, placedState, sequence);
        int w = settledStateWithOverride(nx - 1, ny, nz, placedX, placedY, placedZ, placedState, sequence);
        int s = settledStateWithOverride(nx, ny, nz + 1, placedX, placedY, placedZ, placedState, sequence);
        int n = settledStateWithOverride(nx, ny, nz - 1, placedX, placedY, placedZ, placedState, sequence);
        if (e < 0 || w < 0 || s < 0 || n < 0) {
            return -1;
        }
        String props = states.properties(currentState);
        String facing = propValue(props, "facing");
        String half = propValue(props, "half");
        String wl = propValue(props, "waterlogged");
        if (facing == null || half == null || wl == null) {
            return -1;
        }
        String newShape = computeStairsShape(facing, half, e, w, s, n);
        String newProps = "facing=" + facing + ",half=" + half + ",shape=" + newShape + ",waterlogged=" + wl;
        return states.stateOf(states.block(currentState), newProps);
    }

    private String computeStairsShape(String facing, String half, int eastState, int westState, int southState, int northState) {
        int frontState = neighborByFacing(facing, eastState, westState, southState, northState);
        if (states.block(frontState).endsWith("_stairs")) {
            String fProps = states.properties(frontState);
            if (half.equals(propValue(fProps, "half"))) {
                String fFacing = propValue(fProps, "facing");
                if (fFacing != null && isPerpendicularFacing(facing, fFacing)) {
                    int checkState = neighborByFacing(oppositeHorizontalFacing(fFacing), eastState, westState, southState, northState);
                    if (canTakeStairShape(facing, half, checkState)) {
                        return fFacing.equals(counterClockWise(facing)) ? "outer_left" : "outer_right";
                    }
                }
            }
        }
        int backState = neighborByFacing(oppositeHorizontalFacing(facing), eastState, westState, southState, northState);
        if (states.block(backState).endsWith("_stairs")) {
            String bProps = states.properties(backState);
            if (half.equals(propValue(bProps, "half"))) {
                String bFacing = propValue(bProps, "facing");
                if (bFacing != null && isPerpendicularFacing(facing, bFacing)) {
                    int checkState = neighborByFacing(bFacing, eastState, westState, southState, northState);
                    if (canTakeStairShape(facing, half, checkState)) {
                        return bFacing.equals(counterClockWise(facing)) ? "inner_left" : "inner_right";
                    }
                }
            }
        }
        return "straight";
    }

    private boolean canTakeStairShape(String facing, String half, int otherState) {
        if (!states.block(otherState).endsWith("_stairs")) {
            return true;
        }
        String props = states.properties(otherState);
        return !(facing.equals(propValue(props, "facing")) && half.equals(propValue(props, "half")));
    }

    private PlacementPlan resolveConnectedPlacement(int sequence, String placedBlockKey,
            int tx, int ty, int tz, int[][] before, boolean inWater) {
        boolean placedWall = isWall(placedBlockKey);
        boolean placedPane = isPaneOrBars(placedBlockKey);
        boolean placedFence = placedBlockKey.endsWith("_fence");
        int[] up = before[4];
        int[] down = before[5];
        if (up == null || down == null || before[2] == null || before[3] == null || before[6] == null || before[7] == null) {
            return null;
        }
        if (before[2].length != 1 || before[3].length != 1 || before[6].length != 1 || before[7].length != 1) {
            return null;
        }
        int eastState = before[2][0];
        int westState = before[3][0];
        int southState = before[6][0];
        int northState = before[7][0];
        // Vertical neighbour checks
        if (placedWall) {
            if (up.length != 1) {
                return null;
            }
            int upState = up[0];
            if (reacts(upState) && !isWall(states.block(upState))) {
                return null;
            }
            if (down.length == 1 && isWall(states.block(down[0]))) {
                // Wall stacked on wall: lower wall updates below!
            } else {
                for (int s : down) {
                    if (reacts(s)) {
                        return null;
                    }
                }
            }
        } else {
            for (int s : up) {
                if (reacts(s)) {
                    return null;
                }
            }
            for (int s : down) {
                if (reacts(s)) {
                    return null;
                }
            }
        }
        // Horizontal neighbour safety check: reject unknown reactive blocks
        int[] horizStates = {eastState, westState, southState, northState};
        BlockFace[] horizDirs = {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH};
        for (int ns : horizStates) {
            if (states.has(ns, BlockStates.DYNAMIC_SHAPE)) {
                return null;
            }
            String nb = states.block(ns);
            boolean supportedReactive = nb.endsWith("_fence") || isWall(nb) || isPaneOrBars(nb) || isFenceGate(nb);
            if (reacts(ns) && !supportedReactive) {
                return null;
            }
        }
        boolean connE = connectedTo(placedBlockKey, eastState, BlockFace.EAST);
        boolean connW = connectedTo(placedBlockKey, westState, BlockFace.WEST);
        boolean connS = connectedTo(placedBlockKey, southState, BlockFace.SOUTH);
        boolean connN = connectedTo(placedBlockKey, northState, BlockFace.NORTH);
        int chosen;
        if (placedWall) {
            chosen = computeWallState(placedBlockKey, connE, connW, connS, connN, up[0], inWater);
        } else {
            String props = "east=" + connE + ",north=" + connN + ",south=" + connS
                    + ",waterlogged=" + inWater + ",west=" + connW;
            chosen = states.stateOf(placedBlockKey, props);
        }
        if (chosen < 0 || states.has(chosen, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
            return null;
        }
        // Compute exact secondary updates on affected horizontal neighbours (and lower wall if wall-on-wall)
        List<Placement> extras = new ArrayList<>();
        int[][] horizOffsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int i = 0; i < 4; i++) {
            int ns = horizStates[i];
            String nb = states.block(ns);
            int nx = tx + horizOffsets[i][0];
            int nz = tz + horizOffsets[i][2];
            BlockFace dirFromNeighborToUs = oppositeFace(horizDirs[i]);
            if (placedFence && sameFenceFamily(placedBlockKey, nb)) {
                int updated = setHorizontalConnProp(ns, dirFromNeighborToUs, true);
                if (updated < 0) {
                    return null;
                }
                if (updated != ns) {
                    extras.add(new Placement(new BlockPosition(nx, ty, nz), updated));
                }
            } else if ((placedPane || placedWall) && isPaneOrBars(nb)) {
                int updated = setHorizontalConnProp(ns, dirFromNeighborToUs, true);
                if (updated < 0) {
                    return null;
                }
                if (updated != ns) {
                    extras.add(new Placement(new BlockPosition(nx, ty, nz), updated));
                }
            } else if ((placedPane || placedWall) && isWall(nb)) {
                int updated = recomputeNeighborWall(nx, ty, nz, ns, tx, ty, tz, chosen, sequence);
                if (updated < 0) {
                    return null;
                }
                if (updated != ns) {
                    extras.add(new Placement(new BlockPosition(nx, ty, nz), updated));
                }
            } else if (placedWall && isFenceGate(nb)) {
                if (fenceGateConnectsTo(states.properties(ns), horizDirs[i])) {
                    int updated = setFenceGateInWall(ns, true);
                    if (updated < 0) {
                        return null;
                    }
                    if (updated != ns) {
                        extras.add(new Placement(new BlockPosition(nx, ty, nz), updated));
                    }
                }
            }
        }
        if (placedWall && down.length == 1 && isWall(states.block(down[0]))) {
            int updatedDown = recomputeNeighborWall(tx, ty - 1, tz, down[0], tx, ty, tz, chosen, sequence);
            if (updatedDown < 0) {
                return null;
            }
            if (updatedDown != down[0]) {
                extras.add(new Placement(new BlockPosition(tx, ty - 1, tz), updatedDown));
            }
        }
        return new PlacementPlan(new Placement(new BlockPosition(tx, ty, tz), chosen), extras);
    }

    private boolean connectedTo(String placedBlock, int neighborState, BlockFace dirToNeighbor) {
        String nb = states.block(neighborState);
        boolean sturdyNotException = !isExceptionForConnection(nb) && isSideSturdyToward(neighborState, dirToNeighbor);
        if (placedBlock.endsWith("_fence")) {
            return sturdyNotException
                    || sameFenceFamily(placedBlock, nb)
                    || (isFenceGate(nb) && fenceGateConnectsTo(states.properties(neighborState), dirToNeighbor));
        }
        if (isPaneOrBars(placedBlock)) {
            return sturdyNotException || isPaneOrBars(nb) || isWall(nb);
        }
        if (isWall(placedBlock)) {
            return sturdyNotException
                    || isWall(nb)
                    || isPaneOrBars(nb)
                    || (isFenceGate(nb) && fenceGateConnectsTo(states.properties(neighborState), dirToNeighbor));
        }
        return false;
    }

    private int computeWallState(String wallBlock, boolean connE, boolean connW, boolean connS, boolean connN,
            int aboveState, boolean waterlogged) {
        String aboveBlock = states.block(aboveState);
        String eVal, wVal, sVal, nVal;
        boolean aboveWallUp = false;
        boolean aboveCoversPost;
        if (states.isAir(aboveState)
                || (!states.has(aboveState, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)
                        && states.collisionShape(aboveState).isEmpty())) {
            eVal = connE ? "low" : "none";
            wVal = connW ? "low" : "none";
            sVal = connS ? "low" : "none";
            nVal = connN ? "low" : "none";
            aboveCoversPost = false;
        } else if (states.has(aboveState, BlockStates.FULL_BLOCK)
                && !states.has(aboveState, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
            eVal = connE ? "tall" : "none";
            wVal = connW ? "tall" : "none";
            sVal = connS ? "tall" : "none";
            nVal = connN ? "tall" : "none";
            aboveCoversPost = true;
        } else if (isWall(aboveBlock)) {
            String ap = states.properties(aboveState);
            boolean aE = !"none".equals(propValue(ap, "east"));
            boolean aW = !"none".equals(propValue(ap, "west"));
            boolean aS = !"none".equals(propValue(ap, "south"));
            boolean aN = !"none".equals(propValue(ap, "north"));
            aboveWallUp = "true".equals(propValue(ap, "up"));
            eVal = connE ? (aE ? "tall" : "low") : "none";
            wVal = connW ? (aW ? "tall" : "low") : "none";
            sVal = connS ? (aS ? "tall" : "low") : "none";
            nVal = connN ? (aN ? "tall" : "low") : "none";
            aboveCoversPost = aboveWallUp || aE || aW || aS || aN;
        } else {
            return -1;
        }
        boolean flag5 = (!connN && !connS && !connE && !connW) || (connN != connS) || (connE != connW);
        boolean flag6 = ("tall".equals(nVal) && "tall".equals(sVal)) || ("tall".equals(eVal) && "tall".equals(wVal));
        boolean up = aboveWallUp || flag5 || (!flag6 && aboveCoversPost);
        String props = "east=" + eVal + ",north=" + nVal + ",south=" + sVal
                + ",up=" + up + ",waterlogged=" + waterlogged + ",west=" + wVal;
        return states.stateOf(wallBlock, props);
    }

    private int recomputeNeighborWall(int wx, int wy, int wz, int currentWallState,
            int placedX, int placedY, int placedZ, int placedState, int sequence) {
        int e = settledStateWithOverride(wx + 1, wy, wz, placedX, placedY, placedZ, placedState, sequence);
        int w = settledStateWithOverride(wx - 1, wy, wz, placedX, placedY, placedZ, placedState, sequence);
        int s = settledStateWithOverride(wx, wy, wz + 1, placedX, placedY, placedZ, placedState, sequence);
        int n = settledStateWithOverride(wx, wy, wz - 1, placedX, placedY, placedZ, placedState, sequence);
        int up = settledStateWithOverride(wx, wy + 1, wz, placedX, placedY, placedZ, placedState, sequence);
        if (e < 0 || w < 0 || s < 0 || n < 0 || up < 0) {
            return -1;
        }
        if (states.has(e, BlockStates.DYNAMIC_SHAPE) || states.has(w, BlockStates.DYNAMIC_SHAPE)
                || states.has(s, BlockStates.DYNAMIC_SHAPE) || states.has(n, BlockStates.DYNAMIC_SHAPE)) {
            return -1;
        }
        String wallBlock = states.block(currentWallState);
        boolean wl = "true".equals(propValue(states.properties(currentWallState), "waterlogged"));
        boolean connE = connectedTo(wallBlock, e, BlockFace.EAST);
        boolean connW = connectedTo(wallBlock, w, BlockFace.WEST);
        boolean connS = connectedTo(wallBlock, s, BlockFace.SOUTH);
        boolean connN = connectedTo(wallBlock, n, BlockFace.NORTH);
        return computeWallState(wallBlock, connE, connW, connS, connN, up, wl);
    }

    private PlacementPlan resolveDoorPlacement(int sequence, ServerboundPacket.UseItemOn use, String placedBlockKey,
            String horizontalFacing, int tx, int ty, int tz, int[][] before) {
        if (ty + 1 >= minY() + height()) {
            return null;
        }
        int[] above = before[4];
        int[] below = before[5];
        if (above == null || below == null || !pureAir(above) || !allFullSturdyBlock(below)) {
            return null;
        }
        // Check all 6 neighbours of lower (except above) and upper (except below) are settled & non-reactive.
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            if (i == 3) { // UP is our upper door half
                continue;
            }
            int[] beside = before[i + 1];
            if (beside == null) {
                return null;
            }
            for (int s : beside) {
                if (reacts(s)) {
                    return null;
                }
            }
        }
        int[][] aroundUpper = around(tx, ty + 1, tz, sequence);
        if (aroundUpper == null) {
            return null;
        }
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            if (i == 4) { // DOWN is our lower door half
                continue;
            }
            int[] beside = aroundUpper[i];
            if (beside == null) {
                return null;
            }
            for (int s : beside) {
                if (reacts(s)) {
                    return null;
                }
            }
        }
        if (hasNearbyRedstoneSource(tx, ty, tz, sequence) || hasNearbyRedstoneSource(tx, ty + 1, tz, sequence)) {
            return null;
        }
        // Compute vanilla DoorBlock.getHinge
        String leftDir = counterClockWise(horizontalFacing);
        String rightDir = clockWise(horizontalFacing);
        int[] leftLow = neighborArrayByFacing(leftDir, before[2], before[3], before[6], before[7]);
        int[] rightLow = neighborArrayByFacing(rightDir, before[2], before[3], before[6], before[7]);
        int[] leftHigh = neighborArrayByFacing(leftDir, aroundUpper[1], aroundUpper[2], aroundUpper[5], aroundUpper[6]);
        int[] rightHigh = neighborArrayByFacing(rightDir, aroundUpper[1], aroundUpper[2], aroundUpper[5], aroundUpper[6]);
        if (leftLow == null || rightLow == null || leftHigh == null || rightHigh == null
                || leftLow.length != 1 || rightLow.length != 1 || leftHigh.length != 1 || rightHigh.length != 1) {
            return null;
        }
        int leftL = leftLow[0], rightL = rightLow[0], leftH = leftHigh[0], rightH = rightHigh[0];
        if (states.has(leftL, BlockStates.DYNAMIC_SHAPE) || states.has(rightL, BlockStates.DYNAMIC_SHAPE)
                || states.has(leftH, BlockStates.DYNAMIC_SHAPE) || states.has(rightH, BlockStates.DYNAMIC_SHAPE)) {
            return null;
        }
        int score = (states.has(leftL, BlockStates.FULL_BLOCK) ? -1 : 0)
                + (states.has(leftH, BlockStates.FULL_BLOCK) ? -1 : 0)
                + (states.has(rightL, BlockStates.FULL_BLOCK) ? 1 : 0)
                + (states.has(rightH, BlockStates.FULL_BLOCK) ? 1 : 0);
        boolean leftDoor = states.block(leftL).endsWith("_door") && "lower".equals(propValue(states.properties(leftL), "half"));
        boolean rightDoor = states.block(rightL).endsWith("_door") && "lower".equals(propValue(states.properties(rightL), "half"));
        String hinge;
        if ((!leftDoor || rightDoor) && score <= 0) {
            if ((!rightDoor || leftDoor) && score >= 0) {
                if (use.location() == null) {
                    return null;
                }
                double fracX = use.location().x() - tx;
                double fracZ = use.location().z() - tz;
                int stepX = facingStepX(horizontalFacing);
                int stepZ = facingStepZ(horizontalFacing);
                // Vanilla DoorBlock.getHinge: (stepX < 0 && fracZ < 0.5) || (stepX > 0 && fracZ > 0.5)
                // || (stepZ < 0 && fracX > 0.5) || (stepZ > 0 && fracX < 0.5) -> RIGHT else LEFT
                double coord = stepX != 0 ? fracZ : fracX;
                if (Math.abs(coord - 0.5) <= 1.0E-5) {
                    return null;
                }
                boolean isRight = (stepX < 0 && fracZ < 0.5) || (stepX > 0 && fracZ > 0.5)
                        || (stepZ < 0 && fracX > 0.5) || (stepZ > 0 && fracX < 0.5);
                hinge = isRight ? "right" : "left";
            } else {
                hinge = "left";
            }
        } else {
            hinge = "right";
        }
        int lower = states.stateOf(placedBlockKey, "facing=" + horizontalFacing + ",half=lower,hinge=" + hinge + ",open=false,powered=false");
        int upper = states.stateOf(placedBlockKey, "facing=" + horizontalFacing + ",half=upper,hinge=" + hinge + ",open=false,powered=false");
        if (lower < 0 || upper < 0) {
            return null;
        }
        return new PlacementPlan(
                new Placement(new BlockPosition(tx, ty, tz), lower),
                List.of(new Placement(new BlockPosition(tx, ty + 1, tz), upper)));
    }

    private PlacementPlan resolveFenceGatePlacement(int sequence, String placedBlockKey, String horizontalFacing,
            int tx, int ty, int tz, int[][] before) {
        if (before[2] == null || before[3] == null || before[4] == null || before[5] == null || before[6] == null || before[7] == null) {
            return null;
        }
        if (before[2].length != 1 || before[3].length != 1 || before[6].length != 1 || before[7].length != 1) {
            return null;
        }
        for (int s : before[4]) {
            if (reacts(s)) {
                return null;
            }
        }
        for (int s : before[5]) {
            if (reacts(s)) {
                return null;
            }
        }
        if (hasNearbyRedstoneSource(tx, ty, tz, sequence)) {
            return null;
        }
        int eastState = before[2][0];
        int westState = before[3][0];
        int southState = before[6][0];
        int northState = before[7][0];
        int[] horizStates = {eastState, westState, southState, northState};
        BlockFace[] horizDirs = {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH};
        for (int ns : horizStates) {
            String nb = states.block(ns);
            boolean allowed = nb.endsWith("_fence") || isWall(nb) || isPaneOrBars(nb) || isFenceGate(nb);
            if (reacts(ns) && !allowed) {
                return null;
            }
        }
        String cw = clockWise(horizontalFacing);
        String ccw = counterClockWise(horizontalFacing);
        int cwState = neighborByFacing(cw, eastState, westState, southState, northState);
        int ccwState = neighborByFacing(ccw, eastState, westState, southState, northState);
        boolean inWall = isWall(states.block(cwState)) || isWall(states.block(ccwState));
        String props = "facing=" + horizontalFacing + ",in_wall=" + inWall + ",open=false,powered=false";
        int chosen = states.stateOf(placedBlockKey, props);
        if (chosen < 0) {
            return null;
        }
        List<Placement> extras = new ArrayList<>();
        int[][] horizOffsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int i = 0; i < 4; i++) {
            int ns = horizStates[i];
            String nb = states.block(ns);
            if (!fenceGateConnectsTo(props, horizDirs[i])) {
                continue;
            }
            int nx = tx + horizOffsets[i][0];
            int nz = tz + horizOffsets[i][2];
            if (nb.endsWith("_fence")) {
                int updated = setHorizontalConnProp(ns, oppositeFace(horizDirs[i]), true);
                if (updated < 0) {
                    return null;
                }
                if (updated != ns) {
                    extras.add(new Placement(new BlockPosition(nx, ty, nz), updated));
                }
            } else if (isWall(nb)) {
                int updated = recomputeNeighborWall(nx, ty, nz, ns, tx, ty, tz, chosen, sequence);
                if (updated < 0) {
                    return null;
                }
                if (updated != ns) {
                    extras.add(new Placement(new BlockPosition(nx, ty, nz), updated));
                }
            }
        }
        return new PlacementPlan(new Placement(new BlockPosition(tx, ty, tz), chosen), extras);
    }

    private int settledStateWithOverride(int x, int y, int z, int ox, int oy, int oz, int oState, int sequence) {
        if (x == ox && y == oy && z == oz) {
            return oState;
        }
        int[] possible = possibleStatesBefore(x, y, z, sequence);
        return possible != null && possible.length == 1 ? possible[0] : -1;
    }

    private int setHorizontalConnProp(int state, BlockFace side, boolean value) {
        String props = states.properties(state);
        String key = switch (side) {
            case EAST -> "east";
            case WEST -> "west";
            case SOUTH -> "south";
            case NORTH -> "north";
            default -> null;
        };
        if (key == null) {
            return -1;
        }
        String e = "east".equals(key) ? Boolean.toString(value) : propValue(props, "east");
        String n = "north".equals(key) ? Boolean.toString(value) : propValue(props, "north");
        String s = "south".equals(key) ? Boolean.toString(value) : propValue(props, "south");
        String wl = propValue(props, "waterlogged");
        String w = "west".equals(key) ? Boolean.toString(value) : propValue(props, "west");
        if (e == null || n == null || s == null || wl == null || w == null) {
            return -1;
        }
        return states.stateOf(states.block(state), "east=" + e + ",north=" + n + ",south=" + s + ",waterlogged=" + wl + ",west=" + w);
    }

    private int setFenceGateInWall(int state, boolean inWall) {
        String props = states.properties(state);
        String facing = propValue(props, "facing");
        String open = propValue(props, "open");
        String powered = propValue(props, "powered");
        if (facing == null || open == null || powered == null) {
            return -1;
        }
        return states.stateOf(states.block(state), "facing=" + facing + ",in_wall=" + inWall + ",open=" + open + ",powered=" + powered);
    }

    private static String toggleOpenProperty(String props) {
        if (props.contains("open=false")) {
            return props.replace("open=false", "open=true");
        }
        if (props.contains("open=true")) {
            return props.replace("open=true", "open=false");
        }
        return null;
    }

    private boolean doorNeighboursNonReactive(BlockPosition a, BlockPosition b, int sequence) {
        return nonReactiveAroundExcluding(a, b, sequence) && nonReactiveAroundExcluding(b, a, sequence);
    }

    private boolean nonReactiveAroundExcluding(BlockPosition center, BlockPosition excluded, int sequence) {
        int[][] aroundPos = around(center.x(), center.y(), center.z(), sequence);
        if (aroundPos == null) {
            return false;
        }
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int nx = center.x() + PREDICTION_NEIGHBOURS[i][0];
            int ny = center.y() + PREDICTION_NEIGHBOURS[i][1];
            int nz = center.z() + PREDICTION_NEIGHBOURS[i][2];
            if (nx == excluded.x() && ny == excluded.y() && nz == excluded.z()) {
                continue;
            }
            int[] beside = aroundPos[i];
            if (beside == null) {
                return false;
            }
            for (int s : beside) {
                if (states.has(s, BlockStates.NEIGHBOUR_REACTIVE) && !isDrySlab(s)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasNearbyRedstoneSource(int x, int y, int z, int sequence) {
        for (int[] o : PREDICTION_NEIGHBOURS) {
            if (o[0] == 0 && o[1] == 0 && o[2] == 0) {
                continue;
            }
            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
            int[] beside = possibleStatesBefore(nx, ny, nz, sequence);
            if (beside == null) {
                return true;
            }
            for (int s : beside) {
                if (couldEmitRedstone(s)) {
                    return true;
                }
                if (states.has(s, BlockStates.FULL_BLOCK)) {
                    // Check direct neighbours of this full block for indirect strong redstone power
                    for (int[] o2 : PREDICTION_NEIGHBOURS) {
                        if (o2[0] == 0 && o2[1] == 0 && o2[2] == 0) {
                            continue;
                        }
                        int bx = nx + o2[0], by = ny + o2[1], bz = nz + o2[2];
                        if (bx == x && by == y && bz == z) {
                            continue;
                        }
                        int[] indirect = possibleStatesBefore(bx, by, bz, sequence);
                        if (indirect == null) {
                            return true;
                        }
                        for (int is : indirect) {
                            if (couldEmitRedstone(is)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean couldEmitRedstone(int state) {
        String b = states.block(state);
        if (b.contains("redstone") || b.contains("lever") || b.contains("button")
                || b.contains("pressure_plate") || b.contains("observer")
                || b.contains("repeater") || b.contains("comparator")
                || b.contains("daylight") || b.contains("tripwire")
                || b.contains("detector_rail") || b.contains("sculk_sensor")
                || b.contains("trapped_chest") || b.contains("lectern")
                || b.contains("lightning_rod") || "minecraft:target".equals(b)
                || "minecraft:jukebox".equals(b)) {
            return true;
        }
        String p = states.properties(state);
        return p.contains("powered=true") || p.contains("power=");
    }

    private boolean allNonAir(int[] possible) {
        if (possible.length == 0) {
            return false;
        }
        for (int s : possible) {
            if (states.isAir(s)) {
                return false;
            }
        }
        return true;
    }

    private boolean allFullSturdyBlock(int[] possible) {
        if (possible.length == 0) {
            return false;
        }
        for (int s : possible) {
            if (!states.has(s, BlockStates.FULL_BLOCK)
                    || states.has(s, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
                return false;
            }
        }
        return true;
    }

    private boolean canSupportSnow(int[] below) {
        if (below.length == 0) {
            return false;
        }
        for (int s : below) {
            String b = states.block(s);
            if ("minecraft:ice".equals(b) || "minecraft:packed_ice".equals(b) || "minecraft:barrier".equals(b)) {
                return false;
            }
            if ("minecraft:honey_block".equals(b) || "minecraft:soul_sand".equals(b) || "minecraft:mud".equals(b)) {
                continue;
            }
            if ("minecraft:snow".equals(b) && "layers=8".equals(states.properties(s))) {
                continue;
            }
            if (!states.has(s, BlockStates.FULL_BLOCK)
                    || states.has(s, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSideSturdyToward(int neighborState, BlockFace dirToNeighbor) {
        if (states.has(neighborState, BlockStates.DYNAMIC_SHAPE)) {
            return false;
        }
        return switch (dirToNeighbor) {
            case EAST -> states.has(neighborState, BlockStates.STURDY_WEST);
            case WEST -> states.has(neighborState, BlockStates.STURDY_EAST);
            case SOUTH -> states.has(neighborState, BlockStates.STURDY_NORTH);
            case NORTH -> states.has(neighborState, BlockStates.STURDY_SOUTH);
            default -> false;
        };
    }

    private static boolean isExceptionForConnection(String block) {
        return block.endsWith("_leaves")
                || block.endsWith("shulker_box")
                || "minecraft:barrier".equals(block)
                || "minecraft:carved_pumpkin".equals(block)
                || "minecraft:jack_o_lantern".equals(block)
                || "minecraft:melon".equals(block)
                || "minecraft:pumpkin".equals(block);
    }

    private static boolean sameFenceFamily(String a, String b) {
        if (!a.endsWith("_fence") || !b.endsWith("_fence")) {
            return false;
        }
        boolean aNether = "minecraft:nether_brick_fence".equals(a);
        boolean bNether = "minecraft:nether_brick_fence".equals(b);
        return aNether == bNether;
    }

    private static boolean isPaneOrBars(String block) {
        return "minecraft:iron_bars".equals(block)
                || "minecraft:glass_pane".equals(block)
                || block.endsWith("_stained_glass_pane");
    }

    private static boolean isWall(String block) {
        return block.endsWith("_wall");
    }

    private static boolean isFenceGate(String block) {
        return block.endsWith("_fence_gate");
    }

    private static boolean fenceGateConnectsTo(String gateProps, BlockFace dirToGate) {
        String f = propValue(gateProps, "facing");
        if (f == null) {
            return false;
        }
        boolean gateNS = "north".equals(f) || "south".equals(f);
        return gateNS ? (dirToGate == BlockFace.EAST || dirToGate == BlockFace.WEST)
                : (dirToGate == BlockFace.NORTH || dirToGate == BlockFace.SOUTH);
    }

    private static String propValue(String props, String key) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        int len = props.length();
        int klen = key.length();
        int start = 0;
        while (start < len) {
            int comma = props.indexOf(',', start);
            int end = comma < 0 ? len : comma;
            if (end - start > klen + 1 && props.regionMatches(start, key, 0, klen) && props.charAt(start + klen) == '=') {
                return props.substring(start + klen + 1, end);
            }
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return null;
    }

    private static int parsePositiveIntProp(String props, String key) {
        String v = propValue(props, key);
        if (v == null || v.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int neighborByFacing(String facing, int east, int west, int south, int north) {
        return switch (facing) {
            case "east" -> east;
            case "west" -> west;
            case "south" -> south;
            case "north" -> north;
            default -> -1;
        };
    }

    private static int[] neighborArrayByFacing(String facing, int[] east, int[] west, int[] south, int[] north) {
        return switch (facing) {
            case "east" -> east;
            case "west" -> west;
            case "south" -> south;
            case "north" -> north;
            default -> null;
        };
    }

    private static boolean isPerpendicularFacing(String a, String b) {
        boolean aNS = "north".equals(a) || "south".equals(a);
        boolean bNS = "north".equals(b) || "south".equals(b);
        return aNS != bNS;
    }

    private static String clockWise(String facing) {
        return switch (facing) {
            case "north" -> "east";
            case "east" -> "south";
            case "south" -> "west";
            case "west" -> "north";
            default -> null;
        };
    }

    private static String counterClockWise(String facing) {
        return switch (facing) {
            case "north" -> "west";
            case "west" -> "south";
            case "south" -> "east";
            case "east" -> "north";
            default -> null;
        };
    }

    private static int facingStepX(String facing) {
        return "east".equals(facing) ? 1 : ("west".equals(facing) ? -1 : 0);
    }

    private static int facingStepZ(String facing) {
        return "south".equals(facing) ? 1 : ("north".equals(facing) ? -1 : 0);
    }

    private static BlockFace oppositeFace(BlockFace face) {
        return switch (face) {
            case DOWN -> BlockFace.UP;
            case UP -> BlockFace.DOWN;
            case NORTH -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.NORTH;
            case WEST -> BlockFace.EAST;
            case EAST -> BlockFace.WEST;
        };
    }

    private boolean clickedNeighboursNonReactive(BlockPosition c, int sequence) {
        int[][] aroundClicked = around(c.x(), c.y(), c.z(), sequence);
        if (aroundClicked == null) {
            return false;
        }
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] beside = aroundClicked[i];
            if (beside == null) {
                return false;
            }
            for (int s : beside) {
                if (states.has(s, BlockStates.NEIGHBOUR_REACTIVE) && !isDrySlab(s)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isPureHorizontalFacingFullBlock(int[] all) {
        for (int s : all) {
            if (!states.has(s, BlockStates.FULL_BLOCK)
                    || states.has(s, BlockStates.INTERACTIVE | BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)
                    || reacts(s)) {
                return false;
            }
            String p = states.properties(s);
            if (!"facing=north".equals(p) && !"facing=south".equals(p)
                    && !"facing=east".equals(p) && !"facing=west".equals(p)) {
                return false;
            }
        }
        return true;
    }

    private static String oppositeHorizontalFacing(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            default -> null;
        };
    }

    private boolean pureAir(int[] possible) {
        for (int s : possible) {
            if (!states.isAir(s)) {
                return false;
            }
        }
        return true;
    }

    private boolean pureWaterSource(int[] possible) {
        int waterSource = states.stateOf("minecraft:water", "level=0");
        if (waterSource < 0 || possible == null || possible.length == 0) {
            return false;
        }
        for (int s : possible) {
            if (s != waterSource) {
                return false;
            }
        }
        return true;
    }

    private boolean isPurePillarStates(int[] all) {
        for (int s : all) {
            if (!states.has(s, BlockStates.FULL_BLOCK)
                    || states.has(s, BlockStates.DYNAMIC_SHAPE | BlockStates.CONTEXTUAL_COLLISION)
                    || reacts(s)) {
                return false;
            }
            String p = states.properties(s);
            if (!"axis=x".equals(p) && !"axis=y".equals(p) && !"axis=z".equals(p)) {
                return false;
            }
        }
        return true;
    }

    private boolean targetReplaceableNonReactive(int[] target, String placedBlockKey) {
        for (int s : target) {
            if (!states.has(s, BlockStates.REPLACEABLE) || states.has(s, BlockStates.CUSTOM_REPLACING) || reacts(s)
                    || states.block(s).equals(placedBlockKey)) {
                return false;
            }
        }
        return true;
    }

    private boolean neighboursNonReactive(int[][] before) {
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] beside = before[i + 1];
            if (beside == null) {
                return false;
            }
            for (int s : beside) {
                if (reacts(s)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean clickedAllowsPlacement(int[] clicked, String placedBlockKey) {
        for (int s : clicked) {
            boolean slabCannotBeReplaced = states.block(s).endsWith("_slab")
                    && (states.properties(s).startsWith("type=double,") || !placedBlockKey.equals(states.block(s)));
            boolean customReplacing = states.has(s, BlockStates.CUSTOM_REPLACING) && !slabCannotBeReplaced;
            if (states.has(s, BlockStates.INTERACTIVE | BlockStates.REPLACEABLE) || customReplacing) {
                return false;
            }
        }
        return true;
    }

    public BlockPosition placement(int sequence, int state) {
        ServerboundPacket.UseItemOn use = uses.get(sequence);
        if (use == null || storage == null || hasUnlocatedPredictionBefore(sequence) || !states.isValid(state)
                || states.has(state, BlockStates.CUSTOM_PLACEMENT) || reacts(state)) {
            return null;
        }
        BlockPosition c = use.position();
        BlockFace face = use.face();
        int tx = c.x() + face.stepX();
        int ty = c.y() + face.stepY();
        int tz = c.z() + face.stepZ();
        if (ty < minY() || ty >= minY() + height()) {
            return null;
        }
        int[][] before = useBefore.get(sequence);
        int[] clicked = before == null ? null : before[0];
        int[] target = before == null ? null : before[1];
        if (clicked == null || target == null) {
            return null;
        }
        if (!clickedAllowsPlacement(clicked, states.block(state))) {
            return null;
        }
        for (int s : target) {
            if (!states.has(s, BlockStates.REPLACEABLE) || states.has(s, BlockStates.CUSTOM_REPLACING) || reacts(s)
                    || states.block(s).equals(states.block(state))) {
                return null;
            }
        }
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] beside = before[i + 1];
            if (beside == null) {
                return null;
            }
            for (int s : beside) {
                if (reacts(s)) {
                    return null;
                }
            }
        }
        return new BlockPosition(tx, ty, tz);
    }

    /** A dry slab ({@code waterlogged=false}): {@code SlabBlock.updateShape} returns it unchanged and schedules no tick. */
    private boolean isDrySlab(int state) {
        return states.block(state).endsWith("_slab") && states.properties(state).endsWith("waterlogged=false");
    }

    /** A change next to it may make it another block, or setting it may change others ({@link BlockStates#UPDATE_KEEPS_BLOCK} is neither). */
    private boolean reacts(int state) {
        return states.has(state, BlockStates.NEIGHBOUR_REACTIVE)
                && !states.has(state, BlockStates.UPDATE_KEEPS_BLOCK)
                && !isDrySlab(state);
    }

    /**
     * What the client's block use {@code sequence} did to the positions it may have changed ({@link #onInbound}: the clicked
     * one, the one on the clicked face and that one's neighbours): {@code placed} (a {@link #placement} of this use) took
     * {@code state} and the others kept theirs, or (null) none changed. Ignored for a use already acknowledged.
     */
    public void resolveUse(int sequence, BlockPosition placed, int state) {
        ServerboundPacket.UseItemOn use = uses.get(sequence);
        if (use == null) {
            return;
        }
        Map<Long, Integer> cells = new HashMap<>();
        BlockPosition c = use.position();
        BlockFace face = use.face();
        cells.put(blockKey(c.x(), c.y(), c.z()), KEPT);
        for (int[] o : PREDICTION_NEIGHBOURS) {
            cells.put(blockKey(c.x() + face.stepX() + o[0], c.y() + face.stepY() + o[1], c.z() + face.stepZ() + o[2]), KEPT);
        }
        if (placed != null) {
            boolean atClicked = placed.x() == c.x() && placed.y() == c.y() && placed.z() == c.z();
            boolean atTarget = placed.x() == c.x() + face.stepX() && placed.y() == c.y() + face.stepY() && placed.z() == c.z() + face.stepZ();
            if (!atClicked && !atTarget) {
                throw new IllegalArgumentException(placed + " is not where use " + sequence + " places");
            }
            cells.put(blockKey(placed.x(), placed.y(), placed.z()), state);
            if (atTarget) {
                // the new block's neighbours get a shape update: one that keeps its block may still change its state
                int[][] before = useBefore.get(sequence);
                for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
                    int[] o = PREDICTION_NEIGHBOURS[i];
                    int[] beside = before == null ? null : before[i + 1];
                    long key = blockKey(placed.x() + o[0], placed.y() + o[1], placed.z() + o[2]);
                    if (beside == null) {
                        cells.remove(key); // not known: not resolved there (placement would not have given this position)
                        continue;
                    }
                    for (int s : beside) {
                        if (states.has(s, BlockStates.NEIGHBOUR_REACTIVE) && !isDrySlab(s)) {
                            cells.put(key, SAME_BLOCK);
                        }
                    }
                }
            }
        }
        resolvedUses.put(sequence, cells);
    }

    /**
     * Atomically resolves a multi-cell block use ({@link #placementPlanForItem} or {@link #blockToggleForUse}):
     * sets {@code plan.primary()} and every cell in {@code plan.extras()} to its exact predicted state, registers
     * prediction tracking for any extra cell outside the default target neighbourhood (such as a toggled door's
     * partner half), keeps untouched neighbours as {@link #KEPT}, and marks {@link BlockStates#UPDATE_KEEPS_BLOCK}
     * neighbours as {@link #SAME_BLOCK}. Passing {@code plan == null} marks every predicted cell as {@link #KEPT}.
     */
    public void resolveUseMulti(int sequence, PlacementPlan plan) {
        if (plan == null) {
            resolveUse(sequence, null, 0);
            return;
        }
        ServerboundPacket.UseItemOn use = uses.get(sequence);
        if (use == null) {
            return;
        }
        BlockPosition c = use.position();
        BlockFace face = use.face();
        int tx = c.x() + face.stepX();
        int ty = c.y() + face.stepY();
        int tz = c.z() + face.stepZ();
        BlockPosition primaryPos = plan.primary().position();
        boolean atClicked = primaryPos.x() == c.x() && primaryPos.y() == c.y() && primaryPos.z() == c.z();
        boolean atTarget = primaryPos.x() == tx && primaryPos.y() == ty && primaryPos.z() == tz;
        if (!atClicked && !atTarget) {
            throw new IllegalArgumentException(primaryPos + " is not where use " + sequence + " places");
        }
        Map<Long, Integer> cells = new HashMap<>();
        cells.put(blockKey(c.x(), c.y(), c.z()), KEPT);
        for (int[] o : PREDICTION_NEIGHBOURS) {
            cells.put(blockKey(tx + o[0], ty + o[1], tz + o[2]), KEPT);
        }
        java.util.Set<Long> explicit = new java.util.HashSet<>();
        long primaryKey = blockKey(primaryPos.x(), primaryPos.y(), primaryPos.z());
        cells.put(primaryKey, plan.primary().state());
        explicit.add(primaryKey);
        for (Placement extra : plan.extras()) {
            BlockPosition ep = extra.position();
            long key = blockKey(ep.x(), ep.y(), ep.z());
            if (!cells.containsKey(key)) {
                addPredictionCell(ep.x(), ep.y(), ep.z(), sequence);
                if (lastEventSequence != Long.MIN_VALUE) {
                    change(lastEventSequence, ep.x(), ep.y(), ep.z(), ep.x(), ep.y(), ep.z());
                }
            }
            cells.put(key, extra.state());
            explicit.add(key);
        }
        if (atTarget) {
            int[][] before = useBefore.get(sequence);
            for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
                int[] o = PREDICTION_NEIGHBOURS[i];
                long key = blockKey(tx + o[0], ty + o[1], tz + o[2]);
                if (explicit.contains(key)) {
                    continue;
                }
                int[] beside = before == null ? null : before[i + 1];
                if (beside == null) {
                    cells.remove(key);
                    continue;
                }
                for (int s : beside) {
                    if (states.has(s, BlockStates.UPDATE_KEEPS_BLOCK)) {
                        cells.put(key, SAME_BLOCK);
                    }
                }
            }
        }
        resolvedUses.put(sequence, cells);
    }

    /** Whether the client may hold blocks of an item use before {@code sequence} that WhyNotAC cannot locate. */
    public boolean hasUnlocatedPredictionBefore(int sequence) {
        return !unlocatedPredictions.isEmpty() && unlocatedPredictions.peekFirst() < sequence;
    }

    /**
     * Clears the unlocated-prediction marker of {@link ServerboundPacket.UseItem} {@code sequence} when every item
     * the client may hold in the used hand cannot modify any block.
     */
    public void resolveItemUseNoChange(int sequence) {
        unlocatedPredictions.remove(Integer.valueOf(sequence));
        long[] marker = unlocatedUseChanges.remove(sequence);
        if (marker != null) {
            changes.remove(marker);
        }
    }

    /**
     * Replaces the unlocated-prediction marker of {@link ServerboundPacket.UseItem} {@code sequence} with a concrete
     * prediction at {@code at -> state}, or clears it with no block change when {@code at == null}.
     */
    public void resolveItemUseBlock(int sequence, BlockPosition at, int state) {
        unlocatedPredictions.remove(Integer.valueOf(sequence));
        long[] marker = unlocatedUseChanges.remove(sequence);
        if (at == null) {
            if (marker != null) {
                changes.remove(marker);
            }
            return;
        }
        if (marker != null) {
            // Shrink the in-place changes marker to the 3x3x3 neighbourhood while keeping changes monotonic in eventSequence.
            marker[1] = (long) at.x() - 1;
            marker[2] = (long) at.y() - 1;
            marker[3] = (long) at.z() - 1;
            marker[4] = (long) at.x() + 1;
            marker[5] = (long) at.y() + 1;
            marker[6] = (long) at.z() + 1;
        }
        int[][] before = around(at.x(), at.y(), at.z(), sequence);
        Map<Long, Integer> cells = new HashMap<>();
        for (int[] o : PREDICTION_NEIGHBOURS) {
            addPredictionCell(at.x() + o[0], at.y() + o[1], at.z() + o[2], sequence);
            cells.put(blockKey(at.x() + o[0], at.y() + o[1], at.z() + o[2]), KEPT);
        }
        cells.put(blockKey(at.x(), at.y(), at.z()), state);
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] o = PREDICTION_NEIGHBOURS[i];
            int[] beside = before == null ? null : before[i];
            long key = blockKey(at.x() + o[0], at.y() + o[1], at.z() + o[2]);
            if (beside == null) {
                cells.remove(key);
                continue;
            }
            for (int s : beside) {
                if (states.has(s, BlockStates.NEIGHBOUR_REACTIVE)) {
                    cells.put(key, SAME_BLOCK);
                }
            }
        }
        resolvedUses.put(sequence, cells);
    }

    private static final double BUCKET_RAY_MARGIN = 1.0E-4;
    private static final double BUCKET_FACE_MARGIN = 1.0E-6;
    private static final int BUCKET_MAX_STEPS = 256;

    private record BucketRayHit(int hx, int hy, int hz, BlockFace face, int state) {}

    /**
     * Resolves a deterministic {@code BucketItem.use} placement ({@code water_bucket}, {@code lava_bucket}) or isolated
     * fluid pickup ({@code bucket} on water/lava source) across every candidate eye position, returning null whenever
     * geometry, reach, fluids, neighbour reactivity, or block state leaves any ambiguity.
     */
    public Placement bucketPlacement(int sequence, String bucketItem, List<Vec3d> eyes, float yaw, float pitch, double blockRange) {
        if (storage == null || bucketItem == null || eyes == null || eyes.isEmpty()
                || hasUnlocatedPredictionBefore(sequence) || !(blockRange > 0.0 && Double.isFinite(blockRange))
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return null;
        }
        boolean pickup = "minecraft:bucket".equals(bucketItem);
        boolean water = "minecraft:water_bucket".equals(bucketItem);
        boolean lava = "minecraft:lava_bucket".equals(bucketItem);
        if (!pickup && !water && !lava) {
            return null;
        }
        if (water && isUltrawarmDimension()) {
            return null; // water evaporates in ultrawarm dimensions such as the Nether
        }
        int newState;
        if (pickup) {
            newState = states.onlyStateOf("minecraft:air");
        } else {
            newState = states.stateOf(water ? "minecraft:water" : "minecraft:lava", "level=0");
        }
        if (newState < 0) {
            return null;
        }
        double[] dir = viewVector(pitch, yaw);
        BucketRayHit firstHit = null;
        for (Vec3d eye : eyes) {
            BucketRayHit hit = raycastBucket(sequence, eye, dir, blockRange, pickup);
            if (hit == null) {
                return null;
            }
            if (firstHit == null) {
                firstHit = hit;
            } else if (firstHit.hx() != hit.hx() || firstHit.hy() != hit.hy() || firstHit.hz() != hit.hz()
                    || firstHit.face() != hit.face()) {
                return null;
            }
        }
        if (firstHit == null) {
            return null;
        }
        if (pickup) {
            BlockPosition target = new BlockPosition(firstHit.hx(), firstHit.hy(), firstHit.hz());
            return bucketTargetAndNeighboursValid(sequence, target.x(), target.y(), target.z(), true, false)
                    ? new Placement(target, newState) : null;
        }
        if (!bucketHitBlockSolidForLiquid(firstHit.state(), water)) {
            return null;
        }
        BlockPosition target = new BlockPosition(
                firstHit.hx() + firstHit.face().stepX(),
                firstHit.hy() + firstHit.face().stepY(),
                firstHit.hz() + firstHit.face().stepZ());
        return bucketTargetAndNeighboursValid(sequence, target.x(), target.y(), target.z(), false, true)
                ? new Placement(target, newState) : null;
    }

    private BucketRayHit raycastBucket(int sequence, Vec3d eye, double[] dir, double range, boolean pickup) {
        double fx = eye.x();
        double fy = eye.y();
        double fz = eye.z();
        double dx = dir[0] * range;
        double dy = dir[1] * range;
        double dz = dir[2] * range;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 1.0E-7)) {
            return null;
        }
        int cx = (int) Math.floor(fx);
        int cy = (int) Math.floor(fy);
        int cz = (int) Math.floor(fz);
        if ((dx < 0 && fx - cx < BUCKET_RAY_MARGIN) || (dx > 0 && cx + 1.0 - fx < BUCKET_RAY_MARGIN)
                || (dy < 0 && fy - cy < BUCKET_RAY_MARGIN) || (dy > 0 && cy + 1.0 - fy < BUCKET_RAY_MARGIN)
                || (dz < 0 && fz - cz < BUCKET_RAY_MARGIN) || (dz > 0 && cz + 1.0 - fz < BUCKET_RAY_MARGIN)) {
            return null;
        }
        int stepX = Math.abs(dx) > 1.0E-9 ? (dx > 0 ? 1 : -1) : 0;
        int stepY = Math.abs(dy) > 1.0E-9 ? (dy > 0 ? 1 : -1) : 0;
        int stepZ = Math.abs(dz) > 1.0E-9 ? (dz > 0 ? 1 : -1) : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : stepX / dx;
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : stepY / dy;
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : stepZ / dz;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : ((stepX > 0 ? cx + 1.0 : cx) - fx) / dx;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : ((stepY > 0 ? cy + 1.0 : cy) - fy) / dy;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : ((stepZ > 0 ? cz + 1.0 : cz) - fz) / dz;
        double tEnd = 1.0 - BUCKET_RAY_MARGIN / Math.max(1.0, len);
        double t = 0.0;
        for (int steps = 0; t <= 1.0 && steps < BUCKET_MAX_STEPS; steps++) {
            int[] possible = possibleStatesBefore(cx, cy, cz, sequence);
            if (possible == null || possible.length != 1) {
                return null;
            }
            int s = possible[0];
            if (!states.isValid(s) || !states.outlineExact(s) || !states.outlineInCell(s)
                    || states.has(s, BlockStates.MOVING_OUTLINE)) {
                return null;
            }
            BucketRayHit cellHit = testBucketCell(cx, cy, cz, s, fx, fy, fz, dx, dy, dz, tEnd, pickup);
            if (cellHit != null) {
                return cellHit.hx() == Integer.MIN_VALUE ? null : cellHit;
            }
            if (Math.abs(tMaxX - tMaxY) < 1.0E-5 || Math.abs(tMaxX - tMaxZ) < 1.0E-5 || Math.abs(tMaxY - tMaxZ) < 1.0E-5) {
                return null;
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX;
                tMaxX += tDeltaX;
                cx += stepX;
            } else if (tMaxY < tMaxZ) {
                t = tMaxY;
                tMaxY += tDeltaY;
                cy += stepY;
            } else {
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                cz += stepZ;
            }
        }
        return null;
    }

    private BucketRayHit testBucketCell(int cx, int cy, int cz, int state, double fx, double fy, double fz,
            double dx, double dy, double dz, double tEnd, boolean pickup) {
        BucketRayHit ambiguous = new BucketRayHit(Integer.MIN_VALUE, 0, 0, BlockFace.UP, -1);
        Shape outline = states.outlineShape(state);
        double bestSolidT = Double.POSITIVE_INFINITY;
        BlockFace bestSolidFace = null;
        for (int b = 0; b < outline.boxCount(); b++) {
            double x0 = cx + outline.box(b, 0);
            double y0 = cy + outline.box(b, 1);
            double z0 = cz + outline.box(b, 2);
            double x1 = cx + outline.box(b, 3);
            double y1 = cy + outline.box(b, 4);
            double z1 = cz + outline.box(b, 5);
            if (x0 - BUCKET_RAY_MARGIN <= fx && fx <= x1 + BUCKET_RAY_MARGIN
                    && y0 - BUCKET_RAY_MARGIN <= fy && fy <= y1 + BUCKET_RAY_MARGIN
                    && z0 - BUCKET_RAY_MARGIN <= fz && fz <= z1 + BUCKET_RAY_MARGIN) {
                return ambiguous;
            }
            double tExpanded = enter(fx, fy, fz, dx, dy, dz,
                    x0 - BUCKET_RAY_MARGIN, y0 - BUCKET_RAY_MARGIN, z0 - BUCKET_RAY_MARGIN,
                    x1 + BUCKET_RAY_MARGIN, y1 + BUCKET_RAY_MARGIN, z1 + BUCKET_RAY_MARGIN);
            if (tExpanded > 1.0) {
                continue;
            }
            double tShrunk = enter(fx, fy, fz, dx, dy, dz,
                    x0 + BUCKET_RAY_MARGIN, y0 + BUCKET_RAY_MARGIN, z0 + BUCKET_RAY_MARGIN,
                    x1 - BUCKET_RAY_MARGIN, y1 - BUCKET_RAY_MARGIN, z1 - BUCKET_RAY_MARGIN);
            if (!(tShrunk < tEnd)) {
                return ambiguous;
            }
            double tExact = enter(fx, fy, fz, dx, dy, dz, x0, y0, z0, x1, y1, z1);
            BlockFace face = enteredFace(fx, fy, fz, dx, dy, dz, x0, y0, z0, x1, y1, z1);
            if (!(tExact < tEnd) || face == null) {
                return ambiguous;
            }
            if (tExact < bestSolidT) {
                bestSolidT = tExact;
                bestSolidFace = face;
            }
        }
        if (!pickup) {
            return bestSolidFace == null ? null : new BucketRayHit(cx, cy, cz, bestSolidFace, state);
        }
        // ClipContext.Fluid.SOURCE_ONLY: pure water[level=0] / lava[level=0] source blocks stop the pickup ray cleanly
        if (isPureFluidSource(state)) {
            double y1Max = cy + 1.0;
            double y1Safe = cy + (8.0 / 9.0);
            if (cx - BUCKET_RAY_MARGIN <= fx && fx <= cx + 1.0 + BUCKET_RAY_MARGIN
                    && cy - BUCKET_RAY_MARGIN <= fy && fy <= y1Max + BUCKET_RAY_MARGIN
                    && cz - BUCKET_RAY_MARGIN <= fz && fz <= cz + 1.0 + BUCKET_RAY_MARGIN) {
                return ambiguous;
            }
            double tExpanded = enter(fx, fy, fz, dx, dy, dz,
                    cx - BUCKET_RAY_MARGIN, cy - BUCKET_RAY_MARGIN, cz - BUCKET_RAY_MARGIN,
                    cx + 1.0 + BUCKET_RAY_MARGIN, y1Max + BUCKET_RAY_MARGIN, cz + 1.0 + BUCKET_RAY_MARGIN);
            if (tExpanded <= 1.0) {
                double tShrunk = enter(fx, fy, fz, dx, dy, dz,
                        cx + BUCKET_RAY_MARGIN, cy + BUCKET_RAY_MARGIN, cz + BUCKET_RAY_MARGIN,
                        cx + 1.0 - BUCKET_RAY_MARGIN, y1Safe - BUCKET_RAY_MARGIN, cz + 1.0 - BUCKET_RAY_MARGIN);
                if (!(tShrunk < tEnd)) {
                    return ambiguous;
                }
                if (bestSolidFace == null || tShrunk < bestSolidT) {
                    return new BucketRayHit(cx, cy, cz, BlockFace.UP, state);
                }
            }
        }
        // Solid outline before fluid source misses pickup
        return bestSolidFace == null ? null : ambiguous;
    }

    private static BlockFace enteredFace(double fx, double fy, double fz, double dx, double dy, double dz,
            double x0, double y0, double z0, double x1, double y1, double z1) {
        double tx = Math.abs(dx) > 1.0E-9 ? ((dx > 0 ? x0 : x1) - fx) / dx : Double.NEGATIVE_INFINITY;
        double ty = Math.abs(dy) > 1.0E-9 ? ((dy > 0 ? y0 : y1) - fy) / dy : Double.NEGATIVE_INFINITY;
        double tz = Math.abs(dz) > 1.0E-9 ? ((dz > 0 ? z0 : z1) - fz) / dz : Double.NEGATIVE_INFINITY;
        if (tx >= ty && tx >= tz) {
            if (tx - Math.max(ty, tz) <= BUCKET_FACE_MARGIN) {
                return null;
            }
            return dx > 0 ? BlockFace.WEST : BlockFace.EAST;
        }
        if (ty >= tx && ty >= tz) {
            if (ty - Math.max(tx, tz) <= BUCKET_FACE_MARGIN) {
                return null;
            }
            return dy > 0 ? BlockFace.DOWN : BlockFace.UP;
        }
        if (tz - Math.max(tx, ty) <= BUCKET_FACE_MARGIN) {
            return null;
        }
        return dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
    }

    private boolean isUltrawarmDimension() {
        if (dimension == null) {
            return true;
        }
        if (dimension.fastLava() != null) {
            return dimension.fastLava();
        }
        return !"minecraft:overworld".equals(dimension.key()) && !"minecraft:the_end".equals(dimension.key());
    }

    private boolean isPureFluidSource(int state) {
        String block = states.block(state);
        return ("minecraft:water".equals(block) || "minecraft:lava".equals(block))
                && states.fluidAmount(state) == 8 && !states.fluidFalling(state);
    }

    private boolean bucketHitBlockSolidForLiquid(int state, boolean water) {
        if (!states.has(state, BlockStates.FULL_BLOCK)
                || states.has(state, BlockStates.REPLACEABLE | BlockStates.CUSTOM_REPLACING | BlockStates.INTERACTIVE)
                || reacts(state)
                || states.fluidKind(state) != BlockStates.FLUID_NONE) {
            return false;
        }
        if (water) {
            if (states.properties(state).contains("waterlogged=") || states.block(state).contains("sponge")) {
                return false;
            }
        }
        return true;
    }

    private boolean bucketTargetAndNeighboursValid(int sequence, int tx, int ty, int tz, boolean pickup, boolean checkTargetAir) {
        if (ty < minY() || ty >= minY() + height() || !isLoaded(tx >> 4, tz >> 4)) {
            return false;
        }
        int[] target = possibleStatesBefore(tx, ty, tz, sequence);
        if (target == null || target.length != 1) {
            return false;
        }
        if (checkTargetAir && (!states.isAir(target[0]) || !"minecraft:air".equals(states.block(target[0])))) {
            return false;
        }
        if (pickup && !isPureFluidSource(target[0])) {
            return false;
        }
        for (int i = 1; i < PREDICTION_NEIGHBOURS.length; i++) {
            int[] o = PREDICTION_NEIGHBOURS[i];
            int[] beside = possibleStatesBefore(tx + o[0], ty + o[1], tz + o[2], sequence);
            if (beside == null || beside.length != 1) {
                return false;
            }
            int ns = beside[0];
            if (reacts(ns) || states.fluidKind(ns) != BlockStates.FLUID_NONE || states.block(ns).contains("sponge")) {
                return false;
            }
        }
        return true;
    }

    private static double[] viewVector(float xRot, float yRot) {
        float x = xRot * (float) (Math.PI / 180.0);
        float y = -yRot * (float) (Math.PI / 180.0);
        float cosY = VanillaMath.cos(y);
        float sinY = VanillaMath.sin(y);
        float cosX = VanillaMath.cos(x);
        float sinX = VanillaMath.sin(x);
        return new double[] {sinY * cosX, -sinX, cosY * cosX};
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

    /** Primitive {@code long -> positive count} table eliminating {@code Long} boxing on chunk/block keys. */
    private static final class LongCounterMap {
        private long[] keys = new long[32];
        private int[] counts = new int[32];
        private int size;
        private int zeroCount;

        boolean isEmpty() {
            return size == 0 && zeroCount == 0;
        }

        int size() {
            return size + (zeroCount > 0 ? 1 : 0);
        }

        void clear() {
            if (size > 0) {
                Arrays.fill(counts, 0);
                size = 0;
            }
            zeroCount = 0;
        }

        boolean containsKey(long key) {
            if (key == 0L) {
                return zeroCount > 0;
            }
            if (size == 0) {
                return false;
            }
            int mask = keys.length - 1;
            for (int i = mix(key) & mask; ; i = (i + 1) & mask) {
                int c = counts[i];
                if (c == 0) {
                    return false;
                }
                if (keys[i] == key) {
                    return true;
                }
            }
        }

        void increment(long key) {
            if (key == 0L) {
                zeroCount++;
                return;
            }
            if (2 * (size + 1) > keys.length) {
                rehash();
            }
            insert(key);
        }

        private void insert(long key) {
            int mask = keys.length - 1;
            int i = mix(key) & mask;
            while (counts[i] != 0) {
                if (keys[i] == key) {
                    counts[i]++;
                    return;
                }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            counts[i] = 1;
            size++;
        }

        void decrement(long key) {
            if (key == 0L) {
                if (zeroCount > 0) {
                    zeroCount--;
                }
                return;
            }
            int mask = keys.length - 1;
            int gap = -1;
            for (int i = mix(key) & mask; counts[i] != 0; i = (i + 1) & mask) {
                if (keys[i] == key) {
                    if (counts[i] > 1) {
                        counts[i]--;
                        return;
                    }
                    gap = i;
                    break;
                }
            }
            if (gap < 0) {
                return;
            }
            for (int j = (gap + 1) & mask; counts[j] != 0; j = (j + 1) & mask) {
                int home = mix(keys[j]) & mask;
                if (((j - home) & mask) >= ((j - gap) & mask)) {
                    keys[gap] = keys[j];
                    counts[gap] = counts[j];
                    gap = j;
                }
            }
            keys[gap] = 0L;
            counts[gap] = 0;
            size--;
        }

        private void rehash() {
            long[] oldKeys = keys;
            int[] oldCounts = counts;
            keys = new long[oldKeys.length * 2];
            counts = new int[oldCounts.length * 2];
            int mask = keys.length - 1;
            for (int idx = 0; idx < oldKeys.length; idx++) {
                int c = oldCounts[idx];
                if (c != 0) {
                    long k = oldKeys[idx];
                    int i = mix(k) & mask;
                    while (counts[i] != 0) {
                        i = (i + 1) & mask;
                    }
                    keys[i] = k;
                    counts[i] = c;
                }
            }
        }

        private static int mix(long z) {
            z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
            z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
            return (int) (z ^ (z >>> 33));
        }
    }
}
