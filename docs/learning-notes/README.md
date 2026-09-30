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
8. **[Phase 08 — Admin panel](phase-08-admin-panel.md)**
   - Covers: RBAC vs. object-level authorization, privilege-escalation surface and config-only admin bootstrap, revoking a stateless JWT immediately (a Redis deny-list vs. `jti`-keyed alternatives), fail-open vs. fail-closed, post- vs. pre-moderation, append-only audit logs enforced by a DB trigger, aggregate dashboard queries.
9. **[Phase 09 — Hardening & release readiness](phase-09-hardening-release.md)** — MVP1 complete
   - Covers: correlation IDs + MDC propagation across `@Async` threads, structured JSON logs and what never to log, HTTP security headers (CSP/HSTS/Permissions-Policy), GraphQL depth/complexity limiting + introspection, credential-stuffing throttling, fail-fast production config, liveness vs. readiness, the testing pyramid and where E2E fits, requirements traceability.

10. **[Phase 09B — Real Razorpay integration (test mode)](phase-09b-razorpay.md)** — Launch track
   - Covers: ports-and-adapters paying off (mock → real with no business-logic change), calling an HTTP API without an SDK and testing it with `MockRestServiceServer`, test vs live keys and the fail-fast live-key guard, authorize vs capture, three idempotent completion paths (browser, webhook, reconciliation), refunds as state transitions, who absorbs gateway fees, CSP for third-party checkout.

11. **[Phase 09C — Creator payouts, receipts & legal pages](phase-09c-payouts-legal.md)** — Launch track
   - Covers: ledger thinking (balance derived from events, never stored), hold periods vs. the refund window, preventing double payouts with a row lock + partial unique index and a two-thread test, state machines for money, adding a Postgres enum value safely, CSV injection, money as integers down to the form input, why marketplaces need Terms/Privacy/Refund pages, and scoping global CSS (the site-wide print bug).

12. **[Phase 09D — Production on one Oracle server](phase-09d-production-single-server.md)** — Launch track
   - Covers: capacity planning from measured numbers, the single-server trade-off, reverse proxy + automatic TLS + same-origin (no CORS), container memory limits vs JVM heap, the two-firewall trap on Oracle, defense-in-depth hardening, health-gated deploy with rollback, 3-2-1 backups and restore drills, ARM64 images, and the fonts-in-slim-images production bug.

13. **[Phase 10 — Observability & load-test harness](phase-10-observability-load-testing.md)** — MVP2 foundation
   - Covers: the three pillars, percentiles vs averages and histograms, RED/USE, load vs stress vs soak, Little's Law applied to the Tomcat pool, coordinated omission, metric cardinality, Micrometer/Prometheus/Grafana, a private management port, idempotent test-data seeding, "measure before you optimise".

14. **[Phase 11 — Per-buyer rate limiting](phase-11-rate-limiting.md)** — MVP2-04
   - Covers: token bucket vs fixed window / sliding log / leaky bucket, distributed limiting and CAS atomicity in Redis, 429 + `Retry-After`, trusting `X-Forwarded-For`, fail-open vs fail-closed, rate limiting as a DRM control, an abuse signal from access logs.

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
| 8 | [Admin panel](phase-08-admin-panel.md) | ✅ Done | RBAC vs. object-level authz, privilege-escalation surface, config-only bootstrap, Redis deny-list for immediate JWT revocation, fail-open vs. fail-closed, post-moderation, append-only audit log (DB trigger), aggregate dashboard queries |
| 9 | [Hardening & release readiness](phase-09-hardening-release.md) | ✅ Done | Correlation IDs + MDC across `@Async` threads, structured JSON logs, security headers, GraphQL depth/complexity limiting + introspection, credential-stuffing throttling, fail-fast prod config, liveness vs. readiness, testing pyramid + E2E, requirements traceability |
| 9B | [Real Razorpay (test mode)](phase-09b-razorpay.md) | ✅ Done | Ports & adapters, `RestClient` + `MockRestServiceServer`, test vs live key guard, authorize vs capture, reconciliation as a third idempotent path, refunds as state transitions, gateway-fee accounting, CSP for third-party checkout |
| 9C | [Creator payouts, receipts & legal pages](phase-09c-payouts-legal.md) | ✅ Done | Ledger/derived balances, hold period vs. refund window, `FOR UPDATE` + partial unique index against double payouts, payout state machine + audit, `ALTER TYPE ADD VALUE` in its own migration, CSV injection, paise-only money, legal pages as data, scoped print CSS |
| 9D | [Production on one Oracle server](phase-09d-production-single-server.md) | ✅ Done | Capacity planning from measurements, Caddy + auto-TLS + same-origin, `mem_limit` vs `MaxRAMPercentage`, two-firewall trap, hardening layers, health-gated rollback, 3-2-1 backups + restore drill, ARM64, fonts in slim images |
| 10 | [Observability & load-test harness](phase-10-observability-load-testing.md) | ✅ Done | Three pillars, percentiles + histograms, RED/USE, Little's Law on Tomcat threads, coordinated omission, cardinality, private management port, idempotent seeder, k6 thresholds |
| 11 | [Per-buyer rate limiting](phase-11-rate-limiting.md) | ✅ Done | Token bucket vs the alternatives, Bucket4j CAS in Redis, 429 + Retry-After, X-Forwarded-For trust, fail-open, scraper signal |
| 12 | [Decoupled render pool + backpressure](phase-12-render-pool-backpressure.md) | ✅ Done | Bulkhead, CPU- vs I/O-bound, virtual threads (and what they don't fix, pinning), bounded queues, 503 vs 429, timeout vs cancellation, Little's Law sizing |
| 13–17 | MVP2 — [roadmap](../phases/README.md) | ⏳ Queued | Caching, libvips, versioning, capacity |
| UI 1 | [ui-01-responsive-navigation.md](ui-01-responsive-navigation.md) | ✅ Done | Mobile-first breakpoints, accessible disclosure menu (`aria-expanded`), single source of truth for nav |
| Ops 1 | [ops-01-phase-scheduler.md](ops-01-phase-scheduler.md) | ✅ Done | Cron + event-driven CI, WIP limit of one, reconciliation, privilege separation, `GITHUB_TOKEN` vs PAT, prompt injection, bounded retries |

---

## MVP1 complete

Phase 9 was the last MVP1 phase. Every requirement id in `docs/requirements.md`'s MVP1 scope now
has a status and evidence entry in **[`docs/release-mvp1.md`](../release-mvp1.md)** — the
requirements traceability matrix Phase 9 produced (D10) — along with the release checklist the
owner follows to tag and deploy. `docs/deployment.md` (rewritten in Phase 09D) is the Oracle-server
runbook for actually shipping it.

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
