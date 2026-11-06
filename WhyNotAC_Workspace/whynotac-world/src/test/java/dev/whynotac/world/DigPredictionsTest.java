package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DigPredictions} through the replicas as registered: a {@code START_DESTROY_BLOCK} that certainly only starts the dig
 * leaves the client's world as the server's; one that may break at once, in creative, or of a block with an
 * {@code attack} of its own stays the client's prediction. A survival {@code STOP_DESTROY_BLOCK} leaves the block's fluid;
 * in adventure, with instabuild, with a debug stick, or of a block with hooks of its own it stays the prediction.
 */
class DigPredictionsTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final UUID ME = new UUID(7, 7);
    private static final int SELF = 1;
    private static final int AIR = STATES.firstStateOf("minecraft:air");
    private static final Stack GOLDEN_PICKAXE = new Stack("minecraft:golden_pickaxe", 1, List.of(), List.of(), null, false);

    private Replicas replicas;
    private PlayerSession session;
    private long seq;
    private int pingId;
    private int actionSequence;

    private void open(int gameMode) {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        session = registry.openReplay("test");
        deliver(new TimelineEvent.Observation(seq++, 0, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(seq++, 0, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        deliver(new ClientFact(seq++, 0, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, 0, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, 0, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        deliver(new TimelineEvent.Bound(seq++, 0, ME, "me", SELF));
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD, gameMode));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, gameMode))));
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                out(new ClientboundPacket.LevelChunk(x, z, chunk(OVERWORLD.sections(), AIR)));
            }
        }
        out(new ClientboundPacket.SetHeldSlot(0));
        hold(Stack.EMPTY);
        sync();
        in(new ServerboundPacket.PlayerLoaded());
    }

    private void deliver(TimelineEvent event) {
        session.deliver(event);
    }

    private void out(ClientboundPacket packet) {
        deliver(new TimelineEvent.Outbound(seq++, 0, packet));
    }

    private void in(ServerboundPacket packet) {
        deliver(new TimelineEvent.Inbound(seq++, 0, 0, packet));
    }

    private void sync() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        in(new ServerboundPacket.Pong(id));
        deliver(new TimelineEvent.Acknowledged(seq++, 0, id, 0, 0));
    }

    private void hold(Stack stack) {
        List<Stack> items = new ArrayList<>(Collections.nCopies(46, Stack.EMPTY));
        items.set(36, stack);
        out(new ClientboundPacket.ContainerSetContent(0, 1, items, Stack.EMPTY));
    }

    /** The block dug, at (2, 65, 0), and stone next to it. */
    private int put(String block) {
        int state = STATES.firstStateOf(block);
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 65, 0), state));
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 65, 1), STATES.firstStateOf("minecraft:stone")));
        sync();
        return state;
    }

    private void start() {
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK, new BlockPosition(2, 65, 0),
                BlockFace.WEST, ++actionSequence));
    }

    private void stop() {
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, new BlockPosition(2, 65, 0),
                BlockFace.WEST, ++actionSequence));
    }

    private ClientWorld world() {
        return replicas.world().world(session);
    }

    @Test
    void aStartOfABlockAnEmptyHandDigsSlowlySetsNothing() {
        open(0);
        int dirt = put("minecraft:dirt");
        start();
        assertArrayEquals(new int[] {dirt}, world().possibleStates(2, 65, 0));
        assertArrayEquals(new int[] {AIR}, world().possibleStates(3, 65, 0));
    }

    @Test
    void aStartOfABlockThatBreaksAtOnceIsTheClientsPrediction() {
        open(0);
        put("minecraft:short_grass"); // hardness 0
        start();
        assertNull(world().possibleStates(2, 65, 0));
    }

    @Test
    void aToolFastEnoughMayBreakItAtOnce() {
        open(0);
        hold(GOLDEN_PICKAXE);
        sync();
        put("minecraft:netherrack"); // 12 / 0.4 / 30 = 1: a golden pickaxe breaks it at once
        start();
        assertNull(world().possibleStates(2, 65, 0));
        out(new ClientboundPacket.BlockChangedAck(actionSequence)); // until then that start may hold it
        int obsidian = put("minecraft:obsidian");
        start();
        assertArrayEquals(new int[] {obsidian}, world().possibleStates(2, 65, 0), "12 / 50 / 30");
    }

    @Test
    void aStartInCreativeBreaksIt() {
        open(1);
        put("minecraft:dirt");
        start();
        assertNull(world().possibleStates(2, 65, 0));
    }

    @Test
    void aBlockWithAnAttackOfItsOwnIsNotResolved() {
        open(0);
        put("minecraft:redstone_ore"); // attack lights it, inside the prediction
        start();
        assertNull(world().possibleStates(2, 65, 0));
        assertNull(world().possibleStates(2, 65, 1), "nor its neighbours");
    }

    @Test
    void aFinishInSurvivalLeavesAirUntilTheServersStatesCount() {
        open(0);
        int dirt = put("minecraft:dirt");
        stop();
        assertArrayEquals(new int[] {AIR}, world().possibleStates(2, 65, 0));
        assertArrayEquals(new int[] {STATES.firstStateOf("minecraft:stone")}, world().possibleStates(2, 65, 1), "its neighbour kept");
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 65, 0), dirt)); // the server refused it
        out(new ClientboundPacket.BlockChangedAck(actionSequence));
        assertArrayEquals(new int[] {dirt, AIR}, world().possibleStates(2, 65, 0), "the ack may have reached the client");
        sync();
        assertArrayEquals(new int[] {dirt}, world().possibleStates(2, 65, 0));
    }

    @Test
    void aWaterloggedBlockLeavesAWaterSource() {
        open(0);
        int slab = -1;
        for (int s : STATES.statesOfBlock(STATES.firstStateOf("minecraft:oak_slab"))) {
            if (STATES.properties(s).equals("type=bottom,waterlogged=true")) {
                slab = s;
            }
        }
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 65, 0), slab));
        sync();
        stop();
        int water = STATES.firstStateOf("minecraft:water");
        assertEquals("level=0", STATES.properties(water));
        assertArrayEquals(new int[] {water}, world().possibleStates(2, 65, 0));
    }

    @Test
    void aFinishThatMayBreakNothingIsNotKnown() {
        open(2); // adventure: blockActionRestricted
        put("minecraft:dirt");
        stop();
        assertNull(world().possibleStates(2, 65, 0));
    }

    @Test
    void aFinishWithInstabuildOrADebugStickIsNotKnown() {
        open(0);
        put("minecraft:dirt");
        out(new ClientboundPacket.PlayerAbilities(false, false, false, true, 0.05F, 0.1F));
        sync();
        stop();
        assertNull(world().possibleStates(2, 65, 0), "instabuild: a tool that cannot break in creative breaks nothing");
        out(new ClientboundPacket.PlayerAbilities(false, false, false, false, 0.05F, 0.1F));
        out(new ClientboundPacket.BlockChangedAck(actionSequence));
        hold(new Stack("minecraft:debug_stick", 1, List.of(), List.of(), null, false));
        sync();
        stop();
        assertNull(world().possibleStates(2, 65, 0), "the debug stick's canDestroyBlock refuses");
    }

    @Test
    void aFinishOfABlockWithHooksOrUnbreakableIsNotKnown() {
        open(0);
        put("minecraft:oak_door");
        stop();
        assertNull(world().possibleStates(2, 65, 0));
        out(new ClientboundPacket.BlockChangedAck(actionSequence));
        put("minecraft:bedrock");
        stop();
        assertNull(world().possibleStates(2, 65, 0));
    }

    private static byte[] chunk(int sections, int state) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < sections; i++) {
            data.write(0);
            data.write(0);
            data.write(0);
            varInt(data, state);
            data.write(0);
            varInt(data, 0);
        }
        varInt(body, data.size());
        body.writeBytes(data.toByteArray());
        varInt(body, 0);
        return body.toByteArray();
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
