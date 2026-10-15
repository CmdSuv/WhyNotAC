package dev.whynotac.recording.cli;

import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.format.RecordingFormat;
import dev.whynotac.recording.format.RecordingHeader;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.write.Pseudonymizer;
import dev.whynotac.recording.write.RecordingScrubber;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Offline inspection of recordings, and data-subject requests (KVKK / GDPR):
 * <pre>
 *   java -jar WhyNotAC.jar inspect &lt;file.wnrec&gt;   JSON summary (integrity report, counts, latency, anomalies)
 *   java -jar WhyNotAC.jar dump    &lt;file.wnrec&gt;   one JSON object per event, floats with raw bits
 *   java -jar WhyNotAC.jar find    &lt;recordings-dir&gt; &lt;secret-key&gt; &lt;player-uuid&gt;          recordings of one player
 *   java -jar WhyNotAC.jar forget  &lt;recordings-dir&gt; &lt;secret-key&gt; &lt;player-uuid&gt; [--yes] delete them
 * </pre>
 * {@code find}/{@code forget} turn the real UUID into the pseudonym with the server's secret and
 * compare it with each file's header only; no recording content is decoded. Without {@code --yes},
 * {@code forget} only lists what it would delete. Run it while the player is offline.
 *
 * <p>Exit codes: {@code inspect}/{@code dump} 0 if the recording is complete, 2 if it was read but is
 * incomplete or damaged; {@code find}/{@code forget} 0 on success, 2 if some files could not be read
 * (they are listed for manual review); 1 on usage or I/O errors.
 */
public final class RecordingTool {

    private RecordingTool() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length >= 4 && (args[0].equals("find") || args[0].equals("forget"))) {
            return subjectRequest(args, out, err);
        }
        if (args.length != 2 || !(args[0].equals("inspect") || args[0].equals("dump"))) {
            err.println("usage: inspect|dump <recording.wnrec>");
            err.println("       find <recordings-dir> <secret-key> <player-uuid>");
            err.println("       forget <recordings-dir> <secret-key> <player-uuid> [--yes]");
            return 1;
        }
        RecordingReader reader;
        try {
            reader = RecordingReader.open(Path.of(args[1]));
        } catch (IOException | RuntimeException e) {
            err.println("cannot read " + args[1] + ": " + e.getMessage());
            return 1;
        }
        if (args[0].equals("dump")) {
            for (TimelineEvent event : reader) {
                out.println(Json.value(event));
            }
        } else {
            out.println(Json.value(summarise(reader)));
        }
        return reader.report().complete() ? 0 : 2;
    }

    static Map<String, Object> summarise(RecordingReader reader) {
        Map<String, Long> kinds = new TreeMap<>();
        Map<String, Long> inbound = new TreeMap<>();
        Map<String, Long> outbound = new TreeMap<>();
        List<Object> anomalies = new ArrayList<>();
        List<Object> observations = new ArrayList<>();
        Map<String, Long> divergences = new TreeMap<>();
        RunningMoments rtt = new RunningMoments();
        long skipped = 0;
        long clientTicks = 0;
        long first = Long.MIN_VALUE;
        long last = Long.MIN_VALUE;
        for (TimelineEvent event : reader) {
            kinds.merge(event.getClass().getSimpleName(), 1L, Long::sum);
            if (first == Long.MIN_VALUE) {
                first = event.nanoTime();
            }
            last = event.nanoTime();
            switch (event) {
                case TimelineEvent.Inbound in -> {
                    inbound.merge(name(in.packet()), 1L, Long::sum);
                    if (in.packet() instanceof ServerboundPacket.ClientTickEnd) {
                        clientTicks++;
                    }
                }
                case TimelineEvent.Outbound o -> outbound.merge(name(o.packet()), 1L, Long::sum);
                case TimelineEvent.Acknowledged ack -> {
                    rtt.add(ack.roundTripNanos() / 1.0e6);
                    skipped += ack.skipped();
                }
                case TimelineEvent.Anomaly anomaly -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("sequence", anomaly.sequence());
                    entry.put("kind", anomaly.kind());
                    entry.put("detail", anomaly.detail());
                    anomalies.add(entry);
                }
                case TimelineEvent.Observation observation -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("sequence", observation.sequence());
                    entry.put("inbound", observation.inbound());
                    entry.put("outbound", observation.outbound());
                    entry.put("client_protocol", observation.clientProtocol());
                    entry.put("detail", observation.detail());
                    observations.add(entry);
                }
                case TimelineEvent.InboundDivergence d -> divergences.merge("inbound " + d.kind() + " " + name(d.packet()), 1L, Long::sum);
                case TimelineEvent.OutboundDivergence d -> divergences.merge("outbound " + d.kind() + " " + name(d.packet()), 1L, Long::sum);
                default -> { }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("header", reader.header());
        summary.put("report", reader.report());
        summary.put("complete", reader.report().complete());
        summary.put("duration_seconds", first == Long.MIN_VALUE ? 0.0 : (last - first) / 1.0e9);
        summary.put("events_by_kind", kinds);
        summary.put("inbound_packets", inbound);
        summary.put("outbound_packets", outbound);
        summary.put("client_ticks", clientTicks);
        Map<String, Object> transactions = new LinkedHashMap<>();
        transactions.put("acknowledged", rtt.count());
        transactions.put("skipped", skipped);
        transactions.put("rtt_ms_min", rtt.min());
        transactions.put("rtt_ms_mean", rtt.mean());
        transactions.put("rtt_ms_max", rtt.max());
        summary.put("transactions", transactions);
        summary.put("anomalies", anomalies);
        summary.put("observation", observations);
        summary.put("divergences", divergences);
        return summary;
    }

    private static int subjectRequest(String[] args, PrintStream out, PrintStream err) {
        boolean delete = args[0].equals("forget") && args.length == 5 && args[4].equals("--yes");
        if (args.length > 5 || args.length == 5 && !delete) {
            err.println("usage: find|forget <recordings-dir> <secret-key> <player-uuid> [--yes]");
            return 1;
        }
        Path directory = Path.of(args[1]);
        UUID player;
        Pseudonymizer pseudonymizer;
        try {
            player = UUID.fromString(args[3]);
            pseudonymizer = new Pseudonymizer(Files.readAllBytes(Path.of(args[2])));
        } catch (IOException | IllegalArgumentException e) {
            err.println("cannot use secret or uuid: " + e.getMessage());
            return 1;
        }
        UUID subject = pseudonymizer.pseudonym(player);
        List<Object> matches = new ArrayList<>();
        List<Object> mentions = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        long deleted = 0;
        long scrubbed = 0;
        try (Stream<Path> walk = Files.walk(directory, 3)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(RecordingFormat.FILE_SUFFIX) && !fileName.endsWith(RecordingFormat.PARTIAL_SUFFIX)) {
                    continue;
                }
                RecordingHeader header;
                try {
                    header = RecordingHeader.decode(readPrefix(file, 4096)).header();
                } catch (IOException | RuntimeException e) {
                    unreadable.add(file.toString());
                    continue;
                }
                if (!header.subject().equals(subject)) {
                    // Another player's recording: the player may appear in it by pseudonym (its player list, entities near it).
                    long count;
                    try {
                        count = RecordingScrubber.mentions(file, subject);
                    } catch (IOException | RuntimeException e) {
                        unreadable.add(file.toString());
                        continue;
                    }
                    if (count == 0) {
                        continue;
                    }
                    Map<String, Object> mention = new LinkedHashMap<>();
                    mention.put("file", file.toString());
                    mention.put("occurrences", count);
                    boolean finished = fileName.endsWith(RecordingFormat.FILE_SUFFIX);
                    mention.put("complete_file", finished);
                    if (delete) {
                        if (finished) {
                            try {
                                RecordingScrubber.scrub(file, subject);
                                mention.put("scrubbed", true);
                                scrubbed++;
                            } catch (IOException | RuntimeException e) {
                                mention.put("scrubbed", false);
                                unreadable.add(file.toString());
                            }
                        } else {
                            // still being written: run forget again once that session has ended
                            mention.put("scrubbed", false);
                            unreadable.add(file.toString());
                        }
                    }
                    mentions.add(mention);
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("file", file.toString());
                entry.put("bytes", Files.size(file));
                entry.put("started_utc", java.time.Instant.ofEpochMilli(header.startEpochMillis()).toString());
                entry.put("complete_file", fileName.endsWith(RecordingFormat.FILE_SUFFIX));
                if (delete) {
                    Files.delete(file);
                    deleted++;
                    entry.put("deleted", true);
                }
                matches.add(entry);
            }
        } catch (IOException e) {
            err.println("cannot scan " + directory + ": " + e.getMessage());
            return 1;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", args[0]);
        result.put("pseudonym", subject);
        result.put("recordings", matches);
        result.put("mentioned_in", mentions);
        result.put("deleted", deleted);
        result.put("scrubbed", scrubbed);
        result.put("dry_run", args[0].equals("forget") && !delete);
        result.put("unreadable_for_manual_review", unreadable);
        out.println(Json.value(result));
        return unreadable.isEmpty() ? 0 : 2;
    }

    private static byte[] readPrefix(Path file, int max) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(max);
        }
    }

    private static String name(ServerboundPacket packet) {
        return packet instanceof ServerboundPacket.Unmodeled u ? u.type() : packet.getClass().getSimpleName();
    }

    private static String name(ClientboundPacket packet) {
        return packet instanceof ClientboundPacket.Unmodeled u ? u.type() : packet.getClass().getSimpleName();
    }
}
