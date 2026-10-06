package dev.whynotac.paper.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {

    private static final int EVENTS = 200_000;

    /** Yaw of rotation packet {@code i}: i * 0.25 (exact in float), so each delta (0.25) and each packet are identifiable. */
    private static float yaw(int i) {
        return (i % 4096) * 0.25F;
    }

    private static TimelineEvent rotation(int i) {
        return new TimelineEvent.Inbound(i, i, 0, new ServerboundPacket.MovePlayer(Double.NaN, Double.NaN, Double.NaN,
                yaw(i), (i % 2) * 0.5F, false, true, true, false));
    }

    @Test
    void yawAndItsDeltaAlwaysComeFromTheSamePacket() throws Exception {
        Diagnostics diagnostics = new Diagnostics();
        PlayerSession session = new SessionRegistry(Runnable::run, 16, (s, e, t) -> { throw new AssertionError(t); }).open("x");
        diagnostics.onEvent(session, rotation(0));
        Diagnostics.View view = diagnostics.view(session);
        assertTrue(Double.isNaN(view.lastRotation().yawDelta()), "no delta before a second rotation");
        diagnostics.onEvent(session, rotation(1));

        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> torn = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            while (!done.get() && torn.get() == null) {
                Diagnostics.Rotation r = view.lastRotation();
                int i = Math.round(r.move().yaw() / 0.25F);
                double expectedYaw = i == 0 ? -1023.75 : 0.25;  // wrap from 4095 * 0.25 back to 0
                double expectedPitch = (i % 2 == 0) ? -0.5 : 0.5;
                if (r.yawDelta() != expectedYaw || r.pitchDelta() != expectedPitch) {
                    torn.set("yaw " + r.move().yaw() + " shown with dYaw " + r.yawDelta() + " dPitch " + r.pitchDelta());
                }
            }
        });
        reader.start();
        for (int i = 2; i < EVENTS; i++) {
            diagnostics.onEvent(session, rotation(i));
            if (i % 7 == 0) {  // a position-only move in between leaves the rotation pair intact
                diagnostics.onEvent(session, new TimelineEvent.Inbound(i, i, 0,
                        new ServerboundPacket.MovePlayer(1, 2, 3, Float.NaN, Float.NaN, true, false, true, false)));
            }
        }
        done.set(true);
        reader.join();
        assertEquals(null, torn.get());
        assertEquals(0.25, view.lastRotation().yawDelta());
    }
}
