package dev.whynotac.analysis.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.physics.PlayerState;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** States equal but for the options the server cannot see become one, holding every option either allowed. */
class HypothesisMergeTest {

    private static PlayerState at(double x) {
        return new PlayerState(x, 64.0, 0.5, Vec3d.ZERO, 0.0F, 0.0F, true, false, false, true, true, 0.0, false, false, false, PlayerState.Pose.STANDING,
                PlayerState.Keys.NONE, 0, 0, 0, null, false, Vec3d.ZERO, false, false);
    }

    private static Hypothesis hypothesis(PlayerState p, int window, int autoJump, int cosine) {
        return new Hypothesis(p, SelfState.INITIAL, Hypothesis.Sent.fresh(PlayerState.Keys.NONE, false), window, autoJump, cosine, 0);
    }

    @Test
    void theMasksOfEqualStatesAreJoined() {
        Set<Hypothesis> states = new LinkedHashSet<>();
        states.add(hypothesis(at(0.5), 1 << 3, Hypothesis.AUTO_JUMP_OFF, 0b001)); // one platform's cosine
        states.add(hypothesis(at(0.5), 1 << 7, Hypothesis.AUTO_JUMP_ON, 0b110)); // the others', the same state after all
        states.add(hypothesis(at(0.75), 1 << 2, Hypothesis.AUTO_JUMP_ON, 0b100));
        List<Hypothesis> merged = MovementTracker.merged(states);
        assertEquals(2, merged.size());
        Hypothesis joined = merged.getFirst();
        assertEquals(1 << 3 | 1 << 7, joined.windowMask());
        assertEquals(Hypothesis.AUTO_JUMP_ANY, joined.autoJumpMask());
        assertEquals(Hypothesis.ALL_COSINES, joined.cosineMask());
        assertEquals(0b100, merged.get(1).cosineMask(), "another state keeps its own");
    }
}
