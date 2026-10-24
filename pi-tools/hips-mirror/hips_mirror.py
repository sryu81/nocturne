#!/usr/bin/env python3
"""Mirror the DSS2 colour HiPS survey (CDS) down to a given order, for offline use.

Layout matches the survey itself (Norder<k>/Dir<d>/Npix<n>.jpg), which is what
KStars's offline HiPS source reads (Settings → HiPS → offline path) and what the
Nocturne app copies from the Pi for its Framing card. Tiles are FOV-independent:
any scope, rotation or mosaic is cropped from them.

Resumable: existing non-empty tiles are skipped, downloads go to .part first.
Polite: a few requests per second in total, identified User-Agent, backoff on
errors. Stdlib only.

Usage: hips_mirror.py DEST_DIR [MAX_ORDER] [WORKERS]

Run it on a machine with unmetered internet, not on the rig (its uplink can be a metered SIM),
then copy DEST_DIR to the Pi over the LAN. HIPS_SOURCE_ADDR=<local IPv4> pins every connection
to that interface, so a dropped primary network fails the download instead of rerouting it.
"""
import http.client
import os
import sys
import threading
import time

HOST = "alasky.u-strasbg.fr"
BASE = "/DSS/DSSColor"
UA = "Nocturne-hips-mirror/1.0 (personal offline copy for KStars/Ekos)"
REQ_INTERVAL_S = 0.6  # per worker; 3 workers ≈ 5 req/s
SOURCE_ADDR = os.environ.get("HIPS_SOURCE_ADDR")


def tile_path(order, npix):
    return f"Norder{order}/Dir{(npix // 10000) * 10000}/Npix{npix}.jpg"


def all_paths(max_order):
    yield "properties"
    yield "Moc.fits"
    yield "Norder3/Allsky.jpg"
    for order in range(max_order + 1):
        for npix in range(12 * 4 ** order):
            yield tile_path(order, npix)


class Worker(threading.Thread):
    def __init__(self, dest, queue, lock, stats):
        super().__init__(daemon=True)
        self.dest, self.queue, self.lock, self.stats = dest, queue, lock, stats
        self.conn = None

    def fetch(self, rel):
        for attempt in range(6):
            try:
                if self.conn is None:
                    self.conn = http.client.HTTPSConnection(
                        HOST, timeout=30, source_address=(SOURCE_ADDR, 0) if SOURCE_ADDR else None)
                self.conn.request("GET", f"{BASE}/{rel}", headers={"User-Agent": UA})
                resp = self.conn.getresponse()
                body = resp.read()
                if resp.status == 200:
                    return body
                if resp.status == 404:
                    return None
                raise OSError(f"HTTP {resp.status}")
            except (OSError, http.client.HTTPException) as e:
                if self.conn:
                    self.conn.close()
                self.conn = None
                wait = min(300, 5 * 2 ** attempt)
                print(f"{rel}: {e}, retry in {wait} s", flush=True)
                time.sleep(wait)
        raise SystemExit(f"giving up on {rel}")

    def run(self):
        while True:
            with self.lock:
                rel = next(self.queue, None)
            if rel is None:
                return
            out = os.path.join(self.dest, rel)
            if os.path.exists(out) and os.path.getsize(out) > 0:
                with self.lock:
                    self.stats["skipped"] += 1
                continue
            start = time.monotonic()
            body = self.fetch(rel)
            with self.lock:
                if body is None:
                    self.stats["missing"] += 1
                else:
                    self.stats["fetched"] += 1
                    self.stats["bytes"] += len(body)
            if body is not None:
                os.makedirs(os.path.dirname(out) or self.dest, exist_ok=True)
                with open(out + ".part", "wb") as f:
                    f.write(body)
                os.replace(out + ".part", out)
            time.sleep(max(0.0, REQ_INTERVAL_S - (time.monotonic() - start)))


def main():
    dest = sys.argv[1]
    max_order = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    workers = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    total = 3 + sum(12 * 4 ** o for o in range(max_order + 1))
    os.makedirs(dest, exist_ok=True)
    lock = threading.Lock()
    stats = {"fetched": 0, "skipped": 0, "missing": 0, "bytes": 0}
    queue = iter(all_paths(max_order))
    pool = [Worker(dest, queue, lock, stats) for _ in range(workers)]
    for w in pool:
        w.start()
    t0 = time.monotonic()
    while any(w.is_alive() for w in pool):
        time.sleep(60)
        with lock:
            done = stats["fetched"] + stats["skipped"] + stats["missing"]
            rate = stats["fetched"] / max(1.0, time.monotonic() - t0)
            eta = (total - done) / rate / 3600 if rate else float("nan")
            print(f"{time.strftime('%H:%M:%S')} {done}/{total} tiles, "
                  f"{stats['bytes'] / 1e6:.0f} MB new, {rate:.1f}/s, ETA {eta:.1f} h", flush=True)
    print(f"DONE {stats}", flush=True)


if __name__ == "__main__":
    main()
