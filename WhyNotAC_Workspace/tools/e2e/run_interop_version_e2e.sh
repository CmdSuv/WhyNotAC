#!/usr/bin/env bash
# WhyNotAC Multi-Version Interop E2E Test (PH25-04):
# Boots real Paper 1.21.11 (protocol 774) with WhyNotAC + ViaVersion-5.12.0 + ViaBackwards-5.12.0,
# connects old_protocol_probe.py speaking Minecraft 1.21.4 (protocol 769), and verifies:
#   1. ViaVersion & ViaBackwards loaded and translated protocol 769 -> 774 cleanly.
#   2. WhyNotAC detected protocol 769, switched to GAME view, and emitted zero FP during honest ticks.
#   3. Physical check (movement) emitted NO_CLAIM despite injected mid-air hover (0 movement cases).
#   4. Translator-safe checks (evaluateTranslatorSafe) caught injected skipped-transaction and packet-bounds!
#
# usage: tools/e2e/run_interop_version_e2e.sh <work-dir>
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)

PAPER_JAR=paper-1.21.11-132.jar
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PORT=25595

VV_JAR=ViaVersion-5.12.0.jar
VV_URL="https://cdn.modrinth.com/data/P1OZGk5p/versions/FaishMnD/ViaVersion-5.12.0.jar"
VV_SHA512="2dfe562109179f08685dc84a66aedf7c810592223b5287b6355ba1e741d1737d015ca0c444b4bc01e5db2a4b10549d5028bf2e3f3337219ded709e44b579a4d8"

VB_JAR=ViaBackwards-5.12.0.jar
VB_URL="https://cdn.modrinth.com/data/NpvuJQoq/versions/SxGhdsPK/ViaBackwards-5.12.0.jar"
VB_SHA512="dba076b3283eb5987e3d37a63b57802e7946cf8a2d3b6f68ff57ddbc4eb648e2084decd212fef2cd0cde5187bc6565959c31b496bfb38000e67e3ffab4c290b3"

if [ ! -f "$WORK_DIR/$PAPER_JAR" ]; then
  if [ -f "/tmp/wnac-e2e/$PAPER_JAR" ]; then
    cp "/tmp/wnac-e2e/$PAPER_JAR" "$WORK_DIR/$PAPER_JAR"
  elif [ -f "/tmp/wnac-cheat-e2e/$PAPER_JAR" ]; then
    cp "/tmp/wnac-cheat-e2e/$PAPER_JAR" "$WORK_DIR/$PAPER_JAR"
  else
    curl -fsSL --retry 3 --retry-delay 5 --max-time 300 -o "$WORK_DIR/$PAPER_JAR" "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/$PAPER_JAR"
  fi
fi
echo "$PAPER_SHA256  $WORK_DIR/$PAPER_JAR" | sha256sum -c --quiet -

PLUGIN_CACHE="$WORK_DIR/cache-plugins"
mkdir -p "$PLUGIN_CACHE"
for item in "$VV_JAR|$VV_URL|$VV_SHA512" "$VB_JAR|$VB_URL|$VB_SHA512"; do
  IFS='|' read -r jar url sha <<< "$item"
  if [ ! -f "$PLUGIN_CACHE/$jar" ]; then
    if [ -f "/tmp/wnac-interop-plugins/$jar" ]; then
      cp "/tmp/wnac-interop-plugins/$jar" "$PLUGIN_CACHE/$jar"
    else
      curl -fsSL --retry 3 --retry-delay 5 --max-time 300 -o "$PLUGIN_CACHE/$jar.part" "$url"
      mv "$PLUGIN_CACHE/$jar.part" "$PLUGIN_CACHE/$jar"
    fi
  fi
  echo "$sha  $PLUGIN_CACHE/$jar" | sha512sum -c --quiet -
done

for i in 1 2 3 4; do
  if (cd "$ROOT" && ./gradlew -q :whynotac-paper:jar); then break; fi
  [ "$i" -eq 4 ] && exit 1
  sleep $((10 * i))
done
PLUGIN_JAR=$(find "$ROOT/whynotac-paper/build/libs" -name 'whynotac-paper-*.jar' ! -name '*-sources.jar' | head -1)

mkdir -p "$WORK_DIR/plugins/WhyNotAC"
rm -rf "$WORK_DIR/world" "$WORK_DIR/world_nether" "$WORK_DIR/world_the_end" "$WORK_DIR/usercache.json" \
       "$WORK_DIR/plugins/WhyNotAC/recordings" "$WORK_DIR/plugins/WhyNotAC/cases" "$WORK_DIR/plugins/WhyNotAC/cases.db" \
       "$WORK_DIR/plugins/WhyNotAC/alerts-stream.jsonl"
cp "$PLUGIN_JAR" "$WORK_DIR/plugins/WhyNotAC.jar"
cp "$PLUGIN_CACHE/$VV_JAR" "$WORK_DIR/plugins/$VV_JAR"
cp "$PLUGIN_CACHE/$VB_JAR" "$WORK_DIR/plugins/$VB_JAR"

cat > "$WORK_DIR/plugins/WhyNotAC/config.yml" <<CFG
recording:
  enabled: false
observation:
  edge: true
  per-tick-transactions: true
  flush-transactions: true
verdict:
  armed-checks: []
  mitigations: false
  auto-kick: false
  auto-ban: false
observability:
  health-watchdog: true
  alert-stream-file: "alerts-stream.jsonl"
CFG

echo 'eula=true' > "$WORK_DIR/eula.txt"
cat > "$WORK_DIR/server.properties" <<PROPS
online-mode=false
server-port=$PORT
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
spawn-monsters=false
difficulty=peaceful
max-players=5
PROPS

FIFO="$WORK_DIR/interop.fifo"
rm -f "$FIFO" && mkfifo "$FIFO"
sleep infinity > "$FIFO" & HOLDER=$!
(cd "$WORK_DIR" && exec java -Xms512M -Xmx1536M -jar "$PAPER_JAR" --nogui < "$FIFO" > "$WORK_DIR/interop-server.log" 2>&1) &
SERVER=$!
cleanup() { echo "stop" > "$FIFO" 2>/dev/null || true; wait "$SERVER" 2>/dev/null || true; kill "$HOLDER" 2>/dev/null || true; rm -f "$FIFO"; }
trap cleanup EXIT

until grep -q 'Done (' "$WORK_DIR/interop-server.log" 2>/dev/null; do
  if ! kill -0 "$SERVER" 2>/dev/null; then echo "paper died"; tail -50 "$WORK_DIR/interop-server.log"; exit 1; fi
  sleep 1
done
echo "paper up: $(grep -o 'Done ([^)]*)' "$WORK_DIR/interop-server.log")"

python3 -I "$HERE/old_protocol_probe.py" 127.0.0.1 "$PORT" Legacy769 12 > "$WORK_DIR/probe-769.json" &
PROBE=$!

for _ in $(seq 1 150); do
  if grep -q 'Legacy769 joined the game' "$WORK_DIR/interop-server.log" 2>/dev/null; then
    break
  fi
  if ! kill -0 "$PROBE" 2>/dev/null; then
    echo "old_protocol_probe exited before joining"; tail -40 "$WORK_DIR/interop-server.log"; exit 1
  fi
  sleep 0.2
done

# Inspect midway during honest phase (3s after join -> must have 0 cases!)
sleep 3
echo "whynotac inspect Legacy769" > "$FIFO"
echo "whynotac cases" > "$FIFO"

wait "$PROBE"
sleep 2
echo "whynotac cases" > "$FIFO"
echo "whynotac verdict" > "$FIFO"
echo "whynotac stats" > "$FIFO"
sleep 1

echo "stop" > "$FIFO"
wait "$SERVER" || true
trap - EXIT

python3 - "$WORK_DIR/interop-server.log" "$WORK_DIR/probe-769.json" "$WORK_DIR/plugins/WhyNotAC/cases" <<'PY'
import glob, json, os, sys

log_path, probe_path, cases_dir = sys.argv[1], sys.argv[2], sys.argv[3]
log = open(log_path, encoding="utf-8", errors="replace").read()
probe = json.loads(open(probe_path, encoding="utf-8").read().strip().splitlines()[-1])

assert "Enabling ViaVersion" in log, "ViaVersion did not enable"
assert "Enabling ViaBackwards" in log, "ViaBackwards did not enable"
assert "Legacy769 joined the game" in log, "1.21.4 (769) probe failed to join Paper 1.21.11"
assert "View GAME | client protocol 769" in log, "WhyNotAC did not identify client protocol 769 in GAME view"
assert "WhyNotAC cases (newest first): 0" in log, "Honest 1.21.4 phase must open 0 cases (0 FP!)"
assert probe["pingsSeen"] >= 5 and probe["skippedPingInjected"], f"Probe ping trace insufficient: {probe}"

dossiers = []
for p in sorted(glob.glob(os.path.join(cases_dir, "*.json"))):
    dossiers.append(json.load(open(p, encoding="utf-8")))

checks = {d["check"] for d in dossiers}
physical_checks = {"movement", "attack-reach", "attack-aim", "break-time", "use-aim", "velocity-response", "use-slowdown"}
bad_physical = checks & physical_checks
assert not bad_physical, f"Translated 769 client MUST NOT produce physical claims (NO_CLAIM violated): {bad_physical}"
assert "transaction-order" in checks, f"Translator-safe transaction-order must catch skipped-transaction: {checks}"
assert "packet-bounds" in checks, f"Translator-safe packet-bounds must catch invalid pitch/slot: {checks}"

print(f"probe={json.dumps(probe)}")
print(f"checks_caught={sorted(checks)} total_dossiers={len(dossiers)} physical_claims=0")
print("INTEROP_VERSION_E2E_RESULT: OK")
PY
