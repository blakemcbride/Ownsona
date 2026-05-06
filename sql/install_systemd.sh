#!/usr/bin/env bash
#
# One-time install: replace any manually-running Tomcat and stale
# tomcat.service with a proper ownsona.service that auto-starts on boot.
#
# Run with:  sudo sql/install_systemd.sh
#
# This script is idempotent --- safe to re-run.

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "This script must be run as root (try: sudo $0)" >&2
    exit 1
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT_SRC="$REPO_DIR/sql/ownsona.service"
UNIT_DST="/etc/systemd/system/ownsona.service"

if [[ ! -f "$UNIT_SRC" ]]; then
    echo "Unit file not found at $UNIT_SRC" >&2
    exit 1
fi

# 1) Stop any manually-launched Tomcat so we can hand control to systemd.
if pgrep -u ownsona -f 'org.apache.catalina.startup.Bootstrap' >/dev/null; then
    echo "==> Stopping the currently-running Tomcat (manual launch)"
    sudo -u ownsona /home/ownsona/tomcat/bin/shutdown.sh || true
    for i in $(seq 1 30); do
        if pgrep -u ownsona -f 'org.apache.catalina.startup.Bootstrap' >/dev/null; then
            sleep 1
        else
            echo "    stopped after ${i}s"
            break
        fi
    done
fi

# 2) Remove the stale tomcat.service that points at /home/stack360/tomcat/.
if systemctl list-unit-files tomcat.service --no-legend 2>/dev/null | grep -q tomcat; then
    echo "==> Disabling and removing stale /etc/systemd/system/tomcat.service"
    systemctl disable --now tomcat.service 2>/dev/null || true
    rm -f /etc/systemd/system/tomcat.service
    rm -f /etc/systemd/system/multi-user.target.wants/tomcat.service
fi

# 3) Install the new unit.
echo "==> Installing $UNIT_DST"
install -m 0644 "$UNIT_SRC" "$UNIT_DST"
systemctl daemon-reload

# 4) Enable + start.
echo "==> Enabling and starting ownsona.service"
systemctl enable ownsona.service
systemctl start  ownsona.service

# 5) Wait for HTTPS to come up and report status.
echo "==> Waiting for port 443"
for i in $(seq 1 60); do
    if ss -tln 2>/dev/null | awk '{print $4}' | grep -qE ':443$'; then
        echo "    listening after ${i}s"
        break
    fi
    sleep 1
done

systemctl --no-pager status ownsona.service | head -20

echo
echo "Auto-start verified by checking the enabled units:"
systemctl is-enabled ownsona.service
echo
echo "Done.  On next reboot ownsona.service will start automatically."
