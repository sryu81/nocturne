#!/usr/bin/env python3
"""Tiny companion daemon for Nocturne's rig-maintenance "Reboot Pi" feature.

Stdlib-only (no pip install needed on the Pi). Listens on its own port,
entirely separate from EkosRemote's websocket (9000) — this is the one piece
of Nocturne that can act on the Pi itself rather than through Ekos, which
matters exactly when Ekos is the thing that's hung.

Endpoints:
  GET  /health   -> 200 "ok"                          (no auth; harmless)
  GET  /hips/status -> 200 {"status":"ok","orders":{"0":12,...},"complete":{"0":true,...}}
                    tiles present per order in the offline DSS2 colour HiPS
                    mirror (pi-tools/hips-mirror), so the app knows what it can copy
  GET  /hips/<path> -> one file of that mirror: properties, Norder3/Allsky.jpg or
                    Norder<k>/Dir<d>/Npix<n>.jpg (nothing else is served)
  POST /reboot   -> 202 "rebooting" then `sudo reboot`
  POST /set-time -> 200 "set" then `sudo date -s ...`
                    body: {"iso": "YYYY-MM-DDTHH:MM:SS"} — a LOCAL wall-clock
                    time (no timezone suffix), same shape `date -s` itself
                    expects: interpreted in whatever timezone the Pi's own
                    `/etc/localtime` is set to, matching the app's own real
                    intent (phone and rig are co-located, same local time),
                    not a UTC instant. Also runs `hwclock -w` best-effort
                    afterward (silently skipped if no RTC hardware) so the
                    corrected time survives a power loss, not just this boot.

No auth — same trust-the-LAN model as the EkosRemote wire on 9000, which
already lets any LAN client slew the mount, stop Ekos or set KStars' clock.
A shared-secret header here (removed 2026-10) guarded nothing that port
didn't already leave open, and it travelled in plain HTTP anyway; the app's
typed "REBOOT" confirm is the real guard against an accidental tap. Real
auth, if ever needed, belongs on both channels together (docs/FORK-BACKLOG.md).
Name/service file predate `/set-time` (this daemon started as reboot-only).
"""
import http.server
import json
import os
import re
import subprocess
import threading
import time

PORT = int(os.environ.get("NOCTURNE_REBOOT_PORT", "9001"))
# on the SD card, next to KStars' data: the USB stick is not always attached
HIPS_DIR = os.environ.get("NOCTURNE_HIPS_DIR", os.path.expanduser("~/.local/share/kstars/hips/DSSColor"))
HIPS_PATH = re.compile(r"^(properties|Norder3/Allsky\.jpg|Norder\d{1,2}/Dir\d+/Npix\d+\.jpg)$")

_hips_status = {"at": 0.0, "body": None}
_hips_lock = threading.Lock()


def hips_status():
    """Tile counts per order, rescanned at most once a minute (65k files on a USB stick)."""
    with _hips_lock:
        if _hips_status["body"] is None or time.monotonic() - _hips_status["at"] > 60:
            orders = {}
            try:
                for entry in os.scandir(HIPS_DIR):
                    m = re.fullmatch(r"Norder(\d+)", entry.name)
                    if not (m and entry.is_dir()):
                        continue
                    count = 0
                    for d in os.scandir(entry.path):
                        if d.is_dir():
                            count += sum(1 for f in os.scandir(d.path)
                                         if f.name.endswith(".jpg") and f.name.startswith("Npix"))
                    orders[m.group(1)] = count
            except OSError:
                pass
            complete = {k: v >= 12 * 4 ** int(k) for k, v in orders.items()}
            _hips_status["body"] = {"status": "ok", "orders": orders, "complete": complete}
            _hips_status["at"] = time.monotonic()
        return _hips_status["body"]


class Handler(http.server.BaseHTTPRequestHandler):
    # keep-alive, so the app's sky-atlas copy (16k tiles) reuses connections
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # one line per tile would flood the journal during an atlas copy
        if self.path.startswith("/hips/") and args and str(args[1]) == "200":
            return
        print(f"[nocturne-reboot] {self.address_string()} {fmt % args}")

    def do_GET(self):
        if self.path == "/health":
            self._respond(200, "ok")
        elif self.path == "/hips/status":
            self._send(200, "application/json", json.dumps(hips_status()).encode())
        elif self.path.startswith("/hips/"):
            rel = self.path[len("/hips/"):]
            if not HIPS_PATH.match(rel):
                self._respond(404, "not found")
                return
            try:
                with open(os.path.join(HIPS_DIR, rel), "rb") as f:
                    data = f.read()
            except OSError:
                self._respond(404, "not found")
                return
            ctype = "image/jpeg" if rel.endswith(".jpg") else "text/plain"
            self._send(200, ctype, data)
        else:
            self._respond(404, "not found")

    def do_POST(self):
        # always drain the body: with keep-alive an unread body would be parsed as the next request
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length > 0 else b""
        if self.path not in ("/reboot", "/set-time"):
            self._respond(404, "not found")
            return
        if self.path == "/reboot":
            self._respond(202, "rebooting")
            # Respond first — `sudo reboot` tears down networking almost
            # immediately, and the app should see this 202, not a dropped
            # connection that looks like a failure.
            threading.Timer(0.3, lambda: subprocess.run(["sudo", "reboot"])).start()
            return
        # /set-time
        try:
            body = json.loads(raw or b"{}")
            iso = str(body["iso"])
        except (json.JSONDecodeError, KeyError, ValueError):
            self._respond(400, "bad body — expected {\"iso\": \"YYYY-MM-DDTHH:MM:SS\"}")
            return
        result = subprocess.run(["sudo", "date", "-s", iso], capture_output=True, text=True)
        if result.returncode != 0:
            self._respond(500, f"date -s failed: {result.stderr.strip()}")
            return
        # Best-effort — most Pis have no RTC at all (that's the whole reason this
        # endpoint exists: the clock is wrong again after every power loss), so a
        # missing/failing hwclock is the expected common case, not an error.
        subprocess.run(["sudo", "hwclock", "-w"], capture_output=True)
        self._respond(200, "set")

    def _respond(self, code: int, body: str):
        self._send(code, "application/json", json.dumps({"status": body}).encode())

    def _send(self, code: int, ctype: str, payload: bytes):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[nocturne-reboot] listening on 0.0.0.0:{PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
