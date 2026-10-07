#!/usr/bin/env python3
"""
WhyNotAC load bots: many honest protocol-774 clients in one process (one reader thread and one tick
thread per bot), built on the E2E probe's protocol code (tools/e2e/probe_client.py).

Every bot behaves like a legitimate vanilla client: it answers every keep-alive and every transaction
ping, walks slowly in a circle on the ground, turns with the exact vanilla mouse arithmetic, toggles
its movement keys, swings its arm now and then, and ends every tick with ClientTickEnd. It never
breaks a rule on purpose, so any anomaly or divergence WhyNotAC reports for these bots under load is
a false positive.

Usage: load_bots.py HOST PORT NAME_PREFIX FIRST COUNT SECONDS [LOGIN_INTERVAL] [STOP_FILE]
Every bot stops after SECONDS, or as soon as STOP_FILE exists (the runner's end of measurement).
Prints one JSON summary line for all bots of this process.
"""
import collections
import json
import math
import os
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "e2e"))
import probe_client as pc  # noqa: E402  (stdlib-only module of this repository)

PLAY_S_SWING = 0x3C
RADIUS = 2.0           # blocks
ANGULAR_STEP = 0.05    # radians per tick -> 0.1 blocks per tick, about half walking speed


class LoadBot(pc.Client):
    def __init__(self, host, port, name):
        super().__init__(host, port, name)
        self.sock.settimeout(120)  # a loaded server may go quiet for a while; that is measured, not fatal
        self.trace = collections.deque(maxlen=0)  # the probe's trace is not needed here
        self.teleports = 0
        # A teleport (accept + move to the new spot, sent by the reader thread) and a tick's move (tick
        # thread) must not interleave: a move from the old spot after the accept is "moved too quickly".
        self.move_lock = threading.Lock()

    def handle(self, packet_id, r):
        if self.state == "play" and packet_id == pc.PLAY_C_POSITION:
            with self.move_lock:
                super().handle(packet_id, r)
                self.teleports += 1
        else:
            super().handle(packet_id, r)


def run_bot(host, port, name, seconds, results, start_gate, stop_file):
    result = {"name": name, "joined": False, "ticks": 0, "pings_answered": 0, "pings_seen": 0,
              "teleports": 0, "still_connected": False, "error": None, "disconnect_reason": None}
    results.append(result)
    try:
        bot = LoadBot(host, port, name)
        bot.login()
        threading.Thread(target=bot.reader_loop, daemon=True, name=name + "-reader").start()
        if not bot.loaded.wait(120):
            raise RuntimeError("never reached play state: " + str(bot.disconnect_reason))
        result["joined"] = True
        start_gate.wait()

        d = 0.5 * pc.f32(0.6) + pc.f32(0.2)
        multiplier = d * d * d * 8.0
        x0, y0, z0, yaw, pitch = bot.position
        center, seen_teleports, angle = (x0 - RADIUS, z0), bot.teleports, 0.0
        tick, sent_input = 0, None
        start = time.monotonic()
        while bot.alive and time.monotonic() - start < seconds:
            if tick % 20 == 0 and stop_file and os.path.exists(stop_file):
                break
            forward = (tick // 60) % 2 == 0
            flags = 0x01 if forward else 0x00
            if flags != sent_input:
                bot.send(pc.PLAY_S_INPUT, bytes([flags]))
                sent_input = flags
            counts = (tick % 9) - 4
            yaw = pc.f32(yaw + pc.f32(pc.f32(counts * multiplier) * pc.f32(0.15)))
            with bot.move_lock:
                if bot.teleports != seen_teleports:  # moved by the server (spreadplayers): walk around the new spot
                    seen_teleports = bot.teleports
                    x0, y0, z0, _, _ = bot.position
                    center, angle = (x0 - RADIUS, z0), 0.0
                angle += ANGULAR_STEP
                x, z = center[0] + RADIUS * math.cos(angle), center[1] + RADIUS * math.sin(angle)
                bot.send(pc.PLAY_S_MOVE_POS_ROT, struct.pack(">dddffB", x, y0, z, yaw, pitch, 1))
            if tick % 25 == 0:
                bot.send(PLAY_S_SWING, pc.varint(0))
            bot.send(pc.PLAY_S_TICK_END)
            tick += 1
            time.sleep(max(0.0, start + tick * 0.05 - time.monotonic()))
        result.update(ticks=tick, pings_answered=bot.pings_answered, pings_seen=bot.pings_seen,
                      teleports=bot.teleports, still_connected=bot.alive, disconnect_reason=bot.disconnect_reason)
        bot.alive = False
        bot.sock.close()
    except Exception as e:  # reported, never hidden: a bot that fails is part of the result
        result["error"] = f"{type(e).__name__}: {e}"


def main():
    host, port, prefix = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    first, count, seconds = int(sys.argv[4]), int(sys.argv[5]), float(sys.argv[6])
    login_interval = float(sys.argv[7]) if len(sys.argv) > 7 else 0.1
    stop_file = sys.argv[8] if len(sys.argv) > 8 else None
    results, threads = [], []
    start_gate = threading.Event()  # everyone starts ticking together once all have joined (or failed)
    for i in range(first, first + count):
        t = threading.Thread(target=run_bot, args=(host, port, f"{prefix}{i}", seconds, results, start_gate, stop_file), daemon=True)
        t.start()
        threads.append(t)
        time.sleep(login_interval)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline and sum(1 for r in results if r["joined"] or r["error"]) < count:
        time.sleep(0.2)
    start_gate.set()
    for t in threads:
        t.join(seconds + 60)
    print(json.dumps({
        "bots": count,
        "joined": sum(r["joined"] for r in results),
        "still_connected": sum(r["still_connected"] for r in results),
        "errors": [r for r in results if r["error"]][:10],
        "disconnects": [r for r in results if r["joined"] and not r["still_connected"]][:10],
        "ticks": sum(r["ticks"] for r in results),
        "pings_seen": sum(r["pings_seen"] for r in results),
        "pings_answered": sum(r["pings_answered"] for r in results),
    }))


if __name__ == "__main__":
    main()
