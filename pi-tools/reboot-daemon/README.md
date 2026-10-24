# Maintenance daemon

A small stdlib-only Python daemon on the Pi (port 9001) for things the EkosRemote protocol can't
do, or can't do when KStars is hung:

| Endpoint | Does |
|---|---|
| `GET /health` | `{"status": "ok"}` |
| `POST /reboot` | Reboots the Pi (replies 202 first) |
| `POST /set-time` | `{"iso": "YYYY-MM-DDTHH:MM:SS"}` local time → `sudo date -s` (+ `hwclock -w` if an RTC exists). The Pi has no RTC, so its clock is wrong after every power loss |
| `GET /hips/status` | Sky-atlas tiles present per order |
| `GET /hips/<path>` | One sky-atlas tile (`Norder<k>/Dir<d>/Npix<n>.jpg`, `properties`, `Norder3/Allsky.jpg`); nothing else is served |

The sky atlas is the offline DSS2 colour HiPS copy at `~/.local/share/kstars/hips/DSSColor`
(override with `NOCTURNE_HIPS_DIR`), the same folder KStars' offline HiPS reads; the app copies
missing tiles from it (Gear → Rig maintenance → Sky atlas).

## Install

```
scp -r pi-tools/reboot-daemon <user>@<rig-ip>:~/reboot-daemon
ssh <user>@<rig-ip>
cd reboot-daemon && sudo ./install.sh
```

Installs to `/opt/nocturne-reboot/` with the systemd unit `nocturne-reboot.service` (starts on
boot). Needs passwordless `sudo reboot`/`date`/`hwclock`; the script prints the sudoers line if
missing. Re-running is safe.

## Security

No auth, no encryption, LAN only — the same trust model as port 9000, which already lets any LAN
client drive the rig. Never forward this port to the internet.

## Uninstall

```
sudo systemctl disable --now nocturne-reboot
sudo rm -rf /opt/nocturne-reboot /etc/systemd/system/nocturne-reboot.service
sudo systemctl daemon-reload
```
