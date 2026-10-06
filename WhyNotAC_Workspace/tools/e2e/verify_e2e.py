#!/usr/bin/env python3
"""
Checks an E2E run: the server-side view printed by `whynotac inspect` must match what the probe
client actually did, bit for bit where floats are involved.

usage: verify_e2e.py <e2e-server.log> <e2e-probe.json>
"""
import json
import re
import struct
import sys


def f32(x):
    return struct.unpack(">f", struct.pack(">f", x))[0]


def from_bits(bits):
    return struct.unpack(">f", struct.pack(">I", bits))[0]


def main():
    log = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    probe = json.loads(open(sys.argv[2]).read().strip().splitlines()[-1])
    failures = []

    def check(condition, message):
        print(("PASS " if condition else "FAIL ") + message)
        if not condition:
            failures.append(message)

    m = re.search(r"Transactions sent (\d+) acked (\d+) pending (\d+) \(oldest [\d.]+ ms\) skipped (\d+) unknown (\d+)", log)
    check(m is not None, "inspect output present")
    if m:
        sent, acked, pending, skipped, unknown = map(int, m.groups())
        check(skipped == len(probe["skipped_transaction_ids"]) == 1, f"withheld transaction detected (skipped={skipped})")
        check(unknown == len(probe["forged_transaction_ids"]) == 1, f"forged transaction detected (unknown={unknown})")
        check(sent == acked + skipped + pending, f"transaction accounting closes ({sent} = {acked} + {skipped} + {pending})")
    m = re.search(r"queued \d+ dropped (\d+)", log)
    check(m is not None and int(m.group(1)) == 0, "no timeline events dropped")
    check(probe["still_connected"], "probe was never disconnected (interceptor is passive)")
    check("SKIPPED_TRANSACTION" in log or "Anomalies 2" in log, "anomalies reported")

    # Rotation: the yaw the server printed, and its exact delta, must be one of the probe's own steps.
    m = re.search(r"Last move ROT .*? yaw=(\S+) pitch", log)
    d = re.search(r"Exact dYaw=(\S+) dPitch", log)
    check(m is not None and d is not None, "rotation observed")
    if m and d:
        server_yaw = f32(float(m.group(1)))  # Java's Float.toString is the shortest repr that round-trips
        server_delta = float(d.group(1))     # Double.toString round-trips exactly
        steps = [(from_bits(a), from_bits(b)) for a, b in probe["yaw_steps_bits"]]
        matches = [(a, b) for a, b in steps if b == server_yaw and (b - a) == server_delta]
        check(bool(matches), f"server yaw {server_yaw!r} with exact delta {server_delta!r} matches a step the probe sent, bit for bit")
    if len(sys.argv) > 3:
        verify_recording(probe, sys.argv[3], sys.argv[4], sys.argv[5], check)
    print("RESULT:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


def verify_recording(probe, recording, inspect_path, dump_path, check):
    """The recording, read back offline, must contain exactly what the probe sent."""
    check(bool(recording), "a finished .wnrec recording exists")
    if not recording:
        return
    summary = json.loads(open(inspect_path).read())
    check(summary["complete"] is True, f"recording is complete (report: {summary['report']})")
    events = [json.loads(line) for line in open(dump_path) if line.strip()]

    def inbound(packet_type):
        return [e["packet"] for e in events if e["type"] == "Inbound" and e["packet"]["type"] == packet_type]

    rotations = [p for p in inbound("MovePlayer") if p["hasRotation"] and not p["hasPosition"]]
    recorded_yaw = [int(p["yaw"]["bits"], 16) for p in rotations]
    sent_yaw = [b for _, b in probe["yaw_steps_bits"]]
    check(recorded_yaw == sent_yaw, f"all {len(sent_yaw)} rotation packets recorded in order with identical float bits")
    check(all(int(p["pitch"]["bits"], 16) == probe["pitch_bits"] for p in rotations), "pitch bits identical in every rotation packet")
    ticks = len(inbound("ClientTickEnd"))
    check(ticks == probe["tick_ends_sent"], f"every ClientTickEnd recorded ({ticks} = {probe['tick_ends_sent']})")
    inputs = [(1 if p["forward"] else 0) for p in inbound("PlayerInput")]
    check(inputs == probe["inputs_sent"], f"key-state sequence recorded exactly ({len(inputs)} PlayerInput packets)")
    anomalies = [e for e in events if e["type"] == "Anomaly"]
    kinds = [a["kind"] for a in anomalies]
    check("SKIPPED_TRANSACTION" in kinds, "withheld transaction preserved in the recording")
    check(any(a["kind"] == "UNKNOWN_TRANSACTION" and "574ebeef" in a["detail"] for a in anomalies), "forged transaction id preserved")
    bound = [e for e in events if e["type"] == "Bound"]
    check(len(bound) == 1 and bound[0]["playerName"].startswith("anon-"), "identity pseudonymised in the recording")
    raw = open(recording, "rb").read()
    check(b"WhyNotProbe" not in raw and "WhyNotProbe" not in open(dump_path).read(), "real player name appears nowhere in the file")
    check(events[-1]["type"] == "Disconnected", "recording ends with the disconnect")


if __name__ == "__main__":
    main()
