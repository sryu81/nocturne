#!/usr/bin/env bash
# Installs Nocturne's pier guard on the Pi. Idempotent — safe to re-run.
#
# Usage: sudo ./install.sh
set -euo pipefail

INSTALL_DIR="/opt/nocturne-pier-guard"
SERVICE_PATH="/etc/systemd/system/nocturne-pier-guard.service"
RUN_USER="${SUDO_USER:-$(whoami)}"

if [[ $EUID -ne 0 ]]; then
    echo "Run with sudo: sudo ./install.sh" >&2
    exit 1
fi

mkdir -p "$INSTALL_DIR"
cp "$(dirname "$0")/pier_guard.py" "$INSTALL_DIR/pier_guard.py"
chmod 755 "$INSTALL_DIR/pier_guard.py"

sed "s/__NOCTURNE_PIER_GUARD_USER__/$RUN_USER/" "$(dirname "$0")/nocturne-pier-guard.service" > "$SERVICE_PATH"

systemctl daemon-reload
systemctl enable nocturne-pier-guard.service
systemctl restart nocturne-pier-guard.service

echo "== Done. Pier guard running as $RUN_USER. =="
echo "== Log: journalctl -u nocturne-pier-guard -f =="
