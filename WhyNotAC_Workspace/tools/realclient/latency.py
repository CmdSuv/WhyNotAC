#!/usr/bin/env python3
"""
A TCP relay that delays both directions, for a legitimate laggy connection (VAL-13): every chunk read is written on
after DELAY ms plus a uniform jitter of up to +-JITTER ms, never before the chunk read before it (TCP keeps order, so
a late chunk holds the ones behind it: the bursts a jittery link gives). Deterministic per seed.

usage: latency.py LISTEN_PORT TARGET_HOST:TARGET_PORT DELAY_MS JITTER_MS SEED
"""
import asyncio
import random
import sys


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
            last = max(last, loop.time() + (delay + rng.uniform(-jitter, jitter)) / 1000.0)
            await queue.put((last, data))
    finally:
        await queue.put((last, None))
        await sender


async def main():
    listen, target, delay, jitter, seed = int(sys.argv[1]), sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
    host, port = target.rsplit(":", 1)
    connections = 0

    async def handle(client_reader, client_writer):
        nonlocal connections
        connections += 1
        rng = random.Random(f"{seed}/{connections}")
        server_reader, server_writer = await asyncio.open_connection(host, int(port))
        await asyncio.gather(pipe(client_reader, server_writer, delay, jitter, rng),
                             pipe(server_reader, client_writer, delay, jitter, rng), return_exceptions=True)

    server = await asyncio.start_server(handle, "127.0.0.1", listen)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
