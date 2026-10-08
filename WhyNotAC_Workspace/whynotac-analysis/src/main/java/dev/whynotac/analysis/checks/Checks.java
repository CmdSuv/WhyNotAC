package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Every shipped check, by id: the plugin registers them all, {@code verify} re-runs the one a dossier names. */
public final class Checks {

    private static final Map<String, Function<EvidenceSink, TimelineListener>> FACTORIES = Map.of(
            TickFramingCheck.ID, TickFramingCheck::new,
            ClientClockCheck.ID, ClientClockCheck::new);
    private static final Map<String, Integer> VERSIONS = Map.of(
            TickFramingCheck.ID, TickFramingCheck.VERSION,
            ClientClockCheck.ID, ClientClockCheck.VERSION);

    private Checks() {}

    /** Every check, in a fixed order, reporting to {@code sink}. */
    public static List<TimelineListener> all(EvidenceSink sink) {
        return List.of(new TickFramingCheck(sink), new ClientClockCheck(sink));
    }

    /** The check with this id, or null. */
    public static TimelineListener create(String id, EvidenceSink sink) {
        Function<EvidenceSink, TimelineListener> factory = FACTORIES.get(id);
        return factory == null ? null : factory.apply(sink);
    }

    /** The version this build runs of the check with this id, or -1. */
    public static int version(String id) {
        return VERSIONS.getOrDefault(id, -1);
    }
}
