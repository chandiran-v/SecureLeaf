#!/bin/sh
# Phase 09D D12 — nightly backup to Oracle Object Storage (S3-compatible). Run by the systemd
# timer that setup-server.sh installs (secureleaf-backup.timer).
#   * Postgres:  pg_dump -Fc (compressed custom format) -> dest/db/daily/, plus dest/db/weekly/ on Sundays
#   * MinIO:     `mc mirror` of each bucket -> dest/mirror/<bucket>  (incremental: only new/changed objects)
#   * Retention: 7 daily + 4 weekly dumps. The bucket mirror is kept current (`--remove`).
# Object-Storage request maths is in docs/deployment.md (it stays inside 20 GB / 50k requests).
set -eu
. "$(dirname "$0")/lib.sh"
load_env

: "${OCI_S3_ENDPOINT:?set OCI_S3_ENDPOINT in .env}"
: "${OCI_S3_BUCKET:?set OCI_S3_BUCKET in .env}"
: "${OCI_S3_ACCESS_KEY:?set OCI_S3_ACCESS_KEY in .env}"
: "${OCI_S3_SECRET_KEY:?set OCI_S3_SECRET_KEY in .env}"

BACKUP_DIR=${BACKUP_DIR:-/var/backups/secureleaf}
BACKUP_BUCKETS=${BACKUP_BUCKETS:-secureleaf-raw secureleaf-tiles secureleaf-thumbnails}
KEEP_DAILY=${KEEP_DAILY:-7}
KEEP_WEEKLY=${KEEP_WEEKLY:-4}
DAY=$(date -u +%Y-%m-%d)
WEEKDAY=$(date -u +%u)          # 7 = Sunday
DUMP_NAME="secureleaf-$DAY.dump"
export BACKUP_DIR BACKUP_BUCKETS KEEP_DAILY KEEP_WEEKLY DAY WEEKDAY DUMP_NAME

mkdir -p "$BACKUP_DIR"
umask 077

log "Dumping Postgres -> $BACKUP_DIR/$DUMP_NAME"
compose exec -T postgres pg_dump -Fc -U secureleaf_user -d secureleaf > "$BACKUP_DIR/$DUMP_NAME.tmp"
[ -s "$BACKUP_DIR/$DUMP_NAME.tmp" ] || die "pg_dump produced an empty file"
mv "$BACKUP_DIR/$DUMP_NAME.tmp" "$BACKUP_DIR/$DUMP_NAME"

log "Uploading dump, mirroring buckets, applying retention"
{
    printf '%s\n' "$MC_ALIASES"
    cat <<'MCSCRIPT'
mc cp "/backup/$DUMP_NAME" "dest/$OCI_S3_BUCKET/db/daily/$DUMP_NAME"
if [ "$WEEKDAY" = "7" ]; then
    mc cp "/backup/$DUMP_NAME" "dest/$OCI_S3_BUCKET/db/weekly/$DUMP_NAME"
fi
for b in $BACKUP_BUCKETS; do
    mc mb --ignore-existing "dest/$OCI_S3_BUCKET/mirror/$b" >/dev/null 2>&1 || true
    mc mirror --remove --quiet "local/$b" "dest/$OCI_S3_BUCKET/mirror/$b"
done
# Retention: names sort chronologically (ISO dates), so drop everything but the newest N.
prune() {  # $1 = prefix, $2 = keep
    mc ls "dest/$OCI_S3_BUCKET/$1/" | awk '{print $NF}' | sort -r | tail -n +$(($2 + 1)) |
    while read -r old; do
        [ -n "$old" ] && mc rm "dest/$OCI_S3_BUCKET/$1/$old"
    done
}
prune db/daily "$KEEP_DAILY"
prune db/weekly "$KEEP_WEEKLY"
MCSCRIPT
} | run_mc

# Local staging copy: keep only the latest dump on the server (the real copies are off-box).
# shellcheck disable=SC2012  # our own ISO-dated names, no odd characters
ls -1t "$BACKUP_DIR"/secureleaf-*.dump 2>/dev/null | tail -n +2 | while read -r f; do rm -f "$f"; done
log "Backup complete: $DUMP_NAME"
