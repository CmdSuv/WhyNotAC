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
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.EntitySizes;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Interact-at hitbox bounds ({@code interact-at-box}, P-M, SHADOW): verifies that when a client sends
 * {@code ServerboundInteractPacket} with {@code Kind.INTERACT_AT}, the relative hit vector {@code location}
 * ({@code hitVec.subtract(entity.position())} from {@code AABB.clip}) is finite and lies within the target
 * entity's possible bounding box dimensions plus pick/margin inflation.
 */
public final class InteractAtBoxCheck implements TimelineListener {

    public static final String ID = "interact-at-box";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final double BOX_TOLERANCE = 0.25;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public InteractAtBoxCheck(EvidenceSink sink, Replicas replicas) {
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
        if (event instanceof TimelineEvent.Inbound in
                && in.packet() instanceof ServerboundPacket.InteractEntity interact
                && interact.kind() == ServerboundPacket.InteractEntity.Kind.INTERACT_AT
                && interact.location() != null) {
            onInteractAt(session, state, in, interact);
        }
    }

    private void onInteractAt(PlayerSession session, State state, TimelineEvent.Inbound in, ServerboundPacket.InteractEntity interact) {
        Vec3d loc = interact.location();
        if (!Double.isFinite(loc.x()) || !Double.isFinite(loc.y()) || !Double.isFinite(loc.z())) {
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "non-finite-interact-at");
            details.put("target", Integer.toString(interact.entityId()));
            report(session, state, in.sequence(), in.nanoTime(), in, "INTERACT_AT carried non-finite location vector", details);
            return;
        }
        ClientSelf self = replicas.self().self(session);
        if (self.mayBeCreativeOrSpectator()) {
            return;
        }
        ClientEntities entities = replicas.entities().entities(session);
        List<ClientEntities.PossibleState> states = new ArrayList<>();
        entities.forEachPossibleState(interact.entityId(), states::add);
        if (states.isEmpty()) {
            return;
        }
        double maxHalfX = 0.0;
        double maxHalfZ = 0.0;
        double minY = 0.0;
        double maxY = 0.0;
        for (ClientEntities.PossibleState s : states) {
            if (!s.present() || !s.known() || s.bounds() == null) {
                return;
            }
            EntitySizes.Box b = s.bounds();
            double halfX = (b.maxX() - b.minX()) * 0.5 + s.pickRadius() + BOX_TOLERANCE;
            double halfZ = (b.maxZ() - b.minZ()) * 0.5 + s.pickRadius() + BOX_TOLERANCE;
            double lowY = -s.pickRadius() - BOX_TOLERANCE;
            double highY = (b.maxY() - b.minY()) + s.pickRadius() + BOX_TOLERANCE;
            maxHalfX = Math.max(maxHalfX, halfX);
            maxHalfZ = Math.max(maxHalfZ, halfZ);
            minY = Math.min(minY, lowY);
            maxY = Math.max(maxY, highY);
        }
        if (Math.abs(loc.x()) > maxHalfX || Math.abs(loc.z()) > maxHalfZ || loc.y() < minY || loc.y() > maxY) {
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "out-of-bounds-interact-at");
            details.put("target", Integer.toString(interact.entityId()));
            details.put("location", String.format(Locale.ROOT, "%.4f,%.4f,%.4f", loc.x(), loc.y(), loc.z()));
            details.put("allowedHalfXZ", String.format(Locale.ROOT, "%.4f,%.4f", maxHalfX, maxHalfZ));
            details.put("allowedY", String.format(Locale.ROOT, "%.4f..%.4f", minY, maxY));
            report(session, state, in.sequence(), in.nanoTime(), in,
                    String.format(Locale.ROOT, "INTERACT_AT offset (%.3f, %.3f, %.3f) outside entity %d hitbox bounds",
                            loc.x(), loc.y(), loc.z(), interact.entityId()),
                    details);
        }
    }

    private void report(PlayerSession session, State state, long fromSeq, long fromNano,
                        TimelineEvent.Inbound at, String summary, Map<String, String> details) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = state.admissibility.evaluate(fromSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, fromSeq, at.sequence(),
                fromNano, at.nanoTime(), summary, details, admissibility));
        state.lastReportNanos = at.nanoTime();
    }
}
