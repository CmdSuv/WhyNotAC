#!/usr/bin/env python3
"""
Checks a real-client session (run_realclient.sh) against what was played and against WhyNotAC's
vanilla models. Exit code 0 means every check passed.

usage: verify_realclient.py <work-dir>
"""
import json
import os
import re
import sys


def aligned(observed, commanded):
    """True if the observed per-step counts are the commanded motions one for one, in order (a motion the
    client merged with its neighbour into one frame is accepted as their sum, at most twice). The vanilla
    client ignores the first cursor move after the window grabs the mouse and after the cursor enters
    the window (MouseHandler.onMove, ignoreFirstMove): up to two leading motions may be consumed so."""
    results = [_aligned(observed, commanded[skip:], skip) for skip in range(3)]
    return next((r for r in results if r[0]), results[0])


def _aligned(observed, commanded, skipped):
    i, merged = 0, 0
    for count in observed:
        if count is None or i >= len(commanded):
            return False, f"step {i}: {count} (commanded {commanded[i:i + 3]})"
        total, j = commanded[i], i + 1
        while total != count and j < len(commanded) and j - i < 3:
            total += commanded[j]
            j += 1
        if total != count:
            return False, f"step {i}: observed {count}, commanded {commanded[i:i + 3]}"
        merged += (j - i) - 1
        i = j
    if i != len(commanded) or merged > 2:
        return False, f"{i} of {len(commanded)} motions matched, {merged} merged"
    return True, f"{len(observed)} steps == {len(commanded)} commanded motions ({merged} merged, {skipped} leading consumed by ignoreFirstMove)"


def main():
    work = sys.argv[1]
    failures = []

    def check(condition, message):
        print(("PASS " if condition else "FAIL ") + message)
        if not condition:
            failures.append(message)

    log = open(os.path.join(work, "server.log"), encoding="utf-8", errors="replace").read()
    events = [json.loads(line) for line in open(os.path.join(work, "dump.jsonl")) if line.strip()]
    result = json.load(open(os.path.join(work, "check.json")))
    inbound = [e["packet"] for e in events if e["type"] == "Inbound"]

    check(re.search(r"RealProbe (was kicked|lost connection: (?!Disconnected))", log) is None,
          "the real client was never kicked or flagged by the server")
    observation = [e for e in events if e["type"] == "Observation"]
    check(len(observation) == 1 and observation[0]["inbound"] == "WIRE" and observation[0]["clientProtocol"] == 774,
          f"recorded at the network edge from the client's real handshake: {observation}")
    divergences = [e for e in events if e["type"] in ("InboundDivergence", "OutboundDivergence")]
    check(not divergences, f"edge and game agree on every packet of a real session ({len(divergences)} divergences)")
    brands = [p["brand"] for p in inbound if p["type"] == "ClientBrand"]
    check(brands == ["vanilla"], f"the unmodified client announces itself as vanilla: {brands}")

    inputs = [p for p in inbound if p["type"] == "PlayerInput"]
    for key in ("forward", "backward", "left", "right", "jump", "shift", "sprint"):
        check(any(p[key] for p in inputs), f"key '{key}' pressed with real input reached the server")
    swings = [p for p in inbound if p["type"] == "Swing"]
    check(len(swings) >= 5, f"left clicks became swings ({len(swings)})")
    slots = [p["slot"] for p in inbound if p["type"] == "SetCarriedItem"]
    check(slots[-3:] == [1, 2, 0], f"hotbar keys 2, 3, 1 selected slots 1, 2, 0: {slots}")
    positions = [p for p in inbound if p["type"] == "MovePlayer" and p["hasPosition"]]
    xs = [float(p["x"]["f64"]) for p in positions]
    ys = [float(p["y"]["f64"]) for p in positions]
    check(len(positions) > 50 and max(xs) - min(xs) > 1.0 and max(ys) - min(ys) > 0.5,
          f"the player walked and jumped ({len(positions)} position packets)")

    tps = result["steady_ticks_per_second"]
    check(19.5 <= tps <= 20.5, f"client tick clock after the join: {tps:.2f} ClientTickEnd per second "
                               f"(whole session incl. world loading: {result['ticks_per_second']:.2f})")
    check(result["pongs_inside_a_tick"] == 0 and result["pongs"] > 100,
          f"wire order: no pong inside a tick, as the vanilla client answers pings at frame start ({result['pongs']} pongs)")
    # The driver spaced its mouse motions two ticks apart: each one is a rotation step of its own, so the
    # counts inferred through the exact float model must equal the commanded counts one for one. That
    # proves the float arithmetic and the absolute scale (a multiplier off by 2x would double them).
    drive = json.load(open(os.path.join(work, "drive.json")))
    mouse = next(a for a in drive["actions"] if a["what"] == "mouse")
    yaw_ok, yaw_detail = aligned(result["yaw_counts"], mouse["horizontal"])
    check(yaw_ok, f"every yaw step is exactly one commanded mouse motion through the vanilla float math: {yaw_detail}")
    pitch_ok, pitch_detail = aligned(result["pitch_counts"], mouse["vertical"])
    check(pitch_ok, f"every pitch step too: {pitch_detail}")
    rejected = {name: not aligned(c["yaw_counts"], mouse["horizontal"])[0] for name, c in result["negative_controls"].items()}
    check(all(rejected.values()), f"the same data rejects every wrong model (negative controls): {rejected}")
    cases_dir = os.path.join(work, "plugins", "WhyNotAC", "cases")
    cases = [f for f in os.listdir(cases_dir) if f.endswith(".json")] if os.path.isdir(cases_dir) else []
    check(not cases, f"no case was opened for the honest, unmodified client ({len(cases)} dossiers)")
    screenshots = os.listdir(os.path.join(work, "game", "screenshots")) if os.path.isdir(os.path.join(work, "game", "screenshots")) else []
    check(len(screenshots) == 1, f"the client rendered and saved a screenshot: {screenshots}")
    # The world WhyNotAC rebuilt from what the real client received equals the server's world.
    m = re.search(r"WhyNotAC world RealProbe: (.*)", log)
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv) if m else {}
    n = lambda k: int(fields.get(k, "-1"))
    check(m is not None and fields.get("dimension") == "minecraft:overworld" and n("chunks") >= 9 and n("missingOnServer") == 0
          and n("different") == 0 and n("stale") == 0 and n("unreadable") == 0 and n("equal") == n("sections") > 0,
          f"the real client's world, rebuilt from its packets, equals the server's block for block ({m.group(1) if m else 'no answer'})")
    # The entities WhyNotAC rebuilt from what the real client received are the ones the server shows it, at the same bases.
    m = re.search(r"WhyNotAC entities RealProbe: (.*)", re.sub(r"\x1b\[[0-9;]*m", "", log))
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv) if m else {}
    check(m is not None and fields.get("complete") == "true" and n("server") >= 3 and n("missingInReplica") == 0 and n("extraInReplica") == 0
          and n("different") == 0 and n("equal") >= 3 and n("equal") + n("moving") == n("compared") and n("overflows") == 0,
          f"the real client's entities, rebuilt from its packets, are the server's, base for base ({m.group(1) if m else 'no answer'})")
    check(m is not None and n("sizeDifferent") == 0 and n("sizeEqual") >= 3 and n("sizeEqualNonDefault") >= 1,
          f"every size the replica claims for the real client's entities is the server's own, a baby's too ({n('sizeEqual')} equal, {n('sizeEqualNonDefault')} not the default, {n('sizeDifferent')} different)")
    print("RESULT:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
