#!/usr/bin/env python3
"""Summarizes one load-test run (see run_load.sh) as one JSON document. Stdlib only."""
import glob
import json
import os
import re
import sys

ANSI = re.compile(r"\x1b\[[0-9;]*m")



def bot_artefact(case):
    """What the bots do unlike a vanilla client, not a false finding: they walk without vanilla physics, and they attack
    a cow in reach without picking it (on real terrain, through leaves and hills)."""
    return case.get("check") == "movement" or (case.get("check") == "attack-aim" and case.get("details", {}).get("reason") == "blocks")

def main():
    (variant, layout, bots, measure, cpu_start, cpu_end, t_start, t_end, clk_tck,
     server_log, jfr_json, histogram, out_prefix) = sys.argv[1:14]
    log = [ANSI.sub("", line) for line in open(server_log, encoding="utf-8", errors="replace")]

    mspt = None
    for i, line in enumerate(log):
        if "Server tick times" in line and i + 1 < len(log):
            triples = re.findall(r"([\d.]+)/([\d.]+)/([\d.]+)", log[i + 1])
            if len(triples) == 3:
                avg, low, high = (float(v) for v in triples[2])   # the last minute = the measurement window
                mspt = {"avg": avg, "min": low, "max": high, "window": "1m"}
    tps = None
    for line in log:
        m = re.search(r"TPS from last 1m, 5m, 15m: ([\d.*]+)", line)
        if m:
            tps = float(m.group(1).strip("*"))
    stats = [json.loads(line.split("WhyNotAC stats ", 1)[1]) for line in log if "WhyNotAC stats {" in line]

    seconds = float(t_end) - float(t_start)
    cpu_cores = (int(cpu_end) - int(cpu_start)) / int(clk_tck) / seconds

    heap_total, ours, top = 0, 0, []
    for line in open(histogram, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line)
        if not m:
            continue
        instances, size, name = int(m.group(1)), int(m.group(2)), m.group(3)
        heap_total += size
        if name.startswith("dev.whynotac."):
            ours += size
            top.append({"class": name, "instances": instances, "bytes": size})
    top.sort(key=lambda c: -c["bytes"])

    bot_results = [json.loads(open(f).read().strip().splitlines()[-1]) for f in sorted(glob.glob(out_prefix + "-bots-*.json"))
                   if open(f).read().strip()]
    bot_summary = {
        "processes": len(bot_results),
        "joined": sum(b["joined"] for b in bot_results),
        "still_connected": sum(b["still_connected"] for b in bot_results),
        "pings_seen": sum(b["pings_seen"] for b in bot_results),
        "pings_answered": sum(b["pings_answered"] for b in bot_results),
        "errors": [e for b in bot_results for e in b["errors"]][:10],
        "disconnects": [d for b in bot_results for d in b["disconnects"]][:10],
    }
    try:
        warmed, settled = open(out_prefix + "-warmup.txt").read().split()
        warmup = {"seconds": int(warmed), "settled": int(settled) >= 2}
    except (OSError, ValueError):
        warmup = None
    # Every bot is honest: any case WhyNotAC opened for one of them is a false positive, except the movement check's.
    # The bots walk their circle with their own arithmetic, not the vanilla client's physics, so no vanilla client
    # moves as they do: that check rightly finds them modified (its false-positive evidence is the real client's).
    cases_dir = os.path.join(os.path.dirname(out_prefix), "plugins", "WhyNotAC", "cases")
    cases = [json.load(open(os.path.join(cases_dir, f))) for f in sorted(os.listdir(cases_dir)) if f.endswith(".json")] \
        if os.path.isdir(cases_dir) else []
    moved_wrongly = sum(1 for line in log if "moved wrongly" in line or "moved too quickly" in line)
    # Every attack the bots made, assessed over the recordings (run_load.sh): an out-of-reach one is a false positive.
    reach_path = out_prefix + "-reach.jsonl"
    audit = [json.loads(line) for line in open(reach_path) if line.strip()] if os.path.exists(reach_path) else []
    outcomes = {}
    for a in audit:
        key = a["outcome"] if a["outcome"] != "NO_CLAIM" else "NO_CLAIM: " + a["reason"]
        outcomes[key] = outcomes.get(key, 0) + 1
    in_reach = [a for a in audit if a["outcome"] == "IN_REACH"]
    attack_reach = {
        "attacksAssessed": len(audit),
        "outcomes": outcomes,
        "outOfReach": sum(1 for a in audit if a["outcome"] == "OUT_OF_REACH"),
        "smallestMarginInReach": round(min(a["allowed"] - a["distance"] for a in in_reach), 6) if in_reach else None,
    } if os.path.exists(reach_path) else None

    print(json.dumps({
        "variant": variant, "layout": layout, "bots": int(bots), "measureSeconds": round(seconds, 2),
        "warmup": warmup, "mspt": mspt, "tps": tps, "serverCpuCores": round(cpu_cores, 4),
        "jfr": json.load(open(jfr_json)),
        "heapAfterFullGcBytes": heap_total, "whynotacHeapBytes": ours, "whynotacTopClasses": top[:12],
        "whynotacStatsDuringLoad": stats[0] if stats else None,
        "whynotacStatsAfterAllLeft": stats[-1] if len(stats) > 1 else None,
        "botSide": bot_summary,
        "serverMovementCorrections": moved_wrongly,
        "attackReach": attack_reach,
        "casesOpened": sum(1 for c in cases if not bot_artefact(c)),
        "caseSummaries": [c.get("playerName", "?") + " " + c.get("check", "?") + ": " + c.get("summary", "") for c in cases
                          if not bot_artefact(c)][:10],
        "movementCases": sum(1 for c in cases if c.get("check") == "movement"),
        # the bots attack a cow in reach whatever is between: on real terrain through leaves and hills (they do not pick)
        "throughBlocksCases": [c.get("playerName", "?") + ": " + c.get("summary", "") for c in cases
                               if c.get("check") == "attack-aim" and c.get("details", {}).get("reason") == "blocks"][:10],
    }, indent=1))


if __name__ == "__main__":
    main()
