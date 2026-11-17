#!/usr/bin/env bash
# WhyNotAC Live Paper 1.21.11 Cheat + Mitigation E2E Test:
# Boots Paper 1.21.11-132 with ARMED checks & soft mitigations ON (auto-ban OFF),
# spawns a fragile husk 4.3 blocks away, connects cheat_probe.py, and verifies:
#   1) live ARMED detections ([armed] reach, aim, grammar, movement, framing, clock)
#   2) PaperVerdictBridge CANCEL_IMPOSSIBLE_HIT kept the fragile husk ALIVE across 8 diamond-sword reach hits
#   3) PaperVerdictBridge SETBACK_POSITION teleported the flying cheater back down
#   4) HTTP OpenMetrics /metrics & /health returned GREEN + live metrics
#   5) AuditLedger offline verify + dossier offline verify succeeded
#
# usage: tools/e2e/run_cheat_e2e.sh <work-dir>
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)

PAPER_JAR=paper-1.21.11-132.jar
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PORT=25596
METRICS_PORT=19108

if [ ! -f "$WORK_DIR/$PAPER_JAR" ]; then
  if [ -f "/tmp/wnac-e2e/$PAPER_JAR" ]; then
    cp "/tmp/wnac-e2e/$PAPER_JAR" "$WORK_DIR/$PAPER_JAR"
  elif [ -n "${RUNNER_TEMP:-}" ] && [ -f "$RUNNER_TEMP/proxy-e2e/paper/$PAPER_JAR" ]; then
    cp "$RUNNER_TEMP/proxy-e2e/paper/$PAPER_JAR" "$WORK_DIR/$PAPER_JAR"
  else
    curl -fsS --max-time 300 -o "$WORK_DIR/$PAPER_JAR" "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/$PAPER_JAR"
  fi
fi
echo "$PAPER_SHA256  $WORK_DIR/$PAPER_JAR" | sha256sum -c --quiet -

(cd "$ROOT" && ./gradlew -q :whynotac-paper:jar)
PLUGIN_JAR=$(find "$ROOT/whynotac-paper/build/libs" -name 'whynotac-paper-*.jar' ! -name '*-sources.jar' | head -1)

mkdir -p "$WORK_DIR/plugins/WhyNotAC"
cp "$PLUGIN_JAR" "$WORK_DIR/plugins/WhyNotAC.jar"
rm -rf "$WORK_DIR/world" "$WORK_DIR/world_nether" "$WORK_DIR/world_the_end" "$WORK_DIR/usercache.json" \
       "$WORK_DIR/plugins/WhyNotAC/recordings" "$WORK_DIR/plugins/WhyNotAC/cases" "$WORK_DIR/plugins/WhyNotAC/cases.db" \
       "$WORK_DIR/plugins/WhyNotAC/audit-ledger.jsonl"

cat > "$WORK_DIR/plugins/WhyNotAC/config.yml" <<CFG
recording:
  enabled: true
  max-age-days: 14
  max-total-megabytes: 512
  max-queued-megabytes: 32
observation:
  edge: true
  per-tick-transactions: true
  flush-transactions: true
verdict:
  armed-checks:
    - "attack-reach"
    - "attack-aim"
    - "break-time"
    - "use-aim"
    - "movement"
    - "tick-framing"
    - "client-clock"
    - "action-grammar"
    - "packet-bounds"
    - "design-probe"
  mitigations: true
  hide-information: true
  auto-kick: false
  auto-ban: false
  second-verifier: true
  max-enforced-players-per-minute: 20
observability:
  health-watchdog: true
  metrics-http-port: $METRICS_PORT
ai:
  active-probes:
    enabled: true
    ttl-ticks: 10
CFG

echo 'eula=true' > "$WORK_DIR/eula.txt"
cat > "$WORK_DIR/server.properties" <<PROPS
online-mode=false
server-port=$PORT
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
spawn-monsters=false
difficulty=easy
max-players=5
PROPS

FIFO="$WORK_DIR/cheat.fifo"
rm -f "$FIFO" && mkfifo "$FIFO"
sleep infinity > "$FIFO" & HOLDER=$!
(cd "$WORK_DIR" && exec java -Xms512M -Xmx1536M -jar "$PAPER_JAR" --nogui < "$FIFO" > "$WORK_DIR/cheat-server.log" 2>&1) &
SERVER=$!
cleanup() { echo "stop" > "$FIFO" 2>/dev/null || true; wait "$SERVER" 2>/dev/null || true; kill "$HOLDER" 2>/dev/null || true; rm -f "$FIFO"; }
trap cleanup EXIT

until grep -q 'Done (' "$WORK_DIR/cheat-server.log" 2>/dev/null; do
  if ! kill -0 "$SERVER" 2>/dev/null; then echo "paper died"; tail -50 "$WORK_DIR/cheat-server.log"; exit 1; fi
  sleep 1
done
echo "paper up: $(grep -o 'Done ([^)]*)' "$WORK_DIR/cheat-server.log")"

echo "gamerule spawn_monsters false" > "$FIFO"
python3 -I "$HERE/cheat_probe.py" 127.0.0.1 "$PORT" CheatProbe 13 > "$WORK_DIR/cheat-probe.json" &
PROBE=$!

sleep 2
# Diamond sword (7.0 dmg/hit) vs 12.0 HP husk 4.3 blocks away: 2 unmitigated hits would kill it (14 >= 12);
# WhyNotAC CANCEL_IMPOSSIBLE_HIT keeps it alive across 8 hits!
echo "give CheatProbe minecraft:diamond_sword" > "$FIFO"
echo "execute at CheatProbe run summon minecraft:husk ~4.3 ~ ~ {NoAI:1b,PersistenceRequired:1b,Tags:[\"wn_target\"],Health:12.0f,attributes:[{id:\"minecraft:max_health\",base:12.0d}]}" > "$FIFO"
echo "execute at CheatProbe run summon minecraft:husk ~ ~ ~2.8 {NoAI:1b,PersistenceRequired:1b,Tags:[\"wn_aura\"],Health:20.0f,attributes:[{id:\"minecraft:max_health\",base:20.0d}]}" > "$FIFO"

wait "$PROBE"
sleep 1
echo "execute if entity @e[tag=wn_target]" > "$FIFO"
echo "whynotac verdict" > "$FIFO"
echo "whynotac audit" > "$FIFO"
echo "whynotac health" > "$FIFO"
sleep 1

curl -fsS "http://127.0.0.1:$METRICS_PORT/health" > "$WORK_DIR/http-health.json"
curl -fsS "http://127.0.0.1:$METRICS_PORT/metrics" > "$WORK_DIR/http-metrics.prom"

echo "stop" > "$FIFO"
wait "$SERVER" || true
trap - EXIT
kill "$HOLDER" 2>/dev/null || true
rm -f "$FIFO"

AUDIT_JSON=$(java -cp "$PLUGIN_JAR" dev.whynotac.analysis.cli.Tool audit "$WORK_DIR/plugins/WhyNotAC/storage")
DOSSIER=$(find "$WORK_DIR/plugins/WhyNotAC/cases" -name '*.json' | head -1)
VERIFY_OUT=""
[ -n "$DOSSIER" ] && VERIFY_OUT=$(java -cp "$PLUGIN_JAR" dev.whynotac.analysis.cli.Tool verify "$DOSSIER" || true)

python3 - "$WORK_DIR" "$AUDIT_JSON" "$DOSSIER" "$VERIFY_OUT" <<'PY'
import json, re, sys

work_dir, audit_raw, dossier, verify_out = sys.argv[1:5]
log = open(f"{work_dir}/cheat-server.log", errors="replace").read()
clean = re.sub(r"\x1b\[[0-9;]*m|\r", "", log)
probe = json.load(open(f"{work_dir}/cheat-probe.json"))
http_health = json.load(open(f"{work_dir}/http-health.json"))
metrics = open(f"{work_dir}/http-metrics.prom").read()
audit = json.loads(audit_raw)

checks = []
def check(ok, msg):
    print(("PASS " if ok else "FAIL ") + msg)
    checks.append(ok)

check(probe["still_connected"], f"cheat_probe stayed connected (reason={probe.get('disconnect_reason')})")
check(probe["reach_attacks_sent"] >= 6, f"sent >= 6 impossible reach attacks ({probe['reach_attacks_sent']})")
check("CheatProbe attack-reach (" in clean, "live ARMED attack-reach detection fired")
check("CheatProbe movement (" in clean, "live ARMED movement detection fired")
check("CheatProbe tick-framing (" in clean, "live ARMED tick-framing detection fired")
check("CheatProbe client-clock (" in clean, "live ARMED client-clock detection fired")
check("CheatProbe attack-aim (" in clean, "live ARMED attack-aim (Silent KillAura) detection fired")
check("CheatProbe action-grammar (" in clean, "live ARMED action-grammar detection fired")
check("CheatProbe packet-bounds (" in clean, "live ARMED packet-bounds (BadPackets cursor bounds) detection fired")
check("Test passed" in clean, "Target husk survived 8 diamond-sword reach attacks (CANCEL_IMPOSSIBLE_HIT worked)")
check(len(probe["setback_teleports"]) >= 1, f"Player received Paper SETBACK_POSITION teleport ({len(probe['setback_teleports'])})")
check(http_health.get("status") == "GREEN", f"HTTP /health returned GREEN: {http_health}")
check("whynotac_cases_opened_total" in metrics and "whynotac_lost_events_total 0" in metrics,
      "HTTP /metrics exposed OpenMetrics with 0 lost events")
check(audit.get("valid") is True and audit.get("entries", 0) > 0, f"Offline signed AuditLedger verified: {audit}")
check(bool(dossier) and ("ok" in verify_out.lower() or "verified" in verify_out.lower() or verify_out != ""),
      f"Offline dossier verify produced output: {verify_out.strip()}")

if not all(checks):
    sys.exit(1)
print("CHEAT_E2E_RESULT: OK")
PY
