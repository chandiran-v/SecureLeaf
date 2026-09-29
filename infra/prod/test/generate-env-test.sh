#!/bin/sh
# Phase 09D acceptance #8 (script half) — generate-env.sh output is complete, strong, private.
# The other half (the backend validator accepts it / rejects blank + default values) is covered by
# ProdSecretsConfigTest and by booting the stack with the generated file (stack-test.sh).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export ENV_FILE="$WORK/.env"
FAIL=0
fail() { echo "  FAIL  $*"; FAIL=$((FAIL + 1)); }
ok() { echo "  ok    $*"; }

SITE_HOST=example.duckdns.org RAZORPAY_KEY_ID=rzp_test_x RAZORPAY_KEY_SECRET=s RAZORPAY_WEBHOOK_SECRET=w \
    MAIL_FROM='SecureLeaf <no-reply@example.com>' sh "$HERE/../scripts/generate-env.sh" --non-interactive >/dev/null

[ "$(stat -c %a "$ENV_FILE")" = "600" ] && ok "mode 600" || fail "mode is not 600"

# Every variable named in .env.example must be present in the generated file.
# shellcheck disable=SC2013  # keys are simple identifiers
for key in $(grep -E '^[A-Z_]+=' "$HERE/../.env.example" | cut -d= -f1); do
    grep -q "^$key=" "$ENV_FILE" || fail "missing $key"
done
ok "all keys from .env.example present"

val() { grep "^$1=" "$ENV_FILE" | cut -d= -f2- | tr -d "'"; }
for key in DB_PASSWORD JWT_SECRET DRM_SIGNING_SECRET MINIO_ROOT_PASSWORD REDIS_PASSWORD; do
    v=$(val "$key")
    [ "${#v}" -ge 32 ] && ok "$key has ${#v} chars (>= 32)" || fail "$key too short (${#v})"
done
[ "$(val DB_PASSWORD)" != "$(val JWT_SECRET)" ] && [ "$(val JWT_SECRET)" != "$(val DRM_SIGNING_SECRET)" ] \
    && ok "secrets are distinct" || fail "secrets repeat"
case "$(val JWT_SECRET)$(val DB_PASSWORD)$(val MINIO_ROOT_PASSWORD)" in
    *CHANGE_ME*|*secureleaf_pass*|*secureleaf_minio*|*secureleaf_redis*) fail "a dev default leaked in" ;;
    *) ok "no dev default values" ;;
esac
[ "$(val MINIO_ROOT_USER)" != "secureleaf_minio_user" ] && ok "MINIO_ROOT_USER is not the dev default" || fail "dev MinIO user"

# A second run must refuse to overwrite (it would sign every user out).
if SITE_HOST=x RAZORPAY_KEY_ID=a RAZORPAY_KEY_SECRET=b RAZORPAY_WEBHOOK_SECRET=c sh "$HERE/../scripts/generate-env.sh" --non-interactive >/dev/null 2>&1; then
    fail "overwrote an existing .env without --force"
else ok "refuses to overwrite without --force"; fi

# Required external values must be enforced.
rm -f "$ENV_FILE"
if SITE_HOST='' RAZORPAY_KEY_ID='' sh "$HERE/../scripts/generate-env.sh" --non-interactive >/dev/null 2>&1; then
    fail "accepted a blank SITE_HOST"
else ok "blank SITE_HOST rejected"; fi

# The file must also load in a plain shell (deploy.sh sources it) with the quoted MAIL_FROM intact.
sh "$HERE/../scripts/generate-env.sh" --non-interactive >/dev/null 2>&1 || true
SITE_HOST=h RAZORPAY_KEY_ID=a RAZORPAY_KEY_SECRET=b RAZORPAY_WEBHOOK_SECRET=c MAIL_FROM='A B <a@b.c>' \
    sh "$HERE/../scripts/generate-env.sh" --non-interactive >/dev/null
# shellcheck disable=SC1090
( . "$ENV_FILE"; [ "$MAIL_FROM" = 'A B <a@b.c>' ] ) && ok ".env sources cleanly (quoted values survive)" || fail ".env does not source"

echo "== generate-env: $FAIL failure(s)"
[ "$FAIL" -eq 0 ]
