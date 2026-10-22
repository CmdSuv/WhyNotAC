package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // a change in a chunk sent again later: the chunk decides
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(7, 10, 5), DIRT));
        loadStone(0, 0);
        assertEquals(null, world.possibleStates(7, 10, 5));
        sync();
        assertArrayEquals(new int[] {STONE}, world.possibleStates(7, 10, 5));
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
}
