#!/usr/bin/env bash
# WhyNotAC live end-to-end test.
#
# Boots a real Paper 1.21.11 server with the freshly built plugin, connects the protocol-774 probe
# client over TCP, collects the server's view through the console command `whynotac inspect`, and
# verifies it against what the probe actually sent (verify_e2e.py). Exit code 0 means every check passed.
#
# usage: tools/e2e/run_e2e.sh <work-dir> [probe-seconds]
#   <work-dir> is created if needed; the official Paper build is downloaded into it and checksum-verified.
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
PROBE_SECONDS=${2:-14}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PAPER_JAR=paper-1.21.11-132.jar
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PORT=25599

if [ ! -f "$WORK_DIR/$PAPER_JAR" ]; then
  curl -fsS --max-time 300 -o "$WORK_DIR/$PAPER_JAR" "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/$PAPER_JAR"
fi
echo "$PAPER_SHA256  $WORK_DIR/$PAPER_JAR" | sha256sum -c --quiet -
echo 'eula=true' > "$WORK_DIR/eula.txt"
cat > "$WORK_DIR/server.properties" <<PROPS
online-mode=false
server-port=$PORT
level-type=minecraft\:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
spawn-monsters=false
max-players=5
motd=WhyNotAC E2E
PROPS

(cd "$ROOT" && ./gradlew -q :whynotac-paper:jar)
mkdir -p "$WORK_DIR/plugins"
cp "$ROOT"/whynotac-paper/build/libs/whynotac-paper-*.jar "$WORK_DIR/plugins/WhyNotAC.jar"

FIFO="$WORK_DIR/console.fifo"
rm -f "$FIFO" && mkfifo "$FIFO"
sleep infinity > "$FIFO" &   # keeps the console pipe open
HOLDER=$!
(cd "$WORK_DIR" && exec java -Xms1G -Xmx2G -jar "$PAPER_JAR" --nogui < "$FIFO" > "$WORK_DIR/e2e-server.log" 2>&1) &
SERVER=$!
cleanup() { echo "stop" > "$FIFO" || true; wait "$SERVER" || true; kill "$HOLDER" 2>/dev/null || true; rm -f "$FIFO"; }
trap cleanup EXIT

until grep -q 'Done (' "$WORK_DIR/e2e-server.log" 2>/dev/null; do
  if ! kill -0 "$SERVER" 2>/dev/null; then echo "server died during startup"; tail -50 "$WORK_DIR/e2e-server.log"; exit 1; fi
  sleep 1
done
echo "server up: $(grep -o 'Done ([^)]*)' "$WORK_DIR/e2e-server.log")"

python3 -I "$HERE/probe_client.py" 127.0.0.1 "$PORT" WhyNotProbe "$PROBE_SECONDS" > "$WORK_DIR/e2e-probe.json" &
PROBE=$!
sleep $(( PROBE_SECONDS - 2 ))
echo "whynotac inspect WhyNotProbe" > "$FIFO"
echo "whynotac sessions" > "$FIFO"
wait "$PROBE"
sleep 1

echo "--- server view (whynotac inspect)"
grep -E '\[WhyNotAC\]|== WhyNotAC|RTT last|Transactions sent|Packets in|Last move|Exact dYaw|Keys W|Anomalies|WhyNotAC sessions|PlayerSession\[' "$WORK_DIR/e2e-server.log" | sed -E 's/\x1b\[[0-9;]*m//g'
echo "--- verification"
python3 -I "$HERE/verify_e2e.py" "$WORK_DIR/e2e-server.log" "$WORK_DIR/e2e-probe.json"
