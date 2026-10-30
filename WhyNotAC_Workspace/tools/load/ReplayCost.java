import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.evidence.Margins;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.world.Replicas;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The analysis cost of recorded sessions, without the network or the server: every recording of a directory (a load
 * test's, one per bot) is read into memory once, then replayed ROUNDS times into fresh replicas and every check, as the
 * plugin wires them, on one thread. Prints the thread's CPU time per client tick of the last rounds (the first warm the
 * JIT), the evidence the checks gave (an honest session gives none) and how close the rest came to each limit
 * ({@code EvidenceSink.margin}). Deterministic enough to compare two builds of the same code path on the same
 * recordings, unlike a load test.
 *
 * <p>usage: java -cp WhyNotAC.jar ReplayCost.java DIR [ROUNDS] [MAX_RECORDINGS] [replicas|all]
 */
public final class ReplayCost {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int max = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        boolean checks = args.length <= 3 || !args[3].equals("replicas");
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(p -> p.toString().endsWith(".wnrec")).sorted().limit(max).toList();
        }
        List<List<TimelineEvent>> sessions = new ArrayList<>();
        long ticks = 0;
        for (Path f : files) {
            List<TimelineEvent> events = new ArrayList<>();
            for (TimelineEvent e : RecordingReader.open(f)) {
                events.add(e);
                if (e instanceof TimelineEvent.Inbound in && in.packet() instanceof ServerboundPacket.ClientTickEnd) {
                    ticks++;
                }
            }
            sessions.add(events);
        }
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] perRound = new long[rounds];
        List<Evidence> given = List.of();
        Margins margins = new Margins();
        for (int round = 0; round < rounds; round++) {
            List<Evidence> found = new ArrayList<>();
            Margins roundMargins = new Margins();
            margins = roundMargins;
            EvidenceSink sink = new EvidenceSink() {
                @Override
                public void accept(PlayerSession session, Evidence e) {
                    found.add(e);
                }

                @Override
                public void rejected(PlayerSession session, String check, Admissibility admissibility) {
                }

                @Override
                public void margin(PlayerSession session, String check, double slack) {
                    roundMargins.add(check, slack);
                }
            };
            SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (session, event, error) -> {
                throw new IllegalStateException(event + ": " + error, error);
            });
            Replicas replicas = Replicas.vanilla();
            replicas.listeners().forEach(registry::addListener);
            if (checks) {
                Checks.all(sink, replicas).forEach(registry::addListener);
            }
            long start = bean.getCurrentThreadCpuTime();
            int n = 0;
            for (List<TimelineEvent> events : sessions) {
                PlayerSession session = registry.openReplay("cost:" + (n++));
                TimelineEvent last = null;
                for (TimelineEvent e : events) {
                    session.deliver(e);
                    last = e;
                }
                if (!session.isClosed() && last != null) {
                    session.deliver(new TimelineEvent.Disconnected(last.sequence() + 1, last.nanoTime()));
                }
            }
            perRound[round] = bean.getCurrentThreadCpuTime() - start;
            given = found;
        }
        long best = Long.MAX_VALUE;
        long sum = 0;
        int counted = 0;
        for (int round = Math.min(2, rounds - 1); round < rounds; round++) {
            best = Math.min(best, perRound[round]);
            sum += perRound[round];
            counted++;
        }
        Map<String, Integer> byCheck = new TreeMap<>();
        for (Evidence e : given) {
            byCheck.merge(e.check(), 1, Integer::sum);
        }
        System.out.printf("{\"recordings\":%d,\"clientTicks\":%d,\"rounds\":%d,\"checks\":%b,\"bestNsPerTick\":%.1f,\"meanNsPerTick\":%.1f,"
                + "\"evidence\":%d,\"byCheck\":\"%s\",\"margins\":\"%s\"}%n", files.size(), ticks, rounds, checks, (double) best / ticks, (double) sum / counted / ticks,
                given.size(), byCheck, margins.snapshot());
    }
}
