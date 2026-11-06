package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ItemPrototypes;
import dev.whynotac.world.Replicas;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The check over the replicas it reads, fed a timeline as the plugin would. The tick counts are the vanilla client's own,
 * in its float arithmetic: dirt by hand 15 increments (1 / 0.5 / 30 each), stone with a diamond pickaxe 6 (8 / 1.5 / 30),
 * with haste II 5, stone by hand 151 (the float sum of 150 is just below 1).
 */
class BreakTimeCheckTest {

    private static final BlockStates STATES = BlockStates.vanilla();
    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final UUID ME = new UUID(7, 7);
    private static final int SELF = 1;
    private static final long SECOND = 1_000_000_000L;
    private static final Stack PICKAXE = new Stack("minecraft:diamond_pickaxe", 1, List.of(), List.of(), null, false);
    private static final BreakTimeCheck.TagLookup UNKNOWN_TAGS = tag -> null;

    private final List<Evidence> evidence = new ArrayList<>();
    private final List<Admissibility> rejected = new ArrayList<>();
    private final List<BreakTimeCheck.Judgement> judged = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now;
    private int pingId;
    private int actionSequence;

    @BeforeEach
    void setUp() {
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new BreakTimeCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {
                rejected.add(a);
            }
        }, replicas, judged::add));
        session = registry.openReplay("test");
        deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "edge decoding"));
        deliver(new ClientFact(seq++, now, Fact.IDENTITY, "UNAUTHENTICATED", Trust.CLAIMED, "test"));
        deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        deliver(new TimelineEvent.Bound(seq++, now, ME, "me", SELF));
        out(new ClientboundPacket.Login(SELF, 8, OVERWORLD, 0));
        out(new ClientboundPacket.PlayerInfoUpdate(true, true, List.of(new ClientboundPacket.PlayerInfoUpdate.Entry(ME, 0))));
        chunks();
        out(new ClientboundPacket.SetHeldSlot(0));
        hold(Stack.EMPTY);
        put("minecraft:dirt");
        sync();
        in(new ServerboundPacket.PlayerLoaded());
        in(new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0F, 0F, true, false, true, false));
        tick();
    }

    // --- timeline helpers ---

    private void deliver(TimelineEvent event) {
        session.deliver(event);
    }

    private void out(ClientboundPacket packet) {
        deliver(new TimelineEvent.Outbound(seq++, now, packet));
    }

    private void in(ServerboundPacket packet) {
        deliver(new TimelineEvent.Inbound(seq++, now, 0, packet));
    }

    private void sync() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        in(new ServerboundPacket.Pong(id));
        deliver(new TimelineEvent.Acknowledged(seq++, now, id, now, 0));
    }

    private void tick() {
        in(new ServerboundPacket.ClientTickEnd());
        now += SECOND / 20;
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    /** Air around the origin. */
    private void chunks() {
        out(new ClientboundPacket.ChunkCacheCenter(0, 0));
        int air = STATES.firstStateOf("minecraft:air");
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                out(new ClientboundPacket.LevelChunk(x, z, chunk(OVERWORLD.sections(), air)));
            }
        }
    }

    /** The block the tests dig, at (2, 65, 0). */
    private void put(String block) {
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(2, 65, 0), STATES.firstStateOf(block)));
    }

    private void hold(Stack stack) {
        List<Stack> items = new ArrayList<>(Collections.nCopies(46, Stack.EMPTY));
        items.set(36, stack);
        out(new ClientboundPacket.ContainerSetContent(0, 1, items, Stack.EMPTY));
    }

    private void dig(ServerboundPacket.PlayerAction.Action action) {
        in(new ServerboundPacket.PlayerAction(action, new BlockPosition(2, 65, 0), BlockFace.WEST,
                action == ServerboundPacket.PlayerAction.Action.ABORT_DESTROY_BLOCK ? 0 : ++actionSequence));
    }

    private void start() {
        dig(ServerboundPacket.PlayerAction.Action.START_DESTROY_BLOCK);
    }

    private void stop() {
        dig(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK);
    }

    /** The server acknowledged the client's block actions so far: the block is the server's again. */
    private void acknowledged() {
        out(new ClientboundPacket.BlockChangedAck(actionSequence));
        sync();
        tick();
    }

    /** A tick whose movement sent this onGround flag: the next tick's keybinds read it. */
    private void flag(boolean onGround) {
        in(move(onGround));
        tick();
    }

    private static ServerboundPacket.MovePlayer move(boolean onGround) {
        return new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0F, 0F, true, false, onGround, false);
    }

    /** A dig that finishes after {@code increments} of the client's increments (the start's tick counts). */
    private void digFor(int increments) {
        start();
        ticks(increments - 1);
        stop();
        tick();
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

    // --- the bound ---

    @Test
    void theIncrementIsTheClientsOwnFloatArithmetic() {
        dev.whynotac.world.ClientSelf.MiningBounds plain = new dev.whynotac.world.ClientSelf.MiningBounds(-1, -1, 0.0, 1.0, 0.2);
        assertEquals(1.0F / 0.5F / 30.0F, BreakTimeCheck.increment(1.0F, 0.5F, true, plain, false));
        assertEquals(1.0F / 1.5F / 100.0F, BreakTimeCheck.increment(1.0F, 1.5F, false, plain, false));
        // efficiency adds to a speed above 1 only; haste II multiplies by 1.4, mining fatigue I by 0.3
        assertEquals((8.0F + 26.0F) / 1.5F / 30.0F, BreakTimeCheck.increment(8.0F, 1.5F, true,
                new dev.whynotac.world.ClientSelf.MiningBounds(-1, -1, 26.0, 1.0, 0.2), false));
        assertEquals(1.0F / 0.5F / 30.0F, BreakTimeCheck.increment(1.0F, 0.5F, true,
                new dev.whynotac.world.ClientSelf.MiningBounds(-1, -1, 26.0, 1.0, 0.2), false));
        assertEquals(8.0F * (1.0F + 2 * 0.2F) / 1.5F / 30.0F, BreakTimeCheck.increment(8.0F, 1.5F, true,
                new dev.whynotac.world.ClientSelf.MiningBounds(1, -1, 0.0, 1.0, 0.2), false));
        assertEquals(1.0F * 0.3F / 0.5F / 30.0F, BreakTimeCheck.increment(1.0F, 0.5F, true,
                new dev.whynotac.world.ClientSelf.MiningBounds(-1, 0, 0.0, 1.0, 0.2), false));
        // a submerged speed above 1 may apply (the eye in water), below 1 never makes it faster
        assertEquals(1.0F * 2.0F / 0.5F / 30.0F, BreakTimeCheck.increment(1.0F, 0.5F, true,
                new dev.whynotac.world.ClientSelf.MiningBounds(-1, -1, 0.0, 1.0, 2.0), false));
        // off the ground a fifth, after every other factor
        assertEquals(1.0F / 5.0F / 0.5F / 30.0F, BreakTimeCheck.increment(1.0F, 0.5F, true, plain, true));
        assertEquals(1.0F * 2.0F / 5.0F / 0.5F / 30.0F, BreakTimeCheck.increment(1.0F, 0.5F, true,
                new dev.whynotac.world.ClientSelf.MiningBounds(-1, -1, 0.0, 1.0, 2.0), true));
    }

    @Test
    void aToolsSpeedIsItsFirstRuleThatMayHoldTheBlock() {
        ItemPrototypes items = ItemPrototypes.vanilla();
        ItemPrototypes.Tool pickaxe = items.get("minecraft:diamond_pickaxe").tool();
        assertEquals(8.0F, BreakTimeCheck.maxSpeed(pickaxe, "minecraft:stone", UNKNOWN_TAGS), "#mineable/pickaxe may hold it: the server's tags");
        assertTrue(BreakTimeCheck.mayBeCorrect(pickaxe, "minecraft:stone", UNKNOWN_TAGS));
        ItemPrototypes.Tool shears = items.get("minecraft:shears").tool();
        assertEquals(15.0F, BreakTimeCheck.maxSpeed(shears, "minecraft:cobweb", UNKNOWN_TAGS), "the cobweb rule names it: the first rule decides");
        assertTrue(BreakTimeCheck.mayBeCorrect(shears, "minecraft:cobweb", UNKNOWN_TAGS));
        assertEquals(2.0F, BreakTimeCheck.maxSpeed(shears, "minecraft:vine", UNKNOWN_TAGS) >= 2.0F ? 2.0F : 0F);
        assertEquals(1.0F, items.get("minecraft:mace").tool().defaultMiningSpeed());
        assertEquals(1.0F, BreakTimeCheck.maxSpeed(items.get("minecraft:mace").tool(), "minecraft:stone", UNKNOWN_TAGS));
        assertTrue(!BreakTimeCheck.mayBeCorrect(items.get("minecraft:mace").tool(), "minecraft:stone", UNKNOWN_TAGS));
        assertEquals(Float.MAX_VALUE, BreakTimeCheck.maxSpeed(items.get("minecraft:iron_sword").tool(), "minecraft:dirt", UNKNOWN_TAGS),
                "#sword_instantly_mines may hold it");
    }

    @Test
    void aTagRuleDecidesAsTheClientsTagsSay() {
        ItemPrototypes.Tool pickaxe = ItemPrototypes.vanilla().get("minecraft:diamond_pickaxe").tool();
        assertEquals(1.0F, BreakTimeCheck.maxSpeed(pickaxe, "minecraft:dirt", tag -> Boolean.FALSE), "no tag holds it: the default");
        assertTrue(!BreakTimeCheck.mayBeCorrect(pickaxe, "minecraft:dirt", tag -> Boolean.FALSE));
        assertEquals(8.0F, BreakTimeCheck.maxSpeed(pickaxe, "minecraft:stone", tag -> tag.equals("minecraft:mineable/pickaxe")));
        assertTrue(!BreakTimeCheck.mayBeCorrect(pickaxe, "minecraft:obsidian", tag -> true), "incorrect_for_diamond_tool comes first");
    }

    @Test
    void thePickaxeOnDirtIsAHandOnceTheTagsSaySo() {
        hold(PICKAXE);
        sync();
        digFor(3); // the server's tags not known: #mineable/pickaxe may hold dirt, 8 / 0.5 / 30 a tick
        assertEquals(List.of(), evidence);
        int stone = STATES.blockId(STATES.firstStateOf("minecraft:stone"));
        out(new ClientboundPacket.UpdateTags(List.of(new ClientboundPacket.UpdateTags.RegistryTags("minecraft:block", List.of(
                new ClientboundPacket.UpdateTags.Tag("minecraft:mineable/pickaxe", new int[] {stone}),
                new ClientboundPacket.UpdateTags.Tag("minecraft:incorrect_for_diamond_tool", new int[0]))))));
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(3); // dirt is in no rule's tag: the default speed 1, 15 increments
        assertEquals(1, evidence.size());
        assertEquals("1.0", evidence.getFirst().details().get("toolSpeed"));
    }

    // --- digging ---

    @Test
    void dirtByHandFinishesAfterFifteenIncrements() {
        digFor(15);
        assertEquals(List.of(), evidence);
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(14);
        assertEquals(1, evidence.size());
        Evidence e = evidence.getFirst();
        assertEquals(BreakTimeCheck.ID, e.check());
        assertEquals(EvidenceClass.MODIFICATION_PROOF, e.evidenceClass());
        assertEquals("minecraft:dirt", e.details().get("state"));
        assertEquals("14", e.details().get("ticks"));
        assertEquals("empty", e.details().get("item"));
        assertTrue(Float.parseFloat(e.details().get("maxProgress")) < 1.0F);
    }

    @Test
    void everyFinishIsJudgedForAudits() {
        digFor(15);
        stop(); // no start of it
        tick();
        acknowledged();
        put("minecraft:dirt");
        sync();
        flag(false);
        digFor(14);
        assertEquals(List.of("within", "no dig followed", "early"), judged.stream().map(BreakTimeCheck.Judgement::outcome).toList());
        BreakTimeCheck.Judgement within = judged.getFirst();
        assertEquals(15, within.ticks());
        assertEquals(0, within.offGroundTicks());
        assertEquals(1.0F, within.maxProgress(), "the client's own sum: 15 increments of 1 / 0.5 / 30 reach 1 exactly");
        assertTrue(Float.isNaN(judged.get(1).maxProgress()));
        assertEquals(14, judged.get(2).offGroundTicks());
        assertTrue(judged.get(2).maxProgress() < 0.2F);
    }

    @Test
    void stoneByHandTakesTheFloatSumsOneHundredFiftyOne() {
        put("minecraft:stone");
        sync();
        digFor(151);
        assertEquals(List.of(), evidence);
        acknowledged();
        put("minecraft:stone");
        sync();
        digFor(150);
        assertEquals(1, evidence.size());
        assertEquals("false", evidence.getFirst().details().get("correctTool"));
    }

    @Test
    void aDiamondPickaxeOnStoneSixOrWithHasteFive() {
        put("minecraft:stone");
        hold(PICKAXE);
        sync();
        digFor(6);
        assertEquals(List.of(), evidence);
        acknowledged();
        put("minecraft:stone");
        sync();
        digFor(5);
        assertEquals(1, evidence.size());
        now += 2 * SECOND;
        acknowledged();
        put("minecraft:stone");
        out(new ClientboundPacket.MobEffect(SELF, "minecraft:haste", 1, 600, 0)); // haste II, not yet acknowledged: it may apply
        digFor(5);
        assertEquals(1, evidence.size());
        acknowledged();
        put("minecraft:stone");
        sync();
        digFor(4);
        assertEquals(2, evidence.size());
        assertEquals("1", evidence.get(1).details().get("digSpeedAmplifier"));
    }

    @Test
    void miningFatigueCountsOnlyWhereItCertainlyApplies() {
        out(new ClientboundPacket.MobEffect(SELF, "minecraft:mining_fatigue", 0, 600, 0));
        digFor(15); // the client may not have it yet: 15 by hand is possible
        assertEquals(List.of(), evidence);
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(15); // with it, 1 * 0.3 / 0.5 / 30 per tick: 50
        assertEquals(1, evidence.size());
        assertEquals("0", evidence.getFirst().details().get("fatigueAmplifier"));
        now += 2 * SECOND;
        acknowledged();
        put("minecraft:dirt");
        out(new ClientboundPacket.RemoveMobEffect(SELF, "minecraft:mining_fatigue"));
        sync();
        digFor(15);
        assertEquals(1, evidence.size());
    }

    @Test
    void efficiencyAddsItsAttribute() {
        put("minecraft:stone");
        hold(PICKAXE);
        out(new ClientboundPacket.UpdateAttributes(SELF, List.of(new ClientboundPacket.UpdateAttributes.Attribute("minecraft:mining_efficiency", 0.0,
                List.of(new ClientboundPacket.UpdateAttributes.Modifier("minecraft:enchantment.efficiency/mainhand", 26.0,
                        ClientboundPacket.UpdateAttributes.Operation.ADD_VALUE))))));
        sync();
        digFor(2); // (8 + 26) / 1.5 / 30 = 0.76 a tick
        assertEquals(List.of(), evidence);
        acknowledged();
        put("minecraft:stone");
        sync();
        digFor(1);
        assertEquals(1, evidence.size());
    }

    @Test
    void noClaimWhereTheBlockMayChangeOrTheToolIsTheStacksOwn() {
        start();
        ticks(3);
        put("minecraft:short_grass"); // the server changes the block during the dig: the client's next increment is the grass's
        sync();
        stop();
        tick();
        assertEquals(List.of(), evidence, "the server changed the dug block");
        acknowledged();
        put("minecraft:dirt");
        sync();
        start();
        ticks(3);
        put("minecraft:short_grass");
        put("minecraft:dirt"); // and back, not yet acknowledged: the client may have the grass now
        stop();
        tick();
        assertEquals(List.of(), evidence, "the client may hold the grass in the finish's tick");
        acknowledged();
        start();
        in(new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, new BlockPosition(2, 64, 0), BlockFace.UP, new Vec3d(2.5, 65.0, 0.5), false, false,
                ++actionSequence));
        tick();
        stop();
        tick();
        assertEquals(List.of(), evidence, "a click during the dig may have changed the block");
        acknowledged();
        hold(new Stack("minecraft:stick", 1, List.of(BreakTimeCheck.TOOL), List.of(), null, false));
        sync();
        digFor(2);
        assertEquals(List.of(), evidence, "a tool component of the stack's own");
        hold(new Stack("minecraft:stick", 1, List.of(), List.of(), null, false));
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(2);
        assertEquals(1, evidence.size(), "a stick without a tool of its own mines as a hand does");
    }

    @Test
    void noClaimInCreativeOrWithoutTheStartOrAfterAnAbort() {
        out(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, 1F));
        sync();
        digFor(2);
        assertEquals(List.of(), evidence, "creative");
        out(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, 0F));
        acknowledged();
        put("minecraft:dirt");
        hold(Stack.EMPTY); // creative may have set any slot
        sync();
        start();
        dig(ServerboundPacket.PlayerAction.Action.ABORT_DESTROY_BLOCK);
        out(new ClientboundPacket.BlockChangedAck(actionSequence)); // the block is the server's again
        sync();
        stop();
        tick();
        assertEquals(List.of(), evidence, "aborted: the finish has no start this check saw");
        acknowledged();
        put("minecraft:dirt");
        sync();
        start();
        tick();
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0));
        chunks();
        put("minecraft:dirt");
        hold(Stack.EMPTY);
        sync();
        stop();
        tick();
        assertEquals(List.of(), evidence, "a respawn: MultiPlayerGameMode may go on with the old dig");
        acknowledged();
        put("minecraft:dirt");
        out(new ClientboundPacket.BlockUpdate(new BlockPosition(3, 65, 0), STATES.firstStateOf("minecraft:dirt")));
        sync();
        start();
        tick();
        in(new ServerboundPacket.PlayerAction(ServerboundPacket.PlayerAction.Action.STOP_DESTROY_BLOCK, new BlockPosition(3, 65, 0), BlockFace.WEST,
                ++actionSequence));
        tick();
        assertEquals(List.of(), evidence, "a finish of another block than the one started");
    }

    @Test
    void theBlockBreakSpeedMultiplies() {
        out(new ClientboundPacket.UpdateAttributes(SELF, List.of(new ClientboundPacket.UpdateAttributes.Attribute("minecraft:block_break_speed", 2.0,
                List.of()))));
        sync();
        digFor(8); // 2 / 0.5 / 30 a tick
        assertEquals(List.of(), evidence);
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(7);
        assertEquals(1, evidence.size());
        assertEquals("2.0", evidence.getFirst().details().get("blockBreakSpeed"));
    }

    @Test
    void findingsAreAtMostOneASecondAndCountWhatCameBetween() {
        digFor(2);
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(2);
        assertEquals(1, evidence.size());
        now += 2 * SECOND;
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(2);
        assertEquals(2, evidence.size());
        assertEquals("2", evidence.get(1).details().get("earlyFinishes"));
    }

    // --- off the ground ---

    @Test
    void offTheGroundDirtByHandTakesSeventySix() {
        flag(false);
        digFor(76); // the float sum of 75 fifths of 1 / 0.5 / 30 is just below 1
        assertEquals(List.of(), evidence);
        acknowledged();
        put("minecraft:dirt");
        sync();
        digFor(75);
        assertEquals(1, evidence.size());
        assertEquals("75", evidence.getFirst().details().get("offGroundTicks"));
        now += 2 * SECOND;
        acknowledged();
        put("minecraft:dirt");
        sync();
        flag(true);
        digFor(15);
        assertEquals(1, evidence.size(), "on the ground again");
    }

    @Test
    void theFlagCountsFromTheTickAfterItWasSent() {
        // the start's own tick reads the flag of the tick before it (on the ground), the ticks after it the one its movement sent
        start();
        in(move(false));
        tick();
        ticks(70);
        stop();
        tick();
        assertEquals(List.of(), evidence, "one increment on the ground and 71 off it");
        acknowledged();
        put("minecraft:dirt");
        sync();
        flag(true);
        start();
        in(move(false));
        tick();
        ticks(69);
        stop();
        tick();
        assertEquals(1, evidence.size());
        assertEquals("70", evidence.getFirst().details().get("offGroundTicks"));
    }

    @Test
    void theAnswersToATeleportOrARotationAreNotTheClientsFlag() {
        // handleMovePlayer answers with onGround false and leaves the client's own as it was: on the ground here
        out(new ClientboundPacket.PlayerPosition(1, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 0F, 0F, Set.of()));
        in(new ServerboundPacket.AcceptTeleportation(1));
        in(new ServerboundPacket.MovePlayer(0.5, 64.0, 0.5, 0F, 0F, true, true, false, false));
        tick();
        digFor(15);
        assertEquals(List.of(), evidence, "after a teleport");
        acknowledged();
        put("minecraft:dirt");
        sync();
        // handleRotatePlayer too
        out(new ClientboundPacket.PlayerRotation(90F, false, 0F, false));
        in(new ServerboundPacket.MovePlayer(0, 0, 0, 90F, 0F, false, true, false, false));
        tick();
        digFor(15);
        assertEquals(List.of(), evidence, "after a rotation");
        acknowledged();
        put("minecraft:dirt");
        sync();
        flag(false); // sent after the client processed both: its own
        digFor(15);
        assertEquals(1, evidence.size());
    }

    @Test
    void aTeleportOfThePlayersOwnEntitySetsTheFlagTheNextKeybindsRead() {
        flag(false);
        out(new ClientboundPacket.TeleportEntity(SELF, new Vec3d(0.5, 64.0, 0.5), Vec3d.ZERO, 0F, 0F, Set.of(), true));
        digFor(15);
        assertEquals(List.of(), evidence);
    }

    @Test
    void aNewPlayerSentNoFlagYet() {
        flag(false);
        out(new ClientboundPacket.Respawn(OVERWORLD, 0, 0));
        chunks();
        put("minecraft:dirt");
        hold(Stack.EMPTY);
        sync();
        digFor(15);
        assertEquals(List.of(), evidence);
    }

    @Test
    void noClaimWhileThePlayerMayRideNorFromTheAnswerToItsRemovedVehiclesTeleport() {
        out(new ClientboundPacket.SetPassengers(99, new int[] {SELF}));
        sync();
        flag(false); // a passenger's tick sets it false: the claim would hold, it is not made
        digFor(15);
        assertEquals(List.of(), evidence, "riding");
        acknowledged();
        put("minecraft:dirt");
        out(new ClientboundPacket.RemoveEntities(new int[] {99}));
        sync();
        flag(true);
        // a teleport of the removed vehicle moves the player and answers with onGround false (handleTeleportEntity)
        out(new ClientboundPacket.TeleportEntity(99, new Vec3d(5.5, 64.0, 5.5), Vec3d.ZERO, 0F, 0F, Set.of(), false));
        in(new ServerboundPacket.MovePlayer(5.5, 64.0, 5.5, 0F, 0F, true, true, false, false));
        tick();
        digFor(15);
        assertEquals(List.of(), evidence, "the answer to a removed vehicle's teleport");
    }

    @Test
    void noClaimWhileTheCameraIsAnotherEntity() {
        out(new ClientboundPacket.SetCamera(99));
        sync();
        flag(false); // nothing is sent while another entity is the camera: the player may have landed since
        digFor(15);
        assertEquals(List.of(), evidence, "another camera");
        acknowledged();
        put("minecraft:dirt");
        out(new ClientboundPacket.SetCamera(SELF));
        sync();
        digFor(15);
        assertEquals(List.of(), evidence, "the first tick after it reads a flag not sent yet");
    }
}
