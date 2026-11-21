package dev.whynotac.analysis.movement;

import dev.whynotac.physics.Border;
import dev.whynotac.protocol.packet.ClientboundPacket;

/**
 * The client's world border: the {@code WorldBorder} of its {@code ClientLevel}, as the packets it processed and its
 * ticks left it (decompiled 1.21.11). Every new level has a new one ({@code new WorldBorder()}: centred on 0, 0, as wide
 * as the world): every login, and a respawn into another dimension ({@code handleRespawn} compares the keys). The
 * server's {@code InitializeBorder} sets all of it (centre, size or lerp, absolute maximum); {@code SetBorderCenter},
 * {@code SetBorderSize} and {@code SetBorderLerpSize} one part each. A lerp is a {@code MovingBorderExtent}: one step in
 * every level tick that runs normally ({@code ClientLevel.tick}: {@code tickRateManager().runsNormally()}, after the
 * entities), and a {@code StaticBorderExtent} at its target once its ticks are spent. Immutable.
 *
 * @param dimension the level's dimension key, or null when not known
 */
public record ClientBorder(String dimension, double centerX, double centerZ, int absoluteMaxSize, Extent extent) {

    /** {@code WorldBorder.absoluteMaxSize}'s initial value. */
    static final int DEFAULT_ABSOLUTE_MAX_SIZE = 29999984;
    /** {@code new StaticBorderExtent(5.999997E7F)}: the float, widened. */
    static final double DEFAULT_SIZE = 5.999997E7F;

    /** {@code WorldBorder.BorderExtent}. */
    public sealed interface Extent permits Static, Moving, Uncertain {}

    /** {@code StaticBorderExtent}. */
    public record Static(double size) implements Extent {}

    /**
     * {@code MovingBorderExtent}: from {@code from} to {@code to} over {@code duration} ticks ({@code lerpDuration}, a
     * double), {@code progress} of them left, the size now and before the last step.
     */
    public record Moving(double from, double to, double duration, long progress, double size, double previousSize) implements Extent {
        /** {@code new MovingBorderExtent(from, to, time, gameTime)}: both sizes the start's. */
        static Moving start(double from, double to, long time) {
            double size = size(from, to, time, time);
            return new Moving(from, to, time, time, size, size);
        }

        /** {@code calculateSize}: {@code Mth.lerp} of the part done, the target once it is done (or the part is NaN). */
        static double size(double from, double to, double duration, long progress) {
            double done = (duration - progress) / duration;
            return done < 1.0 ? from + done * (to - from) : to;
        }

        /** {@code update()}: one step, the static target once none is left. */
        Extent update() {
            long left = progress - 1;
            double next = size(from, to, duration, left);
            return left <= 0L ? new Static(to) : new Moving(from, to, duration, left, next, size);
        }
    }

    /**
     * A size not known exactly, between {@code minSize} and {@code maxSize}: a lerp whose steps are not certain (processed
     * at a tick not known, or ticks that may not have run normally). It is certainly static at {@code to} once
     * {@code ticksLeft} more level ticks certainly ran normally; never when {@code ticksLeft} is negative (nothing known).
     */
    public record Uncertain(double minSize, double maxSize, long ticksLeft, double to) implements Extent {}

    /** A new level's border. */
    public static ClientBorder fresh(String dimension) {
        return new ClientBorder(dimension, 0.0, 0.0, DEFAULT_ABSOLUTE_MAX_SIZE, new Static(DEFAULT_SIZE));
    }

    /** Not known at all (a respawn that may have made a new level): only the server's {@code InitializeBorder} tells. */
    public static ClientBorder unknown(String dimension) {
        return new ClientBorder(dimension, 0.0, 0.0, DEFAULT_ABSOLUTE_MAX_SIZE, new Uncertain(0.0, Double.POSITIVE_INFINITY, -1L, DEFAULT_SIZE));
    }

    /** After a respawn into {@code key}: the same level's border, a new level's, or not known. */
    public ClientBorder respawned(String key) {
        if (key != null && key.equals(dimension)) {
            return this;
        }
        return key != null && dimension != null ? fresh(key) : unknown(key);
    }

    /** {@code setCenter}. */
    public ClientBorder withCenter(double x, double z) {
        return new ClientBorder(dimension, x, z, absoluteMaxSize, extent);
    }

    /** {@code setSize}: static. */
    public ClientBorder withSize(double size) {
        return new ClientBorder(dimension, centerX, centerZ, absoluteMaxSize, new Static(size));
    }

    /** {@code lerpSizeBetween(from, to, time, gameTime)}: static when the sizes are equal. */
    public ClientBorder lerp(double from, double to, long time) {
        return new ClientBorder(dimension, centerX, centerZ, absoluteMaxSize, from == to ? new Static(to) : Moving.start(from, to, time));
    }

    /** {@code ClientPacketListener.handleInitializeBorder}. */
    public ClientBorder initialized(ClientboundPacket.InitializeBorder b) {
        ClientBorder centred = withCenter(b.centerX(), b.centerZ());
        ClientBorder sized = b.lerpTime() > 0L ? centred.lerp(b.oldSize(), b.newSize(), b.lerpTime()) : centred.withSize(b.newSize());
        return new ClientBorder(dimension, sized.centerX, sized.centerZ, b.absoluteMaxSize(), sized.extent);
    }

    /**
     * After a level tick ({@code WorldBorder.tick}) that ran normally ({@code ranNormally} true), did not (false), or may
     * have (null).
     */
    public ClientBorder ticked(Boolean ranNormally) {
        Extent next = switch (extent) {
            case Static s -> s;
            case Moving m when Boolean.TRUE.equals(ranNormally) -> m.update();
            case Moving m when ranNormally == null -> new Uncertain(Math.min(m.from(), m.to()), Math.max(m.from(), m.to()), m.progress(), m.to());
            case Moving m -> m;
            case Uncertain u when Boolean.TRUE.equals(ranNormally) && u.ticksLeft() > 0L ->
                    u.ticksLeft() == 1L ? new Static(u.to()) : new Uncertain(u.minSize(), u.maxSize(), u.ticksLeft() - 1L, u.to());
            case Uncertain u -> u;
        };
        return next == extent ? this : new ClientBorder(dimension, centerX, centerZ, absoluteMaxSize, next);
    }

    /**
     * Processed at a tick not known, at the latest the one about to run: a lerp's steps are not known, but all of it is
     * certainly done one tick after its time has certainly run (the extra tick in case it was processed after this one's
     * level tick).
     */
    public ClientBorder processedBefore() {
        if (extent instanceof Moving m) {
            return new ClientBorder(dimension, centerX, centerZ, absoluteMaxSize,
                    new Uncertain(Math.min(m.from(), m.to()), Math.max(m.from(), m.to()), m.progress() + 1L, m.to()));
        }
        return this;
    }

    /** Whether the border is known exactly: its collision is then {@link #collision()}. */
    public boolean known() {
        return !(extent instanceof Uncertain);
    }

    /**
     * What the collision reads ({@code getMinX()} and the rest: partial tick 0, a moving extent's previous size, clamped to
     * the absolute maximum); null when the border is not known exactly.
     */
    public Border collision() {
        return switch (extent) {
            case Static s -> bounds(s.size());
            case Moving m -> bounds(m.previousSize() + 0.0 * (m.size() - m.previousSize())); // Mth.lerp(0.0F, previousSize, size)
            case Uncertain u -> null;
        };
    }

    private Border bounds(double size) {
        return new Border(clamp(centerX - size / 2.0), clamp(centerZ - size / 2.0), clamp(centerX + size / 2.0), clamp(centerZ + size / 2.0));
    }

    /** {@code Mth.clamp(value, -absoluteMaxSize, absoluteMaxSize)}. */
    private double clamp(double value) {
        double min = -absoluteMaxSize;
        double max = absoluteMaxSize;
        return value < min ? min : Math.min(value, max);
    }

    /**
     * Whether the box (x and z bounds) stays more than {@code margin} inside the border for every size it may have: an
     * uncertain border's bounds are nearest at its smallest size.
     */
    boolean farFrom(double minX, double minZ, double maxX, double maxZ, double margin) {
        Border b = collision();
        if (b == null) {
            b = bounds(((Uncertain) extent).minSize());
        }
        return minX - margin > b.minX() && maxX + margin < b.maxX() && minZ - margin > b.minZ() && maxZ + margin < b.maxZ();
    }
}
