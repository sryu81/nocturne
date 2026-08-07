#!/usr/bin/env python3
"""Tiny companion daemon for Nocturne's rig-maintenance "Reboot Pi" feature.

Stdlib-only (no pip install needed on the Pi). Listens on its own port,
entirely separate from EkosRemote's websocket (9000) — this is the one piece
of Nocturne that can act on the Pi itself rather than through Ekos, which
matters exactly when Ekos is the thing that's hung.

Endpoints:
  GET  /health  -> 200 "ok"                         (no auth; harmless)
  POST /reboot  -> 202 "rebooting" then `sudo reboot` (requires X-Reboot-Token)

Auth is a single shared-secret header, not because the LAN is assumed
hostile (Nocturne's own EkosRemote wire has none either — see its
ConnectScreen warning), but because this endpoint's blast radius is an OS
reboot, not a telescope setting, and the check costs nothing.
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
        if self.path != "/reboot":
            self._respond(404, "not found")
            return
        if self.headers.get("X-Reboot-Token", "") != self.token:
            self._respond(403, "bad token")
            return
        self._respond(202, "rebooting")
        # Respond first — `sudo reboot` tears down networking almost
        # immediately, and the app should see this 202, not a dropped
        # connection that looks like a failure.
        threading.Timer(0.3, lambda: subprocess.run(["sudo", "reboot"])).start()

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
