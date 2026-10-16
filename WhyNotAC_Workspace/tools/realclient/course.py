#!/usr/bin/env python3
"""
One leg of the movement course for the real client, through OS-level input (XTest). The server builds the
course and places the player at the start of each leg (run_realclient.sh); this presses the keys.

Each step is KEYS/SECONDS: hold the comma-separated keys together for that long, then release them.
KEYS "-" waits. KEYS/SECONDS/TAP also taps the key TAP every 0.6 s while holding.

usage: course.py DISPLAY STEP...     e.g. course.py :99 w/2.0 w,Control_L/1.5/space -/0.5
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # run with -I: import our own helper explicitly
from xinput import Input  # noqa: E402


def main():
    i = Input(sys.argv[1])
    for step in sys.argv[2:]:
        parts = step.split("/")
        keys = [] if parts[0] == "-" else parts[0].split(",")
        seconds = float(parts[1])
        tap = parts[2] if len(parts) > 2 else None
        for key in keys:
            i.key(key, True)
        end = time.monotonic() + seconds
        while tap and time.monotonic() + 0.1 < end:
            i.tap(tap, 0.1)
            time.sleep(min(0.5, max(0.0, end - time.monotonic())))
        time.sleep(max(0.0, end - time.monotonic()))
        for key in reversed(keys):
            i.key(key, False)


if __name__ == "__main__":
    main()
