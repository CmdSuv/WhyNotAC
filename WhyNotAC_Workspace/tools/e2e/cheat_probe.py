#!/usr/bin/env python3
"""
WhyNotAC Live Cheat & Mitigation E2E Client (DATA-02 / PaperVerdictBridge verification):
Reuses probe_client's protocol-774 session framing to connect to a real Paper 1.21.11 server
running WhyNotAC with ARMED mitigations enabled, populates a clean safe movement trail, then
executes realistic cheats (Reach burst, Vertical Fly, Split-Tick, Timer burst, Silent KillAura/NoSwing)
and records server setbacks, target survival, and detections.
"""
import json
import math
import os
import struct
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_client as pc


class CheatClient(pc.Client):
    def __init__(self, host, port, name):
        super().__init__(host, port, name)
        self.setback_positions = []
        self.spawn_position = None

    def handle(self, packet_id, r):
        if self.state == "play" and packet_id == pc.PLAY_C_POSITION:
            super().handle(packet_id, r)
            if self.spawn_position is None:
                self.spawn_position = self.position
            else:
                self.setback_positions.append(self.position)
            return
        super().handle(packet_id, r)


def main():
    host, port, name, seconds = sys.argv[1], int(sys.argv[2]), sys.argv[3], float(sys.argv[4])
    client = CheatClient(host, port, name)
    client.login()
    reader = threading.Thread(target=client.reader_loop, daemon=True)
    reader.start()

    if not client.loaded.wait(timeout=20):
        raise SystemExit("cheat_probe never reached play state: " + repr(client.disconnect_reason))

    x, y, z, yaw, pitch = client.position
    tick = 0
    end_tick = int(seconds * 20)
    reach_attacks = 0
    fly_injected = False
    clock_burst_sent = False
    split_tick_sent = False
    silent_killaura_sent = False
    packet_bounds_sent = False
    attacked_decoys = set()

    start = time.monotonic()
    handled_setbacks = 0
    while client.alive and tick < end_tick:
        # Sync position cleanly if server set us back (SETBACK_POSITION) and confirm ground standing
        if len(client.setback_positions) > handled_setbacks:
            handled_setbacks = len(client.setback_positions)
            x, y, z, yaw, pitch = client.position
            client.send(pc.PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 1))

        # Phase 1 (ticks 5..14): clean rotation updates populate PaperVerdictBridge 3-step safeTrails cleanly
        if 5 <= tick < 15:
            yaw = pc.f32(yaw + 3.0)
            client.send(pc.PLAY_S_MOVE_ROT, struct.pack(">ffB", yaw, pitch, 1))
        elif tick in (30, 50, 70):
            # Regular 20-tick vanilla standing heartbeat keeps MovementTracker base position fresh
            client.send(pc.PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 1))

        # Active Decoy Probe response (before any tick movement packet, during combat windows)
        if (80 <= tick <= 112) or (tick >= 145):
            for eid in list(client.entities.keys()):
                if eid >= 1_900_000_000 and eid not in attacked_decoys:
                    attacked_decoys.add(eid)
                    client.send(pc.PLAY_S_INTERACT, pc.varint(eid) + pc.varint(1) + b"\x00")
                    client.send(pc.PLAY_S_SWING, pc.varint(0))

        # Phase 2 (ticks 80..112): Reach cheat — 8 attacks every 4 ticks against husky ~4.3 blocks east
        # (within Paper's 6.0 cap, outside vanilla 3.0 reach): ARMED attack-reach must cancel hits #2..#8
        if 80 <= tick <= 112 and tick % 4 == 0:
            husk_id = None
            best_d = 0.0
            for eid, (etype, ex, ey, ez) in client.entities.items():
                if etype == pc.ENTITY_HUSK:
                    d = math.dist((x, y, z), (ex, ey, ez))
                    if d > 3.5 and d > best_d:
                        best_d = d
                        husk_id = eid
            if husk_id is not None:
                _, ex, ey, ez = client.entities[husk_id]
                aim_yaw = pc.turn_towards(yaw, (x, y + 1.62, z), (ex, ey + 1.0, ez), pc.f32(0.5 * 0.6 + 0.2) ** 3 * 8.0)
                client.send(pc.PLAY_S_INTERACT, pc.varint(husk_id) + pc.varint(1) + b"\x00")
                client.send(pc.PLAY_S_SWING, pc.varint(0))
                if aim_yaw != yaw:
                    yaw = aim_yaw
                    client.send(pc.PLAY_S_MOVE_ROT, struct.pack(">ffB", yaw, pitch, 1))
                reach_attacks += 1

        # Quiet settle anchor for MovementTracker (ticks 126 and 132 >= SETTLE_MIN_TICKS apart, >= 12 quiet ticks)
        if tick in (126, 132):
            client.send(pc.PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 1))

        # Phase 3 (tick 140): Fly/Step cheat (+2.2 blocks upward) -> MovementCheck P-M + SETBACK_POSITION teleport
        if tick == 140 and not fly_injected:
            client.send(pc.PLAY_S_MOVE_POS, struct.pack(">dddB", x, y + 2.2, z, 0))
            fly_injected = True

        # Phase 4 (tick 180): Split-Tick (two extra movement packets in one tick)
        if tick == 180 and not split_tick_sent:
            client.send(pc.PLAY_S_MOVE_ROT, struct.pack(">ffB", pc.f32(yaw + 1.5), pitch, 1))
            yaw = pc.f32(yaw + 3.0)
            client.send(pc.PLAY_S_MOVE_ROT, struct.pack(">ffB", yaw, pitch, 1))
            split_tick_sent = True

        # Phase 5 (tick 200): Silent KillAura + NoSwing against dedicated in-reach husk (d < 3.5) -> attack-aim + action-grammar
        if tick == 200 and not silent_killaura_sent:
            for eid, (etype, ex, ey, ez) in client.entities.items():
                if etype == pc.ENTITY_HUSK and math.dist((x, y, z), (ex, ey, ez)) < 3.5:
                    client.send(pc.PLAY_S_INTERACT, pc.varint(eid) + pc.varint(1) + b"\x00")
                    client.send(pc.PLAY_S_MOVE_ROT, struct.pack(">ffB", pc.f32(yaw + 135.0), pitch, 1))
                    yaw = pc.f32(yaw + 135.0)
                    silent_killaura_sent = True
                    break

        client.send(pc.PLAY_S_TICK_END)

        # Phase 6 (tick 220): Timer 1.5x burst (30 extra ClientTickEnd -> client-clock)
        if tick == 220 and not clock_burst_sent:
            for _ in range(30):
                client.send(pc.PLAY_S_TICK_END)
            clock_burst_sent = True

        # Phase 7 (tick 235): Out-of-bounds UseItemOn cursor offset (2.5, 0.5, 0.5) -> packet-bounds (P-M)
        if tick == 235 and not packet_bounds_sent:
            bx, by, bz = int(math.floor(x)), int(math.floor(y)) - 1, int(math.floor(z))
            block_pos_long = ((bx & 0x3FFFFFF) << 38) | ((bz & 0x3FFFFFF) << 12) | (by & 0xFFF)
            client.send(
                pc.PLAY_S_USE_ITEM_ON,
                pc.varint(0) + struct.pack(">Q", block_pos_long) + pc.varint(1)
                + struct.pack(">fff??", 2.5, 0.5, 0.5, False, False) + pc.varint(1)
            )
            packet_bounds_sent = True

        tick += 1
        start = max(start, time.monotonic() - 0.5 - tick * 0.05)
        time.sleep(max(0.0, start + tick * 0.05 - time.monotonic()))

    time.sleep(0.5)
    summary = {
        "still_connected": client.alive,
        "spawn_position": client.spawn_position,
        "final_position": client.position,
        "setback_teleports": client.setback_positions,
        "reach_attacks_sent": reach_attacks,
        "fly_injected": fly_injected,
        "split_tick_sent": split_tick_sent,
        "silent_killaura_sent": silent_killaura_sent,
        "clock_burst_sent": clock_burst_sent,
        "disconnect_reason": client.disconnect_reason,
    }
    print(json.dumps(summary))
    client.sock.close()


if __name__ == "__main__":
    main()
