package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientWorldTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final int STONE = STATES.firstStateOf("minecraft:stone");
    private static final int DIRT = STATES.firstStateOf("minecraft:dirt");
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final int VOID_AIR = STATES.firstStateOf("minecraft:void_air");
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final ClientboundPacket.Dimension NETHER = new ClientboundPacket.Dimension("minecraft:the_nether", 0, 256);

    private ClientWorld world;
    private long sequence;
    private int pingId;

    @BeforeEach
    void setUp() {
        world = new ClientWorld(STATES);
        sequence = 0;
    }

    // --- event helpers ---

    private void out(ClientboundPacket packet) {
        world.onEvent(new TimelineEvent.Outbound(sequence++, sequence, packet));
    }

    private void in(ServerboundPacket packet) {
        world.onEvent(new TimelineEvent.Inbound(sequence++, sequence, 0, packet));
    }

    /** Sends a ping and returns its id (acknowledge it with {@link #ack}). */
    private int ping() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        return id;
    }

    private void ack(int id) {
        world.onEvent(new TimelineEvent.Acknowledged(sequence++, sequence, id, 0, 0));
    }

    /** Ping and acknowledge: the client processed everything sent so far. */
    private void sync() {
        ack(ping());
    }

    private void login(int radius, ClientboundPacket.Dimension dimension) {
        out(new ClientboundPacket.Login(1, radius, dimension));
    }

    /** A chunk whose every section is {@code state}, except section {@code index} which holds {@code special} at (1, 2, 3). */
    static byte[] chunk(int sections, int state, int index, int special) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0); // no heightmaps
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < sections; i++) {
            data.write(0);
            data.write(16); // non-empty count (short)
            if (i == index) {
                int bits = STATES.globalPaletteBits();
                data.write(bits); // global ids
                long[] longs = new long[Section.longsFor(bits, 4096)];
                int perLong = 64 / bits;
                for (int b = 0; b < 4096; b++) {
                    int value = b == Section.index(1, 2, 3) ? special : state;
                    longs[b / perLong] |= (long) value << (b % perLong) * bits;
                }
                for (long l : longs) {
                    for (int k = 7; k >= 0; k--) {
                        data.write((int) (l >>> 8 * k));
                    }
                }
            } else {
                data.write(0);
                varInt(data, state);
            }
            data.write(0); // biomes: one value
            varInt(data, 0);
        }
        varInt(body, data.size());
        body.writeBytes(data.toByteArray());
        varInt(body, 0); // block entities
        return body.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private void loadStone(int x, int z) {
        out(new ClientboundPacket.LevelChunk(x, z, chunk(24, STONE, -1, 0)));
    }

    // --- tests ---

    @Test
    void nothingBeforeALogin() {
        assertEquals(ClientWorld.UNKNOWN, world.blockState(0, 0, 0));
        loadStone(0, 0);
        assertEquals(ClientWorld.UNKNOWN, world.blockState(0, 0, 0)); // the client has no level to put it in
    }

    @Test
    void chunksDecodeAndUnloadedChunksAreVoidAir() {
        login(10, OVERWORLD);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, 5, DIRT)));
        assertEquals(DIRT, world.blockState(1, -64 + 5 * 16 + 2, 3));
        assertEquals(AIR, world.blockState(0, 70, 0));
        assertEquals(VOID_AIR, world.blockState(16, 70, 0)); // a chunk the client does not hold
        assertEquals(VOID_AIR, world.blockState(0, -65, 0)); // below the build height
        assertEquals(VOID_AIR, world.blockState(0, 320, 0)); // above it
        assertTrue(world.isLoaded(0, 0));
        assertFalse(world.isLoaded(1, 0));
    }

    @Test
    void chunksOutsideTheViewRangeAreIgnoredAsTheClientDoes() {
        login(2, OVERWORLD); // storage range max(2, 2) + 3 = 5
        loadStone(5, 0);
        loadStone(6, 0);
        assertTrue(world.isLoaded(5, 0));
        assertFalse(world.isLoaded(6, 0));
        assertEquals(1, world.stats().chunksIgnored());
    }

    @Test
    void theRingBufferReplacesTheChunkInTheSameSlot() {
        login(2, OVERWORLD); // view range 11
        loadStone(-5, 0);
        out(new ClientboundPacket.ChunkCacheCenter(6, 0));
        loadStone(6, 0); // floorMod(6, 11) == floorMod(-5, 11): same slot
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        assertFalse(world.isLoaded(-5, 0)); // replaced, as in ClientChunkCache.Storage.replace
    }

    @Test
    void movingTheCentreHidesChunksWithoutDroppingThem() {
        login(2, OVERWORLD);
        loadStone(-5, 0);
        out(new ClientboundPacket.ChunkCacheCenter(1, 0));
        assertFalse(world.isLoaded(-5, 0)); // out of range of the new centre
        assertEquals(VOID_AIR, world.blockState(-5 * 16, 0, 0));
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        assertTrue(world.isLoaded(-5, 0)); // still in its slot
    }

    @Test
    void forgetDropsAChunk() {
        login(10, OVERWORLD);
        loadStone(2, 3);
        out(new ClientboundPacket.ForgetLevelChunk(2, 3));
        assertFalse(world.isLoaded(2, 3));
    }

    @Test
    void blockUpdatesApplyOnlyInHeldChunksAndWithinTheBuildHeight() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(5, 10, 5), DIRT));
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(40, 10, 5), DIRT)); // chunk not held: ignored
        assertEquals(DIRT, world.blockState(5, 10, 5));
        assertEquals(STONE, world.blockState(6, 10, 5));
        assertEquals(VOID_AIR, world.blockState(40, 10, 5));
        long entry = (long) DIRT << 12 | 1 << 8 | 2 << 4 | 3; // x=1, z=2, y=3 in section (0, 1, 0)
        out(new ClientboundPacket.SectionBlocksUpdate(0, 1, 0, new long[] {entry}));
        assertEquals(DIRT, world.blockState(1, 16 + 3, 2));
    }

    @Test
    void aChangeIsUnsettledUntilATransactionSentAfterItIsAcknowledged() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        int before = ping();
        sync();
        assertTrue(world.settled(5, 10, 5));
        int early = ping();
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(5, 10, 5), DIRT));
        int late = ping();
        assertFalse(world.settled(5, 10, 5));
        assertTrue(world.settled(6, 10, 5));
        ack(before);
        ack(early); // sent before the change: proves nothing about it
        assertFalse(world.settled(5, 10, 5));
        ack(late);
        assertTrue(world.settled(5, 10, 5));
        assertEquals(DIRT, world.blockState(5, 10, 5));
    }

    @Test
    void anUnsettledBlockMayHoldEachStateBeforeItsPendingChanges() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        assertEquals(null, world.possibleStates(5, 10, 5)); // the chunk itself is not settled
        sync();
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 10, 5));
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(5, 10, 5), DIRT));
        int first = ping();
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(5, 10, 5), AIR));
        int second = ping();
        assertArrayEquals(new int[] {STONE, DIRT, AIR}, world.possibleStates(5, 10, 5));
        ack(first);
        assertArrayEquals(new int[] {DIRT, AIR}, world.possibleStates(5, 10, 5));
        ack(second);
        assertArrayEquals(new int[] {AIR}, world.possibleStates(5, 10, 5));
        // the client's own prediction: not known which state it holds
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, new BlockPosition(6, 10, 5), BlockFace.UP, 3));
        assertEquals(null, world.possibleStates(6, 10, 5));
        // a change in a chunk sent again later (away from the dig and its neighbours): the chunk decides
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(9, 10, 5), DIRT));
        loadStone(0, 0);
        assertEquals(null, world.possibleStates(9, 10, 5));
        sync();
        assertArrayEquals(new int[] {STONE}, world.possibleStates(9, 10, 5));
    }

    @Test
    void aNewChunkIsUnsettledUntilAcknowledged() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        assertFalse(world.settled(1, 1, 1));
        sync();
        assertTrue(world.settled(1, 1, 1));
    }

    @Test
    void theClientsOwnPredictionsStayUnsettledUntilTheServersAckReachesIt() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        sync();
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(5, 10, 5), BlockFace.UP, new Vec3d(5.5, 11, 5.5), false, false, 7));
        assertFalse(world.settled(5, 10, 5));
        assertFalse(world.settled(5, 11, 5)); // the placed block
        assertFalse(world.settled(5, 12, 5)); // and its neighbours (a door's upper half)
        assertTrue(world.settled(9, 10, 9));
        sync();
        assertFalse(world.settled(5, 11, 5)); // no ack from the server yet
        out(new ClientboundPacket.BlockChangedAck(6)); // an older action
        sync();
        assertFalse(world.settled(5, 11, 5));
        out(new ClientboundPacket.BlockChangedAck(7));
        assertFalse(world.settled(5, 11, 5)); // sent, not yet processed by the client
        sync();
        assertTrue(world.settled(5, 11, 5));
    }

    // --- block uses resolved by the caller (ClientWorld.placement, resolveUse) ---

    private static final int CHEST = STATES.firstStateOf("minecraft:chest");
    private static final int FENCE = STATES.firstStateOf("minecraft:oak_fence");
    private static final int SHORT_GRASS = STATES.firstStateOf("minecraft:short_grass");
    private static final int OAK_LOG = STATES.firstStateOf("minecraft:oak_log");
    private static final int CONCRETE_POWDER = STATES.onlyStateOf("minecraft:white_concrete_powder");
    private static final int GRASS_BLOCK = STATES.firstStateOf("minecraft:grass_block");

    private void useOn(int x, int y, int z, BlockFace face, int sequence) {
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(x, y, z), face, new Vec3d(x + 0.5, y + 1, z + 0.5), false, false, sequence));
    }

    private void setBlock(int x, int y, int z, int state) {
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(x, y, z), state));
    }

    /** An empty chunk at 0, 0 with stone at (5, 10, 5), all settled. */
    private void stoneInTheAir() {
        login(10, OVERWORLD);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, -1, 0)));
        setBlock(5, 10, 5, STONE);
        sync();
    }

    private static int[] distinct(int[] states) {
        return states == null ? null : java.util.Arrays.stream(states).distinct().sorted().toArray();
    }

    @Test
    void aResolvedPlacementHoldsItsStateUntilTheAckAndTheServersStateAfter() {
        assertEquals(STATES.firstStateOf("minecraft:stone"), STATES.onlyStateOf("minecraft:stone"));
        assertEquals(-1, STATES.onlyStateOf("minecraft:oak_log"));
        stoneInTheAir();
        useOn(5, 10, 5, BlockFace.UP, 7);
        assertNull(world.possibleStates(5, 11, 5)); // not resolved: the default, a prediction not known
        BlockPosition first = world.placement(7, STONE);
        assertEquals(new BlockPosition(5, 11, 5), first);
        world.resolveUse(7, first, STONE);
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 11, 5));
        assertFalse(world.settled(5, 11, 5));
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 10, 5)); // clicked, kept
        assertArrayEquals(new int[] {AIR}, world.possibleStates(5, 12, 5)); // neighbours, kept
        assertArrayEquals(new int[] {AIR}, world.possibleStates(6, 11, 5));
        assertArrayEquals(new int[] {AIR}, world.possibleStatesBefore(5, 11, 5, 7)); // what the use's own pick saw

        // a tower: the next use clicks the block the first one placed, before any ack
        useOn(5, 11, 5, BlockFace.UP, 8);
        BlockPosition second = world.placement(8, STONE);
        assertEquals(new BlockPosition(5, 12, 5), second);
        world.resolveUse(8, second, STONE);
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 12, 5));
        assertArrayEquals(new int[] {AIR}, world.possibleStatesBefore(5, 12, 5, 8));
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 11, 5));

        // the server places the first too; the client keeps its own state for it until the ack
        setBlock(5, 11, 5, STONE);
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 11, 5));
        out(new ClientboundPacket.BlockChangedAck(7));
        assertArrayEquals(new int[] {STONE}, distinct(world.possibleStates(5, 11, 5))); // the server's as of the ack: stone too
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 12, 5)); // the ack is not the second use's
        sync();
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 11, 5));

        // the server refuses the second: the client holds stone until it processes the ack, air after
        setBlock(5, 12, 5, AIR);
        out(new ClientboundPacket.BlockChangedAck(8));
        assertArrayEquals(new int[] {AIR, STONE}, distinct(world.possibleStates(5, 12, 5)));
        sync();
        assertArrayEquals(new int[] {AIR}, world.possibleStates(5, 12, 5));
        assertTrue(world.settled(5, 12, 5));
        assertTrue(world.settled(5, 11, 5));
        assertNull(world.placement(8, STONE)); // acknowledged: no longer one to resolve
    }

    @Test
    void anAckSentBeforeTheServersChangeLeavesTheStateBeforeItPossible() {
        stoneInTheAir();
        useOn(5, 10, 5, BlockFace.UP, 7);
        world.resolveUse(7, world.placement(7, STONE), STONE);
        out(new ClientboundPacket.BlockChangedAck(7)); // before the server's own change: the client may restore air
        setBlock(5, 11, 5, STONE);
        assertArrayEquals(new int[] {AIR, STONE}, distinct(world.possibleStates(5, 11, 5)));
    }

    @Test
    void whatTheServerSendsAfterAUseIsNotWhatTheUseSaw() {
        stoneInTheAir();
        useOn(5, 10, 5, BlockFace.UP, 7);
        setBlock(5, 10, 5, STONE); // the server's reply: both positions, the placed block among them
        setBlock(5, 11, 5, STONE);
        assertEquals(new BlockPosition(5, 11, 5), world.placement(7, STONE)); // the client used it on air, before those
    }

    @Test
    void aBlockedUseKeepsEveryPosition() {
        stoneInTheAir();
        useOn(5, 10, 5, BlockFace.UP, 7);
        world.resolveUse(7, null, 0);
        assertArrayEquals(new int[] {AIR}, world.possibleStates(5, 11, 5));
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 10, 5));
        assertFalse(world.settled(5, 11, 5)); // still a prediction until the ack: only its outcome is known
        assertThrows(IllegalArgumentException.class, () -> world.resolveUse(7, new BlockPosition(9, 9, 9), STONE));
    }

    @Test
    void aPlacementIsOnlyWhereTheBlocksDecideIt() {
        stoneInTheAir();
        useOn(5, 10, 5, BlockFace.UP, 7);
        assertNull(world.placement(7, OAK_LOG)); // its state depends on the click (CUSTOM_PLACEMENT)
        assertNull(world.placement(7, CONCRETE_POWDER)); // a shape update may make it concrete
        assertNull(world.placement(99, STONE)); // no such use
        setBlock(4, 10, 4, CHEST);
        sync();
        useOn(4, 10, 4, BlockFace.UP, 8);
        assertNull(world.placement(8, STONE)); // the chest's own use comes first
        setBlock(7, 11, 7, SHORT_GRASS);
        sync();
        useOn(7, 10, 7, BlockFace.UP, 9);
        assertNull(world.placement(9, STONE)); // short grass is replaced, but it reacts to the change
        setBlock(2, 10, 2, STONE);
        setBlock(2, 11, 3, FENCE);
        sync();
        useOn(2, 10, 2, BlockFace.UP, 10);
        assertNull(world.placement(10, STONE)); // a fence next to the new block connects to it
        setBlock(9, 319, 9, STONE);
        sync();
        useOn(9, 319, 9, BlockFace.UP, 11);
        assertNull(world.placement(11, STONE)); // above the build height
        assertNull(world.placement(12, STONE));
        useOn(9, 319, 9, BlockFace.DOWN, 12);
        assertNull(world.placement(12, STONE)); // use 11, not resolved, may have changed the clicked block
        setBlock(12, 319, 12, STONE);
        setBlock(13, 10, 13, STONE);
        sync();
        useOn(12, 319, 12, BlockFace.DOWN, 13);
        assertEquals(new BlockPosition(12, 318, 12), world.placement(13, STONE));
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, new BlockPosition(13, 10, 13), BlockFace.UP, 14));
        useOn(13, 10, 13, BlockFace.UP, 15);
        assertNull(world.placement(15, STONE)); // the clicked block may be the dig's prediction
    }

    @Test
    void aNeighbourWhoseUpdateKeepsItsBlockMayHoldAnyOfItsStates() {
        login(10, OVERWORLD);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, -1, 0)));
        setBlock(5, 10, 5, GRASS_BLOCK); // snowy=true: the block above decides it (SnowyDirtBlock.updateShape)
        sync();
        useOn(5, 10, 5, BlockFace.UP, 7);
        BlockPosition at = world.placement(7, STONE);
        assertEquals(new BlockPosition(5, 11, 5), at);
        world.resolveUse(7, at, STONE);
        assertArrayEquals(STATES.statesOfBlock(GRASS_BLOCK), world.possibleStates(5, 10, 5));
        assertEquals(2, STATES.statesOfBlock(GRASS_BLOCK).length);
        assertArrayEquals(new int[] {STONE}, world.possibleStates(5, 11, 5));
        assertArrayEquals(new int[] {AIR}, world.possibleStates(5, 12, 5));
        useOn(5, 10, 5, BlockFace.UP, 8); // blocked this time: nothing set, no update
        world.resolveUse(8, null, 0);
        assertArrayEquals(STATES.statesOfBlock(GRASS_BLOCK), world.possibleStates(5, 10, 5)); // use 7's still holds
    }

    @Test
    void aChunkSentAgainHoldsTheServersStateNotTheClientsOwn() {
        stoneInTheAir();
        useOn(5, 10, 5, BlockFace.UP, 7);
        world.resolveUse(7, world.placement(7, STONE), STONE);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, -1, 0)));
        sync();
        assertNull(world.possibleStates(5, 11, 5)); // the chunk replaced the placed block; the ack will restore the server's
    }

    @Test
    void plainItemUsesAreReportedAsUnlocatedPredictions() {
        login(10, OVERWORLD);
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 3, 0, 0));
        assertTrue(world.hasUnlocatedPrediction());
        out(new ClientboundPacket.BlockChangedAck(3));
        sync();
        assertFalse(world.hasUnlocatedPrediction());
    }

    @Test
    void lostEventsMakeEveryChunkStaleUntilItIsSentAgain() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        loadStone(1, 0);
        sync();
        world.onEvent(new TimelineEvent.Gap(sequence++, sequence, 3));
        assertEquals(ClientWorld.UNKNOWN, world.blockState(1, 1, 1));
        assertFalse(world.settled(1, 1, 1));
        loadStone(0, 0);
        sync();
        assertEquals(STONE, world.blockState(1, 1, 1));
        assertTrue(world.settled(1, 1, 1));
        assertEquals(ClientWorld.UNKNOWN, world.blockState(17, 1, 1));
    }

    @Test
    void anUnreadableWorldPacketMakesTheWorldStale() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        out(new ClientboundPacket.Untranslatable("minecraft:block_update"));
        assertEquals(ClientWorld.UNKNOWN, world.blockState(1, 1, 1));
    }

    @Test
    void aRespawnKeepsTheWorldUnlessTheDimensionChanges() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        out(new ClientboundPacket.Respawn(OVERWORLD));
        assertEquals(STONE, world.blockState(1, 1, 1));
        out(new ClientboundPacket.Respawn(NETHER));
        assertEquals(VOID_AIR, world.blockState(1, 1, 1));
        assertEquals(0, world.minY());
        assertEquals(256, world.height());
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(16, DIRT, -1, 0)));
        assertEquals(DIRT, world.blockState(1, 255, 1));
        assertEquals(VOID_AIR, world.blockState(1, -1, 1));
    }

    @Test
    void aSmallerViewRadiusKeepsOnlyTheChunksInTheNewRange() {
        login(10, OVERWORLD); // range 13
        loadStone(0, 0);
        loadStone(9, 0);
        out(new ClientboundPacket.ChunkCacheRadius(4)); // range 7
        assertTrue(world.isLoaded(0, 0));
        assertFalse(world.isLoaded(9, 0));
        out(new ClientboundPacket.ChunkCacheRadius(10));
        assertFalse(world.isLoaded(9, 0)); // dropped by the resize, not just hidden
    }

    @Test
    void aViewChangeLeavesBorderChunksUnsettledUntilAcknowledged() {
        login(2, OVERWORLD);
        loadStone(5, 0);
        sync();
        assertTrue(world.settled(5 * 16, 0, 0));
        out(new ClientboundPacket.ChunkCacheCenter(-1, 0)); // chunk 5 leaves the range
        loadStone(-6, 0);
        assertFalse(world.settled(-6 * 16, 0, 0)); // the client may still use the old centre
        sync();
        assertTrue(world.settled(-6 * 16, 0, 0));
    }

    @Test
    void theDigestHashesEverySection() {
        login(10, OVERWORLD);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, 5, DIRT)));
        ClientWorld.Digest digest = world.digest();
        long[] hashes = digest.chunks().get(ClientWorld.chunkKey(0, 0));
        int[] air = new int[4096];
        java.util.Arrays.fill(air, AIR);
        assertEquals(ClientWorld.sectionHash(air), hashes[0]);
        air[Section.index(1, 2, 3)] = DIRT;
        assertEquals(ClientWorld.sectionHash(air), hashes[5]);
    }

    @Test
    void identicalSectionsAreSharedBeforeAndAfterTheSameChange() {
        SectionPool pool = new SectionPool();
        ClientWorld a = new ClientWorld(STATES, pool);
        world = a;
        login(10, OVERWORLD);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, 3, DIRT)));
        assertEquals(2, pool.size()); // the air section and the one with dirt
        ClientWorld b = new ClientWorld(STATES, pool);
        world = b;
        login(10, OVERWORLD);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, 3, DIRT)));
        assertEquals(2, pool.size()); // the second player's identical chunk adds nothing
        BlockPosition p = new BlockPosition(1, -64 + 3 * 16 + 5, 3);
        out(new ClientboundPacket.BlockUpdate(p, STONE));
        assertEquals(3, pool.size()); // b's changed section; a still holds the original
        assertEquals(STONE, b.blockState(p.x(), p.y(), p.z()));
        assertEquals(AIR, a.blockState(p.x(), p.y(), p.z())); // the other world is untouched
        assertEquals(DIRT, b.blockState(1, -64 + 3 * 16 + 2, 3)); // and b keeps the rest of the section
        world = a;
        out(new ClientboundPacket.BlockUpdate(p, STONE)); // the same change for a: the same section again
        assertEquals(2, pool.size());
        a.onEvent(new TimelineEvent.Disconnected(sequence++, sequence));
        b.onEvent(new TimelineEvent.Disconnected(sequence++, sequence));
        assertEquals(0, pool.size());
        assertEquals(0, pool.uniqueBytes());
    }

    @Test
    void changesGrowThePaletteAndTheStorageLikeTheGame() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        int count = 0;
        for (int id = 1; id < STATES.size() && count < 300; id += 97, count++) { // past 16 and 256 distinct states
            out(new ClientboundPacket.BlockUpdate(new BlockPosition(count & 15, (count >> 4) & 15, (count >> 8) & 15), id));
        }
        count = 0;
        for (int id = 1; id < STATES.size() && count < 300; id += 97, count++) {
            assertEquals(id, world.blockState(count & 15, (count >> 4) & 15, (count >> 8) & 15));
        }
        assertEquals(STONE, world.blockState(15, 15, 15));
        long[] sectionUpdate = {(long) DIRT << 12 | 15 << 8 | 15 << 4 | 15};
        out(new ClientboundPacket.SectionBlocksUpdate(0, 0, 0, sectionUpdate));
        assertEquals(DIRT, world.blockState(15, 15, 15));
    }

    @Test
    void replacingForgettingAndResizingReleaseSections() {
        SectionPool pool = new SectionPool();
        world = new ClientWorld(STATES, pool);
        login(10, OVERWORLD);
        loadStone(0, 0);
        loadStone(0, 0); // resent
        loadStone(9, 0);
        out(new ClientboundPacket.ForgetLevelChunk(0, 0));
        assertEquals(1, pool.size()); // chunk (9, 0) still holds the stone section
        out(new ClientboundPacket.ChunkCacheRadius(2)); // range 5: (9, 0) is dropped
        assertEquals(0, pool.size());
    }


    // --- the crosshair's ray ---

    /** Half the size of the eyes' box in these tests. */
    private static final double R = 1.0E-4;

    @Test
    void blockChangesAreKnownSinceASequence() {
        airWorld();
        long before = sequence;
        assertFalse(world.mayHaveChangedIn(0, 70, 0, 1, 71, 1, before), "nothing changed since");
        put(1, 70, 1, STONE);
        assertTrue(world.mayHaveChangedIn(0, 70, 0, 1, 71, 1, before), "not settled yet");
        sync();
        long after = sequence;
        assertTrue(world.mayHaveChangedIn(0, 70, 0, 1, 71, 1, before), "changed at or after it");
        assertFalse(world.mayHaveChangedIn(0, 70, 0, 1, 71, 1, after), "settled, and nothing since");
        assertFalse(world.mayHaveChangedIn(3, 70, 3, 4, 71, 4, before), "elsewhere");
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, new BlockPosition(3, 70, 3),
                BlockFace.UP, 7));
        assertTrue(world.mayHaveChangedIn(3, 70, 3, 4, 71, 4, after), "the client's own prediction");
        long view = sequence;
        out(new ClientboundPacket.ChunkCacheCenter(20, 0)); // range 10 + 3: chunk 0 leaves it, chunk 10 stays in
        sync();
        assertTrue(world.mayHaveChangedIn(8, 70, 8, 8, 70, 8, view), "the view hid its chunk");
        assertFalse(world.mayHaveChangedIn(168, 70, 8, 168, 70, 8, view), "a chunk the view kept");
    }

    private static int state(String block, String properties) {
        for (int id = 0; id < STATES.size(); id++) {
            if (STATES.block(id).equals(block) && STATES.properties(id).equals(properties)) {
                return id;
            }
        }
        throw new AssertionError(block + "[" + properties + "]");
    }

    /** An overworld of air in chunk (0, 0), the client up to date with it. */
    private void airWorld() {
        login(10, OVERWORLD);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, -1, 0)));
        sync();
    }

    private void put(int x, int y, int z, int state) {
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(x, y, z), state));
    }

    /** From the eyes within {@code r} of the point, along +x. */
    private double east(double x, double y, double z, double r, double maxT) {
        return world.certainOutlineHit(x - r, y - r, z - r, x + r, y + r, z + r, 1, 0, 0, maxT);
    }

    @Test
    void aFullBlockStopsTheRayWhereItEntersIt() {
        airWorld();
        put(3, 65, 0, STONE);
        sync();
        assertEquals(2.5, east(0.5, 65.62, 0.5, R, 10), 1e-3);
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 2.4), "not before maxT");
        assertEquals(Double.POSITIVE_INFINITY, world.certainOutlineHit(0.4, 65.6, 0.4, 0.6, 65.7, 0.6, -1, 0, 0, 10), "nothing behind");
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 66.62, 0.5, R, 10), "above it");
    }

    @Test
    void aBlockStopsTheRayOnlyIfItStopsTheRayFromEveryEye() {
        airWorld();
        put(3, 65, 0, STONE);
        sync();
        assertEquals(2.95, east(0.5, 65.5, 0.5, 0.45, 10), 1e-3); // where the ray from the centre is 0.45 deep in it
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.5, 0.5, 0.55, 10), "a ray beside the block");
        assertEquals(Double.POSITIVE_INFINITY, world.certainOutlineHit(0.5, 65.5, 0.5, 0.5, 66.2, 0.5, 1, 0, 0, 10), "an eye above it");
    }

    @Test
    void onlyTheOutlineStopsTheRay() {
        airWorld();
        put(3, 65, 0, state("minecraft:stone_slab", "type=bottom,waterlogged=false"));
        put(4, 65, 0, state("minecraft:light", "level=15,waterlogged=false")); // empty unless the player holds light
        sync();
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10), "over the slab");
        assertEquals(2.5, east(0.5, 65.25, 0.5, R, 10), 1e-3);
    }

    @Test
    void noClaimPastACellWhoseOutlineMayLeaveIt() {
        airWorld();
        put(3, 65, 0, STONE);
        put(5, 65, 0, STATES.firstStateOf("minecraft:lectern")); // past the stone: the client stopped before
        sync();
        assertEquals(2.5, east(0.5, 65.62, 0.5, R, 10), 1e-3);
        for (String block : new String[] {"minecraft:lectern", "minecraft:poppy", "minecraft:shulker_box"}) {
            put(2, 65, 0, STATES.firstStateOf(block));
            sync();
            assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10), block + " before the stone");
        }
    }

    @Test
    void shortGrassStopsTheRayItsOffsetDoesNotMove() {
        // TallGrassBlock.getShape ignores the offset; the pick (Block.OUTLINE) stops in it: its box x 2/16..14/16, y to 13/16
        airWorld();
        put(5, 65, 0, STONE);
        put(2, 65, 0, STATES.firstStateOf("minecraft:short_grass"));
        sync();
        assertEquals(1.625, east(0.5, 65.62, 0.5, R, 10), 1e-3);
        assertEquals(4.5, east(0.5, 65.9, 0.5, R, 10), 1e-3, "over it");
        put(2, 65, 0, STATES.firstStateOf("minecraft:poppy")); // FlowerBlock.getShape moves it by the offset
        sync();
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.5, 0.5, R, 10));
    }

    @Test
    void aBlockStopsTheRayOnlyIfItIsThereInEveryStateTheClientMayHold() {
        airWorld();
        put(3, 65, 0, STONE);
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10), "the client may not have it yet");
        sync();
        put(3, 65, 0, AIR);
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10), "the client may not hold it any more");
        put(3, 65, 0, DIRT);
        put(3, 65, 0, STONE);
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10), "air in between");
        sync();
        assertEquals(2.5, east(0.5, 65.62, 0.5, R, 10), 1e-3);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, -1, 0)));
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10), "a chunk the client may not have");
    }

    @Test
    void aRayIsNotStoppedByABoxItMayStartIn() {
        airWorld();
        put(0, 65, 0, STONE);
        sync();
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10));
        put(3, 65, 0, STONE);
        sync();
        assertEquals(2.5, east(0.5, 65.62, 0.5, R, 10), 1e-3);
    }

    @Test
    void noClaimPastACellWhoseOutlineMayLeaveItWhicheverWayTheRayGoes() {
        airWorld();
        put(2, 65, 0, STONE);
        put(4, 65, 0, STATES.firstStateOf("minecraft:lectern"));
        sync();
        double west = world.certainOutlineHit(5.5 - R, 65.62 - R, 0.5 - R, 5.5 + R, 65.62 + R, 0.5 + R, -1, 0, 0, 10);
        assertEquals(Double.POSITIVE_INFINITY, west, "the lectern before the stone");
        put(4, 65, 0, AIR);
        sync();
        assertEquals(2.5, world.certainOutlineHit(5.5 - R, 65.62 - R, 0.5 - R, 5.5 + R, 65.62 + R, 0.5 + R, -1, 0, 0, 10), 1e-3);
    }

    @Test
    void noClaimPastACellNotKnown() {
        airWorld();
        out(new ClientboundPacket.LevelChunk(1, 0, chunk(24, AIR, -1, 0)));
        put(17, 65, 0, STONE);
        sync();
        assertEquals(2.5, east(14.5, 65.62, 0.5, R, 10), 1e-3);
        out(new ClientboundPacket.LevelChunk(0, 0, chunk(24, AIR, -1, 0))); // the eye's chunk again: the client may not have it yet
        assertEquals(Double.POSITIVE_INFINITY, east(14.5, 65.62, 0.5, R, 10));
    }

    @Test
    void theWorldBeforeAnActionLeavesOutThatActionsOwnPredictionsOnly() {
        login(10, OVERWORLD);
        loadStone(0, 0);
        sync();
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(5, 10, 5), BlockFace.UP, new Vec3d(5.5, 11, 5.5), false, false, 7));
        assertEquals(null, world.possibleStates(5, 10, 5));
        assertArrayEquals(new int[] {STONE}, world.possibleStatesBefore(5, 10, 5, 7), "what the pick saw");
        assertEquals(null, world.possibleStatesBefore(5, 10, 5, 8), "a later action's pick may see the prediction");
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(5, 10, 5), BlockFace.UP, new Vec3d(5.5, 11, 5.5), false, false, 8));
        assertEquals(null, world.possibleStatesBefore(5, 10, 5, 8), "the earlier prediction is still the client's");
        out(new ClientboundPacket.BlockChangedAck(7));
        sync();
        assertArrayEquals(new int[] {STONE}, world.possibleStatesBefore(5, 10, 5, 8), "acknowledged: only the later one is left");
        assertEquals(null, world.possibleStates(5, 10, 5));
    }

    private static ServerboundPacket.PlayerAction dig(ServerboundPacket.PlayerAction.Action action, int x, int y, int z, int sequence) {
        return new ServerboundPacket.PlayerAction(action, new BlockPosition(x, y, z), BlockFace.UP, sequence);
    }

    @Test
    void aBreakMayChangeTheBlocksNextToItThatReactUntilItsAck() {
        // destroyBlock's setBlock(pos, air, 11) shape-updates the neighbours on the client, inside the prediction: a fence
        // next to the broken block loses its side there until the ack; a stone next to it keeps its state
        int fence = STATES.firstStateOf("minecraft:oak_fence");
        airWorld();
        put(3, 65, 0, STONE);
        put(4, 65, 0, fence);
        put(3, 65, 1, STONE);
        sync();
        in(dig(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, 3, 65, 0, 4));
        assertNull(world.possibleStates(3, 65, 0), "the broken block: the client's prediction");
        assertNull(world.possibleStates(4, 65, 0), "a neighbour that reacts");
        assertArrayEquals(new int[] {STONE}, world.possibleStates(3, 65, 1), "a neighbour that does not: it keeps its state");
        assertArrayEquals(new int[] {AIR}, world.possibleStates(2, 65, 0));
        assertFalse(world.settled(3, 65, 1), "still the prediction's position");
        out(new ClientboundPacket.BlockChangedAck(4));
        sync();
        assertArrayEquals(new int[] {fence}, world.possibleStates(4, 65, 0), "acknowledged: the server's");
        assertArrayEquals(new int[] {STONE}, world.possibleStates(3, 65, 0));
    }

    @Test
    void aBreakNextToABlockThatKeepsItsBlockMayGiveItAnyOfItsStates() {
        int grass = STATES.firstStateOf("minecraft:grass_block");
        airWorld();
        put(3, 65, 0, STONE);
        put(3, 66, 0, grass);
        sync();
        in(dig(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, 3, 65, 0, 4));
        assertArrayEquals(STATES.statesOfBlock(grass), world.possibleStates(3, 66, 0), "snowy or not");
        assertEquals(2, STATES.statesOfBlock(grass).length);
    }

    @Test
    void aBreakOfABlockWithHooksOfItsOwnLeavesItsNeighboursUnknown() {
        // a door's playerWillDestroy, a redstone ore's attack: code of their own around the client's break
        airWorld();
        put(3, 65, 0, STATES.firstStateOf("minecraft:oak_door"));
        put(3, 65, 1, STONE);
        sync();
        in(dig(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, 3, 65, 0, 4));
        assertNull(world.possibleStates(3, 65, 1));
    }

    @Test
    void aStartResolvedAsOnlyStartingTheDigSetNothing() {
        int fence = STATES.firstStateOf("minecraft:oak_fence");
        airWorld();
        put(3, 65, 0, STONE);
        put(4, 65, 0, fence);
        sync();
        in(dig(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, 3, 65, 0, 4));
        assertArrayEquals(new int[] {STONE}, world.digBefore(4));
        assertNull(world.possibleStates(3, 65, 0));
        world.resolveDigStartedOnly(4);
        assertArrayEquals(new int[] {STONE}, world.possibleStates(3, 65, 0));
        assertArrayEquals(new int[] {fence}, world.possibleStates(4, 65, 0), "no update came: the fence too");
        // a finish breaks it: never resolved so
        in(dig(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, 3, 65, 0, 5));
        world.resolveDigStartedOnly(5);
        assertNull(world.possibleStates(3, 65, 0));
        out(new ClientboundPacket.BlockChangedAck(5));
        sync();
        assertNull(world.digBefore(4), "acknowledged: forgotten");
        world.resolveDigStartedOnly(4);
        assertArrayEquals(new int[] {STONE}, world.possibleStates(3, 65, 0));
    }

    @Test
    void anAbortPredictsNothing() {
        // the dig restarted (the held item changed): the abort, unnumbered, then the new start
        login(10, OVERWORLD);
        loadStone(0, 0);
        sync();
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.ABORT_DESTROY_BLOCK, new BlockPosition(5, 10, 5), BlockFace.UP, 0));
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, new BlockPosition(5, 10, 5), BlockFace.UP, 4));
        assertArrayEquals(new int[] {STONE}, world.possibleStatesBefore(5, 10, 5, 4));
    }

    @Test
    void anItemUseWhoseBlocksCannotBeLocatedHidesTheWorldAfterIt() {
        airWorld();
        put(3, 65, 0, STONE);
        sync();
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 5, 0F, 0F)); // a lily pad or a bucket: wherever the client's ray hit
        assertFalse(world.hasUnlocatedPredictionBefore(5));
        assertTrue(world.hasUnlocatedPredictionBefore(6));
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10));
        assertEquals(2.5, world.certainOutlineHitBefore(0.5 - R, 65.62 - R, 0.5 - R, 0.5 + R, 65.62 + R, 0.5 + R, 1, 0, 0, 10, 5), 1e-3,
                "the pick of that use saw no change of its own");
    }

    @Test
    void theBlockAnActionClicksIsInTheWorldItsPickSaw() {
        airWorld();
        put(3, 65, 0, STONE);
        sync();
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(3, 65, 0), BlockFace.WEST, new Vec3d(3, 65.62, 0.5), false, false, 9));
        assertEquals(Double.POSITIVE_INFINITY, east(0.5, 65.62, 0.5, R, 10), "the client's prediction: not known");
        assertEquals(2.5, world.certainOutlineHitBefore(0.5 - R, 65.62 - R, 0.5 - R, 0.5 + R, 65.62 + R, 0.5 + R, 1, 0, 0, 10, 9), 1e-3);
    }

    @Test
    void resolvesPillarAxisAndNonWaterloggedSlabPlacements() {
        stoneInTheAir();
        // Clicking UP with an oak_log places axis=y at (5, 11, 5)
        useOn(5, 10, 5, BlockFace.UP, 10);
        ClientWorld.Placement logY = world.placementForItem(10, "minecraft:oak_log");
        assertNotNull(logY);
        assertEquals(new BlockPosition(5, 11, 5), logY.position());
        assertEquals(STATES.stateOf("minecraft:oak_log", "axis=y"), logY.state());
        world.resolveUse(10, logY.position(), logY.state());

        // Clicking EAST side with stone_slab hitting bottom half places type=bottom,waterlogged=false
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(5, 10, 5), BlockFace.EAST,
                new Vec3d(6.0, 10.25, 5.5), false, false, 11));
        ClientWorld.Placement slabBottom = world.placementForItem(11, "minecraft:stone_slab");
        assertNotNull(slabBottom);
        assertEquals(new BlockPosition(6, 10, 5), slabBottom.position());
        assertEquals(STATES.stateOf("minecraft:stone_slab", "type=bottom,waterlogged=false"), slabBottom.state());
        world.resolveUse(11, slabBottom.position(), slabBottom.state());
        assertArrayEquals(new int[] {slabBottom.state()}, world.possibleStates(6, 10, 5));
    }

    @Test
    void resolvesStraightStairPlacementsAndRejectsBesideExistingStairs() {
        stoneInTheAir();
        // Clicking UP on (5, 10, 5) with oak_stairs while facing north places straight bottom north stair at (5, 11, 5)
        useOn(5, 10, 5, BlockFace.UP, 10);
        ClientWorld.Placement stair = world.placementForItem(10, "minecraft:oak_stairs", "north");
        assertNotNull(stair);
        assertEquals(new BlockPosition(5, 11, 5), stair.position());
        assertEquals(STATES.stateOf("minecraft:oak_stairs", "facing=north,half=bottom,shape=straight,waterlogged=false"), stair.state());
        world.resolveUse(10, stair.position(), stair.state());

        // Clicking UP on (6, 10, 5) places horizontally adjacent to the existing oak_stairs at (5, 11, 5): shape=straight is no longer guaranteed
        put(6, 10, 5, STONE);
        sync();
        useOn(6, 10, 5, BlockFace.UP, 11);
        assertNull(world.placementForItem(11, "minecraft:oak_stairs", "east"));
    }

    @Test
    void resolvesPlainAndBucketItemUsesSoundly() {
        airWorld();
        put(3, 63, 0, STONE);
        sync();
        long since = sequence;

        // Plain UseItem cleared via resolveItemUseNoChange no longer blinds world queries
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 2, 0F, 0F));
        assertTrue(world.hasUnlocatedPrediction());
        assertTrue(world.mayHaveChangedIn(10, 64, 10, 11, 65, 11, since));
        world.resolveItemUseNoChange(2);
        assertFalse(world.hasUnlocatedPrediction());
        assertFalse(world.mayHaveChangedIn(10, 64, 10, 11, 65, 11, since));

        // Water bucket looking straight down at (3, 63, 0) from (3.5, 65.62, 0.5) places water[level=0] at (3, 64, 0)
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 3, 0F, 90F));
        ClientWorld.Placement placed = world.bucketPlacement(3, "minecraft:water_bucket",
                java.util.List.of(new Vec3d(3.5, 65.62, 0.5)), 0F, 90F, 4.5);
        assertNotNull(placed);
        assertEquals(new BlockPosition(3, 64, 0), placed.position());
        int water = STATES.stateOf("minecraft:water", "level=0");
        assertEquals(water, placed.state());
        world.resolveItemUseBlock(3, placed.position(), placed.state());
        assertFalse(world.hasUnlocatedPrediction());
        assertArrayEquals(new int[] {water}, world.possibleStates(3, 64, 0));

        // Empty bucket scoops the isolated water source back into air
        put(3, 64, 0, water);
        out(new ClientboundPacket.BlockChangedAck(3));
        sync();
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 4, 0F, 90F));
        ClientWorld.Placement scooped = world.bucketPlacement(4, "minecraft:bucket",
                java.util.List.of(new Vec3d(3.5, 65.62, 0.5)), 0F, 90F, 4.5);
        assertNotNull(scooped);
        assertEquals(new BlockPosition(3, 64, 0), scooped.position());
        assertEquals(AIR, scooped.state());
        world.resolveItemUseBlock(4, scooped.position(), scooped.state());
        assertArrayEquals(new int[] {AIR}, world.possibleStates(3, 64, 0));

        // Adjacent water source disqualifies pickup (infinite source guard)
        put(3, 64, 0, water);
        put(4, 64, 0, water);
        out(new ClientboundPacket.BlockChangedAck(4));
        sync();
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 5, 0F, 90F));
        assertNull(world.bucketPlacement(5, "minecraft:bucket",
                java.util.List.of(new Vec3d(3.5, 65.62, 0.5)), 0F, 90F, 4.5));

        // Ultrawarm dimension rejects water_bucket placement
        login(8, new ClientboundPacket.Dimension("minecraft:the_nether", 0, 256, true));
        sync();
        in(new ServerboundPacket.UseItem(Hand.MAIN_HAND, 1, 0F, 90F));
        assertNull(world.bucketPlacement(1, "minecraft:water_bucket",
                java.util.List.of(new Vec3d(3.5, 65.62, 0.5)), 0F, 90F, 4.5));
    }

    @Test
    void resolvesDoubleSlabMergeHorizontalDirectionalAndIsolatedFenceWall() {
        stoneInTheAir();
        // 1. Place bottom stone_slab at (5, 11, 5), then click UP on it to merge into double slab at (5, 11, 5)
        int bottomSlab = STATES.stateOf("minecraft:stone_slab", "type=bottom,waterlogged=false");
        int doubleSlab = STATES.stateOf("minecraft:stone_slab", "type=double,waterlogged=false");
        put(5, 11, 5, bottomSlab);
        sync();
        useOn(5, 11, 5, BlockFace.UP, 20);
        ClientWorld.Placement merged = world.placementForItem(20, "minecraft:stone_slab");
        assertNotNull(merged);
        assertEquals(new BlockPosition(5, 11, 5), merged.position());
        assertEquals(doubleSlab, merged.state());
        world.resolveUse(20, merged.position(), merged.state());
        assertArrayEquals(new int[] {doubleSlab}, world.possibleStates(5, 11, 5));

        // 2. 4-way HorizontalDirectionalBlock (white_glazed_terracotta): facing opposite player direction
        put(5, 11, 5, doubleSlab);
        out(new ClientboundPacket.BlockChangedAck(20));
        sync();
        useOn(5, 11, 5, BlockFace.UP, 21);
        ClientWorld.Placement glazed = world.placementForItem(21, "minecraft:white_glazed_terracotta", "north");
        assertNotNull(glazed);
        assertEquals(new BlockPosition(5, 12, 5), glazed.position());
        assertEquals(STATES.stateOf("minecraft:white_glazed_terracotta", "facing=south"), glazed.state());
        world.resolveUse(21, glazed.position(), glazed.state());

        // 3. carved_pumpkin above snow_block rejected (golem spawn side-effect guard)
        put(7, 10, 5, STATES.onlyStateOf("minecraft:snow_block"));
        out(new ClientboundPacket.BlockChangedAck(21));
        sync();
        useOn(7, 10, 5, BlockFace.UP, 22);
        assertNull(world.placementForItem(22, "minecraft:carved_pumpkin", "east"));

        // 4. Dry isolated oak_fence and cobblestone_wall resolve in open air, reject beside solid block
        put(10, 10, 10, STONE);
        out(new ClientboundPacket.BlockChangedAck(22));
        sync();
        useOn(10, 10, 10, BlockFace.UP, 23);
        ClientWorld.Placement fence = world.placementForItem(23, "minecraft:oak_fence");
        assertNotNull(fence);
        assertEquals(STATES.stateOf("minecraft:oak_fence", "east=false,north=false,south=false,waterlogged=false,west=false"), fence.state());
        world.resolveUse(23, fence.position(), fence.state());

        put(13, 10, 10, STONE);
        out(new ClientboundPacket.BlockChangedAck(23));
        sync();
        useOn(13, 10, 10, BlockFace.UP, 24);
        ClientWorld.Placement wall = world.placementForItem(24, "minecraft:cobblestone_wall");
        assertNotNull(wall);
        assertEquals(STATES.stateOf("minecraft:cobblestone_wall", "east=none,north=none,south=none,up=true,waterlogged=false,west=none"), wall.state());

        // Adjacent solid block at (12, 11, 10) disqualifies isolated wall
        put(12, 11, 10, STONE);
        out(new ClientboundPacket.BlockChangedAck(24));
        sync();
        useOn(13, 10, 10, BlockFace.UP, 25);
        assertNull(world.placementForItem(25, "minecraft:cobblestone_wall"));
    }

    @Test
    void resolvesIsolatedPanesBarsDryChainsAndEndRod() {
        stoneInTheAir();
        // 1. Isolated iron_bars and white_stained_glass_pane in open air
        useOn(5, 10, 5, BlockFace.UP, 30);
        ClientWorld.Placement bars = world.placementForItem(30, "minecraft:iron_bars");
        assertNotNull(bars);
        assertEquals(new BlockPosition(5, 11, 5), bars.position());
        assertEquals(STATES.stateOf("minecraft:iron_bars", "east=false,north=false,south=false,waterlogged=false,west=false"), bars.state());
        out(new ClientboundPacket.BlockChangedAck(30));

        put(8, 10, 5, STONE);
        sync();
        useOn(8, 10, 5, BlockFace.UP, 31);
        ClientWorld.Placement pane = world.placementForItem(31, "minecraft:white_stained_glass_pane");
        assertNotNull(pane);
        assertEquals(STATES.stateOf("minecraft:white_stained_glass_pane", "east=false,north=false,south=false,waterlogged=false,west=false"),
                pane.state());
        // Adjacent block disqualifies isolated pane
        put(7, 11, 5, STONE);
        out(new ClientboundPacket.BlockChangedAck(31));
        sync();
        useOn(8, 10, 5, BlockFace.UP, 32);
        assertNull(world.placementForItem(32, "minecraft:white_stained_glass_pane"));

        // 2. Dry iron_chain and copper_chain resolve axis from clicked face
        put(11, 10, 5, STONE);
        out(new ClientboundPacket.BlockChangedAck(32));
        sync();
        useOn(11, 10, 5, BlockFace.EAST, 33);
        ClientWorld.Placement chainX = world.placementForItem(33, "minecraft:iron_chain");
        assertNotNull(chainX);
        assertEquals(new BlockPosition(12, 10, 5), chainX.position());
        assertEquals(STATES.stateOf("minecraft:iron_chain", "axis=x,waterlogged=false"), chainX.state());

        out(new ClientboundPacket.BlockChangedAck(33));
        sync();
        useOn(11, 10, 5, BlockFace.UP, 34);
        ClientWorld.Placement copperChainY = world.placementForItem(34, "minecraft:copper_chain");
        assertNotNull(copperChainY);
        assertEquals(STATES.stateOf("minecraft:copper_chain", "axis=y,waterlogged=false"), copperChainY.state());

        // 3. end_rod against non-end_rod clicked block resolves facing=clickedFace; against end_rod returns null
        out(new ClientboundPacket.BlockChangedAck(34));
        sync();
        useOn(11, 10, 5, BlockFace.NORTH, 35);
        ClientWorld.Placement rod = world.placementForItem(35, "minecraft:end_rod");
        assertNotNull(rod);
        assertEquals(new BlockPosition(11, 10, 4), rod.position());
        assertEquals(STATES.stateOf("minecraft:end_rod", "facing=north"), rod.state());
        world.resolveUse(35, rod.position(), rod.state());
        assertArrayEquals(new int[] {rod.state()}, world.possibleStates(11, 10, 4));

        put(11, 10, 4, rod.state());
        out(new ClientboundPacket.BlockChangedAck(35));
        sync();
        useOn(11, 10, 4, BlockFace.NORTH, 36);
        assertNull(world.placementForItem(36, "minecraft:end_rod"), "clicking an existing end_rod may flip facing");

        // 4. Waterlogged slab, chain, and wooden trapdoor into settled water[level=0], plus dry trapdoor
        int water = STATES.stateOf("minecraft:water", "level=0");
        put(11, 11, 5, water);
        out(new ClientboundPacket.BlockChangedAck(36));
        sync();
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(11, 10, 5), BlockFace.UP,
                new Vec3d(11.5, 11.0, 5.5), false, false, 37));
        ClientWorld.Placement wlSlab = world.placementForItem(37, "minecraft:stone_slab");
        assertNotNull(wlSlab);
        assertEquals(STATES.stateOf("minecraft:stone_slab", "type=bottom,waterlogged=true"), wlSlab.state());
        ClientWorld.Placement wlChain = world.placementForItem(37, "minecraft:iron_chain");
        assertNotNull(wlChain);
        assertEquals(STATES.stateOf("minecraft:iron_chain", "axis=y,waterlogged=true"), wlChain.state());
        ClientWorld.Placement wlTrapdoor = world.placementForItem(37, "minecraft:oak_trapdoor", "north");
        assertNotNull(wlTrapdoor);
        assertEquals(STATES.stateOf("minecraft:oak_trapdoor", "facing=south,half=bottom,open=false,powered=false,waterlogged=true"),
                wlTrapdoor.state());

        // Dry trapdoor on horizontal EAST face upper half -> facing=east,half=top,waterlogged=false
        out(new ClientboundPacket.BlockChangedAck(37));
        sync();
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(11, 10, 5), BlockFace.EAST,
                new Vec3d(12.0, 10.75, 5.5), false, false, 38));
        ClientWorld.Placement dryTrapdoor = world.placementForItem(38, "minecraft:oak_trapdoor");
        assertNotNull(dryTrapdoor);
        assertEquals(STATES.stateOf("minecraft:oak_trapdoor", "facing=east,half=top,open=false,powered=false,waterlogged=false"),
                dryTrapdoor.state());
        assertNull(world.placementForItem(38, "minecraft:iron_trapdoor"));
    }
}
