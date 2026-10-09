#!/usr/bin/env bash
# WhyNotAC live end-to-end test.
#
# Boots a real Paper 1.21.11 server with the freshly built plugin, connects the protocol-774 probe
# client over TCP, collects the server's view through the console command `whynotac inspect`, and
# verifies it against what the probe actually sent (verify_e2e.py). Exit code 0 means every check passed.
#
# usage: tools/e2e/run_e2e.sh <work-dir> [probe-seconds]
#   <work-dir> is created if needed; the official Paper build is downloaded into it and checksum-verified.
#   EXTRA_PLUGINS="a.jar b.jar" installs additional plugins (interop testing, e.g. ViaVersion, PacketEvents;
#   see fetch_interop_plugins.sh), and E2E_EXPECT_PLUGINS="ViaVersion packetevents" checks they were enabled.
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
PROBE_SECONDS=${2:-19}
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
# Hermetic: a fresh world and player data every run (state from an earlier run must not leak in).
rm -rf "$WORK_DIR/world" "$WORK_DIR/world_nether" "$WORK_DIR/world_the_end" "$WORK_DIR/usercache.json"
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
difficulty=peaceful
max-players=5
motd=WhyNotAC E2E
PROPS

(cd "$ROOT" && ./gradlew -q :whynotac-paper:jar :whynotac-interop-probe:jar)
mkdir -p "$WORK_DIR/plugins/WhyNotAC"
rm -rf "$WORK_DIR/plugins/WhyNotAC/recordings" "$WORK_DIR/plugins/WhyNotAC/cases" "$WORK_DIR/plugins/WhyNotAC/cases.db"
cat > "$WORK_DIR/plugins/WhyNotAC/config.yml" <<CONFIG
recording:
  enabled: true
  max-age-days: 14
  max-total-megabytes: 2048
  max-queued-megabytes: 64
CONFIG
PLUGIN_JAR=$(find "$ROOT/whynotac-paper/build/libs" -name 'whynotac-paper-*.jar' ! -name '*-sources.jar' | head -1)
cp "$PLUGIN_JAR" "$WORK_DIR/plugins/WhyNotAC.jar"
find "$WORK_DIR/plugins" -maxdepth 1 -name '*.jar' ! -name 'WhyNotAC.jar' -delete
# Test-only helper that sends and cancels knockback below WhyNotAC (PacketEvents if installed, raw Netty otherwise).
find "$ROOT/whynotac-interop-probe/build/libs" -name 'whynotac-interop-probe-*.jar' ! -name '*-sources.jar' -exec cp {} "$WORK_DIR/plugins/WhyNotACInteropProbe.jar" \;
for extra in ${EXTRA_PLUGINS:-}; do cp "$extra" "$WORK_DIR/plugins/"; done

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
# Knockback three ways: the game's own path, injected below the game, and sent by the game but cancelled below it.
sleep 4; echo "wnprobe game WhyNotProbe 0 0.3 0" > "$FIFO"
sleep 1; echo "wnprobe inject WhyNotProbe 0 0.5 0" > "$FIFO"
sleep 1; echo "wnprobe cancel WhyNotProbe 0 0.7 0" > "$FIFO"
# The client's world: one block (block_update) and a slab of 9x9x2 (section_blocks_update), above the probe's head.
# Separate ticks: changes in one section in the same tick are sent together as one section update.
sleep 1; echo "execute at WhyNotProbe run setblock ~3 ~6 ~3 minecraft:stone" > "$FIFO"
sleep 1; echo "execute at WhyNotProbe run fill ~-4 ~7 ~-4 ~4 ~8 ~4 minecraft:glass" > "$FIFO"
# The client's entities: still mobs (no AI: only the commands move them; a husk does not burn in daylight), a
# passenger, an armor stand, an end crystal, and a cow that is killed (removed from the client).
sleep 1
echo "execute at WhyNotProbe run summon minecraft:husk ~2 ~ ~2 {NoAI:1b,PersistenceRequired:1b,Tags:[\"wnz\"]}" > "$FIFO"
echo "execute at WhyNotProbe run summon minecraft:villager ~-2 ~ ~2 {NoAI:1b,PersistenceRequired:1b,Tags:[\"wnv\"]}" > "$FIFO"
echo "execute at WhyNotProbe run summon minecraft:armor_stand ~-2 ~ ~-2" > "$FIFO"
echo "execute at WhyNotProbe run summon minecraft:end_crystal ~3 ~ ~-3 {ShowBottom:0b}" > "$FIFO"
echo "execute at WhyNotProbe run summon minecraft:pig ~4 ~ ~0.5 {NoAI:1b,Passengers:[{id:\"minecraft:armor_stand\",Small:1b}]}" > "$FIFO"
echo "execute at WhyNotProbe run summon minecraft:cow ~-4 ~ ~0.5 {NoAI:1b,Tags:[\"wnc\"]}" > "$FIFO"
# Moves: absolute and relative positions and rotations; the cow is removed.
sleep 1; echo "execute at WhyNotProbe run tp @e[tag=wnz,limit=1] ~1.3172 ~ ~2.7071 45 10" > "$FIFO"
echo "execute at WhyNotProbe run tp @e[tag=wnv,limit=1] ~-1.5 ~ ~1.25 -170 0" > "$FIFO"
sleep 1; echo "execute at WhyNotProbe run tp @e[tag=wnz,limit=1] ~1.1 ~ ~2.9 -60 0" > "$FIFO"
echo "kill @e[tag=wnc]" > "$FIFO"
sleep 1; echo "execute as @e[tag=wnz,limit=1] at @s run tp @s ~0.0371 ~ ~-0.0518 ~15 ~" > "$FIFO"
sleep 1
echo "whynotac entities WhyNotProbe" > "$FIFO"
sleep $(( PROBE_SECONDS - 15 ))
echo "whynotac world WhyNotProbe" > "$FIFO"
echo "whynotac inspect WhyNotProbe" > "$FIFO"
echo "whynotac pipeline WhyNotProbe" > "$FIFO"
echo "whynotac sessions" > "$FIFO"
wait "$PROBE"
sleep 2   # let the server see the disconnect and finalise the recording
# After the probe: its violation (tick 200) may come later than the commands above on a slow machine.
echo "whynotac cases" > "$FIFO"
sleep 1
echo "stop" > "$FIFO"
wait "$SERVER" || true
trap - EXIT
kill "$HOLDER" 2>/dev/null || true
rm -f "$FIFO"

RECORDING=$(find "$WORK_DIR/plugins/WhyNotAC/recordings" -name '*.wnrec' | head -1)
echo "--- recording: ${RECORDING:-<none>}"
if [ -n "$RECORDING" ]; then
  java -jar "$WORK_DIR/plugins/WhyNotAC.jar" inspect "$RECORDING" > "$WORK_DIR/e2e-inspect.json" || true
  java -jar "$WORK_DIR/plugins/WhyNotAC.jar" dump "$RECORDING" > "$WORK_DIR/e2e-dump.jsonl" || true
  ls -la "$RECORDING"
  # Data-subject requests, the way docs/privacy/README.md tells operators to do them.
  PROBE_UUID=$(python3 -I -c 'import json, sys; print(next(e["uuid"] for e in json.load(open(sys.argv[1])) if e["name"] == "WhyNotProbe"))' "$WORK_DIR/usercache.json" || echo missing)
  SECRET="$WORK_DIR/plugins/WhyNotAC/recording-secret.key"
  java -jar "$WORK_DIR/plugins/WhyNotAC.jar" find "$WORK_DIR/plugins/WhyNotAC/recordings" "$SECRET" "$PROBE_UUID" > "$WORK_DIR/e2e-find.json" || true
  java -jar "$WORK_DIR/plugins/WhyNotAC.jar" find "$WORK_DIR/plugins/WhyNotAC/recordings" "$SECRET" "$(python3 -I -c 'import uuid; print(uuid.uuid4())')" > "$WORK_DIR/e2e-find-other.json" || true
  rm -rf "$WORK_DIR/forget-copy" && cp -r "$WORK_DIR/plugins/WhyNotAC/recordings" "$WORK_DIR/forget-copy"
  java -jar "$WORK_DIR/plugins/WhyNotAC.jar" forget "$WORK_DIR/forget-copy" "$SECRET" "$PROBE_UUID" --yes > "$WORK_DIR/e2e-forget.json" || true
fi

# Every dossier is re-verified independently of the server: the same evidence must come out of the recording.
rm -f "$WORK_DIR/e2e-verify.jsonl"
for dossier in "$WORK_DIR"/plugins/WhyNotAC/cases/*.json; do
  [ -e "$dossier" ] || continue
  java -jar "$WORK_DIR/plugins/WhyNotAC.jar" verify "$dossier" >> "$WORK_DIR/e2e-verify.jsonl" || true
done

echo "--- server view (whynotac inspect)"
grep -E '\[WhyNotAC\]|\[wnprobe\]|== WhyNotAC|RTT last|Transactions sent|View |Packets in|Last move|Last rotation|Keys W|Anomalies|WhyNotAC sessions|PlayerSession\[|Pipeline of|^\[[0-9:]+ INFO\]: +[0-9]+ ' "$WORK_DIR/e2e-server.log" | sed -E 's/\x1b\[[0-9;]*m//g'
echo "--- verification"
python3 -I "$HERE/verify_e2e.py" "$WORK_DIR/e2e-server.log" "$WORK_DIR/e2e-probe.json" "${RECORDING:-}" "$WORK_DIR/e2e-inspect.json" "$WORK_DIR/e2e-dump.jsonl" "$WORK_DIR"
