package dev.whynotac.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientTagsTest {

    private static final ClientboundPacket.Dimension OVERWORLD = new ClientboundPacket.Dimension("minecraft:overworld", -64, 384);
    private static final String CLIMBABLE = "minecraft:climbable";

    private ClientTags tags;
    private long sequence;
    private int pingId;

    @BeforeEach
    void setUp() {
        tags = new ClientTags();
    }

    private void out(ClientboundPacket packet) {
        tags.onEvent(new TimelineEvent.Outbound(sequence++, sequence, packet));
    }

    private int ping() {
        int id = ++pingId;
        out(new ClientboundPacket.Ping(id));
        return id;
    }

    private void ack(int id) {
        tags.onEvent(new TimelineEvent.Acknowledged(sequence++, sequence, id, 0, 0));
    }

    private void sync() {
        ack(ping());
    }

    private static ClientboundPacket.UpdateTags blocks(ClientboundPacket.UpdateTags.Tag... tags) {
        return new ClientboundPacket.UpdateTags(List.of(new ClientboundPacket.UpdateTags.RegistryTags(ClientTags.BLOCK, List.of(tags))));
    }

    private static ClientboundPacket.UpdateTags.Tag tag(String name, int... ids) {
        return new ClientboundPacket.UpdateTags.Tag(name, ids);
    }

    private void login() {
        out(new ClientboundPacket.Login(1, 8, OVERWORLD, 0));
    }

    @Test
    void theConfigurationPhasesTagsAreThePlayRegistries() {
        assertNull(tags.holds(ClientTags.BLOCK, CLIMBABLE, 1), "nothing sent yet");
        out(blocks(tag(CLIMBABLE, 7, 2)));
        login();
        sync();
        assertEquals(Boolean.TRUE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 2));
        assertEquals(Boolean.TRUE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 7));
        assertEquals(Boolean.FALSE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 3));
        assertEquals(Boolean.FALSE, tags.holds(ClientTags.BLOCK, "minecraft:fences", 3), "a tag not sent is empty");
        assertTrue(tags.certainlyExactly(ClientTags.BLOCK, CLIMBABLE, new int[] {2, 7}));
        assertTrue(!tags.certainlyExactly(ClientTags.BLOCK, CLIMBABLE, new int[] {2}));
        assertNull(tags.possible(ClientTags.FLUID, "minecraft:water"), "a registry the configuration phase sent nothing for");
    }

    @Test
    void aReloadInPlayReplacesTheSentRegistryInEveryPrefix() {
        out(new ClientboundPacket.UpdateTags(List.of(new ClientboundPacket.UpdateTags.RegistryTags(ClientTags.BLOCK, List.of(tag(CLIMBABLE, 1, 2))),
                new ClientboundPacket.UpdateTags.RegistryTags(ClientTags.FLUID, List.of(tag("minecraft:water", 1, 2))))));
        login();
        sync();
        out(blocks(tag(CLIMBABLE, 2, 3)));
        int after = ping();
        assertNull(tags.holds(ClientTags.BLOCK, CLIMBABLE, 1), "either version");
        assertEquals(Boolean.TRUE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 2));
        assertNull(tags.holds(ClientTags.BLOCK, CLIMBABLE, 3));
        assertTrue(!tags.certainlyExactly(ClientTags.BLOCK, CLIMBABLE, new int[] {1, 2}));
        ack(after);
        assertEquals(Boolean.FALSE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 1));
        assertEquals(Boolean.TRUE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 3));
        assertTrue(tags.certainlyExactly(ClientTags.FLUID, "minecraft:water", new int[] {2, 1}), "a registry left out keeps its tags");
        out(blocks(tag("minecraft:fences", 5)));
        sync();
        assertEquals(Boolean.FALSE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 3), "prepareTagReload: a tag left out is empty");
    }

    @Test
    void aLoginAfterAConfigurationThatSentNothingLeavesTheRegistryUnknown() {
        out(blocks(tag(CLIMBABLE, 1)));
        login();
        sync();
        login(); // a reconfiguration that sent no tags
        sync();
        assertNull(tags.holds(ClientTags.BLOCK, CLIMBABLE, 1));
        out(blocks(tag(CLIMBABLE, 1)));
        login();
        sync();
        assertEquals(Boolean.TRUE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 1));
    }

    @Test
    void anUntranslatablePacketOrLostEventsLeaveTheTagsUnknown() {
        out(blocks(tag(CLIMBABLE, 1)));
        login();
        sync();
        long before = tags.revision();
        out(new ClientboundPacket.Untranslatable("minecraft:update_tags"));
        assertNotEquals(before, tags.revision());
        sync();
        assertNull(tags.holds(ClientTags.BLOCK, CLIMBABLE, 1));
        out(blocks(tag(CLIMBABLE, 1)));
        sync();
        assertEquals(Boolean.TRUE, tags.holds(ClientTags.BLOCK, CLIMBABLE, 1));
        tags.onEvent(new TimelineEvent.Gap(sequence++, sequence, 3));
        assertNull(tags.holds(ClientTags.BLOCK, CLIMBABLE, 1));
    }

    @Test
    void everySessionSharesTheSamePayload() {
        ClientTags other = new ClientTags();
        ClientboundPacket.UpdateTags update = blocks(tag(CLIMBABLE, 4, 9));
        out(update);
        login();
        sync();
        other.onEvent(new TimelineEvent.Outbound(0, 0, blocks(tag(CLIMBABLE, 4, 9))));
        other.onEvent(new TimelineEvent.Outbound(1, 1, new ClientboundPacket.Login(1, 8, OVERWORLD, 0)));
        assertSame(tags.possible(ClientTags.BLOCK, CLIMBABLE).getFirst(), other.possible(ClientTags.BLOCK, CLIMBABLE).getFirst());
    }
}
