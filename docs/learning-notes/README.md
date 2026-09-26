# SecureLeaf — Learning Notes

This folder contains the "Learning Notes" for each phase of the SecureLeaf project. As per our core requirements, every major feature or milestone must include one of these documents.

They serve a dual purpose:
1. **Onboarding:** Explaining the architectural decisions and "why" behind the code to new engineers.
2. **Interview Prep:** Breaking down the complex concepts, trade-offs, and best practices into easily digestible formats for technical interviews.

## Current Notes

1. **[Phase 01 — Authentication & Authorization](phase-01-auth.md)**
   - Covers: JWTs, Refresh Tokens, Next.js / Spring Boot security integration.
2. **[Phase 02 — Creator Upload & Async Processing](phase-02-upload-pipeline.md)**
   - Covers: BOLA, Magic Bytes, Async Pipeline, PostgreSQL `FOR UPDATE SKIP LOCKED`.
3. **[Phase 03 — Marketplace (with watermarked free preview)](phase-03-marketplace.md)**
   - Covers: Full-text search (`tsvector`/GIN), the N+1 query problem, two-step ID paging, DTO projection + field resolvers, the Strategy pattern.
4. **[Phase 04 — Commerce (orders → payments → entitlements)](phase-04-commerce.md)**
   - Covers: Idempotency keys, race conditions + pessimistic locking, state machines, append-only audit logs, webhooks + HMAC, Razorpay-shaped gateway, after-commit side effects. **~50 graded interview Q&As.**
5. **[Phase 05 — Secure viewer (backend + frontend)](phase-05-secure-viewer.md)**
   - **05A (backend):** the DRM threat model, HMAC-signed single-use tile URLs vs. presigned storage URLs, `SET NX` vs `SET...GET` in Redis, leases + heartbeats, constant-time comparison, append-only audit logs, defense in depth.
   - **05B (frontend):** canvas rendering vs. `<img>`, `createImageBitmap` + memory hygiene, `keepalive` fetch vs. `sendBeacon`, custom hooks as units of behaviour, Apollo vs. Zustand, and an honest table of what each piracy-friction control stops and how it's bypassed.
6. **[Phase 06 — Library, creator dashboard & live notifications](phase-06-library-dashboard-notifications.md)**
   - Covers: SSE vs. WebSocket vs. polling, one-time Redis tickets for a stream `EventSource` can't send a header on, Pub/Sub fan-out across instances and its at-most-once caveat, a named `DataLoader` shared across two GraphQL fields, guarded state transitions, soft delete re-verified end to end.
7. **[Phase 07 — Reviews & ratings + password reset](phase-07-reviews-password-reset.md)**
   - Covers: denormalized aggregates and the lost-update anomaly (pessimistic lock vs. incremental formula vs. nightly recompute vs. `SERIALIZABLE`), upserts, data minimisation (the `Review.buyer`→email leak caught before shipping), user enumeration, reset-token hashing + single use, session revocation on credential change, Redis `INCR`/`EXPIRE` rate limiting, an accessible ARIA `radiogroup` star input.

**Tooling / DevOps**

- **[Ops 01 — Phase Scheduler](ops-01-phase-scheduler.md)**
   - Covers: Cron + event-driven workflows, the default-branch rule, WIP-limited state machine over labels, reconciliation, privilege separation (AI job never holds the write token), `GITHUB_TOKEN` vs PAT, prompt injection, bounded retries.

## Process
When a new phase is complete, duplicate `TEMPLATE.md`, name it appropriately, and fill it out before closing the ticket.

---

## How to use this folder

| If you want to… | Read |
|---|---|
| Understand a phase in depth | `phase-XX-*.md` |
| Cram before an interview | `interview-prep/quick-reference.md` |
| Look up a term | `interview-prep/glossary.md` |
| Write a new phase note | Copy `TEMPLATE.md` |

---

## Index

| Phase | Note | Status | Core topics |
|---|---|---|---|
| 0 | *(schema design — covered in `../db_schema.md`)* | ✅ Done | Normalization, indexes, enums, constraints |
| 1 | [phase-01-auth.md](phase-01-auth.md) | ✅ Done | JWT, refresh token rotation, BCrypt, Spring Security filter chain, OAuth2, RBAC |
| 2 | [phase-02-upload-pipeline.md](phase-02-upload-pipeline.md) | ✅ Done | Async processing, job queues, `SKIP LOCKED`, object storage, thread pools, object-level authz |
| 3 | [phase-03-marketplace.md](phase-03-marketplace.md) | ✅ Done | Full-text search, two-step ID paging, N+1 queries, DTO projection, field resolvers, Strategy pattern |
| 4 | [phase-04-commerce.md](phase-04-commerce.md) | ✅ Done | Idempotency keys, check-then-act races, `FOR UPDATE`, state machines, append-only audit log, webhooks/HMAC, at-least-once delivery, AFTER_COMMIT events, `@BatchMapping` |
| 5 | Secure Viewer — [05A + 05B, one note](phase-05-secure-viewer.md) | ✅ Done | DRM threat model, HMAC-signed single-use URLs, leases/heartbeats, canvas rendering, `createImageBitmap`, `keepalive` vs `sendBeacon`, honest browser-DRM limits |
| 6 | [Library, dashboard, live notifications](phase-06-library-dashboard-notifications.md) | ✅ Done | SSE vs WebSocket vs polling, one-time Redis tickets, Pub/Sub fan-out + at-most-once caveat, a named `DataLoader` shared across two fields, guarded state transitions |
| 7 | [Reviews & ratings + password reset](phase-07-reviews-password-reset.md) | ✅ Done | Denormalized aggregates, the lost-update anomaly, pessimistic locking, upserts, data minimisation, user enumeration, single-use hashed tokens, session revocation, Redis rate limiting |
| 8–9 | [Admin](../phases/phase-08-admin-panel.md) · [Hardening](../phases/phase-09-hardening-release.md) | ⏳ Queued | See `docs/phases/README.md` |
| 10–17 | MVP2 — [roadmap](../phases/README.md) | ⏳ Queued | Observability, rate limiting, bulkheads, caching, libvips, versioning, capacity |
| Ops 1 | [ops-01-phase-scheduler.md](ops-01-phase-scheduler.md) | ✅ Done | Cron + event-driven CI, WIP limit of one, reconciliation, privilege separation, `GITHUB_TOKEN` vs PAT, prompt injection, bounded retries |

---

## The rule that keeps this folder alive

Every AI-assisted development session on this repo **must** update these notes as part of the work — not afterwards, not on request. That instruction lives in:

- **`/CLAUDE.md`** — read automatically by Claude Code in every session
- **`/GEMINI.md`** — read automatically by Gemini CLI in every session
- **`/.github/copilot-instructions.md`** — read automatically by GitHub Copilot
- **`/AI_RULES.md`** — the single source of truth all three point to

If you switch to another AI tool, point its config file at `AI_RULES.md` too. Do not copy the rules — copies drift.

---

## What makes a good note here

1. **Explain to a beginner, not to yourself.** If a term appears for the first time, define it in one sentence before using it.
2. **Always answer "why", not just "what".** "We hash refresh tokens" is trivia. "We hash refresh tokens so a database leak doesn't hand an attacker working sessions" is an interview answer.
3. **Name the alternative you rejected.** Interviewers probe trade-offs. Every design decision should record what else was on the table and why it lost.
4. **Include real code from this repo**, with file paths and line references — not invented examples.
5. **Write the interview Q&A as you go.** It is far harder to reconstruct months later.
