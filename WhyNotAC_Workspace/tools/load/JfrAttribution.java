import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Attributes a JFR recording's CPU samples (jdk.ExecutionSample) to WhyNotAC, per thread group.
 *
 * <p>A sample is WhyNotAC's when, walking down from the executing frame, a WhyNotAC frame comes before
 * any Netty pipeline dispatch frame ({@code AbstractChannelHandlerContext}). JDK or Netty code that
 * WhyNotAC calls counts as WhyNotAC's; the handlers WhyNotAC's pass-through taps forward to (vanilla's
 * decoders, other plugins) do not, although WhyNotAC's frame is below them on the stack.
 * {@code anywhere} counts every sample with a WhyNotAC frame at any depth: an upper bound.
 *
 * <p>Usage: {@code java JfrAttribution.java <recording.jfr>}; prints one JSON line.
 */
public final class JfrAttribution {

    private static final String PACKAGE = "dev.whynotac.";
    private static final String DISPATCH = "io.netty.channel.AbstractChannelHandlerContext";

    public static void main(String[] args) throws IOException {
        Map<String, long[]> byGroup = new TreeMap<>(); // group -> {samples, whynotac, anywhere}
        long total = 0;
        long ours = 0;
        long anywhere = 0;
        long truncated = 0;
        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    continue;
                }
                String thread = event.getThread("sampledThread") == null ? "?" : event.getThread("sampledThread").getJavaName();
                String group = thread == null ? "?" : thread.replaceAll("[0-9]+", "#");
                boolean whynotac = false;
                boolean decided = false;
                boolean onStack = false;
                RecordedStackTrace stack = event.getStackTrace();
                if (stack != null) {
                    truncated += stack.isTruncated() ? 1 : 0;
                    for (RecordedFrame frame : stack.getFrames()) {
                        if (!frame.isJavaFrame()) {
                            continue;
                        }
                        String type = frame.getMethod().getType().getName();
                        if (type.startsWith(PACKAGE)) {
                            onStack = true;
                            if (!decided) {
                                whynotac = true;
                                decided = true;
                            }
                        } else if (type.equals(DISPATCH)) {
                            decided = true;
                        }
                    }
                }
                long[] counts = byGroup.computeIfAbsent(group, g -> new long[3]);
                counts[0]++;
                total++;
                if (whynotac) {
                    counts[1]++;
                    ours++;
                }
                if (onStack) {
                    counts[2]++;
                    anywhere++;
                }
            }
        }
        StringBuilder json = new StringBuilder("{\"samples\":").append(total).append(",\"whynotacSamples\":").append(ours)
                .append(",\"whynotacShare\":").append(total == 0 ? 0 : (double) ours / total)
                .append(",\"anywhereSamples\":").append(anywhere).append(",\"truncatedStacks\":").append(truncated).append(",\"threads\":{");
        boolean first = true;
        for (Map.Entry<String, long[]> entry : byGroup.entrySet()) {
            json.append(first ? "" : ",").append('"').append(entry.getKey().replace("\"", "'")).append("\":{\"samples\":")
                    .append(entry.getValue()[0]).append(",\"whynotac\":").append(entry.getValue()[1])
                    .append(",\"anywhere\":").append(entry.getValue()[2]).append('}');
            first = false;
        }
        System.out.println(json.append("}}"));
    }
}
