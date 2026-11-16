#!/usr/bin/env bash
# WhyNotAC multi-process proxy E2E test:
# Boots Paper 1.21.11 + Velocity 3.4.0 with WhyNotAC + WhyNotAC-Velocity companion plugins,
# connects the protocol-774 probe client through Velocity modern forwarding, and verifies
# authenticated whynotac:bridge messaging (NET-01 / NET-22).
#
# usage: tools/e2e/run_proxy_e2e.sh <work-dir>
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)

PAPER_JAR=paper-1.21.11-132.jar
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
VELOCITY_JAR=velocity-3.4.0-SNAPSHOT-563.jar
VELOCITY_SHA256=fe53021f3168322cb6cb68f78699866fd098df3c306e4359847a10b0d02689ef

PAPER_PORT=25598
VELOCITY_PORT=25577

PAPER_DIR="$WORK_DIR/paper"
VEL_DIR="$WORK_DIR/velocity"
mkdir -p "$PAPER_DIR/plugins/WhyNotAC" "$PAPER_DIR/config" "$VEL_DIR/plugins/whynotac-velocity"

if [ ! -f "$PAPER_DIR/$PAPER_JAR" ]; then
  if [ -f "/tmp/wnac-e2e/$PAPER_JAR" ]; then
    cp "/tmp/wnac-e2e/$PAPER_JAR" "$PAPER_DIR/$PAPER_JAR"
  else
    curl -fsS --max-time 300 -o "$PAPER_DIR/$PAPER_JAR" "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/$PAPER_JAR"
  fi
fi
echo "$PAPER_SHA256  $PAPER_DIR/$PAPER_JAR" | sha256sum -c --quiet -

if [ ! -f "$VEL_DIR/$VELOCITY_JAR" ]; then
  curl -fsS --max-time 300 -o "$VEL_DIR/$VELOCITY_JAR" "https://fill-data.papermc.io/v1/objects/$VELOCITY_SHA256/$VELOCITY_JAR"
fi
echo "$VELOCITY_SHA256  $VEL_DIR/$VELOCITY_JAR" | sha256sum -c --quiet -

(cd "$ROOT" && ./gradlew -q :whynotac-paper:jar :whynotac-velocity:jar)
PAPER_PLUGIN=$(find "$ROOT/whynotac-paper/build/libs" -name 'whynotac-paper-*.jar' ! -name '*-sources.jar' | head -1)
VEL_PLUGIN=$(find "$ROOT/whynotac-velocity/build/libs" -name 'whynotac-velocity-*.jar' ! -name '*-sources.jar' | head -1)
cp "$PAPER_PLUGIN" "$PAPER_DIR/plugins/WhyNotAC.jar"
cp "$VEL_PLUGIN" "$VEL_DIR/plugins/WhyNotAC-Velocity.jar"

# Shared 32-byte HMAC secret for whynotac:bridge
python3 -I -c 'import os, sys; b=os.urandom(32); open(sys.argv[1],"wb").write(b); open(sys.argv[2],"wb").write(b)' \
  "$PAPER_DIR/plugins/WhyNotAC/network-secret.key" \
  "$VEL_DIR/plugins/whynotac-velocity/network-secret.key"

echo 'eula=true' > "$PAPER_DIR/eula.txt"
rm -rf "$PAPER_DIR/world" "$PAPER_DIR/world_nether" "$PAPER_DIR/world_the_end" "$PAPER_DIR/usercache.json"
cat > "$PAPER_DIR/server.properties" <<PROPS
online-mode=false
server-port=$PAPER_PORT
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
spawn-monsters=false
difficulty=peaceful
max-players=5
PROPS

cat > "$PAPER_DIR/config/paper-global.yml" <<PAPERGLOBAL
_version: 29
proxies:
  velocity:
    enabled: true
    online-mode: false
    secret: "wnac-e2e-forwarding-secret"
PAPERGLOBAL

cat > "$PAPER_DIR/plugins/WhyNotAC/config.yml" <<WNACCFG
recording:
  enabled: false
observability:
  health-watchdog: true
network-bridge:
  enabled: true
  secret-file: "network-secret.key"
  max-clock-skew-seconds: 60
WNACCFG

printf '%s' "wnac-e2e-forwarding-secret" > "$VEL_DIR/forwarding.secret"
cat > "$VEL_DIR/velocity.toml" <<VELTOML
config-version = "2.7"
bind = "127.0.0.1:$VELOCITY_PORT"
motd = "WhyNotAC Proxy E2E"
show-max-players = 10
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = false

[servers]
lobby = "127.0.0.1:$PAPER_PORT"
try = ["lobby"]

[forced-hosts]
VELTOML

PAPER_FIFO="$WORK_DIR/paper.fifo"
VEL_FIFO="$WORK_DIR/vel.fifo"
rm -f "$PAPER_FIFO" "$VEL_FIFO"
mkfifo "$PAPER_FIFO" "$VEL_FIFO"
sleep infinity > "$PAPER_FIFO" & PAPER_HOLDER=$!
sleep infinity > "$VEL_FIFO" & VEL_HOLDER=$!

PAPER_PID=""
VEL_PID=""
cleanup() {
  echo "shutdown" > "$VEL_FIFO" 2>/dev/null || true
  echo "stop" > "$PAPER_FIFO" 2>/dev/null || true
  [ -n "$VEL_PID" ] && wait "$VEL_PID" 2>/dev/null || true
  [ -n "$PAPER_PID" ] && wait "$PAPER_PID" 2>/dev/null || true
  kill "$PAPER_HOLDER" "$VEL_HOLDER" 2>/dev/null || true
  rm -f "$PAPER_FIFO" "$VEL_FIFO"
}
trap cleanup EXIT

(cd "$PAPER_DIR" && exec java -Xms512M -Xmx1G -jar "$PAPER_JAR" --nogui < "$PAPER_FIFO" > "$WORK_DIR/paper.log" 2>&1) &
PAPER_PID=$!
until grep -q 'Done (' "$WORK_DIR/paper.log" 2>/dev/null; do
  if ! kill -0 "$PAPER_PID" 2>/dev/null; then echo "paper died"; tail -50 "$WORK_DIR/paper.log"; exit 1; fi
  sleep 1
done
echo "paper up: $(grep -o 'Done ([^)]*)' "$WORK_DIR/paper.log")"

(cd "$VEL_DIR" && exec java -Xms256M -Xmx512M -jar "$VELOCITY_JAR" < "$VEL_FIFO" > "$WORK_DIR/velocity.log" 2>&1) &
VEL_PID=$!
until grep -q 'Done (' "$WORK_DIR/velocity.log" 2>/dev/null; do
  if ! kill -0 "$VEL_PID" 2>/dev/null; then echo "velocity died"; tail -50 "$WORK_DIR/velocity.log"; exit 1; fi
  sleep 1
done
echo "velocity up: $(grep -o 'Done ([^)]*)' "$WORK_DIR/velocity.log")"

python3 -I "$HERE/probe_client.py" 127.0.0.1 "$VELOCITY_PORT" WhyNotProxy 14 > "$WORK_DIR/proxy-probe.json" &
PROBE_PID=$!

sleep 8
echo "wnacproxy status" > "$VEL_FIFO"
echo "whynotac inspect WhyNotProxy" > "$PAPER_FIFO"
echo "whynotac network" > "$PAPER_FIFO"
echo "whynotac health" > "$PAPER_FIFO"
wait "$PROBE_PID"
sleep 1

echo "shutdown" > "$VEL_FIFO"
echo "stop" > "$PAPER_FIFO"
wait "$VEL_PID" 2>/dev/null || true
wait "$PAPER_PID" 2>/dev/null || true
trap - EXIT
kill "$PAPER_HOLDER" "$VEL_HOLDER" 2>/dev/null || true
rm -f "$PAPER_FIFO" "$VEL_FIFO"

echo "--- velocity summary"
grep -E "WhyNotAC|wnacproxy" "$WORK_DIR/velocity.log" || true
echo "--- paper summary"
grep -E "WhyNotAC|== WhyNotAC|WhyNotAC network|WhyNotAC health" "$WORK_DIR/paper.log" || true

python3 -I - "$WORK_DIR/velocity.log" "$WORK_DIR/paper.log" "$WORK_DIR/proxy-probe.json" <<'PY'
import json, re, sys
vel = re.sub(r"\x1b\[[0-9;]*m|\r", "", open(sys.argv[1]).read())
pap = re.sub(r"\x1b\[[0-9;]*m|\r", "", open(sys.argv[2]).read())
probe = json.loads(open(sys.argv[3]).read().strip().splitlines()[-1])

checks = [
    ("Velocity companion enabled", "WhyNotAC Velocity companion active (channel=whynotac:bridge)" in vel),
    ("Paper network bridge enabled", "WhyNotAC Velocity companion network bridge enabled (whynotac:bridge)" in pap),
    ("Probe stayed connected behind Velocity", probe.get("still_connected") is True),
    ("Velocity sent signed ProxyClientEdge", re.search(r"sentEdges=[1-9]\d*\b.*rejected=0\b", vel) is not None),
    ("Paper verified signed whynotac:bridge frame", re.search(r"WhyNotAC network bridge active: valid=[1-9]\d*\b rejected=0\b", pap) is not None),
    ("Paper session observed through proxy", "== WhyNotAC: WhyNotProxy" in pap),
    ("Paper health GREEN", re.search(r"WhyNotAC health: GREEN\b", pap) is not None),
]
failures = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("PASS " if ok else "FAIL ") + name)
if failures:
    sys.exit(1)
print("PROXY_E2E_RESULT: OK")
PY
