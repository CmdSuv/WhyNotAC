#!/usr/bin/env python3
"""
WhyNotAC end-to-end probe: a minimal Minecraft 1.21.11 (protocol 774) client written from the
official packet table (Mojang data generator, reports/packets.json).

It logs into an offline-mode server, completes the configuration phase, accepts the spawn teleport
and then behaves like a vanilla client for a while: it sends PlayerInput, rotation packets produced
with the *exact* float32 arithmetic of MouseHandler/Entity.turn, ClientTickEnd every tick, and answers
keep-alives and pings. Two deliberate protocol violations are injected so the server-side detection
can be verified live:

  * one transaction is never answered while the next one is  -> SKIPPED_TRANSACTION
  * one transaction is answered twice                         -> DUPLICATE_TRANSACTION
  * two movement packets in one client tick (tick 200)        -> a tick-framing case (P-M, shadow)

It also keeps a trace of every transaction ping and every velocity packet addressed to itself, in
arrival order, with velocities decoded exactly as the client's LpVec3 codec does: the ground truth the
server-side timeline must match.

Usage: probe_client.py HOST PORT NAME SECONDS
WhyNotAC transaction ids are random, so the probe treats every play ping alike (on the test server
WhyNotAC is the only plugin that sends them).

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

# packet ids (1.21.11, from reports/packets.json)
LOGIN_C_DISCONNECT, LOGIN_C_FINISHED, LOGIN_C_COMPRESSION, LOGIN_C_CUSTOM_QUERY = 0x00, 0x02, 0x03, 0x04
LOGIN_S_HELLO, LOGIN_S_CUSTOM_QUERY_ANSWER, LOGIN_S_ACK = 0x00, 0x02, 0x03
CFG_C_DISCONNECT, CFG_C_FINISH, CFG_C_KEEP_ALIVE, CFG_C_PING, CFG_C_KNOWN_PACKS = 0x02, 0x03, 0x04, 0x05, 0x0E
CFG_S_CLIENT_INFO, CFG_S_CUSTOM_PAYLOAD, CFG_S_FINISH, CFG_S_KEEP_ALIVE, CFG_S_PONG, CFG_S_KNOWN_PACKS = 0x00, 0x02, 0x03, 0x04, 0x05, 0x07
BRAND = "whynotac-probe"
CHANNELS = ["whynotac:probe", "example:sync"]
PLAY_C_CHUNK_BATCH_FINISHED, PLAY_C_DISCONNECT, PLAY_C_KEEP_ALIVE, PLAY_C_LOGIN, PLAY_C_PING, PLAY_C_POSITION, PLAY_C_START_CONFIG = (
    0x0B, 0x20, 0x2B, 0x30, 0x3B, 0x46, 0x74)
PLAY_S_ACCEPT_TELEPORT, PLAY_S_CHUNK_BATCH_RECEIVED, PLAY_S_TICK_END, PLAY_S_KEEP_ALIVE = 0x00, 0x0A, 0x0C, 0x1B
PLAY_S_MOVE_POS_ROT, PLAY_S_MOVE_ROT, PLAY_S_INPUT, PLAY_S_LOADED, PLAY_S_PONG = 0x1E, 0x1F, 0x2A, 0x2B, 0x2C
PLAY_S_MOVE_POS = 0x1D
PLAY_C_SET_ENTITY_MOTION = 0x63
PLAY_C_CONTAINER_SET_CONTENT, PLAY_C_CONTAINER_SET_SLOT = 0x12, 0x14
PLAY_S_CONTAINER_CLICK, PLAY_S_SET_CARRIED_ITEM = 0x11, 0x34
# item registry ids (1.21.11, reports/registries.json)
ITEM_DIAMOND_SWORD, ITEM_STICK = 936, 946


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


def read_lpvec3(r):
    """net.minecraft.network.LpVec3.read, operation for operation (IEEE doubles, same order)."""
    b0 = r.unpack("B")
    if b0 == 0:
        return 0.0, 0.0, 0.0
    b1 = r.unpack("B")
    high = r.unpack("I")
    packed = (high << 16) | (b1 << 8) | b0
    scale = b0 & 3
    if b0 & 4:
        scale |= (r.varint() & 0xFFFFFFFF) << 2

    def unpack(value):
        return min(value & 32767, 32766.0) * 2.0 / 32766.0 - 1.0

    return unpack(packed >> 3) * scale, unpack(packed >> 18) * scale, unpack(packed >> 33) * scale


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
        self.pings_seen = 0
        self.skip_next = False
        self.duplicate_next = False
        self.last_answered = None
        self.skipped_ids = []
        self.duplicated_ids = []
        self.trace = []  # ["ping", id] and ["motion", x, y, z] in arrival order
        self.loaded = threading.Event()
        self.state_id = 0  # the inventory menu's, as the server last sent it
        self.slots = {}    # inventory menu slot -> (count, item id), from the slot packets

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
                # What a vanilla client announces (the brand) and what a modded one adds (channel registrations).
                self.send(CFG_S_CUSTOM_PAYLOAD, mc_string("minecraft:brand") + mc_string(BRAND))
                self.send(CFG_S_CUSTOM_PAYLOAD, mc_string("minecraft:register") + "\x00".join(CHANNELS).encode("utf-8"))
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
                # As vanilla's handleMovePlayer: the accept, then PosRot with onGround=false, horizontalCollision=false.
                self.send(PLAY_S_MOVE_POS_ROT, struct.pack(">dddffB", x, y, z, yaw, pitch, 0))
                if not self.loaded.is_set():
                    self.send(PLAY_S_LOADED)
                    self.loaded.set()
            elif packet_id == PLAY_C_KEEP_ALIVE:
                self.send(PLAY_S_KEEP_ALIVE, struct.pack(">q", r.unpack("q")))
            elif packet_id == PLAY_C_SET_ENTITY_MOTION:
                if r.varint() == self.entity_id:
                    self.trace.append(["motion"] + [repr(c) for c in read_lpvec3(r)])
            elif packet_id == PLAY_C_PING:
                ping_id = r.unpack("i")
                self.trace.append(["ping", ping_id])
                self.pings_seen += 1
                if self.skip_next:
                    self.skip_next = False
                    self.skipped_ids.append(ping_id)
                    return  # violation 1: never answer this one
                self.send(PLAY_S_PONG, struct.pack(">i", ping_id))
                self.pings_answered += 1
                self.last_answered = ping_id
                if self.duplicate_next:
                    self.duplicate_next = False
                    self.duplicated_ids.append(ping_id)
                    self.send(PLAY_S_PONG, struct.pack(">i", ping_id))  # violation 2: answer it twice
            elif packet_id == PLAY_C_CONTAINER_SET_SLOT:
                container, state_id, slot = r.varint(), r.varint(), r.unpack("h")
                count = r.varint()
                if container == 0:
                    self.state_id = state_id
                    self.slots[slot] = (count, r.varint() if count > 0 else 0)
            elif packet_id == PLAY_C_CONTAINER_SET_CONTENT:
                container, state_id = r.varint(), r.varint()
                if container == 0:
                    self.state_id = state_id
                    for slot in range(r.varint()):
                        count = r.varint()
                        if count > 0:
                            break  # a stack's components are not decoded here: the rest stays as it was
                        self.slots[slot] = (0, 0)
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


def server_list_ping(host, port):
    """What every launcher does before joining: handshake with intent STATUS, then a status request."""
    sock = socket.create_connection((host, port), timeout=10)
    handshake = varint(0x00) + varint(PROTOCOL) + mc_string(host) + struct.pack(">H", port) + varint(1)
    request = varint(0x00)
    sock.sendall(varint(len(handshake)) + handshake + varint(len(request)) + request)
    data = sock.recv(65536)
    sock.close()
    return b"version" in data


# Extra ClientTickEnd sent at once for violation 4: well above the client-clock check's 22 ticks of slack.
CLOCK_BURST = 40


def hashed_stack(stack):
    """HashedStack.STREAM_CODEC: optional (item id, count, added components {type: hash}, removed types); none here have a patch."""
    if stack is None:
        return b"\x00"
    item, count = stack
    return b"\x01" + varint(item) + varint(count) + varint(0) + varint(0)


def click(container, state_id, slot, changed, carried):
    """ServerboundContainerClickPacket: a PICKUP (left) click with the client's predicted changed slots and carried stack."""
    body = varint(container) + varint(state_id) + struct.pack(">hb", slot, 0) + varint(0)
    body += varint(len(changed))
    for changed_slot, stack in sorted(changed.items()):
        body += struct.pack(">h", changed_slot) + hashed_stack(stack)
    return body + hashed_stack(carried)


def main():
    host, port, name, seconds = sys.argv[1], int(sys.argv[2]), sys.argv[3], float(sys.argv[4])
    status_ok = server_list_ping(host, port)
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
    extra_tick_ends = 0
    inputs_sent = []
    inventory_step = 0
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
        if tick == 200:
            # Violation 3: a second movement packet in the same client tick (what mace-damage and click-teleport
            # hacks do). The vanilla client never sends one outside a reply: WhyNotAC's tick-framing check must open a case.
            client.send(PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 1))  # position only: the rotation trace stays the probe's own
        # Inventory, once the given items arrived: select hotbar slot 2 (the stick), then move the sword from
        # hotbar 0 to hotbar 4 with two clicks, each listing the slots it changed as the vanilla client hashes them.
        if inventory_step == 0 and client.slots.get(36, (0, 0))[1] == ITEM_DIAMOND_SWORD and client.slots.get(38, (0, 0))[1] == ITEM_STICK:
            client.send(PLAY_S_SET_CARRIED_ITEM, struct.pack(">h", 2))
            inventory_step = 1
        elif inventory_step == 1:
            client.send(PLAY_S_CONTAINER_CLICK, click(0, client.state_id, 36, {36: None}, (ITEM_DIAMOND_SWORD, 1)))
            inventory_step = 2
        elif inventory_step == 2:
            client.send(PLAY_S_CONTAINER_CLICK, click(0, client.state_id, 40, {40: (ITEM_DIAMOND_SWORD, 1)}, None))
            inventory_step = 3
        client.send(PLAY_S_TICK_END)
        if tick == 240:
            # Violation 4: a burst of extra ClientTickEnd (a timer, or hiding extra movement packets from the tick
            # framing): more client ticks than the client's clock allows. WhyNotAC's client-clock check must open a case.
            for _ in range(CLOCK_BURST):
                client.send(PLAY_S_TICK_END)
            extra_tick_ends += CLOCK_BURST
        if tick == 100:
            client.skip_next = True       # violation 1, at ~5 s
        if tick == 160:
            client.duplicate_next = True  # violation 2, at ~8 s
        tick += 1
        # As vanilla's DeltaTracker: a client that fell behind runs at most 10 ticks to catch up and drops the rest.
        start = max(start, time.monotonic() - 0.5 - tick * 0.05)
        time.sleep(max(0.0, start + tick * 0.05 - time.monotonic()))

    last_from, last_to, last_counts = yaw_history[-1] if yaw_history else (yaw, yaw, 0)
    summary = {
        "entity_id": client.entity_id,
        "ticks": tick,
        "pings_answered": client.pings_answered,
        "pings_seen": client.pings_seen,
        "skipped_transaction_ids": [hex(i & 0xFFFFFFFF) for i in client.skipped_ids],
        "duplicated_transaction_ids": [hex(i & 0xFFFFFFFF) for i in client.duplicated_ids],
        "yaw_steps_bits": [[float_bits(a), float_bits(b)] for a, b, _ in yaw_history],
        "pitch_bits": float_bits(pitch),
        "inputs_sent": inputs_sent,
        "tick_framing_violation_tick": 200 if tick > 200 else None,
        "clock_violation_tick": 240 if tick > 240 else None,
        "clock_burst": CLOCK_BURST,
        "tick_ends_sent": tick + extra_tick_ends,
        "inventory_steps": inventory_step,
        "last_yaw": repr(last_to),
        "last_yaw_delta_exact": repr(last_to - last_from),
        "last_counts": last_counts,
        "multiplier": repr(multiplier),
        "server_list_ping_answered": status_ok,
        "still_connected": client.alive,
        "trace": client.trace,
        "brand": BRAND,
        "channels": CHANNELS,
        "disconnect_reason": client.disconnect_reason,
    }
    print(json.dumps(summary))
    client.sock.close()


if __name__ == "__main__":
    main()
