# Rig-maintenance companion daemon

Nocturne's "Reboot Pi" button (Gear → Rig maintenance) needs a way to power-
cycle the Pi itself — something the EkosRemote protocol (port 9000) has no
command for, and couldn't rely on anyway, since a hung/crashed Ekos process
is exactly the case a reboot needs to recover from. Same reasoning applies
to setting the Pi's real OS clock (2026-09 addition, `POST /set-time`) — a
wrong system clock on a Pi with no RTC battery is an OS-level fact, nothing
Ekos/INDI exposes any control over.

This is a small stdlib-only Python daemon that listens on its own port
(9001 by default) for `POST /reboot` and `POST /set-time`. No token or
pairing — the app finds it on the same host it already connects to.

## Sky atlas tiles

`GET /hips/status` (tiles per order) and `GET /hips/<Norder/Dir/Npix path>` serve the offline
DSS2 colour HiPS copy at `~/.local/share/kstars/hips/DSSColor` (override `NOCTURNE_HIPS_DIR`),
the same folder KStars' offline HiPS reads. The app copies missing tiles from here (Gear →
Rig maintenance → Sky atlas); only HiPS tile paths are served, nothing else on the Pi.

## Install

```
scp -r pi-tools/reboot-daemon pi@<rig-ip>:~/reboot-daemon
ssh pi@<rig-ip>
cd reboot-daemon
sudo ./install.sh
```

The script:
1. Checks the current user has passwordless `sudo reboot` (prints the
   sudoers line to add if not, then exits — re-run after adding it).
2. Installs the daemon to `/opt/nocturne-reboot/`, a systemd unit
   (`nocturne-reboot.service`), and starts it on boot.

Re-running `install.sh` is safe (idempotent). It also removes the
`/etc/nocturne-reboot/token` file left by older, token-based installs.

## Security note

Same trust model as Nocturne's own EkosRemote connection (see its Connect
screen warning): **no auth, no encryption, LAN-only** — anything on your
LAN can reboot the Pi or set its clock, just as it can already drive the
rig over port 9000. An earlier shared-secret token was dropped: it added
setup friction while guarding nothing 9000 didn't leave open. Don't expose
this port past your LAN (no port-forwarding it to the internet).

## Uninstall

```
sudo systemctl disable --now nocturne-reboot
sudo rm -rf /opt/nocturne-reboot /etc/systemd/system/nocturne-reboot.service
sudo systemctl daemon-reload
```
