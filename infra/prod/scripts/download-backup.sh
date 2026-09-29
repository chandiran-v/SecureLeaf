#!/bin/sh
# Phase 09D D12 — run from YOUR LAPTOP (not the server). Pulls the newest database dump over
# SSH/scp so a copy exists OUTSIDE Oracle (the ADR's account-termination risk: 3-2-1 backups).
#   download-backup.sh user@server [local-dir]
# The server keeps the latest dump in /var/backups/secureleaf. Objects (PDFs) live in Object
# Storage; download those with `mc mirror` from your laptop if you want a full offline copy.
set -eu
[ $# -ge 1 ] || { echo "usage: $0 user@server [local-dir]" >&2; exit 2; }
HOST=$1
DEST=${2:-./secureleaf-backups}
REMOTE_DIR=${REMOTE_DIR:-/var/backups/secureleaf}
mkdir -p "$DEST"
# shellcheck disable=SC2029  # expanding REMOTE_DIR on the client side is intended
LATEST=$(ssh "$HOST" "ls -1t $REMOTE_DIR/secureleaf-*.dump 2>/dev/null | head -n 1")
[ -n "$LATEST" ] || { echo "no dump found in $REMOTE_DIR on $HOST" >&2; exit 1; }
scp "$HOST:$LATEST" "$DEST/"
echo "Saved $DEST/$(basename "$LATEST")"
