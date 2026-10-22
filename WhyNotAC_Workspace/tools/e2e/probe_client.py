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
  * an attack on a sheep 7 blocks away (tick 330)             -> an attack-reach case (P-A, shadow)
  * an attack on the husk with no swing after it (tick 345)    -> an action-grammar case (P-M, shadow)
  * an attack on the husk with the look turned away (tick 365) -> an attack-aim case (P-M, shadow)
  * an attack on the llama behind a wall, empty-handed (tick 315) -> an attack-aim case through blocks (P-M, shadow)
  * a use of the stick with a rotation 45 degrees off the look (tick 374) -> a use-aim case (P-M, shadow)
  * a click on the wall with the cursor off the look, empty-handed (tick 416) -> a use-aim case for the look (P-M, shadow)
  * digging the wall on its far face, empty-handed (tick 437) -> a use-aim case for the face (P-M, shadow)

After each use it sends its position once more (ticks 384, 405, 421 and 431: the server's acknowledgement of a use may
roll a player back), clicks the wall once where the vanilla pick puts the cursor (tick 395) and starts digging it on the
face its look enters (tick 426), which must open nothing.

It also attacks the nearest husk (tick 300), well within reach, which must open nothing. Before each attack its
mouse turns its look to the target, as a player does.

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
import math
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
PLAY_C_ADD_ENTITY, PLAY_S_INTERACT, PLAY_S_SWING = 0x01, 0x19, 0x3C
PLAY_S_USE_ITEM, PLAY_S_USE_ITEM_ON, PLAY_S_PLAYER_ACTION = 0x40, 0x3F, 0x28
PLAY_C_TELEPORT_ENTITY, PLAY_C_ENTITY_POSITION_SYNC, PLAY_C_MOVE_ENTITY_POS, PLAY_C_MOVE_ENTITY_POS_ROT = 0x7B, 0x23, 0x33, 0x34
# item registry ids (1.21.11, reports/registries.json)
ITEM_DIAMOND_SWORD, ITEM_STICK = 936, 946
# entity type registry ids
ENTITY_HUSK, ENTITY_SHEEP, ENTITY_LLAMA = 67, 111, 78  # protocol ids (reports/registries.json)


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
        self.entities = {}  # entity id -> (type id, x, y, z) as added

    # --- framing -------------------------------------------------------------------------------
    def frame(self, packet_id, payload=b""):
        data = varint(packet_id) + payload
        if self.threshold >= 0:
            if len(data) >= self.threshold:
                body = varint(len(data)) + zlib.compress(data)
            else:
                body = varint(0) + data
        else:
            body = data
        return varint(len(body)) + body

    def send(self, packet_id, payload=b""):
        self.send_all([(packet_id, payload)])

    def send_all(self, packets):
        """Packets the client's main thread sends back to back: no answer from the reader thread lands between them."""
        frames = b"".join(self.frame(packet_id, payload) for packet_id, payload in packets)
        with self.lock:
            self.sock.sendall(frames)

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
            elif packet_id == PLAY_C_ADD_ENTITY:
                entity_id = r.varint()
                r.unpack("16s")  # uuid
                entity_type = r.varint()
                ex, ey, ez = r.unpack("ddd")
                self.entities[entity_id] = (entity_type, ex, ey, ez)
            elif packet_id in (PLAY_C_TELEPORT_ENTITY, PLAY_C_ENTITY_POSITION_SYNC):
                # PositionMoveRotation (position, delta, yRot, xRot); a teleport adds its relative flags (x, y, z: bits 0-2)
                entity_id = r.varint()
                ex, ey, ez = r.unpack("ddd")
                r.unpack("dddff")
                relative = r.unpack("i") if packet_id == PLAY_C_TELEPORT_ENTITY else 0
                if entity_id in self.entities:
                    kind, ox, oy, oz = self.entities[entity_id]
                    self.entities[entity_id] = (kind, ex + (ox if relative & 1 else 0.0), ey + (oy if relative & 2 else 0.0),
                                                ez + (oz if relative & 4 else 0.0))
            elif packet_id in (PLAY_C_MOVE_ENTITY_POS, PLAY_C_MOVE_ENTITY_POS_ROT):
                entity_id = r.varint()
                dx, dy, dz = r.unpack("hhh")
                if entity_id in self.entities:  # VecDeltaCodec, near enough to aim: 1/4096 of a block per unit
                    kind, ox, oy, oz = self.entities[entity_id]
                    self.entities[entity_id] = (kind, ox + dx / 4096.0, oy + dy / 4096.0, oz + dz / 4096.0)
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


# Violation 6, an attack without its swing (1.5 s after the sheep: attack-reach and action-grammar each report once).
GRAMMAR_VIOLATION_TICK = 345
# Violation 7, an attack with the look turned away from the target.
AIM_VIOLATION_TICK = 365
# Violation 8, an attack on the llama behind the wall: the empty hand selected the tick before, the stick again after.
WALL_VIOLATION_TICK = 315
# An honest use of the stick, then violation 9: a use whose rotation is not the look (ticks without a mouse turn). After
# the attacks: the server's BlockChangedAck for a use may roll the player back, and the probe sends no position after.
USE_TICK, USE_AIM_VIOLATION_TICK = 367, 374
# The position again after the uses, then an honest click on the wall and violation 10: the cursor 0.4 off the look.
ANCHOR_TICKS, CLICK_TICK, CLICK_VIOLATION_TICK = (384, 405, 421, 431), 395, 416
# An honest START_DESTROY_BLOCK on the wall's face towards the probe, then violation 11: the same on its far face.
DIG_TICK, DIG_VIOLATION_TICK = 426, 437
EMPTY_HAND_TICKS = (CLICK_TICK, CLICK_VIOLATION_TICK, DIG_TICK, DIG_VIOLATION_TICK)
# Mth.SIN, as the game fills it: the view vector and the pick's cursor are computed exactly as the client does.
SIN_TABLE = []


def mth_sin(x):
    """Mth.sin(double): SIN[(int) ((long) (x * 10430.378350470453) & 65535)]."""
    return SIN_TABLE[int(x * 10430.378350470453) & 65535]


def mth_cos(x):
    return SIN_TABLE[int(x * 10430.378350470453 + 16384.0) & 65535]


def view_vector(x_rot, y_rot):
    """Entity.calculateViewVector: float products of the sine table, widened."""
    if not SIN_TABLE:
        SIN_TABLE.extend(f32(math.sin(i / 10430.378350470453)) for i in range(65536))
    rad = f32(math.pi / 180.0)
    f = f32(x_rot * rad)
    g = f32(-y_rot * rad)
    j = mth_cos(f)
    return (f32(mth_sin(g) * j), -mth_sin(f), f32(mth_cos(g) * j))


def east_face_click(eye, view, block):
    """Where the vanilla pick (Entity.pick, 4.5 blocks; AABB.clip) enters a full block's east face looking west, as the
    packet carries it: the block, and the location's float offsets from it."""
    to = (eye[0] + view[0] * 4.5, eye[1] + view[1] * 4.5, eye[2] + view[2] * 4.5)
    delta = (to[0] - eye[0], to[1] - eye[1], to[2] - eye[2])
    d = (block[0] + 1 - eye[0]) / delta[0]
    location = (eye[0] + d * delta[0], eye[1] + d * delta[1], eye[2] + d * delta[2])
    return tuple(f32(location[i] - block[i]) for i in range(3))
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


def nearest(client, entity_type):
    """The added entity of this type nearest to the probe (where it was added), as (id, horizontal distance)."""
    x, _, z = client.position[:3]
    found = [(((e[1] - x) ** 2 + (e[3] - z) ** 2) ** 0.5, i) for i, e in list(client.entities.items()) if e[0] == entity_type]
    return (min(found)[1], min(found)[0]) if found else (None, None)


def turn_towards(yaw, eye, target, multiplier, away=0.0):
    """The yaw after the mouse turned towards the target (plus `away` degrees) in whole cursor counts, as MouseHandler and
    Entity.turn compute it: the client's look then points at the target's position within half a count (0.075 degrees).

    Entity.calculateViewVector: the look's x is -sin(yaw), its z cos(yaw)."""
    wanted = math.degrees(math.atan2(-(target[0] - eye[0]), target[2] - eye[2])) + away
    step = multiplier * 0.15
    counts = round(((wanted - yaw + 180.0) % 360.0 - 180.0) / step)
    return f32(yaw + f32(f32(counts * multiplier) * f32(0.15)))


def turn_pitch_towards(pitch, eye, target, multiplier):
    """The pitch after the mouse turned towards the target point in whole cursor counts, as Entity.turn computes it (then
    clamped to [-90, 90]): positive looks down."""
    horizontal = math.hypot(target[0] - eye[0], target[2] - eye[2])
    wanted = math.degrees(math.atan2(-(target[1] - eye[1]), horizontal))
    counts = round((wanted - pitch) / (multiplier * 0.15))
    return min(90.0, max(-90.0, f32(pitch + f32(f32(counts * multiplier) * f32(0.15)))))


def attack(client, entity_id, swing=True):
    """As MultiPlayerGameMode.attack then startAttack's swing: ServerboundInteractPacket ATTACK, then the main hand's swing.

    Both from the client's main thread, early in the tick (handleKeybinds), before the tick's movement packet; the
    vanilla client answers pings on that thread too, so nothing but a keep-alive could come between them."""
    packets = [(PLAY_S_INTERACT, varint(entity_id) + varint(1) + b"\x00")]
    if swing:
        packets.append((PLAY_S_SWING, varint(0)))
    client.send_all(packets)


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
    attacks = []
    uses, use_sequence = [], 0
    clicks = []
    anchors = []  # the yaw bits of the position packets at ANCHOR_TICKS
    digs = []
    start = time.monotonic()
    while client.alive and time.monotonic() - start < seconds:
        # As Minecraft.tick: attacks come from handleKeybinds, before the tick's input and movement packets. The client
        # attacks what its look met at the tick's start: the mouse turned it to the target in the frame before, and the
        # tick's rotation packet carries that look.
        aim = None  # (target position, degrees away from it)
        if tick in (300, 330):
            # An attack in reach (the husk next to the probe), then violation 5: a sheep 7 blocks away.
            target, distance = nearest(client, ENTITY_HUSK if tick == 300 else ENTITY_SHEEP)
            if target is not None:
                attack(client, target)
                aim = (client.entities[target][1:], 0.0)
                attacks.append({"tick": tick, "target": target, "type": "husk" if tick == 300 else "sheep", "distance": distance})
        if tick == GRAMMAR_VIOLATION_TICK:
            # Violation 6: the husk again, in reach, without the swing every vanilla attack is followed by. WhyNotAC's
            # action-grammar check must open a case (and attack-reach none).
            target, distance = nearest(client, ENTITY_HUSK)
            if target is not None:
                attack(client, target, swing=False)
                aim = (client.entities[target][1:], 0.0)
                attacks.append({"tick": tick, "target": target, "type": "husk without swing", "distance": distance})
        if tick in (AIM_VIOLATION_TICK - 1, AIM_VIOLATION_TICK):
            # Violation 7: the husk again, in reach and swung at, with the look turned a quarter away from it since the tick
            # before (the stick's attack range also picks along the head's yaw, which is the previous tick's): the vanilla
            # client attacks only what its look met. WhyNotAC's attack-aim check must open a case.
            target, distance = nearest(client, ENTITY_HUSK)
            if target is not None:
                aim = (client.entities[target][1:], 90.0)
                if tick == AIM_VIOLATION_TICK:
                    attack(client, target)
                    attacks.append({"tick": tick, "target": target, "type": "husk off the look", "distance": distance})
        if tick == WALL_VIOLATION_TICK and inventory_step == 3:
            # Violation 8: the llama behind the stone wall, in reach and looked at, but only past the wall: the vanilla
            # client's pick stops at the wall's outline. With the empty hand (no attack range), selected the tick before.
            target, distance = nearest(client, ENTITY_LLAMA)
            if target is not None:
                attack(client, target)
                aim = (client.entities[target][1:], 0.0)
                attacks.append({"tick": tick, "target": target, "type": "llama behind the wall", "distance": distance})
        if inventory_step == 3 and tick in (USE_TICK, USE_AIM_VIOLATION_TICK):
            # As MultiPlayerGameMode.useItem: the held item (the stick) used with the player's rotation, before the tick's
            # movement, which turns nothing (no cursor counts on these ticks). Violation 9: 45 degrees off it, a silent aim.
            use_sequence += 1
            used_yaw = yaw if tick == USE_TICK else f32(yaw + 45.0)
            client.send(PLAY_S_USE_ITEM, varint(0) + varint(use_sequence) + struct.pack(">ff", used_yaw, pitch))
            uses.append({"tick": tick, "yaw": used_yaw, "look": yaw})
        if inventory_step == 3 and tick in (CLICK_TICK, CLICK_VIOLATION_TICK):
            # As startUseItem with the pick on the wall (the stone the fill command put 2 blocks west, its upper half at
            # the eye): the empty hand (selected the tick before) uses it at the cursor the pick computed with the tick's
            # look, which the mouse turned to the wall in the frame before. Violation 10: the cursor 0.4 off that look.
            eye = (x, y + f32(1.62), z)
            wall = (math.floor(x - 2), math.floor(y) + 1, math.floor(z))
            aim = ((wall[0] + 1, eye[1], z), 0.0)
            click_yaw = turn_towards(yaw, eye, aim[0], multiplier)
            cursor = east_face_click(eye, view_vector(pitch, click_yaw), wall)
            if tick == CLICK_VIOLATION_TICK:
                cursor = (cursor[0], cursor[1], f32(cursor[2] + 0.4))
            use_sequence += 1
            packed = (wall[0] & 0x3FFFFFF) << 38 | (wall[2] & 0x3FFFFFF) << 12 | (wall[1] & 0xFFF)  # BlockPos.asLong: the long's bits
            hit = struct.pack(">Q", packed) + varint(5)  # EAST
            client.send(PLAY_S_USE_ITEM_ON, varint(0) + hit + struct.pack(">fff??", *cursor, False, False) + varint(use_sequence))
            clicks.append({"tick": tick, "block": wall, "cursor": cursor, "yaw": click_yaw})
        if inventory_step == 3 and tick in (DIG_TICK, DIG_VIOLATION_TICK):
            # As startAttack with the pick on the wall: START_DESTROY_BLOCK with the pick's block and face (east, towards
            # the probe), the empty hand selected the tick before; no abort, the next start restarts it. Violation 11: the
            # west face, which the look cannot enter.
            eye = (x, y + f32(1.62), z)
            wall = (math.floor(x - 2), math.floor(y) + 1, math.floor(z))
            aim = ((wall[0] + 1, eye[1], z), 0.0)
            use_sequence += 1
            face = 5 if tick == DIG_TICK else 4  # EAST, WEST
            packed = (wall[0] & 0x3FFFFFF) << 38 | (wall[2] & 0x3FFFFFF) << 12 | (wall[1] & 0xFFF)
            client.send(PLAY_S_PLAYER_ACTION, varint(0) + struct.pack(">QB", packed, face) + varint(use_sequence))
            digs.append({"tick": tick, "block": wall, "face": "EAST" if face == 5 else "WEST"})
        if inventory_step == 3 and (tick - 1 in EMPTY_HAND_TICKS or tick + 1 in EMPTY_HAND_TICKS):
            client.send(PLAY_S_SET_CARRIED_ITEM, struct.pack(">h", 0 if tick + 1 in EMPTY_HAND_TICKS else 2))
        if inventory_step == 3 and tick in (WALL_VIOLATION_TICK - 1, WALL_VIOLATION_TICK + 1):
            client.send(PLAY_S_SET_CARRIED_ITEM, struct.pack(">h", 0 if tick < WALL_VIOLATION_TICK else 2))
        # Inventory, once the given items arrived: select hotbar slot 2 (the stick), then move the sword from hotbar 0
        # to hotbar 4 with two clicks, each listing the slots it changed as the vanilla client hashes them. As the
        # client: the hotbar slot goes out at the tick's start, the clicks between ticks, both before the movement.
        if inventory_step == 0 and client.slots.get(36, (0, 0))[1] == ITEM_DIAMOND_SWORD and client.slots.get(38, (0, 0))[1] == ITEM_STICK:
            client.send(PLAY_S_SET_CARRIED_ITEM, struct.pack(">h", 2))
            inventory_step = 1
        elif inventory_step == 1:
            client.send(PLAY_S_CONTAINER_CLICK, click(0, client.state_id, 36, {36: None}, (ITEM_DIAMOND_SWORD, 1)))
            inventory_step = 2
        elif inventory_step == 2:
            client.send(PLAY_S_CONTAINER_CLICK, click(0, client.state_id, 40, {40: (ITEM_DIAMOND_SWORD, 1)}, None))
            inventory_step = 3
        forward = (tick // 40) % 2 == 0  # toggle W every 2 seconds
        flags = 0x01 if forward else 0x00
        if flags != sent_input:
            client.send(PLAY_S_INPUT, bytes([flags]))
            sent_input = flags
            inputs_sent.append(flags)
        counts = (tick % 7) - 3  # -3..3 cursor counts per frame, one frame per tick
        increment = f32(f32(counts * multiplier) * f32(0.15))
        new_yaw = f32(yaw + increment) if aim is None else turn_towards(yaw, (x, y + 1.62, z), aim[0], multiplier, aim[1])
        if tick in ANCHOR_TICKS:
            # its position once more (unchanged: it does not walk), in the tick's one movement packet
            client.send(PLAY_S_MOVE_POS_ROT, struct.pack(">dddffB", x, y, z, new_yaw, pitch, 1))
            anchors.append(struct.unpack(">I", struct.pack(">f", new_yaw))[0])
            yaw = new_yaw
        elif new_yaw != yaw:
            client.send(PLAY_S_MOVE_ROT, struct.pack(">ffB", new_yaw, pitch, 1))
            yaw_history.append((yaw, new_yaw, counts))
            yaw = new_yaw
        if tick == 200:
            # Violation 3: a second movement packet in the same client tick (what mace-damage and click-teleport
            # hacks do). The vanilla client never sends one outside a reply: WhyNotAC's tick-framing check must open a case.
            client.send(PLAY_S_MOVE_POS, struct.pack(">dddB", x, y, z, 1))  # position only: the rotation trace stays the probe's own
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
        "attacks": attacks,
        "uses": uses,
        "clicks": clicks,
        "anchor_yaw_bits": anchors,
        "digs": digs,
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
