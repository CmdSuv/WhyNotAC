package dev.whynotac.paper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ChunkDecoder;
import dev.whynotac.world.Section;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.core.IdMapper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Sections serialized by the game's own {@code PalettedContainer} (every palette mode: one value,
 * linear, hash map, global) must decode to exactly the states the game wrote, block for block.
 */
@SuppressWarnings("deprecation") // Paper deprecates PalettedContainer(T, Strategy) for its anti-xray variant; the wire format is the same
class ChunkDecoderConformanceTest {

    private static BlockStates states;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        states = BlockStates.vanilla();
    }

    /** A biome container needs an id map; its contents do not matter, only that it is skipped exactly. */
    private static PalettedContainer<Integer> biomes(Random random, int distinct) {
        IdMapper<Integer> ids = new IdMapper<>();
        for (int i = 0; i < 80; i++) {
            ids.add(i);
        }
        PalettedContainer<Integer> container = new PalettedContainer<>(0, Strategy.createForBiomes(ids));
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    container.set(x, y, z, random.nextInt(distinct));
                }
            }
        }
        return container;
    }

    private static PalettedContainer<BlockState> blocks(Random random, int distinct) {
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < distinct; i++) {
            palette.add(Block.BLOCK_STATE_REGISTRY.byId(random.nextInt(Block.BLOCK_STATE_REGISTRY.size())));
        }
        PalettedContainer<BlockState> container = new PalettedContainer<>(palette.get(0), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        if (distinct > 1) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        container.set(x, y, z, palette.get(random.nextInt(distinct)));
                    }
                }
            }
        }
        return container;
    }

    /** A complete chunk body (after the coordinates), with {@code sections} sections. */
    private static byte[] body(List<PalettedContainer<BlockState>> sections, Random random, int[] biomeDistinct) {
        ByteBuf sectionBytes = Unpooled.buffer();
        FriendlyByteBuf out = new FriendlyByteBuf(sectionBytes);
        for (int i = 0; i < sections.size(); i++) {
            out.writeShort(4096);
            sections.get(i).write(out);
            biomes(random, biomeDistinct[i % biomeDistinct.length]).write(out);
        }
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        body.writeVarInt(1);                // one heightmap
        body.writeVarInt(4);                // type id
        body.writeVarInt(37);               // 37 longs
        for (int i = 0; i < 37; i++) {
            body.writeLong(random.nextLong());
        }
        body.writeVarInt(sectionBytes.readableBytes());
        body.writeBytes(sectionBytes);
        body.writeVarInt(0);                // block entities
        body.writeBytes(new byte[] {1, 2, 3}); // light data: not read
        byte[] bytes = new byte[body.readableBytes()];
        body.readBytes(bytes);
        return bytes;
    }

    @Test
    void everyPaletteModeDecodesToTheGameStates() throws Exception {
        Random random = new Random(774);
        // 1: one value; 2..16: linear (4 bits); 17..256: hash map (5..8 bits); more: global ids.
        int[] distinct = {1, 2, 3, 15, 16, 17, 31, 32, 33, 100, 255, 256, 257, 600, 4096};
        List<PalettedContainer<BlockState>> sections = new ArrayList<>();
        for (int d : distinct) {
            sections.add(blocks(random, d));
        }
        byte[] body = body(sections, random, new int[] {1, 2, 3, 4, 5, 9, 33, 64});
        Section[] decoded = ChunkDecoder.decode(body, sections.size(), states);
        for (int i = 0; i < sections.size(); i++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertEquals(Block.getId(sections.get(i).get(x, y, z)), decoded[i].get(x, y, z),
                                "section " + i + " (" + distinct[i] + " states) at " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    @Test
    void theClientReadsOnlyItsDimensionsSectionCount() throws Exception {
        Random random = new Random(1);
        List<PalettedContainer<BlockState>> sections = List.of(blocks(random, 5), blocks(random, 1), blocks(random, 40));
        byte[] body = body(sections, random, new int[] {1});
        Section[] two = ChunkDecoder.decode(body, 2, states); // extra section data is ignored, as by the client
        assertEquals(Block.getId(sections.get(1).get(3, 3, 3)), two[1].get(3, 3, 3));
        assertThrows(ChunkDecoder.FormatException.class, () -> ChunkDecoder.decode(body, 4, states)); // too few: the client fails
    }

    @Test
    void anAirChunkIsOneValuePerSection() throws Exception {
        PalettedContainer<BlockState> air = new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        byte[] body = body(List.of(air, air), new Random(2), new int[] {1});
        Section[] decoded = ChunkDecoder.decode(body, 2, states);
        assertEquals(Block.getId(Blocks.AIR.defaultBlockState()), decoded[1].get(15, 15, 15));
    }
}
