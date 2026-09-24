#!/usr/bin/env bash
# Phase scheduler — step 2a: prepare the working copy for Claude (runs BEFORE Claude, in the
# job that has no write token). Creates the branch and writes .phase-run/:
#   brief.md   the Issue (what to build / what is broken)
#   review.md  revise mode only: all PR feedback + failing CI logs
#   TASK.md    exactly what this run must do
set -euo pipefail
: "${MODE:?}" "${ISSUE:?}" "${BRANCH:?}" "${TARGET_BRANCH:?}" "${GH_REPO:?}" "${OWNER:?}"

git config user.name  "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

if [ "$MODE" = revise ]; then git checkout -B "$BRANCH"; else git checkout -b "$BRANCH"; fi
echo "base_sha=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"

# .phase-run/ is the channel between the workflow and Claude. Never committed.
mkdir -p .phase-run
echo ".phase-run/" >> .git/info/exclude

gh issue view "$ISSUE" --json number,title,body,comments -q '
  "# Issue #\(.number): \(.title)\n\n\(.body)\n\n## Comments on the Issue\n" +
  ([.comments[] | "- **\(.author.login)** (\(.createdAt)): \(.body)"] | join("\n"))' > .phase-run/brief.md

common_done='
## Definition of done (all modes)
- `CLAUDE.md` and `AI_RULES.md` are followed. The learning notes are part of the work, not an extra.
- Backend verified:  `cd backend && sh ./mvnw -B verify`  (Docker is available for Testcontainers; Redis on localhost:6379)
- Frontend verified: `cd frontend && npm run lint && npx tsc --noEmit && npm run test && npm run build`
- Failures are fixed, never skipped or deleted to go green.
- Small, logical commits on the current branch. Do not push, open PRs or switch branches.
- Nothing under `.github/` is modified (the publish step refuses such changes).
- If blocked by a decision only the owner can make, or unable to reach a working, tested state:
  commit only what is safe, write `.phase-run/BLOCKED.md` (numbered questions, each with your
  recommended answer) and stop.'

case "$MODE" in
  phase)
    cat > .phase-run/TASK.md <<EOF
# Task: implement a new phase

Implement the phase described in \`.phase-run/brief.md\` end to end. If it points at a spec under
\`docs/phases/\`, that spec is authoritative: follow its decisions (D1, D2 …) and treat its
acceptance criteria as the test plan. Stay inside its scope; list follow-ups in the PR body.

Write the learning note the spec names (from \`docs/learning-notes/TEMPLATE.md\`, every section
filled), and update the learning-notes README index, \`interview-prep/glossary.md\` and
\`interview-prep/quick-reference.md\`.

When done, write \`.phase-run/PR_BODY.md\`: what was built, requirement IDs covered, decisions
made (with reasons), how to try it locally, test results, and known gaps. Be honest about
anything you could not verify.
$common_done
EOF
    ;;
  fix)
    cat > .phase-run/TASK.md <<EOF
# Task: resolve an open problem before the next phase starts

\`.phase-run/brief.md\` describes a problem (bug, gap, or review finding). Resolve it:
1. Reproduce it first, ideally as a failing test.
2. Make the smallest correct fix. No unrelated refactors or features.
3. Keep that test as a regression test.
4. Log it in the relevant phase learning note's "Gotchas and bugs we hit" table (symptom, root
   cause, fix, lesson). If it makes a good interview story, add it to that note's
   "Tell me about a bug you fixed" section.

When done, write \`.phase-run/PR_BODY.md\`: root cause, fix, how it was verified.
$common_done
EOF
    ;;
  revise)
    : "${PR:?}"
    sha=$(git rev-parse HEAD)
    {
      echo "# Feedback on PR #$PR (branch \`$BRANCH\`)"
      echo
      echo "## Reviews"
      gh api "repos/$GH_REPO/pulls/$PR/reviews?per_page=100" \
        -q '.[] | select((.body // "") != "") | "### \(.user.login) — \(.state) — \(.submitted_at)\n\(.body)\n"'
      echo
      echo "## Inline review comments"
      gh api "repos/$GH_REPO/pulls/$PR/comments?per_page=100" \
        -q '.[] | "- `\(.path):\(.line // .original_line // "?")` — **\(.user.login)** (\(.created_at)): \(.body)"'
      echo
      echo "## Conversation comments by the owner"
      gh pr view "$PR" --json comments \
        -q ".comments[] | select(.author.login == \"$OWNER\") | \"- (\(.createdAt)) \(.body)\""
      echo
      echo "## Failing CI on $sha"
      for id in $(gh run list --commit "$sha" --json databaseId,conclusion \
                    -q '.[] | select(.conclusion == "failure") | .databaseId'); do
        echo "### Run $id"; echo '```'; gh run view "$id" --log-failed | tail -n 200; echo '```'
      done
    } > .phase-run/review.md
    cat > .phase-run/TASK.md <<EOF
# Task: revise the open PR #$PR

You are on the branch of PR #$PR (Issue #$ISSUE, brief in \`.phase-run/brief.md\`). All feedback and
failing CI logs are in \`.phase-run/review.md\`. \`git log\` shows what earlier passes already did.
1. Address every actionable comment from the owner (**$OWNER**). The owner's word wins.
2. For findings from **claude[bot]**, fix the valid ones. For each one you dismiss, give the reason.
3. Make failing CI green.
4. Record any real bug found in review in the phase learning note's "Gotchas and bugs we hit" table.

When done, write \`.phase-run/REVISION.md\`: a table of *feedback item → what you did / why not*.
$common_done
EOF
    ;;
  *) echo "::error::unknown mode $MODE"; exit 1 ;;
esac

# Warm caches so Claude's first build doesn't spend turns downloading.
(cd frontend && npm ci --no-audit --no-fund)
(cd backend && sh ./mvnw -B -q dependency:go-offline) || true
