package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.TimelineEvent;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Deterministic 100,000+ operation fuzzer across {@link ClientWorld} multi-cell connected block
 * placements, interactive block toggles, {@link ClientEntities}, and {@link ClientInventory} (PH26-02).
 */
final class WorldAndEntitiesFuzzTest {

    private static final String[] BLOCKS = {
            "minecraft:stone",
            "minecraft:oak_stairs",
            "minecraft:oak_slab",
            "minecraft:oak_fence",
            "minecraft:cobblestone_wall",
            "minecraft:glass_pane",
            "minecraft:iron_bars",
            "minecraft:oak_door",
            "minecraft:oak_trapdoor",
            "minecraft:oak_fence_gate",
            "minecraft:snow",
            "minecraft:red_carpet",
            "minecraft:lantern",
            "minecraft:oak_log",
            "minecraft:torch"
    };

    private static final String[] FACINGS = {"north", "south", "east", "west"};
    private static final BlockFace[] FACES = BlockFace.values();

    @Test
    void fuzzes100000WorldPlacementsTogglesEntitiesAndInventoryWithZeroExceptions() {
        SplittableRandom rng = new SplittableRandom(0x2602_B10CL);
        BlockStates states = BlockStates.vanilla();
        ClientboundPacket.Dimension overworld = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384, false);

        ClientWorld world = new ClientWorld(states);
        ClientEntities entities = new ClientEntities(EntityTypes.vanilla());
        ClientInventory inventory = new ClientInventory(ItemPrototypes.vanilla(), MenuLayouts.vanilla(),
                (stack, use) -> ClientInventory.Outcome.KEEP);

        long seq = 0;
        int pingId = 0;
        byte[] chunkData = flatStoneChunk(states, overworld);
        List<Stack> initialInv = new ArrayList<>(46);
        for (int i = 0; i < 46; i++) {
            initialInv.add(Stack.EMPTY);
        }

        // Bootstrap all 3 replicas with a 3x3 loaded arena
        for (TimelineEvent ev : List.of(
                new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.Login(1, 4, overworld, 0)),
                new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.ChunkCacheCenter(0, 0)),
                new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.SetHeldSlot(0)),
                new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.ContainerSetContent(0, 1, initialInv, Stack.EMPTY)))) {
            world.onEvent(ev);
            entities.onEvent(ev);
            inventory.onEvent(ev);
        }
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                TimelineEvent lc = new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.LevelChunk(cx, cz, chunkData));
                world.onEvent(lc);
                entities.onEvent(lc);
            }
        }
        int initPing = ++pingId;
        TimelineEvent pOut = new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.Ping(initPing));
        TimelineEvent pAck = new TimelineEvent.Acknowledged(seq++, seq, initPing, 0, 0);
        world.onEvent(pOut);
        world.onEvent(pAck);
        entities.onEvent(pOut);
        entities.onEvent(pAck);
        inventory.onEvent(pOut);
        inventory.onEvent(pAck);

        // 1. 70,000 ClientWorld randomized multi-cell placements, toggles, and server block updates
        int worldOps = 0;
        int useSeq = 1;
        for (int i = 0; i < 70_000; i++) {
            int bx = rng.nextInt(-10, 11);
            int by = rng.nextInt(63, 70);
            int bz = rng.nextInt(-10, 11);
            BlockPosition pos = new BlockPosition(bx, by, bz);
            BlockFace face = FACES[rng.nextInt(FACES.length)];
            Vec3d loc = new Vec3d(bx + rng.nextDouble(), by + rng.nextDouble(), bz + rng.nextDouble());
            String facing = FACINGS[rng.nextInt(FACINGS.length)];
            int op = rng.nextInt(10);
            if (op < 5) {
                int sId = useSeq++;
                world.onEvent(new TimelineEvent.Inbound(seq++, seq, i,
                        new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, pos, face, loc, false, false, sId)));
                String blockKey = BLOCKS[rng.nextInt(BLOCKS.length)];
                ClientWorld.PlacementPlan plan = world.placementPlanForItem(sId, blockKey, facing);
                world.resolveUseMulti(sId, plan);
                if ((i & 7) == 0) {
                    world.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.BlockChangedAck(sId)));
                }
            } else if (op < 8) {
                int sId = useSeq++;
                world.onEvent(new TimelineEvent.Inbound(seq++, seq, i,
                        new ServerboundPacket.UseItemOn(Hand.MAIN_HAND, pos, face, loc, false, false, sId)));
                String held = BLOCKS[rng.nextInt(BLOCKS.length)];
                ClientWorld.PlacementPlan toggle = world.blockToggleForUse(sId, facing, held);
                world.resolveUseMulti(sId, toggle);
            } else {
                int randomState = states.firstStateOf(BLOCKS[rng.nextInt(BLOCKS.length)]);
                world.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.BlockUpdate(pos, randomState)));
                if ((i & 15) == 0) {
                    int pid = ++pingId;
                    world.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.Ping(pid)));
                    world.onEvent(new TimelineEvent.Acknowledged(seq++, seq, pid, 0, 0));
                }
                assertTrue(world.blockState(bx, by, bz) >= 0);
            }
            worldOps++;
        }

        // 2. 15,000 ClientEntities spawn/move/teleport/tick/remove ops
        int entityOps = 0;
        for (int i = 0; i < 15_000; i++) {
            int eid = 10 + (i % 48);
            int action = rng.nextInt(5);
            if (action == 0) {
                entities.onEvent(new TimelineEvent.Outbound(seq++, seq, ClientboundPacket.AddEntity.received(
                        eid, new UUID(1, eid), "minecraft:zombie",
                        rng.nextDouble() * 10.0, 64.0, rng.nextDouble() * 10.0,
                        Vec3d.ZERO, (byte) 0, (byte) 0, (byte) 0, 0)));
            } else if (action == 1) {
                entities.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.MoveEntity(
                        eid, (short) rng.nextInt(-256, 257), (short) 0, (short) rng.nextInt(-256, 257),
                        (byte) 0, (byte) 0, true, false, true)));
            } else if (action == 2) {
                entities.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.TeleportEntity(
                        eid, new Vec3d(rng.nextDouble() * 10.0, 64.0, rng.nextDouble() * 10.0),
                        Vec3d.ZERO, 0.0f, 0.0f, Set.of(), true)));
            } else if (action == 3) {
                entities.onEvent(new TimelineEvent.Inbound(seq++, seq, i, new ServerboundPacket.ClientTickEnd()));
            } else {
                entities.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.RemoveEntities(new int[] {eid})));
            }
            if ((i & 15) == 0) {
                int pid = ++pingId;
                entities.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.Ping(pid)));
                entities.onEvent(new TimelineEvent.Acknowledged(seq++, seq, pid, 0, 0));
            }
            entityOps++;
        }

        // 3. 15,000 ClientInventory slot/held/click ops
        int invOps = 0;
        for (int i = 0; i < 15_000; i++) {
            int kind = rng.nextInt(4);
            if (kind == 0) {
                inventory.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.SetHeldSlot(rng.nextInt(9))));
            } else if (kind == 1) {
                inventory.onEvent(new TimelineEvent.Inbound(seq++, seq, i, new ServerboundPacket.SetCarriedItem(rng.nextInt(9))));
            } else if (kind == 2) {
                inventory.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.ContainerSetSlot(
                        0, i, rng.nextInt(46), new Stack(BLOCKS[rng.nextInt(BLOCKS.length)], 1 + rng.nextInt(64), List.of(), List.of(), null, false))));
            } else {
                inventory.onEvent(new TimelineEvent.Inbound(seq++, seq, i, new ServerboundPacket.ContainerClick(
                        0, i, rng.nextInt(46), rng.nextInt(2), "PICKUP", List.of(), Stack.EMPTY)));
            }
            if ((i & 15) == 0) {
                int pid = ++pingId;
                inventory.onEvent(new TimelineEvent.Outbound(seq++, seq, new ClientboundPacket.Ping(pid)));
                inventory.onEvent(new TimelineEvent.Acknowledged(seq++, seq, pid, 0, 0));
            }
            invOps++;
        }

        assertEquals(100_000, worldOps + entityOps + invOps);
    }

    private static byte[] flatStoneChunk(BlockStates states, ClientboundPacket.Dimension dim) {
        int stone = states.firstStateOf("minecraft:stone");
        int air = states.firstStateOf("minecraft:air");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, 0);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < dim.sections(); i++) {
            boolean solid = i < 8;
            data.write(solid ? 0x10 : 0);
            data.write(0);
            data.write(0);
            writeVarInt(data, solid ? stone : air);
            data.write(0);
            writeVarInt(data, 0);
        }
        writeVarInt(body, data.size());
        body.writeBytes(data.toByteArray());
        writeVarInt(body, 0);
        return body.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }
}
