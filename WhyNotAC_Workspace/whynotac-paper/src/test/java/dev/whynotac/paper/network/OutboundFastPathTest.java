package dev.whynotac.paper.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import org.junit.jupiter.api.Test;

/**
 * {@link PacketTranslator#outbound} skips its type switch for classes outside {@code OUTBOUND_CLASSES}.
 * A case added to the switch without the class would silently translate as unmodelled: the two lists
 * must stay equal.
 */
class OutboundFastPathTest {

    @Test
    void theFastPathListEqualsTheSwitchCases() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/whynotac/paper/network/PacketTranslator.java"));
        String outbound = source.substring(source.indexOf("public ClientboundPacket outbound(Packet<?> packet, boolean fromWire)"));
        outbound = outbound.substring(0, outbound.indexOf("default ->"));
        String list = source.substring(source.indexOf("OUTBOUND_CLASSES = List.of("));
        list = list.substring(0, list.indexOf(");"));
        assertEquals(names(outbound, "case (Clientbound\\w+Packet) \\w+ ->"), names(list, "(Clientbound\\w+Packet)\\.class"));
    }

    @Test
    void classesAreClassified() {
        assertTrue(PacketTranslator.OUTBOUND_MODELLED.get(ClientboundLevelChunkWithLightPacket.class));
        assertTrue(PacketTranslator.OUTBOUND_MODELLED.get(ClientboundMoveEntityPacket.Pos.class), "a subclass of a modelled class");
        assertFalse(PacketTranslator.OUTBOUND_MODELLED.get(ClientboundAnimatePacket.class));
    }

    private static Set<String> names(String text, String regex) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
