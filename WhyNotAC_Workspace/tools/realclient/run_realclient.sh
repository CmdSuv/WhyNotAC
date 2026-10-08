#!/usr/bin/env bash
# WhyNotAC ground truth with the real game: the official, unmodified Minecraft 1.21.11 client runs
# headless (Xvfb + Mesa software OpenGL), joins a real Paper server with WhyNotAC, and is played with
# real OS-level keyboard and mouse input (XTest). The recording is then checked against WhyNotAC's
# vanilla models (verify_realclient.py).
#
# usage: tools/realclient/run_realclient.sh <work-dir>
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PAPER_JAR=paper-1.21.11-132.jar
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PORT=25598
DISPLAY_NUM=:${REALCLIENT_DISPLAY:-97}
NAME=RealProbe

python3 -I "$HERE/fetch_client.py" "$WORK_DIR/client-cache" > /dev/null
if [ ! -f "$WORK_DIR/$PAPER_JAR" ]; then
  curl -fsS --max-time 300 -o "$WORK_DIR/$PAPER_JAR" "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/$PAPER_JAR"
fi
echo "$PAPER_SHA256  $WORK_DIR/$PAPER_JAR" | sha256sum -c --quiet -
echo 'eula=true' > "$WORK_DIR/eula.txt"
rm -rf "$WORK_DIR/world" "$WORK_DIR/world_nether" "$WORK_DIR/world_the_end" "$WORK_DIR/usercache.json" "$WORK_DIR/game/screenshots" "$WORK_DIR/game/crash-reports"
cat > "$WORK_DIR/server.properties" <<PROPS
online-mode=false
server-port=$PORT
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=3
difficulty=peaceful
spawn-monsters=false
max-players=5
motd=WhyNotAC real client
PROPS
(cd "$ROOT" && ./gradlew -q :whynotac-paper:jar)
mkdir -p "$WORK_DIR/plugins/WhyNotAC" "$WORK_DIR/game"
rm -rf "$WORK_DIR/plugins/WhyNotAC/recordings" "$WORK_DIR/plugins/WhyNotAC/cases" "$WORK_DIR/plugins/WhyNotAC/cases.db"
printf 'recording:\n  enabled: true\n  max-age-days: 14\n  max-total-megabytes: 2048\n  max-queued-megabytes: 64\n' > "$WORK_DIR/plugins/WhyNotAC/config.yml"
find "$ROOT/whynotac-paper/build/libs" -name 'whynotac-paper-*.jar' ! -name '*-sources.jar' -exec cp {} "$WORK_DIR/plugins/WhyNotAC.jar" \;
find "$WORK_DIR/plugins" -maxdepth 1 -name '*.jar' ! -name 'WhyNotAC.jar' -delete
# Options that would otherwise stop the client on a screen (first-run onboarding, warnings) or slow it down.
cat > "$WORK_DIR/game/options.txt" <<'OPTS'
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
pauseOnLostFocus:false
renderDistance:2
simulationDistance:5
maxFps:60
narrator:0
soundCategory_master:0.0
mouseSensitivity:0.83
OPTS

Xvfb "$DISPLAY_NUM" -screen 0 1024x768x24 -nolisten tcp > "$WORK_DIR/xvfb.log" 2>&1 &
XVFB=$!
FIFO="$WORK_DIR/console.fifo"
rm -f "$FIFO" && mkfifo "$FIFO"
sleep infinity > "$FIFO" &   # keeps the console pipe open
HOLDER=$!
(cd "$WORK_DIR" && exec java -Xms512M -Xmx1G -jar "$PAPER_JAR" --nogui < "$FIFO" > "$WORK_DIR/server.log" 2>&1) &
SERVER=$!
CLIENT=
cleanup() { [ -n "$CLIENT" ] && kill "$CLIENT" 2>/dev/null || true; kill "$SERVER" 2>/dev/null || true; wait "$SERVER" 2>/dev/null || true; kill "$XVFB" "$HOLDER" 2>/dev/null || true; rm -f "$FIFO"; }
trap cleanup EXIT
until grep -q 'Done (' "$WORK_DIR/server.log" 2>/dev/null; do
  kill -0 "$SERVER" 2>/dev/null || { echo "server died"; tail -40 "$WORK_DIR/server.log"; exit 1; }
  sleep 1
done
echo "server up"

# The ground-truth agent: records the client's exact physics state every tick (see agent/).
AGENT_OUT="$WORK_DIR/agent-build"
rm -rf "$AGENT_OUT" && mkdir -p "$AGENT_OUT/classes"
(cd "$ROOT" && ./gradlew -q :whynotac-world:jar)
WORLD_JAR=$(find "$ROOT/whynotac-world/build/libs" -name 'whynotac-world-*.jar' ! -name '*-sources.jar' | head -1)
PROTOCOL_JAR=$(find "$ROOT/whynotac-protocol/build/libs" -name 'whynotac-protocol-*.jar' ! -name '*-sources.jar' | head -1)
javac --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp "$WORLD_JAR:$PROTOCOL_JAR" -d "$AGENT_OUT/classes" $(find "$HERE/agent/src" -name '*.java')
printf 'Premain-Class: dev.whynotac.truth.TruthAgent\n' > "$AGENT_OUT/manifest.txt"
jar --create --file "$AGENT_OUT/truth-agent.jar" --manifest "$AGENT_OUT/manifest.txt" -C "$AGENT_OUT/classes" .
rm -f "$WORK_DIR/truth.jsonl"

LAUNCH="$WORK_DIR/client-cache/launch.json"
CP=$(python3 -I -c "import json, os, sys; print(os.pathsep.join(json.load(open(sys.argv[1]))['classpath']))" "$LAUNCH")
INDEX=$(python3 -I -c "import json, sys; print(json.load(open(sys.argv[1]))['assetIndex'])" "$LAUNCH")
MAPPINGS=$(python3 -I -c "import json, sys; print(json.load(open(sys.argv[1]))['mappings'])" "$LAUNCH")
OFFLINE_UUID=$(python3 -I -c "import hashlib, uuid; print(uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:$NAME').digest(), version=3))")
(cd "$WORK_DIR/game" && DISPLAY="$DISPLAY_NUM" LIBGL_ALWAYS_SOFTWARE=1 exec java -Xmx1500M \
  --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED \
  -javaagent:"$AGENT_OUT/truth-agent.jar=$MAPPINGS,$WORK_DIR/truth.jsonl" \
  -cp "$CP" net.minecraft.client.main.Main \
  --username "$NAME" --uuid "$OFFLINE_UUID" --version 1.21.11 --gameDir "$WORK_DIR/game" \
  --assetsDir "$WORK_DIR/client-cache/assets" --assetIndex "$INDEX" --accessToken 0 --userType legacy \
  --width 854 --height 480 --quickPlayMultiplayer "127.0.0.1:$PORT" > "$WORK_DIR/client.log" 2>&1) &
CLIENT=$!
for _ in $(seq 1 180); do
  grep -q "$NAME joined the game" "$WORK_DIR/server.log" && break
  kill -0 "$CLIENT" 2>/dev/null || { echo "client died"; tail -40 "$WORK_DIR/client.log"; exit 1; }
  sleep 1
done
grep -q "$NAME joined the game" "$WORK_DIR/server.log" || { echo "client never joined"; tail -40 "$WORK_DIR/client.log"; exit 1; }
sleep 5 # let the world render and the player land
(cd "$HERE" && python3 -I drive.py "$DISPLAY_NUM") > "$WORK_DIR/drive.json"
# The world WhyNotAC rebuilt from the client's packets, compared with the server's (verify_realclient.py reads the line).
echo "whynotac world $NAME" > "$FIFO"
sleep 3
kill -0 "$CLIENT" 2>/dev/null || { echo "client died during the drive"; grep -h -A12 "^Description" "$WORK_DIR"/game/crash-reports/*.txt 2>/dev/null | head -30; tail -20 "$WORK_DIR/client.log"; exit 1; }
kill "$CLIENT"; wait "$CLIENT" 2>/dev/null || true; CLIENT=
sleep 2
kill "$SERVER"; wait "$SERVER" 2>/dev/null || true
trap - EXIT
kill "$XVFB" "$HOLDER" 2>/dev/null || true
rm -f "$FIFO"

RECORDING=$(find "$WORK_DIR/plugins/WhyNotAC/recordings" -name '*.wnrec' | head -1)
echo "--- recording: ${RECORDING:-<none>}"
JARS=$(find "$ROOT/whynotac-math/build/libs" "$ROOT/whynotac-protocol/build/libs" "$ROOT/whynotac-recording/build/libs" -name '*.jar' ! -name '*-sources.jar' | paste -sd:)
java -jar "$WORK_DIR/plugins/WhyNotAC.jar" dump "$RECORDING" > "$WORK_DIR/dump.jsonl"
java -cp "$JARS" "$HERE/RealClientCheck.java" "$RECORDING" 0.83 > "$WORK_DIR/check.json"
cat "$WORK_DIR/check.json"
echo "--- verification"
STATUS=0
python3 -I "$HERE/verify_realclient.py" "$WORK_DIR" || STATUS=1
echo "--- client truth"
python3 -I "$HERE/verify_truth.py" "$WORK_DIR" || STATUS=1
echo "--- block-state data"
# whynotac-world ships block-state data generated from this very client; regenerate it and require equality.
(cd "$WORK_DIR" && java -cp "$CP:$AGENT_OUT/classes:$WORLD_JAR:$PROTOCOL_JAR" dev.whynotac.truth.BlockDataDump "$MAPPINGS" "$WORK_DIR/block-states.txt.gz") \
  | grep -v '^Picked up' | tail -1
if cmp -s <(zcat "$WORK_DIR/block-states.txt.gz") <(zcat "$ROOT/whynotac-world/src/main/resources/dev/whynotac/world/block-states.txt.gz"); then
  echo "PASS whynotac-world's block-state data is exactly what the official client's registry yields"
else
  echo "FAIL whynotac-world's block-state data differs from the official client (copy $WORK_DIR/block-states.txt.gz over the resource)"
  STATUS=1
fi
exit $STATUS
