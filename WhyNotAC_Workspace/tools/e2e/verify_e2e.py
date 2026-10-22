#!/usr/bin/env python3
"""
Checks an E2E run: the server-side view printed by `whynotac inspect` must match what the probe
client actually did, bit for bit where floats are involved.

usage: verify_e2e.py <e2e-server.log> <e2e-probe.json> [<recording> <inspect.json> <dump.jsonl> <work-dir>]
E2E_EXPECT_PLUGINS="ViaVersion packetevents" additionally requires those plugins to have been enabled.
"""
import hashlib
import json
import os
import re
import struct
import sys
import uuid


def f32(x):
    return struct.unpack(">f", struct.pack(">f", x))[0]


def from_bits(bits):
    return struct.unpack(">f", struct.pack(">I", bits))[0]


def main():
    log = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    probe = json.loads(open(sys.argv[2]).read().strip().splitlines()[-1])
    failures = []

    def check(condition, message):
        print(("PASS " if condition else "FAIL ") + message)
        if not condition:
            failures.append(message)

    m = re.search(r"Transactions sent (\d+) acked (\d+) pending (\d+) \(oldest [\d.]+ ms\) skipped (\d+) duplicate (\d+)", log)
    check(m is not None, "inspect output present")
    if m:
        sent, acked, pending, skipped, duplicate = map(int, m.groups())
        check(skipped == len(probe["skipped_transaction_ids"]) == 1, f"unanswered transaction detected (skipped={skipped})")
        check(duplicate == len(probe["duplicated_transaction_ids"]) == 1, f"twice-answered transaction detected (duplicate={duplicate})")
        check(sent == acked + skipped + pending, f"transaction accounting closes ({sent} = {acked} + {skipped} + {pending})")
    m = re.search(r"lost-before-wire (\d+)", log)
    check(m is not None and int(m.group(1)) == 0, "no transaction was lost on the server side")
    m = re.search(r"queued \d+ dropped (\d+)", log)
    check(m is not None and int(m.group(1)) == 0, "no timeline events dropped")
    for plugin in os.environ.get("E2E_EXPECT_PLUGINS", "").split():
        check(re.search(rf"\[{re.escape(plugin)}\] Enabling {re.escape(plugin)} v", log) is not None, f"{plugin} loaded next to WhyNotAC")
    check(probe["still_connected"], "probe was never disconnected (interceptor is passive)")
    check(probe.get("server_list_ping_answered") is True, "server-list ping answered normally")
    m = re.search(r"WhyNotAC sessions: (\d+)", log)
    check(m is not None and int(m.group(1)) == 1, "the server-list ping left no session behind")
    check("SKIPPED_TRANSACTION" in log or "Anomalies 2" in log, "anomalies reported")

    # Rotation: the yaw the server printed, and its exact delta, must be one of the probe's own steps.
    m = re.search(r"Last rotation yaw=(\S+) pitch=\S+ exact dYaw=(\S+) dPitch", log)
    check(m is not None, "rotation observed")
    if m:
        server_yaw = f32(float(m.group(1)))  # Java's Float.toString is the shortest repr that round-trips
        server_delta = float(m.group(2))     # Double.toString round-trips exactly
        steps = [(from_bits(a), from_bits(b)) for a, b in probe["yaw_steps_bits"]]
        matches = [(a, b) for a, b in steps if b == server_yaw and (b - a) == server_delta]
        check(bool(matches), f"server yaw {server_yaw!r} with exact delta {server_delta!r} matches a step the probe sent, bit for bit")
    if len(sys.argv) > 3:
        verify_recording(probe, sys.argv[3], sys.argv[4], sys.argv[5], check)
        if sys.argv[3]:
            verify_subject_requests(sys.argv[6], sys.argv[3], sys.argv[5], check)
            verify_console_privacy(log, sys.argv[6], sys.argv[3], check)
            verify_edge(probe, log, sys.argv[5], check)
            verify_facts(probe, sys.argv[5], check)
            verify_cases(probe, log, sys.argv[6], check)
            verify_world(log, sys.argv[5], check)
            verify_entities(log, sys.argv[5], check)
            verify_inventory(sys.argv[5], check)
            verify_inventory_replica(log, probe, sys.argv[5], check)
            verify_self(log, check)
    print("RESULT:", "OK" if not failures else f"{len(failures)} FAILURE(S)")
    sys.exit(1 if failures else 0)


def verify_recording(probe, recording, inspect_path, dump_path, check):
    """The recording, read back offline, must contain exactly what the probe sent."""
    check(bool(recording), "a finished .wnrec recording exists")
    if not recording:
        return
    summary = json.loads(open(inspect_path).read())
    check(summary["complete"] is True, f"recording is complete (report: {summary['report']})")
    events = [json.loads(line) for line in open(dump_path) if line.strip()]

    def inbound(packet_type):
        return [e["packet"] for e in events if e["type"] == "Inbound" and e["packet"]["type"] == packet_type]

    rotations = [p for p in inbound("MovePlayer") if p["hasRotation"] and not p["hasPosition"]]
    recorded_yaw = [int(p["yaw"]["bits"], 16) for p in rotations]
    sent_yaw = [b for _, b in probe["yaw_steps_bits"]]
    check(recorded_yaw == sent_yaw, f"all {len(sent_yaw)} rotation packets recorded in order with identical float bits")
    check(all(int(p["pitch"]["bits"], 16) == probe["pitch_bits"] for p in rotations), "pitch bits identical in every rotation packet")
    ticks = len(inbound("ClientTickEnd"))
    check(ticks == probe["tick_ends_sent"], f"every ClientTickEnd recorded ({ticks} = {probe['tick_ends_sent']})")
    inputs = [(1 if p["forward"] else 0) for p in inbound("PlayerInput")]
    check(inputs == probe["inputs_sent"], f"key-state sequence recorded exactly ({len(inputs)} PlayerInput packets)")
    anomalies = [e for e in events if e["type"] == "Anomaly"]
    kinds = [a["kind"] for a in anomalies]
    check("SKIPPED_TRANSACTION" in kinds, "withheld transaction preserved in the recording")
    dup = probe["duplicated_transaction_ids"][0][2:] if probe["duplicated_transaction_ids"] else "?"
    check(any(a["kind"] == "DUPLICATE_TRANSACTION" and a["detail"].endswith(dup) for a in anomalies), "twice-answered transaction preserved")
    pings = [e["packet"]["id"] for e in events if e["type"] == "Outbound" and e["packet"]["type"] == "Ping"]
    check(all(not (-32768 <= p <= 32767) for p in pings) and len(set(p >> 16 for p in pings)) > len(pings) * 0.9,
          f"transaction ids are random, no shared prefix ({len(pings)} pings)")
    bound = [e for e in events if e["type"] == "Bound"]
    check(len(bound) == 1 and bound[0]["playerName"].startswith("anon-"), "identity pseudonymised in the recording")
    handshakes = inbound("Handshake")
    check(len(handshakes) == 1 and handshakes[0]["intent"] == "LOGIN" and handshakes[0]["protocolVersion"] == 774,
          "login handshake recorded with protocol 774")
    recordings = [p for p in os.listdir(os.path.dirname(recording)) if p.endswith(".wnrec")]
    check(len(recordings) == 1, f"exactly one recording (the ping made none): {recordings}")
    raw = open(recording, "rb").read()
    check(b"WhyNotProbe" not in raw and "WhyNotProbe" not in open(dump_path).read(), "real player name appears nowhere in the file")
    check(events[-1]["type"] == "Disconnected", "recording ends with the disconnect")


def verify_subject_requests(work_dir, recording, dump_path, check):
    """find/forget, driven by the UUID from usercache.json as the operator guide describes."""
    # Offline-mode UUID, derived independently: Java's UUID.nameUUIDFromBytes (MD5, version 3).
    expected = str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:WhyNotProbe").digest(), version=3))
    cache = json.load(open(os.path.join(work_dir, "usercache.json")))
    check([e["uuid"] for e in cache if e["name"] == "WhyNotProbe"] == [expected], f"usercache.json holds the player's UUID {expected}")
    found = json.load(open(os.path.join(work_dir, "e2e-find.json")))
    files = [r["file"] for r in found["recordings"]]
    check(files == [recording] and found["unreadable_for_manual_review"] == [], f"find locates exactly the player's recording: {files}")
    bound = [json.loads(line) for line in open(dump_path) if '"type":"Bound"' in line]
    check(len(bound) == 1 and found["pseudonym"] == bound[0]["playerId"], "find's pseudonym is the one inside the recording")
    other = json.load(open(os.path.join(work_dir, "e2e-find-other.json")))
    check(other["recordings"] == [], "find for another UUID matches nothing")
    forgotten = json.load(open(os.path.join(work_dir, "e2e-forget.json")))
    left = [f for _, _, fs in os.walk(os.path.join(work_dir, "forget-copy")) for f in fs if ".wnrec" in f]
    check(forgotten["deleted"] == 1 and forgotten["dry_run"] is False and left == [], f"forget --yes deleted the recording (left: {left})")


def verify_console_privacy(log, work_dir, recording, check):
    """/whynotac privacy on the live server: the player's recording and cases are found, then deleted."""
    log = re.sub(r"\x1b\[[0-9;]*m", "", log)
    player = str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:WhyNotProbe").digest(), version=3))
    results = re.findall(r"WhyNotAC privacy (find|forget) (\S+)(.*)\n.*?INFO\]: recordings: (.*)\n.*?INFO\]: cases: (\d+) (.*)", log)
    check([(a, u) for a, u, *_ in results] == [("find", player), ("forget", player), ("find", player)],
          f"the console ran find, forget and find for the player: {[(a, u) for a, u, *_ in results]}")
    if len(results) != 3:
        return
    dossiers = [f for f in os.listdir(os.path.join(work_dir, "plugins", "WhyNotAC", "cases")) if f.endswith(".json")]
    (_, _, flag1, rec1, n1, rest1), (_, _, flag2, rec2, n2, rest2), (_, _, flag3, rec3, n3, rest3) = results
    first, forgotten, after = json.loads(rec1), json.loads(rec2), json.loads(rec3)
    check(flag1 == "" and [os.path.basename(r["file"]) for r in first["recordings"]] == [os.path.basename(recording)]
          and int(n1) == len(dossiers) > 0 and "deleted" not in rest1,
          f"find lists the recording and all {len(dossiers)} cases (dossiers): {n1} {rest1}")
    check(flag2 == "" and forgotten["deleted"] == 1 and f"deleted rows {len(dossiers)}, deleted dossiers {len(dossiers)}" in rest2,
          f"forget --yes deleted the recording, the case rows and the dossiers: {rest2}")
    check(after["recordings"] == [] and after["mentioned_in"] == [] and int(n3) == 0, "afterwards nothing is found")
    leaks = [line for line in log.splitlines() if ("WhyNotAC" in line or "PlayerSession[" in line) and "127.0.0.1" in line]
    check(leaks == [], f"no WhyNotAC log line holds the client's address: {leaks[:3]}")


def f64(value):
    """A dumped double: {"f64": text, "bits": hex} -> exact float."""
    return struct.unpack(">d", bytes.fromhex(value["bits"][2:].zfill(16)))[0]


def verify_edge(probe, log, dump_path, check):
    """The timeline's knockback must be exactly what the client received, whoever sent or cancelled it."""
    events = [json.loads(line) for line in open(dump_path) if line.strip()]
    self_id = probe["entity_id"]
    observation = [e for e in events if e["type"] == "Observation"]
    check(len(observation) == 1 and observation[0]["inbound"] == "WIRE" and observation[0]["outbound"] == "WIRE"
          and observation[0]["clientProtocol"] == 774, f"timeline recorded at the network edge: {observation}")

    def motion_of(packet):
        return tuple(f64(packet["clientMotion"][axis]) for axis in ("x", "y", "z"))

    recorded = [motion_of(e["packet"]) for e in events
                if e["type"] == "Outbound" and e["packet"]["type"] == "SetEntityMotion" and e["packet"]["entityId"] == self_id]
    received = [tuple(float(c) for c in t[1:]) for t in probe["trace"] if t[0] == "motion"]
    check(recorded == received and len(received) >= 2,
          f"knockback on the timeline == knockback the client received, bit for bit ({len(received)}: {received})")

    def near(motion, y):
        return motion[0] == 0.0 and motion[2] == 0.0 and abs(motion[1] - y) < 4e-5

    check(any(near(m, 0.3) for m in received), "game-path knockback (0.3) reached the client and the timeline")
    check(any(near(m, 0.5) for m in received), "knockback injected below the game (0.5) reached the client and the timeline")
    check(not any(near(m, 0.7) for m in received + recorded), "knockback cancelled below the game (0.7) is in neither")

    divergences = [e for e in events if e["type"] == "OutboundDivergence"]
    inbound_divergences = [e for e in events if e["type"] == "InboundDivergence"]
    kinds = sorted((e["kind"], round(motion_of(e["packet"])[1], 3)) for e in divergences if e["packet"]["type"] == "SetEntityMotion")
    check(kinds == [("GAME_ONLY", 0.7), ("WIRE_ONLY", 0.5)], f"exactly the injected and the cancelled knockback are flagged: {kinds}")
    check(len(divergences) == 2 and not inbound_divergences,
          f"no other divergence anywhere ({len(divergences)} outbound, {len(inbound_divergences)} inbound)")

    # Bracketing. WhyNotAC also sends one transaction per tick, so "a ping on each side" alone proves nothing
    # (review finding): what only a sandwich produces is two pings written together with the knockback,
    # within a few milliseconds (the game's sandwich goes through the event loop as three tasks; the edge
    # writes its own in one call), while per-tick pings are 50 ms apart.
    trace = probe["trace"]
    outbound = [e for e in events if e["type"] == "Outbound"]
    sandwiches = []
    for i, e in enumerate(outbound):
        if e["packet"]["type"] == "SetEntityMotion" and e["packet"]["entityId"] == self_id:
            before, after = (outbound[i - 1] if i > 0 else None), (outbound[i + 1] if i + 1 < len(outbound) else None)
            sandwiches.append((before, e, after))
    client_neighbours = [(trace[i - 1], trace[i + 1]) for i, t in enumerate(trace) if t[0] == "motion" and 0 < i < len(trace) - 1]
    tight = all(b is not None and a is not None and b["packet"]["type"] == "Ping" and a["packet"]["type"] == "Ping"
                and a["nanoTime"] - b["nanoTime"] < 20_000_000 for b, _, a in sandwiches)
    check(len(sandwiches) == len(received) and tight,
          "every knockback is sandwiched: two pings written with it, < 20 ms apart (per-tick pings are 50 ms apart): "
          + str([(round((a["nanoTime"] - b["nanoTime"]) / 1e6, 3) if a and b else None) for b, _, a in sandwiches]) + " ms")
    same_ids = [(["ping", b["packet"]["id"]], ["ping", a["packet"]["id"]]) for b, _, a in sandwiches] == [(list(x), list(y)) for x, y in client_neighbours]
    check(same_ids, "the client received each knockback between exactly those two transactions")
    edge = re.search(r"edge transactions (\d+)", log)
    check(edge is not None and int(edge.group(1)) == 2,
          f"exactly one sandwich was written at the edge, for the injected knockback (edge transactions {edge.group(1) if edge else '?'})")
    flush = re.search(r"flush transactions (\d+)", log)
    check(flush is not None and int(flush.group(1)) > 0, f"flushes are bracketed with transactions at the edge ({flush.group(1) if flush else '?'})")
    m = re.search(r"\[wnprobe\] inject via (\S+)", log)
    c = re.search(r"\[wnprobe\] (\S+) cancelled a velocity packet", log)
    check(m is not None and c is not None and m.group(1) == c.group(1),
          f"knockback injected and cancelled through {m.group(1) if m else '?'}")


def verify_cases(probe, log, work_dir, check):
    """Each of the probe's deliberate violations went all the way: evidence, policy, case, alert, dossier, re-verification."""
    cases_dir = os.path.join(work_dir, "plugins", "WhyNotAC", "cases")
    dossiers = [json.load(open(os.path.join(cases_dir, f))) for f in sorted(os.listdir(cases_dir)) if f.endswith(".json")] \
        if os.path.isdir(cases_dir) else []
    check(probe.get("tick_framing_violation_tick") == 200, "the probe sent two movement packets in one tick")
    check(probe.get("clock_violation_tick") == 240, f"the probe sent {probe.get('clock_burst')} extra ClientTickEnd at once (a timer)")
    by_check = {}
    for d in dossiers:
        by_check.setdefault(d["check"], []).append(d)
    attacks = {a["type"]: a for a in probe.get("attacks", [])}
    check(set(attacks) == {"husk", "sheep", "husk without swing"} and attacks["husk"]["distance"] < 2.9 and attacks["sheep"]["distance"] > 6.5
          and attacks["husk without swing"]["distance"] < 2.9,
          f"the probe attacked a husk next to it, a sheep 7 blocks away and the husk without a swing: {probe.get('attacks')}")
    check(len(dossiers) == 4 and len(by_check.get("tick-framing", [])) == 1 and len(by_check.get("client-clock", [])) == 1
          and len(by_check.get("attack-reach", [])) == 1 and len(by_check.get("action-grammar", [])) == 1,
          f"exactly four cases opened, one per violation (none for the attacks in reach): {sorted((d['check'], d['summary']) for d in dossiers)}")
    framing = by_check.get("tick-framing", [None])[0]
    if framing:
        check(framing["evidenceClass"] == "P-M" and framing["details"].get("movePackets") == "2"
              and framing["details"].get("rotationAllowance") == "0" and framing["details"].get("vehicleAllowance") == "0",
              f"the tick-framing case is the proof (P-M, 2 packets, no reply allowance): {framing['summary']}")
    clock = by_check.get("client-clock", [None])[0]
    if clock:
        ticks, allowed = int(clock["details"].get("ticks", "0")), int(clock["details"].get("allowedTicks", "0"))
        check(clock["evidenceClass"] == "P-M" and ticks > allowed and "1%" in clock["details"].get("assumption", ""),
              f"the client-clock case is the proof (P-M, {ticks} ticks > {allowed} allowed, its assumption stated): {clock['summary']}")
    reach = by_check.get("attack-reach", [None])[0]
    if reach:
        dd = reach["details"]
        check(reach["evidenceClass"] == "P-A" and dd.get("targetType") == "minecraft:sheep"
              and dd.get("target") == str(attacks.get("sheep", {}).get("target")) and float(dd.get("distance", "0")) > float(dd.get("allowed", "99")),
              f"the attack-reach case is the sheep, beyond every reach the client may have had (P-A): {reach['summary']}")
    grammar = by_check.get("action-grammar", [None])[0]
    if grammar:
        dd = grammar["details"]
        target = attacks.get("husk without swing", {}).get("target")
        check(grammar["evidenceClass"] == "P-M" and dd.get("attacksWithoutSwing") == "1" and dd.get("actionsAfterMovement") == "0"
              and grammar["summary"].startswith(f"attack on entity {target} not followed by the main hand's swing"),
              f"the action-grammar case is the husk attacked without a swing, the probe's other actions in the client's order (P-M): {grammar['summary']}")
    for d in dossiers:
        check(d["playerName"] == "WhyNotProbe" and d["identity"] == "UNAUTHENTICATED",
              f"the {d['check']} case names the player, and an offline-mode identity stays unauthenticated: {d['playerName']} {d['identity']}")
        check(d["lifecycle"] == "SHADOW" and d["permittedActions"] == ["ALERT", "REVIEW"],
              f"a shadow check may only alert and be reviewed (docs/POLICY.md): {d['check']} {d['lifecycle']} {d['permittedActions']}")
        check(d["recording"] is not None and d["admissible"] is True, f"the {d['check']} dossier points at the session's recording")
        check(re.search(r"\[WhyNotAC\] \[shadow\] WhyNotProbe " + re.escape(d["check"]) + r" \(" + re.escape(d["evidenceClass"]) + r"\)", log) is not None,
              f"a shadow alert for {d['check']} went to the console (and to whynotac.alerts.shadow)")
    verified = [json.loads(line) for line in open(os.path.join(work_dir, "e2e-verify.jsonl"))] \
        if os.path.exists(os.path.join(work_dir, "e2e-verify.jsonl")) else []
    check(len(verified) == len(dossiers) == 4 and all(v["verified"] is True and v["evidenceInRecording"] == 1 for v in verified),
          f"`verify` re-ran each check over the recording, independently, and found the same evidence: {verified}")
    check(re.search(r"WhyNotAC cases \(newest first\): 4", log) is not None
          and all(re.search(re.escape(d["id"][:8]) + r" WhyNotProbe " + re.escape(d["check"]) + " " + re.escape(d["evidenceClass"]) + r" SHADOW", log)
                  for d in dossiers),
          "/whynotac cases lists all four")


def verify_self(log, check):
    """The client's own player WhyNotAC rebuilt (eye, reach and scale attributes, game mode) is the server's."""
    m = re.search(r"WhyNotAC self WhyNotProbe: (.*)", log)
    check(m is not None, "/whynotac self answered")
    if not m:
        return
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv)
    check(fields.get("matches") == "true" and fields.get("eye") == "known" and fields.get("positionEqual") == "true",
          f"the eye is known and based on the server's own position of the player: {m.group(1)}")
    check(fields.get("rangeContains") == "true" and fields.get("range") == "3.0000..3.0000" and fields.get("scaleContains") == "true",
          f"entity_interaction_range and scale are the server's (range {fields.get('range')}, scale {fields.get('scale')})")
    check(fields.get("modeConsistent") == "true" and fields.get("mayBeCreativeOrSpectator") == "false" and fields.get("serverMode") == "0",
          "survival on both sides: the replica rules out creative and spectator")


def verify_world(log, dump_path, check):
    """The world WhyNotAC rebuilt from what the client received equals the server's world."""
    m = re.search(r"WhyNotAC world WhyNotProbe: (.*)", log)
    check(m is not None, "/whynotac world answered")
    if not m:
        return
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv)
    n = lambda k: int(fields.get(k, "-1"))
    check(fields.get("dimension") == "minecraft:overworld" and "(server:" not in m.group(1),
          f"the client's dimension is the player's world: {fields.get('dimension')}")
    check(n("chunks") >= 9 and n("missingOnServer") == 0, f"the client holds the chunks around it ({n('chunks')}, all loaded on the server)")
    # A section the server kept changing through every round (12 ticks) is not compared; at most two may be.
    check(n("sections") > 0 and n("different") == 0 and n("stale") == 0 and n("equal") + n("changing") == n("sections") and n("changing") <= 2,
          f"every section the client holds equals the server's, block for block ({n('equal')}/{n('sections')} equal, {n('changing')} changing, {n('rounds')} rounds)")
    check(n("unreadable") == 0, "every chunk packet decoded")
    check(n("blockUpdates") >= 1 + 9 * 9 * 2, f"the setblock and the fill reached the client's world ({n('blockUpdates')} block changes)")
    events = [json.loads(line) for line in open(dump_path)] if os.path.exists(dump_path) else []
    kinds = {}
    for e in events:
        if e["type"] == "Outbound":
            t = e["packet"]["type"]
            kinds[t] = kinds.get(t, 0) + 1
    check(kinds.get("LevelChunk", 0) >= 9 and kinds.get("BlockUpdate", 0) >= 1 and kinds.get("SectionBlocksUpdate", 0) >= 1,
          f"the recording holds the world packets: {[(k, kinds.get(k, 0)) for k in ('LevelChunk', 'BlockUpdate', 'SectionBlocksUpdate', 'ChunkCacheCenter')]}")
    logins = [e["packet"] for e in events if e["type"] == "Outbound" and e["packet"]["type"] == "Login"]
    check(len(logins) == 1 and logins[0].get("dimension", {}).get("minY") == -64 and logins[0].get("chunkRadius") == 3
          and logins[0].get("dimension", {}).get("fastLava") is False,
          f"the login carries the dimension (the overworld's lava slow) and chunk radius: {logins}")


def verify_entities(log, dump_path, check):
    """The entities WhyNotAC rebuilt from what the client received are the ones the server shows it, at the same bases."""
    m = re.search(r"WhyNotAC entities WhyNotProbe: (.*)", re.sub(r"\x1b\[[0-9;]*m", "", log))
    check(m is not None, "/whynotac entities answered")
    if not m:
        return
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv)
    n = lambda k: int(fields.get(k, "-1"))
    check(fields.get("complete") == "true", "nothing about the entities was lost")
    check(n("server") >= 6 and n("replica") == n("server"), f"the replica holds every entity the server shows the client ({n('replica')}/{n('server')})")
    check(n("missingInReplica") == 0 and n("extraInReplica") == 0, "no entity missing from the replica, none extra")
    check(n("equal") >= 5 and n("different") == 0 and n("equal") + n("moving") == n("compared"),
          f"every entity's position base equals the server's on the 1/4096 grid ({n('equal')}/{n('compared')}, {n('moving')} still moving)")
    check(n("passengers") >= 1, f"the passenger was recognised and left out of the comparison ({n('passengers')})")
    check(n("overflows") == 0, "no entity was given up")
    check(n("sizeDifferent") == 0 and n("sizeEqual") >= 16,
          f"every size the replica claims is the server's own (pose, baby, scale, size data): {n('sizeEqual')} equal, {n('sizeDifferent')} different")
    check(n("sizeEqualNonDefault") >= 11,
          f"the babies, the scaled husk and the sizes from data are among them ({n('sizeEqualNonDefault')} not the type's default size)")
    check(n("pickDifferent") == 0 and n("pickEqual") >= 15,
          f"whether the client can pick each entity is the server's own, the marker and the cloud not ({n('pickEqual')} equal, {n('pickDifferent')} different, {n('pickUnknown')} unknown)")
    events = [json.loads(line) for line in open(dump_path)] if os.path.exists(dump_path) else []
    kinds = {}
    for e in events:
        if e["type"] == "Outbound":
            t = e["packet"]["type"]
            kinds[t] = kinds.get(t, 0) + 1
    check(all(kinds.get(k, 0) >= 1 for k in ("AddEntity", "RemoveEntities", "EntityPositionSync", "SetPassengers", "SetEntityData")),
          f"the recording holds the entity packets: {[(k, kinds.get(k, 0)) for k in ('AddEntity', 'RemoveEntities', 'MoveEntity', 'EntityPositionSync', 'RotateHead', 'SetPassengers', 'SetEntityData', 'PlayerInfoUpdate', 'TickingState')]}")
    check(kinds.get("TickingState", 0) >= 1 and kinds.get("PlayerInfoUpdate", 0) >= 1, "the ticking state and the player list are recorded")


def verify_inventory_replica(log, probe, dump_path, check):
    """The hands WhyNotAC rebuilt from what the client received and sent are the server's: slots, selection, menu, reach."""
    check(probe.get("inventory_steps") == 3, f"the probe selected the stick and moved the sword with two clicks ({probe.get('inventory_steps')} steps)")
    m = re.search(r"WhyNotAC inventory WhyNotProbe: (.*)", re.sub(r"\x1b\[[0-9;]*m", "", log))
    check(m is not None, "/whynotac inventory answered")
    if not m:
        return
    fields = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv)
    n = lambda k: int(fields.get(k, "-1"))
    check(fields.get("matches") == "true" and n("slotsKnown") == 10 and n("slotsEqual") == 10 and n("slotsUnknown") == 0,
          f"every hotbar slot and the off hand equal the server's, the clicked ones included ({n('slotsEqual')}/{n('slotsKnown')} equal, {n('slotsUnknown')} unknown)")
    check(fields.get("selectedKnown") == "true" and fields.get("menuKnown") == "true", "the selected slot and the open menu are known and the server's")
    check(fields.get("reachKnown") == "true" and fields.get("reachContainsServer") == "true" and fields.get("mainHand") == "4.5+0.25",
          f"the main hand's reach is the stick's own attack range: {fields.get('mainHand')}")
    check(n("overflows") == 0, "no candidate overflow")
    events = [json.loads(line) for line in open(dump_path)] if os.path.exists(dump_path) else []
    inbound = [e["packet"] for e in events if e["type"] == "Inbound"]
    clicks = [p for p in inbound if p["type"] == "ContainerClick"]
    check(len(clicks) == 2 and clicks[0]["changed"] and clicks[0]["carried"] and clicks[0]["carried"].get("hashed") is True,
          f"the clicks are recorded with their changed slots and carried stack: {clicks[:2]}")
    check(any(p["type"] == "SetCarriedItem" and p["slot"] == 2 for p in inbound), "the selection is recorded")


def verify_inventory(dump_path, check):
    """The probe's inventory reached the recording as the client received it: items, counts, component patches."""
    events = [json.loads(line) for line in open(dump_path)] if os.path.exists(dump_path) else []
    stacks = []
    for e in events:
        if e["type"] != "Outbound":
            continue
        p = e["packet"]
        if p["type"] == "ContainerSetContent":
            stacks += [(p["containerId"], i, s) for i, s in enumerate(p["items"])]
        elif p["type"] == "ContainerSetSlot":
            stacks.append((p["containerId"], p["slot"], p["item"]))
        elif p["type"] == "SetPlayerInventory":
            stacks.append(("inventory", p["slot"], p["item"]))
    items = {s["item"] for _, _, s in stacks if s.get("item")}
    check({"minecraft:diamond_sword", "minecraft:iron_spear", "minecraft:stick", "minecraft:shield"} <= items,
          f"the given items reached the recording: {sorted(items)}")
    sticks = [s for _, _, s in stacks if s.get("item") == "minecraft:stick"]
    check(any(s["count"] == 2 and s["added"] == ["minecraft:attack_range"] and s["removed"] == ["minecraft:lore"]
              and s["attackRange"] and s["attackRange"]["maxRange"]["f32"] == "4.5" and s["attackRange"]["hitboxMargin"]["f32"] == "0.25"
              for s in sticks), f"the stick's patch and attack range are the client's: {sticks[:1]}")
    spears = [s for _, _, s in stacks if s.get("item") == "minecraft:iron_spear"]
    check(spears and all(s["attackRange"] is None and not s["added"] for s in spears),
          "the spear's attack range is its prototype's: no patch")
    shields = [(c, slot) for c, slot, s in stacks if s.get("item") == "minecraft:shield"]
    check((0, 45) in shields or ("inventory", 40) in shields, f"the shield is in the off hand: {shields}")


def verify_facts(probe, dump_path, check):
    """What the server established about the client, each with its trust level; claims recorded, bounded, minimal."""
    events = [json.loads(line) for line in open(dump_path) if line.strip()]
    facts = {e["fact"]: (e["value"], e["trust"]) for e in events if e["type"] == "ClientFact"}
    check(facts.get("IDENTITY") == ("UNAUTHENTICATED", "CLAIMED"), f"offline-mode identity is never treated as authenticated: {facts.get('IDENTITY')}")
    check(facts.get("TOPOLOGY") == ("DIRECT", "AUTHENTIC"), f"topology: {facts.get('TOPOLOGY')}")
    check(facts.get("NATIVE_PROTOCOL") == ("774", "FORMAT_BOUND") and facts.get("TRANSLATOR") == ("none", "FORMAT_BOUND")
          and facts.get("TRANSACTION_ORIGIN") == ("CLIENT", "FORMAT_BOUND"), f"protocol facts: {facts}")
    check("BEDROCK" not in facts, "no Bedrock claim without Floodgate/Geyser")
    inbound = [e["packet"] for e in events if e["type"] == "Inbound"]
    brands = [p["brand"] for p in inbound if p["type"] == "ClientBrand"]
    registrations = [p["channels"] for p in inbound if p["type"] == "ChannelRegistration"]
    infos = [p for p in inbound if p["type"] == "ClientInformation"]
    check(brands == [probe["brand"]] and registrations == [probe["channels"]], f"claimed brand and channels recorded: {brands} {registrations}")
    check(len(infos) == 1 and infos[0]["viewDistance"] == 3 and infos[0]["mainHand"] == "RIGHT" and "en_us" not in open(dump_path).read(),
          f"client settings minimal (no language): {infos}")


if __name__ == "__main__":
    main()
