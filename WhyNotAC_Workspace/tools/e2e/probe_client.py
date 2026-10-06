#!/usr/bin/env python3
"""
WhyNotAC end-to-end probe: a minimal Minecraft 1.21.11 (protocol 774) client written from the
official packet table (Mojang data generator, reports/packets.json).

It logs into an offline-mode server, completes the configuration phase, accepts the spawn teleport
and then behaves like a vanilla client for a while: it sends PlayerInput, rotation packets produced
with the *exact* float32 arithmetic of MouseHandler/Entity.turn, ClientTickEnd every tick, and answers
keep-alives and pings. Two deliberate protocol violations are injected so the server-side detection
can be verified live:

  * one WhyNotAC transaction is never answered while the next one is  -> SKIPPED_TRANSACTION
  * one pong carries a WhyNotAC-prefixed id the server never sent     -> UNKNOWN_TRANSACTION

Usage: probe_client.py HOST PORT NAME SECONDS
Stdlib only. Prints a JSON summary as the last line.
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

PROTOCOL = 774
WN_PREFIX = 0x574E0000

# packet ids (1.21.11, from reports/packets.json)
LOGIN_C_DISCONNECT, LOGIN_C_FINISHED, LOGIN_C_COMPRESSION, LOGIN_C_CUSTOM_QUERY = 0x00, 0x02, 0x03, 0x04
LOGIN_S_HELLO, LOGIN_S_CUSTOM_QUERY_ANSWER, LOGIN_S_ACK = 0x00, 0x02, 0x03
CFG_C_DISCONNECT, CFG_C_FINISH, CFG_C_KEEP_ALIVE, CFG_C_PING, CFG_C_KNOWN_PACKS = 0x02, 0x03, 0x04, 0x05, 0x0E
CFG_S_CLIENT_INFO, CFG_S_FINISH, CFG_S_KEEP_ALIVE, CFG_S_PONG, CFG_S_KNOWN_PACKS = 0x00, 0x03, 0x04, 0x05, 0x07
PLAY_C_CHUNK_BATCH_FINISHED, PLAY_C_DISCONNECT, PLAY_C_KEEP_ALIVE, PLAY_C_LOGIN, PLAY_C_PING, PLAY_C_POSITION, PLAY_C_START_CONFIG = (
    0x0B, 0x20, 0x2B, 0x30, 0x3B, 0x46, 0x74)
PLAY_S_ACCEPT_TELEPORT, PLAY_S_CHUNK_BATCH_RECEIVED, PLAY_S_TICK_END, PLAY_S_KEEP_ALIVE = 0x00, 0x0A, 0x0C, 0x1B
PLAY_S_MOVE_POS_ROT, PLAY_S_MOVE_ROT, PLAY_S_INPUT, PLAY_S_LOADED, PLAY_S_PONG = 0x1E, 0x1F, 0x2A, 0x2B, 0x2C


def f32(x):
    return struct.unpack(">f", struct.pack(">f", x))[0]


def float_bits(x):
    return struct.unpack(">I", struct.pack(">f", x))[0]


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
        value = struct.unpack_from(">" + fmt, self.data, self.pos)
        self.pos += size
        return value if len(value) > 1 else value[0]

    def rest(self):
        return self.data[self.pos:]


class Client:
    def __init__(self, host, port, name):
        self.sock = socket.create_connection((host, port), timeout=30)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.host, self.port, self.name = host, port, name
        self.threshold = -1
        self.lock = threading.Lock()
        self.buffer = b""
        self.state = "login"
        self.entity_id = None
        self.position = None
        self.alive = True
        self.disconnect_reason = None
        self.pings_answered = 0
        self.wn_pings_seen = 0
        self.skip_next_wn = False
        self.skipped_ids = []
        self.forged_ids = []
        self.loaded = threading.Event()

    # --- framing -------------------------------------------------------------------------------
    def send(self, packet_id, payload=b""):
        data = varint(packet_id) + payload
        if self.threshold >= 0:
            if len(data) >= self.threshold:
                body = varint(len(data)) + zlib.compress(data)
            else:
                body = varint(0) + data
        else:
            body = data
        with self.lock:
            self.sock.sendall(varint(len(body)) + body)

    def _read_exact(self, n):
        while len(self.buffer) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("server closed the connection")
            self.buffer += chunk
        data, self.buffer = self.buffer[:n], self.buffer[n:]
        return data

    def _read_varint_stream(self):
        result, shift = 0, 0
        while True:
            b = self._read_exact(1)[0]
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
            shift += 7

    def receive(self):
        length = self._read_varint_stream()
        frame = self._read_exact(length)
        if self.threshold >= 0:
            r = Reader(frame)
            data_length = r.varint()
            frame = zlib.decompress(r.rest()) if data_length else r.rest()
        r = Reader(frame)
        return r.varint(), r

    # --- protocol ------------------------------------------------------------------------------
    def login(self):
        self.send(0x00, varint(PROTOCOL) + mc_string(self.host) + struct.pack(">H", self.port) + varint(2))
        # Same as Java's UUID.nameUUIDFromBytes("OfflinePlayer:" + name), the vanilla offline-mode id.
        offline_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + self.name).encode("utf-8")).digest(), version=3)
        self.send(LOGIN_S_HELLO, mc_string(self.name) + offline_uuid.bytes)

    def handle(self, packet_id, r):
        if self.state == "login":
            if packet_id == LOGIN_C_COMPRESSION:
                self.threshold = r.varint()
            elif packet_id == LOGIN_C_FINISHED:
                self.send(LOGIN_S_ACK)
                self.state = "configuration"
                self.send(CFG_S_CLIENT_INFO, mc_string("en_us") + bytes([3]) + varint(0) + b"\x01" + b"\x7f" + varint(1) + b"\x00" + b"\x01" + varint(0))
            elif packet_id == LOGIN_C_CUSTOM_QUERY:
                self.send(LOGIN_S_CUSTOM_QUERY_ANSWER, varint(r.varint()) + b"\x00")
            elif packet_id == LOGIN_C_DISCONNECT:
                raise RuntimeError("login rejected: " + r.rest().decode("utf-8", "replace"))
        elif self.state == "configuration":
            if packet_id == CFG_C_KNOWN_PACKS:
                self.send(CFG_S_KNOWN_PACKS, varint(1) + mc_string("minecraft") + mc_string("core") + mc_string("1.21.11"))
            elif packet_id == CFG_C_KEEP_ALIVE:
                self.send(CFG_S_KEEP_ALIVE, struct.pack(">q", r.unpack("q")))
            elif packet_id == CFG_C_PING:
                self.send(CFG_S_PONG, struct.pack(">i", r.unpack("i")))
            elif packet_id == CFG_C_FINISH:
                self.send(CFG_S_FINISH)
                self.state = "play"
            elif packet_id == CFG_C_DISCONNECT:
                raise RuntimeError("configuration rejected")
        else:
            if packet_id == PLAY_C_LOGIN:
                self.entity_id = r.unpack("i")
            elif packet_id == PLAY_C_POSITION:
                teleport_id = r.varint()
                x, y, z, _dx, _dy, _dz = r.unpack("dddddd")
                yaw, pitch = r.unpack("ff")
                self.send(PLAY_S_ACCEPT_TELEPORT, varint(teleport_id))
                self.position = (x, y, z, yaw, pitch)
                self.send(PLAY_S_MOVE_POS_ROT, struct.pack(">dddffB", x, y, z, yaw, pitch, 1))
                if not self.loaded.is_set():
                    self.send(PLAY_S_LOADED)
                    self.loaded.set()
            elif packet_id == PLAY_C_KEEP_ALIVE:
                self.send(PLAY_S_KEEP_ALIVE, struct.pack(">q", r.unpack("q")))
            elif packet_id == PLAY_C_PING:
                ping_id = r.unpack("i")
                if (ping_id & 0xFFFF0000) == WN_PREFIX:
                    self.wn_pings_seen += 1
                    if self.skip_next_wn:
                        self.skip_next_wn = False
                        self.skipped_ids.append(ping_id)
                        return  # violation 1: never answer this one
                self.send(PLAY_S_PONG, struct.pack(">i", ping_id))
                self.pings_answered += 1
            elif packet_id == PLAY_C_CHUNK_BATCH_FINISHED:
                self.send(PLAY_S_CHUNK_BATCH_RECEIVED, struct.pack(">f", 9.0))
            elif packet_id == PLAY_C_DISCONNECT:
                self.disconnect_reason = r.rest()[:200].decode("utf-8", "replace")
                self.alive = False

    def reader_loop(self):
        try:
            while self.alive:
                packet_id, r = self.receive()
                self.handle(packet_id, r)
        except (EOFError, OSError) as e:
            if self.alive:
                self.disconnect_reason = self.disconnect_reason or str(e)
            self.alive = False


def main():
    host, port, name, seconds = sys.argv[1], int(sys.argv[2]), sys.argv[3], float(sys.argv[4])
    client = Client(host, port, name)
    client.login()
    threading.Thread(target=client.reader_loop, daemon=True).start()
    if not client.loaded.wait(60):
        raise SystemExit("never reached play state: " + str(client.disconnect_reason))

    x, y, z, yaw, pitch = client.position
    # Vanilla mouse model at 100% sensitivity (option 0.5), exactly as MouseHandler + Entity.turn compute it.
    d = 0.5 * f32(0.6) + f32(0.2)
    multiplier = d * d * d * 8.0
    yaw_history = []
    tick, sent_input = 0, None
    inputs_sent = []
    start = time.monotonic()
    while client.alive and time.monotonic() - start < seconds:
        forward = (tick // 40) % 2 == 0  # toggle W every 2 seconds
        flags = 0x01 if forward else 0x00
        if flags != sent_input:
            client.send(PLAY_S_INPUT, bytes([flags]))
            sent_input = flags
            inputs_sent.append(flags)
        counts = (tick % 7) - 3  # -3..3 cursor counts per frame, one frame per tick
        increment = f32(f32(counts * multiplier) * f32(0.15))
        new_yaw = f32(yaw + increment)
        if new_yaw != yaw:
            client.send(PLAY_S_MOVE_ROT, struct.pack(">ffB", new_yaw, pitch, 1))
            yaw_history.append((yaw, new_yaw, counts))
            yaw = new_yaw
        client.send(PLAY_S_TICK_END)
        if tick == 100:
            client.skip_next_wn = True  # violation 1, at ~5 s
        if tick == 160:
            forged = WN_PREFIX | 0xBEEF
            client.forged_ids.append(forged)
            client.send(PLAY_S_PONG, struct.pack(">i", forged))  # violation 2, at ~8 s
        tick += 1
        time.sleep(max(0.0, start + tick * 0.05 - time.monotonic()))

    last_from, last_to, last_counts = yaw_history[-1] if yaw_history else (yaw, yaw, 0)
    summary = {
        "entity_id": client.entity_id,
        "ticks": tick,
        "pings_answered": client.pings_answered,
        "whynotac_pings_seen": client.wn_pings_seen,
        "skipped_transaction_ids": [hex(i) for i in client.skipped_ids],
        "forged_transaction_ids": [hex(i) for i in client.forged_ids],
        "yaw_steps_bits": [[float_bits(a), float_bits(b)] for a, b, _ in yaw_history],
        "pitch_bits": float_bits(pitch),
        "inputs_sent": inputs_sent,
        "tick_ends_sent": tick,
        "last_yaw": repr(last_to),
        "last_yaw_delta_exact": repr(last_to - last_from),
        "last_counts": last_counts,
        "multiplier": repr(multiplier),
        "still_connected": client.alive,
        "disconnect_reason": client.disconnect_reason,
    }
    print(json.dumps(summary))
    client.sock.close()


if __name__ == "__main__":
    main()
