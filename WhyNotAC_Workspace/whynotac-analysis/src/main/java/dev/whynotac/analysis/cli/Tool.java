package dev.whynotac.analysis.cli;

import dev.whynotac.analysis.checks.Checks;
import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.recording.cli.RecordingTool;
import dev.whynotac.recording.read.RecordingReader;
import dev.whynotac.recording.replay.Replayer;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plugin jar's command-line entry point. {@code verify <dossier.json>} re-runs the dossier's check
 * over the session's recording, independently of the server that produced it, and requires the same
 * evidence (fingerprint) to come out. Every other command is the recording tool's.
 */
public final class Tool {

    private Tool() {}

    public static void main(String[] args) {
        if (args.length == 2 && args[0].equals("verify")) {
            System.exit(verify(Path.of(args[1]), System.out, System.err));
        }
        RecordingTool.main(args);
    }

    static int verify(Path dossier, PrintStream out, PrintStream err) {
        String json;
        try {
            json = Files.readString(dossier, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("cannot read " + dossier + ": " + e.getMessage());
            return 1;
        }
        String recording = field(json, "recording");
        String fingerprint = field(json, "fingerprint");
        String check = field(json, "check");
        if (recording == null || fingerprint == null || check == null || Checks.version(check) < 0) {
            err.println("dossier has no recording, no fingerprint, or a check this tool cannot run: " + check);
            return 1;
        }
        String version = number(json, "checkVersion");
        if (version != null && !version.equals(Integer.toString(Checks.version(check)))) {
            err.println("the dossier was made by " + check + " version " + version + "; this tool runs version " + Checks.version(check)
                    + " (use the WhyNotAC build that opened the case)");
            return 1;
        }
        // The recording is named relative to the dossier (a copied plugin folder still verifies), else absolutely.
        Path file = dossier.toAbsolutePath().getParent().resolve(recording).normalize();
        String absolute = field(json, "recordingAbsolute");
        if (!Files.exists(file) && absolute != null) {
            file = Path.of(absolute);
        }
        List<Evidence> found = new ArrayList<>();
        try {
            RecordingReader reader = RecordingReader.open(file);
            Replayer.replay(reader, List.of(Checks.create(check, new EvidenceSink() {
                @Override
                public void accept(PlayerSession session, Evidence evidence) {
                    found.add(evidence);
                }

                @Override
                public void rejected(PlayerSession session, String id, Admissibility admissibility) {}
            })));
        } catch (IOException | RuntimeException e) {
            err.println("cannot replay " + file + ": " + e.getMessage());
            return 1;
        }
        boolean verified = found.stream().anyMatch(e -> e.fingerprint().equals(fingerprint));
        out.println("{\"verified\":" + verified + ",\"recording\":\"" + escape(file.toString()) + "\",\"expected\":\"" + escape(fingerprint)
                + "\",\"evidenceInRecording\":" + found.size() + "}");
        return verified ? 0 : 3;
    }

    /** A string field of the dossier (written by WhyNotAC, so a flat object with escaped strings). */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : null;
    }

    /** A numeric field of the dossier, as text. */
    static String number(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
