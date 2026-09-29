#!/bin/sh
# Phase 09D acceptance #2 and #10 — smoke-tests a RUNNING local prod stack through Caddy:
#   health UP, SPA + SPA fallback, /graphql, security headers, only-Caddy-publishes-ports,
#   non-public actuator, and SSE: a notification reaches an open stream THROUGH the proxy.
#
# Bring the stack up first (see infra/prod/test/README in docs/deployment.md "Testing the stack"):
#   SITE_HOST=localhost HTTPS_PORT=8443 HTTP_PORT=8080 ... scripts/generate-env.sh --non-interactive
#   COMPOSE_FILE=$PWD/infra/prod/docker-compose.prod.yml:$PWD/infra/prod/docker-compose.test.yml \
#     docker compose --env-file infra/prod/.env up -d --build
#   infra/prod/test/stack-test.sh
# Uses -k because Caddy's `tls internal` CA is not trusted by curl.
# shellcheck disable=SC2016  # GraphQL variables ($i) must not be expanded by the shell
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
ENV_FILE=${ENV_FILE:-$HERE/../.env}
# shellcheck disable=SC1090
. "$ENV_FILE"
BASE=${BASE:-https://${SITE_HOST}:${HTTPS_PORT:-443}}
PASS=0
FAIL=0

check() {  # $1 = description, rest = command
    desc=$1; shift
    if "$@" >/dev/null 2>&1; then PASS=$((PASS + 1)); echo "  ok    $desc"; else FAIL=$((FAIL + 1)); echo "  FAIL  $desc"; fi
}
gql() {  # $1 = query, $2 = optional bearer token, $3 = optional variables JSON
    if [ -n "${2:-}" ]; then AUTH="Authorization: Bearer $2"; else AUTH="X-No-Auth: 1"; fi
    jq -n --arg q "$1" --argjson v "${3:-null}" '{query:$q, variables:$v}' |
        curl -sk -H 'Content-Type: application/json' -H "$AUTH" --data @- "$BASE/graphql"
}

echo "== Through Caddy: $BASE"
check "actuator health is UP"        sh -c "curl -sk '$BASE/actuator/health' | grep -q '\"status\":\"UP\"'"
check "SPA index is served"          sh -c "curl -sk '$BASE/' | grep -q 'id=\"root\"'"
check "SPA fallback for a deep link" sh -c "curl -sk '$BASE/library/anything' | grep -q 'id=\"root\"'"
check "/graphql answers"             sh -c "curl -sk -H 'Content-Type: application/json' -d '{\"query\":\"{ categories { id } }\"}' '$BASE/graphql' | grep -q '\"categories\"'"
check "metrics NOT exposed publicly" sh -c "! curl -sk '$BASE/actuator/metrics' | grep -q '\"names\"'"
HEADERS=$(mktemp)
curl -skI "$BASE/" > "$HEADERS"
check "HSTS header on static"        grep -qi '^strict-transport-security' "$HEADERS"
check "CSP allows Razorpay checkout" grep -qi '^content-security-policy:.*checkout.razorpay.com' "$HEADERS"
check "CSP frame-ancestors none"     grep -qi "^content-security-policy:.*frame-ancestors 'none'" "$HEADERS"
check "CSP allows Google sign-in"    grep -qi '^content-security-policy:.*accounts.google.com' "$HEADERS"
check "index.html is no-store"       grep -qi '^cache-control:.*no-store' "$HEADERS"
rm -f "$HEADERS"

echo "== Only Caddy publishes ports"
PUBLISHED=$(docker ps --filter "network=secureleaf_internal" --format '{{.Names}} {{.Ports}}' | grep -- '->' | cut -d' ' -f1 | sort -u | tr '\n' ' ')
check "published containers = caddy only (got: $PUBLISHED)" sh -c "[ \"$(echo "$PUBLISHED" | tr -d ' ')\" = 'secureleaf-caddy-1' ]"

echo "== SSE through the proxy (register -> become creator -> open stream -> upload -> event)"
EMAIL="stack-test-$(date +%s)@example.com"
gql 'mutation($i: RegisterInput!){ register(input:$i){ id } }' "" \
    "{\"i\":{\"email\":\"$EMAIL\",\"password\":\"StackTest123!\",\"displayName\":\"Stack Test\"}}" >/dev/null
TOKEN=$(gql 'mutation($i: LoginInput!){ login(input:$i){ accessToken } }' "" \
    "{\"i\":{\"email\":\"$EMAIL\",\"password\":\"StackTest123!\"}}" | jq -r '.data.login.accessToken')
check "login through Caddy returned a token" test "$TOKEN" != "null" -a -n "$TOKEN"
gql 'mutation{ becomeCreator(input:{}){ id } }' "$TOKEN" >/dev/null
# becomeCreator changes roles: log in again so the token carries the CREATOR role.
TOKEN=$(gql 'mutation($i: LoginInput!){ login(input:$i){ accessToken } }' "" \
    "{\"i\":{\"email\":\"$EMAIL\",\"password\":\"StackTest123!\"}}" | jq -r '.data.login.accessToken')
CAT=$(gql '{ categories { id } }' "" | jq -r '.data.categories[0].id')
PRODUCT=$(gql 'mutation($i: CreateProductInput!){ createProduct(input:$i){ id } }' "$TOKEN" \
    "{\"i\":{\"title\":\"Stack test $EMAIL\",\"description\":\"d\",\"pricePaise\":19900,\"categoryId\":\"$CAT\",\"tags\":[\"t\"],\"freePreviewPages\":1}}" | jq -r '.data.createProduct.id')
check "product created" test "$PRODUCT" != "null" -a -n "$PRODUCT"

TICKET=$(gql '{ notificationStreamTicket }' "$TOKEN" | jq -r '.data.notificationStreamTicket')
STREAM=$(mktemp)
curl -skN --max-time 120 -H 'Accept: text/event-stream' "$BASE/api/notifications/stream?ticket=$TICKET" > "$STREAM" 2>/dev/null &
CURL_PID=$!
sleep 3
STATUS=$(curl -sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
    -F "file=@$HERE/sample.pdf;type=application/pdf" "$BASE/api/products/$PRODUCT/document")
check "PDF upload accepted through Caddy (HTTP $STATUS)" test "$STATUS" = "202" -o "$STATUS" = "200"

waited=0
while [ "$waited" -lt 90 ]; do
    grep -q 'PROCESSING_COMPLETE' "$STREAM" 2>/dev/null && break
    sleep 2; waited=$((waited + 2))
done
kill "$CURL_PID" 2>/dev/null || true
check "SSE event reached the open stream through Caddy (${waited}s)" grep -q 'PROCESSING_COMPLETE' "$STREAM"
rm -f "$STREAM"

echo "== Result: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
