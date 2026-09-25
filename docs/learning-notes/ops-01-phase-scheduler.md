# Ops 01 — Phase Scheduler (unattended, one-item-at-a-time CI automation)

> **Status:** Done (goes live once pushed to the default branch, `feature/secure-leaf-mvp1`)
> **Built:** 2026-09-24
> **Requirement IDs covered:** none. This is developer tooling, not a product feature.
> **Commits:** _(filled in on commit)_

---

## 1. What we built, in plain English

The project is now split into **phases**, each with a written spec (`docs/phases/`) and a GitHub Issue. A robot in GitHub Actions works through them **one at a time, in order**. It gives the next phase to Claude. Claude builds it on its own branch, runs the tests, writes the learning note, and a PR is opened for me to review.

The robot never merges. It also never starts new work while old work is still open. Every time it wakes up, it does exactly **one** thing, in this priority:

1. If anything is **blocked** (Claude asked me a question, or a run failed), it stops and waits for me.
2. If a PR is **open**, it checks whether I left feedback, CI failed, or the automated Claude reviewer found problems. If so, it runs a *revision pass* on that same PR. If not, it waits for me to merge.
3. If I opened a **`fix` Issue** (a problem found later), it fixes that before any new phase.
4. Only then does it start the **next phase**.

It wakes up twice a day (03:00 and 14:00 IST), and also immediately when something relevant happens: I merge, I review, CI finishes, I open an Issue, or I unblock something.

**Before:** every phase needed me at the keyboard to start and steer a session.
**After:** I write specs and review PRs. The sequencing, retries and bookkeeping are automatic, and the "finish before moving on" rule is enforced by the robot.

---

## 2. Why it matters

- **Correctness of the sequence.** Phase 6 builds on Phase 5's code. Starting it while Phase 5 still has open review comments means building on code that is about to change.
- **Human judgement stays the gate.** Tests catch regressions, not wrong designs. The merge button is mine.
- **Security on a public repo.** An AI with a shell, triggered by GitHub Issues, is a prompt-injection target. The design has to assume a stranger will try.
- **It's the engineering-manager loop.** Well-specified tickets, one owner at a time, review before done. The phase specs are the tickets.

---

## 3. New concepts introduced

### 3.1 Scheduled workflows and the default-branch rule

**What it is:** `on: schedule` runs a workflow at times given by a cron expression, always in **UTC**.

**The analogy:** An alarm clock that rings whether or not anyone is home.

**How it works:** we run twice a day. `30 21 * * *` means 21:30 UTC, which is 03:00 IST the *next* calendar day, because IST is UTC+5:30 and the conversion crosses midnight. `30 8 * * *` means 08:30 UTC, which is 14:00 IST. **GitHub only runs schedules from the default branch's copy of the file.** That's why the default branch was switched to `feature/secure-leaf-mvp1`. It also means "Closes #N" in a PR now closes the Issue on merge, because closing keywords also only work for the default branch.

**In our code:** `.github/workflows/phase-scheduler.yml:20-21`

**What breaks without it:** a cron that looks right and silently never fires.

### 3.2 A priority-ordered state machine over GitHub labels

**What it is:** The scheduler's "memory" is the Issue labels: `phase`, `fix`, `mvp1`, `phase:in-progress`, `phase:blocked`. Each run reads the current state and makes one move.

**The analogy:** A kanban board. Cards move column to column, and there's a work-in-progress limit of one.

**How it works:** `pick.sh` checks the rules in a fixed order, and the first match wins. Blocked stops everything. An open PR means revise or wait. Then fix Issues, then the next phase, sorted with `sort -V` so `05A < 05B < 06 < 10`.

**In our code:** `.github/scheduler/pick.sh`: the priority block at the bottom of the file.

**What breaks without it:** two phases in flight, conflicting PRs, and new work piled on top of unresolved bugs.

### 3.3 Reconciliation instead of trusting events

**What it is:** Every run compares reality (the PR state) with the labels and repairs any drift. A merged PR closes its Issue. A closed or missing PR marks the Issue blocked.

**The analogy:** Balancing your chequebook every night instead of trusting every notification arrived.

**Why:** Events get missed, workflows fail midway, and people close PRs by hand. Reconciliation makes the system **self-healing**: a missed event only delays things by one run. Kubernetes controllers work the same way. So does the Phase 4 note's reconciliation job for lost webhooks.

**In our code:** `pick.sh`, function `reconcile`.

### 3.4 Event-driven with a cron safety net

**What it is:** `phase-kick.yml` listens for PR merged, PR reviewed, `/revise` comments, Issue opened or unblocked, and CI / Claude review finished. It then *asks* the scheduler to run (`gh workflow run`). The twice-daily cron (03:00 and 14:00 IST) catches anything the kicks missed.

**The gotcha:** Events caused by `GITHUB_TOKEN` don't trigger other workflows; this is GitHub's loop protection. **`workflow_dispatch` is one of the few exceptions**, which is why the kick dispatches instead of, say, posting a comment.

**In our code:** `.github/workflows/phase-kick.yml`

### 3.5 Privilege separation: the AI never holds the write token

**What it is:** The work is split across three jobs on three separate runners:

| Job | Has | Does |
|---|---|---|
| `pick` | Issue/PR write, no code write | Decides the move, updates labels |
| `implement` | **read-only** `GITHUB_TOKEN`, no push credential in `.git/config` | Claude works, and its commits are packed into a **git bundle** artifact |
| `publish` | write token (PAT) | Downloads the bundle, checks it, pushes, opens or updates the PR |

**The analogy:** A bank teller counts cash behind glass. A different person, who can't touch the cash drawer, carries it to the vault.

**Why:** Claude has a shell. Anything on the runner where Claude runs could have been changed by it, including scripts that a later step executes with a secret. Putting the credential on a **different machine** that only receives a bundle (data, not executable steps) removes that path completely. `publish` also refuses any change under `.github/`, so an automated run can never rewrite the automation.

**In our code:** `phase-scheduler.yml`, jobs `implement` (`permissions: contents: read`) and `publish`. `publish.sh` checks for the `.github/` refusal.

### 3.6 Prompt injection and "who wrote this?"

**What it is:** Text from an untrusted person steering an AI into actions its operator didn't intend.

**How we defend:**
- Only Issues **authored by the repo owner** are ever picked (`--author "$OWNER"`).
- The kick workflow only reacts to the owner's reviews and comments.
- Feedback detection counts only the owner's and `claude[bot]`'s comments.

**The subtle bug this caused (see §8):** if the robot posts comments using *my* PAT, those comments are authored by *me*, and the robot then mistakes its own comments for my feedback. So all robot comments use `GITHUB_TOKEN` (they appear as `github-actions[bot]`). Only the push and PR creation use the PAT.

### 3.7 Bounded retries per round of feedback

**What it is:** A PR gets at most `MAX_REVISIONS` (3) automatic revision passes **since my last comment on it**. Automated review findings get only **one** pass per PR.

**Why:** Without a bound, "CI fails → Claude revises → CI fails → …" runs all night and burns quota. And two bots (the reviewer and the fixer) could ping-pong forever. After the limit, the Issue is blocked and I decide. A new `/revise` from me starts a fresh round of 3.

**In our code:** `pick.sh`, function `revision_reason` (the `ATTEMPTS` and `total` counters).

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Least privilege | Per-job `permissions:`, and Claude's job is read-only | A compromised step can't escalate | `phase-scheduler.yml` |
| Serialisation | `concurrency: group: phase-scheduler` | Two runs never overlap | `phase-scheduler.yml` |
| Fail loudly, park safely | Any failure leads to a comment plus `phase:blocked`, never a silent retry | No quota burn, and I always know why it stopped | `publish.sh` `park()` |
| Wait for all signals | Revise only after every check has finished | One revision sees all the feedback | `revision_reason` |
| Idempotent retries | Fixed branch per Issue (`auto/issue-N`), force-pushed for new work, never force-pushed for revisions | Retries replace; revisions never clobber my commits | `publish.sh` |
| Scripts, not YAML soup | Logic in `.github/scheduler/*.sh`, loaded from the workflow's own commit | Readable, `bash -n`-checkable, testable | `.github/scheduler/` |
| Dry run | `workflow_dispatch` → `dry_run` | See the decision before trusting it | `phase-scheduler.yml` |
| Specs remove ambiguity | Every open question in a spec is a numbered decision with an answer | An unattended run can't ask mid-way | `docs/phases/*.md` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `.github/workflows/phase-scheduler.yml` | Triggers and the three jobs (pick → implement → publish) |
| `.github/workflows/phase-kick.yml` | Turns relevant events into a scheduler run |
| `.github/scheduler/pick.sh` | Reconcile, then choose one of: stop / revise / fix / phase |
| `.github/scheduler/prepare.sh` | Branch, brief, review digest, and `TASK.md` per mode, for Claude |
| `.github/scheduler/publish.sh` | Verify the bundle, push, open the PR or comment, or park |
| `.github/workflows/claude.yml` | Interactive `@claude` on Issues and PRs (separate path) |
| `.github/workflows/claude-code-review.yml` | Automated review, whose findings feed revision passes |
| `docs/phases/*.md` | The specs; `docs/phases/README.md` has the roadmap and "your part" table |

**Trace — a phase from start to merge:**
1. Nightly cron, or a kick, runs `pick` → nothing blocked, no open PR, no `fix` Issues → the next phase is `#N` → label `phase:in-progress`.
2. `implement`: checks out `feature/secure-leaf-mvp1` → `auto/issue-N` → `.phase-run/TASK.md` → Claude builds, tests, writes the note, commits → bundle.
3. `publish`: fetch the bundle → refuse if `.github/` was touched → force-push → `gh pr create` ("Closes #N").
4. CI and the Claude review run → the kick fires → `pick` sees review findings → a **revise** run pushes fixes to the same PR.
5. I review. My inline comments or `/revise` trigger another revise, otherwise I merge → "Closes #N" closes the Issue → the kick fires → `pick` starts the next item.

---

## 6. Design decisions and trade-offs

### Decision: the workflow pushes and opens PRs, not Claude
- **Alternatives:** let Claude push with the Claude GitHub App token and run `gh pr create`.
- **Why this:** the App token is revoked when the action ends, and the action deliberately doesn't open PRs. Plain `git` and `gh` steps are predictable, and they let the write credential live on a separate runner (§3.5).
- **Cost:** Claude can't react to a failed push. Publishing failures park the item instead.

### Decision: GitHub Issues as the queue
- **Alternatives:** a checklist file in the repo, or a JSON queue.
- **Why this:** labels, comments (where Claude's questions go), PR links and `Closes #N` come for free, and I can see and edit them on GitHub.

### Decision: one automatic pass for bot review findings, three per round for mine
- **Why:** my feedback is authoritative. The bot's is advisory, and it can generate new findings on every revision.
- **Revisit when:** the review bot's precision is measured to be high.

### Decision: `phase:blocked` stops *everything*, not just that item
- **Why:** phases are sequential. Skipping a blocked phase would build later phases on a hole.
- **Cost:** one unanswered question pauses the pipeline. That's intentional.

---

## 7. Interview questions

### Beginner
**Q: What's a cron expression, and what timezone does GitHub use?**
A: Five fields: minute, hour, day of month, month, day of week. `30 8 * * *` is 08:30 every day. GitHub Actions runs it in UTC, so 14:00 IST becomes 08:30 UTC. Watch out for the date too: 03:00 IST is 21:30 UTC on the *previous* day.

**Q: Why not let the bot merge its own PRs when CI is green?**
A: Green CI proves it didn't break the tests; it doesn't prove the design is right. Each phase builds on the last, so a bad merge spreads. The merge is the human quality gate.

### Intermediate
**Q: My scheduled workflow never runs. Why?**
A: Schedules only run from the default branch's copy of the workflow. Either put the file there or change the default branch.

**Q: A PR opened by the workflow didn't trigger CI. Why?**
A: It was opened with `GITHUB_TOKEN`. Events caused by that token don't trigger other workflows, to prevent infinite loops. Use a PAT or an App token for the PR. `workflow_dispatch` is an allowed exception, which is how our kick workflow works.

**Q: How do you make sure the next phase doesn't start while the previous one still has problems?**
A: A strict priority order checked on every run: blocked stops everything, an open PR gets revised or waited on, open `fix` Issues come next, and only then a new phase. There's also a `concurrency` group so two runs never overlap.

### Advanced / follow-up probes
**Q: The AI in your pipeline has a shell. How do you stop it from pushing to `main` or leaking your token?**
A: Privilege separation across machines. The job where the AI runs has a read-only token and no credential in the git config. Its output leaves as a git bundle artifact. A separate job on a fresh runner, which never runs AI code, holds the write token, verifies the bundle (for example, no `.github/` changes) and pushes. Same principle as keeping build and deploy credentials in different stages.

**Q: It's a public repo. Anyone can open an Issue. What's the risk?**
A: Prompt injection: a stranger writes instructions and the AI follows them. We only pick Issues authored by the owner, only react to the owner's comments, and only count the owner's and the review bot's feedback.

**Q: Why reconcile state on every run instead of reacting to events?**
A: Events are lossy: workflows crash, people act by hand. Reconciliation reads the current truth and converges on it, so a missed event only costs a delay. The system heals itself; that's the controller pattern Kubernetes uses.

**Q: How do you stop two bots from looping, a reviewer and a fixer?**
A: Bound it. Bot findings get one automatic pass per PR, and my feedback gets three per round. After that the item is blocked for a human. Any automation that reacts to its own side effects needs a termination condition.

**Q: Your autonomous agent exits successfully but did nothing. How do you make that impossible?**
A: Stop trusting the exit code and define "done" as something checkable. Here that's a result file that must exist. After each run a check looks for it. If it's missing, we resume the same session with its full context and tell it to continue, up to a bound. If it's still missing, the partial work is saved and a human is paged by labelling the Issue blocked. It's the same idea as a health check that tests real behaviour instead of "the process is up".

### "Tell me about a bug you fixed"
**Q: Tell me about a subtle bug in automation you built.**
A: The scheduler decides whether a PR needs revision by looking for comments from me. I'd set the publish step to use my personal access token, so the PR would trigger CI. But the same token was also used to post the bot's status comments, so those comments were authored by *me*. The scheduler would then have read its own status comments as my feedback, and looped until the revision limit. I found it by tracing "who is the author of each comment?" through every step. Fix: robot comments use `GITHUB_TOKEN` (they appear as `github-actions[bot]`); only the push and PR creation use the PAT. Lesson: in any system that reacts to events, know exactly which identity causes each event.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| (design) cron would never fire | Schedules run only from the default branch, which was `main` at the initial commit | Default branch → `feature/secure-leaf-mvp1` | Check where GitHub reads a workflow from for each event type |
| (design) Issues wouldn't close on merge | Closing keywords only work for the default branch | Reconcile step (also fixed by the branch switch) | Don't rely on conventions outside their scope |
| (design) scheduler would read its own comments as my feedback | Comments posted with my PAT are authored by me | Comments use `GITHUB_TOKEN`; the PAT only pushes and creates the PR | Know which identity causes each event |
| (design) a large PR's review JSON would crash `jq --argjson` | Linux caps a *single* argument at 128 KB (`MAX_ARG_STRLEN`) | Write the JSON to temp files and use `jq --slurpfile` | Pass data through files or stdin, not argv |
| (design) the Claude job could tamper with a later step's script | Everything on one runner is shared; later steps may get secrets | Publish on a separate runner from a bundle | Isolate by machine, not by step |
| **(first real run)** Phase 05A parked after 3 minutes: "Claude finished without making any changes" | Claude read the task for ~70 s (24 turns), then *ended its turn*, most likely after presenting a plan. In unattended `-p` mode, ending the turn ends the run; nobody was there to say "go ahead". The transcript was lost because the action hides Claude's messages by default | (1) The prompt now says explicitly that ending the turn ends the run and that no one will approve a plan. (2) A "finished" check: if neither the result file nor `BLOCKED.md` exists, the **same session is resumed** (`--resume <session_id>`) up to twice. (3) Transcripts are uploaded as an artifact and the report goes to the run summary. (4) Unfinished work goes to the branch and is parked, never opened as a PR | Define "done" as an artifact you can check (a file), not as "the process exited 0". And always keep the logs of an autonomous agent: you can't debug what you didn't record |
| **(second real run)** Claude finished Phase 05A (10 commits), then publish failed: `Permission to … denied to chandiran-v` (HTTP 403) | The PAT authenticated fine but lacked **Contents: write** on this repository. A fine-grained token only has the permissions and repositories you tick | Owner fixes the token scopes, then uses **Re-run failed jobs**. The artifact-based design means the 40 minutes of work is re-published, not redone | Separating "produce" from "publish" doesn't just isolate credentials: it makes the expensive step *replayable*. Check a credential's scopes, not just that it authenticates |
| (found while recovering) Unblocking a failed-publish Issue re-blocked it, and a hand re-run could have made the scheduler overwrite the new PR | Reconcile treated "in progress + no PR" as a crash even while the owner was still deciding. It also never repaired the reverse drift: "open PR, but label missing" | Leave blocked items untouched; after an unblock with no PR, reset and redo. Reconcile also restores `in-progress` on any Issue with an open `auto/issue-N` PR | Reconciliation must cover *every* direction of drift, including states created by manual recovery |
| `./mvnw` "permission denied" risk | `backend/mvnw` was committed as `100644` (from Windows) | `sh ./mvnw` everywhere | File modes survive git; Windows commits often lose `+x` |
| (design) scheduler PRs untested | CI only ran on PRs to `main`/`develop` | Added `feature/**` | Automation is only as good as the gate after it |

The `jq` filters in `pick.sh` were unit-tested locally against fabricated PR/review JSON before first use (owner-feedback count, ignoring the Claude review check, attempts since the owner's last comment, lowest-numbered `fix` Issue).

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Cron expression | Five-field schedule (min hour dom month dow); UTC in Actions |
| Default branch | The branch GitHub treats as "the repo": schedules run from it, and closing keywords apply to it |
| `GITHUB_TOKEN` | Per-run token GitHub creates; events it causes don't trigger other workflows |
| PAT | Personal Access Token: acts as you, so its events do trigger workflows |
| Concurrency group | Actions setting that makes runs with the same key wait for each other |
| Reconciliation loop | Repeatedly compare actual vs desired state and fix the difference |
| Privilege separation | Split work so the component handling untrusted input never holds the powerful credential |
| Git bundle | A single file containing commits, used to move them between machines without a remote |
| Prompt injection | Untrusted text steering an AI into actions its operator didn't intend |
| WIP limit | Kanban rule capping how many items are in progress (here: one) |

---

## 10. If I had to defend this in a code review

- **Strong:** the sequencing rule is enforced by code, not by discipline. Nothing new starts while anything is open.
- **Strong:** privilege separation. The AI's runner never holds a write credential, and `.github/` changes are refused.
- **Strong:** self-healing through reconciliation, and bounded retries that end at a human.
- **Weakest point:** Claude gets an unrestricted `Bash` tool inside its (read-only) job. The blast radius is small, but an allowlist (`Bash(sh ./mvnw:*)`, `Bash(npm:*)`, `Bash(git commit:*)` …) would be tighter. I'd tighten it once the phases show which commands they really use. Second weakest: the whole pipeline pauses on one blocked item. That is intentional, but it means I have to be responsive.
