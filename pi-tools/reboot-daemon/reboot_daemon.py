#!/usr/bin/env python3
"""Tiny companion daemon for Nocturne's rig-maintenance "Reboot Pi" feature.

Stdlib-only (no pip install needed on the Pi). Listens on its own port,
entirely separate from EkosRemote's websocket (9000) — this is the one piece
of Nocturne that can act on the Pi itself rather than through Ekos, which
matters exactly when Ekos is the thing that's hung.

Endpoints:
  GET  /health   -> 200 "ok"                          (no auth; harmless)
  POST /reboot   -> 202 "rebooting" then `sudo reboot` (requires X-Reboot-Token)
  POST /set-time -> 200 "set" then `sudo date -s ...`  (requires X-Reboot-Token)
                    body: {"iso": "YYYY-MM-DDTHH:MM:SS"} — a LOCAL wall-clock
                    time (no timezone suffix), same shape `date -s` itself
                    expects: interpreted in whatever timezone the Pi's own
                    `/etc/localtime` is set to, matching the app's own real
                    intent (phone and rig are co-located, same local time),
                    not a UTC instant. Also runs `hwclock -w` best-effort
                    afterward (silently skipped if no RTC hardware) so the
                    corrected time survives a power loss, not just this boot.

Auth is a single shared-secret header, not because the LAN is assumed
hostile (Nocturne's own EkosRemote wire has none either — see its
ConnectScreen warning), but because this daemon's blast radius is real OS
state (reboot, system clock), not a telescope setting, and the check costs
nothing. Name/token/service file predate `/set-time` (this daemon started as
reboot-only) — kept as-is rather than renamed for a 2nd endpoint; still not
installed on the real Pi as of this addition (see README), so renaming
would've been free, just not worth the churn for what's still one small
maintenance daemon.
"""
import http.server
import json
import os
import subprocess
import sys
import threading

TOKEN_PATH = "/etc/nocturne-reboot/token"
PORT = int(os.environ.get("NOCTURNE_REBOOT_PORT", "9001"))


def load_token() -> str:
    try:
        with open(TOKEN_PATH) as f:
            return f.read().strip()
    except FileNotFoundError:
        print(f"error: token file {TOKEN_PATH} not found — run install.sh first", file=sys.stderr)
        sys.exit(1)


class Handler(http.server.BaseHTTPRequestHandler):
    token = ""  # set in main()

    def log_message(self, fmt, *args):
        print(f"[nocturne-reboot] {self.address_string()} {fmt % args}")

    def do_GET(self):
        if self.path == "/health":
            self._respond(200, "ok")
        else:
            self._respond(404, "not found")

    def do_POST(self):
        if self.path not in ("/reboot", "/set-time"):
            self._respond(404, "not found")
            return
        if self.headers.get("X-Reboot-Token", "") != self.token:
            self._respond(403, "bad token")
            return
        if self.path == "/reboot":
            self._respond(202, "rebooting")
            # Respond first — `sudo reboot` tears down networking almost
            # immediately, and the app should see this 202, not a dropped
            # connection that looks like a failure.
            threading.Timer(0.3, lambda: subprocess.run(["sudo", "reboot"])).start()
            return
        # /set-time
        length = int(self.headers.get("Content-Length", "0"))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
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
        payload = json.dumps({"status": body}).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    Handler.token = load_token()
    server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[nocturne-reboot] listening on 0.0.0.0:{PORT}")
    server.serve_forever()


if __name__ == "__main__":
    main()
