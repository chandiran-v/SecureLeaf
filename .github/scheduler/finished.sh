#!/usr/bin/env bash
# Phase scheduler — did Claude finish? "Finished" means it wrote its result file (PR_BODY.md,
# or REVISION.md in revise mode) or BLOCKED.md. Ending a turn without either — e.g. after
# presenting a plan — is not finishing. Writes finished=true|false to $GITHUB_OUTPUT.
set -euo pipefail
: "${MODE:?}"
result=PR_BODY.md; [ "$MODE" = revise ] && result=REVISION.md
if [ -f ".phase-run/$result" ] || [ -f .phase-run/BLOCKED.md ]; then
  echo "finished=true" >> "$GITHUB_OUTPUT"
else
  echo "Claude ended its turn without .phase-run/$result or BLOCKED.md — resuming the session." | tee -a "$GITHUB_STEP_SUMMARY"
  echo "finished=false" >> "$GITHUB_OUTPUT"
fi
