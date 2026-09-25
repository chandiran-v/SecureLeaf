#!/usr/bin/env bash
# Phase scheduler — step 1: decide what this run does.
#
# Priority (the first match wins):
#   0. Reconcile: close Issues whose PR merged; flag Issues whose PR vanished.
#   1. Anything blocked               -> stop. The owner has to answer first.
#   2. A work item has an open PR     -> "revise" if it has unaddressed feedback or failing CI,
#                                        otherwise stop and wait for the owner to merge.
#   3. An open `fix` Issue            -> "fix"   (open problems are resolved before new work)
#   4. The next `phase` Issue         -> "phase" (lowest title in version order: 05A < 05B < 06 < 10)
#
# Writes mode / issue / pr / branch / title to $GITHUB_OUTPUT. No mode = nothing to do.
set -euo pipefail

: "${GH_REPO:?}" "${OWNER:?}" "${MILESTONE_LABEL:?}" "${RUN_URL:?}"
PHASE_LABEL=${PHASE_LABEL:-phase}
FIX_LABEL=${FIX_LABEL:-fix}
IN_PROGRESS_LABEL=${IN_PROGRESS_LABEL:-phase:in-progress}
BLOCKED_LABEL=${BLOCKED_LABEL:-phase:blocked}
MAX_REVISIONS=${MAX_REVISIONS:-3}
REVISE_MARKER='<!-- auto-revise -->'
REVIEW_BOT='claude[bot]'

say() { echo "$*" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"; }
out() { echo "$1=$2" >> "${GITHUB_OUTPUT:-/dev/null}"; }
has_label() { gh issue view "$1" --json labels -q "any(.labels[]; .name == \"$2\")"; }

block() { # block <issue> <reason>
  gh issue edit "$1" --add-label "$BLOCKED_LABEL" >/dev/null
  gh issue comment "$1" --body "🚧 **Blocked:** $2

Fix what's needed, then remove the \`$BLOCKED_LABEL\` label and the scheduler will carry on." >/dev/null
}

ensure_labels() {
  gh label create "$PHASE_LABEL"       --color 5319e7 --description "A build phase the scheduler runs in order"        --force >/dev/null
  gh label create "$FIX_LABEL"         --color b60205 --description "A problem to resolve before the next phase starts" --force >/dev/null
  gh label create "$MILESTONE_LABEL"   --color 0e8a16 --description "Belongs to this MVP"                              --force >/dev/null
  gh label create "$IN_PROGRESS_LABEL" --color fbca04 --description "Claude is working on it or its PR awaits review"  --force >/dev/null
  gh label create "$BLOCKED_LABEL"     --color d93f0b --description "Needs the owner before the scheduler continues"   --force >/dev/null
}

reconcile() {
  local n pr state
  for n in $(gh issue list --state open --label "$IN_PROGRESS_LABEL" --json number -q '.[].number'); do
    read -r pr state < <(gh pr list --state all --head "auto/issue-$n" --json number,state \
                           -q '.[0] | "\(.number) \(.state)"')
    case "$state" in
      MERGED)
        gh issue edit "$n" --remove-label "$IN_PROGRESS_LABEL" --remove-label "$BLOCKED_LABEL" >/dev/null
        gh issue close "$n" --reason completed --comment "✅ Merged in #$pr." >/dev/null
        say "Closed #$n (PR #$pr merged)." ;;
      OPEN) ;;
      CLOSED)
        gh issue edit "$n" --remove-label "$IN_PROGRESS_LABEL" >/dev/null
        block "$n" "PR #$pr was closed without merging. Removing the label will re-run the work from scratch." ;;
      *)
        # No PR at all: the run that started it failed before publishing (every failure path
        # parks the Issue as blocked). While it is blocked, leave it alone: the owner may still
        # "Re-run failed jobs" to publish the saved work. Once the owner removes the block
        # without a PR appearing, start the item again from scratch.
        if [ "$(has_label "$n" "$BLOCKED_LABEL")" != true ]; then
          gh issue edit "$n" --remove-label "$IN_PROGRESS_LABEL" >/dev/null
          gh issue comment "$n" --body "🔄 Unblocked with no PR found, so this item will be redone from scratch." >/dev/null
          say "Reset #$n (no PR) so it can be redone."
        fi
        ;;
    esac
  done

  # Issues closed by "Closes #N" on merge keep their in-progress label; tidy it.
  for n in $(gh issue list --state closed --label "$IN_PROGRESS_LABEL" --json number -q '.[].number'); do
    gh issue edit "$n" --remove-label "$IN_PROGRESS_LABEL" --remove-label "$BLOCKED_LABEL" >/dev/null
  done

  # The reverse drift: an open scheduler PR whose Issue lost its in-progress label (e.g. a
  # failed publish that was re-run by hand). Restore it, or the scheduler would start that
  # Issue again and overwrite the PR's branch.
  for n in $(gh pr list --state open --json headRefName -q '.[].headRefName | select(startswith("auto/issue-")) | ltrimstr("auto/issue-")'); do
    if [ "$(gh issue view "$n" --json state -q .state)" = OPEN ] && [ "$(has_label "$n" "$IN_PROGRESS_LABEL")" != true ]; then
      gh issue edit "$n" --add-label "$IN_PROGRESS_LABEL" >/dev/null
      say "Re-marked #$n in progress (its PR is open)."
    fi
  done
}

# Prints why a PR needs a revision pass, "WAIT:<why>" if it must wait, or nothing if it is
# simply waiting for the owner to review/merge. Also sets ATTEMPTS: automatic passes since the
# owner's last activity on the PR (so every new round of feedback gets MAX_REVISIONS passes).
revision_reason() {
  local pr=$1 json reviews inline last owner_last pending failing owner_new total bot_new d
  # Files, not --argjson: a busy PR's review JSON can exceed the 128 KB per-argument limit.
  d=$(mktemp -d)
  gh pr view "$pr" --json commits,statusCheckRollup,comments > "$d/pr.json"
  gh api "repos/$GH_REPO/pulls/$pr/reviews?per_page=100"  > "$d/reviews.json"
  gh api "repos/$GH_REPO/pulls/$pr/comments?per_page=100" > "$d/inline.json"
  json=$(cat "$d/pr.json"); inline=$(cat "$d/inline.json")
  last=$(jq -r '.commits[-1].committedDate' <<<"$json")

  owner_last=$(jq -rn --arg o "$OWNER" --slurpfile j "$d/pr.json" --slurpfile r "$d/reviews.json" --slurpfile i "$d/inline.json" '
    [ ($j[0].comments[] | select(.author.login == $o) | .createdAt),
      ($r[0][] | select(.user.login == $o) | .submitted_at),
      ($i[0][] | select(.user.login == $o) | .created_at) ] | max // ""')
  ATTEMPTS=$(jq --arg m "$REVISE_MARKER" --arg t "$owner_last" '[.comments[] | select((.body | contains($m)) and .createdAt > $t)] | length' <<<"$json")
  total=$(jq --arg m "$REVISE_MARKER" '[.comments[] | select(.body | contains($m))] | length' <<<"$json")

  # Let CI and the Claude review finish first, so one revision sees all the feedback.
  pending=$(jq '[.statusCheckRollup[]
                 | select((.status != null and .status != "COMPLETED") or .state == "PENDING")] | length' <<<"$json")
  [ "$pending" -eq 0 ] || { echo "WAIT:$pending check(s) still running"; return; }

  failing=$(jq -r '[.statusCheckRollup[]
                    | select(.conclusion == "FAILURE" or .conclusion == "TIMED_OUT"
                             or .state == "FAILURE" or .state == "ERROR")
                    | (.name // .context)
                    | select(test("claude"; "i") | not)] | join(", ")' <<<"$json")
  [ -z "$failing" ] || { echo "failing checks: $failing"; return; }

  # Owner feedback newer than the last commit: inline review comments, a "Request changes"
  # review, or "/revise" anywhere. Comments mentioning @claude belong to claude.yml instead.
  owner_new=$(jq -rn --arg o "$OWNER" --arg t "$last" --slurpfile j "$d/pr.json" --slurpfile r "$d/reviews.json" --slurpfile i "$d/inline.json" '
    [ ($i[0][] | select(.user.login == $o and .created_at > $t and (.body | contains("@claude") | not))),
      ($r[0][] | select(.user.login == $o and .submitted_at > $t
                     and (.state == "CHANGES_REQUESTED" or ((.body // "") | contains("/revise"))))),
      ($j[0].comments[] | select(.author.login == $o and .createdAt > $t and (.body | contains("/revise")))) ] | length')
  if [ "$owner_new" -gt 0 ]; then echo "$owner_new piece(s) of owner feedback"; return; fi

  # Findings from the automated Claude review get ONE automatic pass per PR (no bot ping-pong).
  bot_new=$(jq --arg b "$REVIEW_BOT" --arg t "$last" '[.[] | select(.user.login == $b and .created_at > $t)] | length' <<<"$inline")
  if [ "$bot_new" -gt 0 ] && [ "$total" -eq 0 ]; then echo "$bot_new Claude review finding(s)"; return; fi
}

start() { # start <mode> <issue> [pr] [reason]
  local mode=$1 issue=$2 pr=${3:-} reason=${4:-} title
  title=$(gh issue view "$issue" --json title -q .title)
  say "▶ $mode: #$issue — $title${pr:+ (PR #$pr)}${reason:+ — $reason}"
  if [ "${DRY_RUN:-false}" = true ]; then say "Dry run — stopping here."; exit 0; fi

  if [ "$mode" = revise ]; then
    gh pr comment "$pr" --body "🔁 Revision pass started ($reason): $RUN_URL $REVISE_MARKER" >/dev/null
  else
    gh issue edit "$issue" --add-label "$IN_PROGRESS_LABEL" --remove-label "$BLOCKED_LABEL" >/dev/null
    gh issue comment "$issue" --body "🤖 Scheduler started this ($mode): $RUN_URL" >/dev/null
  fi
  out mode "$mode"; out issue "$issue"; out pr "$pr"; out branch "auto/issue-$issue"
  { echo "title<<__EOF__"; echo "$title"; echo "__EOF__"; } >> "${GITHUB_OUTPUT:-/dev/null}"
  exit 0
}

open_pr_for() { gh pr list --state open --head "auto/issue-$1" --json number -q '.[0].number // empty'; }

# ─────────────────────────────────────────────────────────────────────────────

ensure_labels
reconcile

# Manual override from "Run workflow": run exactly this Issue (also clears its block).
if [ -n "${INPUT_ISSUE:-}" ]; then
  json=$(gh issue view "$INPUT_ISSUE" --json state,author,labels)
  [ "$(jq -r .state <<<"$json")" = OPEN ]            || { say "::error::#$INPUT_ISSUE is not open"; exit 1; }
  [ "$(jq -r .author.login <<<"$json")" = "$OWNER" ] || { say "::error::#$INPUT_ISSUE was not opened by $OWNER"; exit 1; }
  pr=$(open_pr_for "$INPUT_ISSUE")
  if [ -n "$pr" ]; then start revise "$INPUT_ISSUE" "$pr" "manual run"; fi
  if jq -e --arg l "$FIX_LABEL" 'any(.labels[]; .name == $l)' <<<"$json" >/dev/null; then start fix "$INPUT_ISSUE"; fi
  jq -e --arg l "$PHASE_LABEL" 'any(.labels[]; .name == $l)' <<<"$json" >/dev/null \
    || { say "::error::#$INPUT_ISSUE has neither '$PHASE_LABEL' nor '$FIX_LABEL'"; exit 1; }
  start phase "$INPUT_ISSUE"
fi

# 1. Blocked work stops everything — phases build on each other.
blocked=$(gh issue list --state open --label "$MILESTONE_LABEL" --label "$BLOCKED_LABEL" --json number -q 'map("#\(.number)") | join(", ")')
if [ -n "$blocked" ]; then say "⏸ Blocked: $blocked — waiting for you."; exit 0; fi

# 2. Work in flight: revise it, or wait for the merge.
for n in $(gh issue list --state open --label "$MILESTONE_LABEL" --label "$IN_PROGRESS_LABEL" --json number -q '.[].number'); do
  pr=$(open_pr_for "$n")
  [ -n "$pr" ] || continue
  ATTEMPTS=0
  reason_file=$(mktemp)
  revision_reason "$pr" > "$reason_file"   # not $(…): ATTEMPTS must survive the call
  reason=$(cat "$reason_file")
  case "$reason" in
    "")     say "⏳ PR #$pr (#$n) is waiting for your review/merge."; exit 0 ;;
    WAIT:*) say "⏳ PR #$pr (#$n): ${reason#WAIT:}."; exit 0 ;;
  esac
  if [ "$ATTEMPTS" -ge "$MAX_REVISIONS" ]; then
    block "$n" "PR #$pr still needs work ($reason) after $ATTEMPTS automatic revision passes. Fix it yourself, or comment \`/revise\` with guidance for $MAX_REVISIONS more passes."
    say "⏸ Blocked #$n after $ATTEMPTS revision passes."; exit 0
  fi
  start revise "$n" "$pr" "$reason"
done

# 3. Open problems are fixed before new work starts.
fix=$(gh issue list --state open --label "$FIX_LABEL" --label "$MILESTONE_LABEL" --author "$OWNER" \
        --json number -q 'map(.number) | sort | .[0] // empty')
if [ -n "$fix" ]; then start fix "$fix"; fi

# 4. Next phase. Only the owner's Issues: the repo is public and anyone can open one.
next=$(gh issue list --state open --label "$PHASE_LABEL" --label "$MILESTONE_LABEL" --author "$OWNER" --limit 100 \
         --json number,title -q '.[] | "\(.title)\t\(.number)"' | sort -V | head -n1 | cut -f2)
if [ -n "$next" ]; then start phase "$next"; fi

say "🎉 No open '$MILESTONE_LABEL' work left."
