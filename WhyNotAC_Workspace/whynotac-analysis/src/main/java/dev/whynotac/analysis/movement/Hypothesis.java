package dev.whynotac.analysis.movement;

import dev.whynotac.math.vanilla.PlatformCos;
import dev.whynotac.physics.PlayerState;

/**
 * One state the client's own player may be in between two of its ticks: its physics, the rest of its
 * state, what it last sent ({@link Sent}), the options the server cannot see, and how many of the
 * packets sent to it the client has processed.
 *
 * @param windowMask   the {@code sprintWindow} option values (bit per value 0..10) still possible
 * @param autoJumpMask bit 0: auto-jump may be off; bit 1: it may be on
 * @param cosineMask   the platforms ({@link PlatformCos}, bit per ordinal) whose {@code Math.cos} the client's may be
 * @param applied      index of the first queued packet about the player this state has not processed
 */
public record Hypothesis(PlayerState player, SelfState self, Sent sent, int windowMask, int autoJumpMask, int cosineMask, long applied) {

    static final int ALL_WINDOWS = (1 << 11) - 1;
    static final int AUTO_JUMP_OFF = 1;
    static final int AUTO_JUMP_ON = 2;
    static final int AUTO_JUMP_ANY = AUTO_JUMP_OFF | AUTO_JUMP_ON;
    static final int ALL_COSINES = (1 << PlatformCos.values().length) - 1;

    /**
     * {@code LocalPlayer}'s record of what it last sent ({@code sendPosition}, {@code sendIsSprintingIfNeeded},
     * the input packet): the next packet depends on it.
     */
    public record Sent(double x, double y, double z, float yRot, float xRot, boolean onGround, boolean horizontalCollision, int positionReminder,
            boolean sprinting, PlayerState.Keys input) {

        /** A new {@code LocalPlayer}: zeros, except what a respawn keeps (the last input sent and whether it sprinted). */
        static Sent fresh(PlayerState.Keys input, boolean sprinting) {
            return new Sent(0.0, 0.0, 0.0, 0.0F, 0.0F, false, false, 0, sprinting, input);
        }
    }

    Hypothesis withPlayer(PlayerState p) {
        return new Hypothesis(p, self, sent, windowMask, autoJumpMask, cosineMask, applied);
    }

    Hypothesis withSelf(SelfState s) {
        return new Hypothesis(player, s, sent, windowMask, autoJumpMask, cosineMask, applied);
    }

    Hypothesis withApplied(long index) {
        return new Hypothesis(player, self, sent, windowMask, autoJumpMask, cosineMask, index);
    }
}
