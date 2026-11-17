package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.Replicas;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Packet bounds ({@code packet-bounds}, P-M, SHADOW): protocol-level structural bounds and invariants that
 * no vanilla 1.21.11 client can violate under any game state (BadPackets / exploit / crasher payloads).
 *
 * <ul>
 *   <li>{@code non-finite-movement}: {@code MovePlayer} or {@code MoveVehicle} carrying {@code NaN} or
 *       {@code +-Infinity} in a present coordinate or angle;</li>
 *   <li>{@code invalid-hotbar-slot}: {@code SetCarriedItem} outside the hotbar index range {@code 0..8}
 *       ({@code Inventory.isHotbarSlot});</li>
 *   <li>{@code self-interaction}: {@code InteractEntity} targeting the player's own entity id
 *       ({@code ProjectileUtil.getEntityHitResult} excludes the shooter/camera entity);</li>
 *   <li>{@code invalid-block-cursor}: {@code UseItemOn} with a non-finite hit location or a cursor offset
 *       outside the vanilla block outline envelope {@code [-1.0001, 2.0001]};</li>
 *   <li>{@code invalid-container-slot}: {@code ContainerClick} with {@code slot < -1 && slot != -999}
 *       ({@code AbstractContainerScreen.slotClicked});</li>
 *   <li>{@code invalid-container-swap}: {@code ContainerClick} with {@code clickType == "SWAP"} and
 *       {@code button} outside hotbar {@code 0..8} and offhand {@code 40} ({@code AbstractContainerMenu.doClick}).</li>
 * </ul>
 */
public final class PacketBoundsCheck implements TimelineListener {

    public static final String ID = "packet-bounds";
    public static final int VERSION = 2;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    static final double MIN_CURSOR_OFFSET = -1.0001;
    static final double MAX_CURSOR_OFFSET = 2.0001;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public PacketBoundsCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        long lastReportNanos = Long.MIN_VALUE;
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        if (event instanceof TimelineEvent.Inbound in) {
            onInbound(session, state, in);
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.MovePlayer move when move.hasNonFiniteValue() -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "non-finite-movement");
                details.put("shape", move.shape().name());
                report(session, state, in, "MovePlayer carried non-finite coordinate or rotation (" + move.shape() + ")", details);
            }
            case ServerboundPacket.MoveVehicle vehicle when !isFiniteVehicle(vehicle) -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "non-finite-movement");
                details.put("shape", "MOVE_VEHICLE");
                report(session, state, in, "MoveVehicle carried non-finite coordinate or rotation", details);
            }
            case ServerboundPacket.SetCarriedItem held when held.slot() < 0 || held.slot() > 8 -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "invalid-hotbar-slot");
                details.put("slot", Integer.toString(held.slot()));
                report(session, state, in, "selected hotbar slot " + held.slot() + " outside vanilla 0..8 bounds", details);
            }
            case ServerboundPacket.InteractEntity interact -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && interact.entityId() == self.selfId()) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "self-interaction");
                    details.put("entityId", Integer.toString(interact.entityId()));
                    details.put("kind", interact.kind().name());
                    report(session, state, in, "interacted with own entity id " + interact.entityId() + " (" + interact.kind() + ")", details);
                }
            }
            case ServerboundPacket.UseItemOn useOn when !isValidCursor(useOn) -> {
                Vec3d loc = useOn.location();
                double dx = loc.x() - useOn.position().x();
                double dy = loc.y() - useOn.position().y();
                double dz = loc.z() - useOn.position().z();
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "invalid-block-cursor");
                details.put("cursorOffset", String.format(Locale.ROOT, "%.4f,%.4f,%.4f", dx, dy, dz));
                report(session, state, in,
                        String.format(Locale.ROOT, "UseItemOn cursor offset (%.4f, %.4f, %.4f) outside vanilla block bounds", dx, dy, dz),
                        details);
            }
            case ServerboundPacket.ContainerClick click -> {
                if (click.slot() < -1 && click.slot() != -999) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-container-slot");
                    details.put("slot", Integer.toString(click.slot()));
                    report(session, state, in, "ContainerClick slot " + click.slot() + " outside vanilla bounds (>= -1 or -999)", details);
                } else if ("SWAP".equals(click.clickType()) && (click.button() < 0 || (click.button() > 8 && click.button() != 40))) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-container-swap");
                    details.put("button", Integer.toString(click.button()));
                    report(session, state, in, "ContainerClick SWAP button " + click.button() + " outside vanilla 0..8 or 40 bounds", details);
                }
            }
            default -> { }
        }
    }

    private static boolean isFiniteVehicle(ServerboundPacket.MoveVehicle v) {
        Vec3d p = v.position();
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z())
                && Float.isFinite(v.yaw()) && Float.isFinite(v.pitch());
    }

    private static boolean isValidCursor(ServerboundPacket.UseItemOn useOn) {
        Vec3d loc = useOn.location();
        if (!Double.isFinite(loc.x()) || !Double.isFinite(loc.y()) || !Double.isFinite(loc.z())) {
            return false;
        }
        double dx = loc.x() - useOn.position().x();
        double dy = loc.y() - useOn.position().y();
        double dz = loc.z() - useOn.position().z();
        return dx >= MIN_CURSOR_OFFSET && dx <= MAX_CURSOR_OFFSET
                && dy >= MIN_CURSOR_OFFSET && dy <= MAX_CURSOR_OFFSET
                && dz >= MIN_CURSOR_OFFSET && dz <= MAX_CURSOR_OFFSET;
    }

    private void report(PlayerSession session, State state, TimelineEvent.Inbound at, String summary, Map<String, String> details) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = state.admissibility.evaluate(at.sequence());
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, at.sequence(), at.sequence(),
                at.nanoTime(), at.nanoTime(), summary, details, admissibility));
        state.lastReportNanos = at.nanoTime();
    }
}
