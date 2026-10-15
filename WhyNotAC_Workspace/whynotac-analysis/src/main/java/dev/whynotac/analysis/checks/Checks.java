package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Every shipped check, by id: the plugin registers them all after the replicas they read, {@code verify}
 * re-runs the one a dossier names over a fresh set of replicas.
 */
public final class Checks {

    private static final Map<String, Function<EvidenceSink, TimelineListener>> FACTORIES = Map.of(
            TickFramingCheck.ID, TickFramingCheck::new,
            ClientClockCheck.ID, ClientClockCheck::new);
    private static final Map<String, Integer> VERSIONS = Map.of(
            TickFramingCheck.ID, TickFramingCheck.VERSION,
            ClientClockCheck.ID, ClientClockCheck.VERSION);

    private Checks() {}

    /** Every check, in a fixed order, reporting to {@code sink}; register them after {@code replicas}' trackers. */
    public static List<TimelineListener> all(EvidenceSink sink, Replicas replicas) {
        return List.of(new TickFramingCheck(sink), new ClientClockCheck(sink));
    }

    /** The check with this id, behind a fresh set of the replicas it may read, or null. */
    public static TimelineListener create(String id, EvidenceSink sink) {
        Function<EvidenceSink, TimelineListener> factory = FACTORIES.get(id);
        if (factory == null) {
            return null;
        }
        List<TimelineListener> chain = new ArrayList<>(Replicas.vanilla().listeners());
        chain.add(factory.apply(sink));
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
