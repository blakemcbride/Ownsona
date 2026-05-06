#!/usr/bin/env bash
#
# /usr/local/sbin/ownsona-backup.sh
#
# Triggered by ownsona-backup.timer (systemd; daily at 03:00 local time).
# Run as root.
#
# Produces two compressed files in /mnt/backups (an s3fs mount):
#
#   files-YYYY-MM-DD.tar.gz   --- /home, /root, /etc, /usr/local, /opt,
#                                 /var/spool/cron, plus a /var/lib/ownsona-backup
#                                 metadata bundle (package list, crontabs,
#                                 enabled systemd units, OS info).
#   database-YYYY-MM-DD.gz    --- pg_dumpall: every PostgreSQL database PLUS
#                                 global roles/passwords/grants/tablespaces.
#                                 Per-DB pg_dump would not capture the
#                                 'ownsona' role and would leave a restore
#                                 broken.
#
# Retention:
#   - Backups <= 30 days old are kept.
#   - Backups dated on the last day of any month are kept forever.
#   - Everything else is deleted.
#
# Logs to /var/log/ownsona-backup.log.

set -euo pipefail

DATE=$(date +%F)                         # YYYY-MM-DD
BACKUP_DIR=/mnt/backups
S3FS_MOUNT_HELPER=/etc/mount-s3fs        # provided by the user; mounts /mnt/backups
LOG=/var/log/ownsona-backup.log
LOCK=/var/lock/ownsona-backup.lock
META=/var/lib/ownsona-backup             # bundled into the file tarball

# Single-instance lock --- if the previous run is still going, just exit.
exec 9>"$LOCK"
if ! flock -n 9; then
    echo "$(date -Iseconds) another ownsona-backup is already running; exiting" >&2
    exit 0
fi

# All output (stdout + stderr) goes to the log file from here on.
exec >>"$LOG" 2>&1
echo
echo "==== $(date -Iseconds) ownsona-backup start (DATE=$DATE) ===="

# 1. Make sure the s3fs target is mounted.
if ! mountpoint -q "$BACKUP_DIR"; then
    echo "Mounting $BACKUP_DIR via $S3FS_MOUNT_HELPER"
    if [[ ! -x "$S3FS_MOUNT_HELPER" ]]; then
        echo "ERROR: $S3FS_MOUNT_HELPER missing or not executable" >&2
        exit 2
    fi
    "$S3FS_MOUNT_HELPER"
    sleep 2
    if ! mountpoint -q "$BACKUP_DIR"; then
        echo "ERROR: $BACKUP_DIR still not a mount point after $S3FS_MOUNT_HELPER" >&2
        exit 2
    fi
fi

FILES_TGT="$BACKUP_DIR/files-$DATE.tar.gz"
DB_TGT="$BACKUP_DIR/database-$DATE.gz"

# 2. Refresh metadata that ends up in /var/lib/ownsona-backup/ inside the file
#    tarball.  This captures things tar can't pull from the live filesystem.
mkdir -p "$META"
chmod 0700 "$META"
{
    echo "==== Ownsona system snapshot ===="
    echo "host:    $(hostname)"
    echo "date:    $(date -Iseconds)"
    echo "uname:   $(uname -a)"
    if command -v lsb_release >/dev/null 2>&1; then
        echo "release: $(lsb_release -d 2>/dev/null | cut -f2-)"
    fi
} > "$META/system.txt"
dpkg --get-selections                                > "$META/dpkg-selections.txt"     2>/dev/null || true
apt-mark showmanual                                  > "$META/apt-mark-manual.txt"     2>/dev/null || true
crontab -u root -l                                   > "$META/crontab-root.txt"        2>/dev/null || true
crontab -u ownsona -l                                > "$META/crontab-ownsona.txt"     2>/dev/null || true
systemctl list-unit-files --state=enabled --no-pager > "$META/systemd-enabled-units.txt" 2>/dev/null || true

# 3. Filesystem tarball.  --warning suppression keeps tar quiet about files
#    that change/disappear between fstat and read (e.g. log lines being
#    appended); they don't fail the backup.
echo "Creating $FILES_TGT"
tar --create --gzip \
    --warning=no-file-changed \
    --warning=no-file-removed \
    --warning=no-file-ignored \
    --exclude='*/.cache' \
    --exclude='*/snap' \
    --exclude='*/.local/share/Trash' \
    --exclude='/home/ownsona/ownsona/work' \
    --exclude='/home/ownsona/ownsona/tomcat' \
    --exclude='/home/ownsona/tomcat/work' \
    --exclude='/home/ownsona/tomcat/temp' \
    --exclude='*.swp' \
    --file="$FILES_TGT" \
    /home /root /etc /usr/local /opt \
    /var/spool/cron \
    "$META"

# 4. PostgreSQL: pg_dumpall captures all databases AND global state
#    (roles + passwords + grants + tablespaces).  Run as the postgres OS
#    user so peer authentication accepts the connection.
echo "Creating $DB_TGT (pg_dumpall)"
sudo -u postgres pg_dumpall --clean --if-exists | gzip -9 > "$DB_TGT"

# 5. Retention pruning.  Keep <= 30 days, plus last-day-of-month forever.
prune() {
    local pattern=$1
    local cutoff
    cutoff=$(date -d '30 days ago' +%s)
    shopt -s nullglob
    local file fdate fts thism nextm
    for file in "$BACKUP_DIR"/$pattern; do
        fdate=$(basename "$file" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
        [[ -z "$fdate" ]] && continue
        if ! fts=$(date -d "$fdate" +%s 2>/dev/null); then
            continue
        fi
        if (( fts >= cutoff )); then
            continue                     # within 30 days --- keep
        fi
        thism=$(date -d "$fdate" +%m)
        nextm=$(date -d "$fdate + 1 day" +%m)
        if [[ "$thism" != "$nextm" ]]; then
            continue                     # last day of month --- keep forever
        fi
        echo "pruning $file"
        rm -f "$file"
    done
    shopt -u nullglob
}
prune 'files-*.tar.gz'
prune 'database-*.gz'

# 6. Summary so the log shows you sizes at a glance.
ls -lh "$FILES_TGT" "$DB_TGT" 2>/dev/null || true

echo "==== $(date -Iseconds) ownsona-backup done ===="
