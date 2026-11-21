#!/usr/bin/env python3
"""
A random walk over the movement course for the real client (PHY-A10: the client's own physics is the oracle, every
tick of it compared with the engine and claimed by the movement check afterwards). From a seed and the walk's number,
deterministically: where along the course it starts (x, on the course's lane at z 0.5), the yaw it starts with, and
the steps for course.py (KEYS/SECONDS): any of the movement keys, jump, sneak and sprint held together for 0.05 to
0.7 s, now and then nothing, about eight seconds in all. The same seed walks the same keys (the timing of a real
client still varies by a tick or so).

usage: fuzz.py SEED WALK     prints "X YAW STEP..."
"""
import random
import sys

MOVES = ["w", "a", "s", "d"]
SECONDS = 8.0


def walk(seed, number):
    r = random.Random(f"{seed}/{number}")
    x = r.randrange(41, 176) + 0.5
    yaw = r.randrange(-180, 180)
    steps = []
    total = 0.0
    while total < SECONDS:
        keys = [k for k in MOVES if r.random() < 0.35]
        if r.random() < 0.25:
            keys.append("space")
        if r.random() < 0.15:
            keys.append("Shift_L")
        elif "w" in keys and r.random() < 0.4:
            keys.append("Control_L")
        seconds = round(r.uniform(0.05, 0.7), 2)
        steps.append((",".join(keys) if keys else "-") + f"/{seconds}")
        total += seconds
    return x, yaw, steps


def main():
    x, yaw, steps = walk(sys.argv[1], int(sys.argv[2]))
    print(x, yaw, " ".join(steps))


if __name__ == "__main__":
    main()
