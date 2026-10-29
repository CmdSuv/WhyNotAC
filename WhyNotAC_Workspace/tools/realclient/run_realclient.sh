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
# Options that would otherwise stop the client on a screen (first-run onboarding, warnings) or slow it down; auto-jump
# on, so the course's step-ups exercise it (the engine and the movement check model it).
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
autoJump:true
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
# The movement course, east of everything else along z = -1..1 (the ground's top is y = -60). Built before the
# client joins, so it arrives with the chunks: the physics check needs the world as the client had it.
for c in "forceload add 32 -16 191 16" "gamerule random_tick_speed 0" \
  "fill 44 -60 -1 44 -60 1 minecraft:smooth_stone_slab[type=bottom]" "fill 45 -60 -1 46 -60 1 minecraft:stone" \
  "fill 46 -59 -1 46 -59 1 minecraft:smooth_stone_slab[type=bottom]" \
  "fill 52 -61 -1 62 -61 1 minecraft:ice" "fill 70 -61 -1 74 -61 1 minecraft:soul_sand" "fill 82 -60 -2 82 -59 2 minecraft:stone" \
  "fill 88 -60 -1 91 -60 1 minecraft:stone" "fill 98 -58 -1 101 -58 1 minecraft:stone" \
  "fill 124 -60 -1 124 -60 1 minecraft:oak_stairs[facing=east]" "fill 125 -60 -1 127 -60 1 minecraft:stone" \
  "fill 136 -60 -1 139 -60 1 minecraft:stone" "fill 143 -60 -3 146 -60 3 minecraft:stone" "fill 170 -60 -1 170 -59 1 minecraft:stone" \
  "fill 62 -60 -11 62 -55 -9 minecraft:stone" "fill 61 -60 -10 61 -57 -10 minecraft:ladder[facing=west]" \
  "fill 67 -60 -11 67 -55 -9 minecraft:stone" "fill 66 -60 -10 66 -57 -10 minecraft:vine[east=true]" \
  "fill 72 -60 -11 72 -58 -9 minecraft:stone" "setblock 71 -60 -10 minecraft:ladder[facing=west]" \
  "setblock 71 -59 -10 minecraft:oak_trapdoor[facing=west,open=true,half=bottom]" \
  "fill 80 -61 -16 85 -61 -13 minecraft:slime_block" "setblock 91 -60 -15 minecraft:red_bed[facing=east,part=head]" \
  "setblock 90 -60 -15 minecraft:red_bed[facing=east,part=foot]" \
  "fill 100 -60 5 106 -60 5 minecraft:oak_fence" "setblock 109 -60 5 minecraft:oak_fence" \
  "fill 100 -60 8 106 -60 8 minecraft:cobblestone_wall" "fill 100 -60 11 104 -60 11 minecraft:oak_fence_gate[facing=north]" \
  "fill 120 -63 -12 129 -61 -7 minecraft:water" \
  "fill 133 -60 -11 143 -59 -11 minecraft:stone" "fill 133 -60 -9 143 -59 -9 minecraft:stone" "fill 133 -60 -10 133 -59 -10 minecraft:stone" \
  "setblock 134 -60 -10 minecraft:water" \
  "fill 161 -60 -11 161 -56 -9 minecraft:stone" "setblock 160 -56 -10 minecraft:water" \
  "fill 175 -59 -10 180 -59 -10 minecraft:stone" "fill 175 -60 -11 180 -60 -11 minecraft:stone" "fill 175 -60 -9 180 -60 -9 minecraft:stone" \
  "fill 114 -60 14 116 -59 14 minecraft:cobweb" "fill 122 -60 14 124 -60 14 minecraft:sweet_berry_bush[age=0]" \
  "fill 132 -60 14 132 -53 14 minecraft:honey_block" \
  "fill 141 -61 13 143 -56 15 minecraft:glass" "setblock 142 -61 14 minecraft:soul_sand" "fill 142 -60 14 142 -56 14 minecraft:bubble_column[drag=false]" \
  "fill 149 -61 13 151 -56 15 minecraft:glass" "setblock 150 -61 14 minecraft:magma_block" "fill 150 -60 14 150 -56 14 minecraft:bubble_column[drag=true]" \
  "fill 159 -61 11 171 -57 17 minecraft:glass" "fill 160 -60 12 170 -57 16 minecraft:water" \
  "fill 163 -61 12 164 -61 16 minecraft:soul_sand" "fill 167 -61 12 168 -61 16 minecraft:magma_block" \
  "fill 163 -60 12 164 -57 16 minecraft:bubble_column[drag=false]" "fill 167 -60 12 168 -57 16 minecraft:bubble_column[drag=true]" \
  "fill 100 -64 24 110 -61 29 minecraft:glass" "fill 101 -63 25 109 -61 28 minecraft:lava" \
  "fill 113 -61 26 118 -61 26 minecraft:air" "setblock 113 -61 26 minecraft:lava" \
  "fill 132 -60 26 132 -56 26 minecraft:scaffolding[distance=0,bottom=false]" "setblock 66 -60 22 minecraft:chest[facing=north]" \
  "setblock 76 -59 22 minecraft:dirt" \
  "setblock 133 -56 26 minecraft:scaffolding[distance=1,bottom=true]" "setblock 134 -56 26 minecraft:scaffolding[distance=2,bottom=true]" \
  "setblock 135 -56 26 minecraft:scaffolding[distance=3,bottom=true]" \
  "fill 60 -61 29 65 -61 31 minecraft:powder_snow" "fill 68 -62 29 70 -61 31 minecraft:powder_snow" \
  "forceload add 32 -48 127 -17" "forceload add -120 -72 -40 -56" "forceload add -95 -106 -63 -74" "fill 40 -62 -40 70 -61 -28 minecraft:water" "fill 60 -63 -37 66 -63 -31 minecraft:water" \
  "forceload add -158 -72 -121 -56" "fill -147 -61 -67 -136 -61 -62 minecraft:lava" "fill -129 -61 -67 -126 -61 -62 minecraft:air" \
  "fill -129 -61 -67 -129 -61 -62 minecraft:lava" \
  "fill 76 -61 -40 87 -61 -28 minecraft:ice" "fill 88 -61 -40 100 -61 -28 minecraft:blue_ice" "fill 101 -60 -40 101 -59 -28 minecraft:stone" \
  "fill 76 -61 -26 84 -61 -24 minecraft:slime_block" \
  "execute in minecraft:the_nether run forceload add -16 -16 31 31" \
  "execute in minecraft:the_nether run fill -1 99 -1 21 108 21 minecraft:glass hollow" \
  "execute in minecraft:the_nether run fill 0 100 0 20 102 20 minecraft:glass" \
  "execute in minecraft:the_nether run fill 5 100 5 12 102 8 minecraft:lava" \
  "execute in minecraft:the_nether run fill 13 102 10 20 102 10 minecraft:air" \
  "execute in minecraft:the_nether run setblock 13 102 10 minecraft:lava"; do
  echo "$c" > "$FIFO"
done
sleep 2

# The ground-truth agent: records the client's exact physics state every tick (see agent/).
AGENT_OUT="$WORK_DIR/agent-build"
rm -rf "$AGENT_OUT" && mkdir -p "$AGENT_OUT/classes"
(cd "$ROOT" && ./gradlew -q :whynotac-world:jar :whynotac-physics:jar :whynotac-recording:jar)
WORLD_JAR=$(find "$ROOT/whynotac-world/build/libs" -name 'whynotac-world-*.jar' ! -name '*-sources.jar' | head -1)
PROTOCOL_JAR=$(find "$ROOT/whynotac-protocol/build/libs" -name 'whynotac-protocol-*.jar' ! -name '*-sources.jar' | head -1)
PHYSICS_JAR=$(find "$ROOT/whynotac-physics/build/libs" -name 'whynotac-physics-*.jar' ! -name '*-sources.jar' | head -1)
MATH_JAR=$(find "$ROOT/whynotac-math/build/libs" -name 'whynotac-math-*.jar' ! -name '*-sources.jar' | head -1)
RECORDING_JAR=$(find "$ROOT/whynotac-recording/build/libs" -name 'whynotac-recording-*.jar' ! -name '*-sources.jar' | head -1)
WHYNOTAC_CP="$WORLD_JAR:$PROTOCOL_JAR:$PHYSICS_JAR:$MATH_JAR"
javac --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp "$WHYNOTAC_CP" -d "$AGENT_OUT/classes" $(find "$HERE/agent/src" -name '*.java')
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
# The world spawn is random within its radius: place the player where everything below expects it (the entities are
# summoned around it, and the reach stage's line to its husk must stay clear of them).
echo "tp $NAME 0.5 -60 0.5 0 0" > "$FIFO"
sleep 2
# Entities the real client must hold exactly as the server tracks them (no AI: only the commands move them).
# Hostile mobs cannot be summoned on peaceful. Natural monster spawning is a game rule in 1.21.11 (the
# server property no longer applies): off first, or a slime from a slime chunk may kill the player.
echo "gamerule spawn_monsters false" > "$FIFO"
echo "difficulty easy" > "$FIFO"
echo "execute at $NAME run summon minecraft:husk ~3 ~ ~3 {NoAI:1b,PersistenceRequired:1b,Tags:[\"wnz\"]}" > "$FIFO"
echo "execute at $NAME run summon minecraft:villager ~-3 ~ ~3 {NoAI:1b,PersistenceRequired:1b}" > "$FIFO"
echo "execute at $NAME run summon minecraft:villager ~-3 ~ ~-3 {NoAI:1b,PersistenceRequired:1b,Age:-24000}" > "$FIFO"
echo "execute at $NAME run summon minecraft:armor_stand ~3 ~ ~-3" > "$FIFO"
# Sizes from data: a slime's (no AI: it cannot hurt the player) and an interaction's. The slime stays off the reach
# stage's line (x 5.5 to 10.5 at z 0.5): a player placed in it would be pushed off the line.
echo "execute at $NAME run summon minecraft:slime ~-5 ~ ~-4 {NoAI:1b,PersistenceRequired:1b,Size:2}" > "$FIFO"
echo "execute at $NAME run summon minecraft:interaction ~-5 ~ ~0.5 {width:1.75f,height:0.5f}" > "$FIFO"
sleep 1; echo "execute at $NAME run tp @e[tag=wnz,limit=1] ~2.3172 ~ ~3.7071 45 10" > "$FIFO"
# Items: a sword (hotbar 0), a stick with an attack range of its own (hotbar 1; the driver moves it to hotbar 4
# with real clicks, which carry the client's own hash of the range) and a shield in the off hand.
echo "give $NAME minecraft:diamond_sword" > "$FIFO"
echo "give $NAME minecraft:stick[minecraft:attack_range={max_reach:4.5f,hitbox_margin:0.25f}]" > "$FIFO"
echo "item replace entity $NAME weapon.offhand with minecraft:shield" > "$FIFO"
sleep 1
(cd "$HERE" && python3 -I drive.py "$DISPLAY_NUM") > "$WORK_DIR/drive.json"
# The world WhyNotAC rebuilt from the client's packets, compared with the server's (verify_realclient.py reads the line).
echo "whynotac world $NAME" > "$FIFO"
echo "whynotac entities $NAME" > "$FIFO"
echo "whynotac inventory $NAME" > "$FIFO"
echo "whynotac self $NAME" > "$FIFO"
sleep 3
# Reach at its limits, with the real pick and attack: an invulnerable husk (no AI, so it stays) east of
# the player, the player facing it (yaw -90), its eye level inside the husk's box, so the crosshair ray
# meets the box's west face (x 10.2) exactly at the horizontal distance. The stick (selected by the
# drive) reaches 4.5 + 0.25: it attacks from 4.70. The sword (hotbar 0) picks strictly within 3: it
# attacks from 2.98 and cannot from 3.05 (the client sends nothing).
echo "summon minecraft:husk 10.5 -60 0.5 {NoAI:1b,PersistenceRequired:1b,Invulnerable:1b,Silent:1b,Tags:[\"wnr\"]}" > "$FIFO"
sleep 1
reach() { echo "tp $NAME $1 -60 0.5 -90 0" > "$FIFO"; sleep 1.5; python3 -I "$HERE/reach.py" "$DISPLAY_NUM" click; sleep 1.2; }
reach 5.5
python3 -I "$HERE/reach.py" "$DISPLAY_NUM" key 1; sleep 0.6
reach 7.22
reach 7.15
# The movement course: each leg places the player (yaw, pitch 0) and presses keys; every tick is compared with the
# physics engine afterwards (MovementTruth). Step-ups (slab, block, slab on block) and a drop; walking, sprinting and
# sliding on ice; soul sand; a wall approached at an angle; sneaking to a platform's edge, then releasing shift there and
# walking off; jumping under a low ceiling; a double-tapped sprint jumping; shift pressed inside the double-tap window;
# stairs; one-block steps met head on and at an angle (auto-jump).
leg() { echo "tp $NAME $1 $2 0.5 $3 0" > "$FIFO"; sleep 1.5; shift 3; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
leg 41.5 -60 -90 w/3.0
leg 50.5 -60 -90 w/1.0 w,Control_L/1.0 -/1.5
leg 68.5 -60 -90 w/2.5
leg 79.5 -60 -75 w/1.5 -/0.3 w,Control_L/0.8
leg 88.5 -59 -90 Shift_L,w/4.0 w/0.6
leg 97.5 -60 -90 w/1.5/space
leg 104.5 -60 -90 w/0.1 -/0.1 w/1.5/space
leg 108.5 -60 -90 w/0.1 -/0.05 Shift_L/0.5 -/0.3 w/0.1 -/0.1 w/1.0
leg 121.5 -60 -90 w/2.0
leg 132.5 -60 -90 w/2.0
leg 140.5 -60 -60 w/1.5
# Effects the step reads: jump boost II (sprint jumps; auto-jump meets a two-block wall at x 170), slow falling (jumps
# and a six-block drop), levitation II for a second right after the leg starts (rise, then fall back), and blindness
# (no sprinting: sprint key and a double tap).
effect() { echo "effect $*" > "$FIFO"; sleep 0.5; }
effect give $NAME minecraft:jump_boost 60 1 true
leg 152.5 -60 -90 w,Control_L/1.5/space -/1.0
leg 165.5 -60 -90 w/2.5
effect clear $NAME minecraft:jump_boost
effect give $NAME minecraft:slow_falling 60 0 true
leg 152.5 -60 -90 w/1.5/space -/0.8
leg 180.5 -54 -90 -/2.5
effect clear $NAME minecraft:slow_falling
echo "tp $NAME 185.5 -60 0.5 -90 0" > "$FIFO"; sleep 1.5
effect give $NAME minecraft:levitation 1 1 true
(cd "$HERE" && python3 -I course.py "$DISPLAY_NUM" -/3.0)
effect give $NAME minecraft:blindness 60 0 true
leg 152.5 -60 -90 w,Control_L/1.0 -/0.3 w/0.1 -/0.1 w/1.0
effect clear $NAME minecraft:blindness
# Climbing, at z = -9.5: a ladder on a wall walked into, held with shift, jumped on and slid down; vines likewise; an
# open trapdoor over a ladder facing its way (a ladder too). Ladders and trapdoors do not suffocate: walking against
# them is no push.
climb() { echo "tp $NAME $1 -60 -9.5 -90 0" > "$FIFO"; sleep 1.5; shift; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
climb 59.5 w/2.0 Shift_L,w/0.6 Shift_L/1.0 -/1.5 w/0.3 space/1.0 -/1.0
climb 64.5 w/2.0 Shift_L/0.8 -/1.5
climb 69.5 w/1.5 -/1.0
# Placed 0.15 into a wall: the client pushes itself out (moveTowardsClosestSpace), then walks back into it.
climb 71.85 -/1.0 w/1.0
# Bounces, at z = -14.5: a slime pad in the ground (x 80..85) walked onto (its step slows a player not sneaking) and
# walked across sneaking,
# jumped across, dropped onto from five blocks (it bounces back, again and again) and dropped onto holding shift (no
# bounce); a bed (x 90..91) jumped onto, dropped onto (0.66 of the fall back) and dropped onto holding shift.
bounce() { echo "tp $NAME $1 $2 -14.5 -90 0" > "$FIFO"; sleep 1.5; shift 2; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
bounce 77.5 -60 w/2.0 -/0.5
bounce 79.5 -60 Shift_L,w/2.0 -/0.3
bounce 78.5 -60 w/1.8/space -/1.5
bounce 82.5 -55 -/3.0
bounce 83.5 -55 Shift_L/2.0 -/0.5
bounce 87.5 -60 w/0.6/space -/1.5
bounce 90.5 -55 -/2.5
bounce 91.5 -55 Shift_L/2.0 -/0.5
# Fences, walls and fence gates, 1.5 high, at x 100..106: a fence line (z 5), a lone post (x 109), a wall line (z 8) and
# closed gates (z 11) walked along on top, sneaking to their edge and jumped along until the player drops off; the fence
# walked into from the ground and jumped against (too high to climb, to auto-jump or to step up).
fences() { echo "tp $NAME $1 $2 $3 -90 0" > "$FIFO"; sleep 1.5; shift 3; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
fences 100.5 -58.5 5.5 w/1.0 -/0.3 Shift_L,w/1.5 -/0.3 w/1.5/space -/1.0
fences 109.5 -58.5 5.5 -/0.5 Shift_L,w/0.8 -/0.5
fences 100.5 -58.5 8.5 w/1.0 -/0.3 Shift_L,w/1.5 -/0.3 w/1.5/space -/1.0
fences 100.5 -58.5 11.5 w/0.8 -/0.3 Shift_L,w/1.2 -/0.3 w/1.0/space -/1.0
fences 97.5 -60 5.5 w/1.0 w/1.0/space -/0.5
# Water, at z = -9.5: a pool three deep (x 120..129) walked into, crossed jumping, sunk into with shift, swum across
# sprinting under water (level, looking down, looking up, with dolphin's grace) and climbed out of at its far wall; a
# channel one block wide (x 134..141) its source floods eastward, walked against the current, stood in and sprinted
# through (too shallow to sprint in); a waterfall against a stone wall (x 160) stood in and jumped in.
water() { echo "tp $NAME $1 $2 -9.5 $3 $4" > "$FIFO"; sleep 1.5; shift 4; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
water 119.5 -60 -90 0 w/1.5 -/1.0 w/1.0/space -/0.5 Shift_L/1.0 -/0.5 w/2.0/space -/1.0
water 121.5 -62.5 -90 0 w,Control_L/1.5 -/1.0
water 121.5 -61.5 -90 30 w,Control_L/1.5 -/0.5
water 121.5 -62.8 -90 -30 w,Control_L/1.5 -/1.0
echo "effect give $NAME minecraft:dolphins_grace 30 0 true" > "$FIFO"
water 121.5 -62.5 -90 0 w,Control_L/1.5 w/0.5 -/0.5
echo "effect clear $NAME minecraft:dolphins_grace" > "$FIFO"
water 142.5 -60 90 0 w/2.5 -/1.5 Shift_L/0.5 -/0.5
water 143.5 -60 90 0 w,Control_L/2.0 -/1.0
water 160.5 -60 -90 0 -/1.5 space/1.0 -/1.0 w/1.0 -/0.5
# Crawling: placed in a tunnel one block high (x 175..180), the player lies down (no pose fits but swimming's), crawls
# out at the sneaking speed, jumps under the ceiling, and stands up beyond it.
water 176.5 -60 -90 0 -/0.5 w/0.8 w/0.6/space w/1.2 -/0.5
# Blocks that move the player from inside, at z = 14.5: cobwebs two high (x 114..116) walked and sprinted into, jumped in
# and fallen into from five blocks, then again under weaving; sweet berry bushes (x 122..124, age 0: they do not harm, and
# random ticks are off so they stay so) walked and sprinted through and jumped in; a honey column (x 132) slid down from
# its top pressed against it, and jumped against from the ground; bubble columns walled in glass, rising over soul sand
# (x 142: the player shoots out of its open top and falls back, sinks with shift, jumps) and dragging down over magma
# (x 150, with fire resistance from here on: its floor, lava and the fire it sets do not hurt); a pool four deep (x 160..170) over two rows of columns rising
# from soul sand (x 163..164) and two dragging down over magma (x 167..168), swum and sprint-swum across: the box meets
# several columns at once, each once a tick.
inside() { echo "tp $NAME $1 $2 14.5 -90 0" > "$FIFO"; sleep 1.5; shift 2; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
inside 111.5 -60 w/2.5 -/0.5 w/1.0/space -/0.5
inside 111.5 -60 w,Control_L/2.0 -/0.5
inside 115.5 -55 -/2.5
effect give $NAME minecraft:weaving 60 0 true
inside 111.5 -60 w/2.0 -/0.5
inside 115.5 -55 -/2.0
effect clear $NAME minecraft:weaving
inside 119.5 -60 w/2.0 -/0.5
inside 119.5 -60 w,Control_L/2.0 -/0.5
inside 120.5 -60 w/0.5 w/1.0/space -/0.5
inside 131.5 -53 w/3.0 -/0.5
inside 129.5 -60 w/1.0 w/1.5/space -/0.5
inside 142.5 -59 -/3.0 Shift_L/1.0 -/0.5 space/1.5 -/0.5
effect give $NAME minecraft:fire_resistance 1000 0 true
inside 150.5 -57 -/2.0 space/1.0 -/0.5
inside 160.5 -59 w/5.0 -/0.5
inside 160.5 -60 w,Control_L/4.0 -/0.5
# Lava, at z = 26.5: a pool three deep sunk in the ground (x 101..109, glass-walled) walked into, crossed jumping and
# left at its far wall, placed in and jumped in (jumpInLiquid), sprinted in; a channel in the ground (x 113..118) its
# source floods three blocks east (the overworld's slow lava), walked into from the east against the current, stood
# in and jumped in.
lava() { echo "tp $NAME $1 $2 26.5 $3 0" > "$FIFO"; sleep 1.5; shift 3; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
lava 98.5 -60 -90 w/2.0 -/1.0 w/1.5/space -/0.5 Shift_L/1.0 -/0.5
lava 104.5 -62 -90 space/2.0 -/1.0 w,Control_L/1.5 -/1.0
lava 119.5 -60 90 w/3.0 -/1.5 space/1.0 -/0.5
# Scaffolding, at z = 26.5: a tower five high (x 132) walked into and climbed jumping, stood on (its stable top), with a
# bridge of three hanging off its top eastward (unsupported: each the slab of its floor below whoever is inside it);
# the bridge walked along on top, sunk into holding shift (no stable top while descending: onto the floor's slab), stood
# in, and the tower sunk through holding shift.
scaff() { echo "tp $NAME $1 $2 26.5 -90 0" > "$FIFO"; sleep 1.5; shift 2; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
scaff 130.5 -60 w/0.6 space/3.0 -/1.0 w/0.5 -/1.0
scaff 133.5 -55 Shift_L/1.5 -/1.0 w/0.5 -/1.0
scaff 134.5 -55.5 -/1.0 Shift_L/1.0 d/0.4 -/1.0
scaff 132.5 -55 Shift_L/3.0 -/1.0
# Items in use, on a flat lane at z = 10.5 (right mouse button): a golden apple eaten while sprinting and walking (its
# use ends on the server after 32 ticks), the off hand's shield raised behind a sword that has no use (walking, the
# sprint key and a double tap while blocking, sneaking; a tap of W, a short block, W again inside the double-tap
# window), a spear charged while a sprint starts (its use_effects let it), and a bow drawn while walking and released.
lane() { echo "tp $NAME $1 -60 10.5 -90 0" > "$FIFO"; sleep 1.5; shift; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
echo "item replace entity $NAME weapon.mainhand with minecraft:golden_apple 8" > "$FIFO"; sleep 0.5
lane 40.5 w,Control_L/0.8 w,Control_L,Button3/1.2 w,Button3/1.2 w/1.0
echo "item replace entity $NAME weapon.mainhand with minecraft:diamond_sword" > "$FIFO"; sleep 0.5
lane 40.5 w/0.5 w,Button3/1.0 w,Button3,Control_L/1.0 w,Button3/0.1 Button3/0.1 w,Button3/0.8 Shift_L,w,Button3/1.0 w/1.0
lane 40.5 w/0.1 Button3/0.1 w/1.0
# The shield up and the attack button clicked under it: handleKeybinds consumes the clicks while the item is in use (no
# attack, no swing); after the release one click swings.
lane 40.5 Button3/2.6/Button1/0.6 -/0.8 Button1/0.1 -/0.8
echo "item replace entity $NAME weapon.mainhand with minecraft:iron_spear" > "$FIFO"; sleep 0.5
lane 40.5 w/0.5 w,Button3/0.5 w,Button3,Control_L/1.5 w/0.5
echo "item replace entity $NAME weapon.mainhand with minecraft:bow" > "$FIFO"
echo "give $NAME minecraft:arrow 16" > "$FIFO"; sleep 0.5
lane 40.5 w/0.5 w,Button3/1.5 w/1.0
# Pushes, on a lane at z = 20.5: villagers without AI (added after the player, they tick after it and push it away
# from them, LivingEntity.pushEntities): one walked into and stood in, one the player is placed inside, two side by
# side; then in a team whose collision rule is never, which keeps every push off the player.
for v in "44.5 -60 20.5" "50.5 -60 20.2" "50.5 -60 20.9" "56.5 -60 20.5"; do
  echo "summon minecraft:villager $v {NoAI:1b,PersistenceRequired:1b,Silent:1b}" > "$FIFO"
done
sleep 1
pushes() { echo "tp $NAME $1 -60 $2 -90 0" > "$FIFO"; sleep 1.5; shift 2; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
pushes 41.5 20.5 w/1.0 -/1.5
pushes 44.6 20.7 -/1.5
pushes 47.5 20.5 w/1.0 -/1.5 d/0.5 -/1.0
for c in "team add wnc" "team modify wnc collisionRule never" "team join wnc $NAME"; do echo "$c" > "$FIFO"; done
sleep 0.5
pushes 53.5 20.5 w/1.0 -/1.5
for c in "team leave $NAME" "team remove wnc"; do echo "$c" > "$FIFO"; done
# A menu the server opens covers the screen: a chest, used with the right button from an empty hand (x 66, z 22), then
# W held under its screen (it moves nothing and sends no input: setScreen released the keys and they reach the screen),
# escape (ContainerClose), and A, which walks again.
echo "item replace entity $NAME weapon.mainhand with minecraft:air" > "$FIFO"; sleep 0.5
echo "tp $NAME 66.5 -60 20.5 0 30" > "$FIFO"; sleep 1.5
python3 -I "$HERE/course.py" "$DISPLAY_NUM" Button3/0.2 -/1.0 w/1.5 -/0.5 Escape/0.2 -/0.5 a/0.5 -/1.0; sleep 0.5
# Digging with the empty hand, the left button held: a dirt block at eye height ahead (x 76, z 22: its north face), then
# the ground 45 degrees down (its top). START_DESTROY_BLOCK and STOP_DESTROY_BLOCK carry the pick's block and face.
echo "tp $NAME 76.5 -60 20.5 0 0" > "$FIFO"; sleep 1.5
python3 -I "$HERE/course.py" "$DISPLAY_NUM" Button1/1.6 -/0.5; sleep 0.5
echo "tp $NAME 76.5 -60 20.5 0 45" > "$FIFO"; sleep 1.5
python3 -I "$HERE/course.py" "$DISPLAY_NUM" Button1/1.6 -/0.5; sleep 0.5
# Digging while jumping, straight down (x 86, z 20): the grass and the dirt under it, each mostly off the ground, where
# Player.getDestroySpeed divides by 5 (break-time counts a fifth where the client's own flag says so; BreakAudit.java).
echo "tp $NAME 86.5 -60 20.5 0 90" > "$FIFO"; sleep 1.5
python3 -I "$HERE/course.py" "$DISPLAY_NUM" Button1,space/10.0 -/0.5; sleep 0.5
# Gliding with an elytra in the chest slot, east along z = 45.5 (clear of the course; the ground first, so its chunks
# arrive): placed 30 blocks up looking 20 degrees down, the player falls, a press of the jump key in the air starts the
# glide (LocalPlayer.aiStep, tryToStartFallFlying: the client sends START_FALL_FLYING), and it glides down to the
# ground, where the server ends it (its shared flags); from the ground a jump, then a second press at its top, starts a
# short glide; then 20 blocks up looking 5 degrees down with W held throughout (travelFallFlying takes no input); 20 blocks up
# diving at 34.2 degrees, where x86_64's Math.cos is not fdlibm's (PlatformCos), the sprint keys pressed once it glides
# (no sprint starts while gliding); 15 blocks up looking 15 degrees up (the climb). Last, a firework rocket used once
# while gliding (the right button): the server attaches it to the player, and its tick on the client boosts the glide
# (FireworkRocketEntity.tick) until the server removes it.
glide() { echo "tp $NAME $1 -60 45.5 -90 0" > "$FIFO"; sleep 1.5; echo "tp $NAME $1 $2 45.5 -90 $3" > "$FIFO"; sleep 0.3; shift 3
  python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
echo "item replace entity $NAME armor.chest with minecraft:elytra" > "$FIFO"; sleep 0.5
glide 40.5 -30 20 -/0.4 space/0.2 -/8.0
glide 40.5 -60 0 space/0.1 -/0.2 space/0.2 -/2.0
glide 40.5 -40 5 w/0.4 w,space/0.2 w/6.0 -/1.0
glide 40.5 -40 34.2 -/0.4 space/0.2 w,Control_L/3.0 -/1.0
glide 40.5 -45 -15 -/0.4 space/0.2 -/10.0
echo "item replace entity $NAME weapon.mainhand with minecraft:firework_rocket 16" > "$FIFO"; sleep 0.5
glide 40.5 -30 10 -/0.4 space/0.2 -/0.3 Button3/0.1 -/12.0
echo "item replace entity $NAME weapon.mainhand with minecraft:air" > "$FIFO"; sleep 0.5
echo "item replace entity $NAME armor.chest with minecraft:air" > "$FIFO"; sleep 0.5
# Powder snow, at z = 30.5: a strip one deep in the ground (x 60..65) and a pit two deep (x 68..70). Without boots the
# player sinks into the strip (no shape), is held in it (makeStuckInBlock while its feet are in it), walks against its
# east wall and jumps there (no step up a whole block); dropped onto it from five blocks it lands on the falling shape
# (fallen more than 2.5) and sinks after. In leather boots it walks across the strip on top (the full block), sprints
# across, sinks with shift (descending), climbs out at its east wall (wasInPowderSnow: 0.2 a tick against it), and
# climbs out of the pit, two blocks up. Random ticks are off: the snow stays.
snow() { echo "tp $NAME $1 $2 30.5 -90 0" > "$FIFO"; sleep 1.5; shift 2; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
snow 58.5 -60 w/4.0 w,space/2.0 -/0.5
snow 62.5 -55 -/2.5
echo "item replace entity $NAME armor.feet with minecraft:leather_boots" > "$FIFO"; sleep 0.5
snow 58.5 -60 w/1.2 -/0.5 Shift_L/1.0 -/0.3 w/2.5 -/0.5
snow 58.5 -60 w,Control_L/1.5 -/0.5
snow 62.5 -55 -/1.5 w/2.5 -/0.5
snow 68.5 -61 Shift_L/1.0 -/0.3 w/3.0 -/0.5
echo "item replace entity $NAME armor.feet with minecraft:air" > "$FIFO"; sleep 0.5
# Boats, north of everything (z -40..-24): a pool two deep (x 40..70) with a pit three deep in it (x 60..66), ice (x 76..87)
# then blue ice (x 88..100) up to a stone wall (x 101), a slime strip (x 76..84, z -26..-24) and grass. Each leg summons an
# oak boat, mounts the player in it (the ride command), steers it with the keys (A and D turn it, W paddles, S backs) and
# dismounts: the boat paddled across the pool, turned, backed and driven into the pool's east bank; sunk to the pit's floor
# (under water it sinks; the server ejects the player after 60 ticks there); hung in the air (no gravity) and let fall
# onto the water (it lands on the surface: floatBoat's landing); driven over ice and blue ice into the wall at an angle,
# turned there; dropped onto the ice, onto the slime (a bounce of 0.8) and dragged over grass. The truth agent records
# the boat's tick (AbstractBoat.tick), which runs before its passenger's, and the movement engine's boat step is
# compared with it.
boat() { echo "kill @e[tag=wnb]" > "$FIFO"; echo "tp $NAME $1 -60 $2 -90 0" > "$FIFO"; sleep 1.0
  echo "summon minecraft:oak_boat $3 $4 $5 {Tags:[\"wnb\"],Rotation:[$6f,0f]$7}" > "$FIFO"; sleep 1.5
  echo "ride $NAME mount @e[tag=wnb,limit=1]" > "$FIFO"; sleep 1.0; }
# The boat goes first: the player put down on its box would stand on a solid entity, which the on-foot engine does not model.
unboat() { echo "kill @e[tag=wnb]" > "$FIFO"; sleep 1.0; }
fall() { echo "data merge entity @e[tag=wnb,limit=1] {NoGravity:0b}" > "$FIFO"; }
paddle() { python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; }
echo "gamerule entity_drops false" > "$FIFO"
boat 44.5 -26.5 45.5 -60.5 -34.5 -90 ""; paddle w/2.0 w,a/1.0 d/1.0 s/1.0 -/1.0 w,d/0.6 w/3.0 -/0.5; unboat
boat 63.5 -26.5 63.5 -63 -34.5 -90 ""; paddle w/0.8 a/0.4 -/0.4; unboat
boat 50.5 -26.5 50.5 -54 -34.5 -90 ",NoGravity:1b"; paddle -/0.5 d/0.3; fall; paddle -/2.0 w/1.0 -/0.5; unboat
boat 74.5 -34.5 78.5 -60 -34.5 -90 ""; paddle w/2.5 -/0.5 a/0.6 w,a/0.8 -/0.5; unboat
boat 74.5 -36.5 80.5 -60 -38.5 -60 ""; paddle w/2.0 w,d/1.0 -/0.5; unboat
boat 74.5 -34.5 82.5 -55 -34.5 -90 ",NoGravity:1b"; paddle -/0.3; fall; paddle -/1.5 w/1.0 -/0.3; unboat
boat 74.5 -22.5 80.5 -55 -25.0 -90 ",NoGravity:1b"; paddle -/0.3; fall; paddle -/2.5 w/0.8 -/0.3; unboat
boat 44.5 -44.5 45.5 -60 -42.5 -90 ""; paddle w/1.5 a/0.5 -/0.5; unboat
# Horses, west of everything (x -120..-40, z -71..-57, forceloaded with the rest at the start): four lanes, each ridden
# from x -78.5, the course on both sides of it (the rider's yaw may turn between the mount and its first tick, a mouse
# motion through the X server the leg does not send: it then rides another way). Grass (z -70); a stone step one block
# high (x -70, x -88) and a wall two high (x -60, x -98) to jump (z -66), and an arena of its own (centre -79, -90) where
# the step is a ring four blocks out and the wall a ring nine out, met whichever way the horse goes; a pool one deep
# around the start (x -92..-65,
# z -64..-60: in water whichever way it goes); soul sand (x -75..-71, -87..-83) then blue ice (x -68..-60, -98..-90;
# z -58). Each leg summons a tame, saddled horse with a fixed speed (0.3) and
# jump strength (0.8), invulnerable (a hurt horse may rear: its standing then comes from the server), mounts the player
# on it and rides it with the keys (W, S, A and D; the jump key held charges a jump, released it jumps). The truth agent
# records the horse's tick (AbstractHorse.tick), which runs before its rider's.
echo "fill -120 -61 -72 -40 -55 -56 minecraft:air" > "$FIFO"; echo "fill -120 -61 -72 -40 -61 -56 minecraft:grass_block" > "$FIFO"
echo "fill -70 -60 -67 -70 -60 -65 minecraft:stone" > "$FIFO"; echo "fill -60 -60 -67 -60 -59 -65 minecraft:stone" > "$FIFO"
echo "fill -88 -60 -67 -88 -60 -65 minecraft:stone" > "$FIFO"; echo "fill -98 -60 -67 -98 -59 -65 minecraft:stone" > "$FIFO"
echo "fill -92 -61 -64 -65 -61 -60 minecraft:water" > "$FIFO"
echo "fill -95 -61 -106 -63 -55 -74 minecraft:air" > "$FIFO"; echo "fill -95 -61 -106 -63 -61 -74 minecraft:grass_block" > "$FIFO"
for side in "-83 -60 -94 -75 -60 -94" "-83 -60 -86 -75 -60 -86" "-83 -60 -94 -83 -60 -86" "-75 -60 -94 -75 -60 -86" \
    "-88 -60 -99 -70 -59 -99" "-88 -60 -81 -70 -59 -81" "-88 -60 -99 -88 -59 -81" "-70 -60 -99 -70 -59 -81"; do
  echo "fill $side minecraft:stone" > "$FIFO"
done
echo "fill -75 -61 -59 -71 -61 -57 minecraft:soul_sand" > "$FIFO"; echo "fill -68 -61 -59 -60 -61 -57 minecraft:blue_ice" > "$FIFO"
echo "fill -87 -61 -59 -83 -61 -57 minecraft:soul_sand" > "$FIFO"; echo "fill -98 -61 -59 -90 -61 -57 minecraft:blue_ice" > "$FIFO"
horse() { echo "kill @e[tag=wnh]" > "$FIFO"; echo "tp $NAME -79.5 -60 $1 -90 0" > "$FIFO"; sleep 1.5
  echo "summon minecraft:horse -78.5 -60 $1 {Tags:[\"wnh\"],Tame:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[-90f,0f],equipment:{saddle:{id:\"minecraft:saddle\",count:1}},attributes:[{id:\"minecraft:movement_speed\",base:0.3d},{id:\"minecraft:jump_strength\",base:0.8d}]}" > "$FIFO"; sleep 1.5
  # mounted while it interpolates half a block on (relative to the horse: the console's own ~ is the world spawn)
  if [ "${2:-}" = nudge ]; then echo "execute as @e[tag=wnh,limit=1] at @s run tp @s ~0.5 ~ ~" > "$FIFO"; fi
  echo "ride $NAME mount @e[tag=wnh,limit=1]" > "$FIFO"; sleep 1.0; }
# A killed horse keeps its rider through its death (20 ticks): the next leg's teleport waits until it is gone.
unhorse() { echo "kill @e[tag=wnh]" > "$FIFO"; sleep 2.0; }
horse -69.5; paddle w/2.0 w,a/1.0 w,d/1.0 s/1.0 -/0.8 a/0.6 -/0.5; unhorse
horse -69.5; paddle space/0.4 -/1.5 w/0.8 w,space/1.0 w/1.5 -/1.0; unhorse
horse -89.5; paddle w/2.5 w,space/0.7 w/2.0 -/0.5; unhorse
horse -61.5; paddle w/3.0 -/0.5 s/1.0 -/0.5; unhorse
horse -57.5 nudge; paddle w/4.0 -/1.5; unhorse
# Pigs, in the horses' arena (centre -79, -90) and on the grass lane: a saddled pig the player steers with a carrot on a
# stick (a fresh one each leg in the main hand, the shield off the off hand meanwhile), ahead wherever the rider looks
# (Pig.getRiddenInput) up the step ring and into the wall ring; boosted by the use key (the server's boost time reaches
# the client, which counts the boost itself: ItemBasedSteering), pressed while no block is in reach (a use on a block
# leaves the block beside it unsettled until the server answers). NoAI: a carrot on a stick tempts a pig to the player, which it would push.
# The truth agent records the pig's tick (Mob.tick) in the horse's form.
pig() { echo "kill @e[tag=wnp]" > "$FIFO"; echo "tp $NAME -79.5 -60 $1 -90 0" > "$FIFO"
  echo "item replace entity $NAME weapon.mainhand with minecraft:carrot_on_a_stick" > "$FIFO"; sleep 1.5
  echo "summon minecraft:pig -78.5 -60 $1 {Tags:[\"wnp\"],NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[-90f,0f],equipment:{saddle:{id:\"minecraft:saddle\",count:1}}}" > "$FIFO"; sleep 1.5
  echo "ride $NAME mount @e[tag=wnp,limit=1]" > "$FIFO"; sleep 1.0; }
unpig() { echo "kill @e[tag=wnp]" > "$FIFO"; sleep 2.0; }
echo "item replace entity $NAME weapon.offhand with minecraft:air" > "$FIFO"; sleep 0.5
pig -89.5; paddle -/0.5 Button3/0.2 -/7.0; unpig
pig -89.5; paddle -/6.0; unpig
pig -69.5; paddle Button3/0.2 -/4.0; unpig
# Striders, west of the horses (x -158..-121, z -72..-56, built at the start; the horses' grass begins at x -120): a lava pool
# one deep in the ground (x -147..-136, sources) and a channel (x -129..-126) its sources flood three blocks east (flowing
# lava has no stable shape), ridden along
# z -64.5. Each leg summons a saddled strider, NoAI until just before the player is put on it: its AI must be on when the
# client handles the mount (the server sends its data at the end of its tick, the mount at once), for the client to set its
# suffocation itself in every ridden tick; the player is handed a warped fungus on a stick at the mount (it tempts a strider
# to the player). Ahead wherever the rider looks, out of the warm on the grass (0.35 of its speed rather than 0.55), standing
# on the lava's stable shape, sunk in the flowing lava (floatStrider lifts it); boosted by the use key. The truth agent
# records its tick (Strider.tick).
strider() { echo "kill @e[tag=wns]" > "$FIFO"; echo "tp $NAME $(($1 - 1)).5 -60 -64.5 -90 0" > "$FIFO"
  echo "item replace entity $NAME weapon.mainhand with minecraft:air" > "$FIFO"; sleep 1.5
  echo "summon minecraft:strider $1.5 -60 -64.5 {Tags:[\"wns\"],NoAI:1b,Invulnerable:1b,PersistenceRequired:1b,Rotation:[-90f,0f],equipment:{saddle:{id:\"minecraft:saddle\",count:1}}}" > "$FIFO"; sleep 1.5
  echo "data merge entity @e[tag=wns,limit=1] {NoAI:0b}" > "$FIFO"; sleep 0.2
  echo "item replace entity $NAME weapon.mainhand with minecraft:warped_fungus_on_a_stick" > "$FIFO"
  echo "ride $NAME mount @e[tag=wns,limit=1]" > "$FIFO"; sleep 1.0; }
unstrider() { echo "kill @e[tag=wns]" > "$FIFO"; sleep 2.0; }
strider -151; paddle -/6.0; unstrider
strider -131; paddle Button3/0.2 -/4.0; unstrider
echo "item replace entity $NAME weapon.mainhand with minecraft:air" > "$FIFO"
echo "item replace entity $NAME weapon.offhand with minecraft:shield" > "$FIFO"; sleep 0.5
# A minecart, on the grass lane: no player controls one (getControllingPassenger null), so the client, ridden and walked
# with W, sends no vehicle move (the movement check's VEHICLE_UNCONTROLLED is in force over these ticks).
echo "tp $NAME -79.5 -60 -69.5 -90 0" > "$FIFO"; sleep 1.0
echo "summon minecraft:minecart -77.5 -60 -69.5 {Tags:[\"wnm\"],Invulnerable:1b}" > "$FIFO"; sleep 1.0
echo "ride $NAME mount @e[tag=wnm,limit=1]" > "$FIFO"; sleep 1.0
paddle w/1.5 -/1.0
echo "kill @e[tag=wnm]" > "$FIFO"; sleep 1.5
echo "gamerule entity_drops true" > "$FIFO"
# The world border (its shape, rounded out to whole blocks, collides within a few blocks: isInsideCloseToBorder): 20
# wide around x 50, z 65.5 (its east side at x 60, its south side at z 75.5), walked into, jumped at, walked along, and
# sprinted into diagonally up to its corner; then growing to 30 over 80 ticks with the player pressed against it, and
# shrinking back to 20 over 60 ticks as it walks away backwards. The default border after. The player is put inside
# first: far outside a border it takes damage by the block beyond (it would die where the rocket left it). The centres
# are written with a fraction, which the command takes as it is (a whole number is a block's centre).
border() { echo "tp $NAME $1 -60 $2 $3 0" > "$FIFO"; sleep 1.5; shift 3; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
echo "tp $NAME 55.5 -60 65.5 -90 0" > "$FIFO"; sleep 1.5
echo "worldborder center 50.0 65.5" > "$FIFO"; echo "worldborder set 20" > "$FIFO"; sleep 1.0
border 55.5 65.5 -90 w/2.5 w,space/1.0 w,a/1.5 -/0.5
border 55.5 70.5 -60 w/1.5 w,Control_L/1.5 -/0.5
border 56.5 65.5 -90 w/1.0
echo "worldborder set 30 80" > "$FIFO"
python3 -I "$HERE/course.py" "$DISPLAY_NUM" w/5.0 -/0.5
echo "worldborder set 20 60" > "$FIFO"
python3 -I "$HERE/course.py" "$DISPLAY_NUM" s/3.5 -/0.5
echo "worldborder set 59999968" > "$FIFO"; echo "worldborder center 0.0 0.0" > "$FIFO"; sleep 1.0
# Fast lava in the nether (gameplay/fast_lava: its current pushes 0.007 rather than 0.0023): in a glass shell (x -1..21,
# y 99..108, z -1..21), a pool three deep (x 5..12, z 5..8) walked into and jumped in, and a channel (z 10) its source at
# x 13 floods eight blocks east, walked into from the east against the current, stood in and jumped in. The session ends
# there.
nether() { echo "execute in minecraft:the_nether run tp $NAME $1 $2 $3 $4 0" > "$FIFO"; sleep 2.5; shift 4; python3 -I "$HERE/course.py" "$DISPLAY_NUM" "$@"; sleep 0.5; }
# In the nether a new ClientLevel numbers its block predictions from 1 again (prediction-sequence): a right click on the
# glass floor with the empty main hand (the shield in the off hand: useItemOn for each hand, then the shield's use).
echo "item replace entity $NAME weapon.mainhand with minecraft:air" > "$FIFO"
echo "execute in minecraft:the_nether run tp $NAME 2.5 103 2.5 -90 60" > "$FIFO"; sleep 2.5
python3 -I "$HERE/course.py" "$DISPLAY_NUM" Button3/0.3 -/0.5; sleep 0.5
nether 2.5 103 6.5 -90 -/1.5 w/2.5 -/1.0 space/1.5 -/0.5
nether 20.5 103 10.5 90 -/0.5 w/3.0 -/2.0 space/1.0 -/0.5
sleep 0.5
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
# Every attack, assessed as the attack-reach check does (in reach, out of reach, or no claim and why).
java -cp "$WORK_DIR/plugins/WhyNotAC.jar" "$HERE/ReachAudit.java" "$RECORDING" > "$WORK_DIR/reach-audit.jsonl"
# Every block finish, as the break-time check judged it (within, early, not known), with its ticks off the ground.
java -cp "$WORK_DIR/plugins/WhyNotAC.jar" "$HERE/BreakAudit.java" "$RECORDING" > "$WORK_DIR/break-audit.jsonl"
cat "$WORK_DIR/check.json"
echo "--- verification"
STATUS=0
python3 -I "$HERE/verify_realclient.py" "$WORK_DIR" || STATUS=1
echo "--- client truth"
python3 -I "$HERE/verify_truth.py" "$WORK_DIR" || STATUS=1
echo "--- movement engine"
# whynotac-physics' on-foot step, run from each recorded tick's start, must give the client's own end of the tick, bit for bit.
if java -cp "$WORK_DIR/plugins/WhyNotAC.jar" "$HERE/MovementTruth.java" "$RECORDING" "$WORK_DIR/truth.jsonl" | grep -v '^Picked up' | tee "$WORK_DIR/movement.json"; then
  echo "PASS every tick in the on-foot engine's scope equals the client's own tick, bit for bit; the movement check explained every tick it claimed, the client's own state among its hypotheses"
else
  echo "FAIL the on-foot engine differs from the client (see movement.json)"
  STATUS=1
fi
echo "--- block-state data"
# whynotac-world ships block-state data generated from this very client; regenerate it and require equality.
(cd "$WORK_DIR" && java -cp "$CP:$AGENT_OUT/classes:$WHYNOTAC_CP" dev.whynotac.truth.BlockDataDump "$MAPPINGS" "$WORK_DIR/block-states.txt.gz") \
  | grep -v '^Picked up' | tail -1
if cmp -s <(zcat "$WORK_DIR/block-states.txt.gz") <(zcat "$ROOT/whynotac-world/src/main/resources/dev/whynotac/world/block-states.txt.gz"); then
  echo "PASS whynotac-world's block-state data is exactly what the official client's registry yields"
else
  echo "FAIL whynotac-world's block-state data differs from the official client (copy $WORK_DIR/block-states.txt.gz over the resource)"
  STATUS=1
fi
echo "--- collision kernel"
# whynotac-physics' collisions against the client's own VoxelShape/Shapes/Entity code, on random cases; a new seed every run.
SEED=${GITHUB_RUN_ID:-$(date +%s)}
CONFORMANCE=$( (cd "$WORK_DIR" && java -cp "$CP:$AGENT_OUT/classes:$WHYNOTAC_CP" dev.whynotac.truth.CollisionConformance "$MAPPINGS" 50000 "$SEED") \
  | grep -o '{"shapes".*}' || true)
echo "seed $SEED: $CONFORMANCE"
if echo "$CONFORMANCE" | grep -q '"differences":0,'; then
  echo "PASS whynotac-physics' collision kernel equals the official client's, bit for bit, on every random case"
else
  echo "FAIL whynotac-physics' collision kernel differs from the official client (seed $SEED)"
  STATUS=1
fi
exit $STATUS
