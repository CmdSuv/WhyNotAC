import dev.whynotac.analysis.checks.AttackReachCheck;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.replay.Replayer;
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.Replicas;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Replays a recording through the replicas and assesses every attack the client sent the way the
 * attack-reach check does, one JSON line per attack: whether it claims (out of reach), finds it in reach
 * (with the distance and the farthest reach allowed), or makes no claim (and why).
 *
 * <p>usage: java -cp WhyNotAC.jar ReachAudit.java RECORDING
 */
public class ReachAudit {

    public static void main(String[] args) throws Exception {
        Replicas replicas = Replicas.vanilla();
        List<TimelineListener> chain = new ArrayList<>(replicas.listeners());
        chain.add((session, event) -> {
            if (event instanceof TimelineEvent.Inbound in && in.packet() instanceof ServerboundPacket.InteractEntity interact
                    && interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK) {
                AttackReachCheck.Assessment a = AttackReachCheck.assess(replicas.self().self(session), replicas.inventory().inventory(session),
                        replicas.entities().entities(session), replicas.world().world(session), interact.entityId());
                System.out.println("{\"sequence\":" + in.sequence() + ",\"target\":" + a.target() + ",\"type\":" + quote(a.type())
                        + ",\"outcome\":\"" + a.outcome() + "\",\"reason\":" + quote(a.reason()) + ",\"distance\":" + number(a.distance())
                        + ",\"allowed\":" + number(a.allowed()) + ",\"pick\":" + quote(describe(a.pick())) + ",\"check\":" + quote(describe(a.check()))
                        + ",\"interactionRange\":" + number(a.interactionRange()) + ",\"states\":" + a.states() + "}");
            }
        });
        Replayer.Result result = Replayer.replay(RecordingReader.open(Path.of(args[0])), chain);
        if (!result.listenerErrors().isEmpty()) {
            throw new IllegalStateException("listener errors: " + result.listenerErrors());
        }
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String number(double d) {
        return Double.isFinite(d) ? String.format(Locale.ROOT, "%.6f", d) : "null";
    }

    private static String describe(Set<ClientInventory.Reach> reaches) {
        List<String> out = new ArrayList<>();
        for (ClientInventory.Reach reach : reaches) {
            out.add(!reach.known() ? "?" : reach.range() == null ? "none" : reach.range().maxRange() + "+" + reach.range().hitboxMargin());
        }
        out.sort(null);
        return String.join(",", out);
    }
}
