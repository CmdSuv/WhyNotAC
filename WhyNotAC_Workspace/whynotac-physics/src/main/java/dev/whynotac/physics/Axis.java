package dev.whynotac.physics;

/** {@code Direction.Axis}, in the game's order. */
public enum Axis {
    X, Y, Z;

    /** {@code Direction.Axis.choose}. */
    public double choose(double x, double y, double z) {
        return switch (this) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    public int choose(int x, int y, int z) {
        return switch (this) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }
}
