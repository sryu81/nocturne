# Rig-reboot companion daemon

Nocturne's "Reboot Pi" button (Gear → Rig maintenance) needs a way to power-
cycle the Pi itself — something the EkosRemote protocol (port 9000) has no
command for, and couldn't rely on anyway, since a hung/crashed Ekos process
is exactly the case a reboot needs to recover from.

This is a ~70-line stdlib-only Python daemon that listens on its own port
(9001 by default) for a single authenticated `POST /reboot`.

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
2. Generates a random token at `/etc/nocturne-reboot/token` (unless one
   already exists) and prints it once — **copy it into Nocturne's Rig
   maintenance sheet** (Token field) along with the port (9001 default).
3. Installs the daemon to `/opt/nocturne-reboot/`, a systemd unit
   (`nocturne-reboot.service`), and starts it on boot.

Re-running `install.sh` is safe (idempotent) — use `sudo ./install.sh
--rotate-token` to generate a fresh token if you ever need to revoke app
access.

## Security note

Same trust model as Nocturne's own EkosRemote connection (see its Connect
screen warning): **no encryption, LAN-only**. The shared-secret token stops
anything that merely *guesses* your Pi's IP from rebooting it, but anything
that can already sniff or reach your local network can. Don't expose this
port past your LAN (no port-forwarding it to the internet).

## Uninstall

```
sudo systemctl disable --now nocturne-reboot
sudo rm -rf /opt/nocturne-reboot /etc/nocturne-reboot /etc/systemd/system/nocturne-reboot.service
sudo systemctl daemon-reload
```
