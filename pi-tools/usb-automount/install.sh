#!/usr/bin/env bash
# Installs the USB automount rule and script on the Pi. Idempotent — safe to re-run.
#
# Usage: sudo ./install.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Run with sudo: sudo ./install.sh" >&2
    exit 1
fi

here=$(dirname "$0")
install -m 755 "$here/nocturne-usb-mount" /usr/local/bin/nocturne-usb-mount
install -m 644 "$here/99-nocturne-usb.rules" /etc/udev/rules.d/99-nocturne-usb.rules
mkdir -p /media
udevadm control --reload

echo "== Done. A stick plugged in now mounts at /media/<label>; the old one stays at /mnt/astro. =="
echo "== Log: journalctl -t nocturne-usb =="
