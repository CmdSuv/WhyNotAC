#!/usr/bin/env python3
"""
Aligns the client's own per-tick state (truth.jsonl, written by the truth agent inside the real client)
with what WhyNotAC recorded on the server (dump.jsonl).

From each tick's state before and after LocalPlayer.tick(), the movement packet the client's
sendPosition() sent is reconstructed exactly (vanilla rules: nothing while the connection has not loaded
the player, which tick() checks first; otherwise the position was sent iff positionReminder was reset to 0, the rotation iff yRotLast/xRotLast changed, a status-only packet iff onGround or
horizontalCollision changed). A passenger sends its rotation every tick instead (MovePlayer.Rot), and the vehicle
it steers its move (MoveVehicle: the boat's state after its own tick, which runs first). Those sequences must equal
the server-side recording's MovePlayer and MoveVehicle packets, bit for bit, in order. This is the ground truth the
physics engine is tested against: the client's exact position, velocity and collision flags for every tick, aligned
with the packets.

usage: verify_truth.py <work-dir>     (prints PASS/FAIL lines; exit code 0 = aligned)
"""
import json
import os
import sys


def bits(value):
    """Hex bits from either side ("0x..." in the dump, bare hex from the agent) -> int."""
    return int(value, 16)


def client_sent(records):
    """The MovePlayer packets the client sent, reconstructed from its pre/post tick states."""
    sent = []
    pre = None
    for r in records:
        if r["hook"] in ("boatPre", "boatPost"):
            continue
        if r["hook"] == "pre":
            pre = r
            continue
        if pre is None or not pre["loaded"]:
            pre = None
            continue  # not loaded yet: LocalPlayer.tick() returned before super.tick() and sendPosition()
        if r["passenger"]:  # a passenger when it sends: ServerboundMovePlayerPacket.Rot, every tick
            sent.append({"tick": r["tickCount"], "hasPosition": False, "hasRotation": True, "pos": None,
                         "rot": (bits(r["yRot"]), bits(r["xRot"])), "flags": (r["onGround"], r["horizontalCollision"])})
            pre = None
            continue
        pos_sent = r["positionReminder"] == 0
        rot_sent = r["yRotLast"] != pre["yRotLast"] or r["xRotLast"] != pre["xRotLast"]
        # sendPosition compares the flags at send time (= after the tick) with the last ones it sent (= before)
        status_changed = pre["lastOnGround"] != r["onGround"] or pre["lastHorizontalCollision"] != r["horizontalCollision"]
        flags = (r["onGround"], r["horizontalCollision"])
        if pos_sent or rot_sent or status_changed:
            sent.append({
                "tick": r["tickCount"],
                "hasPosition": pos_sent,
                "hasRotation": rot_sent,
                "pos": tuple(bits(c) for c in r["pos"]) if pos_sent else None,
                "rot": (bits(r["yRot"]), bits(r["xRot"])) if rot_sent else None,
                "flags": flags,
            })
        pre = None
    return sent


def client_vehicle_moves(records):
    """The MoveVehicle packets the client sent: each steered boat's state after its tick, sent by its passenger's tick after it."""
    moves = []
    boat = None
    for r in records:
        if r["hook"] == "boatPost":
            boat = r
        elif r["hook"] == "post":
            if boat is not None and r["passenger"]:
                moves.append({"pos": tuple(bits(c) for c in boat["pos"]), "rot": (bits(boat["yRot"]), bits(boat["xRot"])), "onGround": boat["onGround"]})
            boat = None
    return moves


def server_vehicle_moves(events):
    """MoveVehicle packets on the server timeline, and how many vehicle moves the server sent back (its corrections)."""
    moves = []
    corrections = 0
    for e in events:
        if e["type"] == "Outbound" and e["packet"]["type"] == "MoveVehicle":
            corrections += 1
        if e["type"] != "Inbound" or e["packet"]["type"] != "MoveVehicle":
            continue
        p = e["packet"]
        moves.append({"pos": tuple(bits(p["position"][a]["bits"]) for a in ("x", "y", "z")),
                      "rot": (bits(p["yaw"]["bits"]), bits(p["pitch"]["bits"])), "onGround": p["onGround"]})
    return moves, corrections


def server_received(events):
    """MovePlayer packets on the server timeline, without teleport replies (sent at frame start, not in a tick)."""
    received = []
    after_accept = False
    for e in events:
        if e["type"] != "Inbound":
            continue
        p = e["packet"]
        teleport_reply = after_accept and p["type"] == "MovePlayer"
        after_accept = p["type"] == "AcceptTeleportation"
        if p["type"] != "MovePlayer" or teleport_reply:
            continue
        received.append({
            "hasPosition": p["hasPosition"],
            "hasRotation": p["hasRotation"],
            "pos": tuple(bits(p[a]["bits"]) for a in ("x", "y", "z")) if p["hasPosition"] else None,
            "rot": (bits(p["yaw"]["bits"]), bits(p["pitch"]["bits"])) if p["hasRotation"] else None,
            "flags": (p["onGround"], p["horizontalCollision"]),
        })
    return received


def main():
    work = sys.argv[1]
    failures = []

    def check(condition, message):
        print(("PASS " if condition else "FAIL ") + message)
        if not condition:
            failures.append(message)

    path = os.path.join(work, "truth.jsonl")
    records = [json.loads(line) for line in open(path) if line.strip()] if os.path.exists(path) else []
    check(len(records) > 400, f"the truth agent recorded the client's state every tick ({len(records)} records)")
    events = [json.loads(line) for line in open(os.path.join(work, "dump.jsonl")) if line.strip()]
    sent = client_sent(records)
    received = server_received(events)
    keys = ("hasPosition", "hasRotation", "pos", "rot", "flags")
    mismatch = next((i for i, (a, b) in enumerate(zip(sent, received)) if any(a[k] != b[k] for k in keys)), None)
    aligned = mismatch is None and len(sent) == len(received)
    detail = (f"{len(sent)} packets" if aligned else
              f"client {len(sent)} vs server {len(received)}; first difference at {mismatch}: "
              f"client {sent[mismatch] if mismatch is not None else '-'} server {received[mismatch] if mismatch is not None else '-'}")
    check(aligned and len(sent) > 50, "every movement packet the server recorded is exactly the client's own state when it sent it, "
                                      f"bit for bit, in order ({detail})")
    vehicle_sent = client_vehicle_moves(records)
    vehicle_received, corrections = server_vehicle_moves(events)
    vmismatch = next((i for i, (a, b) in enumerate(zip(vehicle_sent, vehicle_received)) if a != b), None)
    valigned = vmismatch is None and len(vehicle_sent) == len(vehicle_received) and corrections == 0
    vdetail = (f"{len(vehicle_sent)} packets" if valigned else
               f"client {len(vehicle_sent)} vs server {len(vehicle_received)}, {corrections} server corrections; first difference at {vmismatch}: "
               f"client {vehicle_sent[vmismatch] if vmismatch is not None else '-'} server {vehicle_received[vmismatch] if vmismatch is not None else '-'}")
    check(valigned and len(vehicle_sent) > 50, "every vehicle move the server recorded is exactly the steered boat's own state after its tick, "
                                               f"bit for bit, in order ({vdetail})")
    moving = [r for r in records if r["hook"] == "post" and any(bits(c) & 0x7FFFFFFFFFFFFFFF for c in r["deltaMovement"])]
    check(len(moving) > 50, f"the ground truth includes the client's exact velocity on {len(moving)} ticks")
    print("TRUTH:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
