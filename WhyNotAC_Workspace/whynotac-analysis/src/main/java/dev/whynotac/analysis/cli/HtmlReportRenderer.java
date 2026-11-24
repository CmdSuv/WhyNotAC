package dev.whynotac.analysis.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generates 100% offline, self-contained single-file HTML5 forensic case reports ({@code WhyNotAC.jar report}).
 *
 * <p>Zero external CDNs, fonts, or scripts. All user-controlled strings are strictly HTML-escaped and
 * embedded JSON payloads escape {@code <}, {@code >}, {@code &}, {@code U+2028}, and {@code U+2029}
 * to eliminate DOM and {@code </script>} XSS vectors.
 */
public final class HtmlReportRenderer {

    public record CaseRecord(
            String id,
            String created,
            String check,
            int checkVersion,
            String evidenceClass,
            String lifecycle,
            String playerName,
            String playerId,
            String identity,
            long fromSequence,
            long toSequence,
            String summary,
            String fingerprint,
            String recording,
            String dependencyGroup,
            String authorizedAction,
            String mitigation,
            String verificationStatus,
            String certificateId,
            String certificateSha256,
            Double aiPriorityScore,
            Double aiIntrinsicRisk,
            String aiDominantSignal,
            Double aiBaselineRmsZ,
            String aiTopFeature,
            Double aiProbeWealth,
            String whynotacVersion,
            Map<String, String> details) {}

    private HtmlReportRenderer() {}

    /** CLI entry point: {@code java -jar WhyNotAC.jar report <dossier.json|cases-dir> <output.html>}. */
    public static int runCli(Path input, Path output, PrintStream out, PrintStream err) {
        try {
            List<CaseRecord> cases = loadCases(input);
            if (cases.isEmpty()) {
                err.println("No valid WhyNotAC case dossiers found at: " + input);
                return 1;
            }
            String html = renderHtml(cases, input.getFileName() == null ? input.toString() : input.getFileName().toString());
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, html, StandardCharsets.UTF_8);
            out.println("WhyNotAC forensic HTML5 report written: " + output.toAbsolutePath() + " (" + cases.size() + " case(s))");
            return 0;
        } catch (IOException | IllegalArgumentException ex) {
            err.println("report failed: " + ex.getMessage());
            return 1;
        }
    }

    /** Convenience API used by {@code /whynotac report <caseId>}. */
    public static int renderPath(Path input, Path output) throws IOException {
        List<CaseRecord> cases = loadCases(input);
        if (cases.isEmpty()) {
            throw new IOException("No valid WhyNotAC case dossiers found at " + input);
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, renderHtml(cases, input.getFileName().toString()), StandardCharsets.UTF_8);
        return cases.size();
    }

    public static List<CaseRecord> loadCases(Path input) throws IOException {
        List<CaseRecord> out = new ArrayList<>();
        if (Files.isRegularFile(input)) {
            CaseRecord parsed = parseDossier(Files.readString(input, StandardCharsets.UTF_8));
            if (parsed != null) {
                out.add(parsed);
            }
        } else if (Files.isDirectory(input)) {
            List<Path> jsonFiles;
            try (Stream<Path> s = Files.list(input)) {
                jsonFiles = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
            }
            for (Path file : jsonFiles) {
                try {
                    CaseRecord parsed = parseDossier(Files.readString(file, StandardCharsets.UTF_8));
                    if (parsed != null) {
                        out.add(parsed);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Skip non-dossier JSON files in directory
                }
            }
            out.sort(Comparator.comparing(CaseRecord::created, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(CaseRecord::id));
        } else {
            throw new IOException("Input path does not exist: " + input);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static CaseRecord parseDossier(String json) {
        Object parsed = new SimpleJson(json).parseRoot();
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Dossier is not a JSON object");
        }
        Map<String, Object> obj = (Map<String, Object>) raw;
        String check = str(obj, "check");
        String evidenceClass = str(obj, "evidenceClass");
        if (check == null || evidenceClass == null) {
            return null;
        }
        Map<String, String> details = new LinkedHashMap<>();
        Object detObj = obj.get("details");
        if (detObj instanceof Map<?, ?> dm) {
            dm.forEach((k, v) -> details.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return new CaseRecord(
                nonNull(str(obj, "id"), "unknown"),
                nonNull(str(obj, "created"), ""),
                check,
                (int) numLong(obj, "checkVersion", 1L),
                evidenceClass,
                nonNull(str(obj, "lifecycle"), "SHADOW"),
                nonNull(str(obj, "playerName"), "unknown"),
                nonNull(str(obj, "playerId"), ""),
                nonNull(str(obj, "identity"), "UNAUTHENTICATED"),
                numLong(obj, "fromSequence", 0L),
                numLong(obj, "toSequence", 0L),
                nonNull(str(obj, "summary"), ""),
                nonNull(str(obj, "fingerprint"), ""),
                nonNull(str(obj, "recording"), ""),
                nonNull(str(obj, "dependencyGroup"), ""),
                nonNull(str(obj, "authorizedAction"), "ALERT"),
                nonNull(str(obj, "mitigation"), "NONE"),
                nonNull(str(obj, "verificationStatus"), "NOT_NEEDED"),
                nonNull(str(obj, "certificateId"), ""),
                nonNull(str(obj, "certificateSha256"), ""),
                numDoubleOrNull(obj, "aiPriorityScore"),
                numDoubleOrNull(obj, "aiIntrinsicRisk"),
                nonNull(str(obj, "aiDominantSignal"), ""),
                numDoubleOrNull(obj, "aiBaselineRmsZ"),
                nonNull(str(obj, "aiTopFeature"), ""),
                numDoubleOrNull(obj, "aiProbeWealth"),
                nonNull(str(obj, "whynotacVersion"), ""),
                details);
    }

    public static String renderHtml(List<CaseRecord> cases, String titleLabel) {
        long paCount = cases.stream().filter(c -> "P-A".equals(c.evidenceClass())).count();
        long pmCount = cases.stream().filter(c -> "P-M".equals(c.evidenceClass())).count();
        long sdCount = cases.stream().filter(c -> "S".equals(c.evidenceClass()) || "D".equals(c.evidenceClass())).count();
        long verifiedCount = cases.stream().filter(c -> "VERIFIED".equals(c.verificationStatus())).count();
        Set<String> uniquePlayers = new LinkedHashSet<>();
        Set<String> uniqueChecks = new LinkedHashSet<>();
        for (CaseRecord c : cases) {
            if (!c.playerName().isEmpty()) {
                uniquePlayers.add(c.playerName());
            }
            uniqueChecks.add(c.check());
        }

        StringBuilder html = new StringBuilder(16_384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\" data-theme=\"dark\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:;\">\n")
                .append("<title>WhyNotAC Forensic Report — ").append(escapeHtml(titleLabel)).append("</title>\n")
                .append("<style>\n")
                .append(":root{--bg:#0b1020;--panel:#131b31;--panel2:#192340;--text:#e6edf7;--muted:#93a4c3;--border:#273459;--accent:#38bdf8;--pa:#ef4444;--pm:#f59e0b;--d:#a855f7;--s:#38bdf8;--ok:#22c55e}\n")
                .append("[data-theme=light]{--bg:#f4f7fb;--panel:#ffffff;--panel2:#eef2f9;--text:#0f172a;--muted:#475569;--border:#cbd5e1;--accent:#0284c7}\n")
                .append("*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--text);line-height:1.5}\n")
                .append(".wrap{max-width:1280px;margin:0 auto;padding:24px}\n")
                .append("header{display:flex;flex-wrap:wrap;gap:12px;justify-content:space-between;align-items:center;border-bottom:1px solid var(--border);padding-bottom:16px;margin-bottom:20px}\n")
                .append("h1{margin:0;font-size:1.5rem} .sub{color:var(--muted);font-size:.9rem}\n")
                .append(".btn{background:var(--panel2);color:var(--text);border:1px solid var(--border);padding:7px 12px;border-radius:8px;cursor:pointer;font-size:.85rem}\n")
                .append(".btn:hover{border-color:var(--accent)}\n")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin-bottom:20px}\n")
                .append(".kpi{background:var(--panel);border:1px solid var(--border);border-radius:12px;padding:14px}\n")
                .append(".kpi .lbl{color:var(--muted);font-size:.78rem;text-transform:uppercase;letter-spacing:.05em}\n")
                .append(".kpi .val{font-size:1.6rem;font-weight:700;margin-top:4px}\n")
                .append(".card{background:var(--panel);border:1px solid var(--border);border-radius:12px;padding:16px;margin-bottom:16px}\n")
                .append(".controls{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:16px}\n")
                .append(".controls input,.controls select{background:var(--panel);color:var(--text);border:1px solid var(--border);padding:8px 10px;border-radius:8px;font-size:.9rem}\n")
                .append(".badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:.75rem;font-weight:700;margin-right:6px}\n")
                .append(".b-pa{background:rgba(239,68,68,.18);color:#fca5a5;border:1px solid var(--pa)}\n")
                .append(".b-pm{background:rgba(245,158,11,.18);color:#fcd34d;border:1px solid var(--pm)}\n")
                .append(".b-d{background:rgba(168,85,247,.18);color:#d8b4fe;border:1px solid var(--d)}\n")
                .append(".b-s{background:rgba(56,189,248,.18);color:#7dd3fc;border:1px solid var(--s)}\n")
                .append(".b-ok{background:rgba(34,197,94,.18);color:#86efac;border:1px solid var(--ok)}\n")
                .append(".b-muted{background:var(--panel2);color:var(--muted);border:1px solid var(--border)}\n")
                .append(".case-head{display:flex;flex-wrap:wrap;justify-content:space-between;gap:8px;align-items:center;margin-bottom:8px}\n")
                .append(".mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.82rem;word-break:break-all}\n")
                .append(".bars{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:10px;margin-top:10px}\n")
                .append(".bar-wrap{background:var(--panel2);border-radius:8px;padding:8px}\n")
                .append(".bar-track{height:8px;background:var(--border);border-radius:4px;overflow:hidden;margin-top:4px}\n")
                .append(".bar-fill{height:100%;background:linear-gradient(90deg,var(--accent),var(--d))}\n")
                .append("table{width:100%;border-collapse:collapse;margin-top:10px;font-size:.84rem}\n")
                .append("th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--border);vertical-align:top}\n")
                .append("th{color:var(--muted);width:220px}\n")
                .append("svg{width:100%;height:auto;display:block}\n")
                .append("</style>\n</head>\n<body>\n<div class=\"wrap\">\n");

        html.append("<header><div><h1>WhyNotAC Forensic Case Report</h1>")
                .append("<div class=\"sub\">Source: ").append(escapeHtml(titleLabel))
                .append(" &bull; Generated: ").append(escapeHtml(Instant.now().toString()))
                .append(" &bull; Offline self-contained HTML5</div></div>")
                .append("<div style=\"display:flex;gap:8px;flex-wrap:wrap\">")
                .append("<button class=\"btn\" id=\"btnTheme\" type=\"button\">Toggle Theme</button>")
                .append("<button class=\"btn\" id=\"btnJson\" type=\"button\">Export JSON</button>")
                .append("<button class=\"btn\" id=\"btnCsv\" type=\"button\">Export CSV</button>")
                .append("</div></header>\n");

        html.append("<section class=\"grid\">")
                .append(kpi("Total Cases", Long.toString(cases.size())))
                .append(kpi("P-A Proofs", Long.toString(paCount)))
                .append(kpi("P-M Proofs", Long.toString(pmCount)))
                .append(kpi("S / D Signals", Long.toString(sdCount)))
                .append(kpi("SecondVerifier Verified", Long.toString(verifiedCount)))
                .append(kpi("Unique Players", Integer.toString(uniquePlayers.size())))
                .append("</section>\n");

        html.append("<section class=\"card\"><h2 style=\"margin:0 0 10px;font-size:1.05rem\">Event Sequence Timeline</h2>")
                .append(renderTimelineSvg(cases))
                .append("</section>\n");

        html.append("<section class=\"controls\">")
                .append("<input id=\"qSearch\" type=\"search\" placeholder=\"Search player, case ID, check, summary...\" style=\"flex:1;min-width:220px\">")
                .append("<select id=\"qClass\"><option value=\"\">All Classes</option><option value=\"P-A\">P-A</option><option value=\"P-M\">P-M</option><option value=\"D\">D</option><option value=\"S\">S</option></select>")
                .append("<select id=\"qCheck\"><option value=\"\">All Checks</option>");
        for (String chk : uniqueChecks) {
            html.append("<option value=\"").append(escapeHtml(chk)).append("\">").append(escapeHtml(chk)).append("</option>");
        }
        html.append("</select></section>\n");

        html.append("<main id=\"caseList\">\n");
        for (int i = 0; i < cases.size(); i++) {
            CaseRecord c = cases.get(i);
            html.append("<article class=\"card case-item\" data-idx=\"").append(i)
                    .append("\" data-class=\"").append(escapeHtml(c.evidenceClass()))
                    .append("\" data-check=\"").append(escapeHtml(c.check()))
                    .append("\">\n");
            html.append("<div class=\"case-head\"><div>")
                    .append("<span class=\"badge ").append(classCss(c.evidenceClass())).append("\">").append(escapeHtml(c.evidenceClass())).append("</span>")
                    .append("<span class=\"badge b-muted\">").append(escapeHtml(c.lifecycle())).append("</span>")
                    .append("<span class=\"badge ").append("VERIFIED".equals(c.verificationStatus()) ? "b-ok" : "b-muted").append("\">2ndVerify: ")
                    .append(escapeHtml(c.verificationStatus())).append("</span>")
                    .append("<strong>").append(escapeHtml(c.check())).append("</strong> <span class=\"sub\">v").append(c.checkVersion()).append("</span>")
                    .append("</div><div class=\"mono sub\">").append(escapeHtml(c.id())).append("</div></div>\n");

            html.append("<div><strong>Player:</strong> ").append(escapeHtml(c.playerName()))
                    .append(" <span class=\"mono sub\">(").append(escapeHtml(c.playerId())).append(", ").append(escapeHtml(c.identity())).append(")</span>")
                    .append(" &bull; <strong>Created:</strong> ").append(escapeHtml(c.created()))
                    .append(" &bull; <strong>Seq:</strong> ").append(c.fromSequence()).append("..").append(c.toSequence())
                    .append("</div>\n");
            html.append("<p style=\"margin:8px 0\">").append(escapeHtml(c.summary())).append("</p>\n");

            html.append("<div class=\"mono sub\"><strong>Certificate SHA-256:</strong> ")
                    .append(c.certificateSha256().isEmpty() ? "n/a" : escapeHtml(c.certificateSha256()))
                    .append(" &bull; <strong>Action:</strong> ").append(escapeHtml(c.authorizedAction()))
                    .append(" / ").append(escapeHtml(c.mitigation()))
                    .append(" &bull; <strong>Group:</strong> ").append(escapeHtml(c.dependencyGroup()))
                    .append("</div>\n");

            if (c.aiPriorityScore() != null || c.aiBaselineRmsZ() != null || c.aiProbeWealth() != null) {
                html.append("<div class=\"bars\">");
                if (c.aiPriorityScore() != null) {
                    html.append(bar("AI Triage Priority", String.format(Locale.ROOT, "%.1f / 100", c.aiPriorityScore()), clampPct(c.aiPriorityScore())));
                }
                if (c.aiIntrinsicRisk() != null) {
                    html.append(bar("Intrinsic Risk (" + nonNull(c.aiDominantSignal(), "none") + ")",
                            String.format(Locale.ROOT, "%.1f / 100", c.aiIntrinsicRisk()), clampPct(c.aiIntrinsicRisk())));
                }
                if (c.aiBaselineRmsZ() != null) {
                    html.append(bar("4x4 Mahalanobis / Baseline RMS-Z (" + nonNull(c.aiTopFeature(), "none") + ")",
                            String.format(Locale.ROOT, "%.3f z", c.aiBaselineRmsZ()), clampPct(c.aiBaselineRmsZ() * 20.0)));
                }
                if (c.aiProbeWealth() != null) {
                    html.append(bar("Design Probe E-Wealth",
                            String.format(Locale.ROOT, "%.3f", c.aiProbeWealth()), clampPct(Math.log10(Math.max(1.0, c.aiProbeWealth())) * 33.3)));
                }
                html.append("</div>\n");
            }

            if (!c.details().isEmpty()) {
                html.append("<table><tbody>");
                for (Map.Entry<String, String> entry : c.details().entrySet()) {
                    html.append("<tr><th>").append(escapeHtml(entry.getKey())).append("</th><td class=\"mono\">")
                            .append(escapeHtml(entry.getValue())).append("</td></tr>");
                }
                html.append("</tbody></table>\n");
            }
            html.append("</article>\n");
        }
        html.append("</main>\n</div>\n");

        html.append("<script>\n")
                .append("const REPORT_CASES = ").append(casesToSafeScriptJson(cases)).append(";\n")
                .append("(function(){\n")
                .append("  const root=document.documentElement;\n")
                .append("  document.getElementById('btnTheme').addEventListener('click',()=>{\n")
                .append("    root.dataset.theme=root.dataset.theme==='dark'?'light':'dark';\n")
                .append("  });\n")
                .append("  const qSearch=document.getElementById('qSearch');\n")
                .append("  const qClass=document.getElementById('qClass');\n")
                .append("  const qCheck=document.getElementById('qCheck');\n")
                .append("  const cards=[...document.querySelectorAll('.case-item')];\n")
                .append("  function applyFilters(){\n")
                .append("    const s=(qSearch.value||'').toLowerCase(), cl=qClass.value, ch=qCheck.value;\n")
                .append("    cards.forEach(el=>{\n")
                .append("      const idx=Number(el.dataset.idx), c=REPORT_CASES[idx];\n")
                .append("      const hay=(c.id+' '+c.playerName+' '+c.check+' '+c.summary+' '+c.fingerprint).toLowerCase();\n")
                .append("      const ok=(!s||hay.includes(s))&&(!cl||c.evidenceClass===cl)&&(!ch||c.check===ch);\n")
                .append("      el.style.display=ok?'':'none';\n")
                .append("    });\n")
                .append("  }\n")
                .append("  qSearch.addEventListener('input',applyFilters);\n")
                .append("  qClass.addEventListener('change',applyFilters);\n")
                .append("  qCheck.addEventListener('change',applyFilters);\n")
                .append("  function dl(name,mime,text){\n")
                .append("    const b=new Blob([text],{type:mime}); const a=document.createElement('a');\n")
                .append("    a.href=URL.createObjectURL(b); a.download=name; a.click(); setTimeout(()=>URL.revokeObjectURL(a.href),1000);\n")
                .append("  }\n")
                .append("  document.getElementById('btnJson').addEventListener('click',()=>dl('whynotac-report.json','application/json',JSON.stringify(REPORT_CASES,null,2)));\n")
                .append("  document.getElementById('btnCsv').addEventListener('click',()=>{\n")
                .append("    const cols=['id','created','playerName','check','evidenceClass','lifecycle','verificationStatus','authorizedAction','summary'];\n")
                .append("    const esc=v=>'\"'+String(v??'').replace(/\"/g,'\"\"')+'\"';\n")
                .append("    const rows=[cols.join(','),...REPORT_CASES.map(c=>cols.map(k=>esc(c[k])).join(','))];\n")
                .append("    dl('whynotac-report.csv','text/csv',rows.join('\\n'));\n")
                .append("  });\n")
                .append("})();\n</script>\n</body>\n</html>\n");
        return html.toString();
    }

    private static String renderTimelineSvg(List<CaseRecord> cases) {
        int width = 1100;
        int height = 110;
        long minSeq = Long.MAX_VALUE;
        long maxSeq = Long.MIN_VALUE;
        for (CaseRecord c : cases) {
            minSeq = Math.min(minSeq, c.fromSequence());
            maxSeq = Math.max(maxSeq, Math.max(c.fromSequence(), c.toSequence()));
        }
        if (minSeq == Long.MAX_VALUE) {
            minSeq = 0;
            maxSeq = 1;
        }
        long span = Math.max(1L, maxSeq - minSeq);
        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" role=\"img\" aria-label=\"Case Sequence Timeline\">")
                .append("<line x1=\"40\" y1=\"55\" x2=\"").append(width - 40).append("\" y2=\"55\" stroke=\"#475569\" stroke-width=\"2\"/>")
                .append("<text x=\"40\" y=\"96\" fill=\"#93a4c3\" font-size=\"11\">seq ").append(minSeq).append("</text>")
                .append("<text x=\"").append(width - 120).append("\" y=\"96\" fill=\"#93a4c3\" font-size=\"11\">seq ").append(maxSeq).append("</text>");
        int plotW = width - 80;
        for (int i = 0; i < cases.size(); i++) {
            CaseRecord c = cases.get(i);
            double frac = (c.fromSequence() - minSeq) / (double) span;
            double x = 40.0 + frac * plotW;
            double y = 32.0 + (i % 3) * 22.0;
            String fill = switch (c.evidenceClass()) {
                case "P-A" -> "#ef4444";
                case "P-M" -> "#f59e0b";
                case "D" -> "#a855f7";
                default -> "#38bdf8";
            };
            svg.append(String.format(Locale.ROOT,
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"6\" fill=\"%s\" stroke=\"#0b1020\" stroke-width=\"1.5\"><title>%s (%s) seq %d..%d</title></circle>",
                    x, y, fill, escapeHtml(c.check()), escapeHtml(c.playerName()), c.fromSequence(), c.toSequence()));
        }
        svg.append("</svg>");
        return svg.toString();
    }

    private static String kpi(String label, String value) {
        return "<div class=\"kpi\"><div class=\"lbl\">" + escapeHtml(label) + "</div><div class=\"val\">" + escapeHtml(value) + "</div></div>";
    }

    private static String bar(String label, String valueText, double pct) {
        return String.format(Locale.ROOT,
                "<div class=\"bar-wrap\"><div style=\"display:flex;justify-content:space-between;font-size:.8rem\"><span>%s</span><strong>%s</strong></div>"
                        + "<div class=\"bar-track\"><div class=\"bar-fill\" style=\"width:%.1f%%\"></div></div></div>",
                escapeHtml(label), escapeHtml(valueText), pct);
    }

    private static double clampPct(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(100.0, v));
    }

    private static String classCss(String cls) {
        return switch (cls) {
            case "P-A" -> "b-pa";
            case "P-M" -> "b-pm";
            case "D" -> "b-d";
            default -> "b-s";
        };
    }

    /** Strict HTML character escaping for markup text and double-quoted attributes. */
    public static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Escapes JSON for safe embedding directly inside an inline HTML {@code <script>} element. */
    public static String escapeScriptJsonString(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                case '\u2028' -> out.append("\\u2028");
                case '\u2029' -> out.append("\\u2029");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String casesToSafeScriptJson(List<CaseRecord> cases) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cases.size(); i++) {
            CaseRecord c = cases.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(escapeScriptJsonString(c.id()))
                    .append("\",\"created\":\"").append(escapeScriptJsonString(c.created()))
                    .append("\",\"check\":\"").append(escapeScriptJsonString(c.check()))
                    .append("\",\"evidenceClass\":\"").append(escapeScriptJsonString(c.evidenceClass()))
                    .append("\",\"lifecycle\":\"").append(escapeScriptJsonString(c.lifecycle()))
                    .append("\",\"playerName\":\"").append(escapeScriptJsonString(c.playerName()))
                    .append("\",\"verificationStatus\":\"").append(escapeScriptJsonString(c.verificationStatus()))
                    .append("\",\"authorizedAction\":\"").append(escapeScriptJsonString(c.authorizedAction()))
                    .append("\",\"fingerprint\":\"").append(escapeScriptJsonString(c.fingerprint()))
                    .append("\",\"summary\":\"").append(escapeScriptJsonString(c.summary()))
                    .append("\"}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static String str(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof String s ? s : null;
    }

    private static long numLong(Map<String, Object> obj, String key, long def) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }

    private static Double numDoubleOrNull(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static String nonNull(String s, String def) {
        return s == null ? def : s;
    }

    /** Lightweight RFC 8259 recursive-descent JSON reader (zero external dependencies). */
    private static final class SimpleJson {
        private static final int MAX_DEPTH = 32;
        private final String s;
        private int pos;

        SimpleJson(String s) {
            this.s = s == null ? "" : s;
        }

        Object parseRoot() {
            skipWs();
            Object v = parseValue(0);
            skipWs();
            if (pos != s.length()) {
                throw new IllegalArgumentException("Trailing JSON characters at " + pos);
            }
            return v;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON depth limit exceeded");
            }
            skipWs();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected EOF");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObj(depth + 1);
                case '[' -> parseArr(depth + 1);
                case '"' -> parseStr();
                case 't' -> parseLit("true", Boolean.TRUE);
                case 'f' -> parseLit("false", Boolean.FALSE);
                case 'n' -> parseLit("null", null);
                default -> parseNum();
            };
        }

        private Map<String, Object> parseObj(int depth) {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected key at " + pos);
                }
                String k = parseStr();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at " + pos);
                }
                pos++;
                map.put(k, parseValue(depth));
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
            }
        }

        private List<Object> parseArr(int depth) {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth));
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    return list;
                }
                throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
            }
        }

        private String parseStr() {
            pos++; // '"'
            StringBuilder out = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> out.append(e);
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape \\" + e);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number parseNum() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            boolean frac = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                frac = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                frac = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String token = s.substring(start, pos);
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Invalid number at " + start);
            }
            try {
                return frac ? Double.parseDouble(token) : Long.parseLong(token);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(token);
            }
        }

        private Object parseLit(String lit, Object val) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("Expected " + lit + " at " + pos);
            }
            pos += lit.length();
            return val;
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
