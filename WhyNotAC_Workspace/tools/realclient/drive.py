#!/usr/bin/env python3
"""
Scripted play for the real client through OS-level input (XTest). Prints a JSON summary of what was
done, so the checker can compare it with what the server recorded.

usage: drive.py DISPLAY
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # run with -I: import our own helper explicitly
from xinput import Input  # noqa: E402

TICK = 0.05


def main():
    i = Input(sys.argv[1])
    actions = []

    def log(what, **details):
        actions.append(dict(t=round(time.monotonic(), 3), what=what, **details))

    def hold(key, seconds):
        log("hold", key=key, seconds=seconds)
        i.key(key, True)
        time.sleep(seconds)
        i.key(key, False)

    time.sleep(1.0)
    # Mouse: one relative motion every two ticks, at several speeds. Two ticks apart, every motion lands
    # in its own frame and its own rotation packet, so the counts the server can infer must equal the
    # commanded counts one for one (which proves the exact float math AND the absolute scale).
    motions, vertical = [], []
    for speed in (1, 2, 3, 5, -4, -1, 7, -2, 11, -13):
        for _ in range(8):
            i.move(speed, 0)
            motions.append(speed)
            time.sleep(2 * TICK)
    for speed in (2, -3):
        for _ in range(8):
            i.move(0, speed)
            vertical.append(speed)
            time.sleep(2 * TICK)
    log("mouse", horizontal=motions, vertical=vertical)

    hold("w", 2.0)
    # Sprint: hold W and press the sprint key while moving.
    i.key("w", True)
    time.sleep(0.2)
    i.tap("Control_L", 0.1)
    time.sleep(1.5)
    log("sprint")
    for _ in range(3):
        i.tap("space", 0.1)
        time.sleep(0.6)
    log("jumps", count=3)
    i.key("w", False)
    time.sleep(0.5)
    hold("a", 0.8)
    hold("d", 0.8)
    hold("s", 0.8)
    hold("Shift_L", 1.0)
    for _ in range(5):
        i.click(1, 0.05)
        time.sleep(0.3)
    log("attacks", count=5)
    for slot in ("2", "3", "1"):
        i.tap(slot, 0.05)
        time.sleep(0.3)
    log("hotbar")
    i.tap("e", 0.05)
    time.sleep(0.8)
    i.tap("Escape", 0.05)
    time.sleep(0.8)
    log("inventory")
    i.tap("F2", 0.1)
    time.sleep(1.0)
    print(json.dumps({"actions": actions}))


if __name__ == "__main__":
    main()
