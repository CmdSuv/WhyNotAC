#!/usr/bin/env bash
# WhyNotAC load test: many honest bots on a real Paper 1.21.11 server, with and without WhyNotAC.
#
# usage: tools/load/run_load.sh <work-dir> <variant> [bots] [measure-seconds] [crowd]
#   variant  baseline  - no plugin (the reference)
#            whynotac  - WhyNotAC with session recording enabled
#            whynotac-noping / whynotac-noedge / whynotac-norec / whynotac-noflush - one component switched off
#            (decomposition)
#   layout   cluster (default): bots at least 8 blocks apart within 160 blocks (each sees a few dozen
#            others, like a busy spawn or arena); spread: far apart (per-player cost only);
#            crowd: all in one place (every bot sees every other one: N^2 entity traffic)
#
# LOAD_LEVEL_TYPE=normal generates real terrain (the client-world replica then holds realistic chunks);
# the default flat world keeps runs comparable. The world seed is fixed (LOAD_SEED), so the variants of a
# real-terrain comparison see the same terrain and the same generated mobs.
# The server runs on CPUs 0-1 and the bots on the remaining CPUs (taskset), so their load does not mix.
# After every bot has joined, the warm-up lasts until the tick time has settled (chunk generation done),
# then one measurement window: Paper's tick times, the server
# process's CPU time, a JFR profile (attributed to WhyNotAC by JfrAttribution.java), the heap by class
# and, with WhyNotAC, its own totals (/whynotac stats). Results: <work-dir>/load-<variant>.json
set -euo pipefail
WORK_DIR=$(mkdir -p "$1" && cd "$1" && pwd)
VARIANT=$2
BOTS=${3:-100}
MEASURE=${4:-60}
LAYOUT=${5:-cluster}
WARMUP=${LOAD_WARMUP:-30}            # minimum warm-up; it continues until MSPT has settled
SETTLE_MSPT=${LOAD_SETTLE_MSPT:-25}  # settled: Paper's 5 s average tick time below this, twice in a row
MAX_WARMUP=${LOAD_MAX_WARMUP:-300}
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
level-type=minecraft\:${LOAD_LEVEL_TYPE:-flat}
level-seed=${LOAD_SEED:-20261008}
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
rm -rf "$WORK_DIR/plugins/WhyNotAC/recordings" "$WORK_DIR/plugins/WhyNotAC/cases" "$WORK_DIR/plugins/WhyNotAC/cases.db"
if [ "${VARIANT%%-*}" = whynotac ]; then
  (cd "$ROOT" && ./gradlew -q :whynotac-paper:jar)
  mkdir -p "$WORK_DIR/plugins/WhyNotAC"
  printf 'recording:\n  enabled: true\n  max-age-days: 14\n  max-total-megabytes: 4096\n  max-queued-megabytes: 64\n' > "$WORK_DIR/plugins/WhyNotAC/config.yml"
  # Decomposition variants: whynotac-noping (no per-tick transactions), whynotac-noedge (game view only),
  # whynotac-norec (no recording), whynotac-noflush (no transactions around flushes).
  case "$VARIANT" in
    whynotac) ;;
    whynotac-noping) printf 'observation:\n  per-tick-transactions: false\n' >> "$WORK_DIR/plugins/WhyNotAC/config.yml" ;;
    whynotac-noedge) printf 'observation:\n  edge: false\n' >> "$WORK_DIR/plugins/WhyNotAC/config.yml" ;;
    whynotac-norec) sed -i 's/enabled: true/enabled: false/' "$WORK_DIR/plugins/WhyNotAC/config.yml" ;;
    whynotac-noflush) printf 'observation:\n  flush-transactions: false\n' >> "$WORK_DIR/plugins/WhyNotAC/config.yml" ;;
    *) echo "unknown variant $VARIANT"; exit 2 ;;
  esac
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
# Vanilla's locator bar (1.21.6+) does O(players^2) work on every move; large servers switch it off.
echo "gamerule locator_bar false" > "$FIFO"
echo "server up (pid $JAVA_PID, CPUs $SERVER_CPUS), $BOTS bots on CPUs $BOT_CPUS"

# Bots in several processes; every bot runs until the end of the measurement window.
BOT_SECONDS=$((MAX_WARMUP + MEASURE + 120))   # an upper bound: the bots stop when the stop file appears
STOP_FILE="$OUT.stop"
rm -f "$STOP_FILE"
PER=$(( (BOTS + PROCESSES - 1) / PROCESSES ))
for ((p = 0; p < PROCESSES; p++)); do
  FIRST=$((p * PER)); COUNT=$(( BOTS - FIRST < PER ? BOTS - FIRST : PER ))
  [ "$COUNT" -gt 0 ] || continue
  taskset -c "$BOT_CPUS" python3 -I "$HERE/load_bots.py" 127.0.0.1 "$PORT" Bot "$FIRST" "$COUNT" "$BOT_SECONDS" 0.15 "$STOP_FILE" > "$OUT-bots-$p.json" 2> "$OUT-bots-$p.err" &
  BOT_PIDS+=($!)
done
for _ in $(seq 1 240); do
  joined=$(grep -c 'joined the game' "$OUT-server.log" || true)
  [ "$joined" -ge "$BOTS" ] && break
  sleep 1
done
echo "joined: $(grep -c 'joined the game' "$OUT-server.log" || true)/$BOTS"
case "$LAYOUT" in
  spread) echo "spreadplayers 0 0 80 1200 false @a" > "$FIFO" ;;   # far enough apart that bots do not see each other
  cluster) echo "spreadplayers 0 0 8 160 false @a" > "$FIFO" ;;
  crowd) ;;
  *) echo "unknown layout $LAYOUT"; exit 2 ;;
esac
sleep "$WARMUP"
WARMED=$WARMUP; SETTLED=0
while [ "$WARMED" -lt "$MAX_WARMUP" ] && [ "$SETTLED" -lt 2 ]; do
  echo "mspt" > "$FIFO"; sleep 5; WARMED=$((WARMED + 5))
  AVG5=$(sed -E 's/\x1b\[[0-9;]*m//g' "$OUT-server.log" | grep -A1 'Server tick times' | tail -1 | grep -oE '[0-9.]+/[0-9.]+/[0-9.]+' | head -1 | cut -d/ -f1)
  if [ -n "$AVG5" ] && awk -v a="$AVG5" -v t="$SETTLE_MSPT" 'BEGIN { exit !(a < t) }'; then SETTLED=$((SETTLED + 1)); else SETTLED=0; fi
done
echo "warm-up: ${WARMED}s, settled=$SETTLED (last 5 s MSPT ${AVG5:-?})"
# A cow where each bot stands (no AI, invulnerable: it stays and takes nothing): the bots attack it when near.
echo "execute as @a at @s run summon minecraft:cow ~ ~ ~ {NoAI:1b,Invulnerable:1b,Silent:1b,PersistenceRequired:1b}" > "$FIFO"
sleep 2
echo "$WARMED $SETTLED" > "$OUT-warmup.txt"

cpu_ticks() { awk '{print $14 + $15}' "/proc/$JAVA_PID/stat"; }
CPU_START=$(cpu_ticks); T_START=$(date +%s.%N)
jcmd "$JAVA_PID" JFR.start name=load settings=profile filename="$OUT.jfr" > /dev/null
sleep "$MEASURE"
jcmd "$JAVA_PID" JFR.stop name=load > /dev/null
CPU_END=$(cpu_ticks); T_END=$(date +%s.%N)
echo "mspt" > "$FIFO"
echo "tps" > "$FIFO"
[ "${VARIANT%%-*}" = whynotac ] && echo "whynotac stats" > "$FIFO"
sleep 2
jcmd "$JAVA_PID" GC.class_histogram > "$OUT-histogram.txt" 2>/dev/null || true
touch "$STOP_FILE"   # every bot finishes its current second and reports whether it was still connected
for pid in "${BOT_PIDS[@]}"; do wait "$pid" || true; done
BOT_PIDS=()
sleep 3
[ "${VARIANT%%-*}" = whynotac ] && echo "whynotac stats" > "$FIFO" && sleep 2   # after every bot has left
echo "stop" > "$FIFO"
wait "$SERVER_SHELL" || true
trap - EXIT
kill "$HOLDER" 2>/dev/null || true
rm -f "$FIFO"

# Every attack the bots made, assessed over the recordings as the attack-reach check does.
rm -f "$OUT-reach.jsonl"
if [ "${VARIANT%%-*}" = whynotac ] && [ -d "$WORK_DIR/plugins/WhyNotAC/recordings" ]; then
  java -Xmx1G -cp "$WORK_DIR/plugins/WhyNotAC.jar" "$ROOT/tools/realclient/ReachAudit.java" "$WORK_DIR/plugins/WhyNotAC/recordings" > "$OUT-reach.jsonl" || true
fi
# JFR's per-thread load is relative to the CPUs the server may use, not to the machine.
java -Xmx1G "$HERE/JfrAttribution.java" "$OUT.jfr" "$(taskset -c "$SERVER_CPUS" nproc)" > "$OUT-jfr.json"
python3 -I "$HERE/summarize_load.py" "$VARIANT" "$LAYOUT" "$BOTS" "$MEASURE" "$CPU_START" "$CPU_END" "$T_START" "$T_END" "$(getconf CLK_TCK)" \
  "$OUT-server.log" "$OUT-jfr.json" "$OUT-histogram.txt" "$OUT" > "$OUT.json"
cat "$OUT.json"
