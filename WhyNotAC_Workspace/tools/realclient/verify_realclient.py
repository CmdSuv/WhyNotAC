#!/usr/bin/env python3
"""
Checks a real-client session (run_realclient.sh) against what was played and against WhyNotAC's
vanilla models. Exit code 0 means every check passed.

usage: verify_realclient.py <work-dir>
"""
import json
import os
import re
import sys


def aligned(observed, commanded):
    """True if the observed per-step counts are the commanded motions one for one, in order (a motion the
    client merged with its neighbour into one frame is accepted as their sum, at most twice). The vanilla
    client ignores the first cursor move after the window grabs the mouse and after the cursor enters
    the window (MouseHandler.onMove, ignoreFirstMove): up to two leading motions may be consumed so."""
    results = [_aligned(observed, commanded[skip:], skip) for skip in range(3)]
    return next((r for r in results if r[0]), results[0])


def _aligned(observed, commanded, skipped):
    i, merged = 0, 0
    for count in observed:
        if count is None or i >= len(commanded):
            return False, f"step {i}: {count} (commanded {commanded[i:i + 3]})"
        total, j = commanded[i], i + 1
        while total != count and j < len(commanded) and j - i < 3:
            total += commanded[j]
            j += 1
        if total != count:
            return False, f"step {i}: observed {count}, commanded {commanded[i:i + 3]}"
        merged += (j - i) - 1
        i = j
    if i != len(commanded) or merged > 2:
        return False, f"{i} of {len(commanded)} motions matched, {merged} merged"
    return True, f"{len(observed)} steps == {len(commanded)} commanded motions ({merged} merged, {skipped} leading consumed by ignoreFirstMove)"


def verify_reach(events, work, check):
    """The real client's attacks at the limits of its reach: the check assessed each one (no abstaining) and found it in reach."""
    audit = [json.loads(line) for line in open(os.path.join(work, "reach-audit.jsonl")) if line.strip()] \
        if os.path.exists(os.path.join(work, "reach-audit.jsonl")) else []
    husks = [e["packet"]["entityId"] for e in events if e["type"] == "Outbound" and e["packet"].get("type") == "AddEntity"
             and e["packet"].get("typeId") == "minecraft:husk" and float(e["packet"]["x"]["f64"]) == 10.5]
    target = husks[-1] if husks else None
    staged = [a for a in audit if a["target"] == target]
    check(audit and all(a["outcome"] != "OUT_OF_REACH" for a in audit),
          f"no attack of the real client is out of reach ({len(audit)} attacks: {sorted(set(a['outcome'] + ':' + a['reason'] for a in audit))})")
    check(len(staged) == 2, f"the client attacked the staged husk twice (4.70 with the stick, 2.98 with the sword) and not from 3.05: "
          f"{[(a['check'], a['distance']) for a in staged]}")
    if len(staged) == 2:
        stick, sword = staged
        check(stick["outcome"] == "IN_REACH" and stick["check"] == "4.5+0.25" and abs(stick["distance"] - 4.70) < 0.01
              and abs(stick["allowed"] - 4.75) < 1e-6,
              f"the stick's attack was assessed, in reach of its own range: {stick['distance']:.4f} <= {stick['allowed']:.4f}")
        check(sword["outcome"] == "IN_REACH" and sword["check"] == "none" and abs(sword["distance"] - 2.98) < 0.01
              and abs(sword["allowed"] - 3.0) < 1e-6,
              f"the sword's attack was assessed, strictly within the interaction range: {sword['distance']:.4f} < {sword['allowed']:.4f}")


def main():
    work = sys.argv[1]
    failures = []

    def check(condition, message):
        print(("PASS " if condition else "FAIL ") + message)
        if not condition:
            failures.append(message)

    log = open(os.path.join(work, "server.log"), encoding="utf-8", errors="replace").read()
    events = [json.loads(line) for line in open(os.path.join(work, "dump.jsonl")) if line.strip()]
    result = json.load(open(os.path.join(work, "check.json")))
    inbound = [e["packet"] for e in events if e["type"] == "Inbound"]

    check(re.search(r"RealProbe (was kicked|lost connection: (?!Disconnected))", log) is None,
          "the real client was never kicked or flagged by the server")
    observation = [e for e in events if e["type"] == "Observation"]
    check(len(observation) == 1 and observation[0]["inbound"] == "WIRE" and observation[0]["clientProtocol"] == 774,
          f"recorded at the network edge from the client's real handshake: {observation}")
    divergences = [e for e in events if e["type"] in ("InboundDivergence", "OutboundDivergence")]
    check(not divergences, f"edge and game agree on every packet of a real session ({len(divergences)} divergences)")
    brands = [p["brand"] for p in inbound if p["type"] == "ClientBrand"]
    check(brands == ["vanilla"], f"the unmodified client announces itself as vanilla: {brands}")
    spawns = [(e["packet"]["dimension"] or {}) for e in events
              if e["type"] == "Outbound" and e["packet"]["type"] in ("Login", "Respawn") and e["packet"].get("dimension")]
    lava = [(d.get("key"), d.get("fastLava")) for d in spawns]
    check(("minecraft:overworld", False) in lava and ("minecraft:the_nether", True) in lava and all(f is not None for _, f in lava),
          f"each dimension the client entered carries its lava speed, read from its type (fast in the nether only): {lava}")

    inputs = [p for p in inbound if p["type"] == "PlayerInput"]
    for key in ("forward", "backward", "left", "right", "jump", "shift", "sprint"):
        check(any(p[key] for p in inputs), f"key '{key}' pressed with real input reached the server")
    swings = [p for p in inbound if p["type"] == "Swing"]
    check(len(swings) >= 5, f"left clicks became swings ({len(swings)})")
    # The action grammar, read independently of the action-grammar check: each attack is followed by the main hand's
    # swing (the network thread's keep-alive and chunk-batch answers aside), and every action handleKeybinds sends
    # comes before the tick's own movement packet (the reply right after a teleport's accept aside; this session is
    # sent no rotation or entity teleport).
    keybind = ("InteractEntity", "UseItemOn", "UseItem", "SetCarriedItem", "PlayerAction")
    attacks, actions, broken, due, moved, accepted = 0, {}, [], None, False, False
    for i, p in enumerate(inbound):
        if p["type"] in ("KeepAlive", "ChunkBatchReceived"):
            continue
        if due is not None and not (p["type"] == "Swing" and p["hand"] == "MAIN_HAND"):
            broken.append((due, "no swing", p["type"]))
        due = None
        if p["type"] == "AcceptTeleportation":
            accepted = True
        elif p["type"] == "MovePlayer":
            moved |= not accepted
            accepted = False
        elif p["type"] == "ClientTickEnd":
            moved = False
        elif p["type"] in keybind and p.get("action") != "UNKNOWN":
            actions[p["type"]] = actions.get(p["type"], 0) + 1
            if moved:
                broken.append((i, "after the tick's movement", p["type"]))
            if p["type"] == "InteractEntity" and p["kind"] == "ATTACK":
                attacks += 1
                due = i
    # Digging: the client started and finished breaking blocks (the dirt ahead and the ground below), each start and
    # finish with the pick's block and face (use-aim judges them; no case may open).
    digs = [p for p in inbound if p["type"] == "PlayerAction" and p["action"] in ("START_DESTROY_BLOCK", "STOP_DESTROY_BLOCK")]
    faces = sorted({p["face"] for p in digs})
    check(sum(p["action"] == "STOP_DESTROY_BLOCK" for p in digs) >= 2 and "NORTH" in faces and "UP" in faces,
          f"the client dug the dirt ahead and the ground below ({len(digs)} starts and finishes, faces {faces})")
    # The chest: from the client's answer to a transaction sent after the OpenScreen until its own ContainerClose, W was
    # held but no movement key and no action reached the server; after it, a movement key did.
    menu, pings, under, after = None, {}, [], None
    for e in events:
        if e["type"] == "Outbound" and e["packet"]["type"] == "OpenScreen" and menu is None:
            menu = {"sent": e["sequence"], "open": None, "closed": None}
        elif e["type"] == "Outbound" and e["packet"]["type"] == "Ping":
            pings[e["packet"]["id"]] = e["sequence"]
        elif e["type"] == "Acknowledged" and menu and menu["open"] is None and pings.get(e["transactionId"], -1) > menu["sent"]:
            menu["open"] = e["sequence"]
        elif e["type"] == "Inbound" and menu and menu["open"] is not None and menu["closed"] is None:
            p = e["packet"]
            if p["type"] == "ContainerClose":
                menu["closed"] = e["sequence"]
            elif p["type"] in keybind or p["type"] == "PlayerInput":
                under.append(p)
        elif e["type"] == "Inbound" and menu and menu["closed"] is not None and after is None and e["packet"]["type"] == "PlayerInput":
            after = e["packet"]
    walking = [p for p in under if p["type"] == "PlayerInput" and any(p[k] for k in ("forward", "backward", "left", "right"))]
    acting = [p["type"] for p in under if p["type"] in keybind and p["type"] != "SetCarriedItem"]
    check(menu is not None and menu["open"] is not None and menu["closed"] is not None and not walking and not acting
          and after is not None and after["left"],
          f"under the chest's screen the client sent no movement key and no action although W was held, and A walked after it "
          f"closed: {menu} {under} {after}")
    # The shield: from its use (the off hand's, behind the sword) to its release the client sent no swing, no attack, no
    # interaction and no other use, though the last block was clicked under three times; that block's release is
    # followed by the one click's swing.
    blocks, current, after_last = [], None, None
    for p in inbound:
        if p["type"] == "UseItem" and p["hand"] == "OFF_HAND" and current is None:
            current = []
        elif current is not None and p["type"] == "PlayerAction" and p["action"] == "RELEASE_USE_ITEM":
            blocks.append(current)
            current, after_last = None, []
        elif current is not None and p["type"] in ("Swing", "InteractEntity", "UseItem", "UseItemOn"):
            current.append(p["type"])
        elif after_last is not None and p["type"] in ("Swing", "UseItem"):
            after_last.append(p["type"])
    check(len(blocks) >= 3 and not any(blocks) and after_last and after_last[0] == "Swing",
          f"while the shield was up the client sent no swing, attack or use though the attack button was clicked, and the "
          f"click after the release swung: {blocks} then {after_last}")
    rotations = sum(1 for e in events if e["type"] == "Outbound" and e["packet"]["type"] in ("PlayerRotation", "TeleportEntity"))
    check(attacks >= 2 and actions.get("UseItem", 0) >= 3 and actions.get("SetCarriedItem", 0) >= 3 and not broken and rotations == 0,
          f"the client's actions all came before its tick's movement, each of its {attacks} attacks followed by the main hand's swing "
          f"({actions}): {broken}")
    slots = [p["slot"] for p in inbound if p["type"] == "SetCarriedItem"]
    check(slots[-5:] == [1, 2, 0, 4, 0], f"hotbar keys 2, 3, 1, 5 (the drive) and 1 (the reach stage) selected slots 1, 2, 0, 4, 0: {slots}")
    clicks = [p for p in inbound if p["type"] == "ContainerClick"]
    check(len(clicks) == 2 and [c["slot"] for c in clicks] == [37, 40] and all(c["containerId"] == 0 for c in clicks)
          and clicks[0]["carried"].get("item") == "minecraft:stick" and clicks[0]["carried"].get("rangeHash") is not None,
          f"two real clicks in the inventory screen moved the stick, its range hashed by the client: {clicks}")
    positions = [p for p in inbound if p["type"] == "MovePlayer" and p["hasPosition"]]
    xs = [float(p["x"]["f64"]) for p in positions]
    ys = [float(p["y"]["f64"]) for p in positions]
    check(len(positions) > 50 and max(xs) - min(xs) > 1.0 and max(ys) - min(ys) > 0.5,
          f"the player walked and jumped ({len(positions)} position packets)")

    tps = result["steady_ticks_per_second"]
    check(19.5 <= tps <= 20.5, f"client tick clock after the join: {tps:.2f} ClientTickEnd per second "
                               f"(whole session incl. world loading: {result['ticks_per_second']:.2f})")
    check(result["pongs_inside_a_tick"] == 0 and result["pongs"] > 100,
          f"wire order: no pong inside a tick, as the vanilla client answers pings at frame start ({result['pongs']} pongs)")
    # The driver spaced its mouse motions two ticks apart: each one is a rotation step of its own, so the
    # counts inferred through the exact float model must equal the commanded counts one for one. That
    # proves the float arithmetic and the absolute scale (a multiplier off by 2x would double them).
    drive = json.load(open(os.path.join(work, "drive.json")))
    mouse = next(a for a in drive["actions"] if a["what"] == "mouse")
    yaw_ok, yaw_detail = aligned(result["yaw_counts"], mouse["horizontal"])
    check(yaw_ok, f"every yaw step is exactly one commanded mouse motion through the vanilla float math: {yaw_detail}")
    pitch_ok, pitch_detail = aligned(result["pitch_counts"], mouse["vertical"])
    check(pitch_ok, f"every pitch step too: {pitch_detail}")
    rejected = {name: not aligned(c["yaw_counts"], mouse["horizontal"])[0] for name, c in result["negative_controls"].items()}
    check(all(rejected.values()), f"the same data rejects every wrong model (negative controls): {rejected}")
    verify_reach(events, work, check)
    m = re.search(r"WhyNotAC self RealProbe: (.*)", re.sub(r"\x1b\[[0-9;]*m", "", log))
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv) if m else {}
    check(m is not None and fields.get("matches") == "true" and fields.get("eye") == "known" and fields.get("positionEqual") == "true"
          and fields.get("rangeContains") == "true" and fields.get("scaleContains") == "true" and fields.get("modeConsistent") == "true",
          f"the real client's own player, rebuilt from its packets, is the server's: eye on its position, reach and scale, mode "
          f"({m.group(1) if m else 'no answer'})")
    check(fields.get("engineTags") == "vanilla",
          f"the tags the real client got in its configuration phase are those the movement engine takes as vanilla: {fields.get('engineTags')}")
    cases_dir = os.path.join(work, "plugins", "WhyNotAC", "cases")
    cases = [f for f in os.listdir(cases_dir) if f.endswith(".json")] if os.path.isdir(cases_dir) else []
    check(not cases, f"no case was opened for the honest, unmodified client ({len(cases)} dossiers)")
    screenshots = os.listdir(os.path.join(work, "game", "screenshots")) if os.path.isdir(os.path.join(work, "game", "screenshots")) else []
    check(len(screenshots) == 1, f"the client rendered and saved a screenshot: {screenshots}")
    # The world WhyNotAC rebuilt from what the real client received equals the server's world.
    m = re.search(r"WhyNotAC world RealProbe: (.*)", log)
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv) if m else {}
    n = lambda k: int(fields.get(k, "-1"))
    check(m is not None and fields.get("dimension") == "minecraft:overworld" and n("chunks") >= 9 and n("missingOnServer") == 0
          and n("different") == 0 and n("stale") == 0 and n("unreadable") == 0 and n("equal") + n("changing") == n("sections") > 0
          and n("changing") <= 2,
          f"the real client's world, rebuilt from its packets, equals the server's block for block ({m.group(1) if m else 'no answer'})")
    # The entities WhyNotAC rebuilt from what the real client received are the ones the server shows it, at the same bases.
    m = re.search(r"WhyNotAC entities RealProbe: (.*)", re.sub(r"\x1b\[[0-9;]*m", "", log))
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv) if m else {}
    check(m is not None and fields.get("complete") == "true" and n("server") >= 3 and n("missingInReplica") == 0 and n("extraInReplica") == 0
          and n("different") == 0 and n("equal") >= 3 and n("equal") + n("moving") == n("compared") and n("overflows") == 0,
          f"the real client's entities, rebuilt from its packets, are the server's, base for base ({m.group(1) if m else 'no answer'})")
    check(m is not None and n("sizeDifferent") == 0 and n("sizeEqual") >= 5 and n("sizeEqualNonDefault") >= 3,
          f"every size the replica claims for the real client's entities is the server's own, a baby's, a slime's and an interaction's too ({n('sizeEqual')} equal, {n('sizeEqualNonDefault')} not the default, {n('sizeDifferent')} different)")
    check(m is not None and n("pickDifferent") == 0 and n("pickEqual") >= 5,
          f"whether the real client can pick each entity is the server's own ({n('pickEqual')} equal, {n('pickDifferent')} different, {n('pickUnknown')} unknown)")
    # The hands WhyNotAC rebuilt from what the real client received and sent are the server's.
    m = re.search(r"WhyNotAC inventory RealProbe: (.*)", re.sub(r"\x1b\[[0-9;]*m", "", log))
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv) if m else {}
    check(m is not None and fields.get("matches") == "true" and n("slotsKnown") == 10 and n("slotsEqual") == 10 and n("slotsUnknown") == 0
          and fields.get("selectedKnown") == "true",
          f"the real client's hotbar, off hand and selection, rebuilt from its packets and clicks, are the server's ({m.group(1) if m else 'no answer'})")
    check(m is not None and fields.get("reachKnown") == "true" and fields.get("mainHand") == "4.5+0.25",
          f"the moved stick's attack range is resolved from the client's own hash of it: mainHand={fields.get('mainHand')}")
    print("RESULT:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
