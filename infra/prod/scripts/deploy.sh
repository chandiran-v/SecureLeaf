#!/bin/sh
# Phase 09D D11 — health-gated deploy with automatic rollback.
#   deploy.sh [git-ref]        default ref: main
#
#   1. git fetch + checkout of the ref
#   2. build the images, tagged with the git SHA
#   3. docker compose up -d
#   4. wait for health: backend readiness AND https://SITE_HOST/actuator/health
#   5. on failure: roll back to the previously running image tags, exit non-zero
#   6. prune old images, keeping the last 3
# Flyway migrations run when the backend boots (they are part of "backend readiness").
#
# Test/automation knobs (env):  SKIP_GIT=1  DEPLOY_TAG=<tag>  NO_BUILD=1  HEALTH_TIMEOUT=<s>
#                               HEALTH_URL=<url>  HEALTH_CURL_ARGS=-k
set -eu
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"
load_env

REF=${1:-main}
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-300}
# The public URL through Caddy; a non-default HTTPS_PORT (local tests) is included.
PORT_SUFFIX=""
if [ -n "${HTTPS_PORT:-}" ] && [ "$HTTPS_PORT" != "443" ]; then PORT_SUFFIX=":$HTTPS_PORT"; fi
HEALTH_URL=${HEALTH_URL:-https://${SITE_HOST}${PORT_SUFFIX}/actuator/health}
KEEP_IMAGES=${KEEP_IMAGES:-3}

cd "$REPO_DIR"

# ── 1. checkout ──────────────────────────────────────────────────────────────
if [ "${SKIP_GIT:-0}" != "1" ]; then
    log "Fetching and checking out $REF"
    git fetch --tags --prune origin
    git checkout --quiet "$REF"
    # A branch needs fast-forwarding; a tag or SHA (detached HEAD) does not.
    if git symbolic-ref -q HEAD >/dev/null; then git merge --ff-only "origin/$REF"; fi
fi
NEW_TAG=${DEPLOY_TAG:-$(git rev-parse --short=12 HEAD)}

# ── what is running now? (the rollback target) ───────────────────────────────
current_tag() {
    cid=$(compose ps -q backend 2>/dev/null || true)
    [ -n "$cid" ] || return 0
    docker inspect -f '{{.Config.Image}}' "$cid" 2>/dev/null | sed 's/.*://'
}
# Rollback target = the last version that PASSED the health gate (state file), not merely "what is
# running": after a failed deploy the running version is the broken one.
STATE_FILE="$PROD_DIR/.last-good-tag"
PREV_TAG=""
[ -s "$STATE_FILE" ] && PREV_TAG=$(cat "$STATE_FILE")
[ -n "$PREV_TAG" ] || PREV_TAG=$(current_tag || true)
log "Deploying tag $NEW_TAG (currently running: ${PREV_TAG:-nothing})"

# ── 2. build ─────────────────────────────────────────────────────────────────
if [ "${NO_BUILD:-0}" != "1" ]; then
    log "Building images (this takes a few minutes on the server)"
    IMAGE_TAG=$NEW_TAG compose build
fi

# ── 3. start ─────────────────────────────────────────────────────────────────
log "Starting the stack"
# `up -d` itself waits on depends_on health and exits non-zero when the backend never becomes
# healthy (caddy depends on it) - that is exactly the case the health gate below must handle, so
# a failure here must not abort the script before it can roll back.
IMAGE_TAG=$NEW_TAG compose up -d --remove-orphans --no-build || log "compose up reported a failure - checking health"

# ── 4. wait for health ───────────────────────────────────────────────────────
healthy() {
    cid=$(compose ps -q backend 2>/dev/null || true)
    [ -n "$cid" ] || return 1
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null)" = "healthy" ] || return 1
    # shellcheck disable=SC2086
    curl -fsS --max-time 5 ${HEALTH_CURL_ARGS:-} "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'
}

wait_healthy() {
    waited=0
    while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
        if healthy; then return 0; fi
        sleep 5
        waited=$((waited + 5))
    done
    return 1
}

if wait_healthy; then
    log "Healthy. Deployed $NEW_TAG."
    echo "$NEW_TAG" > "$STATE_FILE"
else
    log "Health check FAILED after ${HEALTH_TIMEOUT}s for $NEW_TAG"
    compose logs --tail 40 backend || true
    # ── 5. roll back ─────────────────────────────────────────────────────────
    if [ -n "$PREV_TAG" ] && [ "$PREV_TAG" != "$NEW_TAG" ]; then
        log "Rolling back to $PREV_TAG"
        IMAGE_TAG=$PREV_TAG compose up -d --remove-orphans --no-build || true
        if wait_healthy; then
            log "Rollback complete: $PREV_TAG is serving again."
        else
            log "Rollback did not become healthy either - manual intervention needed (docs/ops/runbook.md)"
        fi
    else
        log "No previous version to roll back to."
    fi
    exit 1
fi

# ── 6. prune: keep the newest $KEEP_IMAGES tags of each image ────────────────
for repo in secureleaf/backend secureleaf/caddy; do
    docker image ls "$repo" --format '{{.Tag}}' | tail -n +$((KEEP_IMAGES + 1)) | while read -r tag; do
        [ "$tag" = "$NEW_TAG" ] && continue
        [ "$tag" = "$PREV_TAG" ] && continue
        log "Pruning $repo:$tag"
        docker image rm "$repo:$tag" >/dev/null 2>&1 || true
    done
done
docker image prune -f >/dev/null 2>&1 || true
log "Done."
