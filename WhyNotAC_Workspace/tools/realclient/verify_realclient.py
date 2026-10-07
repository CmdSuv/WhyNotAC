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

    tps = result["ticks_per_second"]
    check(19.0 <= tps <= 21.0, f"client tick clock: {tps:.2f} ClientTickEnd per second")
    check(result["pongs_inside_a_tick"] == 0 and result["pongs"] > 100,
          f"wire order: no pong inside a tick, as the vanilla client answers pings at frame start ({result['pongs']} pongs)")
    check(result["yaw_steps"] >= 60 and result["yaw_unexplained"] == 0,
          f"every yaw change is explained by integer mouse counts through the exact vanilla float math "
          f"({result['yaw_one_frame']} one-frame + {result['yaw_two_or_three_frames']} multi-frame of {result['yaw_steps']})")
    check(result["pitch_steps"] > 0 and result["pitch_explained"] == result["pitch_steps"],
          f"every pitch change is explained too ({result['pitch_explained']}/{result['pitch_steps']})")
    screenshots = os.listdir(os.path.join(work, "game", "screenshots")) if os.path.isdir(os.path.join(work, "game", "screenshots")) else []
    check(len(screenshots) == 1, f"the client rendered and saved a screenshot: {screenshots}")
    print("RESULT:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
