package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.vector.Vec3d;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.RelativeFlag;
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
 *   <li>{@code non-finite-movement}: {@code MovePlayer}, {@code MoveVehicle}, or {@code UseItem} carrying {@code NaN}
 *       or {@code +-Infinity} in a present coordinate or angle;</li>
 *   <li>{@code invalid-pitch}: {@code MovePlayer} (with rotation) or {@code UseItem} carrying {@code |pitch| > 90.0001}
 *       when the server has not sent an out-of-range or relative pitch ({@code Entity.turn} clamps to {@code [-90, 90]});</li>
 *   <li>{@code invalid-hotbar-slot}: {@code SetCarriedItem} outside the hotbar index range {@code 0..8}
 *       ({@code Inventory.isHotbarSlot});</li>
 *   <li>{@code duplicate-hotbar-slot}: consecutive {@code SetCarriedItem} packets carrying the identical slot within
 *       one {@code MultiPlayerGameMode} lifetime ({@code ensureHasSentCarriedItem} only sends on {@code slot != carriedIndex});</li>
 *   <li>{@code self-interaction}: {@code InteractEntity} targeting the player's own entity id
 *       ({@code ProjectileUtil.getEntityHitResult} excludes the shooter/camera entity);</li>
 *   <li>{@code invalid-block-cursor}: {@code UseItemOn} with a non-finite hit location or a cursor offset
 *       outside the vanilla block outline envelope {@code [-1.0001, 2.0001]};</li>
 *   <li>{@code invalid-container-slot}, {@code invalid-container-swap}, and {@code invalid-container-button}:
 *       {@code ContainerClick} with {@code slot < -1 && slot != -999}, {@code SWAP} button outside {@code 0..8} / {@code 40},
 *       {@code THROW} button outside {@code 0..1}, {@code PICKUP_ALL} button {@code != 0}, or {@code QUICK_CRAFT}
 *       button outside valid drag states ({@code 0..2, 4..6, 8..10});</li>
 *   <li>{@code command-entity-mismatch} and {@code invalid-command-data}: {@code PlayerCommand} targeting an entity
 *       id other than {@code selfId}, or carrying out-of-bounds {@code data} ({@code START_RIDING_JUMP} outside
 *       {@code 0..100}, or non-zero {@code data} on any other known action);</li>
 *   <li>{@code invalid-chunk-batch-rate}: {@code ChunkBatchReceived} with non-finite or non-positive
 *       {@code desiredChunksPerTick} ({@code !Float.isFinite(rate) || rate <= 0.0F}); vanilla
 *       {@code ChunkBatchSizeCalculator.getDesiredChunksPerTick()} has no client-side upper bound
 *       ({@code PlayerChunkSender.onChunkBatchReceivedByClient} clamps to {@code [0.01, 64]} on the server).</li>
 * </ul>
 */
public final class PacketBoundsCheck implements TimelineListener {

    public static final String ID = "packet-bounds";
    public static final int VERSION = 4;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    static final double MIN_CURSOR_OFFSET = -1.0001;
    static final double MAX_CURSOR_OFFSET = 2.0001;
    static final float MAX_PITCH = 90.0001F;

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
        int lastSentHotbarSlot = -1;
        boolean serverMayExceedPitch;

        void resetSession() {
            lastSentHotbarSlot = -1;
            serverMayExceedPitch = false;
        }
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> state.lastSentHotbarSlot = -1;
            case TimelineEvent.Outbound out -> onOutbound(session, state, out);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onOutbound(PlayerSession session, State state, TimelineEvent.Outbound out) {
        switch (out.packet()) {
            case ClientboundPacket.Login ignored -> state.resetSession();
            case ClientboundPacket.Respawn ignored -> state.resetSession();
            case ClientboundPacket.Untranslatable ignored -> state.lastSentHotbarSlot = -1;
            case ClientboundPacket.PlayerPosition pos -> {
                if (pos.relatives().contains(RelativeFlag.X_ROT) || pos.relatives().contains(RelativeFlag.UNKNOWN)
                        || !Float.isFinite(pos.pitch()) || Math.abs(pos.pitch()) > 90.0F) {
                    state.serverMayExceedPitch = true;
                }
            }
            case ClientboundPacket.PlayerRotation rot -> {
                if (rot.relativePitch() || !Float.isFinite(rot.pitch()) || Math.abs(rot.pitch()) > 90.0F) {
                    state.serverMayExceedPitch = true;
                }
            }
            case ClientboundPacket.TeleportEntity tp -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && tp.entityId() == self.selfId()) {
                    state.serverMayExceedPitch = true;
                }
            }
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.MovePlayer move when move.hasNonFiniteValue() -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "non-finite-movement");
                details.put("shape", move.shape().name());
                report(session, state, in, "MovePlayer carried non-finite coordinate or rotation (" + move.shape() + ")", details, true);
            }
            case ServerboundPacket.MovePlayer move when move.hasRotation() && !state.serverMayExceedPitch
                    && Math.abs(move.pitch()) > MAX_PITCH -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "invalid-pitch");
                details.put("pitch", Float.toString(move.pitch()));
                details.put("source", "MovePlayer");
                report(session, state, in, "MovePlayer pitch " + move.pitch() + " outside vanilla [-90, 90] clamp", details, true);
            }
            case ServerboundPacket.UseItem use when !Float.isFinite(use.yaw()) || !Float.isFinite(use.pitch()) -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "non-finite-movement");
                details.put("shape", "USE_ITEM");
                report(session, state, in, "UseItem carried non-finite rotation", details, true);
            }
            case ServerboundPacket.UseItem use when !state.serverMayExceedPitch && Math.abs(use.pitch()) > MAX_PITCH -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "invalid-pitch");
                details.put("pitch", Float.toString(use.pitch()));
                details.put("source", "UseItem");
                report(session, state, in, "UseItem pitch " + use.pitch() + " outside vanilla [-90, 90] clamp", details, true);
            }
            case ServerboundPacket.MoveVehicle vehicle when !isFiniteVehicle(vehicle) -> {
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "non-finite-movement");
                details.put("shape", "MOVE_VEHICLE");
                report(session, state, in, "MoveVehicle carried non-finite coordinate or rotation", details, true);
            }
            case ServerboundPacket.SetCarriedItem held -> {
                if (held.slot() < 0 || held.slot() > 8) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-hotbar-slot");
                    details.put("slot", Integer.toString(held.slot()));
                    report(session, state, in, "selected hotbar slot " + held.slot() + " outside vanilla 0..8 bounds", details, true);
                } else if (state.lastSentHotbarSlot == held.slot()) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "duplicate-hotbar-slot");
                    details.put("slot", Integer.toString(held.slot()));
                    report(session, state, in, "sent duplicate consecutive SetCarriedItem for slot " + held.slot(), details, false);
                } else {
                    state.lastSentHotbarSlot = held.slot();
                }
            }
            case ServerboundPacket.InteractEntity interact -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && interact.entityId() == self.selfId()) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "self-interaction");
                    details.put("entityId", Integer.toString(interact.entityId()));
                    details.put("kind", interact.kind().name());
                    report(session, state, in, "interacted with own entity id " + interact.entityId() + " (" + interact.kind() + ")", details, true);
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
                        details, false);
            }
            case ServerboundPacket.ContainerClick click -> {
                if (click.slot() < -1 && click.slot() != -999) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-container-slot");
                    details.put("slot", Integer.toString(click.slot()));
                    report(session, state, in, "ContainerClick slot " + click.slot() + " outside vanilla bounds (>= -1 or -999)", details, false);
                } else if ("SWAP".equals(click.clickType()) && (click.button() < 0 || (click.button() > 8 && click.button() != 40))) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-container-swap");
                    details.put("button", Integer.toString(click.button()));
                    report(session, state, in, "ContainerClick SWAP button " + click.button() + " outside vanilla 0..8 or 40 bounds", details, false);
                } else if (isInvalidContainerButton(click.clickType(), click.button())) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-container-button");
                    details.put("clickType", click.clickType());
                    details.put("button", Integer.toString(click.button()));
                    report(session, state, in,
                            "ContainerClick " + click.clickType() + " button " + click.button() + " outside vanilla bounds",
                            details, false);
                }
            }
            case ServerboundPacket.PlayerCommand cmd -> {
                ClientSelf self = replicas.self().self(session);
                if (self.selfId() >= 0 && cmd.entityId() != self.selfId()) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "command-entity-mismatch");
                    details.put("entityId", Integer.toString(cmd.entityId()));
                    details.put("selfId", Integer.toString(self.selfId()));
                    report(session, state, in, "PlayerCommand entityId " + cmd.entityId() + " != selfId " + self.selfId(), details, false);
                } else if (cmd.action() == ServerboundPacket.PlayerCommand.Action.START_RIDING_JUMP
                        && (cmd.data() < 0 || cmd.data() > 100)) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-command-data");
                    details.put("action", cmd.action().name());
                    details.put("data", Integer.toString(cmd.data()));
                    report(session, state, in, "START_RIDING_JUMP data " + cmd.data() + " outside vanilla 0..100 bounds", details, false);
                } else if (cmd.action() != ServerboundPacket.PlayerCommand.Action.START_RIDING_JUMP
                        && cmd.action() != ServerboundPacket.PlayerCommand.Action.UNKNOWN
                        && cmd.data() != 0) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-command-data");
                    details.put("action", cmd.action().name());
                    details.put("data", Integer.toString(cmd.data()));
                    report(session, state, in, "PlayerCommand " + cmd.action() + " carried non-zero data " + cmd.data(), details, false);
                }
            }
            case ServerboundPacket.ChunkBatchReceived batch -> {
                float rate = batch.desiredChunksPerTick();
                // Vanilla ChunkBatchSizeCalculator.getDesiredChunksPerTick() returns (float)(7000000.0 / aggregatedNanosPerChunk)
                // with no absolute lower bound on aggregatedNanosPerChunk, so rates > 64 are valid on fast clients;
                // only PlayerChunkSender.onChunkBatchReceivedByClient clamps to [0.01, 64] server-side.
                if (!Float.isFinite(rate) || rate <= 0.0F) {
                    Map<String, String> details = new TreeMap<>();
                    details.put("reason", "invalid-chunk-batch-rate");
                    details.put("desiredChunksPerTick", Float.toString(rate));
                    report(session, state, in, "ChunkBatchReceived desiredChunksPerTick " + rate + " is non-finite or <= 0", details, false);
                }
            }
            case ServerboundPacket.Untranslatable ignored -> state.lastSentHotbarSlot = -1;
            default -> { }
        }
    }

    private static boolean isFiniteVehicle(ServerboundPacket.MoveVehicle v) {
        Vec3d p = v.position();
        return Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z())
                && Float.isFinite(v.yaw()) && Float.isFinite(v.pitch());
    }

    private static boolean isInvalidContainerButton(String clickType, int button) {
        if (clickType == null) {
            return false;
        }
        return switch (clickType) {
            case "THROW" -> button != 0 && button != 1;
            case "PICKUP_ALL" -> button != 0;
            case "QUICK_CRAFT" -> button < 0 || button > 10 || (button & 3) == 3;
            default -> false;
        };
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

    private void report(PlayerSession session, State state, TimelineEvent.Inbound at, String summary, Map<String, String> details,
            boolean translatorSafe) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = translatorSafe
                ? state.admissibility.evaluateTranslatorSafe(at.sequence())
                : state.admissibility.evaluate(at.sequence());
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, at.sequence(), at.sequence(),
                at.nanoTime(), at.nanoTime(), summary, details, admissibility));
        state.lastReportNanos = at.nanoTime();
    }
}
