#!/usr/bin/env python3
"""
WhyNotAC Multi-Version Interop Probe: Minecraft 1.21.4 (protocol 769) client.

Connects through real ViaVersion + ViaBackwards onto a Paper 1.21.11 (protocol 774) server to prove:
  1. Honest 1.21.4 play triggers 0 false positives.
  2. Physical checks (e.g. movement) produce NO_CLAIM (strictly rejected by AdmissibilityTracker)
     even when an impossible hover is injected, because the stream is translated.
  3. Translator-safe checks (evaluateTranslatorSafe: packet-bounds, transaction-order) STILL
     detect injected protocol/causal violations (invalid pitch, invalid hotbar slot, skipped ping).

Usage: old_protocol_probe.py HOST PORT NAME SECONDS
Stdlib only. Prints a JSON summary on the final line.
"""
import hashlib
import json
import socket
import struct
import sys
import threading
import time
import uuid
import zlib

PROTOCOL_1_21_4 = 769

# Login (1.21.4 == 1.21.11)
LOGIN_C_DISCONNECT, LOGIN_C_FINISHED, LOGIN_C_COMPRESSION, LOGIN_C_CUSTOM_QUERY = 0x00, 0x02, 0x03, 0x04
LOGIN_S_HELLO, LOGIN_S_CUSTOM_QUERY_ANSWER, LOGIN_S_ACK = 0x00, 0x02, 0x03

# Configuration (1.21.4)
CFG_C_DISCONNECT, CFG_C_FINISH, CFG_C_KEEP_ALIVE, CFG_C_PING, CFG_C_KNOWN_PACKS = 0x02, 0x03, 0x04, 0x05, 0x0E
CFG_S_CLIENT_INFO, CFG_S_CUSTOM_PAYLOAD, CFG_S_FINISH, CFG_S_KEEP_ALIVE, CFG_S_PONG, CFG_S_KNOWN_PACKS = 0x00, 0x02, 0x03, 0x04, 0x05, 0x07

# Play (1.21.4, extracted directly from ViaVersion ClientboundPackets1_21_2 & ServerboundPackets1_21_4)
PLAY_C_CHUNK_BATCH_FINISHED = 0x0C
PLAY_C_DISCONNECT = 0x1D
PLAY_C_KEEP_ALIVE = 0x27
PLAY_C_LOGIN = 0x2C
PLAY_C_PING = 0x37
PLAY_C_POSITION = 0x42
PLAY_C_START_CONFIG = 0x70

PLAY_S_ACCEPT_TELEPORT = 0x00
PLAY_S_CHUNK_BATCH_RECEIVED = 0x09
PLAY_S_TICK_END = 0x0B
PLAY_S_KEEP_ALIVE = 0x1A
PLAY_S_MOVE_POS = 0x1C
PLAY_S_MOVE_POS_ROT = 0x1D
PLAY_S_MOVE_ROT = 0x1E
PLAY_S_INPUT = 0x29
PLAY_S_LOADED = 0x2A
PLAY_S_PONG = 0x2B
PLAY_S_SET_CARRIED_ITEM = 0x33


def varint(value):
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def mc_string(s):
    raw = s.encode("utf-8")
    return varint(len(raw)) + raw


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def varint(self):
        result, shift = 0, 0
        while True:
            b = self.data[self.pos]
            self.pos += 1
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        return result - (1 << 32) if result & (1 << 31) else result

    def unpack(self, fmt):
        size = struct.calcsize(fmt)
        vals = struct.unpack_from(fmt, self.data, self.pos)
        self.pos += size
        return vals


class Conn:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=20.0)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.threshold = -1
        self.lock = threading.Lock()
        self.buf = bytearray()

    def _recv_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("connection closed")
            self.buf.extend(chunk)
        out = bytes(self.buf[:n])
        del self.buf[:n]
        return out

    def _recv_varint(self):
        result, shift = 0, 0
        while True:
            b = self._recv_exact(1)[0]
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
            shift += 7

    def send(self, packet_id, body=b""):
        payload = varint(packet_id) + body
        if self.threshold >= 0:
            if len(payload) >= self.threshold:
                compressed = zlib.compress(payload)
                inner = varint(len(payload)) + compressed
            else:
                inner = b"\x00" + payload
            frame = varint(len(inner)) + inner
        else:
            frame = varint(len(payload)) + payload
        with self.lock:
            self.sock.sendall(frame)

    def recv(self):
        length = self._recv_varint()
        raw = self._recv_exact(length)
        r = Reader(raw)
        if self.threshold >= 0:
            uncompressed = r.varint()
            rest = raw[r.pos:]
            if uncompressed > 0:
                rest = zlib.decompress(rest)
            r = Reader(rest)
            pid = r.varint()
            return pid, rest[r.pos:]
        pid = r.varint()
        return pid, raw[r.pos:]

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def main():
    host, port, name, seconds = sys.argv[1], int(sys.argv[2]), sys.argv[3], float(sys.argv[4])
    offline_uuid = uuid.UUID(bytes=hashlib.md5(f"OfflinePlayer:{name}".encode()).digest(), version=3)
    conn = Conn(host, port)

    # Handshake (1.21.4 -> protocol 769, intent 2 = LOGIN)
    conn.send(0x00, varint(PROTOCOL_1_21_4) + mc_string(host) + struct.pack(">H", port) + varint(2))
    conn.send(LOGIN_S_HELLO, mc_string(name) + offline_uuid.bytes)

    # Login phase
    while True:
        pid, body = conn.recv()
        if pid == LOGIN_C_COMPRESSION:
            conn.threshold = Reader(body).varint()
        elif pid == LOGIN_C_CUSTOM_QUERY:
            qid = Reader(body).varint()
            conn.send(LOGIN_S_CUSTOM_QUERY_ANSWER, varint(qid) + b"\x00")
        elif pid == LOGIN_C_FINISHED:
            conn.send(LOGIN_S_ACK)
            break
        elif pid == LOGIN_C_DISCONNECT:
            raise RuntimeError(f"Disconnected during login: {body!r}")

    # Configuration phase (1.21.4)
    conn.send(
        CFG_S_CLIENT_INFO,
        mc_string("en_us") + struct.pack(">bb?B", 4, 0, True, 0x7F) + varint(1) + struct.pack(">??", False, True) + varint(0),
    )
    conn.send(CFG_S_CUSTOM_PAYLOAD, mc_string("minecraft:brand") + mc_string("whynotac-1.21.4-interop"))
    while True:
        pid, body = conn.recv()
        if pid == CFG_C_KEEP_ALIVE:
            conn.send(CFG_S_KEEP_ALIVE, body)
        elif pid == CFG_C_PING:
            conn.send(CFG_S_PONG, body)
        elif pid == CFG_C_KNOWN_PACKS:
            conn.send(CFG_S_KNOWN_PACKS, varint(0))
        elif pid == CFG_C_FINISH:
            conn.send(CFG_S_FINISH)
            break
        elif pid == CFG_C_DISCONNECT:
            raise RuntimeError(f"Disconnected during config: {body!r}")

    state = {
        "x": 0.0,
        "y": 0.0,
        "z": 0.0,
        "yaw": 0.0,
        "pitch": 0.0,
        "spawned": False,
        "running": True,
        "pings_seen": 0,
        "pings_answered": 0,
        "skipped_ping_injected": False,
        "skip_next_ping": False,
        "disconnected": False,
        "disconnect_reason": "",
    }
    state_lock = threading.Lock()

    def reader_loop():
        try:
            while state["running"]:
                pid, body = conn.recv()
                if pid == PLAY_C_KEEP_ALIVE:
                    conn.send(PLAY_S_KEEP_ALIVE, body)
                elif pid == PLAY_C_PING:
                    (ping_id,) = struct.unpack_from(">i", body, 0)
                    with state_lock:
                        state["pings_seen"] += 1
                        if state["skip_next_ping"] and not state["skipped_ping_injected"]:
                            state["skip_next_ping"] = False
                            state["skipped_ping_injected"] = True
                            continue  # Deliberately drop one ping so the next answered ping skips it!
                        state["pings_answered"] += 1
                    conn.send(PLAY_S_PONG, struct.pack(">i", ping_id))
                elif pid == PLAY_C_CHUNK_BATCH_FINISHED:
                    conn.send(PLAY_S_CHUNK_BATCH_RECEIVED, struct.pack(">f", 20.0))
                elif pid == PLAY_C_POSITION:
                    r = Reader(body)
                    tp_id = r.varint()
                    # 1.21.2+ PlayerPosition: tpId, x, y, z, dx, dy, dz, yaw, pitch, relFlags (int)
                    x, y, z, _dx, _dy, _dz, yaw, pitch, _flags = r.unpack(">ddddddffi")
                    with state_lock:
                        state["x"], state["y"], state["z"], state["yaw"], state["pitch"] = x, y, z, yaw, pitch
                        first = not state["spawned"]
                        state["spawned"] = True
                    conn.send(PLAY_S_ACCEPT_TELEPORT, varint(tp_id))
                    conn.send(PLAY_S_MOVE_POS_ROT, struct.pack(">dddffB", x, y, z, yaw, pitch, 0))
                    if first:
                        conn.send(PLAY_S_LOADED)
                elif pid == PLAY_C_START_CONFIG:
                    conn.send(0x0D)  # configuration ack
                elif pid == PLAY_C_DISCONNECT:
                    with state_lock:
                        state["disconnected"] = True
                        state["disconnect_reason"] = repr(body[:120])
                        state["running"] = False
                    break
        except (EOFError, OSError):
            with state_lock:
                state["running"] = False

    t = threading.Thread(target=reader_loop, daemon=True)
    t.start()

    # Wait for initial spawn teleport
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        with state_lock:
            if state["spawned"] or not state["running"]:
                break
        time.sleep(0.05)
    if not state["spawned"]:
        raise RuntimeError("Timed out waiting for initial spawn teleport through ViaBackwards")

    injected = []
    start = time.monotonic()
    tick = 0
    while time.monotonic() - start < seconds:
        with state_lock:
            if not state["running"]:
                break
            x, y, z, yaw, pitch = state["x"], state["y"], state["z"], state["yaw"], state["pitch"]

        tick += 1
        conn.send(PLAY_S_INPUT, struct.pack(">B", 0))

        # Phase 1 (ticks 1..90 = 4.5s): 100% honest 1.21.4 ticks -> must trigger 0 FP
        if tick <= 90:
            if tick % 10 == 0:
                yaw = (tick * 0.45) % 360.0
                with state_lock:
                    state["yaw"] = yaw
                conn.send(PLAY_S_MOVE_ROT, struct.pack(">ffB", yaw, 0.0, 1))
            else:
                conn.send(PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 1))
        # Phase 2 (ticks 95..115): Impossible mid-air hover (y + 0.6/tick) -> movement check MUST emit NO_CLAIM!
        elif 95 <= tick <= 115:
            y = y + 0.35
            with state_lock:
                state["y"] = y
            conn.send(PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 0))
            if tick == 95:
                injected.append("hover-no-claim")
        # Phase 3 (tick 125): Translator-safe skipped-transaction injection -> transaction-order MUST catch!
        elif tick == 125:
            with state_lock:
                state["skip_next_ping"] = True
            injected.append("skipped-transaction")
            conn.send(PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 0))
        # Phase 4 (tick 150): Translator-safe invalid hotbar slot -> packet-bounds MUST catch!
        elif tick == 150:
            conn.send(PLAY_S_SET_CARRIED_ITEM, struct.pack(">h", 11))
            injected.append("invalid-hotbar-slot")
            conn.send(PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 0))
        # Phase 5 (tick 175): Translator-safe invalid pitch (> 90 deg) -> packet-bounds MUST catch!
        elif tick == 175:
            conn.send(PLAY_S_MOVE_ROT, struct.pack(">ffB", yaw, 95.0, 0))
            injected.append("invalid-pitch")
        else:
            conn.send(PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 0))

        conn.send(PLAY_S_TICK_END)
        time.sleep(0.05)

    state["running"] = False
    conn.close()
    t.join(timeout=2.0)

    print(
        json.dumps(
            {
                "protocol": PROTOCOL_1_21_4,
                "name": name,
                "ticks": tick,
                "pingsSeen": state["pings_seen"],
                "pingsAnswered": state["pings_answered"],
                "skippedPingInjected": state["skipped_ping_injected"],
                "injected": injected,
                "disconnected": state["disconnected"],
                "disconnectReason": state["disconnect_reason"],
            }
        )
    )


if __name__ == "__main__":
    main()
