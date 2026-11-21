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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Container interaction timing ({@code container-timing}, S, SHADOW): statistical signals of automated chest
 * stealers, inventory managers, and robotic container transfer loops.
 *
 * <p>Uses {@code ClientTickEnd} framing so measurements are immune to network batching:
 * <ul>
 *   <li>{@code rapid-container-transfer}: &ge; {@link #BURST_DISTINCT_CLICKS} distinct-slot clicks inside a
 *       server container ({@code containerId > 0}) within &le; {@link #BURST_WINDOW_TICKS} client ticks;</li>
 *   <li>{@code constant-container-cadence}: &ge; {@link #CADENCE_WINDOW} consecutive distinct-slot container
 *       intervals (1..{@link #MAX_CADENCE_GAP_TICKS} ticks, mean &le; {@link #MAX_MEAN_CADENCE_TICKS}) with
 *       near-zero tick variance ({@link RunningMoments#sampleStdDev()} &le; {@link #MAX_CADENCE_STD_DEV}).</li>
 * </ul>
 *
 * <p>Class S (nominal statistic): never triggers automated bans, kicks, or mitigations ({@code docs/POLICY.md}).
 */
public final class ContainerTimingCheck implements TimelineListener {

    public static final String ID = "container-timing";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    static final int BURST_DISTINCT_CLICKS = 10;
    static final long BURST_WINDOW_TICKS = 10L;

    static final int CADENCE_WINDOW = 14;
    static final int MIN_CADENCE_GAP_TICKS = 1;
    static final int MAX_CADENCE_GAP_TICKS = 6;
    static final double MAX_MEAN_CADENCE_TICKS = 3.5;
    static final double MAX_CADENCE_STD_DEV = 0.10;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public ContainerTimingCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private record ClickSample(long tick, long seq, long nano) {}

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        long tick;
        long lastReportNanos = Long.MIN_VALUE;

        int lastContainerId = -1;
        int lastSlot = Integer.MIN_VALUE;
        long lastDistinctClickTick = -1L;

        final ArrayDeque<ClickSample> chestBurst = new ArrayDeque<>();
        final ArrayDeque<Integer> intervals = new ArrayDeque<>();
        final ArrayDeque<ClickSample> intervalSamples = new ArrayDeque<>();

        void resetContainers() {
            lastContainerId = -1;
            lastSlot = Integer.MIN_VALUE;
            lastDistinctClickTick = -1L;
            chestBurst.clear();
            intervals.clear();
            intervalSamples.clear();
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
            case TimelineEvent.Gap ignored -> state.resetContainers();
            case TimelineEvent.Outbound out -> {
                if (out.packet() instanceof ClientboundPacket.Login
                        || out.packet() instanceof ClientboundPacket.Respawn
                        || out.packet() instanceof ClientboundPacket.OpenScreen
                        || out.packet() instanceof ClientboundPacket.ContainerClose) {
                    state.resetContainers();
                }
            }
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.ClientTickEnd ignored -> state.tick++;
            case ServerboundPacket.ContainerClose ignored -> state.resetContainers();
            case ServerboundPacket.ContainerClick click -> onClick(session, state, in, click);
            default -> { }
        }
    }

    private void onClick(PlayerSession session, State state, TimelineEvent.Inbound in, ServerboundPacket.ContainerClick click) {
        if (click.slot() < 0) {
            return;
        }
        ClientSelf self = replicas.self().self(session);
        if (self.mayBeCreativeOrSpectator()) {
            state.resetContainers();
            return;
        }
        if (click.containerId() != state.lastContainerId) {
            state.resetContainers();
            state.lastContainerId = click.containerId();
        }
        // Repeated clicks on the very same slot (drag stacks / quick pickup) do not count as distinct looting moves
        if (click.slot() == state.lastSlot) {
            return;
        }
        state.lastSlot = click.slot();

        if (click.containerId() > 0) {
            state.chestBurst.addLast(new ClickSample(state.tick, in.sequence(), in.nanoTime()));
            while (!state.chestBurst.isEmpty()
                    && (state.tick - state.chestBurst.peekFirst().tick()) > BURST_WINDOW_TICKS) {
                state.chestBurst.pollFirst();
            }
            if (state.chestBurst.size() >= BURST_DISTINCT_CLICKS) {
                ClickSample oldest = state.chestBurst.peekFirst();
                long spanTicks = state.tick - oldest.tick();
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "rapid-container-transfer");
                details.put("containerId", Integer.toString(click.containerId()));
                details.put("clicks", Integer.toString(state.chestBurst.size()));
                details.put("spanTicks", Long.toString(spanTicks));
                if (report(session, state, oldest.seq(), oldest.nano(), in,
                        state.chestBurst.size() + " distinct chest clicks within " + spanTicks + " client ticks",
                        details)) {
                    state.chestBurst.clear();
                    return;
                }
            }
        }

        if (state.lastDistinctClickTick >= 0) {
            long gap = state.tick - state.lastDistinctClickTick;
            if (gap >= MIN_CADENCE_GAP_TICKS && gap <= MAX_CADENCE_GAP_TICKS) {
                state.intervals.addLast((int) gap);
                state.intervalSamples.addLast(new ClickSample(state.tick, in.sequence(), in.nanoTime()));
                while (state.intervals.size() > CADENCE_WINDOW) {
                    state.intervals.pollFirst();
                    state.intervalSamples.pollFirst();
                }
                if (state.intervals.size() == CADENCE_WINDOW) {
                    RunningMoments moments = new RunningMoments();
                    for (int dt : state.intervals) {
                        moments.add(dt);
                    }
                    double mean = moments.mean();
                    double sd = moments.sampleStdDev();
                    if (mean <= MAX_MEAN_CADENCE_TICKS && sd <= MAX_CADENCE_STD_DEV) {
                        ClickSample oldest = state.intervalSamples.peekFirst();
                        Map<String, String> details = new TreeMap<>();
                        details.put("reason", "constant-container-cadence");
                        details.put("samples", Integer.toString(CADENCE_WINDOW));
                        details.put("meanTicks", String.format(Locale.ROOT, "%.3f", mean));
                        details.put("stdDevTicks", String.format(Locale.ROOT, "%.4f", sd));
                        if (report(session, state, oldest.seq(), oldest.nano(), in,
                                String.format(Locale.ROOT, "constant container interval (mean %.2f ticks, sd %.4f over %d clicks)",
                                        mean, sd, CADENCE_WINDOW),
                                details)) {
                            state.intervals.clear();
                            state.intervalSamples.clear();
                        }
                    }
                }
            } else if (gap > MAX_CADENCE_GAP_TICKS) {
                state.intervals.clear();
                state.intervalSamples.clear();
            }
        }
        state.lastDistinctClickTick = state.tick;
    }

    private boolean report(PlayerSession session, State state, long fromSeq, long fromNano,
                           TimelineEvent.Inbound at, String summary, Map<String, String> details) {
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return false;
        }
        Admissibility admissibility = state.admissibility.evaluate(fromSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return true;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.NOMINAL_STATISTIC, fromSeq, at.sequence(),
                fromNano, at.nanoTime(), summary, details, admissibility));
        state.lastReportNanos = at.nanoTime();
        return true;
    }
}
