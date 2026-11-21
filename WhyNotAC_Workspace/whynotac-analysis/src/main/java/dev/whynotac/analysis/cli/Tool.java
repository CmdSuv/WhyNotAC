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
        if (args.length == 2 && args[0].equals("explain")) {
            System.exit(explain(Path.of(args[1]), System.out, System.err));
        }
        if (args.length == 2 && args[0].equals("audit")) {
            System.exit(audit(Path.of(args[1]), System.out, System.err));
        }
        if (args.length >= 1 && args[0].equals("benchmark")) {
            try {
                BenchmarkRunner.runCli(args, System.out, System.err);
                return;
            } catch (IOException e) {
                System.err.println("benchmark failed: " + e.getMessage());
                System.exit(1);
            }
        }
        RecordingTool.main(args);
    }

    static int explain(Path dossier, PrintStream out, PrintStream err) {
        String json;
        try {
            json = Files.readString(dossier, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("cannot read " + dossier + ": " + e.getMessage());
            return 1;
        }
        String id = field(json, "id");
        String player = field(json, "playerName");
        String identity = field(json, "identity");
        String check = field(json, "check");
        String evidenceClass = field(json, "evidenceClass");
        String lifecycle = field(json, "lifecycle");
        String summary = field(json, "summary");
        String fingerprint = field(json, "fingerprint");
        if (check == null || evidenceClass == null) {
            err.println("unrecognized dossier JSON: " + dossier);
            return 1;
        }
        String meaning = switch (evidenceClass) {
            case "P-A" -> "Advantage proof: impossible under vanilla rules with all uncertainties bounded; eligible for corroboration/auto-enforcement when ARMED and authenticated.";
            case "P-M" -> "Modification proof: client behavior not produced by vanilla client; indicates client modification (soft neutralization/kick only when ARMED, never automatic ban).";
            case "D" -> "Design statistic: sequential experimental e-value; triggers alerts and human review only.";
            case "S" -> "Nominal statistic: baseline/behavioral deviation signal; alerts and review priority only (never punitive).";
            default -> "Context metadata: attached for staff review only.";
        };
        String dependencyGroup = field(json, "dependencyGroup");
        String authorizedAction = field(json, "authorizedAction");
        String verificationStatus = field(json, "verificationStatus");
        String aiPriorityScore = numberField(json, "aiPriorityScore");
        String aiIntrinsicRisk = numberField(json, "aiIntrinsicRisk");
        String aiDominantSignal = field(json, "aiDominantSignal");
        String aiBaselineRmsZ = numberField(json, "aiBaselineRmsZ");
        String aiTopFeature = field(json, "aiTopFeature");
        String aiProbeWealth = numberField(json, "aiProbeWealth");
        out.println("WhyNotAC Case Dossier Explanation");
        out.println("  Case ID       : " + (id == null ? "unknown" : id));
        out.println("  Player        : " + (player == null ? "unknown" : player) + " (" + (identity == null ? "UNAUTHENTICATED" : identity) + ")");
        out.println("  Check         : " + check + " [" + evidenceClass + ", " + (lifecycle == null ? "SHADOW" : lifecycle) + "]");
        if (dependencyGroup != null || authorizedAction != null || verificationStatus != null) {
            out.println("  Verdict       : group=" + (dependencyGroup == null ? "?" : dependencyGroup)
                    + ", action=" + (authorizedAction == null ? "?" : authorizedAction)
                    + ", secondVerify=" + (verificationStatus == null ? "?" : verificationStatus));
        }
        out.println("  Summary       : " + (summary == null ? "" : summary));
        out.println("  Policy Meaning: " + meaning);
        if (aiPriorityScore != null) {
            out.println("  AI Triage     : priority=" + aiPriorityScore + "/100"
                    + (aiIntrinsicRisk != null ? ", intrinsicRisk=" + aiIntrinsicRisk : "")
                    + (aiDominantSignal != null && !aiDominantSignal.isEmpty() ? ", signal=" + aiDominantSignal : "")
                    + (aiBaselineRmsZ != null ? ", baselineRmsZ=" + aiBaselineRmsZ : "")
                    + (aiTopFeature != null && !aiTopFeature.isEmpty() ? ", topFeature=" + aiTopFeature : "")
                    + (aiProbeWealth != null ? ", probeWealth=" + aiProbeWealth : ""));
        }
        if (fingerprint != null) {
            out.println("  Fingerprint   : " + fingerprint);
        }
        out.println("  Re-verify cmd : java -jar WhyNotAC.jar verify " + dossier);
        return 0;
    }

    private static String numberField(String json, String name) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static int audit(Path storageDir, PrintStream out, PrintStream err) {
        try {
            Class<?> toolClass = Class.forName("dev.whynotac.storage.AuditLedgerTool");
            Object result = toolClass.getMethod("verifyLedger", Path.class, PrintStream.class, PrintStream.class)
                    .invoke(null, storageDir, out, err);
            return (Integer) result;
        } catch (ClassNotFoundException e) {
            err.println("audit ledger verifier requires the full WhyNotAC plugin jar (whynotac-storage missing)");
            return 1;
        } catch (ReflectiveOperationException e) {
            err.println("cannot run audit verifier: " + e.getMessage());
            return 1;
        }
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
