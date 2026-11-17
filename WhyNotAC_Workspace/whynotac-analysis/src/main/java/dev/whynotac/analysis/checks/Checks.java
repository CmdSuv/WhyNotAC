package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Every shipped check, by id: the plugin registers them all after the replicas they read, {@code verify}
 * re-runs the one a dossier names over a fresh set of replicas.
 */
public final class Checks {

    private static final Map<String, BiFunction<EvidenceSink, Replicas, TimelineListener>> FACTORIES = Map.ofEntries(
            Map.entry(TickFramingCheck.ID, (sink, replicas) -> new TickFramingCheck(sink)),
            Map.entry(ClientClockCheck.ID, (sink, replicas) -> new ClientClockCheck(sink)),
            Map.entry(AttackReachCheck.ID, AttackReachCheck::new),
            Map.entry(AttackAimCheck.ID, AttackAimCheck::new),
            Map.entry(UseAimCheck.ID, UseAimCheck::new),
            Map.entry(BreakTimeCheck.ID, BreakTimeCheck::new),
            Map.entry(PredictionSequenceCheck.ID, PredictionSequenceCheck::new),
            Map.entry(ActionGrammarCheck.ID, ActionGrammarCheck::new),
            Map.entry(MovementCheck.ID, MovementCheck::new),
            Map.entry(ClickPatternCheck.ID, ClickPatternCheck::new),
            Map.entry(AimPatternCheck.ID, AimPatternCheck::new),
            Map.entry(ContainerTimingCheck.ID, ContainerTimingCheck::new),
            Map.entry(ScaffoldPatternCheck.ID, ScaffoldPatternCheck::new),
            Map.entry(PacketBoundsCheck.ID, PacketBoundsCheck::new),
            Map.entry(TransactionOrderCheck.ID, (sink, replicas) -> new TransactionOrderCheck(sink)));
    private static final Map<String, Integer> VERSIONS = Map.ofEntries(
            Map.entry(TickFramingCheck.ID, TickFramingCheck.VERSION),
            Map.entry(ClientClockCheck.ID, ClientClockCheck.VERSION),
            Map.entry(AttackReachCheck.ID, AttackReachCheck.VERSION),
            Map.entry(AttackAimCheck.ID, AttackAimCheck.VERSION),
            Map.entry(UseAimCheck.ID, UseAimCheck.VERSION),
            Map.entry(BreakTimeCheck.ID, BreakTimeCheck.VERSION),
            Map.entry(PredictionSequenceCheck.ID, PredictionSequenceCheck.VERSION),
            Map.entry(ActionGrammarCheck.ID, ActionGrammarCheck.VERSION),
            Map.entry(MovementCheck.ID, MovementCheck.VERSION),
            Map.entry(ClickPatternCheck.ID, ClickPatternCheck.VERSION),
            Map.entry(AimPatternCheck.ID, AimPatternCheck.VERSION),
            Map.entry(ContainerTimingCheck.ID, ContainerTimingCheck.VERSION),
            Map.entry(ScaffoldPatternCheck.ID, ScaffoldPatternCheck.VERSION),
            Map.entry(PacketBoundsCheck.ID, PacketBoundsCheck.VERSION),
            Map.entry(TransactionOrderCheck.ID, TransactionOrderCheck.VERSION));

    private Checks() {}

    /** Every check, in a fixed order, reporting to {@code sink}; register them after {@code replicas}' trackers. */
    public static List<TimelineListener> all(EvidenceSink sink, Replicas replicas) {
        return List.of(new TickFramingCheck(sink), new ClientClockCheck(sink), new AttackReachCheck(sink, replicas), new AttackAimCheck(sink, replicas),
                new UseAimCheck(sink, replicas), new BreakTimeCheck(sink, replicas), new PredictionSequenceCheck(sink, replicas),
                new ActionGrammarCheck(sink, replicas), new MovementCheck(sink, replicas),
                new ClickPatternCheck(sink, replicas), new AimPatternCheck(sink, replicas),
                new ContainerTimingCheck(sink, replicas), new ScaffoldPatternCheck(sink, replicas),
                new PacketBoundsCheck(sink, replicas),
                new TransactionOrderCheck(sink));
    }

    /** The check with this id, behind a fresh set of the replicas it may read, or null. */
    public static TimelineListener create(String id, EvidenceSink sink) {
        BiFunction<EvidenceSink, Replicas, TimelineListener> factory = FACTORIES.get(id);
        if (factory == null) {
            return null;
        }
        Replicas replicas = Replicas.vanilla();
        List<TimelineListener> chain = new ArrayList<>(replicas.listeners());
        chain.add(factory.apply(sink, replicas));
        return new TimelineListener() {
            @Override
            public void onEvent(PlayerSession session, TimelineEvent event) {
                for (TimelineListener listener : chain) {
                    listener.onEvent(session, event);
                }
            }
        };
    }

    /** The version this build runs of the check with this id, or -1. */
    public static int version(String id) {
        return VERSIONS.getOrDefault(id, -1);
    }

    /** Registered check ids (unmodifiable). */
    public static Set<String> ids() {
        return VERSIONS.keySet();
    }
}
