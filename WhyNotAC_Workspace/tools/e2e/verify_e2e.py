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
            verify_edge(probe, log, sys.argv[5], check)
            verify_facts(probe, sys.argv[5], check)
            verify_cases(probe, log, sys.argv[6], check)
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
    m = re.search(r"\[wnprobe\] inject via (\S+)", log)
    c = re.search(r"\[wnprobe\] (\S+) cancelled a velocity packet", log)
    check(m is not None and c is not None and m.group(1) == c.group(1),
          f"knockback injected and cancelled through {m.group(1) if m else '?'}")


def verify_cases(probe, log, work_dir, check):
    """Slice 0: the probe's deliberate tick-framing violation went all the way: evidence, policy, case, alert, dossier, re-verification."""
    cases_dir = os.path.join(work_dir, "plugins", "WhyNotAC", "cases")
    dossiers = [json.load(open(os.path.join(cases_dir, f))) for f in sorted(os.listdir(cases_dir)) if f.endswith(".json")] \
        if os.path.isdir(cases_dir) else []
    check(probe.get("tick_framing_violation_tick") == 200, "the probe sent two movement packets in one tick")
    check(len(dossiers) == 1, f"exactly one case opened, for the one violation ({len(dossiers)} dossiers)")
    if not dossiers:
        return
    d = dossiers[0]
    check(d["check"] == "tick-framing" and d["evidenceClass"] == "P-M" and d["details"].get("movePackets") == "2"
          and d["details"].get("replyAllowance") == "0", f"the case is the tick-framing proof (P-M, 2 packets): {d['summary']}")
    check(d["playerName"] == "WhyNotProbe" and d["identity"] == "UNAUTHENTICATED",
          f"the case names the player, and an offline-mode identity stays unauthenticated: {d['playerName']} {d['identity']}")
    check(d["lifecycle"] == "SHADOW" and d["permittedActions"] == ["ALERT", "REVIEW"],
          f"a shadow check may only alert and be reviewed (docs/POLICY.md): {d['lifecycle']} {d['permittedActions']}")
    check(d["recording"] is not None and d["admissible"] is True, "the dossier points at the session's recording")
    check(re.search(r"\[WhyNotAC\] \[shadow\] WhyNotProbe tick-framing \(P-M\)", log) is not None,
          "a shadow alert went to the console (and to whynotac.alerts.shadow)")
    verified = [json.loads(line) for line in open(os.path.join(work_dir, "e2e-verify.jsonl"))] \
        if os.path.exists(os.path.join(work_dir, "e2e-verify.jsonl")) else []
    check(len(verified) == 1 and verified[0]["verified"] is True and verified[0]["evidenceInRecording"] == 1,
          f"`verify` re-ran the check over the recording, independently, and found the same evidence: {verified}")
    check(re.search(r"WhyNotAC cases \(newest first\): 1", log) is not None
          and re.search(re.escape(d["id"][:8]) + r" WhyNotProbe tick-framing P-M SHADOW", log) is not None, "/whynotac cases lists it")


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
