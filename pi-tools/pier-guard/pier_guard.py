#!/usr/bin/env python3
"""Nocturne pier guard: keep OnStep's Preferred Pier Side on East.

OnStep keeps Preferred Pier Side in RAM only (`:SX96` is never written to
NV; the power-on value is the compile-time PIER_SIDE_PREFERRED_DEFAULT,
stock BEST), so every mount power cycle brings back Best, and with Best
Ekos's meridian-flip re-slew is a no-op. This daemon is a plain INDI client
of the local indiserver: whenever any device reports a "Preferred Pier
Side" property that isn't East, it switches it to East.

East is right in both hemispheres: OnStep picks the side from hour angle
(Goto.ino), and East means "post-flip side for targets west of the meridian".

Stdlib only. Reconnects whenever indiserver goes away (Ekos stopped).
"""
import logging
import socket
import time
import xml.etree.ElementTree as ET

HOST, PORT = "localhost", 7624
PROP = "Preferred Pier Side"
EAST = "2"  # lx200_OnStep.cpp: "1" West, "2" East, "3" Best
MIN_RESEND_S = 10.0  # don't hammer a mount that keeps refusing (e.g. while slewing)

log = logging.getLogger("pier-guard")


def handle(vector, sock, last_sent):
    device = vector.get("device")
    if vector.get("name") != PROP or not device:
        return
    states = {el.get("name"): (el.text or "").strip() for el in vector}
    if not states:
        return
    if states.get(EAST) == "On":
        if device in last_sent:
            log.info("%s: Preferred Pier Side is East", device)
            last_sent.pop(device)
        return
    if vector.get("state") == "Busy":
        return
    now = time.monotonic()
    if now - last_sent.get(device, -MIN_RESEND_S) < MIN_RESEND_S:
        return
    current = next((n for n, s in states.items() if s == "On"), "none")
    log.warning("%s: Preferred Pier Side is %s, setting East", device,
                {"1": "West", "3": "Best"}.get(current, current))
    sock.sendall(
        f'<newSwitchVector device="{device}" name="{PROP}">'
        f'<oneSwitch name="{EAST}">On</oneSwitch></newSwitchVector>\n'.encode()
    )
    last_sent[device] = now


def run_once():
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        sock.settimeout(None)
        sock.sendall(b'<getProperties version="1.7"/>\n<enableBLOB>Never</enableBLOB>\n')
        log.info("connected to indiserver %s:%d", HOST, PORT)
        parser = ET.XMLPullParser(events=("end",))
        parser.feed("<stream>")
        last_sent = {}
        depth_tags = ("defSwitchVector", "setSwitchVector")
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                raise ConnectionError("indiserver closed the connection")
            parser.feed(chunk.decode("utf-8", errors="replace"))
            for _, el in parser.read_events():
                if el.tag in depth_tags:
                    handle(el, sock, last_sent)
                    el.clear()
                elif el.tag.endswith("Vector") or el.tag in ("message", "delProperty"):
                    el.clear()


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    while True:
        try:
            run_once()
        except (OSError, ConnectionError, ET.ParseError) as e:
            log.info("indiserver unavailable (%s); retrying in 5 s", e)
        time.sleep(5)


if __name__ == "__main__":
    main()
