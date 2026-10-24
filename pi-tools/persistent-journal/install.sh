#!/usr/bin/env bash
# Makes the Pi's systemd journal persistent. Idempotent — safe to re-run.
#
# Usage: sudo ./install.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "Run with sudo: sudo ./install.sh" >&2
    exit 1
fi

mkdir -p /etc/systemd/journald.conf.d
cp "$(dirname "$0")/90-nocturne-persistent.conf" /etc/systemd/journald.conf.d/
mkdir -p /var/log/journal
systemd-tmpfiles --create --prefix /var/log/journal
systemctl restart systemd-journald
journalctl --flush

echo "== Done. Journal is persistent (max 200 MB). =="
echo "== Previous boots: journalctl --list-boots; journalctl -b -1 =="
