#!/usr/bin/env bash
# Downloads the third-party plugins of the interop E2E variant and verifies their sha512 (Modrinth
# publishes it; a mismatch aborts). Prints the jar paths, space-separated, for EXTRA_PLUGINS.
#
# usage: EXTRA_PLUGINS=$(tools/e2e/fetch_interop_plugins.sh <dir>) tools/e2e/run_e2e.sh <work-dir>
#
# These two sit closest to the network edge on real servers: ViaVersion rewrites packets for other
# client versions, and PacketEvents can inject or send packets past the vanilla encoder.
set -euo pipefail
DIR=$(mkdir -p "$1" && cd "$1" && pwd)
PLUGINS=(
  "ViaVersion-5.12.0.jar https://cdn.modrinth.com/data/P1OZGk5p/versions/FaishMnD/ViaVersion-5.12.0.jar 2dfe562109179f08685dc84a66aedf7c810592223b5287b6355ba1e741d1737d015ca0c444b4bc01e5db2a4b10549d5028bf2e3f3337219ded709e44b579a4d8"
  "packetevents-spigot-2.14.0.jar https://cdn.modrinth.com/data/HYKaKraK/versions/m78nFxYg/packetevents-spigot-2.14.0.jar 18a942cb55f64783a63b28deea2cebbbe782e5d6a1089f6700430e183bfbce0a45398a640ed0dff4361b55190dcf47017f2ca7788f4a5c8c9efad1f549a63fe3"
)
paths=()
for entry in "${PLUGINS[@]}"; do
  read -r name url sha512 <<< "$entry"
  if [ ! -f "$DIR/$name" ]; then
    curl -fsSL --max-time 300 -o "$DIR/$name.part" "$url"
    mv "$DIR/$name.part" "$DIR/$name"
  fi
  echo "$sha512  $DIR/$name" | sha512sum -c --quiet - >&2
  paths+=("$DIR/$name")
done
echo "${paths[*]}"
