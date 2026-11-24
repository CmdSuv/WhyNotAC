package dev.whynotac.paper.diagnostics;

import java.util.List;
import java.util.Locale;

/**
 * Self-contained HTML5 live operator web dashboard served on {@code /dashboard} when
 * {@code observability.dashboard-enabled: true} and a non-empty {@code observability.metrics-auth-token}
 * is configured (PH24-02).
 *
 * <p>Zero external assets; all dynamic fields are rendered exclusively via {@code textContent} to prevent DOM XSS.
 */
public final class OperatorDashboardHtml {

    public static final String CSP_HEADER =
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'";

    private OperatorDashboardHtml() {}

    public static String renderPage() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>WhyNotAC Live Operator Dashboard</title>
                <style>
                :root{--bg:#090e1c;--panel:#11192e;--panel2:#18233f;--text:#e6edf7;--muted:#8fa3c7;--border:#243356;--green:#22c55e;--yellow:#f59e0b;--red:#ef4444;--accent:#38bdf8}
                *{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--text);line-height:1.45}
                .wrap{max-width:1280px;margin:0 auto;padding:22px}
                header{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:12px;border-bottom:1px solid var(--border);padding-bottom:14px;margin-bottom:18px}
                h1{margin:0;font-size:1.4rem}.sub{color:var(--muted);font-size:.86rem}
                .pill{display:inline-block;padding:4px 12px;border-radius:999px;font-weight:700;font-size:.82rem}
                .p-GREEN{background:rgba(34,197,94,.18);color:#86efac;border:1px solid var(--green)}
                .p-DEGRADED{background:rgba(245,158,11,.18);color:#fcd34d;border:1px solid var(--yellow)}
                .p-CRITICAL{background:rgba(239,68,68,.18);color:#fca5a5;border:1px solid var(--red)}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin-bottom:18px}
                .card{background:var(--panel);border:1px solid var(--border);border-radius:12px;padding:14px}
                .lbl{color:var(--muted);font-size:.76rem;text-transform:uppercase;letter-spacing:.05em}
                .val{font-size:1.5rem;font-weight:700;margin-top:4px}
                .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.82rem}
                table{width:100%;border-collapse:collapse;font-size:.85rem}
                th,td{text-align:left;padding:8px;border-bottom:1px solid var(--border)}
                th{color:var(--muted);font-weight:600}
                .auth-bar{display:flex;gap:8px;align-items:center}
                input{background:var(--panel2);color:var(--text);border:1px solid var(--border);padding:6px 10px;border-radius:8px}
                button{background:var(--panel2);color:var(--text);border:1px solid var(--border);padding:6px 12px;border-radius:8px;cursor:pointer}
                button:hover{border-color:var(--accent)}
                </style>
                </head>
                <body>
                <div class="wrap">
                  <header>
                    <div>
                      <h1>WhyNotAC Live Operator Dashboard</h1>
                      <div class="sub">Auto-refreshes every 5 s via <code>/api/status</code> &bull; <span id="lastUpdate">loading...</span></div>
                    </div>
                    <div class="auth-bar">
                      <span id="healthBadge" class="pill p-GREEN">GREEN</span>
                      <input id="tok" type="password" placeholder="Bearer token (for refresh)" autocomplete="off">
                      <button id="btnRefresh" type="button">Refresh</button>
                    </div>
                  </header>
                  <section class="grid">
                    <div class="card"><div class="lbl">Live Sessions</div><div class="val" id="mSessions">-</div></div>
                    <div class="card"><div class="lbl">Queued Events</div><div class="val" id="mQueued">-</div></div>
                    <div class="card"><div class="lbl">Budget Yields</div><div class="val" id="mYields">-</div></div>
                    <div class="card"><div class="lbl">Gap Bursts</div><div class="val" id="mGaps">-</div></div>
                    <div class="card"><div class="lbl">Cases Stored / Dropped</div><div class="val" id="mCases">-</div></div>
                    <div class="card"><div class="lbl">WAL Batches Committed</div><div class="val" id="mWal">-</div></div>
                    <div class="card"><div class="lbl">Circuit Breaker</div><div class="val" id="mCb">-</div></div>
                    <div class="card"><div class="lbl">False-Ban Budget</div><div class="val" id="mFbb">-</div></div>
                    <div class="card"><div class="lbl">Redis Pub/Sub (Pub/Sub)</div><div class="val" id="mRedis">-</div></div>
                    <div class="card"><div class="lbl">Tick Loop Mean / Max us</div><div class="val" id="mTick">-</div></div>
                  </section>
                  <section class="card" style="margin-bottom:18px">
                    <div class="lbl">Health Reasons</div>
                    <div id="healthReasons" class="mono" style="margin-top:6px">nominal</div>
                  </section>
                  <section class="card">
                    <div class="lbl" style="margin-bottom:8px">Recent Cases</div>
                    <table>
                      <thead><tr><th>ID</th><th>Player</th><th>Check</th><th>Class</th><th>Lifecycle</th><th>Summary</th></tr></thead>
                      <tbody id="caseRows"></tbody>
                    </table>
                  </section>
                </div>
                <script>
                (function(){
                  const tokInput=document.getElementById('tok');
                  tokInput.value=sessionStorage.getItem('wnac_token')||'';
                  tokInput.addEventListener('change',()=>sessionStorage.setItem('wnac_token',tokInput.value.trim()));
                  function setText(id,val){document.getElementById(id).textContent=String(val);}
                  async function poll(){
                    const headers={};
                    const t=(tokInput.value||'').trim();
                    if(t){headers['Authorization']='Bearer '+t;}
                    try{
                      const r=await fetch('/api/status',{headers,cache:'no-store'});
                      if(!r.ok){setText('lastUpdate','HTTP '+r.status);return;}
                      const d=await r.json();
                      const b=document.getElementById('healthBadge');
                      b.textContent=d.health.status;
                      b.className='pill p-'+d.health.status;
                      setText('healthReasons',d.health.reasons.length?d.health.reasons.join('; '):'all subsystems nominal');
                      setText('mSessions',d.sessionsLive+' / '+d.sessionsTotal);
                      setText('mQueued',d.queuedEvents);
                      setText('mYields',d.sessionBudgetYields);
                      setText('mGaps',d.sessionGapBursts);
                      setText('mCases',d.casesStored+' / '+d.casesDropped);
                      setText('mWal',d.batchesCommitted+' ('+d.casesBatched+')');
                      setText('mCb',d.circuitBreaker.killSwitch?'KILL-SWITCH':(d.circuitBreaker.tripped?'TRIPPED':'OK'));
                      setText('mFbb',d.falseBanBudget.incidents+'/'+d.falseBanBudget.limit+(d.falseBanBudget.locked?' LOCKED':''));
                      setText('mRedis',d.redis.published+' / '+d.redis.received);
                      setText('mTick',d.transactionMeanWallMicros.toFixed(1)+' / '+d.transactionMaxWallMicros.toFixed(1));
                      const tbody=document.getElementById('caseRows');
                      tbody.textContent='';
                      for(const c of d.recentCases){
                        const tr=document.createElement('tr');
                        for(const v of [c.id.slice(0,8),c.playerName,c.check,c.evidenceClass,c.lifecycle,c.summary]){
                          const td=document.createElement('td');
                          td.textContent=String(v??'');
                          tr.appendChild(td);
                        }
                        tbody.appendChild(tr);
                      }
                      setText('lastUpdate','updated '+new Date().toLocaleTimeString());
                    }catch(e){setText('lastUpdate','poll error');}
                  }
                  document.getElementById('btnRefresh').addEventListener('click',poll);
                  poll();
                  setInterval(poll,5000);
                })();
                </script>
                </body>
                </html>
                """;
    }

    public static String formatStatusJson(MetricsExporter.DashboardStatus status) {
        MetricsExporter.Snapshot s = status.metrics();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"sessionsLive\":").append(s.sessionsLive())
                .append(",\"sessionsTotal\":").append(s.sessionsTotal())
                .append(",\"queuedEvents\":").append(s.queuedEvents())
                .append(",\"lostEvents\":").append(s.lostEvents())
                .append(",\"casesOpened\":").append(s.casesOpened())
                .append(",\"casesStored\":").append(s.casesStored())
                .append(",\"casesDropped\":").append(s.casesDropped())
                .append(",\"banWavePending\":").append(s.banWavePending())
                .append(",\"sessionBudgetYields\":").append(s.sessionBudgetYields())
                .append(",\"sessionGapBursts\":").append(s.sessionGapBursts())
                .append(String.format(Locale.ROOT, ",\"transactionMeanWallMicros\":%.3f,\"transactionMaxWallMicros\":%.3f",
                        s.transactionMeanWallMicros(), s.transactionMaxWallMicros()))
                .append(",\"batchesCommitted\":").append(status.batchesCommitted())
                .append(",\"casesBatched\":").append(status.casesBatched())
                .append(",\"redis\":{\"published\":").append(status.redisPublished())
                .append(",\"received\":").append(status.redisReceived()).append('}')
                .append(",\"circuitBreaker\":{\"tripped\":").append(status.circuitBreakerTripped())
                .append(",\"killSwitch\":").append(status.killSwitchActive())
                .append(",\"reason\":\"").append(escapeJson(status.circuitBreakerReason())).append("\"}")
                .append(",\"falseBanBudget\":{\"incidents\":").append(status.falseBanIncidents())
                .append(",\"limit\":").append(status.falseBanLimit())
                .append(",\"locked\":").append(status.falseBanLocked()).append('}')
                .append(",\"health\":").append(s.health().toJson())
                .append(",\"recentCases\":[");
        List<MetricsExporter.CaseSummary> cases = status.recentCases();
        if (cases != null) {
            for (int i = 0; i < cases.size(); i++) {
                MetricsExporter.CaseSummary c = cases.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(escapeJson(c.id()))
                        .append("\",\"createdEpochMillis\":").append(c.createdEpochMillis())
                        .append(",\"playerName\":\"").append(escapeJson(c.playerName()))
                        .append("\",\"check\":\"").append(escapeJson(c.check()))
                        .append("\",\"evidenceClass\":\"").append(escapeJson(c.evidenceClass()))
                        .append("\",\"lifecycle\":\"").append(escapeJson(c.lifecycle()))
                        .append("\",\"summary\":\"").append(escapeJson(c.summary())).append("\"}");
            }
        }
        sb.append("]}\n");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
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
}
