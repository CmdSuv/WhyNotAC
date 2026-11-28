#!/usr/bin/env python3
"""
A TCP relay that delays both directions, for a legitimate laggy connection (VAL-13, PH26-04):
every chunk read is written on after DELAY ms plus a uniform jitter of up to +-JITTER ms,
never before the chunk read before it (TCP keeps order, so a late chunk holds the ones behind it:
the bursts a jittery link gives). Deterministic per seed.

Built-in presets (--profile):
  moderate        100 ms each way +-40 ms  (~200 ms RTT)
  high            200 ms each way +-100 ms (~400..600 ms RTT)
  extreme-650ms   325 ms each way +-125 ms (>600 ms RTT, up to 900 ms burst spikes)

Also supports:
  --print-tc-netem [IFACE]  Prints equivalent Linux tc netem commands for kernel-level shaping.
  --self-test               Runs an offline loopback latency verification test.

usage:
  latency.py LISTEN_PORT TARGET_HOST:TARGET_PORT DELAY_MS JITTER_MS SEED
  latency.py --profile extreme-650ms LISTEN_PORT TARGET_HOST:TARGET_PORT [SEED]
  latency.py --print-tc-netem [lo]
  latency.py --self-test
"""
import asyncio
import random
import sys
import time

PROFILES = {
    "moderate": (100.0, 40.0),
    "high": (200.0, 100.0),
    "extreme-650ms": (325.0, 125.0),
}


async def pipe(reader, writer, delay, jitter, rng):
    loop = asyncio.get_running_loop()
    queue = asyncio.Queue()
    last = 0.0

    async def send():
        while True:
            release, data = await queue.get()
            if data is None:
                break
            wait = release - loop.time()
            if wait > 0:
                await asyncio.sleep(wait)
            writer.write(data)
            await writer.drain()
        writer.close()

    sender = asyncio.create_task(send())
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            last = max(last, loop.time() + max(0.0, delay + rng.uniform(-jitter, jitter)) / 1000.0)
            await queue.put((last, data))
    finally:
        await queue.put((last, None))
        await sender


def tc_netem_commands(iface="lo", profile="extreme-650ms"):
    delay, jitter = PROFILES[profile]
    return [
        f"sudo tc qdisc replace dev {iface} root netem delay {int(delay)}ms {int(jitter)}ms distribution normal loss 0.5%",
        f"# To clear: sudo tc qdisc del dev {iface} root",
    ]


async def run_self_test():
    async def echo(reader, writer):
        data = await reader.read(1024)
        writer.write(data)
        await writer.drain()
        writer.close()

    backend = await asyncio.start_server(echo, "127.0.0.1", 0)
    backend_port = backend.sockets[0].getsockname()[1]

    async def handle(cr, cw):
        rng = random.Random("selftest")
        sr, sw = await asyncio.open_connection("127.0.0.1", backend_port)
        await asyncio.gather(pipe(cr, sw, 40.0, 5.0, rng), pipe(sr, cw, 40.0, 5.0, rng), return_exceptions=True)

    relay = await asyncio.start_server(handle, "127.0.0.1", 0)
    relay_port = relay.sockets[0].getsockname()[1]

    async with backend, relay:
        t0 = time.monotonic()
        r, w = await asyncio.open_connection("127.0.0.1", relay_port)
        w.write(b"ping")
        await w.drain()
        reply = await r.read(4)
        elapsed_ms = (time.monotonic() - t0) * 1000.0
        w.close()
        assert reply == b"ping", f"Expected echo 'ping', got {reply!r}"
        assert elapsed_ms >= 65.0, f"Expected >=65ms round-trip delay, got {elapsed_ms:.1f}ms"
    cmds = tc_netem_commands("lo", "extreme-650ms")
    assert "325ms 125ms" in cmds[0]
    print(f"REALCLIENT_LATENCY_SELF_TEST: OK (rtt={elapsed_ms:.1f}ms)")


async def main():
    argv = sys.argv[1:]
    if argv == ["--self-test"]:
        await run_self_test()
        return
    if argv and argv[0] == "--print-tc-netem":
        iface = argv[1] if len(argv) > 1 else "lo"
        for line in tc_netem_commands(iface, "extreme-650ms"):
            print(line)
        return
    if argv and argv[0] == "--profile":
        profile = argv[1]
        if profile not in PROFILES:
            raise SystemExit(f"unknown profile: {profile} (available: {sorted(PROFILES)})")
        delay, jitter = PROFILES[profile]
        listen = int(argv[2])
        target = argv[3]
        seed = argv[4] if len(argv) > 4 else profile
    else:
        listen, target, delay, jitter, seed = int(argv[0]), argv[1], float(argv[2]), float(argv[3]), argv[4]

    host, port = target.rsplit(":", 1)
    connections = 0

    async def handle(client_reader, client_writer):
        nonlocal connections
        connections += 1
        rng = random.Random(f"{seed}/{connections}")
        server_reader, server_writer = await asyncio.open_connection(host, int(port))
        await asyncio.gather(
            pipe(client_reader, server_writer, delay, jitter, rng),
            pipe(server_reader, client_writer, delay, jitter, rng),
            return_exceptions=True,
        )

    server = await asyncio.start_server(handle, "127.0.0.1", listen)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
