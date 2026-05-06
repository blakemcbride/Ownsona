#!/usr/bin/env bash
#
# Install the daily ownsona backup: a script under /usr/local/sbin, plus
# a systemd .service + .timer that wakes it up at 03:00 every day.
#
# Run with:  sudo sql/install_backup.sh
# Idempotent --- safe to re-run.

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "This script must be run as root (try: sudo $0)" >&2
    exit 1
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "==> Installing /usr/local/sbin/ownsona-backup.sh"
install -m 0755 -o root -g root \
    "$REPO_DIR/sql/ownsona-backup.sh" \
    /usr/local/sbin/ownsona-backup.sh

echo "==> Installing /etc/systemd/system/ownsona-backup.{service,timer}"
install -m 0644 -o root -g root \
    "$REPO_DIR/sql/ownsona-backup.service" \
    /etc/systemd/system/ownsona-backup.service
install -m 0644 -o root -g root \
    "$REPO_DIR/sql/ownsona-backup.timer" \
    /etc/systemd/system/ownsona-backup.timer

# Pre-create the log file so the script's append works on first run with the
# expected ownership / mode.
touch /var/log/ownsona-backup.log
chmod 0640 /var/log/ownsona-backup.log
chown root:adm /var/log/ownsona-backup.log

systemctl daemon-reload

echo "==> Enabling and starting ownsona-backup.timer"
systemctl enable --now ownsona-backup.timer

echo
systemctl --no-pager status ownsona-backup.timer | head -10
echo
systemctl list-timers ownsona-backup.timer --no-pager

echo
echo "Done."
echo
echo "To do a one-shot test run now (instead of waiting for 03:00):"
echo "    sudo systemctl start ownsona-backup.service"
echo "Then watch:    tail -f /var/log/ownsona-backup.log"
echo "And verify:    ls -lh /mnt/backups/"
