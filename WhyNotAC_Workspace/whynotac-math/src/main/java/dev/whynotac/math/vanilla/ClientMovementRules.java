package dev.whynotac.math.vanilla;

/**
 * When the 1.21.11 client sends which movement packet ({@code LocalPlayer.sendPosition}).
 *
 * <p>The client does <b>not</b> report every movement. A position is only sent when the player moved
 * more than {@link #POSITION_THRESHOLD} (2e-4 blocks) since the last <em>sent</em> position, or when
 * {@link #POSITION_REMINDER_TICKS} ticks have passed. So a server-side simulation has a built-in
 * uncertainty sphere of radius 2e-4 around the last reported position whenever a tick arrives
 * without a position. This is a hard information limit of the protocol: no anti-cheat can measure
 * sub-2e-4 movement from a vanilla client, and treating the gap as zero movement creates false flags.
 *
 * <p>Per tick, in order: {@code PlayerInput} (only if keys changed), sprint {@code PlayerCommand}
 * (only if changed), then exactly one of {@code PosRot / Pos / Rot / StatusOnly} or nothing, and at
 * the end of the client tick {@code ClientTickEnd}. While riding, the client sends {@code Rot} every
 * tick plus {@code MoveVehicle} if it controls the vehicle.
 */
public final class ClientMovementRules {

    /** Movement below this distance (exclusive) is not reported unless the reminder fires. */
    public static final double POSITION_THRESHOLD = 2.0E-4;
    /** A position is re-sent at least every 20 ticks. */
    public static final int POSITION_REMINDER_TICKS = 20;

    private ClientMovementRules() {}

    /**
     * The client's exact test: {@code Mth.lengthSquared(dx, dy, dz) > Mth.square(2.0E-4) || reminder >= 20}.
     *
     * @param reminderTicks the client's {@code positionReminder} after its increment for this tick
     */
    public static boolean sendsPosition(double dx, double dy, double dz, int reminderTicks) {
        return VanillaMath.lengthSquared(dx, dy, dz) > VanillaMath.square(POSITION_THRESHOLD) || reminderTicks >= POSITION_REMINDER_TICKS;
    }

    /** The client sends a rotation when either float component changed at all ({@code != 0.0} after widening). */
    public static boolean sendsRotation(float yaw, float lastYaw, float pitch, float lastPitch) {
        double dYaw = yaw - lastYaw;
        double dPitch = pitch - lastPitch;
        return dYaw != 0.0 || dPitch != 0.0;
    }
}
