#!/usr/bin/env python3
"""Probe the live EkosRemote server: Message channel handshake + lifecycle burst."""
import asyncio, json, sys
import websockets

URL = "ws://10.0.0.43:9000/message/ekos"

async def main():
    async with websockets.connect(URL, ping_interval=None, max_size=2**24) as ws:
        print("connected")

        async def send(t, payload=None):
            msg = {"type": t, "payload": payload} if payload is not None else {"type": t}
            await ws.send(json.dumps(msg))
            print(f"> {t}")

        await send("set_client_state", {"state": True})
        await asyncio.sleep(0.5)

        for t, p in [("get_connection", None), ("get_states", None), ("get_profiles", None), ("get_devices", None)]:
            await send(t, p)
            await asyncio.sleep(0.3)

        # drain for a few seconds
        try:
            for _ in range(40):
                raw = await asyncio.wait_for(ws.recv(), timeout=3)
                try:
                    data = json.loads(raw)
                    t = data.get("type", "?")
                    payload = data.get("payload")
                    s = json.dumps(payload) if payload is not None else ""
                    print(f"< {t}: {s[:200]}")
                except Exception:
                    print(f"< (non-json {len(raw)} bytes) {raw[:120]}")
        except asyncio.TimeoutError:
            print("(silence)")

asyncio.run(main())
