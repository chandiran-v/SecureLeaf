# SecureLeaf — Phase Roadmap

Each phase has a **spec** in this folder and a matching **GitHub Issue** (labels `phase` + `mvp1`/`mvp2`).
The [Phase Scheduler](../../.github/workflows/phase-scheduler.yml) builds them **one at a time, in order**.
A spec is written so that an unattended run can follow it without asking questions. Every design
decision is numbered (D1, D2 …) and has an answer.

> Requirements live in [`../requirements.md`](../requirements.md). Specs reference requirement IDs; they don't restate them.

## MVP1 — target branch `feature/secure-leaf-mvp1`

| # | Spec | Requirement IDs | Learning note |
|---|---|---|---|
| 0–4 | *(done — see `docs/learning-notes/`)* | AUTH, UPLOAD, MARKET, PAY | phase-01 … phase-04 |
| 05A | [Secure viewer — backend](phase-05a-secure-viewer-backend.md) | VIEW-01..03, 10, 12, 13 | `phase-05-secure-viewer.md` |
| 05B | [Secure viewer — frontend](phase-05b-secure-viewer-frontend.md) | VIEW-04..09, 11 | `phase-05-secure-viewer.md` (extends) |
| 06 | [Library, creator dashboard, live notifications](phase-06-library-dashboard-notifications.md) | LIB-01..03, DASH-01..04, UPLOAD-09/10, NOTIF-01..04 | `phase-06-library-dashboard-notifications.md` |
| 07 | [Reviews & ratings + password reset](phase-07-reviews-password-reset.md) | REV-01..04, MARKET-03, AUTH-07 | `phase-07-reviews-password-reset.md` |
| 08 | [Admin panel](phase-08-admin-panel.md) | ADMIN-01..04, AUTH-08 | `phase-08-admin-panel.md` |
| 09 | [Hardening & release readiness](phase-09-hardening-release.md) | Non-functional requirements | `phase-09-hardening-release.md` |

## MVP2 — target branch `feature/secure-leaf-mvp2` (scale to 5,000 concurrent viewers)

MVP2 starts after MVP1 is released. The scheduler only picks `mvp2` Issues once you switch it over (see "Switching to MVP2" below).

| # | Spec | Requirement IDs | Learning note |
|---|---|---|---|
| 10 | [Observability & load-test harness](phase-10-observability-load-testing.md) | prerequisite for MVP2-01..07 | `phase-10-observability-load-testing.md` |
| 11 | [Per-buyer rate limiting](phase-11-rate-limiting.md) | MVP2-04 | `phase-11-rate-limiting.md` |
| 12 | [Decoupled rendering pool + backpressure](phase-12-render-pool-backpressure.md) | MVP2-03 | `phase-12-render-pool-backpressure.md` |
| 13 | [Watermarked tile cache](phase-13-watermarked-tile-cache.md) | MVP2-02 | `phase-13-watermarked-tile-cache.md` |
| 14 | [libvips watermark renderer](phase-14-libvips-renderer.md) ⚠️ needs your decision first | MVP2-01 | `phase-14-libvips-renderer.md` |
| 15 | [Full document versioning](phase-15-document-versioning.md) | MVP2-05 | `phase-15-document-versioning.md` |
| 16 | [Adaptive tile resolution](phase-16-adaptive-tile-resolution.md) | MVP2-06 | `phase-16-adaptive-tile-resolution.md` |
| 17 | [5,000-viewer capacity verification](phase-17-capacity-verification.md) | MVP2-07 | `phase-17-capacity-verification.md` |

---

## How the automation works

```
          ┌────────── every run does exactly ONE of these, in this priority ──────────┐
 cron /   │ 1. something is phase:blocked     → stop, wait for you                     │
 kick  ──►│ 2. a PR is open                   → revise it if it has your feedback,     │
          │                                     failing CI or Claude-review findings;   │
          │                                     otherwise wait for you to merge         │
          │ 3. an open `fix` Issue exists     → fix it (own PR)                        │
          │ 4. otherwise                      → start the next phase (own PR)          │
          └────────────────────────────────────────────────────────────────────────────┘
```

**Rule: the next phase never starts while anything is open.** Anything open means an unmerged PR, unaddressed feedback, a `fix` Issue or a blocked item.

### Your part

| You want to… | Do this |
|---|---|
| Ask for changes on a phase PR | Leave **inline review comments**, or a comment containing **`/revise`** with instructions. A revision pass runs on the same branch. You get up to 3 automatic passes per round of feedback. |
| Report a problem found after merging | Open an Issue with labels **`fix`** + **`mvp1`**. It is fixed before the next phase starts. |
| Accept a phase | **Merge** the PR. The Issue closes and the next item starts on its own. |
| Answer Claude's questions | Reply on the Issue (or edit the spec), then **remove the `phase:blocked` label**. |
| Start right now instead of waiting | Actions → *Phase Scheduler* → *Run workflow* (optionally with an Issue number, or as a dry run). |
| Pause everything | Add `phase:blocked` to any open `mvp1` Issue. |

Comments that mention **`@claude`** go to the interactive `claude.yml` workflow instead, which is useful for quick questions on a PR.

### Switching to MVP2 (when MVP1 is released)
1. Merge `feature/secure-leaf-mvp1` into `main` (release).
2. Create `feature/secure-leaf-mvp2` from it, and make it the default branch.
3. In `.github/workflows/phase-scheduler.yml`, set `TARGET_BRANCH: feature/secure-leaf-mvp2` and `MILESTONE_LABEL: mvp2`. Automated runs are not allowed to edit `.github/`, so you make this change yourself.

## Writing a new spec
Copy an existing one. Keep the same sections: **Context, Scope, Decisions, Backend, Frontend, Acceptance criteria, Out of scope, Learning note.** Put any open question in the spec as a numbered decision with an answer. The run cannot ask you mid-way; if it has to, it stops and blocks.
