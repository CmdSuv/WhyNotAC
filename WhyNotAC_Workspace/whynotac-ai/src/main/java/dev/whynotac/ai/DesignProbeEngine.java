package dev.whynotac.ai;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Stateful sequential probe experiment evaluator (AI-04 / AI-05, {@code EvidenceClass.DESIGN_STATISTIC}).
 *
 * <p>Tracks active decoy probes registered per session; counts unexpected decoy hits vs clean timeouts,
 * feeds {@link ProbeScheduler#recordOutcome}, and emits an admissible {@code D} finding solely once the
 * sequential test martingale crosses {@code 1 / alpha}. Strictly never authorizes neutralization, kicks,
 * or bans.
 */
public final class DesignProbeEngine implements TimelineListener {

    public static final String CHECK_ID = "design-probe";
    public static final int VERSION = 1;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private static final class ActiveDecoy {
        final int entityId;
        final double nullHitRate;
        final long startSequence;
        final long startNanoTime;
        final long expiresAtTick;
        boolean hit;

        ActiveDecoy(int entityId, double nullHitRate, long startSequence, long startNanoTime, long expiresAtTick) {
            this.entityId = entityId;
            this.nullHitRate = nullHitRate;
            this.startSequence = startSequence;
            this.startNanoTime = startNanoTime;
            this.expiresAtTick = expiresAtTick;
        }
    }

    private static final class SessionState {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        long tick;
        long lastAttackTick = Long.MIN_VALUE;
        long lastSequence;
        long lastNanoTime;
        ActiveDecoy active;
    }

    private final ProbeScheduler scheduler;
    private final EvidenceSink sink;

    public DesignProbeEngine(ProbeScheduler scheduler, EvidenceSink sink) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    /** Registers a freshly spawned phantom decoy probe for {@code session}. */
    public boolean registerDecoy(PlayerSession session, int virtualEntityId, long ttlTicks, double nullHitRate) {
        if (session == null || ttlTicks < 1) {
            return false;
        }
        SessionState s = session.attachment(STATE, SessionState::new);
        synchronized (s) {
            if (s.active != null) {
                return false;
            }
            s.active = new ActiveDecoy(
                    virtualEntityId, nullHitRate,
                    Math.max(1L, s.lastSequence), s.lastNanoTime,
                    s.tick + ttlTicks);
            return true;
        }
    }

    public boolean isInCombat(PlayerSession session) {
        if (session == null) {
            return false;
        }
        SessionState s = session.attachment(STATE, SessionState::new);
        synchronized (s) {
            return s.lastAttackTick != Long.MIN_VALUE && (s.tick - s.lastAttackTick) <= 80L;
        }
    }

    public long currentTick(PlayerSession session) {
        if (session == null) {
            return 0L;
        }
        SessionState s = session.attachment(STATE, SessionState::new);
        synchronized (s) {
            return s.tick;
        }
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        SessionState s = session.attachment(STATE, SessionState::new);
        synchronized (s) {
            s.admissibility.onEvent(event);
            s.lastSequence = event.sequence();
            s.lastNanoTime = event.nanoTime();
            if (!(event instanceof TimelineEvent.Inbound in)) {
                return;
            }
            switch (in.packet()) {
                case ServerboundPacket.InteractEntity interact when interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK -> {
                    s.lastAttackTick = s.tick;
                    if (s.active != null && interact.entityId() == s.active.entityId && !s.active.hit) {
                        s.active.hit = true;
                        UUID pid = playerUuid(session);
                        if (pid != null) {
                            EValueProcess ep = scheduler.recordOutcome(pid, 1.0, s.active.nullHitRate);
                            maybeEmitEvidence(session, s, s.active, ep, in.sequence(), in.nanoTime());
                        }
                        s.active = null;
                    }
                }
                case ServerboundPacket.ClientTickEnd ignored -> {
                    s.tick++;
                    if (s.active != null && s.tick >= s.active.expiresAtTick) {
                        if (!s.active.hit) {
                            UUID pid = playerUuid(session);
                            if (pid != null) {
                                scheduler.recordOutcome(pid, 0.0, s.active.nullHitRate);
                            }
                        }
                        s.active = null;
                    }
                }
                default -> { }
            }
        }
    }

    private void maybeEmitEvidence(
            PlayerSession session, SessionState s, ActiveDecoy decoy,
            EValueProcess ep, long toSeq, long toNano) {
        if (!ep.rejectedNull()) {
            return;
        }
        Admissibility adm = s.admissibility.evaluate(decoy.startSequence);
        if (!adm.admissible()) {
            sink.rejected(session, CHECK_ID, adm);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("eValueWealth", String.format(Locale.ROOT, "%.2f", ep.wealth()));
        details.put("nullHitRate", String.format(Locale.ROOT, "%.4f", decoy.nullHitRate));
        details.put("observations", Long.toString(ep.observations()));
        details.put("observedMean", String.format(Locale.ROOT, "%.4f", ep.observedMean()));
        String summary = String.format(Locale.ROOT,
                "active probe martingale rejected H0 (E=%.2f across %d probes, p0=%.3f)",
                ep.wealth(), ep.observations(), decoy.nullHitRate);
        Evidence evidence = new Evidence(
                CHECK_ID, VERSION, EvidenceClass.DESIGN_STATISTIC,
                decoy.startSequence, toSeq, decoy.startNanoTime, toNano,
                summary, details, adm);
        ep.reset();
        sink.accept(session, evidence);
    }

    private static UUID playerUuid(PlayerSession session) {
        return session.identity() != null ? session.identity().playerId() : null;
    }
}
