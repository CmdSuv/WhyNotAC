package dev.whynotac.analysis.checks;

import dev.whynotac.world.ClientInventory;
import java.util.Set;

/**
 * Whether an entity attack the client sent was within what a vanilla 1.21.11 client can reach.
 *
 * <p>The client (decompiled 1.21.11) decides an attack in two steps:
 * <ol>
 *   <li>The pick, at the start of the tick ({@code GameRenderer.pick} through
 *       {@code LocalPlayer.raycastHitResult}), with the main hand's stack then (the client is not using an
 *       item when it attacks). Without {@code attack_range}: the vanilla pick, which takes an entity only if
 *       the ray enters its box inflated by its pick radius strictly closer than
 *       {@code entity_interaction_range}. With one: that range's own ray, unbounded by the attribute (it is
 *       longer by the player's motion along its look), falling back to the vanilla pick when it hits
 *       nothing.</li>
 *   <li>The check in {@code Minecraft.startAttack}, with the main hand's stack then (after the tick's
 *       hotbar keys): with {@code attack_range} the hit point must be at most {@code max_reach} (in
 *       creative {@code max_creative_reach}) plus {@code hitbox_margin} from the eye; without, nothing.</li>
 * </ol>
 * The hit point of the vanilla pick lies on the target's box inflated by its pick radius (or is the eye, inside
 * it); that of the {@code attack_range} ray on the box itself ({@code ProjectileUtil.getManyEntityHitResult}).
 * Either way the eye's distance to the inflated box is at most the hit's. An attack
 * is out of reach when, for every stack the pick may have used and every stack the check may have used,
 * the target's box is beyond what that pair allows, from every eye position the client may have had and for
 * every box the target may have had.
 */
public final class AttackReach {

    /** The client compares doubles with strict and inclusive bounds; this much rounding is allowed on top. */
    static final double EPSILON = 1.0E-4;

    private AttackReach() {}

    /** An axis-aligned box. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box inflate(double d) {
            return new Box(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
        }
    }

    /**
     * The eye positions the client may have had: x and z within {@code radius} of the given point, y on
     * {@code [y0, y1]} widened by the same radius.
     */
    public record Eye(double x, double z, double y0, double y1, double radius) {}

    /** The smallest distance between any eye position and the box (0 if they meet). */
    public static double distance(Eye eye, Box box) {
        double dx = Math.max(Math.max(box.minX() - eye.x(), 0.0), eye.x() - box.maxX());
        double dz = Math.max(Math.max(box.minZ() - eye.z(), 0.0), eye.z() - box.maxZ());
        double dy = Math.max(Math.max(box.minY() - eye.y1(), 0.0), eye.y0() - box.maxY());
        return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - eye.radius());
    }

    /**
     * True if no pair (pick stack, check stack) lets a vanilla client hit {@code box} from {@code eye}.
     *
     * @param pick the reaches the main hand may have had at the pick
     * @param check the reaches the main hand may have at the check
     * @param interactionRange the largest {@code entity_interaction_range} the client may have
     * @param mayBeCreative whether the client may be in creative ({@code max_creative_reach} applies)
     * @param pickRadius the target's pick radius (1 for a pickable projectile, else 0)
     */
    public static boolean outOfReach(Eye eye, Box box, double pickRadius, Set<ClientInventory.Reach> pick, Set<ClientInventory.Reach> check,
            double interactionRange, boolean mayBeCreative) {
        // a lower bound of the eye's distance to the hit point, whichever pick found it
        double toHit = distance(eye, box.inflate(pickRadius));
        for (ClientInventory.Reach p : pick) {
            for (ClientInventory.Reach a : check) {
                if (!p.known() || !a.known()) {
                    return false;
                }
                // the vanilla pick: the hit strictly closer than the attribute (filterHitResult); the
                // attack_range ray is not bounded by it and falls back to the vanilla pick
                boolean pickAllows = p.range() != null || toHit < interactionRange + EPSILON;
                // startAttack: the hit at most max_reach + hitbox_margin from the eye (AttackRange.isInRange)
                boolean checkAllows = a.range() == null || toHit <= maxReach(a, mayBeCreative) + a.range().hitboxMargin() + EPSILON;
                if (pickAllows && checkAllows) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The farthest a vanilla client could hit from, over every pair (pick stack, check stack): the vanilla
     * pick's {@code entity_interaction_range} and the attack's {@code max_reach + hitbox_margin}, whichever
     * bound the pair has (infinite with neither, or with an unknown reach). For reports.
     */
    public static double allowed(Set<ClientInventory.Reach> pick, Set<ClientInventory.Reach> check, double interactionRange, boolean mayBeCreative) {
        double allowed = 0.0;
        for (ClientInventory.Reach p : pick) {
            for (ClientInventory.Reach a : check) {
                if (!p.known() || !a.known()) {
                    return Double.POSITIVE_INFINITY;
                }
                double pickBound = p.range() == null ? interactionRange : Double.POSITIVE_INFINITY;
                double checkBound = a.range() == null ? Double.POSITIVE_INFINITY : maxReach(a, mayBeCreative) + a.range().hitboxMargin();
                allowed = Math.max(allowed, Math.min(pickBound, checkBound));
            }
        }
        return allowed;
    }

    /** {@code AttackRange.effectiveMaxRange} for a player: the creative value in creative. */
    static double maxReach(ClientInventory.Reach reach, boolean mayBeCreative) {
        double survival = reach.range().maxRange();
        return mayBeCreative ? Math.max(survival, reach.range().maxCreativeRange()) : survival;
    }
}
