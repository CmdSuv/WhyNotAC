import dev.whynotac.analysis.checks.BreakTimeCheck;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.replay.Replayer;
import dev.whynotac.world.Replicas;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Replays a recording through the replicas and the break-time check, one JSON line per block finish the client sent:
 * what the check made of it (within the progress the client may have reached, early, not known, no dig followed), the
 * dig's increments, those counted a fifth because the client was certainly off the ground, and the largest progress.
 *
 * <p>usage: java -cp WhyNotAC.jar BreakAudit.java RECORDING
 */
public class BreakAudit {

    public static void main(String[] args) throws Exception {
        Replicas replicas = Replicas.vanilla();
        List<TimelineListener> chain = new ArrayList<>(replicas.listeners());
        chain.add(new BreakTimeCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession session, Evidence evidence) { }

            @Override
            public void rejected(PlayerSession session, String check, Admissibility admissibility) { }
        }, replicas, j -> System.out.println("{\"sequence\":" + j.sequence() + ",\"outcome\":\"" + j.outcome() + "\",\"ticks\":" + j.ticks()
                + ",\"offGroundTicks\":" + j.offGroundTicks() + ",\"maxProgress\":"
                + (Float.isNaN(j.maxProgress()) ? "null" : String.format(Locale.ROOT, "%.6f", j.maxProgress())) + "}")));
        Replayer.Result result = Replayer.replay(RecordingReader.open(Path.of(args[0])), chain);
        if (!result.listenerErrors().isEmpty()) {
            throw new IllegalStateException("listener errors: " + result.listenerErrors());
        }
    }
}
