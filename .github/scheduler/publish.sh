#!/usr/bin/env bash
# Phase scheduler — step 3: publish what Claude produced. This runs in a SEPARATE job on a fresh
# runner that holds the write token (git push uses the checkout credential = PR_TOKEN). Claude never runs here: the only input from the Claude
# job is the downloaded artifact (a git bundle plus the .md files it wrote).
set -euo pipefail
: "${MODE:?}" "${ISSUE:?}" "${BRANCH:?}" "${TITLE:?}" "${TARGET_BRANCH:?}" "${MILESTONE_LABEL:?}" \
  "${IMPLEMENT_RESULT:?}" "${OUT_DIR:?}" "${RUN_URL:?}"
PHASE_LABEL=${PHASE_LABEL:-phase}
FIX_LABEL=${FIX_LABEL:-fix}
IN_PROGRESS_LABEL=${IN_PROGRESS_LABEL:-phase:in-progress}
BLOCKED_LABEL=${BLOCKED_LABEL:-phase:blocked}
PR=${PR:-}
PR_TOKEN=${PR_TOKEN:-$GH_TOKEN}

say() { echo "$*" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"; }

park() { # park <markdown body> — stop the pipeline on this item until the owner acts
  local body="$1

Run: $RUN_URL
Remove the \`$BLOCKED_LABEL\` label to let the scheduler continue."
  gh issue edit "$ISSUE" --add-label "$BLOCKED_LABEL" >/dev/null
  # A phase/fix without a PR is no longer "in progress". A revise keeps its open PR.
  [ "$MODE" = revise ] || gh issue edit "$ISSUE" --remove-label "$IN_PROGRESS_LABEL" >/dev/null
  gh issue comment "$ISSUE" --body "$body" >/dev/null
  [ "$MODE" != revise ] || gh pr comment "$PR" --body "$body" >/dev/null
  say "⏸ Parked #$ISSUE."
  exit 0
}

if [ "$IMPLEMENT_RESULT" != success ]; then
  park "❌ **The Claude run failed or timed out** (result: \`$IMPLEMENT_RESULT\`). Check the log."
fi

commits=$(cat "$OUT_DIR/commits" 2>/dev/null || echo 0)
head=""
if [ "$commits" -gt 0 ]; then
  git fetch -q "$OUT_DIR/work.bundle" "refs/heads/$BRANCH"
  head=$(git rev-parse FETCH_HEAD)
  if git diff --name-only "$BASE_SHA" "$head" | grep -q '^\.github/'; then
    park "🛑 **Refused to publish:** the change modifies files under \`.github/\`, which automated runs must never touch."
  fi
fi

push() { # push [--force]
  [ -n "$head" ] || return 0
  git push "$@" origin "$head:refs/heads/$BRANCH"
}

if [ -f "$OUT_DIR/BLOCKED.md" ]; then
  if [ "$MODE" = revise ]; then push; else push --force; fi
  body="## 🚧 Claude needs you before it can continue

$(cat "$OUT_DIR/BLOCKED.md")"
  [ -z "$head" ] || body="$body

Partial work is on branch \`$BRANCH\`."
  park "$body

Answer here (or update the spec in \`docs/phases/\`)."
fi

case "$MODE" in
  phase|fix)
    [ "$commits" -gt 0 ] || park "❌ **Claude finished without making any changes.**"
    push --force # this branch belongs to the scheduler; a retry replaces the failed attempt
    {
      if [ -f "$OUT_DIR/PR_BODY.md" ]; then cat "$OUT_DIR/PR_BODY.md"; else echo "Implements $TITLE."; fi
      echo
      echo "Closes #$ISSUE"
      echo
      echo "---"
      echo "**Reviewing:** leave inline comments, a *Request changes* review, or a comment containing"
      echo "\`/revise\`. The scheduler then runs a revision pass on this branch. Merge when you're happy;"
      echo "the next item starts automatically."
      echo
      echo "🤖 Generated with [Claude Code](https://claude.com/claude-code) by the phase scheduler"
    } > "$RUNNER_TEMP/pr.md"
    label=$PHASE_LABEL; [ "$MODE" = fix ] && label=$FIX_LABEL
    prefix=""; [ "$MODE" = fix ] && prefix="Fix: "
    url=$(GH_TOKEN="$PR_TOKEN" gh pr create --base "$TARGET_BRANCH" --head "$BRANCH" --title "$prefix$TITLE" \
            --body-file "$RUNNER_TEMP/pr.md" --label "$label" --label "$MILESTONE_LABEL")
    gh issue comment "$ISSUE" --body "📬 PR ready for review: $url" >/dev/null
    say "Opened $url"
    ;;
  revise)
    push # never force: if the branch moved under us, fail rather than overwrite someone's commits
    {
      if [ "$commits" -gt 0 ]; then echo "✅ **Revision pass pushed $commits commit(s).**"
      else echo "ℹ️ **Revision pass made no code changes.**"; fi
      echo
      [ ! -f "$OUT_DIR/REVISION.md" ] || cat "$OUT_DIR/REVISION.md"
    } > "$RUNNER_TEMP/revision.md"
    gh pr comment "$PR" --body-file "$RUNNER_TEMP/revision.md" >/dev/null
    say "Revised PR #$PR ($commits commit(s))."
    ;;
esac
