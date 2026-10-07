#!/usr/bin/env bash
# WhyNotAC load test: many honest bots on a real Paper 1.21.11 server, with and without WhyNotAC.
#
# usage: tools/load/run_load.sh <work-dir> <variant> [bots] [measure-seconds] [crowd]
#   variant  baseline  - no plugin (the reference)
#            whynotac  - WhyNotAC with session recording enabled
#   crowd    spread (default): bots spread far apart (per-player cost, little entity traffic)
#            crowd: all bots in one place (every bot sees every other one: N^2 entity traffic)
#
# The server runs on CPUs 0-1 and the bots on the remaining CPUs (taskset), so their load does not mix.
# After every bot has joined and a warm-up, one measurement window: Paper's tick times, the server
# process's CPU time, a JFR profile (attributed to WhyNotAC by JfrAttribution.java), the heap by class
# and, with WhyNotAC, its own totals (/whynotac stats). Results: <work-dir>/load-<variant>.json
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
VARIANT=$2
BOTS=${3:-100}
MEASURE=${4:-60}
LAYOUT=${5:-spread}
WARMUP=${LOAD_WARMUP:-60}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PAPER_JAR=paper-1.21.11-132.jar
PAPER_SHA256=5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba
PORT=25597
CPUS=$(nproc)
SERVER_CPUS=${LOAD_SERVER_CPUS:-0-1}
BOT_CPUS=${LOAD_BOT_CPUS:-2-$((CPUS - 1))}
PROCESSES=${LOAD_BOT_PROCESSES:-4}
OUT="$WORK_DIR/load-$VARIANT"

if [ ! -f "$WORK_DIR/$PAPER_JAR" ]; then
  curl -fsS --max-time 300 -o "$WORK_DIR/$PAPER_JAR" "https://fill-data.papermc.io/v1/objects/$PAPER_SHA256/$PAPER_JAR"
fi
echo "$PAPER_SHA256  $WORK_DIR/$PAPER_JAR" | sha256sum -c --quiet -
echo 'eula=true' > "$WORK_DIR/eula.txt"
rm -rf "$WORK_DIR/world" "$WORK_DIR/world_nether" "$WORK_DIR/world_the_end" "$WORK_DIR/usercache.json" "$WORK_DIR/logs"
cat > "$WORK_DIR/server.properties" <<PROPS
online-mode=false
server-port=$PORT
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
spawn-monsters=false
spawn-animals=false
difficulty=peaceful
max-players=1000
motd=WhyNotAC load test
PROPS
# All bots connect from 127.0.0.1: switch off Bukkit's per-address connection throttle.
printf 'settings:\n  connection-throttle: -1\n' > "$WORK_DIR/bukkit.yml"
mkdir -p "$WORK_DIR/plugins"
find "$WORK_DIR/plugins" -maxdepth 1 -name '*.jar' -delete
rm -rf "$WORK_DIR/plugins/WhyNotAC/recordings"
if [ "$VARIANT" = whynotac ]; then
  (cd "$ROOT" && ./gradlew -q :whynotac-paper:jar)
  mkdir -p "$WORK_DIR/plugins/WhyNotAC"
  printf 'recording:\n  enabled: true\n  max-age-days: 14\n  max-total-megabytes: 4096\n  max-queued-megabytes: 64\n' > "$WORK_DIR/plugins/WhyNotAC/config.yml"
  find "$ROOT/whynotac-paper/build/libs" -name 'whynotac-paper-*.jar' ! -name '*-sources.jar' -exec cp {} "$WORK_DIR/plugins/WhyNotAC.jar" \;
elif [ "$VARIANT" != baseline ]; then
  echo "unknown variant $VARIANT"; exit 2
fi

FIFO="$WORK_DIR/console.fifo"
rm -f "$FIFO" && mkfifo "$FIFO"
sleep infinity > "$FIFO" &
HOLDER=$!
(cd "$WORK_DIR" && exec taskset -c "$SERVER_CPUS" java -Xms2G -Xmx2G -XX:FlightRecorderOptions:stackdepth=256 \
  -jar "$PAPER_JAR" --nogui < "$FIFO" > "$OUT-server.log" 2>&1) &
SERVER_SHELL=$!
BOT_PIDS=()
cleanup() {
  for pid in "${BOT_PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  echo "stop" > "$FIFO" 2>/dev/null || true
  wait "$SERVER_SHELL" 2>/dev/null || true
  kill "$HOLDER" 2>/dev/null || true
  rm -f "$FIFO"
}
trap cleanup EXIT
until grep -q 'Done (' "$OUT-server.log" 2>/dev/null; do
  kill -0 "$SERVER_SHELL" 2>/dev/null || { echo "server died"; tail -40 "$OUT-server.log"; exit 1; }
  sleep 1
done
JAVA_PID=$(pgrep -f -n "java .*$PAPER_JAR")
echo "server up (pid $JAVA_PID, CPUs $SERVER_CPUS), $BOTS bots on CPUs $BOT_CPUS"

# Bots in several processes; every bot runs until the end of the measurement window.
BOT_SECONDS=$((WARMUP + MEASURE + 30))
PER=$(( (BOTS + PROCESSES - 1) / PROCESSES ))
for ((p = 0; p < PROCESSES; p++)); do
  FIRST=$((p * PER)); COUNT=$(( BOTS - FIRST < PER ? BOTS - FIRST : PER ))
  [ "$COUNT" -gt 0 ] || continue
  taskset -c "$BOT_CPUS" python3 -I "$HERE/load_bots.py" 127.0.0.1 "$PORT" Bot "$FIRST" "$COUNT" "$BOT_SECONDS" 0.15 > "$OUT-bots-$p.json" 2> "$OUT-bots-$p.err" &
  BOT_PIDS+=($!)
done
for _ in $(seq 1 240); do
  joined=$(grep -c 'joined the game' "$OUT-server.log" || true)
  [ "$joined" -ge "$BOTS" ] && break
  sleep 1
done
echo "joined: $(grep -c 'joined the game' "$OUT-server.log" || true)/$BOTS"
if [ "$LAYOUT" = spread ]; then
  echo "spreadplayers 0 0 80 2000 false @a" > "$FIFO"   # far enough apart that bots do not see each other
fi
sleep "$WARMUP"

cpu_ticks() { awk '{print $14 + $15}' "/proc/$JAVA_PID/stat"; }
CPU_START=$(cpu_ticks); T_START=$(date +%s.%N)
jcmd "$JAVA_PID" JFR.start name=load settings=profile filename="$OUT.jfr" > /dev/null
sleep "$MEASURE"
jcmd "$JAVA_PID" JFR.stop name=load > /dev/null
CPU_END=$(cpu_ticks); T_END=$(date +%s.%N)
echo "mspt" > "$FIFO"
echo "tps" > "$FIFO"
[ "$VARIANT" = whynotac ] && echo "whynotac stats" > "$FIFO"
sleep 2
jcmd "$JAVA_PID" GC.class_histogram > "$OUT-histogram.txt" 2>/dev/null || true
for pid in "${BOT_PIDS[@]}"; do wait "$pid" || true; done
BOT_PIDS=()
sleep 3
[ "$VARIANT" = whynotac ] && echo "whynotac stats" > "$FIFO" && sleep 2   # after every bot has left
echo "stop" > "$FIFO"
wait "$SERVER_SHELL" || true
trap - EXIT
kill "$HOLDER" 2>/dev/null || true
rm -f "$FIFO"

java -Xmx1G "$HERE/JfrAttribution.java" "$OUT.jfr" > "$OUT-jfr.json"
python3 -I "$HERE/summarize_load.py" "$VARIANT" "$LAYOUT" "$BOTS" "$MEASURE" "$CPU_START" "$CPU_END" "$T_START" "$T_END" "$(getconf CLK_TCK)" \
  "$OUT-server.log" "$OUT-jfr.json" "$OUT-histogram.txt" "$OUT" > "$OUT.json"
cat "$OUT.json"
