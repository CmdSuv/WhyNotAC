#!/usr/bin/env python3
"""
One leg of the movement course for the real client, through OS-level input (XTest). The server builds the
course and places the player at the start of each leg (run_realclient.sh); this presses the keys.

Each step is KEYS/SECONDS: hold the comma-separated keys together for that long, then release them.
KEYS "-" waits. KEYS/SECONDS/TAP also taps the key TAP every 0.6 s while holding, KEYS/SECONDS/TAP/DELAY the first
time after DELAY seconds. A key "Button1".."Button3" is a mouse button (Button3: use).

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
        delay = float(parts[3]) if len(parts) > 3 else 0.0
        for key in keys:
            press(i, key, True)
        end = time.monotonic() + seconds
        time.sleep(delay)
        while tap and time.monotonic() + 0.1 < end:
            press(i, tap, True)
            time.sleep(0.1)
            press(i, tap, False)
            time.sleep(min(0.5, max(0.0, end - time.monotonic())))
        time.sleep(max(0.0, end - time.monotonic()))
        for key in reversed(keys):
            press(i, key, False)


def press(i, key, down):
    if key.startswith("Button"):
        i.button(int(key[len("Button"):]), down)
    else:
        i.key(key, down)


if __name__ == "__main__":
    main()
