#!/bin/sh
# Phase 09D D12 — restore onto a FRESH stack (the reverse of backup.sh).
#   restore.sh [dump-name]     default: the newest db/daily dump in Object Storage
# Starts Postgres/Redis/MinIO only, restores the DB and the buckets, then brings the whole stack up
# (Flyway sees the restored schema history and applies only newer migrations).
# Run this on a new server after setup-server.sh + generate-env.sh (see docs/ops/runbook.md).
set -eu
. "$(dirname "$0")/lib.sh"
load_env

: "${OCI_S3_ENDPOINT:?set OCI_S3_ENDPOINT in .env}"
: "${OCI_S3_BUCKET:?set OCI_S3_BUCKET in .env}"
: "${OCI_S3_ACCESS_KEY:?set OCI_S3_ACCESS_KEY in .env}"
: "${OCI_S3_SECRET_KEY:?set OCI_S3_SECRET_KEY in .env}"

BACKUP_DIR=${BACKUP_DIR:-/var/backups/secureleaf}
BACKUP_BUCKETS=${BACKUP_BUCKETS:-secureleaf-raw secureleaf-tiles secureleaf-thumbnails}
DUMP_NAME=${1:-}
export BACKUP_DIR BACKUP_BUCKETS DUMP_NAME
mkdir -p "$BACKUP_DIR"
umask 077

log "Starting data services only (postgres, redis, minio)"
compose up -d --no-build postgres redis minio minio-init
# minio-init exits 0 once buckets exist; wait for postgres + minio health via depends_on above.
waited=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$(compose ps -q postgres)" 2>/dev/null)" = "healthy" ]; do
    sleep 3; waited=$((waited + 3)); [ "$waited" -lt 180 ] || die "postgres did not become healthy"
done

log "Fetching the dump from Object Storage and restoring buckets"
{
    printf '%s\n' "$MC_ALIASES"
    cat <<'MCSCRIPT'
if [ -z "$DUMP_NAME" ]; then
    DUMP_NAME=$(mc ls "dest/$OCI_S3_BUCKET/db/daily/" | awk '{print $NF}' | sort | tail -n 1)
fi
[ -n "$DUMP_NAME" ] || { echo "no dump found in dest/$OCI_S3_BUCKET/db/daily/" >&2; exit 1; }
mc cp "dest/$OCI_S3_BUCKET/db/daily/$DUMP_NAME" "/backup/restore.dump"
echo "$DUMP_NAME" > /backup/restore.dump.name
for b in $BACKUP_BUCKETS; do
    mc mb --ignore-existing "local/$b" >/dev/null
    mc mirror --overwrite --quiet "dest/$OCI_S3_BUCKET/mirror/$b" "local/$b"
done
MCSCRIPT
} | run_mc

log "Restoring database from $(cat "$BACKUP_DIR/restore.dump.name")"
# --clean --if-exists drops objects first, so this also works over a half-initialised database.
compose exec -T postgres pg_restore --clean --if-exists --no-owner --no-privileges \
    -U secureleaf_user -d secureleaf < "$BACKUP_DIR/restore.dump"
rm -f "$BACKUP_DIR/restore.dump" "$BACKUP_DIR/restore.dump.name"

log "Bringing the whole stack up"
compose up -d --no-build
log "Restore complete. Check https://${SITE_HOST}/actuator/health and log in."
