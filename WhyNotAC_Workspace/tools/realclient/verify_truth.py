#!/usr/bin/env python3
"""
Aligns the client's own per-tick state (truth.jsonl, written by the truth agent inside the real client)
with what WhyNotAC recorded on the server (dump.jsonl).

From each tick's state before and after LocalPlayer.tick(), the movement packet the client's
sendPosition() sent is reconstructed exactly (vanilla rules: nothing while the connection has not loaded
the player, which tick() checks first; otherwise the position was sent iff positionReminder was reset to 0, the rotation iff yRotLast/xRotLast changed, a status-only packet iff onGround or
horizontalCollision changed). A passenger sends its rotation every tick instead (MovePlayer.Rot), and the vehicle
it steers its move (MoveVehicle: the boat's state after its own tick, which runs first). Those sequences must equal
the server-side recording's MovePlayer and MoveVehicle packets, bit for bit, in order, but for the client's replies to
the rotations the server set (ClientPacketListener.handleRotatePlayer: Rot(yRot, xRot, false, false), sent at once while
it processes packets, not in a tick), each found and set aside. This is the ground truth the
physics engine is tested against: the client's exact position, velocity and collision flags for every tick, aligned
with the packets.

usage: verify_truth.py <work-dir>     (prints PASS/FAIL lines; exit code 0 = aligned)
"""
import json
import os
import struct
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
    """The MoveVehicle packets the client sent: each steered boat's or ridden horse's state after its tick, sent by its
    passenger's tick after it (ServerboundMoveVehiclePacket.fromEntity: while it still interpolates, the interpolation's
    target and rotations)."""
    moves = []
    boat = None
    for r in records:
        if r["hook"] in ("boatPost", "horsePost"):
            boat = r
        elif r["hook"] == "post":
            if boat is not None and r["passenger"]:
                if boat["interpolating"]:
                    moves.append({"pos": tuple(bits(c) for c in boat["interpolationPos"]),
                                  "rot": (bits(boat["interpolationYRot"]), bits(boat["interpolationXRot"])), "onGround": boat["onGround"]})
                else:
                    moves.append({"pos": tuple(bits(c) for c in boat["pos"]), "rot": (bits(boat["yRot"]), bits(boat["xRot"])), "onGround": boat["onGround"]})
            boat = None
    return moves


def server_vehicle_moves(events):
    """MoveVehicle packets on the server timeline from the vehicle's ticks, the vehicle moves the server sent back (its
    corrections: "moved wrongly") and the client's answers to them. The client answers each where it handles it, at frame
    start and not in a tick (ClientPacketListener.handleMoveVehicle: snapped to the server's position when it is more
    than 1e-5 away, then ServerboundMoveVehiclePacket.fromEntity): the next MoveVehicle after one, left out here, and
    counted as matching when it is at the correction's position."""
    moves = []
    corrections = 0
    answered = 0
    pending = None
    for e in events:
        if e["type"] == "Outbound" and e["packet"]["type"] == "MoveVehicle":
            corrections += 1
            pending = tuple(bits(e["packet"]["position"][a]["bits"]) for a in ("x", "y", "z"))
        if e["type"] != "Inbound" or e["packet"]["type"] != "MoveVehicle":
            continue
        p = e["packet"]
        pos = tuple(bits(p["position"][a]["bits"]) for a in ("x", "y", "z"))
        if pending is not None:
            answered += pos == pending
            pending = None
            continue
        moves.append({"pos": pos, "rot": (bits(p["yaw"]["bits"]), bits(p["pitch"]["bits"])), "onGround": p["onGround"]})
    return moves, corrections, answered


def f32(field):
    return struct.unpack(">f", struct.pack(">I", bits(field["bits"])))[0]


def rotation_windows(events):
    """Each rotation the server set: the event indices between which the client handled it (after its answer to the
    last ping sent before it, before its answer to the next) and its reply's yaw and pitch where known (an absolute
    one: 0.0F + the value, the pitch clamped to +-90, which % 360 keeps; None where relative or not finite)."""
    pings = {}
    ping_seqs = []
    acks = {}
    rotations = []
    for index, e in enumerate(events):
        if e["type"] == "Outbound" and e["packet"]["type"] == "Ping":
            pings[e["packet"]["id"]] = e["sequence"]
            ping_seqs.append(e["sequence"])
        elif e["type"] == "Outbound" and e["packet"]["type"] == "PlayerRotation":
            p = e["packet"]
            yaw = None if p["relativeYaw"] else f32(p["yaw"]) + 0.0
            pitch = None if p["relativePitch"] else min(max(f32(p["pitch"]) + 0.0, -90.0), 90.0)
            yaw = yaw if yaw is not None and abs(yaw) != float("inf") and yaw == yaw else None
            pitch = pitch if pitch is not None and pitch == pitch else None
            before = ping_seqs[-1] if ping_seqs else None
            rotations.append({"sequence": e["sequence"], "before": before, "yaw": yaw, "pitch": pitch})
        elif e["type"] == "Acknowledged" and e["transactionId"] in pings:
            acks.setdefault(pings[e["transactionId"]], index)
    for r in rotations:
        after = next((q for q in ping_seqs if q > r["sequence"]), None)
        r["start"] = 0 if r["before"] is None else acks.get(r["before"], len(events))
        r["end"] = acks.get(after, len(events))
        r["answered"] = after in acks
    return rotations


def server_received(events):
    """MovePlayer packets on the server timeline, without teleport replies (sent at frame start, not in a tick), each with
    the rotations whose reply it may be (handled in its window, its shape and rotation)."""
    rotations = rotation_windows(events)
    received = []
    after_accept = False
    for index, e in enumerate(events):
        if e["type"] != "Inbound":
            continue
        p = e["packet"]
        teleport_reply = after_accept and p["type"] == "MovePlayer"
        after_accept = p["type"] == "AcceptTeleportation"
        if p["type"] != "MovePlayer" or teleport_reply:
            continue
        shaped = p["hasRotation"] and not p["hasPosition"] and not p["onGround"] and not p["horizontalCollision"]
        replies = [k for k, r in enumerate(rotations) if shaped and r["start"] < index < r["end"]
                   and (r["yaw"] is None or bits(p["yaw"]["bits"]) == struct.unpack(">I", struct.pack(">f", r["yaw"]))[0])
                   and (r["pitch"] is None or bits(p["pitch"]["bits"]) == struct.unpack(">I", struct.pack(">f", r["pitch"]))[0])]
        received.append({
            "replies": replies,
            "hasPosition": p["hasPosition"],
            "hasRotation": p["hasRotation"],
            "pos": tuple(bits(p[a]["bits"]) for a in ("x", "y", "z")) if p["hasPosition"] else None,
            "rot": (bits(p["yaw"]["bits"]), bits(p["pitch"]["bits"])) if p["hasRotation"] else None,
            "flags": (p["onGround"], p["horizontalCollision"]),
        })
    return received, rotations


KEYS = ("hasPosition", "hasRotation", "pos", "rot", "flags")


def without_replies(sent, received):
    """The received packets with each rotation's reply set aside: a candidate is the reply where it is not the client's
    next packet of its own, or where the packet after it is that one too. Also the rotations answered so."""
    same = lambda a, b: all(a[k] == b[k] for k in KEYS)
    taken = set()
    out = []
    for j, r in enumerate(received):
        k = next((k for k in r["replies"] if k not in taken), None)
        if k is not None:
            i = len(out)
            own = i < len(sent) and same(sent[i], r)
            next_own = i < len(sent) and j + 1 < len(received) and same(sent[i], received[j + 1])
            if not own or next_own:
                taken.add(k)
                continue
        out.append(r)
    return out, taken


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
    received, rotations = server_received(events)
    received, replied = without_replies(sent, received)
    answered = [k for k, r in enumerate(rotations) if r["answered"]]
    check(len(answered) > 0 and set(answered) == replied,
          f"the client answered each rotation the server set at once, outside its ticks, with the rotation it set "
          f"({len(replied)} replies, {len(answered)} rotations handled of {len(rotations)})")
    keys = KEYS
    mismatch = next((i for i, (a, b) in enumerate(zip(sent, received)) if any(a[k] != b[k] for k in keys)), None)
    aligned = mismatch is None and len(sent) == len(received)
    detail = (f"{len(sent)} packets" if aligned else
              f"client {len(sent)} vs server {len(received)}; first difference at {mismatch}: "
              f"client {sent[mismatch] if mismatch is not None else '-'} server {received[mismatch] if mismatch is not None else '-'}")
    check(aligned and len(sent) > 50, "every movement packet the server recorded is exactly the client's own state when it sent it, "
                                      f"bit for bit, in order ({detail})")
    vehicle_sent = client_vehicle_moves(records)
    vehicle_received, corrections, answered = server_vehicle_moves(events)
    vmismatch = next((i for i, (a, b) in enumerate(zip(vehicle_sent, vehicle_received)) if a != b), None)
    valigned = vmismatch is None and len(vehicle_sent) == len(vehicle_received) and answered == corrections
    vdetail = (f"{len(vehicle_sent)} packets, {corrections} server corrections answered at their position" if valigned else
               f"client {len(vehicle_sent)} vs server {len(vehicle_received)}, {corrections} server corrections ({answered} answered at their "
               f"position); first difference at {vmismatch}: "
               f"client {vehicle_sent[vmismatch] if vmismatch is not None else '-'} server {vehicle_received[vmismatch] if vmismatch is not None else '-'}")
    check(valigned and len(vehicle_sent) > 50, "every vehicle move the server recorded is exactly the steered boat's own state after its tick, "
                                               f"bit for bit, in order ({vdetail})")
    moving = [r for r in records if r["hook"] == "post" and any(bits(c) & 0x7FFFFFFFFFFFFFFF for c in r["deltaMovement"])]
    check(len(moving) > 50, f"the ground truth includes the client's exact velocity on {len(moving)} ticks")
    print("TRUTH:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
