#!/usr/bin/env python3
"""Summarizes one load-test run (see run_load.sh) as one JSON document. Stdlib only."""
import glob
import json
import re
import sys

ANSI = re.compile(r"\x1b\[[0-9;]*m")


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
    moved_wrongly = sum(1 for line in log if "moved wrongly" in line or "moved too quickly" in line)

    print(json.dumps({
        "variant": variant, "layout": layout, "bots": int(bots), "measureSeconds": round(seconds, 2),
        "warmup": warmup, "mspt": mspt, "tps": tps, "serverCpuCores": round(cpu_cores, 4),
        "jfr": json.load(open(jfr_json)),
        "heapAfterFullGcBytes": heap_total, "whynotacHeapBytes": ours, "whynotacTopClasses": top[:12],
        "whynotacStatsDuringLoad": stats[0] if stats else None,
        "whynotacStatsAfterAllLeft": stats[-1] if len(stats) > 1 else None,
        "botSide": bot_summary,
        "serverMovementCorrections": moved_wrongly,
    }, indent=1))


if __name__ == "__main__":
    main()
