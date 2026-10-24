#!/usr/bin/env bash
# Installs Nocturne's rig-reboot companion daemon on the Pi. Idempotent —
# safe to re-run.
#
# Usage: sudo ./install.sh
set -euo pipefail

INSTALL_DIR="/opt/nocturne-reboot"
SERVICE_PATH="/etc/systemd/system/nocturne-reboot.service"
RUN_USER="${SUDO_USER:-$(whoami)}"

if [[ $EUID -ne 0 ]]; then
    echo "Run with sudo: sudo ./install.sh" >&2
    exit 1
fi

echo "== Checking passwordless sudo for 'reboot'/'date'/'hwclock' as $RUN_USER =="
if ! sudo -n -u "$RUN_USER" sudo -n true 2>/dev/null; then
    echo "warning: $RUN_USER doesn't have passwordless sudo yet." >&2
    echo "Add a sudoers rule, e.g.:" >&2
    echo "  echo '$RUN_USER ALL=(ALL) NOPASSWD: /sbin/reboot, /usr/sbin/reboot, /bin/date, /usr/bin/date, /sbin/hwclock, /usr/sbin/hwclock' | sudo tee /etc/sudoers.d/nocturne-reboot" >&2
    echo "then re-run this script." >&2
    exit 1
fi

mkdir -p "$INSTALL_DIR"
# Remove the token file left by old (token-auth) installs.
rm -rf /etc/nocturne-reboot

cp "$(dirname "$0")/reboot_daemon.py" "$INSTALL_DIR/reboot_daemon.py"
chmod 755 "$INSTALL_DIR/reboot_daemon.py"

sed "s/__NOCTURNE_REBOOT_USER__/$RUN_USER/" "$(dirname "$0")/nocturne-reboot.service" > "$SERVICE_PATH"

systemctl daemon-reload
systemctl enable nocturne-reboot.service
# restart, not `enable --now`: that leaves an already-running old daemon in place on upgrade.
systemctl restart nocturne-reboot.service

echo "== Done. Daemon listening on port 9001, running as $RUN_USER. =="
echo "== Check status: systemctl status nocturne-reboot =="
