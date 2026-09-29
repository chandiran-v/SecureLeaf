#!/bin/sh
# Shared helpers for the Phase 09D production scripts. Source it:  . "$(dirname "$0")/lib.sh"
# POSIX sh on purpose: it runs on a bare Ubuntu box with nothing installed.

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROD_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
# shellcheck disable=SC2034  # used by the scripts that source this file
REPO_DIR=$(cd "$PROD_DIR/../.." && pwd)
ENV_FILE="${ENV_FILE:-$PROD_DIR/.env}"
# Compose natively understands COMPOSE_FILE (colon-separated), which is how the CI test layers
# docker-compose.test.yml on top.
COMPOSE_FILE="${COMPOSE_FILE:-$PROD_DIR/docker-compose.prod.yml}"
export COMPOSE_FILE
NETWORK="${NETWORK:-secureleaf_internal}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

load_env() {
    [ -f "$ENV_FILE" ] || die "$ENV_FILE not found - run infra/prod/scripts/generate-env.sh first"
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
}

compose() { docker compose --env-file "$ENV_FILE" "$@"; }

# Run mc (the MinIO client) once, on the compose network, with the given shell script on stdin.
# $BACKUP_DIR is mounted at /backup; the container runs as OUR uid so it can read the 0600 dump. Extra `docker run` args (e.g. --add-host) via MC_DOCKER_ARGS.
run_mc() {
    # shellcheck disable=SC2086
    docker run --rm -i --network "$NETWORK" ${MC_DOCKER_ARGS:-} \
        --user "$(id -u):$(id -g)" -e HOME=/tmp \
        -v "$BACKUP_DIR:/backup" \
        -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD \
        -e OCI_S3_ENDPOINT -e OCI_S3_BUCKET -e OCI_S3_ACCESS_KEY -e OCI_S3_SECRET_KEY \
        -e BACKUP_BUCKETS -e DAY -e WEEKDAY -e DUMP_NAME -e KEEP_DAILY -e KEEP_WEEKLY \
        --entrypoint /bin/sh "${MC_IMAGE:-quay.io/minio/mc:latest}" -s
}

# The two aliases every mc script needs: `local` (our MinIO) and `dest` (Oracle Object Storage).
# shellcheck disable=SC2034,SC2016  # sourced by other scripts; $VARS must expand inside the container
MC_ALIASES='
set -e
mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc alias set dest "$OCI_S3_ENDPOINT" "$OCI_S3_ACCESS_KEY" "$OCI_S3_SECRET_KEY" --api S3v4 --path on >/dev/null
mc mb --ignore-existing "dest/$OCI_S3_BUCKET" >/dev/null
'
