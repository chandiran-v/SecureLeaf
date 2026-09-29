#!/bin/sh
# Phase 09D acceptance #5 — backup.sh -> restore.sh round trip against the LOCAL prod stack, with a
# second MinIO container standing in for Oracle Object Storage (same S3 API).
#   1. record row counts + a checksum of every object in the buckets
#   2. backup.sh  (dump + mirror to the stand-in "Oracle" MinIO; retention pruning is checked too)
#   3. DESTROY the stack and its volumes  (`compose down -v`)
#   4. restore.sh onto the fresh stack
#   5. rows and objects must match, and the backend must come up healthy
# Requires a running local prod stack (see stack-test.sh header) that already contains some data
# (run stack-test.sh first, or upload something).
# shellcheck disable=SC2016  # snippets are expanded inside the mc container, not here
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
PROD_DIR=$(cd "$HERE/.." && pwd)
SRC_ENV=${ENV_FILE:-$PROD_DIR/.env}
WORK=$(mktemp -d)
DEST_NAME=sl-backup-dest
DEST_PORT=${DEST_PORT:-19000}
export BACKUP_DIR="$WORK/staging"
export ENV_FILE="$WORK/env"
export MC_DOCKER_ARGS="--add-host host.docker.internal:host-gateway"
COMPOSE_FILE=${COMPOSE_FILE:-$PROD_DIR/docker-compose.prod.yml:$PROD_DIR/docker-compose.test.yml}
export COMPOSE_FILE

cleanup() { docker rm -f "$DEST_NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

# Copy the env file and point the OCI_* settings at the stand-in.
cp "$SRC_ENV" "$ENV_FILE"
setv() { sed -i "/^$1=/d" "$ENV_FILE"; printf "%s='%s'\n" "$1" "$2" >> "$ENV_FILE"; }
setv OCI_S3_ENDPOINT "http://host.docker.internal:$DEST_PORT"
setv OCI_S3_BUCKET "secureleaf-backups"
setv OCI_S3_ACCESS_KEY "standin-access"
setv OCI_S3_SECRET_KEY "standin-secret-key-1234"
# shellcheck disable=SC1090
. "$ENV_FILE"
export OCI_S3_ENDPOINT OCI_S3_ACCESS_KEY OCI_S3_SECRET_KEY OCI_S3_BUCKET
MC_IMAGE=${MC_IMAGE:-chainguard/minio:latest}
export MC_IMAGE

# Run a shell snippet in an mc container that can reach the stand-in (alias `d`).
dest_mc() {
    # shellcheck disable=SC2086
    docker run --rm $MC_DOCKER_ARGS --user "$(id -u):$(id -g)" -e HOME=/tmp -e OCI_S3_ENDPOINT -e OCI_S3_ACCESS_KEY -e OCI_S3_SECRET_KEY -e OCI_S3_BUCKET \
        --entrypoint /bin/sh "$MC_IMAGE" -c \
        'mc alias set d "$OCI_S3_ENDPOINT" "$OCI_S3_ACCESS_KEY" "$OCI_S3_SECRET_KEY" --api S3v4 --path on >/dev/null; '"$1"
}
compose() { docker compose --env-file "$ENV_FILE" "$@"; }
psql_scalar() { compose exec -T postgres psql -U secureleaf_user -d secureleaf -Atc "$1"; }
bucket_fingerprint() {   # sorted "key size" of every object, hashed
    for b in $BACKUP_BUCKETS; do
        docker run --rm --network secureleaf_internal --user "$(id -u):$(id -g)" -e HOME=/tmp -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
            --entrypoint /bin/sh "$MC_IMAGE" -c \
            "mc alias set local http://minio:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null && mc ls -r --json local/$b" |
            jq -r '"\(.key) \(.size)"'
    done | sort | sha256sum | cut -d' ' -f1
}
fingerprint_db() { psql_scalar "select (select count(*) from users)||'/'||(select count(*) from products)||'/'||(select count(*) from document_versions)||'/'||(select count(*) from content_pages)||'/'||(select count(*) from flyway_schema_history)"; }

echo "== 1. Baseline"
DB_BEFORE=$(fingerprint_db)
OBJ_BEFORE=$(bucket_fingerprint)
echo "   db rows (users/products/versions/pages/migrations): $DB_BEFORE"
echo "   object fingerprint: $OBJ_BEFORE"
[ "$(echo "$DB_BEFORE" | cut -d/ -f1)" -gt 0 ] || { echo "no data in the stack - run stack-test.sh first" >&2; exit 1; }

echo "== 2. Start the stand-in Object Storage and back up"
docker rm -f "$DEST_NAME" >/dev/null 2>&1 || true
docker run -d --name "$DEST_NAME" -p "$DEST_PORT:9000" -e MINIO_ROOT_USER=standin-access \
    -e MINIO_ROOT_PASSWORD=standin-secret-key-1234 chainguard/minio:latest server /data >/dev/null
sleep 4
# Seed three old dumps so retention (KEEP_DAILY=2) has something to prune.
dest_mc '
mc mb --ignore-existing "d/$OCI_S3_BUCKET" >/dev/null
echo old > /tmp/old.dump
for d in 2000-01-01 2000-01-02 2000-01-03; do mc cp /tmp/old.dump "d/$OCI_S3_BUCKET/db/daily/secureleaf-$d.dump" >/dev/null; done'
KEEP_DAILY=2 sh "$PROD_DIR/scripts/backup.sh"
DAILY_COUNT=$(dest_mc 'mc ls "d/$OCI_S3_BUCKET/db/daily/"' | wc -l)
echo "   dumps kept in db/daily after retention (KEEP_DAILY=2): $DAILY_COUNT"
[ "$DAILY_COUNT" -eq 2 ] || { echo "FAIL: retention did not prune to 2" >&2; exit 1; }

echo "== 3. Destroy the stack and every volume"
compose down -v --remove-orphans >/dev/null 2>&1
docker volume ls -q | grep -c '^secureleaf_' | grep -qx 0 || { echo "FAIL: volumes still exist" >&2; exit 1; }

echo "== 4. restore.sh onto the fresh stack"
IMAGE_TAG=${IMAGE_TAG:-$(cat "$PROD_DIR/.last-good-tag" 2>/dev/null || echo local)}
export IMAGE_TAG
sh "$PROD_DIR/scripts/restore.sh"

echo "== 5. Compare"
waited=0
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$(compose ps -q backend)" 2>/dev/null)" = "healthy" ]; do
    sleep 5; waited=$((waited + 5)); [ "$waited" -lt 240 ] || { echo "FAIL: backend not healthy after restore" >&2; exit 1; }
done
DB_AFTER=$(fingerprint_db)
OBJ_AFTER=$(bucket_fingerprint)
echo "   db rows after:  $DB_AFTER"
echo "   objects after:  $OBJ_AFTER"
[ "$DB_BEFORE" = "$DB_AFTER" ] || { echo "FAIL: database rows differ" >&2; exit 1; }
[ "$OBJ_BEFORE" = "$OBJ_AFTER" ] || { echo "FAIL: bucket objects differ" >&2; exit 1; }
echo "PASS: backup -> restore round trip preserved data and objects"
