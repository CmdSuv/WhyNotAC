package dev.whynotac.analysis.checks;

import dev.whynotac.math.vanilla.VanillaMath;

/**
 * Whether the look the client had when it picked could have met an entity's box.
 *
 * <p>The client (decompiled 1.21.11) attacks only the entity its pick found at the start of the tick
 * ({@code Minecraft.startAttack} on {@code hitResult}, from {@code GameRenderer.pick} through
 * {@code LocalPlayer.raycastHitResult}). Every way it finds one is along a ray from the eye:
 * <ul>
 *   <li>the vanilla pick: the view vector ({@code getViewVector(1)}: the current pitch and yaw), the box inflated by
 *       the entity's pick radius, or the eye inside it ({@code ProjectileUtil.getEntityHitResult});</li>
 *   <li>with an {@code attack_range} item first: the head's look ({@code getHeadLookAngle}: the current pitch and
 *       the head's yaw), the box or the box inflated by the range's {@code hitbox_margin}, from a start point on
 *       that ray ({@code ProjectileUtil.getManyEntityHitResult}).</li>
 * </ul>
 * With the eye anywhere in a box {@code S} and the target's box {@code B}, a ray from some eye along {@code v} meets
 * {@code B} exactly when the ray from the origin along {@code v} meets {@code B - S}, the box of every offset from an
 * eye to a point of {@code B}. Only rays ({@code t >= 0}, unbounded) are tested: the client's segments are on them,
 * and how far it reached is {@link AttackReach}'s.
 */
final class AttackAim {

    /** Rounding allowed on top of the client's own (its clip works on the same doubles, in other steps). */
    static final double EPSILON = 1.0E-4;

    private AttackAim() {}

    /** {@code Entity.calculateViewVector(xRot, yRot)}: float products of the game's sine table. */
    static double[] view(float xRot, float yRot) {
        float x = xRot * (float) (Math.PI / 180.0);
        float y = -yRot * (float) (Math.PI / 180.0);
        float cosY = VanillaMath.cos(y);
        float sinY = VanillaMath.sin(y);
        float cosX = VanillaMath.cos(x);
        float sinX = VanillaMath.sin(x);
        return new double[] {sinY * cosX, -sinX, cosY * cosX};
    }

    /**
     * Every offset from an eye in {@code eye} (x and z within its radius, y on {@code [y0, y1]} widened by it) to a point
     * of {@code box}, widened by {@link #EPSILON}.
     */
    static AttackReach.Box offsets(AttackReach.Eye eye, AttackReach.Box box) {
        double r = eye.radius() + EPSILON;
        return new AttackReach.Box(box.minX() - eye.x() - r, box.minY() - eye.y1() - r, box.minZ() - eye.z() - r,
                box.maxX() - eye.x() + r, box.maxY() - eye.y0() + r, box.maxZ() - eye.z() + r);
    }

    /** Whether the ray from the origin along {@code v} ({@code t >= 0}) meets the box (bounds included). */
    static boolean meets(double[] v, AttackReach.Box d) {
        double[] range = {0.0, Double.POSITIVE_INFINITY};
        return slab(v[0], d.minX(), d.maxX(), range) && slab(v[1], d.minY(), d.maxY(), range) && slab(v[2], d.minZ(), d.maxZ(), range);
    }

    private static boolean slab(double v, double min, double max, double[] range) {
        if (v == 0.0) {
            return min <= 0.0 && 0.0 <= max;
        }
        double t1 = min / v;
        double t2 = max / v;
        range[0] = Math.max(range[0], Math.min(t1, t2));
        range[1] = Math.min(range[1], Math.max(t1, t2));
        return range[0] <= range[1];
    }

    /**
     * How far the ray from the origin along {@code v} passes from the box at its closest (0 if they meet), over the first
     * {@code length} blocks. For reports: the distance is convex along the ray, so a ternary search finds it.
     */
    static double missBy(double[] v, AttackReach.Box d, double length) {
        double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double lo = 0.0;
        double hi = length / norm;
        for (int i = 0; i < 100; i++) {
            double a = lo + (hi - lo) / 3.0;
            double b = hi - (hi - lo) / 3.0;
            if (distance(v, d, a) <= distance(v, d, b)) {
                hi = b;
            } else {
                lo = a;
            }
        }
        return distance(v, d, (lo + hi) / 2.0);
    }

    private static double distance(double[] v, AttackReach.Box d, double t) {
        double dx = Math.max(Math.max(d.minX() - v[0] * t, 0.0), v[0] * t - d.maxX());
        double dy = Math.max(Math.max(d.minY() - v[1] * t, 0.0), v[1] * t - d.maxY());
        double dz = Math.max(Math.max(d.minZ() - v[2] * t, 0.0), v[2] * t - d.maxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
