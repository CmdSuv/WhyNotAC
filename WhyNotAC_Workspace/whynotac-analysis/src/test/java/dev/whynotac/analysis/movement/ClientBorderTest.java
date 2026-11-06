package dev.whynotac.analysis.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.physics.Border;
import org.junit.jupiter.api.Test;

/** What the replica of the client's border knows and does not (its arithmetic is checked against the game in whynotac-paper). */
class ClientBorderTest {

    private static final ClientBorder SMALL = ClientBorder.fresh("minecraft:overworld").withCenter(0.0, 0.0).withSize(100.0);

    @Test
    void aRespawnKeepsTheBorderOnlyInTheSameDimension() {
        assertSame(SMALL, SMALL.respawned("minecraft:overworld"));
        ClientBorder nether = SMALL.respawned("minecraft:the_nether");
        assertEquals(ClientBorder.fresh("minecraft:the_nether"), nether, "a new level: a new border");
        for (ClientBorder unknown : new ClientBorder[] {SMALL.respawned(null), ClientBorder.fresh(null).respawned("minecraft:overworld")}) {
            assertFalse(unknown.known());
            assertNull(unknown.collision());
            assertFalse(unknown.farFrom(-0.3, -0.3, 0.3, 0.3, 0.0), "near wherever it is");
        }
        assertTrue(SMALL.farFrom(-0.3, -0.3, 0.3, 0.3, 16.0));
        assertFalse(SMALL.farFrom(40.0, -0.3, 40.6, 0.3, 16.0));
    }

    @Test
    void aLerpStepsOnlyInTicksThatRanNormally() {
        ClientBorder moving = SMALL.lerp(100.0, 50.0, 3L);
        assertEquals(new Border(-50.0, -50.0, 50.0, 50.0), moving.collision(), "the start's size");
        assertSame(moving, moving.ticked(Boolean.FALSE), "a frozen level does not step it");
        ClientBorder one = moving.ticked(Boolean.TRUE);
        assertEquals(new Border(-50.0, -50.0, 50.0, 50.0), one.collision(), "the size before the step collides");
        ClientBorder two = one.ticked(Boolean.TRUE);
        assertEquals(-50.0 + 50.0 / 3.0 / 2.0, two.collision().minX(), 1.0E-12);
        ClientBorder done = two.ticked(Boolean.TRUE);
        assertEquals(new ClientBorder.Static(50.0), done.extent(), "its last step: the target, static");
    }

    @Test
    void aStepNotKnownLeavesTheSizeBetweenItsEndsUntilItIsCertainlyDone() {
        ClientBorder uncertain = SMALL.lerp(100.0, 50.0, 3L).ticked(null);
        assertFalse(uncertain.known());
        assertEquals(new ClientBorder.Uncertain(50.0, 100.0, 3L, 50.0), uncertain.extent());
        assertTrue(uncertain.farFrom(-0.3, -0.3, 0.3, 0.3, 24.0));
        assertFalse(uncertain.farFrom(20.0, -0.3, 20.6, 0.3, 4.9), "within the smallest size's side");
        ClientBorder still = uncertain.ticked(Boolean.TRUE).ticked(null).ticked(Boolean.FALSE).ticked(Boolean.TRUE);
        assertFalse(still.known(), "two ticks certainly ran: one may be left");
        assertEquals(new ClientBorder.Static(50.0), still.ticked(Boolean.TRUE).extent());
    }

    @Test
    void theBaseProcessingALerpDoesNotKnowWhenItStarted() {
        ClientBorder started = SMALL.lerp(100.0, 50.0, 2L).processedBefore();
        assertEquals(new ClientBorder.Uncertain(50.0, 100.0, 3L, 50.0), started.extent(), "one tick more, in case it came after that tick's step");
        assertSame(SMALL, SMALL.processedBefore());
        ClientBorder known = started.ticked(Boolean.TRUE).ticked(Boolean.TRUE).ticked(Boolean.TRUE);
        assertTrue(known.known());
        assertEquals(new Border(-25.0, -25.0, 25.0, 25.0), known.collision());
    }

    @Test
    void initializingSetsAllOfIt() {
        ClientBorder b = ClientBorder.fresh("minecraft:overworld").initialized(new dev.whynotac.protocol.packet.ClientboundPacket.InitializeBorder(
                10.0, -20.0, 30.0, 30.0, 0L, 12));
        assertEquals(new Border(-5.0, -12.0, 12.0, -5.0), b.collision(), "clamped to the absolute maximum");
        assertEquals("minecraft:overworld", b.dimension());
    }
}
