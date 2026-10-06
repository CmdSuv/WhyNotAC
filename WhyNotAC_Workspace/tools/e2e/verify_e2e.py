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
    print("RESULT:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
