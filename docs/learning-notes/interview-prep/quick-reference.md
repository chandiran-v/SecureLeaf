# SecureLeaf — Interview Quick Reference

> The cram sheet. One page per phase, growing as the project grows. Read `../phase-XX-*.md` for the depth behind each line.

---

## The 60-second project pitch

> "SecureLeaf is a DRM-protected marketplace for digital documents. Creators upload PDFs; buyers purchase and read them inside a secure browser viewer, never as a downloadable file. Server-side, each PDF is converted into per-page image tiles by a PostgreSQL-backed async job queue. When a buyer views a page, the tile is fetched, a watermark carrying their identity is burned into it in-memory with Java2D, and it's served over a signed URL that expires in 30 seconds. The browser never receives a clean tile. It's Java 21 / Spring Boot 3 with GraphQL, PostgreSQL, Redis and MinIO, and a React + TypeScript frontend."

**If they ask "why is that hard?"** — three things: the browser must never hold an unwatermarked image, so watermarking happens per-request rather than at upload; tile serving has a sub-500ms budget with watermark burning on the hot path; and access is enforced at the level of individual records, not just roles.

**The honest framing of the DRM:** it is *traceable piracy deterrence*, not absolute prevention. No browser solution can stop a phone camera. Every leaked page carries a watermark identifying the buyer it came from. Same model as Kindle or Spotify. Saying this unprompted signals engineering maturity — claiming "uncrackable DRM" signals the opposite.

---

## MVP1 — the 60-second tour (Phase 9 finish line)

> Use this when someone asks "so is it actually production-ready?" — it's the tour of everything
> that makes MVP1 more than "the features work on my machine."

- **Trace any request:** every response carries `X-Correlation-Id`; every log line it produced —
  even from a background `@Async` job — carries the same id, via SLF4J MDC propagated onto pool
  threads by a `TaskDecorator`.
- **Logs are structured JSON in production** (`logstash-logback-encoder`, since this project pins
  Spring Boot 3.3.2, before Boot's own built-in structured-logging property existed), and a test
  fails the build if a log line ever hands SLF4J a raw password/token/secret by name.
- **Real security headers:** CSP, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
  `Permissions-Policy`, HSTS — on both the API (`SecurityConfig`) and the Vercel-hosted frontend
  (`vercel.json`).
- **GraphQL can't be turned into a DoS vector:** max query depth 10, max complexity 200 (aliasing
  the same field hundreds of times doesn't dodge the complexity cap), introspection off in prod.
- **Credential stuffing is throttled:** 5 failed logins per email+IP per 15 minutes, Redis
  `INCR`+`EXPIRE`, same recipe as Phase 7's password-reset limiter.
- **The app refuses to boot in prod with a leaked default:** JWT/DRM/Razorpay/MinIO secrets are all
  checked against their known dev values at startup; a match crashes the app immediately, naming
  every offender at once.
- **Liveness ≠ readiness:** `/actuator/health/readiness` checks DB+Redis specifically (not every
  auto-configured indicator — a broken SMTP connection doesn't pull a healthy instance out of
  rotation); `/actuator/health/liveness` just answers "is the process alive."
- **One test drives the whole system for real:** a Playwright test registers a creator, uploads a
  real PDF through the real async pipeline to LIVE, has a buyer buy it through the mock gateway,
  and asserts the viewer paints real, non-blank pixels on a real `<canvas>` — no mocked layer,
  anywhere, below the browser.
- **Every MVP1 requirement has a receipt:** `docs/release-mvp1.md` maps every requirement id from
  `docs/requirements.md` to the specific test or file:line proving it, honestly marking anything
  Partial or Deferred.

**The one-line honest gap:** the logging-hygiene test is a name-based check, not a type-aware one —
a secret in a variable spelled unexpectedly still slips through. Say this unprompted; it's the same
"know your own weak spot" signal as the DRM framing above.

---

## Phase 1 — Auth · [full note](../phase-01-auth.md)

| Concept | The one-line answer |
|---|---|
| Two tokens | 15-min JWT access (stateless, unrevocable) + 7-day DB-backed refresh (revocable) |
| BCrypt vs SHA-256 | Slow+salted for guessable human passwords; fast for 128-bit random tokens |
| Rotation | Refresh tokens are single-use; reuse = theft → revoke all sessions |
| 30s grace window | Multiple browser tabs racing a refresh aren't attackers |
| CSRF off | Safe *because* we use `Authorization` headers, not cookies |
| Google OAuth | Verify signature + issuer + **audience**; we never see a password |
| `ddl-auto: validate` | Flyway owns the schema; Hibernate only checks for drift |
| AuthN vs AuthZ vs BOLA | Who are you / what may you do / is *this record* yours |
| Expired token → 401, not silence | RFC 6750: `401` + `WWW-Authenticate: Bearer error="invalid_token"` + a code (`TOKEN_EXPIRED`). We used to send an empty 200 (bug) |
| One refresh for many 401s | Single-flight: share the in-flight refresh promise; each request retries once, else the session-expired modal |

**Weakest point to volunteer:** tokens in localStorage — XSS-exposed. Correct fix is httpOnly cookies + CSRF tokens.

---

## Phase 2 — Upload & async pipeline · [full note](../phase-02-upload-pipeline.md)

| Concept | The one-line answer |
|---|---|
| `FOR UPDATE SKIP LOCKED` | Lets many workers pull from one queue table without blocking or double-processing |
| Why Postgres, not Kafka | ~10 uploads/day doesn't justify brokers, partitions and Zookeeper ops |
| 202 Accepted | Upload returns immediately; the work happens async — don't hold an HTTP thread for 30s |
| Idempotent stages | Every stage is re-runnable, because retries replay it |
| Magic-byte validation | Trust the file's first bytes, never the client's `Content-Type` |
| `assertOwnership` | The object-level check `@PreAuthorize` can't do |

**Weakest point to volunteer:** the worker polling interval is fixed. Event-driven triggers like Postgres LISTEN/NOTIFY would be more responsive and efficient.

---

## Phase 3 — Marketplace · [full note](../phase-03-marketplace.md)

| Concept | The one-line answer |
|---|---|
| FTS predicate (verbatim) | `to_tsvector('english', title \|\| ' ' \|\| description) @@ plainto_tsquery('english', :q)` — must match the GIN expression index exactly or it silently seq-scans |
| Why native SQL, not Criteria/QueryDSL | Criteria may rewrite the FTS expression and drop the index silently; native SQL is honest here |
| The 3-query paging recipe | 1) `SELECT id ... LIMIT/OFFSET` (no join) 2) `COUNT(*)` same WHERE 3) `@EntityGraph findAllByIdIn(ids)` (no LIMIT) — then re-order in Java to step 1's order |
| `HHH000104` | Pagination + collection-fetch join in one query → Hibernate silently loads everything into memory to paginate there |
| `@SchemaMapping` snippet | `@SchemaMapping(typeName = "Product", field = "thumbnailUrl")` — computed only if the client selects the field |
| Presigned thumbnails, never presigned tiles | Thumbnails are public marketing assets (1h TTL); tiles are protected content — always streamed watermarked, never signed |
| 404 not 403 | Hidden/out-of-range resources return "not found," never "forbidden" — a 403 would confirm the resource exists |
| Sort whitelist | `sortBy` matched against a fixed `switch`, never interpolated into SQL — closes the injection door even in hand-built SQL |

**Weakest point to volunteer:** the free-preview endpoint is public and does real CPU work (watermark render) per request with only a page-range check as protection — no rate limiting yet (deferred to MVP-2), and the optional read-through cache wasn't built in this pass.

---

## Phase 4 — Commerce · [full note](../phase-04-commerce.md)

| Concept | The one-line answer |
|---|---|
| Order vs payment vs entitlement | Intent / money movement / access grant — separate so a failed payment never touches access and a free product needs no payment |
| Double-click → one order | 3 layers: client idempotency key (UUID per page visit) → reuse open PENDING order → `FOR UPDATE` on the buyer row makes check-then-act atomic |
| Browser + webhook race | Both lock the order row; the second sees COMPLETED and no-ops. One `applyCapture` path |
| Lock *first*, then read | Loading then locking returns Hibernate's cached, stale object — check state only on the locked row |
| Webhook = source of truth | The browser can close/time out; the webhook is server-to-server and retried until 2xx |
| At-least-once → effectively-once | Dedup by `provider_event_id` (UNIQUE) + idempotent transitions; return 2xx on duplicates |
| HMAC, not SHA-256 | A plain hash proves integrity; only a keyed hash proves origin. Sign the **raw bytes**; compare with `MessageDigest.isEqual` |
| State machine | `canTransitionTo` on the enum; status has no setter, only `transitionTo`; decline ≠ failed order |
| Append-only audit | Every transition writes `payment_events`; a DB trigger rejects UPDATE/DELETE |
| `total_sales + 1` in SQL | Avoids lost updates *and* `@Version` conflicts that would roll back a paid purchase |
| Money | Paise integers; fee in basis points, round half-up; earnings = price − fee; snapshot the split per order item |
| DB backstop | `UNIQUE (buyer_id, product_id) WHERE status='ACTIVE'` — holds even if Java is wrong |
| AFTER_COMMIT + @Async | Never email about a purchase that rolled back; slow SMTP never blocks checkout |
| Timeout ≠ failure | Money may be taken — tell the buyer not to pay again, poll, let the webhook decide |
| Mock can't reach prod | Prod provider = `razorpay`; no implementation → app refuses to start (fail fast) |

**Weakest point to volunteer:** no reconciliation job — a webhook lost beyond the provider's retry window leaves a paid order PENDING. Fix: a scheduled sweep that asks the gateway about stale PENDING orders and feeds captures through the same idempotent path. Also: emails can be lost on a crash between commit and send (fix: Transactional Outbox).

---

## Phase 05A — Secure viewer (backend) · [full note](../phase-05-secure-viewer.md)

| Concept | The one-line answer |
|---|---|
| The honest DRM pitch | Traceable deterrence, not prevention — screen recording always works; every leaked page is watermarked back to the buyer |
| Why not a presigned MinIO URL | It points straight at the CLEAN tile; our signed URL points at our own controller, which watermarks on every hit |
| `SET...GET` vs `SET NX` | Last-writer-wins for the one-active-session pointer (a new login must evict the old); first-writer-wins for single-use tile signatures |
| Signature payload | `HMAC-SHA256(secret, "{sessionId}\|{pageNumber}\|{userId}\|{exp}")` — `userId` baked in, so "valid" and "yours" are one check |
| Lease + heartbeat | Client pings every 15s; each ping atomically (Lua script) refreshes a 45s Redis TTL only if it's still that session's key |
| Who writes `ended_at` | Exactly one place per reason: `startViewerSession`'s takeover writes SUPERSEDED; the 60s sweeper writes EXPIRED; heartbeat only *reports*, never writes |
| D6 check order | JWT (401) → signature+owner (403) → single-use (403) → active session (409/410) → entitlement ACTIVE, re-read fresh (403) → page range (404) |
| Why re-check entitlement per tile | A mid-session revoke must take effect on the next page turn, not at the next login |
| Constant-time compare | Same reasoning as Phase 4's `RazorpaySignatures` — `MessageDigest.isEqual`, not `String.equals` |
| Access log timing | Synchronous, in-transaction (unlike Phase 4's AFTER_COMMIT notifications) — no rollback risk here, and it's on the hot path either way |
| Failsafe gap | `mvn verify` had never actually run any `*IT.java` in the whole project (no plugin bound); fixing it surfaced 3 unrelated pre-existing test bugs |

**Weakest point to volunteer:** the active-session check depends on Redis with no defined fallback if it's briefly unreachable — a timeout surfaces as an error rather than a graceful degraded mode. Also: `viewer_access_logs` is append-only by convention, not enforced by a DB trigger the way `payment_events` is.

---

## Phase 05B — Secure viewer (frontend) · [full note](../phase-05-secure-viewer.md)

| Concept | The one-line answer |
|---|---|
| Canvas, never `<img>` | An `<img src>` is a fetchable resource ("Save image as…"); a `<canvas>` is just painted pixels with nothing to save |
| The exact draw chain | `fetch(url, { headers: { Authorization } })` → `blob()` → `createImageBitmap()` → `ctx.drawImage()` → `bitmap.close()` |
| Why `createImageBitmap`, not `new Image()` | `new Image()` needs a `src` — usually a `blob:` object URL, itself a live reference to the raw bytes; `createImageBitmap` never creates one |
| `.close()` discipline | The *drawn* bitmap closes immediately; only *prefetched-but-undrawn* bitmaps are kept, capped at 3 and evicted-and-closed |
| `keepalive` fetch vs. `sendBeacon` | `sendBeacon` can't carry headers at all; ending a session needs a JWT, so it's a normal `fetch(url, { keepalive: true, headers })` instead |
| `pagehide` + unmount, not just one | `pagehide` catches a tab closing (no React cleanup runs); the `useEffect` cleanup catches an in-app navigation (no `pagehide` fires) |
| Never cache a signed URL | URLs are single-use and expire in ~30s; only the *decoded bitmap* of a prefetched page is worth keeping around |
| Derived, not stored, page clamping | `clampedPage = clampPage(currentPage, pageCount)` computed every render — a stored-then-reclamped value leaks one bad render (Gotcha) |
| One hook per friction control | 5 independent hooks (context menu, focus-loss blur, DevTools heuristic, print/save block, PrintScreen blank), each unit-tested alone |
| DevTools heuristic blurs, never ends the session | It's a guess (`outerWidth − innerWidth > 160px`) that both under- and over-detects — an action this reversible is all a guess should trigger |
| Apollo vs. Zustand | Apollo owns anything the server has an opinion about (the session, a signed URL); Zustand owns what only exists in this tab (current page, blur flags) |
| The honest DRM-control table | Every friction control's own note names what it stops *and* exactly how to beat it — see §3.13 |
| Zoom without new downloads (addendum) | CSS width = fit width × zoom; the canvas's pixel buffer is untouched, so no re-fetch or re-watermark. Soft above ~150–200% until Phase 16 adds bigger tiles |
| Ctrl + wheel needs `{ passive: false }` | React's `onWheel` is passive, so `preventDefault()` there is ignored and the whole tab would zoom |
| `m-auto`, not flex-centre, for zoomed content | Flex centring pushes overflow past the top/left, out of scroll reach; `margin: auto` doesn't |
| Clickable links on a picture (addendum) | Extract PDF link rectangles while processing, store as fractions, overlay invisible `<a>`/buttons in percent. Old uploads get a backfill |
| Untrusted links | Allowlist http/https/mailto on server **and** client; `rel="noopener noreferrer"` against reverse tabnabbing; cap counts and lengths |

**Weakest point to volunteer:** a generic tile-fetch failure just shows "Couldn't load this page / Retry" with no detail surfaced in the UI (the backend's `X-Correlation-Id` isn't displayed anywhere yet); and there's no push/subscription for "you've been taken over" — the first laptop only finds out on its next heartbeat or next page turn.

---

## Ops 1 — Phase Scheduler (CI automation) · [full note](../ops-01-phase-scheduler.md)

- **What:** GitHub Actions works through spec'd phase Issues **one at a time**. Each run does one move, in priority order: blocked → stop; open PR → revise (on my feedback, failing CI, or review findings) or wait for merge; open `fix` Issue → fix; otherwise → next phase. Nothing is ever auto-merged.
- **Cron runs only from the default branch.** `Closes #N` also only works there.
- **Privilege separation:** Claude runs in a read-only job; its commits leave as a **git bundle**; a separate runner with the write token verifies and pushes.
- **Reconciliation, not events:** every run repairs label/PR drift. A cron safety net sits behind the event-driven kicks.
- **`GITHUB_TOKEN` events don't trigger workflows** (except `workflow_dispatch`). Know which identity causes each event: robot comments posted with *my* PAT would look like *my* feedback.
- **Bounded automation:** 3 revision passes per round of my feedback, 1 for bot findings, then a human decides.

**Weakest point to volunteer:** unrestricted `Bash` for Claude (inside a read-only job). I'd tighten it to an allowlist once real usage is known.

---

## Phase 6 — Library, dashboard, live notifications · [full note](../phase-06-library-dashboard-notifications.md)

| Concept | The one-line answer |
|---|---|
| SSE, not WebSocket | Delivery is server → client only; SSE is plain HTTP with browser-managed reconnect, no bidirectional channel needed |
| One-time ticket, not the JWT | `EventSource` can't send headers; a 30s, single-use Redis `GETDEL` ticket shrinks a leak's blast radius near zero vs. a 15-minute JWT in a URL |
| Fan-out across instances | Every instance subscribes to `notifications:user:*`; only the instance actually holding that user's open tab acts on a message |
| At-most-once, and that's fine | Pub/Sub drops messages to offline subscribers; the DB row (written before publish) is the real source of truth, so a missed push just means "found out on the next query" |
| Re-read before push | The SSE listener re-fetches the notification by id instead of trusting the Redis payload — one mapping, can't drift |
| Shared named `DataLoader` | `salesCount`/`netEarningsPaise` pull the *same* named loader via `DataFetchingEnvironment`, so one aggregate query serves both fields, not one each |
| Why not `@RequestScope` for that cache | graphql-java can resolve independent fields on different threads; a `ThreadLocal`-backed bean isn't safely shared across that, `DataLoaderRegistry` is |
| Guarded transitions | `retryProcessing`/`republishProduct` check the exact starting status first; anything else is a typed `INVALID_STATE_TRANSITION`, never a silent no-op |
| Retry reuses the poller | `retryProcessing` resets the job to QUEUED and lets the existing 5s `@Scheduled` poll pick it up — one code path for "a job is ready," not two |
| Soft delete re-verified | `myLibrary` widened to every entitlement status, independent of the product's own status/`deleted_at` — proven end to end with a dedicated IT, not assumed |
| `@BatchMapping` with nullable values | Return `Map<key, value>`, not `List`: a List becomes a Reactor `Flux`, and a Flux can't hold `null`. That bug broke `processingStage`/`failureReason` on every product |

**Weakest point to volunteer:** `SseEmitterRegistry` has no per-user connection cap or total ceiling — nothing stops one user opening the stream hundreds of times. Fine for MVP load, first thing to add before real adversarial traffic.

---

## Phase 7 — Reviews & ratings + password reset · [full note](../phase-07-reviews-password-reset.md)

| Concept | The one-line answer |
|---|---|
| The three-step lock recipe | `SELECT ... FOR UPDATE` the product row → write the review → recompute `average_rating`/`review_count` from `reviews`, all in one transaction |
| Why a lock, not `@Version` | Optimistic locking would fail one of two concurrent `submitReview` calls for no reason a buyer would understand; the pessimistic lock avoids the conflict instead of rejecting it after the fact |
| Recompute, not increment | `AVG`/`COUNT` re-derived from source rows every time — correct for edits and deletes, not just new reviews |
| Why not `SERIALIZABLE` | Correct, but Postgres aborts one of two conflicting transactions under load — a retry storm, not a fix |
| The privacy fix | `Review.buyer: User!` (leaks email) → `Review.reviewer: ReviewerSummary!` (name only, no id) — closed at the *type* level, provable with a schema-validation test |
| Enumeration safety | `requestPasswordReset` always returns `true`; the frontend shows the identical message on success and on error |
| Why hash the reset token | Same reasoning as refresh tokens (Phase 1) — a DB leak yields useless hashes, not working reset links |
| Single-use | Token fields cleared the instant a reset succeeds — a replayed link finds nothing |
| One error for every token failure | Missing/expired/used all collapse into `INVALID_TOKEN` — doesn't tell an attacker which is true |
| Session revocation on reset | `revokeAllForUser` (built for refresh-token reuse detection, Phase 1) is reused here — a reset actually kills a stolen session, not just future logins |
| Redis rate limit | `INCR` (atomic; `1` on a fresh key answers "is this the first request" with no separate check) + `EXPIRE` only on that first call — 3/email/hour |
| Injectable `Clock` | Expiry checked against `Instant.now(clock)`, same pattern as `TileUrlSigner` (Phase 5) — unit-testable with a fixed "now" |
| Accessible star input | ARIA `radiogroup`/`radio` with roving tabindex — arrow keys move *and* select, matching the native radio-group pattern |

**Weakest point to volunteer:** `resetPassword` revokes refresh tokens but not the still-live 15-minute access token a stolen session might hold — for up to 15 minutes after a "successful" reset, that old session still works. Fully closing it needs a server-side access-token blocklist or a shorter token lifetime; deferred as an accepted, time-bounded gap.

---

## Phase 8 — Admin panel · [full note](../phase-08-admin-panel.md)

| Concept | The one-line answer |
|---|---|
| No `grantAdmin` mutation, ever | ADMIN comes only from `ADMIN_EMAILS` config + a restart/registration — removes the whole "can a bug let someone self-promote" question from the app's own code |
| RBAC vs. object-level | `hasRole('ADMIN')` gates the operation; a separate in-service check stops an admin suspending themselves or another admin — role alone can't express that |
| Revoking a stateless JWT | Redis set `auth:suspended`, keyed by user id (not by token `jti`) — one entry per suspended user, O(1) `SISMEMBER`, self-cleans on reactivation |
| Fail-open here, fail-closed elsewhere | The Redis check is a redundant, latency-focused layer on top of a DB `account_status` check that's enforced regardless — failing closed would turn a Redis blip into a platform-wide outage for a non-load-bearing check |
| Post- not pre-moderation | Products go LIVE automatically after processing (Phase 2); admins can only take one *down* after the fact, never approve one before |
| Reused `UNPUBLISHED`, not a new status | `taken_down_at`/`takedown_reason` (paired by a CHECK constraint) distinguish an admin takedown from a creator's own unpublish — every existing status-keyed rule stays correct for free |
| Append-only audit log | Same DB-trigger pattern as Phase 4's `payment_events` — `admin_actions` rejects UPDATE/DELETE structurally, not by convention |
| `Propagation.MANDATORY` on the audit write | Guarantees the audit row commits atomically with the change it describes — calling it outside a transaction is a bug that fails loudly |
| Aggregate queries for `platformStats` | Plain `COUNT`/`SUM`/`GROUP BY` SQL — Postgres computes totals over the whole `orders` table in one round trip, never loop-and-sum in the JVM |
| Bounded query count | `adminUsers` always runs the same handful of queries regardless of page size: id-page, count, one `@EntityGraph` fetch, two batched `GROUP BY` aggregates |

**Weakest point to volunteer:** the `auth:suspended` fail-open default is only safe because `JwtAuthenticationFilter` happens to *also* re-check `account_status` from the database on every request, for reasons that predate this phase. If that per-request DB lookup is ever optimized away, the Redis check silently becomes the only enforcement layer and its fail-open default should flip to fail-closed — a cross-cutting assumption documented in comments, not pinned by a test.

---

## UI 1 — Responsive navigation · [full note](../ui-01-responsive-navigation.md)

| Concept | The one-line answer |
|---|---|
| `hidden md:flex` | Mobile-first: hidden on phones, flex from 768 px. Every "hidden below X" needs a replacement below X |
| Accessible hamburger | A real `<button>`, text label, `aria-expanded`, `aria-controls`; closes on Escape / outside tap / navigation |
| One `navItems` list | Desktop and mobile menus render from the same role-based list, so they can't drift |

**Weakest point to volunteer:** no focus management (focus isn't moved into the panel or returned to the button).

---

## Phase 9 — Hardening & release readiness · [full note](../phase-09-hardening-release.md)

| Concept | The one-line answer |
|---|---|
| MDC across `@Async` | A `TaskDecorator` copies the calling thread's MDC onto the pool thread before the task runs, and restores it after — otherwise every async log line has no correlation id |
| Why JSON logs need an extra dependency here | Boot 3.4+ has `logging.structured.format.console: ecs` built in; this project pins 3.3.2, so `logstash-logback-encoder` does the same job directly |
| Complexity counts aliases separately | `{ a1: title a2: title ... }` 200 times doesn't dodge the complexity cap — each alias is its own field to graphql-java's default calculator |
| Introspection off, the built-in way | `spring.graphql.schema.introspection.enabled: false` — a real Boot property, no custom `GraphQlSourceBuilderCustomizer` bean needed |
| Why email+IP, not email alone, for login throttling | Email alone lets an attacker lock out a *victim* on purpose from a different IP — the anti-abuse feature becomes the weapon |
| Fail-fast naming every offender at once | `ProdSecretsConfig` collects every dev-default secret still set into one exception message, not one failed deploy per secret |
| Readiness scoped to `db`+`redis`, not everything | Boot auto-registers a `mail` health indicator too; a broken SMTP connection shouldn't pull a healthy instance out of rotation for *read* traffic |
| Proven live, not just configured | Manual verification actually saw plain `/actuator/health` report DOWN (broken mail) while the scoped `readiness` group correctly stayed UP — direct evidence the narrower scoping was the right call |
| `ReadinessIT` doesn't share the suite's Redis | It needs to permanently kill Redis mid-test — using the shared, never-stopped singleton container would break every later test in the run |
| The E2E bug that was in the test, not the app | Canvas defaults to 300×150 before anything is drawn — polling "width/height > 0" as a readiness signal is always true immediately; poll for a real non-blank pixel instead |
| Frontend Dockerfile bug found by trying to build it | `COPY ../nginx/nginx.conf` reached outside its `frontend/`-scoped build context — Docker can never do that; fixed by building from the repo root with prefixed COPY paths |
| `restClient`'s hardcoded `/api` | Works behind Vite's dev proxy or nginx's Docker-Compose proxy; breaks on Vercel+Render, genuinely different origins — needed a `VITE_API_URL` override, found only by trying to run the real E2E journey |
| `.github/workflows/` is off-limits to this phase | The E2E CI workflow ships as `docs/ci/e2e.yml.example` for the owner to copy in, not a live workflow |

**Weakest point to volunteer:** `LoggingHygieneTest` is a name-based static scan, not type-aware — a secret held in a variable spelled unexpectedly (not `password`/`token`/`secret` or a listed variant) slips through silently. The test's own doc comment says so.

---

## Phase 9B — Real Razorpay (test mode) · [full note](../phase-09b-razorpay.md)

| Concept | The one-line answer |
|---|---|
| Mock → real swap | A second implementation of the `PaymentGateway` port; order/entitlement logic gained features but needed no rewrite — ports & adapters paying off |
| Why no Razorpay SDK | Five endpoints, fewer dependencies on a small ARM box, and the exact HTTP request is assertable with `MockRestServiceServer` |
| Gateway errors | Every 4xx/5xx/timeout → one `PAYMENT_GATEWAY_UNAVAILABLE`; thrown inside the order transaction so no half-created order; 3 s connect / 10 s read timeouts |
| Test vs live | `rzp_test_` vs `rzp_live_` prefix; app refuses to start with a live key unless `payment.live-enabled=true`; mock refused in prod; blank secrets refused |
| Payment mode in the UI | Derived from provider + key prefix (`MOCK`/`TEST`/`LIVE`), so the banner can't disagree with the server |
| Authorize vs capture | `payment.authorized` → we call `capture` → `payment.captured` completes the order; works with or without auto-capture |
| Never HTTP inside a DB transaction | `recordAuthorization` is non-transactional so a slow gateway can't drain the connection pool |
| Three messengers, one path | Browser handler, webhook, reconciliation job all → `applyCapture` under the order row lock + "already COMPLETED?" check |
| Reconciliation | Every 10 min: PENDING orders > 15 min old → ask gateway; captured → complete; > 24 h with nothing → FAILED ("expired") |
| Refund = state transition | Order/payment `REFUNDED`, entitlement `REVOKED`, notification + email, `payment_events` + `admin_actions` rows; nothing deleted |
| Refund two entrances | Admin mutation and `refund.processed` webhook both go through `applyRefund`; replay is a no-op because the order is already `REFUNDED` |
| Gateway fees | Platform absorbs them; stored per payment; creators keep price − 10%; platform net = fee − gateway fee − GST; refunds don't return the fee |
| CSP for checkout | Name exact hosts: `checkout.razorpay.com` (script + frame), `api.razorpay.com` (connect) — no `unsafe-inline` |
| Known-answer crypto tests | Signature test vectors computed independently in Python so the code can't validate itself |

**Weakest point to volunteer:** fee lookup and refunds hold the order row lock across a gateway call (bounded by timeouts, deliberate for refunds); the fix is after-commit fee capture and an async `REFUND_PENDING` state. Also: nothing here has run against real Razorpay test mode from CI — only against request-shape tests and the mock.

---

## Phase 9C — Creator payouts, receipts & legal pages · [full note](../phase-09c-payouts-legal.md)

| Concept | The one-line answer |
|---|---|
| Balance | Derived on every read: cleared earnings (COMPLETED, older than 7 days) − payouts not REJECTED. Nothing stored, so it can't drift |
| Hold period | 7 days from `orders.completed_at`, equal to the refund window, so refundable money is never paid out |
| Double-payout protection | `SELECT … FOR UPDATE` on the creator's profile row before checking; partial unique index (one open request) as backstop; two-thread latch test |
| Snapshot | Payout destination and gross/fee copied onto the payout row at request time |
| Payout state machine | REQUESTED → APPROVED → PAID; REQUESTED/APPROVED → REJECTED; else `INVALID_STATE_TRANSITION`; each admin action = lock + guard + one audit row + one notification, one transaction |
| Rejected vs paid | Rejected stops being counted (money returns automatically); paid stays counted forever |
| Enum value | `ALTER TYPE … ADD VALUE` in its own migration — can't be used in the transaction that adds it |
| Statement/CSV | Same service feeds GraphQL and CSV; IST months; integer paise; quoted cells + leading `'` against formula injection |
| Owner-only CSV | `/api/creator/**` = CREATOR role, id from the JWT — nothing in the URL to tamper with |
| Receipt | Owner-only (someone else's order = NOT_FOUND), payment id masked to last 4 |
| Print bug | `body { display:none }` was site-wide; now `body.sl-reading`, class added/removed by `useReadingMode` on reader mount/unmount |
| Legal pages | Text as structured data (no `dangerouslySetInnerHTML`); draft banner until `VITE_LEGAL_REVIEWED=true`; privacy policy states exactly what is logged |
| Money in the UI | `"0.29"` → 29 by string maths, never `parseFloat * 100` |

**Weakest point to volunteer:** a refund after a payout leaves the creator owing money and the ledger only clamps "available" to zero — the fix is an explicit adjustment entry. Also, "paid" is the admin's word; the app can't verify the transfer, and the 7 days is configured in two places.

---

## Phase 9D — Production on one Oracle server · [full note](../phase-09d-production-single-server.md)

| Concept | The one-line answer |
|---|---|
| Why one Oracle box | Measured: JVM peaks ~620 MB, a watermarked page ~0.45 CPU-s. Free managed tiers (512 MB / 0.1 CPU) can't run it; 2 OCPU / 12 GB can, at ₹0 |
| Memory budget | backend 4 GB, postgres 1.5 GB, minio 1 GB, redis 384 MB, caddy 256 MB ≈ 7.1 GB + 2 GB swap; steady use stays above Oracle's 20% idle-reclaim floor |
| CPU budget | Processing pool = 1 so one upload at a time and readers keep a core |
| Heap vs container | `mem_limit` is the suitcase, heap the folder: `MaxRAMPercentage=70`; `ExitOnOutOfMemoryError` so Docker restarts a wedged JVM |
| Reverse proxy | Caddy is the only published service: TLS, static SPA (fallback to `index.html`), `/graphql` `/api` `/actuator/health` → backend, `flush_interval -1` for SSE, 60 MB body limit |
| Same-origin | One hostname ⇒ relative URLs ⇒ no CORS in production |
| Auto-TLS | Caddy gets/renews Let's Encrypt certs; needs 80/443 reachable; tests use `tls internal` |
| Two-firewall trap | VCN security list **and** host iptables (Oracle images REJECT all but 22): ACCEPT rules must sit above the REJECT, persisted with netfilter-persistent |
| Hardening layers | Only Caddy publishes ports · iptables · SSH keys only/no root · fail2ban · unattended-upgrades · non-root containers · generated secrets + fail-fast validator · CSP/HSTS |
| Deploy | build tagged with git SHA → `up -d` → wait backend healthy **and** public `/actuator/health` → on failure `up` with last good tag, exit 1 → keep last 3 images |
| Rollback target | The last tag that *passed* the gate (state file), not what's running — the running one may be the broken one; can't undo migrations |
| Backups | `pg_dump -Fc` + incremental `mc mirror` to Oracle Object Storage (S3 API); 7 daily + 4 weekly; nightly systemd timer; laptop copy via `download-backup.sh` |
| Restore drill | Record counts + object checksum → back up → `down -v` → restore → compare (`backup-restore-test.sh`). Untested backup ≠ backup |
| Free-limit maths | ~300 requests/month for the dump + one request per *new* object; 100-page doc ≈ 102 PUTs; 20 GB ≈ 75k tiles |
| ARM64 | Every image needs `linux/arm64`; images are built on the server so architecture always matches |
| Fonts bug | Slim images lack fonts ⇒ watermark fails or is blank **only in prod**; `WatermarkSmokeCheck` asserts pixels changed inside the built image |
| Sentry | Optional (DSN-gated); scrub emails/JWTs/`Bearer`/`?sig=` and drop user/cookies/headers before sending |
| Mail | Brevo STARTTLS 587, `MAIL_FROM` must be a verified sender; failures logged + counted, never break the flow; mail health indicator off so SMTP can't fail the uptime probe |
| Secrets | `generate-env.sh`: `openssl rand`, mode 600, git-ignored, refuses overwrite; prod refuses empty/<32-char/default values |

**Weakest point to volunteer:** one VM and nightly backups ⇒ up to a day of data loss and ~1 hour down; fix with WAL archiving/hourly dumps. Also root MinIO credentials in the app, images built on the serving box, and `setup-server.sh` only dry-run/shellchecked, never run on a real VM in CI.

---

## Phase 10 — Observability & load-test harness · [full note](../phase-10-observability-load-testing.md)

- **Measure before you optimise:** MVP2 phases are judged against a recorded baseline (`docs/perf/baseline-mvp1.md`), not opinion.
- **Percentiles, not averages:** p95/p99 show the tail; histograms (`publishPercentileHistogram`) make `histogram_quantile` possible and aggregate across instances.
- **RED for the endpoint, USE for the resources:** requests/s by outcome + duration; Tomcat threads busy vs max, heap, GC.
- **Little's Law:** busy threads ≈ throughput × latency — but a CPU-bound tile saturates cores long before 200 threads.
- **`Timer.Sample`** when the tag (`outcome`) is only known at the end; keep tags low-cardinality.
- **Actuator on a private port** (8081 in prod): network position, not a shared secret, protects the metrics; Caddy forwards only `/actuator/health`.
- **Load test hygiene:** think time + heartbeats model real readers, thresholds make it pass/fail, closed model under-reports at saturation (coordinated omission), label smoke runs "not a capacity result".
- **Seeders:** guard against prod, find-or-create by natural key, survive half-finished previous runs.

## Phase 11 — Per-buyer rate limiting · [full note](../phase-11-rate-limiting.md)

- **Token bucket:** capacity = burst, refill = sustained rate; refill computed lazily from the last timestamp, O(1) state. Beats fixed window (2× burst at the boundary) and sliding log (memory).
- **Distributed:** bucket state lives in Redis; Bucket4j's CAS (Lua) makes take-a-token atomic across instances — never GET-compare-SET yourself.
- **429 + `Retry-After`** (round up, never 0); GraphQL is always HTTP 200, so use `RATE_LIMITED` + `retryAfterSeconds` in extensions.
- **`X-Forwarded-For` is a header anyone can forge:** trust it only from a trusted-proxy CIDR, take the rightmost untrusted hop. Spring's `ForwardedHeaderFilter` strips it, so capture it first.
- **Fail open** for the limiter (protection, not correctness), **fail closed** for entitlement; back off after a failure so a dead Redis costs one timeout.
- **DRM angle:** limits the *speed* of ripping; with the watermark and access logs it makes ripping slow and traceable.
- **Test without sleeping:** inject a `TimeMeter`, wind the clock by hand.

## Phase 12 — Render pool + backpressure · [full note](../phase-12-render-pool-backpressure.md)

- **Bulkhead:** own bounded pool for the CPU-bound watermark so it can't starve login/heartbeats; proven by a test timing a heartbeat while saturated.
- **CPU-bound → platform threads ≈ cores; I/O-bound → virtual threads.** Virtual threads make *waiting* cheap, they don't add CPU. Pinning = blocking inside `synchronized` (Java 21); find it with JFR.
- **Bounded queue + AbortPolicy:** overload becomes a fast 503, not growing latency. Little's Law (L = λW) sizes it; the timeout is usually the real limit.
- **503 (we're overloaded) vs 429 (you're too fast)**, both with `Retry-After`; client retries 503 with jittered backoff, max twice.
- **`orTimeout` doesn't stop the work** — also `Future.cancel(true)`; non-interruptible code still runs on.
- **Async controller:** `CompletableFuture` return → ASYNC re-dispatch re-runs security; `@Transactional` needs a separate bean (proxy).

## Phase 13 — Watermarked tile cache · [full note](../phase-13-watermarked-tile-cache.md)

- **Cache the watermarked bytes, per buyer + session, server-side only** — never the clean tile; the browser still gets `no-store`.
- **Authorise first, then look up.** A cache in front of the checks is an auth bypass. Proof: revoke without evicting → still 403.
- **Key = sha256(user, session, version, page, variant, renderer, watermarkVersion).** Invalidate by changing the key (bump a version), plus TTL, plus best-effort `evictUser`.
- **Watermark made cache-stable:** minute timestamp → date + session id; forensic precision moved to the access log (joined on session id).
- **Disk by default:** 5,000 × 10 × 500 KB ≈ 25 GB won't fit RAM on a 12 GB box; disk read ≈ 1 ms; Redis for multi-instance.
- **Atomic file write:** temp file + `ATOMIC_MOVE`; unique file name per put; delete on eviction; wipe stale dir at startup.
- **Invalidate by identity, not key** (`remove(key, entry)`) — otherwise you delete the fresh replacement.
- **A hit still writes the access-log row** and still passes the rate limit.

## Phase 15 — Full document versioning · [full note](../phase-15-document-versioning.md)

- **Immutable versions + one pointer.** "Current" is `products.current_document_version_id`, moved in the same transaction that completes the job. Same idea as git refs / Docker tags. Never infer "current" by ORDER BY.
- **Buyers pin to a version.** The entitlement stores `document_version_id`; the viewer and cache read *that*, never "latest". That is what lets the creator choose per version.
- **A later version never disturbs the product.** v2 processing/failure leaves status and pointer alone; only the first version flips PROCESSING→LIVE/FAILED.
- **Backfill + test from the previous schema** (Flyway `target("11")`, seed, migrate, assert) — fresh-DB tests can't see a bad backfill.
- **Resumable batch:** chunks of 500, each its own transaction; checkpoint = "ACTIVE entitlements not yet on target"; stamp `entitlements_migrated_at` only when a chunk finds nothing; poller resumes; UPDATE re-checks `ACTIVE`.
- **Skip stale migrations:** if a newer version is already current, don't move buyers to an older one.
- **Retire, don't delete:** refuse if current, processing, or *any* entitlement references it; only then remove tile objects; rows stay (access-log FKs).
- **BOLA:** owner check on every creator action (upload, list, retire, retry) plus tests with a second creator.

## Cross-cutting themes to weave into any answer

1. **Threat-model each decision.** Every security choice here has a "what attack does this stop" answer. Say it.
2. **Name what you gave up.** Every choice has a cost; stating it is what separates a senior answer from a memorized one.
3. **Defense in depth.** Validate in the DTO, enforce in the service, constrain in the database.
4. **Design for concurrency.** The pessimistic lock, `SKIP LOCKED`, the grace window and Phase 4's order-row lock all exist because two things happen at once in production.
5. **Fail closed.** Errors deny access; they never grant it.
6. **Every message arrives twice, eventually.** Double-clicks, retries, webhook redelivery — make the second one harmless (idempotency) instead of hoping it won't come.
