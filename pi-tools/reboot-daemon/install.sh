#!/usr/bin/env bash
# Installs Nocturne's rig-reboot companion daemon on the Pi. Idempotent —
# safe to re-run (e.g. to rotate the token: pass --rotate-token).
#
# Usage: sudo ./install.sh [--rotate-token]
set -euo pipefail

TOKEN_DIR="/etc/nocturne-reboot"
TOKEN_PATH="$TOKEN_DIR/token"
INSTALL_DIR="/opt/nocturne-reboot"
SERVICE_PATH="/etc/systemd/system/nocturne-reboot.service"
RUN_USER="${SUDO_USER:-$(whoami)}"

if [[ $EUID -ne 0 ]]; then
    echo "Run with sudo: sudo ./install.sh" >&2
    exit 1
fi

echo "== Checking passwordless sudo for 'reboot' as $RUN_USER =="
if ! sudo -n -u "$RUN_USER" sudo -n true 2>/dev/null; then
    echo "warning: $RUN_USER doesn't have passwordless sudo yet." >&2
    echo "Add a sudoers rule, e.g.:" >&2
    echo "  echo '$RUN_USER ALL=(ALL) NOPASSWD: /sbin/reboot, /usr/sbin/reboot' | sudo tee /etc/sudoers.d/nocturne-reboot" >&2
    echo "then re-run this script." >&2
    exit 1
fi

mkdir -p "$TOKEN_DIR" "$INSTALL_DIR"

if [[ ! -f "$TOKEN_PATH" || "${1:-}" == "--rotate-token" ]]; then
    TOKEN="$(openssl rand -hex 16)"
    echo -n "$TOKEN" > "$TOKEN_PATH"
    chmod 600 "$TOKEN_PATH"
    echo "== Generated new token — paste this into Nocturne's Rig maintenance sheet: =="
    echo "$TOKEN"
else
    echo "== Token already exists at $TOKEN_PATH (use --rotate-token to replace it) =="
fi

cp "$(dirname "$0")/reboot_daemon.py" "$INSTALL_DIR/reboot_daemon.py"
chmod 755 "$INSTALL_DIR/reboot_daemon.py"

sed "s/__NOCTURNE_REBOOT_USER__/$RUN_USER/" "$(dirname "$0")/nocturne-reboot.service" > "$SERVICE_PATH"

systemctl daemon-reload
systemctl enable --now nocturne-reboot.service

echo "== Done. Daemon listening on port 9001, running as $RUN_USER. =="
echo "== Check status: systemctl status nocturne-reboot =="
