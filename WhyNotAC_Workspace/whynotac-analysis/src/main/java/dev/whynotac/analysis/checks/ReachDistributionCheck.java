package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.Replicas;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ghost-reach boundary clustering distribution ({@code reach-distribution}, S, {@code STATISTICAL_SIGNALS}):
 * while {@link AttackReachCheck} produces deterministic {@code P-A} evidence past {@code 3.0001} blocks,
 * subtle ghost reach / hitbox-expander modules that clamp hits to the {@code [2.92, 3.00]} vanilla limit
 * create an unnaturally tight boundary cluster over sustained survival combat.
 *
 * <p>Requires &ge; {@link #MIN_SAMPLES} ({@code 40}) admissible standard-reach ({@code 3.0} blocks)
 * survival attacks with median &gt; {@link #MIN_MEDIAN} ({@code 2.94}), &ge; {@link #MIN_BAND_FRACTION}
 * ({@code 85%}) falling inside {@code [2.92, 3.00]}, and sample standard deviation &lt; {@link #MAX_STD_DEV}
 * ({@code 0.025} blocks). Class S (nominal statistic): never automates punishments; acts as a prioritized
 * moderator &amp; {@code AiAdvisor} signal.
 */
public final class ReachDistributionCheck implements TimelineListener {

    public static final String ID = "reach-distribution";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final int MIN_SAMPLES = 40;
    static final double STANDARD_REACH = 3.0;
    static final double REACH_TOLERANCE = 0.05;
    static final double BOUNDARY_LO = 2.92;
    static final double BOUNDARY_HI = 3.0001;
    static final double MIN_MEDIAN = 2.94;
    static final double MIN_BAND_FRACTION = 0.85;
    static final double MAX_STD_DEV = 0.025;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public ReachDistributionCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private record HitSample(double distance, long seq, long nano) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final ArrayDeque<HitSample> samples = new ArrayDeque<>();
        long lastReportNanos = Long.MIN_VALUE;

        void reset() {
            samples.clear();
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
            case TimelineEvent.Gap ignored -> state.reset();
            case TimelineEvent.Outbound out when out.packet() instanceof ClientboundPacket.Login
                    || out.packet() instanceof ClientboundPacket.Respawn -> state.reset();
            case TimelineEvent.Inbound in when in.packet() instanceof ServerboundPacket.InteractEntity interact
                    && interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK -> onAttack(session, state, in, interact);
            default -> { }
        }
    }

    private void onAttack(PlayerSession session, State state, TimelineEvent.Inbound in, ServerboundPacket.InteractEntity interact) {
        ClientSelf self = replicas.self().self(session);
        AttackReachCheck.Assessment finding = AttackReachCheck.assess(
                self,
                replicas.inventory().inventory(session),
                replicas.entities().entities(session),
                replicas.world().world(session),
                interact.entityId());
        if (finding.outcome() != AttackReachCheck.Outcome.IN_REACH) {
            return;
        }
        if (!Double.isFinite(finding.distance()) || !Double.isFinite(finding.allowed())
                || Math.abs(finding.allowed() - STANDARD_REACH) > REACH_TOLERANCE) {
            return;
        }
        state.samples.addLast(new HitSample(finding.distance(), self.eyeSequence(), in.nanoTime()));
        while (state.samples.size() > MIN_SAMPLES) {
            state.samples.pollFirst();
        }
        if (state.samples.size() < MIN_SAMPLES) {
            return;
        }
        double[] values = new double[MIN_SAMPLES];
        RunningMoments moments = new RunningMoments();
        int inBand = 0;
        int idx = 0;
        for (HitSample s : state.samples) {
            double d = s.distance();
            values[idx++] = d;
            moments.add(d);
            if (d >= BOUNDARY_LO && d <= BOUNDARY_HI) {
                inBand++;
            }
        }
        Arrays.sort(values);
        double median = 0.5 * (values[MIN_SAMPLES / 2 - 1] + values[MIN_SAMPLES / 2]);
        double bandFraction = (double) inBand / MIN_SAMPLES;
        double stdDev = moments.sampleStdDev();
        if (median <= MIN_MEDIAN || bandFraction < MIN_BAND_FRACTION || stdDev >= MAX_STD_DEV) {
            return;
        }
        if (state.lastReportNanos != Long.MIN_VALUE && in.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        HitSample oldest = state.samples.peekFirst();
        Admissibility admissibility = state.admissibility.evaluate(oldest.seq());
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            state.samples.clear();
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("reason", "clustered-boundary-reach");
        details.put("samples", Integer.toString(MIN_SAMPLES));
        details.put("median", String.format(Locale.ROOT, "%.4f", median));
        details.put("bandFraction", String.format(Locale.ROOT, "%.4f", bandFraction));
        details.put("stdDev", String.format(Locale.ROOT, "%.5f", stdDev));
        sink.accept(session, new Evidence(
                ID, VERSION, EvidenceClass.NOMINAL_STATISTIC,
                oldest.seq(), in.sequence(), oldest.nano(), in.nanoTime(),
                String.format(Locale.ROOT, "boundary-clustered survival reach (median %.3f, %.0f%% in [2.92,3.00], sd %.4f over %d hits)",
                        median, bandFraction * 100.0, stdDev, MIN_SAMPLES),
                details, admissibility));
        state.lastReportNanos = in.nanoTime();
        state.samples.clear();
    }
}
