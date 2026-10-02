#!/bin/sh
# Phase 17 D2/D5 — the owner's full capacity run as one command.
#
#   LOADTEST_USERS must already be >= the peak (5000) on the backend, and users.csv present here.
#   BASE_URL=http://<backend>:8080 PROMETHEUS_URL=http://<prometheus>:9090 ./loadtest/run-capacity.sh
#
# Starts four k6 generators (docker-compose.k6.yml), waits for them, then runs heap-trend.js over
# every hold window. Env: STEPS, HOLD, RAMP, THINK_TIME, BASE_URL as in capacity.js; PROMETHEUS_URL
# (default http://localhost:9090); MAX_MB_PER_MIN (default 5). Exit code: non-zero if any k6
# threshold or any heap-trend check failed. WATCH `docker stats` on every generator while it runs:
# a generator above ~80 % CPU invalidates the numbers (see docker-compose.k6.yml).
set -u
cd "$(dirname "$0")"

STEPS=${STEPS:-500,1000,2500,5000}
HOLD=${HOLD:-10m}
RAMP=${RAMP:-2m}
PROM=${PROMETHEUS_URL:-http://localhost:9090}
LIMIT=${MAX_MB_PER_MIN:-5}
export STEPS HOLD RAMP

secs() { # 90s | 10m | 2h -> seconds
  n=${1%[smh]}
  case "$1" in *m) echo $((n * 60));; *h) echo $((n * 3600));; *) echo "$n";; esac
}

[ -f users.csv ] || { echo "loadtest/users.csv missing - seed with LOADTEST_USERS >= peak first" >&2; exit 2; }

START=$(date -u +%s)
# No --abort-on-container-exit: generators finish a few seconds apart and an early one must not
# stop the others mid-hold. `up` returns when all have exited; a threshold breach is exit code 99.
docker compose -f docker-compose.k6.yml up
k6_rc=0
for id in $(docker compose -f docker-compose.k6.yml ps -a -q); do
  code=$(docker inspect --format '{{.State.ExitCode}}' "$id")
  [ "$code" = 0 ] || k6_rc=1
done
docker compose -f docker-compose.k6.yml down >/dev/null 2>&1

# Hold windows, relative to the start (k6 begins ~2-5 s after `up`; the 10 s slack at the front of each
# window keeps the ramp out of the heap check).
rc=$k6_rc
t=0
for target in $(echo "$STEPS" | tr ',' ' '); do
  t=$((t + $(secs "$RAMP")))
  from=$((START + t + 10))
  t=$((t + $(secs "$HOLD")))
  to=$((START + t))
  printf 'hold-%s: ' "$target"
  node heap-trend.js --prometheus "$PROM" --max-mb-per-min "$LIMIT" \
    --start "$(date -u -d "@$from" +%FT%TZ)" --end "$(date -u -d "@$to" +%FT%TZ)" || rc=1
done
exit $rc
