package dev.whynotac.physics;

import dev.whynotac.math.vanilla.VanillaMath;

/**
 * {@code ItemBasedSteering} as the 1.21.11 client holds it for a pig (or strider) a player steers with an item on a stick:
 * the boost the client runs itself. It starts when the server's boost time reaches the client ({@code onSynced}: every
 * entity data value of {@code DATA_BOOST_TIME} the client is sent, the one at spawn too) and counts in each tick a player
 * controls it ({@code tickRidden} before {@code getRiddenSpeed}), whatever the value; the speed it gives is
 * {@code 1 + 1.15·sin(time / total · π)} while boosting.
 *
 * @param boosting  {@code boosting}
 * @param boostTime {@code boostTime}: the ticks it has counted
 */
public record ItemSteering(boolean boosting, int boostTime) {

    /** Never boosted: a fresh entity's. */
    public static final ItemSteering NONE = new ItemSteering(false, 0);

    /** {@code onSynced}: the client was sent the boost time. */
    public static ItemSteering synced() {
        return new ItemSteering(true, 0);
    }

    /** {@code tickBoost} with {@code total} the boost time the client holds ({@code boostTimeTotal}). */
    public ItemSteering tick(int total) {
        if (!boosting) {
            return this;
        }
        int time = boostTime + 1; // boostTime++ > total: compared before the increment
        return boostTime > total ? new ItemSteering(false, time) : new ItemSteering(true, time);
    }

    /** {@code boostFactor} with {@code total} the boost time the client holds. */
    public float factor(int total) {
        return boosting ? 1.0F + 1.15F * VanillaMath.sin((float) boostTime / total * (float) Math.PI) : 1.0F;
    }
}
