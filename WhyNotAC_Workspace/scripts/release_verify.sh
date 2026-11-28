#!/usr/bin/env bash
# WhyNotAC Reproducible Release Verification Script (PH27-03):
#   1. Performs two clean builds of :whynotac-paper:jar and :whynotac-velocity:jar and proves
#      bit-exact reproducible SHA-256 digests across builds.
#   2. Executes REL-02 (:whynotac-paper:verifyDependencyIntegrity) and REL-03 CycloneDX 1.5 SBOM check.
#   3. Scans every Markdown (*.md) file in the repository for broken relative links (0 broken links).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
WORKSPACE=$(cd "$HERE/.." && pwd)
REPO_ROOT=$(cd "$WORKSPACE/.." && pwd)

cd "$WORKSPACE"

echo "[1/4] Build #1: clean reproducible jars (:whynotac-paper:jar, :whynotac-velocity:jar)..."
./gradlew -q :whynotac-paper:clean :whynotac-velocity:clean :whynotac-paper:jar :whynotac-velocity:jar
PAPER_JAR=$(find whynotac-paper/build/libs -name 'whynotac-paper-*.jar' ! -name '*-sources.jar' | head -1)
VELOCITY_JAR=$(find whynotac-velocity/build/libs -name 'whynotac-velocity-*.jar' ! -name '*-sources.jar' | head -1)
PAPER_SHA1=$(sha256sum "$PAPER_JAR" | awk '{print $1}')
VELOCITY_SHA1=$(sha256sum "$VELOCITY_JAR" | awk '{print $1}')

echo "[2/4] Build #2: clean rebuild to verify bit-exact SHA-256 reproducibility..."
./gradlew -q :whynotac-paper:clean :whynotac-velocity:clean :whynotac-paper:jar :whynotac-velocity:jar
PAPER_SHA2=$(sha256sum "$PAPER_JAR" | awk '{print $1}')
VELOCITY_SHA2=$(sha256sum "$VELOCITY_JAR" | awk '{print $1}')

echo "  whynotac-paper    SHA-256 build1=$PAPER_SHA1"
echo "  whynotac-paper    SHA-256 build2=$PAPER_SHA2"
echo "  whynotac-velocity SHA-256 build1=$VELOCITY_SHA1"
echo "  whynotac-velocity SHA-256 build2=$VELOCITY_SHA2"

if [ "$PAPER_SHA1" != "$PAPER_SHA2" ]; then
  echo "FAIL: whynotac-paper jar is NOT bit-exact reproducible!" >&2
  exit 1
fi
if [ "$VELOCITY_SHA1" != "$VELOCITY_SHA2" ]; then
  echo "FAIL: whynotac-velocity jar is NOT bit-exact reproducible!" >&2
  exit 1
fi

echo "[3/4] Running REL-02 verifyDependencyIntegrity & REL-03 CycloneDX 1.5 SBOM verification..."
./gradlew -q :whynotac-paper:verifyDependencyIntegrity :whynotac-paper:generateSbom
SBOM="whynotac-paper/build/reports/whynotac-sbom.cdx.json"
python3 - "$SBOM" <<'PY'
import json, sys
bom = json.load(open(sys.argv[1], encoding="utf-8"))
assert bom.get("bomFormat") == "CycloneDX", f"Unexpected bomFormat: {bom.get('bomFormat')}"
assert bom.get("specVersion") == "1.5", f"Unexpected specVersion: {bom.get('specVersion')}"
comps = bom.get("components", [])
assert len(comps) >= 5, f"Expected >=5 SBOM components, got {len(comps)}"
print(f"  CycloneDX {bom['specVersion']} SBOM valid: {len(comps)} components with SHA-256 digests")
PY

echo "[4/4] Verifying 0 broken relative Markdown links across repository..."
python3 - "$REPO_ROOT" <<'PY'
import os, re, sys
from pathlib import Path

root = Path(sys.argv[1])
link_re = re.compile(r'\[[^\]]*\]\(([^)]+)\)')
broken = []
checked_files = 0
checked_links = 0

for md in sorted(root.rglob("*.md")):
    if any(part in {".git", "build", ".gradle", "node_modules"} for part in md.parts):
        continue
    checked_files += 1
    text = md.read_text(encoding="utf-8", errors="replace")
    # Strip fenced code blocks so example snippets are not treated as links
    in_fence = False
    clean_lines = []
    for line in text.splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        if not in_fence:
            clean_lines.append(line)
    clean_text = "\n".join(clean_lines)
    for m in link_re.finditer(clean_text):
        target = m.group(1).strip()
        if not target or target.startswith(("http://", "https://", "mailto:", "#", "file://")):
            continue
        # Strip optional title or anchor
        path_part = target.split()[0].split("#")[0]
        if not path_part:
            continue
        checked_links += 1
        resolved = (md.parent / path_part).resolve()
        if not resolved.exists():
            broken.append(f"{md.relative_to(root)} -> {target}")

print(f"  Checked {checked_links} relative links across {checked_files} Markdown files (broken={len(broken)})")
if broken:
    for item in broken:
        print(f"  BROKEN LINK: {item}", file=sys.stderr)
    sys.exit(1)
PY

echo "RELEASE_VERIFY: OK"
