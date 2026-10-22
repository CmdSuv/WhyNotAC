package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Every shipped check, by id: the plugin registers them all after the replicas they read, {@code verify}
 * re-runs the one a dossier names over a fresh set of replicas.
 */
public final class Checks {

    private static final Map<String, BiFunction<EvidenceSink, Replicas, TimelineListener>> FACTORIES = Map.of(
            TickFramingCheck.ID, (sink, replicas) -> new TickFramingCheck(sink),
            ClientClockCheck.ID, (sink, replicas) -> new ClientClockCheck(sink),
            AttackReachCheck.ID, AttackReachCheck::new,
            AttackGrammarCheck.ID, AttackGrammarCheck::new,
            MovementCheck.ID, MovementCheck::new);
    private static final Map<String, Integer> VERSIONS = Map.of(
            TickFramingCheck.ID, TickFramingCheck.VERSION,
            ClientClockCheck.ID, ClientClockCheck.VERSION,
            AttackReachCheck.ID, AttackReachCheck.VERSION,
            AttackGrammarCheck.ID, AttackGrammarCheck.VERSION,
            MovementCheck.ID, MovementCheck.VERSION);

    private Checks() {}

    /** Every check, in a fixed order, reporting to {@code sink}; register them after {@code replicas}' trackers. */
    public static List<TimelineListener> all(EvidenceSink sink, Replicas replicas) {
        return List.of(new TickFramingCheck(sink), new ClientClockCheck(sink), new AttackReachCheck(sink, replicas), new AttackGrammarCheck(sink, replicas),
                new MovementCheck(sink, replicas));
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
}
