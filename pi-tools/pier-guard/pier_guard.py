#!/usr/bin/env python3
"""Nocturne pier guard: keep OnStep's Preferred Pier Side on East, and log
what the mount does.

OnStep keeps Preferred Pier Side in RAM only (`:SX96` is never written to
NV; the power-on value is the compile-time PIER_SIDE_PREFERRED_DEFAULT,
stock BEST), so every mount power cycle brings back Best, and with Best
Ekos's meridian-flip re-slew is a no-op. This daemon is a plain INDI client
of the local indiserver: whenever any device reports a "Preferred Pier
Side" property that isn't East, it switches it to East.

East is right in both hemispheres: OnStep picks the side from hour angle
(Goto.ino), and East means "post-flip side for targets west of the meridian".

It also keeps a mount event log (pier side, slew/track state, preferred
side, AutoFlip, OnStep errors, driver messages) in a rotating file, warns
when a slew stays Busy too long. The driver's messages are the same
errors/warnings/info its own INDI log file would hold at default levels;
that file needs the DEBUG switch, which Ekos turns back off unless its INDI
logging settings include the mount. The 2026-10-23 M31 flip failure left
no trace because none of this survived the night.

Stdlib only. Reconnects whenever indiserver goes away (Ekos stopped).
"""
import logging
import logging.handlers
import os
import socket
import time
import xml.etree.ElementTree as ET

HOST, PORT = "localhost", 7624
PROP = "Preferred Pier Side"
EAST = "2"  # lx200_OnStep.cpp: "1" West, "2" East, "3" Best
MIN_RESEND_S = 10.0  # don't hammer a mount that keeps refusing (e.g. while slewing)
SLEW_STUCK_S = 300.0  # a flip takes ~80 s; Busy this long means something hung
LOG_FILE = os.environ.get("PIER_GUARD_LOG",
                          os.path.expanduser("~/nocturne-logs/mount-events.log"))

# Properties worth a line in the event log whenever their value changes.
WATCHED = {"TELESCOPE_PIER_SIDE", "TELESCOPE_TRACK_STATE", "TELESCOPE_PARK",
           PROP, "AutoFlip", "OnStep Status"}
VECTOR_TAGS = ("defSwitchVector", "setSwitchVector", "defNumberVector",
               "setNumberVector", "defTextVector", "setTextVector")

log = logging.getLogger("pier-guard")


def fmt(v):
    try:
        return f"{float(v):.4f}"
    except (TypeError, ValueError):
        return "?"


class Guard:
    def __init__(self, sock):
        self.sock = sock
        self.last_sent = {}   # device -> monotonic time of last East request
        self.seen = {}        # (device, prop) -> last logged value
        self.mounts = set()   # devices with EQUATORIAL_EOD_COORD
        self.busy_since = {}  # device -> monotonic start of a Busy slew
        self.stuck_warned = set()

    def send(self, tag, device, name, elem, members):
        body = "".join(f'<{elem} name="{k}">{v}</{elem}>' for k, v in members.items())
        self.sock.sendall(f'<{tag} device="{device}" name="{name}">{body}</{tag}>\n'.encode())

    def handle(self, el):
        device, name = el.get("device"), el.get("name")
        if not device or not name:
            return
        values = {c.get("name"): (c.text or "").strip() for c in el}
        state = el.get("state")

        if name == "EQUATORIAL_EOD_COORD":
            self.on_coords(device, state, values)
        elif name in WATCHED and values:
            self.log_change(device, name, self.describe(name, values))
        if name == PROP and values:
            self.guard_pier_side(device, state, values)

    @staticmethod
    def describe(name, values):
        if name == "OnStep Status":
            return values.get("Error", "")
        on = [k for k, v in values.items() if v == "On"]
        if on:
            labels = {PROP: {"1": "West", "2": "East", "3": "Best"},
                      "AutoFlip": {"1": "OFF", "2": "ON"}}.get(name, {})
            return ",".join(labels.get(k, k) for k in on)
        return " ".join(f"{k}={v}" for k, v in values.items())

    def log_change(self, device, name, value):
        key = (device, name)
        if key in self.seen and self.seen[key] == value:
            return
        before = self.seen.get(key)
        self.seen[key] = value
        if before is None:
            log.info("%s: %s = %s", device, name, value or "-")
        else:
            log.info("%s: %s %s -> %s", device, name, before or "-", value or "-")

    def on_coords(self, device, state, values):
        self.mounts.add(device)
        if state is None:
            return
        key = (device, "EQUATORIAL_EOD_COORD")
        if self.seen.get(key) != state:
            log.info("%s: coords %s -> %s  RA=%sh DEC=%s°", device, self.seen.get(key, "-"),
                     state, fmt(values.get("RA")), fmt(values.get("DEC")))
            self.seen[key] = state
        if state == "Busy":
            self.busy_since.setdefault(device, time.monotonic())
        else:
            if device in self.stuck_warned:
                log.warning("%s: slew finally ended (%s) after %.0f s", device, state,
                            time.monotonic() - self.busy_since[device])
            self.busy_since.pop(device, None)
            self.stuck_warned.discard(device)

    def check_stuck(self):
        now = time.monotonic()
        for device, start in self.busy_since.items():
            if now - start > SLEW_STUCK_S and device not in self.stuck_warned:
                log.warning("%s: slew Busy for %.0f s — mount or driver link likely hung",
                            device, now - start)
                self.stuck_warned.add(device)

    def guard_pier_side(self, device, state, values):
        if values.get(EAST) == "On":
            if device in self.last_sent:
                log.info("%s: Preferred Pier Side is East", device)
                self.last_sent.pop(device)
            return
        if state == "Busy":
            return
        now = time.monotonic()
        if now - self.last_sent.get(device, -MIN_RESEND_S) < MIN_RESEND_S:
            return
        current = next((n for n, s in values.items() if s == "On"), "none")
        log.warning("%s: Preferred Pier Side is %s, setting East", device,
                    {"1": "West", "3": "Best"}.get(current, current))
        self.send("newSwitchVector", device, PROP, "oneSwitch", {EAST: "On"})
        self.last_sent[device] = now


def run_once():
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        sock.settimeout(10)  # wake up regularly for the stuck-slew check
        sock.sendall(b'<getProperties version="1.7"/>\n<enableBLOB>Never</enableBLOB>\n')
        log.info("connected to indiserver %s:%d", HOST, PORT)
        guard = Guard(sock)
        parser = ET.XMLPullParser(events=("end",))
        parser.feed("<stream>")
        while True:
            try:
                chunk = sock.recv(65536)
            except socket.timeout:
                guard.check_stuck()
                continue
            if not chunk:
                raise ConnectionError("indiserver closed the connection")
            parser.feed(chunk.decode("utf-8", errors="replace"))
            for _, el in parser.read_events():
                if el.tag in VECTOR_TAGS:
                    guard.handle(el)
                    el.clear()
                elif el.tag == "message":
                    if el.get("device") in guard.mounts and el.get("message"):
                        log.info("%s says: %s", el.get("device"), el.get("message"))
                    el.clear()
                elif el.tag.endswith("Vector") or el.tag == "delProperty":
                    el.clear()
            guard.check_stuck()


def setup_logging():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
        fh = logging.handlers.RotatingFileHandler(LOG_FILE, maxBytes=5_000_000, backupCount=10)
    except OSError as e:
        log.warning("no event log file (%s): %s", LOG_FILE, e)
        return
    fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logging.getLogger().addHandler(fh)


def main():
    setup_logging()
    while True:
        try:
            run_once()
        except (OSError, ConnectionError, ET.ParseError) as e:
            log.info("indiserver unavailable (%s); retrying in 5 s", e)
        time.sleep(5)


if __name__ == "__main__":
    main()
