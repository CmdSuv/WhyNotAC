package dev.whynotac.recording.cli;

import dev.whynotac.math.stats.RunningMoments;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.recording.read.RecordingReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Offline inspection of recordings:
 * <pre>
 *   java -jar WhyNotAC.jar inspect &lt;file.wnrec&gt;   JSON summary (integrity report, counts, latency, anomalies)
 *   java -jar WhyNotAC.jar dump    &lt;file.wnrec&gt;   one JSON object per event, floats with raw bits
 * </pre>
 * Exit code 0 if the recording is complete, 2 if it was read but is incomplete or damaged, 1 on usage
 * or I/O errors.
 */
public final class RecordingTool {

    private RecordingTool() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2 || !(args[0].equals("inspect") || args[0].equals("dump"))) {
            err.println("usage: inspect|dump <recording.wnrec>");
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
        return summary;
    }

    private static String name(ServerboundPacket packet) {
        return packet instanceof ServerboundPacket.Unmodeled u ? u.type() : packet.getClass().getSimpleName();
    }

    private static String name(ClientboundPacket packet) {
        return packet instanceof ClientboundPacket.Unmodeled u ? u.type() : packet.getClass().getSimpleName();
    }
}
