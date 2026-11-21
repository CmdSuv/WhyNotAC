#!/usr/bin/env python3
"""
One step of the reach stage for the real client, through OS-level input (XTest): press a key or click
the attack button once. The server places the player and the target (run_realclient.sh).

usage: reach.py DISPLAY key KEY | reach.py DISPLAY click
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # run with -I: import our own helper explicitly
from xinput import Input  # noqa: E402


def main():
    i = Input(sys.argv[1])
    if sys.argv[2] == "key":
        i.tap(sys.argv[3], 0.05)
    elif sys.argv[2] == "click":
        i.click(1, 0.05)
    else:
        raise SystemExit("unknown step " + sys.argv[2])


if __name__ == "__main__":
    main()
